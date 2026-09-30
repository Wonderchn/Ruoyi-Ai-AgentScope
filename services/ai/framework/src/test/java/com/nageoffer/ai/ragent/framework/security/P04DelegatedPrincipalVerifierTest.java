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
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 委托验签与租户上下文的单测（Spec §7.1 第一层 / §8.6 Unit 模式必跑类之一）。
 *
 * <p>错误映射按 N02 两段口径断言：凭证本身的问题一律 401 {@code DELEGATION_INVALID}；
 * 缺 {@code tid}/{@code mid}/{@code pv} 这类上下文缺失为 403 {@code TENANT_CONTEXT_MISSING}。
 *
 * <p>测试类带 {@code dev} 标签（用户长期要求）。
 */
@Tag("dev")
class P04DelegatedPrincipalVerifierTest {

    private static final Instant NOW = Instant.parse("2026-09-30T00:00:00Z");
    private static final String KID = "p04-platform-k1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static KeyPair trusted;
    private static KeyPair foreign;
    private static DelegationVerifier verifier;

    @BeforeAll
    static void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        trusted = generator.generateKeyPair();
        foreign = generator.generateKeyPair();

        // 公钥按运行期约定落成 PEM 文件；写到模块 target 下，避免 Windows 临时目录权限问题
        Path dir = Path.of("target", "p04-test");
        Files.createDirectories(dir);
        Path pem = dir.resolve("platform-public.pem");
        Files.writeString(pem, toPem(trusted), StandardCharsets.UTF_8);

