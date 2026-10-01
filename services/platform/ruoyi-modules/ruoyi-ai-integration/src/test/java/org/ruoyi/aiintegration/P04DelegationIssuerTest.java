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

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.aiintegration.delegation.DelegationIssuer;
import org.ruoyi.aiintegration.delegation.DelegationSigningKeys;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主源集<b>合法</b>签发器的单测（Spec §8.6 Unit 模式必跑类之一）。
 *
 * <p>负例变体的铸造已按阻断修复 Spec §2.3 移入测试源集，其断言见
 * {@code org.ruoyi.aiintegration.p04.P04NegativeDelegationMinterTest}；本类只覆盖
 * 合法路径与 TTL 边界。测试类带 {@code dev} 标签。
 */
@Tag("dev")
class P04DelegationIssuerTest {

    private static final Instant FIXED = Instant.parse("2026-09-30T00:00:00Z");

    private DelegationSigningKeys keys;
    private DelegationIssuer issuer;

    @BeforeEach
    void setUp() {
        keys = DelegationSigningKeys.generate("p04-platform-k1");
        issuer = new DelegationIssuer(keys, Clock.fixed(FIXED, ZoneOffset.UTC));
    }

    private JwtParser parser(PublicKey key) {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(DelegationIssuer.ISSUER)
                .requireAudience(DelegationIssuer.AUDIENCE)
                .clock(() -> Date.from(FIXED.plusSeconds(10)))
                .clockSkewSeconds(30)
                .build();
    }

    private DelegationIssuer.Issued legal() {
        return issuer.issue("T1", "sub-u1", "M1", List.of(DelegationIssuer.SCOPE_RAG_CHAT_SUBMIT), 1, null);
    }

    @Test
    @DisplayName("合法委托可验签并携带全部必需声明")
    void legalDelegationCarriesExpectedClaims() {
        DelegationIssuer.Issued issued = legal();
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
        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getNotBefore());
        assertNotNull(claims.getExpiration());
    }

    @Test
    @DisplayName("每次签发都产生新的 jti（F2：重试必须换 jti）")
    void eachIssueGetsFreshJti() {
        DelegationIssuer.Issued first = legal();
        DelegationIssuer.Issued second = legal();
        assertNotEquals(first.jti(), second.jti());
        assertNotEquals(first.token(), second.token());
    }

    @Test
    @DisplayName("TTL 默认 60 秒，显式 30 秒被遵守")
    void ttlDefaultsAndExplicitValue() {
        Claims defaulted = parser(keys.publicKey()).parseSignedClaims(legal().token()).getPayload();
        assertEquals(60L, defaulted.getExpiration().toInstant().getEpochSecond()
                - defaulted.getIssuedAt().toInstant().getEpochSecond());

        DelegationIssuer.Issued shortLived = issuer.issue("T1", "sub-u1", "M1",
                List.of(DelegationIssuer.SCOPE_RAG_CHAT_SUBMIT), 1, 30);
        Claims claims = parser(keys.publicKey()).parseSignedClaims(shortLived.token()).getPayload();
        assertEquals(30L, claims.getExpiration().toInstant().getEpochSecond()
                - claims.getIssuedAt().toInstant().getEpochSecond());
    }

    @Test
    @DisplayName("TTL 越界在签发侧即被拒绝（0 与 61）")
    void ttlOutOfRangeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> issuer.issue("T1", "sub-u1", "M1",
                List.of(DelegationIssuer.SCOPE_RAG_CHAT_SUBMIT), 1, 0));
        assertThrows(IllegalArgumentException.class, () -> issuer.issue("T1", "sub-u1", "M1",
                List.of(DelegationIssuer.SCOPE_RAG_CHAT_SUBMIT), 1, DelegationIssuer.MAX_TTL_SECONDS + 1));
    }

    @Test
    @DisplayName("身份声明缺失在签发侧即被拒绝（负例只能由测试侧铸造）")
    void missingIdentityIsRefusedByIssuer() {
        assertThrows(IllegalArgumentException.class, () -> issuer.issue(null, "sub-u1", "M1",
                List.of(DelegationIssuer.SCOPE_RAG_CHAT_SUBMIT), 1, null));
        assertThrows(IllegalArgumentException.class, () -> issuer.issue("T1", " ", "M1",
                List.of(DelegationIssuer.SCOPE_RAG_CHAT_SUBMIT), 1, null));
        assertThrows(IllegalArgumentException.class, () -> issuer.issue("T1", "sub-u1", null,
                List.of(DelegationIssuer.SCOPE_RAG_CHAT_SUBMIT), 1, null));
        assertThrows(IllegalArgumentException.class, () -> issuer.issue("T1", "sub-u1", "M1",
                List.of(DelegationIssuer.SCOPE_RAG_CHAT_SUBMIT), null, null));
        assertThrows(IllegalArgumentException.class, () -> issuer.issue("T1", "sub-u1", "M1",
                List.of(), 1, null));
    }

    @Test
    @DisplayName("公钥 PEM 可被重新导入并用于验签")
    void publicKeyPemRoundTrips() throws Exception {
        String pem = keys.publicKeyPem();
        assertTrue(pem.startsWith("-----BEGIN PUBLIC KEY-----"));

        String base64 = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        PublicKey imported = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));

        Claims claims = parser(imported).parseSignedClaims(legal().token()).getPayload();
        assertEquals("T1", claims.get("tid", String.class));
    }
}
