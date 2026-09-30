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

package org.ruoyi.aiintegration.delegation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;

import java.security.PrivateKey;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 委托凭证签发器（紧凑 JWS / JWT，RS256）。
 *
 * <p>冻结口径（Spec §7.2/§8.2）：标准紧凑 JWS、{@code RS256}、RSA 3072、固定
 * {@code typ}/{@code alg}、issuer/audience 白名单；<b>不得</b>自创加密结构。
 * 每次签发都产生新的 {@code jti}（F2：重试要换 jti，业务幂等靠 Idempotency-Key）。
 *
 * <p>{@link DelegationVariant} 除 {@code NONE} 外的取值只用于构造负例。
 */
@Component
public class DelegationIssuer {

    /** 受信签发方。 */
    public static final String ISSUER = "platform";

    /** 受信受众（AI 服务）。 */
    public static final String AUDIENCE = "ai";

    /** 动作/功能 scope；AI 校验时必须命中。 */
    public static final String SCOPE_RAG_CHAT_SUBMIT = "rag.chat.submit";

    private final DelegationSigningKeys keys;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DelegationIssuer(DelegationSigningKeys keys, ObjectMapper objectMapper, Clock clock) {
        this.keys = keys;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 签发结果。
     *
     * @param token 紧凑 JWS
     * @param jti   一次性标识（用于防重放账本与证据关联，不记录 token 本身）
     */
    public record Issued(String token, String jti, String kid) {
    }

    /**
     * @param variant      负例变体；{@code null} 视为 {@link DelegationVariant#NONE}
     * @param tenantId     租户；负例可省略
     * @param subject      主体（人）；负例可省略
     * @param membershipId 成员身份；负例可省略
     * @param scopes       功能 scope 集合
     * @param policyVersion platform 策略版本；负例可省略
     * @param ttlSeconds   有效期秒数；{@code null} 用默认 60
     */
    public Issued issue(DelegationVariant variant, String tenantId, String subject, String membershipId,
                        List<String> scopes, Integer policyVersion, Integer ttlSeconds) {
        DelegationVariant v = variant == null ? DelegationVariant.NONE : variant;
        Instant now = clock.instant();
        long ttl = ttlSeconds == null ? 60L : ttlSeconds;
        String jti = UUID.randomUUID().toString();

        Instant issuedAt = now;
        Instant notBefore = now;
        Instant expiresAt = now.plusSeconds(ttl);
        switch (v) {
            case EXPIRED -> {
                issuedAt = now.minus(1, ChronoUnit.HOURS);
                notBefore = issuedAt;
                expiresAt = now.minus(60, ChronoUnit.SECONDS);
            }
            case NOT_YET_VALID -> {
                notBefore = now.plus(5, ChronoUnit.MINUTES);
                expiresAt = now.plus(10, ChronoUnit.MINUTES);
            }
            default -> {
                // 其余变体沿用默认时间窗
            }
        }

        if (v == DelegationVariant.ALG_NONE) {
            return new Issued(algNoneToken(v, tenantId, subject, membershipId, scopes, policyVersion,
                    jti, issuedAt, notBefore, expiresAt), jti, keys.kid());
        }

        String issuer = v == DelegationVariant.WRONG_ISSUER ? "not-platform" : ISSUER;
        String audience = v == DelegationVariant.WRONG_AUDIENCE ? "other-service" : AUDIENCE;
        String kid = v == DelegationVariant.UNKNOWN_KID ? "p04-unknown-kid" : keys.kid();
        PrivateKey signingKey = v == DelegationVariant.FOREIGN_KEY ? keys.untrustedPrivateKey() : keys.privateKey();

        var builder = Jwts.builder()
                .header().keyId(kid).type("JWT").and()
                .issuer(issuer)
                .audience().add(audience).and()
                .issuedAt(Date.from(issuedAt))
                .notBefore(Date.from(notBefore));

        if (v != DelegationVariant.MISSING_SUBJECT && subject != null) {
            builder.subject(subject);
        }
        if (v != DelegationVariant.MISSING_TENANT && tenantId != null) {
            builder.claim("tid", tenantId);
        }
        if (v != DelegationVariant.MISSING_MEMBERSHIP && membershipId != null) {
            builder.claim("mid", membershipId);
        }
        if (v != DelegationVariant.MISSING_POLICY_VERSION && policyVersion != null) {
            builder.claim("pv", policyVersion);
        }
        if (scopes != null && !scopes.isEmpty()) {
            builder.claim("scope", scopes);
        }
        if (v != DelegationVariant.MISSING_JTI) {
            builder.id(jti);
        }
        if (v != DelegationVariant.MISSING_EXPIRATION) {
            builder.expiration(Date.from(expiresAt));
        }

        return new Issued(builder.signWith(signingKey, Jwts.SIG.RS256).compact(), jti, kid);
    }

    /**
     * 手工构造 {@code alg=none} 的无签名 JWS：header.payload. （签名段为空）。
     * 这是协议层必须拒绝的形态，jjwt 不提供构造入口，故在此显式拼装。
     */
    private String algNoneToken(DelegationVariant v, String tenantId, String subject, String membershipId,
                                List<String> scopes, Integer policyVersion, String jti,
                                Instant issuedAt, Instant notBefore, Instant expiresAt) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "none");
        header.put("typ", "JWT");
        header.put("kid", keys.kid());

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("aud", AUDIENCE);
        if (subject != null) {
            claims.put("sub", subject);
        }
        if (tenantId != null) {
            claims.put("tid", tenantId);
        }
        if (membershipId != null) {
            claims.put("mid", membershipId);
        }
        if (policyVersion != null) {
            claims.put("pv", policyVersion);
        }
        if (scopes != null && !scopes.isEmpty()) {
            claims.put("scope", scopes);
        }
        claims.put("jti", jti);
        claims.put("iat", issuedAt.getEpochSecond());
        claims.put("nbf", notBefore.getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());

        try {
            return base64Url(objectMapper.writeValueAsBytes(header))
                    + "." + base64Url(objectMapper.writeValueAsBytes(claims)) + ".";
        } catch (Exception e) {
            throw new IllegalStateException("failed to craft alg=none token", e);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
