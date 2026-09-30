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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>测试专用</b>负例铸造器的单测（阻断修复 Spec §2.3）。
 *
 * <p>它证明了"铸造负例的能力现在只存在于测试源集，且这些凭证确实会被拒绝"——
 * 与主源集合法签发器的测试（{@code P04DelegationIssuerTest}）互补。
 */
@Tag("dev")
class P04NegativeDelegationMinterTest {

    private static final Instant FIXED = Instant.parse("2026-09-30T00:00:00Z");
    private static final List<String> SCOPES = List.of(DelegationIssuer.SCOPE_RAG_CHAT_SUBMIT);

    private DelegationSigningKeys keys;
    private NegativeDelegationMinter minter;

    @BeforeEach
    void setUp() {
        keys = DelegationSigningKeys.generate("p04-platform-k1");
        DelegationIssuer issuer = new DelegationIssuer(keys, Clock.fixed(FIXED, ZoneOffset.UTC));
        minter = new NegativeDelegationMinter(keys, issuer, new ObjectMapper(),
                Clock.fixed(FIXED, ZoneOffset.UTC));
    }

    private JwtParser parser() {
        return Jwts.parser()
                .verifyWith(keys.publicKey())
                .requireIssuer(DelegationIssuer.ISSUER)
                .requireAudience(DelegationIssuer.AUDIENCE)
                .clock(() -> Date.from(FIXED.plusSeconds(10)))
                .clockSkewSeconds(30)
                .build();
    }

    private NegativeDelegationMinter.Minted mint(DelegationVariant variant) {
        return minter.mint(variant, "T1", "sub-u1", "M1", SCOPES, 1, null);
    }

    @Test
    @DisplayName("NONE 走主源集签发器，产出可验签的合法凭证")
    void noneDelegatesToMainIssuer() {
        Jws<Claims> jws = parser().parseSignedClaims(mint(DelegationVariant.NONE).token());
        assertEquals("T1", jws.getPayload().get("tid", String.class));
        assertEquals("RS256", jws.getHeader().getAlgorithm());
    }

    @Test
    @DisplayName("密码学层负例全部被解析器拒绝（外来私钥 / alg=none / 过期 / 未来 nbf / 错 iss / 错 aud）")
    void cryptographicNegativesAreRejected() {
        for (DelegationVariant variant : List.of(DelegationVariant.FOREIGN_KEY, DelegationVariant.ALG_NONE,
                DelegationVariant.EXPIRED, DelegationVariant.NOT_YET_VALID,
                DelegationVariant.WRONG_ISSUER, DelegationVariant.WRONG_AUDIENCE)) {
            NegativeDelegationMinter.Minted minted = mint(variant);
            assertThrows(JwtException.class, () -> parser().parseSignedClaims(minted.token()),
                    "variant " + variant + " must be rejected");
        }
        assertTrue(mint(DelegationVariant.ALG_NONE).token().endsWith("."),
                "alg=none 令牌的签名段必须为空");
    }

    @Test
    @DisplayName("未知 kid 可见于头部，由校验方按白名单拒绝")
    void unknownKidIsVisibleInHeader() {
        Jws<Claims> jws = parser().parseSignedClaims(mint(DelegationVariant.UNKNOWN_KID).token());
        assertEquals("p04-unknown-kid", jws.getHeader().getKeyId());
    }

    @Test
    @DisplayName("缺声明负例确实缺对应 claim（tid/mid/pv/sub/jti/exp/iat）")
    void missingClaimNegativesReallyOmitClaims() {
        assertNull(parser().parseSignedClaims(mint(DelegationVariant.MISSING_TENANT).token())
                .getPayload().get("tid", String.class));
        assertNull(parser().parseSignedClaims(mint(DelegationVariant.MISSING_MEMBERSHIP).token())
                .getPayload().get("mid", String.class));
        assertNull(parser().parseSignedClaims(mint(DelegationVariant.MISSING_POLICY_VERSION).token())
                .getPayload().get("pv", Integer.class));
        assertNull(parser().parseSignedClaims(mint(DelegationVariant.MISSING_SUBJECT).token())
                .getPayload().getSubject());
        assertNull(parser().parseSignedClaims(mint(DelegationVariant.MISSING_JTI).token())
                .getPayload().getId());
        assertNull(parser().parseSignedClaims(mint(DelegationVariant.MISSING_EXPIRATION).token())
                .getPayload().getExpiration());
        assertNull(parser().parseSignedClaims(mint(DelegationVariant.MISSING_ISSUED_AT).token())
                .getPayload().getIssuedAt());
    }

    @Test
    @DisplayName("TTL 超上限负例的 exp-iat 确实为 61 秒（触发 AI 侧 TTL 上限）")
    void ttlOverCeilingHasOversizedWindow() {
        Claims claims = parser().parseSignedClaims(mint(DelegationVariant.TTL_OVER_CEILING).token()).getPayload();
        assertEquals(DelegationIssuer.MAX_TTL_SECONDS + 1L,
                claims.getExpiration().toInstant().getEpochSecond()
                        - claims.getIssuedAt().toInstant().getEpochSecond());
    }
}
