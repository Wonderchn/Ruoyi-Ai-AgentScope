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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.aiintegration.config.AiIntegrationProperties;
import org.ruoyi.aiintegration.delegation.ProductionSigningKeySource;
import org.ruoyi.aiintegration.identity.CurrentPrincipalResolver;
import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.ruoyi.aiintegration.web.AiGatewayClient;
import org.ruoyi.aiintegration.web.AiGatewayController;
import org.ruoyi.aiintegration.web.ApiResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W4-9 / 维护者裁决 <b>A2-ter</b> 的门禁判据：Agent 目录动作要求
 * <b>"scope + 平台管理身份"两个独立条件</b>同时成立。
 *
 * <p>背景（<b>数据面事实勘误，仅更正事实、行为不变</b>）：{@code ai_agent_profile}/
 * {@code ai_agent_prompt}/{@code ai_agent_skill} 三表自 V7 起<b>已有 {@code tenant_id}</b>
 * （NOT NULL；模板行显式落在保留租户 {@code __public_template__}），唯一键为租户作用域
 * ——"三表没有 tenant_id、{@code uk_agent_name} 全局唯一"的旧描述<b>不再成立</b>。
 * 第二道门的理由因此改写为：这族动作面向<b>平台管理的 Agent 目录面</b>，若只按 scope 放行，
 * 任何被误授 {@code ai:agent:write} 的租户成员都能改到平台管理面内容（与 AGENTS.md
 * 「缺少租户或主体时必须拒绝」同向）。维护者要求不变：<b>证明普通租户即使误获该 scope
 * 仍被拒绝</b>。
 *
 * <p>本类用 mock 直接驱动控制器（不起容器），三条判据：
 * <ol>
 *   <li>持有 {@code ai:agent:list} 但<b>不是</b>平台管理身份 ⇒ <b>403 FORBIDDEN</b>，
 *       且<b>不触碰转发</b>；</li>
 *   <li>平台管理身份 + 同一 scope ⇒ <b>通过第二道门</b>（转发发生、状态码透传）；</li>
 *   <li>对照：<b>非</b>目录动作（{@code kb.list}）不受该门影响 ⇒ 200 透传
 *       （证明改动面没有扩大到其它动作）。</li>
 * </ol>
 */
@Tag("dev")
class W49AgentCatalogAdminGateTest {

    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final String BASE_URL = "http://ai.test";
    private static final String ENVELOPE = "{\"code\":200,\"msg\":\"success\",\"data\":{}}";

    private CurrentPrincipalResolver principalResolver;
    private PlatformIdentitySource identitySource;
    private AiGatewayClient client;
    private AiGatewayController controller;

