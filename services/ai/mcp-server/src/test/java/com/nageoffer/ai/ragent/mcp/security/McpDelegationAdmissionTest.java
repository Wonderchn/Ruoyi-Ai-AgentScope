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

package com.nageoffer.ai.ragent.mcp.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.DelegatedPrincipalFilter;
import com.nageoffer.ai.ragent.framework.security.ProductionDelegationProperties;
import com.nageoffer.ai.ragent.framework.security.ProductionReplayGuard;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 9099 受保护面的"入场"组合判据（SPEC-9099 §7，直接 new 驱动，不起容器）：
 * 本模块的两端口实现（重放账本/租户 epoch）与 framework 委托验签过滤器装配后，
 * 合法委托建立主体；无凭证/坏签名/重放/未开通租户全部 fail-closed。
 *
 * <p>铸造口径与 framework {@code P1PrincipalSecurityTest} 同款（一次性测试密钥对，
 * 私钥仅存内存；PEM 落模块 target）；口令仅经构造参数注入，不落日志。
 */
@Tag("dev")
class McpDelegationAdmissionTest {

    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final String KID = "platform-prod-k1";
    private static final String SERVICE_CREDENTIAL = "mcp9099-test-service-credential";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROTECTED_URI = "/internal/ai/v1/mcp";

    private static KeyPair trusted;
    private static KeyPair foreign;
    private static ProductionDelegationProperties properties;

    @BeforeAll
    static void setUpClass() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        trusted = generator.generateKeyPair();
        foreign = generator.generateKeyPair();

        // 公钥按运行期约定落成 PEM 文件；写到模块 target 下，避免 Windows 临时目录权限问题
        Path dir = Path.of("target", "mcp9099-test");
        Files.createDirectories(dir);
        Path pem = dir.resolve("platform-public.pem");
        Files.writeString(pem, toPem(trusted.getPublic().getEncoded()), StandardCharsets.UTF_8);

