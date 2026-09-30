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

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 委托凭证验签器（AI 侧，只持公钥）。
 *
 * <p>冻结口径（Spec §7.2/§8.2）：标准紧凑 JWS、只接受 {@code RS256}、固定 {@code typ}、
 * issuer/audience/kid 白名单、TTL 与 30 秒时钟偏差；拒绝 {@code alg=none}、算法混淆、
 * 未知 kid、坏签名、缺 claim、过期与未来 {@code nbf}。用成熟库解析，<b>不自写</b> JWT 解析器。
 *
 * <p>错误映射按 N02 的两段口径拆分：
 * <ul>
 *   <li>{@link #verify(String)} 只判"凭证本身是否可信" → 任何问题都是 401 {@code DELEGATION_INVALID}
 *       （缺 {@code sub}/{@code exp}/{@code jti} 也在此列）；</li>
 *   <li>{@link #requireTenantContext(DelegationClaims)} 判"身份上下文是否齐备" → 缺
 *       {@code tid}/{@code mid}/{@code pv} 是 403 {@code TENANT_CONTEXT_MISSING}。</li>
 * </ul>
 */
@Component
public class DelegationVerifier {

    private static final Logger log = LoggerFactory.getLogger(DelegationVerifier.class);

    private static final List<String> IDENTITY_CLAIMS = List.of("tid", "tenantId", "userId", "mid", "membershipId", "sub");

    private final P04SecurityProperties properties;
    private final Clock clock;
    private final PublicKey publicKey;

    public DelegationVerifier(P04SecurityProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.publicKey = loadPublicKey(properties.getDelegation().getPublicKeyPath());
    }

    /**
     * 验签结果。
     *
     * @param policyVersion 可能为 {@code null}（缺声明时由 {@link #requireTenantContext} 判 403）
     */
    public record DelegationClaims(String issuer, String subject, String tenantId, String membershipId,
                                   Integer policyVersion, Set<String> scopes, String jti) {
    }

    /**
     * 校验凭证本身。
     *
     * @param token 紧凑 JWS；来自 {@code Authorization: Bearer}
     * @throws P04AiException 401 {@code AUTH_REQUIRED}（未携带）或 401 {@code DELEGATION_INVALID}
     */
    public DelegationClaims verify(String token) {
        if (token == null || token.isBlank()) {
            throw new P04AiException(P04AiErrorCode.AUTH_REQUIRED);
        }

        Jws<Claims> jws;
        try {
            jws = parser().parseSignedClaims(token);
        } catch (JwtException | IllegalArgumentException e) {
            // 不区分具体原因对外呈现，避免给攻击者反馈；细节只进服务端日志
            log.warn("delegation rejected reason={} requestId={}", e.getClass().getSimpleName(),
                    AiRequestIdFilter.currentOrEmpty());
            throw new P04AiException(P04AiErrorCode.DELEGATION_INVALID);
        }

        // 头部白名单：alg/typ/kid 必须精确命中
        if (!"RS256".equals(jws.getHeader().getAlgorithm())
                || !"JWT".equals(jws.getHeader().getType())
                || !properties.getDelegation().getKid().equals(jws.getHeader().getKeyId())) {
            throw new P04AiException(P04AiErrorCode.DELEGATION_INVALID);
        }

        Claims claims = jws.getPayload();
        if (claims.getSubject() == null || claims.getSubject().isBlank()
                || claims.getId() == null || claims.getId().isBlank()
                || claims.getExpiration() == null) {
            throw new P04AiException(P04AiErrorCode.DELEGATION_INVALID);
        }

        return new DelegationClaims(claims.getIssuer(), claims.getSubject(),
                claims.get("tid", String.class), claims.get("mid", String.class),
                claims.get("pv", Integer.class), toScopes(claims), claims.getId());
    }

    /**
     * 把经验签的声明提升为可用主体；缺租户上下文时按 N02 判 403。
     *
     * @throws P04AiException 403 {@code TENANT_CONTEXT_MISSING}
     */
    public DelegatedPrincipal requireTenantContext(DelegationClaims claims) {
        if (claims.tenantId() == null || claims.tenantId().isBlank()
                || claims.membershipId() == null || claims.membershipId().isBlank()
                || claims.policyVersion() == null) {
            throw new P04AiException(P04AiErrorCode.TENANT_CONTEXT_MISSING);
        }
        return new DelegatedPrincipal(claims.issuer(), claims.tenantId(), claims.subject(),
                claims.membershipId(), claims.policyVersion(), claims.scopes(), claims.jti());
    }

    /** 请求体/请求头中禁止出现的身份字段名（N05 判 403）。 */
    public static List<String> identityClaimNames() {
        return IDENTITY_CLAIMS;
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
                .requireIssuer(properties.getDelegation().getIssuer())
                .requireAudience(properties.getDelegation().getAudience())
                .clock(() -> Date.from(clock.instant()))
                .clockSkewSeconds(properties.getDelegation().getClockSkewSeconds())
                .build();
    }

    private static PublicKey loadPublicKey(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("p04.delegation.public-key-path is required");
        }
        try {
            String pem = Files.readString(Path.of(path), StandardCharsets.UTF_8);
            String base64 = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read p04 delegation public key: " + path, e);
        } catch (Exception e) {
            throw new IllegalStateException("cannot parse p04 delegation public key: " + path, e);
        }
    }
}