    @BeforeAll
    static void setUpClass() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        KeyPair pair = generator.generateKeyPair();
        Path dir = Path.of("target", "w49-admin-gate-test");
        Files.createDirectories(dir);
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(pair.getPrivate().getEncoded());
        Files.writeString(dir.resolve("platform-private.pem"),
                "-----BEGIN " + "PRIVATE KEY-----\n" + base64 + "\n-----END " + "PRIVATE KEY-----\n",
                StandardCharsets.UTF_8);
    }

    @BeforeEach
    void setUp() {
        AiIntegrationProperties properties = new AiIntegrationProperties();
        properties.setServiceCredential("synthetic-service-credential");
        properties.setEnabled(true);
        properties.setAiBaseUrl(BASE_URL);
        properties.setForwardTimeoutMillis(2000);
        properties.setMaxForwardBodyBytes(1024 * 1024);

        principalResolver = mock(CurrentPrincipalResolver.class);
        identitySource = mock(PlatformIdentitySource.class);
        client = mock(AiGatewayClient.class);

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("identitySource", identitySource);
        ObjectProvider<PlatformIdentitySource> identityProvider =
                beanFactory.getBeanProvider(PlatformIdentitySource.class);

        String keyPath = Path.of("target", "w49-admin-gate-test", "platform-private.pem")
                .toAbsolutePath().toString();
        StaticListableBeanFactory keyFactory = new StaticListableBeanFactory();
        keyFactory.addBean("signingKeys",
                new ProductionSigningKeySource(keyPath, Clock.fixed(NOW, ZoneOffset.UTC)));
        ObjectProvider<ProductionSigningKeySource> keyProvider =
                keyFactory.getBeanProvider(ProductionSigningKeySource.class);

        controller = new AiGatewayController(principalResolver, identityProvider, keyProvider,
                client, properties);
    }

    /** 已登录、成员有效、身份带给定 scope；平台管理身份默认 false（接口默认值 = fail-closed）。 */
    private void stubMember(Set<String> scopes) {
        when(principalResolver.resolveCurrentMember()).thenReturn(Optional.of(
                new CurrentPrincipalResolver.CurrentMember("T1", "42", "platform:T1:42")));
        when(identitySource.membership("T1", "42", "platform:T1:42")).thenReturn(
                new PlatformIdentitySource.PlatformIdentity("T1", "42", "platform:T1:42",
                        true, scopes, 3));
        when(client.forward(any())).thenReturn(new AiGatewayClient.ForwardResponse(200, ENVELOPE));
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader("Authorization", "satoken-evidence");
        return request;
    }

    @SuppressWarnings("unchecked")
    private static String errorCode(ResponseEntity<?> response) {
        ApiResponse<Map<String, Object>> body = (ApiResponse<Map<String, Object>>) response.getBody();
        assertNotNull(body);
        return String.valueOf(body.data().get("errorCode"));
    }

    @Test
    @DisplayName("A2-ter-①：租户成员<b>误获</b> ai:agent:list 但不是平台管理身份 ⇒ 403 FORBIDDEN，且不转发")
    void tenantMemberWithScopeButNotPlatformAdminIsRejected() {
        stubMember(Set.of("ai:agent:list"));
        // 未 stub isPlatformAdmin() ⇒ 接口默认 false（与"未覆写的实现"等价）

        ResponseEntity<?> response = controller.gateway(
                request("GET", "/api/ai/v1/agent-catalog/agents"), null, null);

        assertNotNull(response);
        assertEquals(403, response.getStatusCode().value(),
                "只有 scope、没有平台管理身份 ⇒ 必须拒绝");
        assertEquals("FORBIDDEN", errorCode(response));
        verify(client, never()).forward(any());
    }

    @Test
    @DisplayName("A2-ter-②：平台管理身份 + 同一 scope ⇒ 通过第二道门（转发发生、状态码透传）")
    void platformAdminWithScopePassesTheAdminGate() {
        stubMember(Set.of("ai:agent:list"));
        when(principalResolver.isPlatformAdmin()).thenReturn(true);

        ResponseEntity<?> response = controller.gateway(
                request("GET", "/api/ai/v1/agent-catalog/agents"), null, null);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value(),
                "平台管理身份 + scope 齐备 ⇒ 不得被门禁拦下");
        verify(client).forward(any());
    }

    @Test
    @DisplayName("A2-ter-③ 对照：非目录动作（kb.list）不受该门影响 ⇒ 200 透传")
    void nonCatalogActionIsUnaffectedByAdminGate() {
        stubMember(Set.of("ai:kb:list"));
        // isPlatformAdmin() 仍为默认 false

        ResponseEntity<?> response = controller.gateway(
                request("GET", "/api/ai/v1/knowledge-bases"), null, null);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value(),
                "平台管理身份门只作用于目录动作，不得波及既有动作");
        verify(client).forward(any());
    }

    @Test
    @DisplayName("A2-ter-④：目录动作仍然要求 scope —— 平台管理身份但无 scope ⇒ 403")
    void platformAdminWithoutScopeIsStillRejected() {
        stubMember(Set.of("ai:kb:list"));
        when(principalResolver.isPlatformAdmin()).thenReturn(true);

        ResponseEntity<?> response = controller.gateway(
                request("GET", "/api/ai/v1/agent-catalog/agents"), null, null);

        assertNotNull(response);
        assertEquals(403, response.getStatusCode().value(),
                "两个条件独立：平台管理身份不能替代 scope");
        verify(client, never()).forward(any());
    }

    /**
     * F09:0 新增详情面：{@code GET /agent-catalog/agents/{id}} 复用 {@code agent.read}，
     * 因此必须与既有目录动作同受 A2-ter 双门（而不是因为"只是读一条"就放宽）。
     *
     * <p>红锚语义：本路由未由 T0 补进 {@code AiGatewayController.ROUTES} 之前，
     * 网关先以 404 收敛（路径不在白名单），所以这里拿不到 403 —— 该用例会红。
     */
    @Test
    @DisplayName("F09:0-详情：持有 ai:agent:read 但非平台管理身份 ⇒ 403，且不转发")
    void agentDetailRouteKeepsTheAdminGate() {
        stubMember(Set.of("ai:agent:read"));
        // isPlatformAdmin() 保持默认 false：本用例钉的是第二道门对**新增详情路由**同样成立

        ResponseEntity<?> response = controller.gateway(
                request("GET", "/api/ai/v1/agent-catalog/agents/a-1"), null, null);

        assertNotNull(response);
        assertEquals(403, response.getStatusCode().value(),
                "详情复用 agent.read，必须与既有目录动作同受平台管理身份门");
        assertEquals("FORBIDDEN", errorCode(response));
        verify(client, never()).forward(any());
    }

    /**
     * F09:0 新增导出面：{@code GET /agent-catalog/agents/export} 复用 {@code agent.read}。
     *
     * <p>同样以 404 为红锚（路由未登记前不可达）；登记后平台管理身份 + scope 必须放行。
     */
    @Test
    @DisplayName("F09:0-导出：平台管理身份 + ai:agent:read ⇒ 通过第二道门（转发发生）")
    void agentExportRoutePassesTheAdminGate() {
        stubMember(Set.of("ai:agent:read"));
        when(principalResolver.isPlatformAdmin()).thenReturn(true);

        ResponseEntity<?> response = controller.gateway(
                request("GET", "/api/ai/v1/agent-catalog/agents/export"), null, null);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value(),
                "平台管理身份 + scope 齐备 ⇒ 导出路由不得被门禁或白名单拦下");
        verify(client).forward(any());
    }
}
