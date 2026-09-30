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

package org.ruoyi.aiintegration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.aiintegration.delegation.DelegationIssuer;
import org.ruoyi.aiintegration.delegation.DelegationSigningKeys;
import org.ruoyi.aiintegration.delegation.DelegationVariant;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 委托签发器的无外部服务单测（Spec §7.1 第一层 / §8.6 Unit 模式的必跑类之一）。
 *
 * <p>用可控时钟断言 TTL 与 nbf/exp 边界，不用 sleep 等过期。测试类必须带 {@code dev} 标签：
 * platform 的 surefire 以 {@code groups=${profiles.active}} 过滤，{@code -Pdev} 下只跑该标签，
 * 否则会出现 “Tests run: 0” 却 BUILD SUCCESS 的假绿。
 */
@Tag("dev")
class P04DelegationIssuerTest {

    private static final Instant FIXED = Instant.parse("2026-09-30T00:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DelegationSigningKeys keys;
    private DelegationIssuer issuer;

    @BeforeEach
    void setUp() {
        keys = DelegationSigningKeys.generate("p04-platform-k1");
        issuer = new DelegationIssuer(keys, MAPPER, Clock.fixed(FIXED, ZoneOffset.UTC));
    }

    /** 解析器时钟固定在签发时刻之后 10 秒，避免固定时钟落在真实"现在"之前造成误判。 */
    private JwtParser parser(java.security.PublicKey key) {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(DelegationIssuer.ISSUER)
                .requireAudience(DelegationIssuer.AUDIENCE)
                .clock(() -> Date.from(FIXED.plusSeconds(10)))
                .clockSkewSeconds(30)
                .build();
    }

    @Test
    @DisplayName("合法委托可验签并携带全部必需声明")
    void legalDelegationVerifiesWithExpectedClaims() {
        DelegationIssuer.Issued issued = issuer.issue(DelegationVariant.NONE, "T1", "sub-u1", "M1",
                List.of(DelegationIssuer.SCOPE_RAG_CHAT_SUBMIT), 1, null);

        Jws<Claims> jws = parser(keys.publicKey()).parseSignedClaims(issued.token());
        Claims claims = jws.getPayload();

        assertEquals("RS256", jws.getHeader().getAlgorithm());
        assertEquals("JWT", jws.getHeader().getType());
        assertEquals("p04-platform-k1", jws.getHeader().getKeyId());
        assertEquals(DelegationIssuer.ISSUER, claims.getIssuer());
        assertTrue(claims.getAudience().contains(DelegationIssuer.AUDIENCE));
        assertEquals("sub-u1", claims.getSubject());
        assertEquals("T1", claims.get("tid", String.class));
        assertEquals("M1", claims.get("mid", String.class));
        assertEquals(1, claims.get("pv", Integer.class));
        assertEquals(issued.jti(), claims.getId());
        assertNotNull(claims.getExpiration());
        assertNotNull(claims.getNotBefore());
        assertNotNull(claims.getIssuedAt());
    }

    @Test
    @DisplayName("每次签发都产生新的 jti（F2：重试必须换 jti）")
    void eachIssueGetsFreshJti() {
        DelegationIssuer.Issued first = issuer.issue(DelegationVariant.NONE, "T1", "sub-u1", "M1",
                List.of(DelegationIssuer.SCOPE_RAG_CHAT_SUBMIT), 1, null);
        DelegationIssuer.Issued second = issuer.issue(DelegationVariant.NONE, "T1", "sub-u1", "M1",
                List.of(DelegationIssuer.SCOPE_RAG_CHAT_SUBMIT), 1, null);

        assertNotEquals(first.jti(), second.jti());
        assertNotEquals(first.token(), second.token());
    }

    @Test
    @DisplayName("TTL 精确等于请求的秒数")
    void ttlIsHonoured() {
        DelegationIssuer.Issued issued = issuer.issue(DelegationVariant.NONE, "T1", "sub-u1", "M1",
                List.of(DelegationIssuer.SCOPE_RAG_CHAT_SUBMIT), 1, 120);

        Claims claims = parser(keys.publicKey()).parseSignedClaims(issued.token()).getPayload();
        long delta = claims.getExpiration().toInstant().getEpochSecond()
                - claims.getIssuedAt().toInstant().getEpochSecond();
        assertEquals(120L, delta);
    }

    @Test
    @DisplayName("过期凭证被拒绝")
    void expiredIsRejected() {
        DelegationIssuer.Issued issued = issuer.issue(DelegationVariant.EXPIRED, "T1", "sub-u1", "M1",
                List.of(DelegationIssuer.SCOPE_RAG_CHAT_SUBMIT), 1, null);
        assertThrows(JwtException.class, () -> parser(keys.publicKey()).parseSignedClaims(issued.token()));
    }

    @Test
    @DisplayName("尚未生效（nbf 在未来）的凭证被拒绝")
    void notYetValidIsRejected() {
        DelegationIssuer.Issued issued = issuer.issue(DelegationVariant.NOT_YET_VALID, "T1", "sub-u1", "M1",
                List.of(DelegationIssuer.SCOPE_RAG_CHAT_SUBMIT), 1, null);
        assertThrows(JwtException.class, () -> parser(keys.publicKey()).parseSignedClaims(issued.token()));
    }

    @Test
    @DisplayName("错误 audience 的凭证被拒绝")
    void wrongAudienceIsRejected() {
        DelegationIssuer.Issued issued = issuer.issue(DelegationVariant.WRONG_AUDIENCE, "T1", "sub-u1", "M1",
                List.of(DelegationIssuer.SCOPE_RAG_CHAT_SUBMIT), 1, null);
        assertThrows(JwtException.class, () -> parser(keys.publicKey()).parseSignedClaims(issued.token()));
    }

    @Test
    @DisplayName("外来私钥签发的凭证签名校验失败")
    void foreignKeySignatureIsRejected() {
        DelegationIssuer.Issued issued = issuer.issue(DelegationVariant.FOREIGN_KEY, "T1", "sub-u1", "M1",
                List.of(DelegationIssuer.SCOPE_RAG_CHAT_SUBMIT), 1, null);
        assertThrows(JwtException.class, () -> parser(keys.publicKey()).parseSignedClaims(issued.token()));
    }

    @Test
    @DisplayName("alg=none 的无签名 JWS 被解析器拒绝")
    void algNoneIsRejected() {
        DelegationIssuer.Issued issued = issuer.issue(DelegationVariant.ALG_NONE, "T1", "sub-u1", "M1",
                List.of(DelegationIssuer.SCOPE_RAG_CHAT_SUBMIT), 1, null);
        assertTrue(issued.token().endsWith("."), "alg=none 令牌的签名段必须为空");
        assertThrows(JwtException.class, () -> parser(keys.publicKey()).parseSignedClaims(issued.token()));
    }

    @Test
    @DisplayName("未知 kid 可见于头部（由校验方按白名单拒绝，而不是靠签名失败兜底）")
    void unknownKidIsVisibleInHeader() {
        DelegationIssuer.Issued issued = issuer.issue(DelegationVariant.UNKNOWN_KID, "T1", "sub-u1", "M1",
                List.of(DelegationIssuer.SCOPE_RAG_CHAT_SUBMIT), 1, null);
        Jws<Claims> jws = parser(keys.publicKey()).parseSignedClaims(issued.token());
        assertEquals("p04-unknown-kid", jws.getHeader().getKeyId());
    }

    @Test
    @DisplayName("缺租户/成员/策略版本声明时对应 claim 为空")
    void missingIdentityClaimsAreAbsent() {
        Claims noTenant = parser(keys.publicKey()).parseSignedClaims(
                issuer.issue(DelegationVariant.MISSING_TENANT, "T1", "sub-u1", "M1",
                        List.of(DelegationIssuer.SCOPE_RAG_CHAT_SUBMIT), 1, null).token()).getPayload();
        assertNull(noTenant.get("tid", String.class));

        Claims noMembership = parser(keys.publicKey()).parseSignedClaims(
                issuer.issue(DelegationVariant.MISSING_MEMBERSHIP, "T1", "sub-u1", "M1",
                        List.of(DelegationIssuer.SCOPE_RAG_CHAT_SUBMIT), 1, null).token()).getPayload();
        assertNull(noMembership.get("mid", String.class));

        Claims noPolicyVersion = parser(keys.publicKey()).parseSignedClaims(
                issuer.issue(DelegationVariant.MISSING_POLICY_VERSION, "T1", "sub-u1", "M1",
                        List.of(DelegationIssuer.SCOPE_RAG_CHAT_SUBMIT), 1, null).token()).getPayload();
        assertNull(noPolicyVersion.get("pv", Integer.class));
    }

    @Test
    @DisplayName("缺 jti / 缺 sub / 缺 exp 声明时对应字段为空")
    void missingTokenClaimsAreAbsent() {
        Claims noJti = parser(keys.publicKey()).parseSignedClaims(
                issuer.issue(DelegationVariant.MISSING_JTI, "T1", "sub-u1", "M1",
                        List.of(DelegationIssuer.SCOPE_RAG_CHAT_SUBMIT), 1, null).token()).getPayload();
        assertNull(noJti.getId());

        Claims noSubject = parser(keys.publicKey()).parseSignedClaims(
                issuer.issue(DelegationVariant.MISSING_SUBJECT, "T1", "sub-u1", "M1",
                        List.of(DelegationIssuer.SCOPE_RAG_CHAT_SUBMIT), 1, null).token()).getPayload();
        assertNull(noSubject.getSubject());

        Claims noExpiration = parser(keys.publicKey()).parseSignedClaims(
                issuer.issue(DelegationVariant.MISSING_EXPIRATION, "T1", "sub-u1", "M1",
                        List.of(DelegationIssuer.SCOPE_RAG_CHAT_SUBMIT), 1, null).token()).getPayload();
        assertNull(noExpiration.getExpiration());
    }

    @Test
    @DisplayName("公钥 PEM 可被重新导入并用于验签")
    void publicKeyPemRoundTrips() throws Exception {
        String pem = keys.publicKeyPem();
        assertTrue(pem.startsWith("-----BEGIN PUBLIC KEY-----"));

        String base64 = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = java.util.Base64.getDecoder().decode(base64);
        java.security.PublicKey imported = java.security.KeyFactory.getInstance("RSA")
                .generatePublic(new java.security.spec.X509EncodedKeySpec(der));

        DelegationIssuer.Issued issued = issuer.issue(DelegationVariant.NONE, "T1", "sub-u1", "M1",
                List.of(DelegationIssuer.SCOPE_RAG_CHAT_SUBMIT), 1, null);
        Claims claims = parser(imported).parseSignedClaims(issued.token()).getPayload();
        assertEquals("T1", claims.get("tid", String.class));
    }
}
