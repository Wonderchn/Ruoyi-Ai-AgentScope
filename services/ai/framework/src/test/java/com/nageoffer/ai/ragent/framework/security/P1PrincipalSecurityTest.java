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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 生产委托主体过滤器与服务身份核验的单测（U06/P1.2c，直接 new 驱动，不起容器）。
 *
 * <p>覆盖：坏签名 401、缺租户声明 403、重复 jti 401、replay 存储异常 503、
 * replay/epoch 端口缺失 503、无 epoch 行 503、合法令牌 → 主体就位且请求后清理、
 * 非目标路径不受影响、{@link ServiceIdentityVerifier} 常量时间比较与空配置拒绝。
 *
 * <p>测试类带 {@code dev} 标签（用户长期要求）。
 */
@Tag("dev")
class P1PrincipalSecurityTest {

    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final String KID = "platform-prod-k1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static KeyPair trusted;
    private static KeyPair foreign;
    private static ProductionDelegationProperties properties;

    private ProductionReplayGuard replayGuard;
    private DelegatedPrincipalFilter.AclVersionSource aclVersionSource;
    private DelegatedPrincipalFilter filter;

    @BeforeAll
    static void setUpClass() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        trusted = generator.generateKeyPair();
        foreign = generator.generateKeyPair();

        // 公钥按运行期约定落成 PEM 文件；写到模块 target 下，避免 Windows 临时目录权限问题
        Path dir = Path.of("target", "p1-test");
        Files.createDirectories(dir);
        Path pem = dir.resolve("platform-public.pem");
        Files.writeString(pem, toPem(trusted), StandardCharsets.UTF_8);

