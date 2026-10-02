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

package org.ruoyi.aiintegration.web;

import jakarta.servlet.http.HttpServletRequest;
import org.ruoyi.aiintegration.authorization.AiActionRegistry;
import org.ruoyi.aiintegration.config.AiIntegrationProperties;
import org.ruoyi.aiintegration.delegation.ProductionSigningKeySource;
import org.ruoyi.aiintegration.identity.CurrentPrincipalResolver;
import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 生产 AI 网关（U06/P1.2c）：{@code /api/ai/v1/**} 白名单代理，真实委托链的 platform 入口。
 *
 * <p>流程（每步失败即终止，失败响应 {@code HTTP status == body.code}）：
 * <ol>
 *   <li>白名单：method + 路径形状必须精确命中固定路由表；白名单外一律 404
 *       （不存在的路径与无权访问的资源同形，不泄露网关能力面）；</li>
 *   <li>登录证据：请求未携带 sa-token 凭证头（{@code Authorization}，与
 *       {@code sa-token.token-name} 一致）→ 401；</li>
 *   <li>成员身份：{@link CurrentPrincipalResolver#resolveCurrentMember()} 为 empty
 *       （已登录但不归属当前租户/无租户上下文）→ 403；</li>
 *   <li>策略版本：经 {@link ObjectProvider}&lt;{@link PlatformIdentitySource}&gt; 查
 *       真实身份事实；无身份源 → 503；成员不存在/停用/无策略版本行 → 403
 *       （版本缺失绝不默认 1）；</li>
 *   <li>最小 scope：路由 → canonical 动作 → 平台权限（{@link AiActionRegistry} 固定映射），
 *       身份 scopes 不含该权限 → 403；委托只携带<b>本路由所需的那一个 scope</b>；</li>
 *   <li>签发委托并转发：剥内部身份头与逐跳头、剥浏览器凭证，附
 *       {@code Authorization: Bearer <委托>}；不跟随重定向；超时/体长受限；
 *       恶意响应 → 503；AI 状态码透传。</li>
 * </ol>
 *
 * <p>网关不解析、不信任 body/header 里的身份字段：身份只来自 SaToken 会话经
 * resolver 与身份源查询的真实事实。
 */
@RestController
@RequestMapping("/api/ai/v1")
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiGatewayController {

    /** 登录证据头：与 platform {@code sa-token.token-name: Authorization} 一致。 */
    static final String LOGIN_EVIDENCE_HEADER = "Authorization";

    /** 网关前缀（与 @RequestMapping 一致），用于从 requestURI 提取内部子路径。 */
    static final String GATEWAY_PREFIX = "/api/ai/v1";

    /** AI 侧内部 API 前缀（被 {@code DelegatedPrincipalFilter} 保护的同一前缀）。 */
    static final String AI_INTERNAL_PREFIX = "/internal/ai/v1";

    /**
     * 内部身份头黑名单：一律不转发（网关身份只经委托凭证传递，
     * 伪造的 X-Tenant/X-User 到了 AI 侧也不被任何组件采信）。
     */
    private static final Set<String> INTERNAL_IDENTITY_HEADERS = Set.of(
            "x-tenant", "x-tenant-id", "x-user", "x-user-id", "x-member-id",
            "x-membership-id", "x-policy-version", "x-acl-version", "x-principal");

    /** 逐跳头/由转发栈自行决定的头：一律不转发。 */
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "authorization", "host", "content-length", "connection", "keep-alive",
            "proxy-authenticate", "proxy-authorization", "te", "trailer",
            "transfer-encoding", "upgrade", "cookie");

    /** 白名单路由：method + 路径形状 → canonical 动作。运行期固定，不可扩展。 */
    private record Route(String method, String pattern, String action) {
    }

    private static final List<Route> ROUTES = List.of(
            new Route("GET", "/knowledge-bases", "kb.list"),
            new Route("POST", "/knowledge-bases", "kb.write"),
            new Route("GET", "/knowledge-bases/{id}", "kb.read"),
            new Route("DELETE", "/knowledge-bases/{id}", "kb.delete"),
            new Route("PUT", "/knowledge-bases/{id}/acl", "kb.acl.manage"),
            new Route("DELETE", "/knowledge-bases/{id}/acl", "kb.acl.manage"),
            new Route("POST", "/knowledge-bases/retrievals", "kb.retrieve"),
            new Route("GET", "/documents/{id}", "document.read"),
            new Route("GET", "/documents/{id}/content", "document.download"));

    private final CurrentPrincipalResolver principalResolver;
    private final ObjectProvider<PlatformIdentitySource> identitySource;
    private final ProductionSigningKeySource signingKeys;
    private final AiGatewayClient client;
    private final AiIntegrationProperties properties;

    public AiGatewayController(CurrentPrincipalResolver principalResolver,
                               ObjectProvider<PlatformIdentitySource> identitySource,
                               ProductionSigningKeySource signingKeys,
                               AiGatewayClient client,
                               AiIntegrationProperties properties) {
        this.principalResolver = principalResolver;
        this.identitySource = identitySource;
        this.signingKeys = signingKeys;
        this.client = client;
        this.properties = properties;
    }

    /** 网关唯一入口：白名单匹配 → 委托 → 转发；白名单外 404。 */
    @RequestMapping("/**")
    public ResponseEntity<?> gateway(HttpServletRequest request,
                                     @RequestBody(required = false) byte[] body) {
        try {
            return doGateway(request, body);
        } catch (P04Exception ex) {
            return fail(ex.errorCode());
        } catch (AiGatewayClient.UpstreamUnavailableException ex) {
            // 上游不可用/恶意响应：不放行也不泄露原因
            return fail(P04ErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
    }

    private ResponseEntity<?> doGateway(HttpServletRequest request, byte[] body) {
        String method = request.getMethod() == null ? "" : request.getMethod().toUpperCase(Locale.ROOT);
        String subPath = subPath(request);
        if (subPath == null) {
            return fail(P04ErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        }
        Optional<Route> matched = matchRoute(method, subPath);
        if (matched.isEmpty()) {
            // 白名单外与不存在同形：404，不区分"路径不存在"与"方法不允许"
            return fail(P04ErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        }
        Route route = matched.get();

        // 1. 登录证据：无任何凭证 → 401（未登录）
        String loginEvidence = request.getHeader(LOGIN_EVIDENCE_HEADER);
        if (loginEvidence == null || loginEvidence.isBlank()) {
            return fail(P04ErrorCode.AUTH_REQUIRED);
        }

        // 2. 成员身份：resolver empty（已登录但不归属当前租户）→ 403
        CurrentPrincipalResolver.CurrentMember member = principalResolver.resolveCurrentMember()
                .orElseThrow(() -> new P04Exception(P04ErrorCode.TENANT_CONTEXT_MISSING));

        // 3. 策略版本：真实身份事实，缺身份源 503，缺成员/版本 403（绝不默认 1）
        PlatformIdentitySource source = identitySource.getIfAvailable();
        if (source == null) {
            return fail(P04ErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
        PlatformIdentitySource.PlatformIdentity identity =
                source.membership(member.tenantId(), member.userId(), member.membershipId());
        if (identity == null || !identity.enabled()) {
            throw new P04Exception(P04ErrorCode.MEMBERSHIP_INVALID);
        }

        // 4. 最小 scope：路由动作 → 平台权限，身份必须显式持有
        String requiredPermission = AiActionRegistry.requirePermission(route.action());
        if (!identity.scopes().contains(requiredPermission)) {
            throw new P04Exception(P04ErrorCode.FORBIDDEN);
        }

        // 5. 签发委托（只带本路由所需 scope）并转发
        ProductionSigningKeySource.Issued issued = signingKeys.issue(
                member.tenantId(), member.userId(), member.membershipId(),
                List.of(requiredPermission), identity.policyVersion(), null);

        return forward(request, method, subPath, issued.token(), body);
    }

    /** 组装转发请求并透传 AI 状态码。 */
    private ResponseEntity<?> forward(HttpServletRequest request, String method,
                                      String subPath, String delegationToken,
                                      byte[] body) {
        if (body != null && body.length > properties.getMaxForwardBodyBytes()) {
            throw new P04Exception(P04ErrorCode.BAD_REQUEST);
        }
        long contentLength = request.getContentLengthLong();
        if (contentLength > properties.getMaxForwardBodyBytes()) {
            throw new P04Exception(P04ErrorCode.BAD_REQUEST);
        }

        String query = request.getQueryString();
        String target = properties.getAiBaseUrl() + AI_INTERNAL_PREFIX + subPath
                + (query == null || query.isBlank() ? "" : "?" + query);
        URI uri;
        try {
            uri = new URI(target);
        } catch (URISyntaxException e) {
            throw new P04Exception(P04ErrorCode.BAD_REQUEST);
        }

        Map<String, String> headers = sanitizedHeaders(request);
        // 委托凭证是唯一身份载体；浏览器凭证已在黑名单中剥除
        headers.put("Authorization", "Bearer " + delegationToken);
        headers.put(RequestId.HEADER, RequestId.currentOrEmpty());

        AiGatewayClient.ForwardResponse response = client.forward(
                new AiGatewayClient.ForwardRequest(method, uri, Map.copyOf(headers), body));

        // 透传 AI 响应状态码与体；AI 内部协议固定 JSON 包络
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.status())
                .header(RequestId.HEADER, RequestId.currentOrEmpty());
        if (!response.body().isBlank()) {
            builder.contentType(MediaType.APPLICATION_JSON);
        }
        return builder.body(response.body());
    }

    /**
     * 净化转发头：剥内部身份头、逐跳头、cookie 与浏览器凭证；
     * 其余头（如 Content-Type、Accept、Idempotency-Key）原样透传。
     */
    private Map<String, String> sanitizedHeaders(HttpServletRequest request) {
        Map<String, String> sanitized = new LinkedHashMap<>();
        var headerNames = request.getHeaderNames();
        if (headerNames == null) {
            return sanitized;
        }
        List<String> names = new ArrayList<>();
        headerNames.asIterator().forEachRemaining(names::add);
        for (String name : names) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (INTERNAL_IDENTITY_HEADERS.contains(lower) || HOP_BY_HOP_HEADERS.contains(lower)) {
                continue;
            }
            String value = request.getHeader(name);
            if (value != null && !value.isBlank()) {
                sanitized.put(name, value);
            }
        }
        if (request.getContentType() != null && !request.getContentType().isBlank()) {
            sanitized.put("Content-Type", request.getContentType());
        }
        return sanitized;
    }

    /** 从 requestURI 提取网关内子路径；形状可疑（残留编码/前缀不符）返回 null → 404。 */
    private static String subPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return null;
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        if (!uri.equals(GATEWAY_PREFIX) && !uri.startsWith(GATEWAY_PREFIX + "/")) {
            return null;
        }
        String subPath = uri.substring(GATEWAY_PREFIX.length());
        if (subPath.isEmpty()) {
            subPath = "/";
        }
        // 残留编码/穿越形状不在白名单判定内，直接按不存在处理（fail-closed）
        if (subPath.indexOf('%') >= 0 || subPath.indexOf('\\') >= 0) {
            return null;
        }
        return subPath;
    }

    /**
     * 白名单匹配：段级精确比较，{@code {x}} 通配任意单段（非空且不含 {@code /}）。
     * 包络外路径（含根路径）一律不匹配。
     */
    static Optional<Route> matchRoute(String method, String subPath) {
        if (method == null || method.isBlank() || subPath == null
                || subPath.isBlank() || !subPath.startsWith("/")) {
            return Optional.empty();
        }
        String[] actual = subPath.substring(1).split("/", -1);
        for (Route route : ROUTES) {
            if (!route.method().equals(method)) {
                continue;
            }
            String[] pattern = route.pattern().substring(1).split("/", -1);
            if (matches(pattern, actual)) {
                return Optional.of(route);
            }
        }
        return Optional.empty();
    }

    private static boolean matches(String[] pattern, String[] actual) {
        if (pattern.length != actual.length) {
            return false;
        }
        for (int i = 0; i < pattern.length; i++) {
            if (pattern[i].startsWith("{") && pattern[i].endsWith("}")) {
                if (actual[i].isBlank()) {
                    return false;
                }
            } else if (!pattern[i].equals(actual[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * 失败响应遵循本协议口径：HTTP status == body.code，符号码放 {@code data.errorCode}
     * （与 {@code ProductionAuthorizationController} 相同的自包含映射）。
     */
    private static ResponseEntity<ApiResponse<Map<String, Object>>> fail(P04ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.httpStatus())
                .header(RequestId.HEADER, RequestId.currentOrEmpty())
                .body(ApiResponse.error(errorCode.httpStatus(), errorCode.message(), errorCode.name()));
    }
}
