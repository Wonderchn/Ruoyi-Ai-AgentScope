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

package org.ruoyi.aiintegration.p04;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.ruoyi.aiintegration.delegation.DelegationIssuer;
import org.ruoyi.aiintegration.delegation.DelegationSigningKeys;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
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
 * <b>测试专用</b>的委托凭证铸造器（阻断修复 Spec §2.3）。
 *
 * <p>合法委托委托给主源集的 {@link DelegationIssuer}；**所有可被拒绝的凭证**都在此构造，
 * 因此主源集不携带任何"负例铸造"能力。负例只服务于合成靶场的拒绝断言。
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "p04.enabled", havingValue = "true")
public class NegativeDelegationMinter {

    private final DelegationSigningKeys keys;
    private final DelegationIssuer issuer;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** 不受信密钥：仅测试侧持有，用于构造"外来私钥签发"负例。 */
    private final KeyPair foreign = DelegationSigningKeys.generateUntrustedPairForTests();

    public NegativeDelegationMinter(DelegationSigningKeys keys, DelegationIssuer issuer,
                                    ObjectMapper objectMapper, Clock clock) {
        this.keys = keys;
        this.issuer = issuer;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public record Minted(String token, String jti, String kid) {
    }

    /**
     * 按变体铸造凭证。
     *
     * @throws IllegalArgumentException 未知变体
     */
    public Minted mint(DelegationVariant variant, String tenantId, String subject, String membershipId,
                       List<String> scopes, Integer policyVersion, Integer ttlSeconds) {
        DelegationVariant v = variant == null ? DelegationVariant.NONE : variant;
        if (v == DelegationVariant.NONE) {
            DelegationIssuer.Issued issued =
                    issuer.issue(tenantId, subject, membershipId, scopes, policyVersion, ttlSeconds);
            return new Minted(issued.token(), issued.jti(), issued.kid());
        }

        Instant now = clock.instant();
        String jti = UUID.randomUUID().toString();
        Instant issuedAt = now;
        Instant notBefore = now;
        long ttl = ttlSeconds == null ? 60L : ttlSeconds;
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
            case TTL_OVER_CEILING -> expiresAt = now.plusSeconds(DelegationIssuer.MAX_TTL_SECONDS + 1);
            default -> {
                // 其余变体沿用默认时间窗
            }
        }

        if (v == DelegationVariant.ALG_NONE) {
            return new Minted(algNoneToken(tenantId, subject, membershipId, scopes, policyVersion,
                    UUID.randomUUID().toString(), issuedAt, notBefore, expiresAt), jti, keys.kid());
        }

        String issuerName = v == DelegationVariant.WRONG_ISSUER ? "not-platform" : DelegationIssuer.ISSUER;
        String audience = v == DelegationVariant.WRONG_AUDIENCE ? "other-service" : DelegationIssuer.AUDIENCE;
        String kid = v == DelegationVariant.UNKNOWN_KID ? "p04-unknown-kid" : keys.kid();
        PrivateKey signingKey = v == DelegationVariant.FOREIGN_KEY ? foreign.getPrivate() : keys.privateKey();

        var builder = Jwts.builder()
                .header().keyId(kid).type("JWT").and()
                .issuer(issuerName)
                .audience().add(audience).and()
                .notBefore(Date.from(notBefore));
        if (v != DelegationVariant.MISSING_ISSUED_AT) {
            builder.issuedAt(Date.from(issuedAt));
        }
        if (v != DelegationVariant.MISSING_SUBJECT) {
            builder.subject(subject);
        }
        if (v != DelegationVariant.MISSING_JTI) {
            builder.id(jti);
        }
        if (v != DelegationVariant.MISSING_EXPIRATION) {
            builder.expiration(Date.from(expiresAt));
        }
        if (v != DelegationVariant.MISSING_TENANT) {
            builder.claim("tid", tenantId);
        }
        if (v != DelegationVariant.MISSING_MEMBERSHIP) {
            builder.claim("mid", membershipId);
        }
        if (v != DelegationVariant.MISSING_POLICY_VERSION) {
            builder.claim("pv", policyVersion);
        }
        if (scopes != null && !scopes.isEmpty()) {
            builder.claim("scope", scopes);
        }
        return new Minted(builder.signWith(signingKey, Jwts.SIG.RS256).compact(), jti, kid);
    }

    /**
     * 手工构造 {@code alg=none} 的无签名 JWS（签名段为空）。jjwt 不提供构造入口，
     * 只能在测试侧显式拼装——这正是它必须留在测试源集的原因之一。
     */
    private String algNoneToken(String tenantId, String subject, String membershipId, List<String> scopes,
                                Integer policyVersion, String jti, Instant issuedAt, Instant notBefore,
                                Instant expiresAt) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "none");
        header.put("typ", "JWT");
        header.put("kid", keys.kid());

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", DelegationIssuer.ISSUER);
        claims.put("aud", DelegationIssuer.AUDIENCE);
        claims.put("sub", subject);
        claims.put("tid", tenantId);
        claims.put("mid", membershipId);
        claims.put("pv", policyVersion);
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
