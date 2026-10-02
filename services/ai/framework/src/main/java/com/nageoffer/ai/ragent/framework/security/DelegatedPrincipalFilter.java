/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.framework.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 生产委托主体过滤器（U06/P1.2c）：对 {@code /internal/ai/v1/**} 把 Bearer 委托令牌
 * 变成不可伪造的 {@link ExecutionPrincipal}，并贯穿请求生命周期。
 *
 * <p>处理链（每一步失败即终止，错误映射沿用 P04 协议「HTTP status == body.code」）：
 * <ol>
 *   <li>Bearer 委托令牌验签（RS256、typ/kid/iss/aud 白名单、iat/exp/nbf 与 skew、
 *       TTL 上限）——平行实现 {@link DelegationVerifier} 的解析口径，<b>不改 p04 类</b>；
 *       缺/坏签名 → 401 {@code DELEGATION_INVALID}（未携带 → 401 {@code AUTH_REQUIRED}）；</li>
 *   <li>缺 {@code tid}/{@code mid}/{@code pv} → 403 {@code TENANT_CONTEXT_MISSING}；
 *       {@code mid} 与 canonical {@code platform:<tid>:<uid>} 不一致 → 403
 *       {@code MEMBERSHIP_INVALID}；</li>
 *   <li>{@link ProductionReplayGuard}（经 {@link ObjectProvider}）原子消费 jti：
 *       无 bean → 503 并记错误；返回 false（重复 jti）→ 401；消费抛异常 → 503——
 *       存储不可用<b>不得当作未消费放行</b>；</li>
 *   <li>{@link AclVersionSource}（经 {@link ObjectProvider}）取租户 ACL epoch（av）：
 *       无 bean、无 epoch 行或版本 &lt; 1、实现抛异常 → 一律 503（"没有版本就当有效"被禁止）；</li>
 *   <li>组装 {@link ExecutionPrincipal} → {@link PrincipalContext#set} →
 *       {@code finally} 恢复旧主体（嵌套不污染）。</li>
 * </ol>
 *
 * <p>过滤器<b>不做资源级授权</b>：资源归属由后续服务用 {@code AuthorizationChecker}
 * 在线复核；本过滤器只确立"请求是谁"。REQUEST/FORWARD/ASYNC/ERROR 四种分派全接管
 * （装配见 {@code ProductionSecurityConfig}），每次分派独立建立并在 {@code finally} 清理。
 */
public class DelegatedPrincipalFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(DelegatedPrincipalFilter.class);

    /** 本过滤器保护的前缀；装配时用同一前缀注册 URL 模式。 */
    public static final String PROTECTED_PREFIX = "/internal/ai/v1";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String CONTENT_TYPE_JSON = "application/json;charset=UTF-8";

    private final ProductionDelegationProperties properties;
    private final Clock clock;
    private final ObjectProvider<ProductionReplayGuard> replayGuard;
    private final ObjectProvider<AclVersionSource> aclVersionSource;
    private final PublicKey publicKey;
    private final ObjectMapper errorMapper = new ObjectMapper();

    public DelegatedPrincipalFilter(ProductionDelegationProperties properties, Clock clock,
                                    ObjectProvider<ProductionReplayGuard> replayGuard,
                                    ObjectProvider<AclVersionSource> aclVersionSource) {
        this.properties = properties;
        this.clock = clock;
        this.replayGuard = replayGuard;
        this.aclVersionSource = aclVersionSource;
        this.publicKey = loadPublicKey(properties.getPublicKeyPath());
    }

    /**
     * 租户级 ACL epoch（av）端口：framework 定义形状，rag 的 DAO 提供实现。
     *
     * <p>契约：返回值必须 ≥ 1。<b>无 epoch 行（租户未开通/数据不完整）时返回
     * {@code 0}（或任何 &lt; 1 的值）</b>，调用方按 503 拒绝——绝不允许"没有版本就当有效"；
     * 实现抛出的任何异常同样按 503 拒绝。
     */
    @FunctionalInterface
    public interface AclVersionSource {

        /**
         * @param tenantId 租户
         * @return 当前 ACL epoch（≥ 1）；无 epoch 行返回 0
         */
        int currentVersion(String tenantId);
    }

    @Override
    public void init(FilterConfig filterConfig) {
        // 无需初始化
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest servletRequest)
                || !(response instanceof HttpServletResponse servletResponse)) {
            // 非 Servlet 环境：交给容器/后续链路处理
            chain.doFilter(request, response);
            return;
        }
        // 前缀自判（注册模式之外的防御性兜底）：非目标路径完全不受影响
        if (!isProtectedPath(servletRequest)) {
            chain.doFilter(request, response);
            return;
        }
        // 屏障只验服务身份，由控制器处理；不能要求平台提供浏览器委托。
        if (java.util.Set.of(PROTECTED_PREFIX + "/authorization/barriers",PROTECTED_PREFIX + "/authorization/deliveries/release")
                .contains(servletRequest.getRequestURI().substring(servletRequest.getContextPath().length()))
                && "POST".equals(servletRequest.getMethod())) {
            try {
                new ServiceIdentityVerifier(properties).verify(
                        servletRequest.getHeader(ServiceIdentityVerifier.SERVICE_CREDENTIAL_HEADER));
            } catch (P04AiException e) { writeError(servletResponse, e); return; }
            servletRequest.setAttribute("ai.service.authenticated", Boolean.TRUE);
            chain.doFilter(request, response);
            return;
        }
        ExecutionPrincipal principal;
        try {
            new ServiceIdentityVerifier(properties).verify(
                    servletRequest.getHeader(ServiceIdentityVerifier.SERVICE_CREDENTIAL_HEADER));
            principal = authenticate(servletRequest);
        } catch (P04AiException ex) {
            writeError(servletResponse, ex);
            return;
        }
        ExecutionPrincipal previous = PrincipalContext.set(principal);
        try {
            HttpServletRequest validated;
            try { validated=validateBody(servletRequest); }
            catch(P04AiException ex){writeError(servletResponse,ex);return;}
            chain.doFilter(validated, response);
        } finally {
            // 恢复而非清空：嵌套分派（FORWARD/ERROR）里外层主体不被内层覆盖丢失
            PrincipalContext.restore(previous);
        }
    }

    private HttpServletRequest validateBody(HttpServletRequest request) throws IOException {
        if(!java.util.Set.of("POST","PUT").contains(request.getMethod()) || request.getContentType()==null
                || !request.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("application/json")){return request;}
        byte[] bytes=request.getInputStream().readNBytes(64*1024+1);
        if(bytes.length>64*1024){throw new P04AiException(P04AiErrorCode.BAD_REQUEST);}
        if(bytes.length==0){return request;}
        try {
            var body=new com.fasterxml.jackson.databind.ObjectMapper()
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
            if(body==null || !body.isObject()){throw new P04AiException(P04AiErrorCode.BAD_REQUEST);}
            rejectIdentity(body);
        }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new P04AiException(P04AiErrorCode.BAD_REQUEST);}
        return new jakarta.servlet.http.HttpServletRequestWrapper(request){
            @Override public jakarta.servlet.ServletInputStream getInputStream(){
                var input=new java.io.ByteArrayInputStream(bytes);
                return new jakarta.servlet.ServletInputStream(){
                    @Override public int read(){return input.read();}
                    @Override public int read(byte[] buffer,int offset,int length){return input.read(buffer,offset,length);}
                    @Override public boolean isFinished(){return input.available()==0;}
                    @Override public boolean isReady(){return true;}
                    @Override public void setReadListener(jakarta.servlet.ReadListener listener){throw new UnsupportedOperationException("synchronous JSON body");}
                };
            }
            @Override public java.io.BufferedReader getReader(){return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}
        };
    }

    private void rejectIdentity(com.fasterxml.jackson.databind.JsonNode node){
        if(node.isObject()){
            var names=node.fieldNames();while(names.hasNext()){
                String name=names.next();
                if(java.util.Set.of("tenantId","tenant_id","membershipId","memberId","userId","ownerMemberId","ownerDeptId","principal","policyVersion","aclVersion")
                        .contains(name)){throw new P04AiException(P04AiErrorCode.FORBIDDEN);}
                rejectIdentity(node.get(name));
            }
        }else if(node.isArray()){node.forEach(this::rejectIdentity);}
    }

    @Override
    public void destroy() {
        // 无需销毁
    }

    /** 前缀判定：剥 contextPath 后精确匹配 {@code /internal/ai/v1} 或其子路径。 */
    private static boolean isProtectedPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return uri.equals(PROTECTED_PREFIX) || uri.startsWith(PROTECTED_PREFIX + "/");
    }

    /**
     * 验签 + 消费 jti + 取 av + 组装主体。
     *
     * @throws P04AiException 按冻结口径映射为 401/403/503
     */
    private ExecutionPrincipal authenticate(HttpServletRequest request) {
        DelegationClaims claims = verifyToken(bearerToken(request));

        if (claims.tenantId() == null || claims.tenantId().isBlank()
                || claims.membershipId() == null || claims.membershipId().isBlank()
                || claims.policyVersion() == null) {
            throw new P04AiException(P04AiErrorCode.TENANT_CONTEXT_MISSING);
        }

        // canonical membership 先做廉价结构校验（不写 replay 账），再消费 jti
        String expectedMembership;
        try {
            expectedMembership = ExecutionPrincipal.canonicalMembershipId(claims.tenantId(), claims.subject());
        } catch (ClientException e) {
            // tid 形状非法（冒号/超长/空白）或 sub 不是十进制 userId：声明违反 platform 契约
            throw new P04AiException(P04AiErrorCode.MEMBERSHIP_INVALID);
        }
        if (!expectedMembership.equals(claims.membershipId())) {
            throw new P04AiException(P04AiErrorCode.MEMBERSHIP_INVALID);
        }

        consumeJti(claims);

        int aclVersion = requireAclVersion(claims.tenantId());

        try {
            return new ExecutionPrincipal(claims.tenantId(), claims.subject(), claims.membershipId(),
                    claims.policyVersion(), aclVersion, claims.scopes(), claims.jti(),
                    claims.issuer(), claims.issuedAtEpochSecond(), claims.expiresAtEpochSecond());
        } catch (ClientException e) {
            // 其余形状问题（版本 < 1 等）：同属"身份上下文不合法"，403 而非 500
            throw new P04AiException(P04AiErrorCode.MEMBERSHIP_INVALID);
        }
    }

    /** 提取 Bearer 令牌；未携带 → 401 AUTH_REQUIRED。 */
    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || header.isBlank()) {
            throw new P04AiException(P04AiErrorCode.AUTH_REQUIRED);
        }
        if (!header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw new P04AiException(P04AiErrorCode.AUTH_REQUIRED);
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new P04AiException(P04AiErrorCode.AUTH_REQUIRED);
        }
        return token;
    }

    /** jti 原子消费：无 guard 或消费失败 → 503；重复 jti → 401。 */
    private void consumeJti(DelegationClaims claims) {
        ProductionReplayGuard guard = replayGuard.getIfAvailable();
        if (guard == null) {
            // 生产装配缺 replay 实现 = 重放防护不存在，必须整体拒绝（fail-closed）
            log.error("delegation replay guard missing, rejecting requestId={}", AiRequestIdFilter.currentOrEmpty());
            throw new P04AiException(P04AiErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
        boolean first;
        try {
            first = guard.consume(claims.issuer(), claims.jti(), claims.tenantId(), claims.expiresAt());
        } catch (Exception e) {
            // 存储不可用不得当作未消费放行
            log.warn("delegation replay store unavailable type={} requestId={}",
                    e.getClass().getSimpleName(), AiRequestIdFilter.currentOrEmpty());
            throw new P04AiException(P04AiErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
        if (!first) {
            throw new P04AiException(P04AiErrorCode.DELEGATION_INVALID);
        }
    }

    /** 取租户 ACL epoch：无 source、无 epoch、异常一律 503。 */
    private int requireAclVersion(String tenantId) {
        AclVersionSource source = aclVersionSource.getIfAvailable();
        if (source == null) {
            log.error("acl version source missing, rejecting requestId={}", AiRequestIdFilter.currentOrEmpty());
            throw new P04AiException(P04AiErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
        int version;
        try {
            version = source.currentVersion(tenantId);
        } catch (RuntimeException e) {
            log.warn("acl version lookup failed type={} requestId={}",
                    e.getClass().getSimpleName(), AiRequestIdFilter.currentOrEmpty());
            throw new P04AiException(P04AiErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
        if (version < 1) {
            // 无 epoch 行：拒绝（不默认初始化到 1）
            throw new P04AiException(P04AiErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
        return version;
    }

    /** 失败响应：HTTP status == body.code，符号码在 {@code data.errorCode}。 */
    private void writeError(HttpServletResponse response, P04AiException ex) throws IOException {
        P04AiErrorCode errorCode = ex.errorCode();
        // 只记符号码与 requestId；绝不输出堆栈、SQL、密钥或其它租户信息
        log.warn("delegation rejected errorCode={} requestId={}", errorCode.name(),
                AiRequestIdFilter.currentOrEmpty());
        response.setStatus(errorCode.httpStatus());
        response.setContentType(CONTENT_TYPE_JSON);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        if (response.getHeader(AiRequestIdFilter.HEADER) == null) {
            response.setHeader(AiRequestIdFilter.HEADER, AiRequestIdFilter.currentOrEmpty());
        }
        response.getWriter().write(errorMapper.writeValueAsString(
                ApiEnvelope.error(errorCode.httpStatus(), ex.getMessage(), errorCode.name())));
    }

    // ------------------------------------------------------------------
    // 平行验签实现（与 DelegationVerifier 同口径，但绑定生产属性，不改 p04 类）
    // ------------------------------------------------------------------

    /** 验签结果（与 {@link DelegationVerifier.DelegationClaims} 同形状）。 */
    record DelegationClaims(String issuer, String subject, String tenantId, String membershipId,
                            Integer policyVersion, Set<String> scopes, String jti,
                            long issuedAtEpochSecond, long expiresAtEpochSecond,
                            java.time.Instant expiresAt) {
    }

    /**
     * 校验凭证本身：任何凭证问题都是 401 {@code DELEGATION_INVALID}（未携带由调用方先行判 401）。
     */
    private DelegationClaims verifyToken(String token) {
        Jws<Claims> jws;
        try {
            jws = parser().parseSignedClaims(token);
        } catch (JwtException | IllegalArgumentException e) {
            // 不区分具体原因对外呈现，避免给攻击者反馈；细节只进服务端日志
            log.warn("delegation verification rejected reason={} requestId={}",
                    e.getClass().getSimpleName(), AiRequestIdFilter.currentOrEmpty());
            throw new P04AiException(P04AiErrorCode.DELEGATION_INVALID);
        }

        // 头部白名单：alg/typ/kid 必须精确命中（拒绝 alg 混淆与未知 kid）
        if (!"RS256".equals(jws.getHeader().getAlgorithm())
                || !"JWT".equals(jws.getHeader().getType())
                || !properties.getKid().equals(jws.getHeader().getKeyId())) {
            throw new P04AiException(P04AiErrorCode.DELEGATION_INVALID);
        }

        // 时间与一次性标识契约：sub/jti/iat/exp 必须存在
        Claims claims = jws.getPayload();
        if (claims.getSubject() == null || claims.getSubject().isBlank()
                || claims.getId() == null || claims.getId().isBlank()
                || claims.getExpiration() == null
                || claims.getIssuedAt() == null) {
            throw new P04AiException(P04AiErrorCode.DELEGATION_INVALID);
        }
        requireSaneValidityWindow(claims);

        return new DelegationClaims(claims.getIssuer(), claims.getSubject(),
                claims.get("tid", String.class), claims.get("mid", String.class),
                claims.get("pv", Integer.class), toScopes(claims), claims.getId(),
                claims.getIssuedAt().toInstant().getEpochSecond(),
                claims.getExpiration().toInstant().getEpochSecond(),
                claims.getExpiration().toInstant());
    }

    /**
     * 时间契约：{@code exp} 必须晚于 {@code iat}；TTL 不得超过上限（不含 skew）；
     * {@code iat} 不得来自未来（超出 skew）。{@code exp}/{@code nbf} 的时钟判定由
     * jjwt 依 skew 完成。
     */
    private void requireSaneValidityWindow(Claims claims) {
        long issuedAt = claims.getIssuedAt().toInstant().getEpochSecond();
        long expiresAt = claims.getExpiration().toInstant().getEpochSecond();
        long skew = properties.getClockSkewSeconds();
        if (expiresAt <= issuedAt
                || expiresAt - issuedAt > properties.getTtlCeilingSeconds()
                || issuedAt > clock.instant().getEpochSecond() + skew) {
            throw new P04AiException(P04AiErrorCode.DELEGATION_INVALID);
        }
    }

    private static Set<String> toScopes(Claims claims) {
        Object raw = claims.get("scope");
        Set<String> scopes = new LinkedHashSet<>();
        if (raw instanceof String s) {
            for (String part : s.split("\\s+")) {
                if (!part.isBlank()) {
                    scopes.add(part);
                }
            }
        } else if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null) {
                    scopes.add(String.valueOf(item));
                }
            }
        }
        return scopes;
    }

    private JwtParser parser() {
        return Jwts.parser()
                .verifyWith(publicKey)
                .requireIssuer(properties.getIssuer())
                .requireAudience(properties.getAudience())
                .clock(() -> Date.from(clock.instant()))
                .clockSkewSeconds((int) properties.getClockSkewSeconds())
                .build();
    }

    /** 与 {@code DelegationVerifier} 同款：只读 X.509 公钥 PEM；启动即失败（fail-fast）。 */
    private static PublicKey loadPublicKey(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("ai.integration.security.public-key-path is required");
        }
        try {
            String pem = java.nio.file.Files.readString(java.nio.file.Path.of(path), StandardCharsets.UTF_8);
            String base64 = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read production delegation public key: " + path, e);
        } catch (Exception e) {
            throw new IllegalStateException("cannot parse production delegation public key: " + path, e);
        }
    }
}