        P04SecurityProperties properties = new P04SecurityProperties();
        properties.getDelegation().setPublicKeyPath(pem.toAbsolutePath().toString());
        verifier = new DelegationVerifier(properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static String toPem(KeyPair pair) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(pair.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n";
    }

    /** 按需省略声明，用于构造各类负例。 */
    private static String token(String kid, String issuer, String audience, String subject, String jti,
                                boolean withExpiration, Instant issuedAt, Instant notBefore, Instant expiresAt,
                                String tenantId, String membershipId, Integer policyVersion,
                                KeyPair signingKey) {
        var builder = Jwts.builder()
                .header().keyId(kid).type("JWT").and()
                .issuer(issuer)
                .audience().add(audience).and()
                .issuedAt(Date.from(issuedAt))
                .notBefore(Date.from(notBefore));
        if (subject != null) {
            builder.subject(subject);
        }
        if (jti != null) {
            builder.id(jti);
        }
        if (withExpiration) {
            builder.expiration(Date.from(expiresAt));
        }
        if (tenantId != null) {
            builder.claim("tid", tenantId);
        }
        if (membershipId != null) {
            builder.claim("mid", membershipId);
        }
        if (policyVersion != null) {
            builder.claim("pv", policyVersion);
        }
        builder.claim("scope", List.of("rag.chat.submit"));
        return builder.signWith(signingKey.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private static String legalToken() {
        return token(KID, "platform", "ai", "sub-u1", UUID.randomUUID().toString(), true,
                NOW, NOW, NOW.plusSeconds(60), "T1", "M1", 1, trusted);
    }

    private static P04AiErrorCode codeOf(Executable executable) {
        P04AiException ex = assertThrows(P04AiException.class, executable::run);
        return ex.errorCode();
    }

    private interface Executable {
        void run() throws Throwable;
    }

    @Test
    @DisplayName("合法委托验签通过并得到 principal（principalId = membershipId）")
    void legalTokenYieldsPrincipal() {
        DelegationVerifier.DelegationClaims claims = verifier.verify(legalToken());
        DelegatedPrincipal principal = verifier.requireTenantContext(claims);

        assertEquals("T1", principal.tenantId());
        assertEquals("sub-u1", principal.subject());
        assertEquals("M1", principal.membershipId());
        assertEquals(1, principal.policyVersion());
        assertTrue(principal.scopes().contains("rag.chat.submit"));
        assertFalse(principal.jti().isBlank());
    }

    @Test
    @DisplayName("未携带凭证是 401 AUTH_REQUIRED")
    void missingTokenIsAuthRequired() {
        assertEquals(P04AiErrorCode.AUTH_REQUIRED, codeOf(() -> verifier.verify(null)));
        assertEquals(P04AiErrorCode.AUTH_REQUIRED, codeOf(() -> verifier.verify("   ")));
    }

    @Test
    @DisplayName("坏签名 / 过期 / 未来 nbf 一律 401 DELEGATION_INVALID")
    void cryptographicAndTemporalFailuresAreInvalid() {
        String foreignSigned = token(KID, "platform", "ai", "sub-u1", UUID.randomUUID().toString(), true,
                NOW, NOW, NOW.plusSeconds(60), "T1", "M1", 1, foreign);
        assertEquals(P04AiErrorCode.DELEGATION_INVALID, codeOf(() -> verifier.verify(foreignSigned)));

        String expired = token(KID, "platform", "ai", "sub-u1", UUID.randomUUID().toString(), true,
                NOW.minusSeconds(600), NOW.minusSeconds(600), NOW.minusSeconds(300), "T1", "M1", 1, trusted);
        assertEquals(P04AiErrorCode.DELEGATION_INVALID, codeOf(() -> verifier.verify(expired)));

        String future = token(KID, "platform", "ai", "sub-u1", UUID.randomUUID().toString(), true,
                NOW, NOW.plusSeconds(600), NOW.plusSeconds(900), "T1", "M1", 1, trusted);
        assertEquals(P04AiErrorCode.DELEGATION_INVALID, codeOf(() -> verifier.verify(future)));
    }

    @Test
    @DisplayName("错误 iss / 错误 aud / 未知 kid 一律 401 DELEGATION_INVALID")
    void issuerAudienceKidWhitelistsAreEnforced() {
        String wrongIssuer = token(KID, "not-platform", "ai", "sub-u1", UUID.randomUUID().toString(), true,
                NOW, NOW, NOW.plusSeconds(60), "T1", "M1", 1, trusted);
        assertEquals(P04AiErrorCode.DELEGATION_INVALID, codeOf(() -> verifier.verify(wrongIssuer)));

        String wrongAudience = token(KID, "platform", "other-service", "sub-u1", UUID.randomUUID().toString(), true,
                NOW, NOW, NOW.plusSeconds(60), "T1", "M1", 1, trusted);
        assertEquals(P04AiErrorCode.DELEGATION_INVALID, codeOf(() -> verifier.verify(wrongAudience)));

        String unknownKid = token("p04-unknown-kid", "platform", "ai", "sub-u1", UUID.randomUUID().toString(), true,
                NOW, NOW, NOW.plusSeconds(60), "T1", "M1", 1, trusted);
        assertEquals(P04AiErrorCode.DELEGATION_INVALID, codeOf(() -> verifier.verify(unknownKid)));
    }

    @Test
    @DisplayName("alg=none 的无签名 JWS 被拒绝为 401 DELEGATION_INVALID")
    void algNoneIsRejected() throws Exception {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "none");
        header.put("typ", "JWT");
        header.put("kid", KID);
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "platform");
        claims.put("aud", "ai");
        claims.put("sub", "sub-u1");
        claims.put("tid", "T1");
        claims.put("mid", "M1");
        claims.put("pv", 1);
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("iat", NOW.getEpochSecond());
        claims.put("exp", NOW.plusSeconds(60).getEpochSecond());
        String algNone = base64Url(MAPPER.writeValueAsBytes(header))
                + "." + base64Url(MAPPER.writeValueAsBytes(claims)) + ".";

        assertEquals(P04AiErrorCode.DELEGATION_INVALID, codeOf(() -> verifier.verify(algNone)));
    }

    @Test
    @DisplayName("缺 sub / 缺 jti / 缺 exp 是 401 DELEGATION_INVALID（N02 后组）")
    void missingTokenClaimsAreInvalid() {
        String noSubject = token(KID, "platform", "ai", null, UUID.randomUUID().toString(), true,
                NOW, NOW, NOW.plusSeconds(60), "T1", "M1", 1, trusted);
        assertEquals(P04AiErrorCode.DELEGATION_INVALID, codeOf(() -> verifier.verify(noSubject)));

        String noJti = token(KID, "platform", "ai", "sub-u1", null, true,
                NOW, NOW, NOW.plusSeconds(60), "T1", "M1", 1, trusted);
        assertEquals(P04AiErrorCode.DELEGATION_INVALID, codeOf(() -> verifier.verify(noJti)));

        String noExpiration = token(KID, "platform", "ai", "sub-u1", UUID.randomUUID().toString(), false,
                NOW, NOW, NOW.plusSeconds(60), "T1", "M1", 1, trusted);
        assertEquals(P04AiErrorCode.DELEGATION_INVALID, codeOf(() -> verifier.verify(noExpiration)));
    }

    @Test
    @DisplayName("缺 tid / 缺 mid / 缺 pv 是 403 TENANT_CONTEXT_MISSING（N02 前组）")
    void missingTenantContextIsForbidden() {
        String noTenant = token(KID, "platform", "ai", "sub-u1", UUID.randomUUID().toString(), true,
                NOW, NOW, NOW.plusSeconds(60), null, "M1", 1, trusted);
        assertEquals(P04AiErrorCode.TENANT_CONTEXT_MISSING,
                codeOf(() -> verifier.requireTenantContext(verifier.verify(noTenant))));

        String noMembership = token(KID, "platform", "ai", "sub-u1", UUID.randomUUID().toString(), true,
                NOW, NOW, NOW.plusSeconds(60), "T1", null, 1, trusted);
        assertEquals(P04AiErrorCode.TENANT_CONTEXT_MISSING,
                codeOf(() -> verifier.requireTenantContext(verifier.verify(noMembership))));

        String noPolicyVersion = token(KID, "platform", "ai", "sub-u1", UUID.randomUUID().toString(), true,
                NOW, NOW, NOW.plusSeconds(60), "T1", "M1", null, trusted);
        assertEquals(P04AiErrorCode.TENANT_CONTEXT_MISSING,
                codeOf(() -> verifier.requireTenantContext(verifier.verify(noPolicyVersion))));
    }

    @Test
    @DisplayName("TTL 上限 60 秒：恰好 60 通过，61 秒被拒（阻断修复 Spec §3.3）")
    void ttlCeilingIsEnforced() {
        String exactly60 = token(KID, "platform", "ai", "sub-u1", UUID.randomUUID().toString(), true,
                NOW, NOW, NOW.plusSeconds(60), "T1", "M1", 1, trusted);
        assertEquals("T1", verifier.verify(exactly60).tenantId());

        String over61 = token(KID, "platform", "ai", "sub-u1", UUID.randomUUID().toString(), true,
                NOW, NOW, NOW.plusSeconds(61), "T1", "M1", 1, trusted);
        assertEquals(P04AiErrorCode.DELEGATION_INVALID, codeOf(() -> verifier.verify(over61)));
    }

    @Test
    @DisplayName("缺 iat 被拒（§3.3 必需 claim）")
    void missingIssuedAtIsRejected() {
        String noIssuedAt = Jwts.builder()
                .header().keyId(KID).type("JWT").and()
                .issuer("platform")
                .audience().add("ai").and()
                .subject("sub-u1")
                .id(UUID.randomUUID().toString())
                .notBefore(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(60)))
                .claim("tid", "T1")
                .claim("mid", "M1")
                .claim("pv", 1)
                .claim("scope", List.of("rag.chat.submit"))
                .signWith(trusted.getPrivate(), Jwts.SIG.RS256)
                .compact();
        assertEquals(P04AiErrorCode.DELEGATION_INVALID, codeOf(() -> verifier.verify(noIssuedAt)));
    }

    @Test
    @DisplayName("iat 来自未来（超出 skew）被拒（§3.3）")
    void issuedAtInTheFutureIsRejected() {
        String futureIat = token(KID, "platform", "ai", "sub-u1", UUID.randomUUID().toString(), true,
                NOW.plusSeconds(120), NOW.plusSeconds(120), NOW.plusSeconds(180), "T1", "M1", 1, trusted);
        assertEquals(P04AiErrorCode.DELEGATION_INVALID, codeOf(() -> verifier.verify(futureIat)));
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