        properties = new ProductionDelegationProperties();
        properties.setEnabled(true);
        properties.setPublicKeyPath(pem.toAbsolutePath().toString());
        properties.setServiceCredential(SERVICE_CREDENTIAL);
    }

    private ProductionReplayGuard replayGuard;
    private Map<String, Integer> tenantEpochs;

    @BeforeEach
    void setUp() {
        PrincipalContext.clear();
        replayGuard = new InMemoryReplayGuard();
        tenantEpochs = Map.of("T1", 7);
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

    private DelegatedPrincipalFilter filter() {
        return filterWith(replayGuard, tenantId -> tenantEpochs.getOrDefault(tenantId, 0));
    }

    private static String toPem(byte[] encoded) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(encoded);
        return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n";
    }

    private static String token(String subject, String jti, String tenantId, String membershipId,
                                Integer policyVersion, KeyPair signingKey, Instant issuedAt, Instant expiresAt) {
        var builder = Jwts.builder()
                .header().keyId(KID).type("JWT").and()
                .issuer("platform")
                .audience().add("ai").and()
                .subject(subject)
                .id(jti)
                .issuedAt(Date.from(issuedAt))
                .notBefore(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .claim("tid", tenantId)
                .claim("mid", membershipId)
                .claim("pv", policyVersion)
                .claim("scope", List.of("ai:agent:execute"));
        return builder.signWith(signingKey.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private static String legalToken() {
        return token("42", UUID.randomUUID().toString(), "T1", "platform:T1:42", 1,
                trusted, NOW, NOW.plusSeconds(60));
    }

    private static MockHttpServletRequest request(String bearer) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PROTECTED_URI);
        request.addHeader("X-P04-Service-Credential", SERVICE_CREDENTIAL);
        if (bearer != null) {
            request.addHeader("Authorization", "Bearer " + bearer);
        }
        return request;
    }

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

    private Outcome run(DelegatedPrincipalFilter filter, MockHttpServletRequest request) throws Exception {
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
    @DisplayName("合法委托+服务凭证：进入链路、主体就位（租户/成员/版本/epoch/scope），请求后清理")
    void legalDelegationAdmitsAndEstablishesPrincipal() throws Exception {
        Outcome outcome = run(filter(), request(legalToken()));

        assertThat(outcome.chainInvoked()).isTrue();
        ExecutionPrincipal principal = outcome.principalInChain().get();
        assertThat(principal).isNotNull();
        assertThat(principal.tenantId()).isEqualTo("T1");
        assertThat(principal.userId()).isEqualTo("42");
        assertThat(principal.membershipId()).isEqualTo("platform:T1:42");
        assertThat(principal.policyVersion()).isEqualTo(1);
        assertThat(principal.aclVersion()).isEqualTo(7);
        assertThat(principal.scopes()).containsExactly("ai:agent:execute");
        assertThat(PrincipalContext.get()).as("请求结束后主体必须清理").isNull();
    }

    @Test
    @DisplayName("未携带委托凭证：401 AUTH_REQUIRED，链路不进入")
    void missingBearerRejected401() throws Exception {
        Outcome outcome = run(filter(), request(null));

        assertThat(outcome.status()).isEqualTo(401);
        assertThat(outcome.errorCode()).isEqualTo("AUTH_REQUIRED");
        assertThat(outcome.chainInvoked()).isFalse();
    }

    @Test
    @DisplayName("服务凭证不匹配：401 DELEGATION_INVALID（与空配置同形），链路不进入")
    void wrongServiceCredentialRejected401() throws Exception {
        MockHttpServletRequest request = request(legalToken());
        request.removeHeader("X-P04-Service-Credential");
        request.addHeader("X-P04-Service-Credential", "wrong-credential");

        Outcome outcome = run(filter(), request);

        assertThat(outcome.status()).isEqualTo(401);
        assertThat(outcome.errorCode()).isEqualTo("DELEGATION_INVALID");
        assertThat(outcome.chainInvoked()).isFalse();
    }

    @Test
    @DisplayName("外来私钥签发（坏签名）：401 DELEGATION_INVALID，链路不进入")
    void foreignSignedRejected401() throws Exception {
        String foreignToken = token("42", UUID.randomUUID().toString(), "T1", "platform:T1:42", 1,
                foreign, NOW, NOW.plusSeconds(60));

        Outcome outcome = run(filter(), request(foreignToken));

        assertThat(outcome.status()).isEqualTo(401);
        assertThat(outcome.errorCode()).isEqualTo("DELEGATION_INVALID");
        assertThat(outcome.chainInvoked()).isFalse();
    }

    @Test
    @DisplayName("过期凭证：401 DELEGATION_INVALID（TTL 窗口外）")
    void expiredTokenRejected401() throws Exception {
        String expired = token("42", UUID.randomUUID().toString(), "T1", "platform:T1:42", 1,
                trusted, NOW.minusSeconds(120), NOW.minusSeconds(60));

        Outcome outcome = run(filter(), request(expired));

        assertThat(outcome.status()).isEqualTo(401);
        assertThat(outcome.errorCode()).isEqualTo("DELEGATION_INVALID");
    }

    @Test
    @DisplayName("重放：同一凭证第二次使用 401（jti 原子消费），链路只进入一次")
    void replayedJtiRejected401() throws Exception {
        String token = legalToken();

        Outcome first = run(filter(), request(token));
        Outcome second = run(filter(), request(token));

        assertThat(first.chainInvoked()).isTrue();
        assertThat(second.status()).isEqualTo(401);
        assertThat(second.errorCode()).isEqualTo("DELEGATION_INVALID");
        assertThat(second.chainInvoked()).isFalse();
    }

    @Test
    @DisplayName("未开通租户（无 epoch 行）：503 AUTHORIZATION_UNAVAILABLE，链路不进入")
    void unprovisionedTenantRejected503() throws Exception {
        String unknownTenant = token("43", UUID.randomUUID().toString(), "T2", "platform:T2:43", 1,
                trusted, NOW, NOW.plusSeconds(60));

        Outcome outcome = run(filter(), request(unknownTenant));

        assertThat(outcome.status()).isEqualTo(503);
        assertThat(outcome.errorCode()).isEqualTo("AUTHORIZATION_UNAVAILABLE");
        assertThat(outcome.chainInvoked()).isFalse();
    }

    @Test
    @DisplayName("重放账本不可用：503（绝不当作未消费放行）")
    void replayStoreUnavailableRejected503() throws Exception {
        ProductionReplayGuard broken = (issuer, jti, tenantId, expiresAt) -> {
            throw new IllegalStateException("db down");
        };

        Outcome outcome = run(filterWith(broken, tenantId -> 7), request(legalToken()));

        assertThat(outcome.status()).isEqualTo(503);
        assertThat(outcome.errorCode()).isEqualTo("AUTHORIZATION_UNAVAILABLE");
        assertThat(outcome.chainInvoked()).isFalse();
    }

    @Test
    @DisplayName("重放端口缺席（装配未提供）：503 fail-closed")
    void missingReplayPortRejected503() throws Exception {
        Outcome outcome = run(filterWith(null, tenantId -> 7), request(legalToken()));

        assertThat(outcome.status()).isEqualTo(503);
        assertThat(outcome.errorCode()).isEqualTo("AUTHORIZATION_UNAVAILABLE");
        assertThat(outcome.chainInvoked()).isFalse();
    }

    /** 一次性测试替身：语义同 Jdbc 实现（首次 true / 重复 false），仅存内存。 */
    private static final class InMemoryReplayGuard implements ProductionReplayGuard {

        private final Set<String> consumed = ConcurrentHashMap.newKeySet();

        @Override
        public boolean consume(String issuer, String jti, String tenantId, Instant expiresAt) {
            return consumed.add(issuer + "|" + jti);
        }
    }
}