        properties = new ProductionDelegationProperties();
        properties.setEnabled(true);
        properties.setPublicKeyPath(pem.toAbsolutePath().toString());
        properties.setServiceCredential("p1-test-service-credential");
    }

    @BeforeEach
    void setUp() {
        PrincipalContext.clear();
        replayGuard = mock(ProductionReplayGuard.class);
        aclVersionSource = Map.of("T1", 7)::get;
        filter = filterWith(replayGuard, aclVersionSource);
    }

    /** 每次构造全新的 bean 工厂：guard/source 可单独缺席，模拟生产装配缺端口的情形。 */
    private DelegatedPrincipalFilter filterWith(ProductionReplayGuard guard,
                                                DelegatedPrincipalFilter.AclVersionSource acl) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        if (guard != null) {
            beanFactory.addBean("replayGuard", guard);
        }
        if (acl != null) {
            beanFactory.addBean("aclVersionSource", acl);
        }
        return new DelegatedPrincipalFilter(properties, Clock.fixed(NOW, ZoneOffset.UTC),
                beanFactory.getBeanProvider(ProductionReplayGuard.class),
                beanFactory.getBeanProvider(DelegatedPrincipalFilter.AclVersionSource.class));
    }

    private static String toPem(KeyPair pair) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(pair.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n";
    }

    /** 按需省略声明，用于构造各类负例。 */
    private static String token(String subject, String jti, String tenantId, String membershipId,
                                Integer policyVersion, KeyPair signingKey) {
        var builder = Jwts.builder()
                .header().keyId(KID).type("JWT").and()
                .issuer("platform")
                .audience().add("ai").and()
                .subject(subject)
                .issuedAt(Date.from(NOW))
                .notBefore(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(60)))
                .claim("scope", List.of("ai:kb:read"));
        if (jti != null) {
            builder.id(jti);
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
        return builder.signWith(signingKey.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private static String legalToken() {
        return token("42", UUID.randomUUID().toString(), "T1", "platform:T1:42", 1, trusted);
    }

    private static MockHttpServletRequest request(String uri, String bearer) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        if (bearer != null) {
            request.addHeader("Authorization", "Bearer " + bearer);
        }
        return request;
    }

    /** 一次过滤的结果：链路内看到的主体 + 响应。 */
    private record Outcome(AtomicReference<ExecutionPrincipal> principalInChain,
                           MockHttpServletResponse response,
                           boolean chainInvoked) {

        int status() {
            return response.getStatus();
        }

        String errorCode() throws Exception {
            JsonNode node = MAPPER.readTree(response.getContentAsString());
            return node.path("data").path("errorCode").asText(null);
        }
    }

    private Outcome run(MockHttpServletRequest request) throws Exception {
        AtomicReference<ExecutionPrincipal> seen = new AtomicReference<>();
        boolean[] invoked = {false};
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            invoked[0] = true;
            seen.set(PrincipalContext.get());
        });
        return new Outcome(seen, response, invoked[0]);
    }

    @Test
    @DisplayName("坏签名（外来私钥签发）→ 401 DELEGATION_INVALID，链路不执行")
    void badSignatureIs401() throws Exception {
        String foreignSigned = token("42", UUID.randomUUID().toString(), "T1", "platform:T1:42", 1, foreign);
        Outcome outcome = run(request("/internal/ai/v1/knowledge-bases", foreignSigned));
        assertEquals(401, outcome.status());
        assertEquals("DELEGATION_INVALID", outcome.errorCode());
        assertNull(PrincipalContext.get());
    }

    @Test
    @DisplayName("未携带凭证 → 401 AUTH_REQUIRED")
    void missingTokenIs401() throws Exception {
        Outcome outcome = run(request("/internal/ai/v1/knowledge-bases", null));
        assertEquals(401, outcome.status());
        assertEquals("AUTH_REQUIRED", outcome.errorCode());
    }

    @Test
    @DisplayName("缺 tid / mid / pv → 403 TENANT_CONTEXT_MISSING")
    void missingTenantClaimsAre403() throws Exception {
        List<String[]> cases = List.of(
                new String[]{null, "platform:T1:42", "1"},
                new String[]{"T1", null, "1"},
                new String[]{"T1", "platform:T1:42", null});
        for (String[] tokenCase : cases) {
            Integer pv = tokenCase[2] == null ? null : Integer.parseInt(tokenCase[2]);
            String t = token("42", UUID.randomUUID().toString(), tokenCase[0], tokenCase[1], pv, trusted);
            String label = "case=" + String.join(",", tokenCase);
            Outcome outcome = run(request("/internal/ai/v1/knowledge-bases", t));
            assertEquals(403, outcome.status(), label);
            assertEquals("TENANT_CONTEXT_MISSING", outcome.errorCode(), label);
        }
        assertNull(PrincipalContext.get());
    }

    @Test
    @DisplayName("mid 与 canonical platform:<tid>:<uid> 不一致 → 403 MEMBERSHIP_INVALID")
    void mismatchedMembershipIs403() throws Exception {
        String t = token("42", UUID.randomUUID().toString(), "T1", "platform:T1:43", 1, trusted);
        Outcome outcome = run(request("/internal/ai/v1/knowledge-bases", t));
        assertEquals(403, outcome.status());
        assertEquals("MEMBERSHIP_INVALID", outcome.errorCode());
    }

    @Test
    @DisplayName("重复 jti（replay 返回 false）→ 401 DELEGATION_INVALID")
    void replayedJtiIs401() throws Exception {
        when(replayGuard.consume(anyString(), anyString(), anyString(), any())).thenReturn(false);
        Outcome outcome = run(request("/internal/ai/v1/knowledge-bases", legalToken()));
        assertEquals(401, outcome.status());
        assertEquals("DELEGATION_INVALID", outcome.errorCode());
        assertNull(PrincipalContext.get());
    }

    @Test
    @DisplayName("replay 存储抛异常 → 503（不得当作未消费放行）")
    void replayStoreFailureIs503() throws Exception {
        when(replayGuard.consume(anyString(), anyString(), anyString(), any()))
                .thenThrow(new java.sql.SQLException("store down"));
        Outcome outcome = run(request("/internal/ai/v1/knowledge-bases", legalToken()));
        assertEquals(503, outcome.status());
        assertEquals("AUTHORIZATION_UNAVAILABLE", outcome.errorCode());
        assertNull(PrincipalContext.get());
    }

    @Test
    @DisplayName("replay 端口缺失（无 bean）→ 503 fail-closed")
    void missingReplayGuardIs503() throws Exception {
        filter = filterWith(null, aclVersionSource);
        Outcome outcome = run(request("/internal/ai/v1/knowledge-bases", legalToken()));
        assertEquals(503, outcome.status());
        assertEquals("AUTHORIZATION_UNAVAILABLE", outcome.errorCode());
    }

    @Test
    @DisplayName("epoch 缺失（无行返回 0）与端口异常 → 一律 503")
    void missingAclEpochIs503() throws Exception {
        when(replayGuard.consume(anyString(), anyString(), anyString(), any())).thenReturn(true);

        filter = filterWith(replayGuard, tenantId -> 0);
        Outcome outcome = run(request("/internal/ai/v1/knowledge-bases", legalToken()));
        assertEquals(503, outcome.status());
        assertEquals("AUTHORIZATION_UNAVAILABLE", outcome.errorCode());

        filter = filterWith(replayGuard, tenantId -> {
            throw new IllegalStateException("db down");
        });
        outcome = run(request("/internal/ai/v1/knowledge-bases", legalToken()));
        assertEquals(503, outcome.status());
        assertNull(PrincipalContext.get());
    }

    @Test
    @DisplayName("合法令牌：链路内主体就位，请求结束后上下文清理")
    void legalTokenEstablishesAndCleansPrincipal() throws Exception {
        when(replayGuard.consume(eq("platform"), anyString(), eq("T1"), any())).thenReturn(true);
        String jti = UUID.randomUUID().toString();
        Outcome outcome = run(request("/internal/ai/v1/knowledge-bases",
                token("42", jti, "T1", "platform:T1:42", 3, trusted)));

        assertEquals(200, outcome.status());
        assertTrue(outcome.chainInvoked());
        ExecutionPrincipal principal = outcome.principalInChain().get();
        assertNotNull(principal);
        assertEquals("T1", principal.tenantId());
        assertEquals("42", principal.userId());
        assertEquals("platform:T1:42", principal.membershipId());
        assertEquals(3, principal.policyVersion());
        assertEquals(7, principal.aclVersion());
        assertEquals(jti, principal.jti());
        assertEquals("platform", principal.issuer());
        assertTrue(principal.hasScope("ai:kb:read"));
        // 请求结束：上下文已清理（previous 为 null → restore 清空）
        assertNull(PrincipalContext.get());
    }

    @Test
    @DisplayName("非目标路径不受过滤器影响：无凭证也直接放行且不建立主体")
    void nonTargetPathIsUntouched() throws Exception {
        filter = filterWith(null, null);
        Outcome outcome = run(request("/api/anything", null));
        assertTrue(outcome.chainInvoked(), "chain must run for non-protected paths");
        assertNull(outcome.principalInChain().get());
        assertNull(PrincipalContext.get());
    }

    @Test
    @DisplayName("ServiceIdentityVerifier：正确凭证通过，错误/缺失凭证 401（常量时间比较）")
    void serviceIdentityVerifierConstantTimeComparison() {
        ServiceIdentityVerifier verifier = new ServiceIdentityVerifier(properties);
        verifier.verify("p1-test-service-credential");

        P04AiException mismatch = assertThrows(P04AiException.class,
                () -> verifier.verify("p1-test-service-credentiaL"));
        assertEquals(P04AiErrorCode.DELEGATION_INVALID, mismatch.errorCode());
        assertEquals(P04AiErrorCode.AUTH_REQUIRED,
                assertThrows(P04AiException.class, () -> verifier.verify(null)).errorCode());
        assertEquals(P04AiErrorCode.AUTH_REQUIRED,
                assertThrows(P04AiException.class, () -> verifier.verify("  ")).errorCode());
    }

    @Test
    @DisplayName("ServiceIdentityVerifier：空配置=全部拒绝（即使呈现值非空）")
    void serviceIdentityVerifierRejectsBlankConfiguration() {
        ProductionDelegationProperties blank = new ProductionDelegationProperties();
        blank.setServiceCredential("");
        ServiceIdentityVerifier verifier = new ServiceIdentityVerifier(blank);
        P04AiException ex = assertThrows(P04AiException.class, () -> verifier.verify("anything"));
        assertEquals(P04AiErrorCode.DELEGATION_INVALID, ex.errorCode());

        ProductionDelegationProperties unset = new ProductionDelegationProperties();
        ServiceIdentityVerifier unsetVerifier = new ServiceIdentityVerifier(unset);
        assertEquals(P04AiErrorCode.DELEGATION_INVALID,
                assertThrows(P04AiException.class, () -> unsetVerifier.verify("anything")).errorCode());
    }
}
