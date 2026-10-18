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

package org.ruoyi.aiweb.embedded;

import com.nageoffer.ai.ragent.flow.dao.entity.AiFlowNodeDO;
import com.nageoffer.ai.ragent.flow.dao.entity.AiFlowWorkflowDO;
import com.nageoffer.ai.ragent.flow.dao.mapper.AiFlowComponentMapper;
import com.nageoffer.ai.ragent.flow.dao.mapper.AiFlowEdgeMapper;
import com.nageoffer.ai.ragent.flow.dao.mapper.AiFlowNodeMapper;
import com.nageoffer.ai.ragent.flow.dao.mapper.AiFlowWorkflowMapper;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ai.api.AiExecutionFacts;
import org.ruoyi.ai.api.identity.AiIdentityPort;
import org.ruoyi.aiintegration.config.AiIntegrationProperties;
import org.ruoyi.aiintegration.identity.CurrentPrincipalResolver;
import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.ruoyi.aiintegration.web.AiGatewayController;
import org.ruoyi.aiweb.AiWebEmbeddedConfiguration;
import org.ruoyi.aiweb.security.AiInternalAccessBoundaryFilter;
import org.ruoyi.aiweb.transport.AiDeliveryReleaser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R17 F13:4：公开工作流 start 输入 schema 经网关<b>真实可达</b>的端到端判据（红先行）。
 *
 * <p>形态照 {@code LocalFlowPublishRouteDispatchTest}：真实 Tomcat + 真实网关 +
 * 真实本地传输 + 真实内嵌装配（{@code AiEmbeddedFlowConfiguration}），仅 Mapper 用替身。
 *
 * <p><b>关键判据</b>：
 * <ul>
 *   <li>{@code GET /flows/public/{uuid}/inputs} 命中新 ROUTES 行（动作 {@code flow.read}），
 *       不被 {@code GET /flows/public/{uuid}} 吃掉；</li>
 *   <li>200 是整数 {@code ApiEnvelope}（{@code code:200}）且带两个交付回执头
 *       （缺任一网关必 503 ⇒ 到达 200 即证明）；</li>
 *   <li>不可见时 404 + 同一文案「工作流不存在」，且<b>在铸回执之前</b>返回；</li>
 *   <li>动作门：只持 {@code ai:flow:list} 的身份不得读 inputs（需 {@code ai:flow:read}）。</li>
 * </ul>
 */
@Tag("dev")
class LocalFlowPublicInputsDispatchTest {

    private static final String TENANT = "T1";
    private static final String USER = "2101";
    private static final String MEMBER = "platform:T1:2101";
    private static final int PV = 7;
    private static final int AV = 3;

    private static final String WORKFLOW_UUID = "wf-public-inputs-1";
    private static final String WORKFLOW_TITLE = "f13-4-public-inputs";
    private static final String INPUT_NAME = "var_user_input";

    private static final Set<String> ALL_PERMISSIONS = Set.of("ai:flow:list", "ai:flow:read");

    private static final AtomicReference<Set<String>> CURRENT_PERMISSIONS =
            new AtomicReference<>(ALL_PERMISSIONS);

    private static Tomcat tomcat;
    private static int port;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static final AiFlowWorkflowMapper workflowMapper = mock(AiFlowWorkflowMapper.class);
    private static final AiFlowNodeMapper nodeMapper = mock(AiFlowNodeMapper.class);
    private static final AiFlowEdgeMapper edgeMapper = mock(AiFlowEdgeMapper.class);
    private static final AiFlowComponentMapper componentMapper = mock(AiFlowComponentMapper.class);
    private static final RevocationGuard revocations = mock(RevocationGuard.class);
    private static final List<String> permits = new ArrayList<>();
    private static final List<String> acknowledgements = new ArrayList<>();

    @BeforeAll
    static void startContainer() throws Exception {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.register(FixtureConfig.class);
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "fixture", Map.of("ai.integration.enabled", "true", "ai.integration.transport", "local",
                        "p2.enabled", "true")));

        tomcat = new Tomcat();
        tomcat.setBaseDir("target/local-flow-public-inputs-tomcat");
        tomcat.setPort(0);
        File docBase = new File("target/local-flow-public-inputs-docroot");
        docBase.mkdirs();
        org.apache.catalina.Context ctx = tomcat.addContext("", docBase.getAbsolutePath());

        org.apache.tomcat.util.descriptor.web.FilterDef def =
                new org.apache.tomcat.util.descriptor.web.FilterDef();
        def.setFilterName("aiInternalAccessBoundaryFilter");
        def.setFilter(new AiInternalAccessBoundaryFilter());
        ctx.addFilterDef(def);
        org.apache.tomcat.util.descriptor.web.FilterMap map =
                new org.apache.tomcat.util.descriptor.web.FilterMap();
        map.setFilterName("aiInternalAccessBoundaryFilter");
        map.addURLPatternDecoded("/internal/ai/v1/*");
        map.setDispatcher("REQUEST");
        ctx.addFilterMap(map);

        org.apache.catalina.core.StandardWrapper wrapper =
                (org.apache.catalina.core.StandardWrapper) Tomcat.addServlet(ctx, "dispatcher",
                        new org.springframework.web.servlet.DispatcherServlet(context));
        wrapper.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/", "dispatcher");

        tomcat.start();
        port = tomcat.getConnector().getLocalPort();
    }

    @AfterAll
    static void stopContainer() throws LifecycleException {
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    @BeforeEach
    void resetFixtures() {
        reset(workflowMapper, nodeMapper, edgeMapper, componentMapper, revocations);
        permits.clear();
        acknowledgements.clear();
        CURRENT_PERMISSIONS.set(ALL_PERMISSIONS);
        when(revocations.enter(any(), anyString(), anyString())).thenAnswer(invocation -> {
            permits.add(invocation.getArgument(1) + "/" + invocation.getArgument(2));
            return new RevocationGuard.Operation(revocations,
                    java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString());
        });
        when(workflowMapper.selectOne(any())).thenReturn(workflow());
        when(nodeMapper.selectList(any())).thenReturn(List.of(startNode(), llmNode(), endNode()));
    }

    private static AiFlowWorkflowDO workflow() {
        AiFlowWorkflowDO row = new AiFlowWorkflowDO();
        row.setId(41L);
        row.setUuid(WORKFLOW_UUID);
        row.setTitle(WORKFLOW_TITLE);
        row.setUserId(2101L);
        row.setTenantId(TENANT);
        row.setIsDeleted(0);
        row.setIsPublic(1);
        row.setIsEnable(1);
        return row;
    }

    private static AiFlowNodeDO startNode() {
        AiFlowNodeDO row = node("n-start", "开始", "{\"type\":\"START\"}",
                "{\"user_inputs\":[{\"uuid\":\"i-1\",\"name\":\"" + INPUT_NAME
                        + "\",\"title\":\"用户输入\",\"type\":1,\"required\":true,\"max_length\":1000}]}");
        return row;
    }

    private static AiFlowNodeDO llmNode() {
        return node("n-llm", "生成回答", "{\"type\":\"LLM\",\"prompt\":\"{input}\"}", "{}");
    }

    private static AiFlowNodeDO endNode() {
        return node("n-end", "结束", "{\"type\":\"END\"}", "{}");
    }

    private static AiFlowNodeDO node(String uuid, String title, String nodeConfig, String inputConfig) {
        AiFlowNodeDO row = new AiFlowNodeDO();
        row.setId((long) Math.abs(uuid.hashCode()));
        row.setUuid(uuid);
        row.setWorkflowId(41L);
        row.setUserId(2101L);
        row.setTitle(title);
        row.setNodeConfig(nodeConfig);
        row.setInputConfig(inputConfig);
        row.setIsDeleted(0);
        row.setTenantId(TENANT);
        return row;
    }

    // ------------------------------------------------------------------ 正例

    @Test
    @DisplayName("公开 inputs 端点经网关 200：整数信封 + START 输入 schema + 节点视图")
    void publicInputsIsReachableThroughTheGateway() throws Exception {
        HttpResponse<String> response = get("/api/ai/v1/flows/public/" + WORKFLOW_UUID + "/inputs");

        assertThat(response.statusCode())
                .as("404=未登记/503=内层不命中或缺回执/403=scope；实际体=%s", response.body())
                .isEqualTo(200);
        assertThat(response.body())
                .contains("\"code\":200")
                .doesNotContain("\"code\":\"0\"")
                .contains(WORKFLOW_TITLE)
                .contains(INPUT_NAME)
                .contains("\"inputs\"")
                .contains("\"nodes\"")
                .contains("\"type\":\"START\"")
                .doesNotContain("nodeConfig")
                .doesNotContain("node_config")
                .doesNotContain("prompt")
                .doesNotContain("tenantId")
                .doesNotContain("userId");
    }

    @Test
    @DisplayName("公开 inputs 恰铸一次 flow.read 许可并 ACK（字节分支）")
    void publicInputsMintsOneFlowReadPermitAndAcknowledgesIt() throws Exception {
        assertJsonOk("/api/ai/v1/flows/public/" + WORKFLOW_UUID + "/inputs");

        assertThat(permits)
                .as("动作必须是 flow.read（不是 flow.list/flow.run）")
                .containsExactly("flow.read/flow:public:inputs:" + WORKFLOW_UUID);
        awaitAcknowledgements(permits.size());
        assertThat(acknowledgements)
                .as("字节分支的每个 200 必须恰有一次本地回执")
                .hasSize(permits.size());
    }

    @Test
    @DisplayName("新端点不被公开详情 {uuid} 吃掉：路径逐字命中 /inputs")
    void inputsRouteIsNotSwallowedByPublicDetail() throws Exception {
        HttpResponse<String> response = get("/api/ai/v1/flows/public/" + WORKFLOW_UUID + "/inputs");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .as("公开详情 VO 没有 inputs/nodes；出现即证明命中了新端点")
                .contains("\"inputs\"")
                .contains("\"nodes\"");
        verify(workflowMapper).selectOne(any());
        verify(nodeMapper).selectList(any());
    }

    // ------------------------------------------------------------------ 负例

    @Test
    @DisplayName("不可见：404 + 同一文案，且在铸回执之前返回")
    void invisibleWorkflowIs404WithOneMessageAndNoPermit() throws Exception {
        when(workflowMapper.selectOne(any())).thenReturn(null);

        HttpResponse<String> response = get("/api/ai/v1/flows/public/wf-hidden/inputs");

        assertThat(response.statusCode()).as("私有/停用/软删/跨租户统一 404").isEqualTo(404);
        assertThat(response.body())
                .contains("\"code\":404")
                .contains("工作流不存在");
        assertThat(permits).as("不可见时先取数失败：不得留下 ACTIVE 许可").isEmpty();
        verify(nodeMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("动作门：只持 ai:flow:list 的身份读公开 inputs 一律 403")
    void publicInputsRequireFlowReadNotJustFlowList() throws Exception {
        CURRENT_PERMISSIONS.set(Set.of("ai:flow:list"));
        try {
            assertThat(get("/api/ai/v1/flows/public/" + WORKFLOW_UUID + "/inputs").statusCode())
                    .as("flow.list 不得覆盖 flow.read").isEqualTo(403);
            clearInvocations(workflowMapper, nodeMapper);
            verify(workflowMapper, never()).selectOne(any());
            verify(nodeMapper, never()).selectList(any());
        } finally {
            CURRENT_PERMISSIONS.set(ALL_PERMISSIONS);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void awaitAcknowledgements(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (acknowledgements.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    private static void assertJsonOk(String path) throws Exception {
        HttpResponse<String> response = get(path);
        assertThat(response.statusCode())
                .as("GET %s：404=未登记/503=内层不命中或缺回执；实际体=%s", path, response.body())
                .isEqualTo(200);
        assertThat(response.body()).contains("\"code\":200").doesNotContain("\"code\":\"0\"");
    }

    private static HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer synthetic-session")
                .GET()
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    // ------------------------------------------------------------------ fixture

    @Configuration
    @EnableWebMvc
    @Import({AiGatewayController.class, AiWebEmbeddedConfiguration.class, AiEmbeddedFlowConfiguration.class})
    static class FixtureConfig {

        @Bean
        AiIntegrationProperties aiIntegrationProperties() {
            AiIntegrationProperties properties = new AiIntegrationProperties();
            properties.setEnabled(true);
            properties.setTransport("local");
            properties.setForwardTimeoutMillis(2000);
            return properties;
        }

        @Bean
        CurrentPrincipalResolver currentPrincipalResolver() {
            return () -> Optional.of(new CurrentPrincipalResolver.CurrentMember(TENANT, USER, MEMBER));
        }

        @Bean
        PlatformIdentitySource platformIdentitySource() {
            return new PlatformIdentitySource() {
                @Override
                public TenantState tenantState(String tenantId) {
                    return TenantState.ENABLED;
                }

                @Override
                public PlatformIdentity membership(String tenantId, String subject, String membershipId) {
                    if (!MEMBER.equals(membershipId)) {
                        return null;
                    }
                    return new PlatformIdentity(TENANT, USER, MEMBER, true, CURRENT_PERMISSIONS.get(), PV);
                }
            };
        }

        @Bean
        AiIdentityPort aiIdentityPort() {
            return () -> Optional.of(new AiExecutionFacts(TENANT, USER, MEMBER, PV, AV,
                    CURRENT_PERMISSIONS.get()));
        }

        @Bean
        AiDeliveryReleaser aiDeliveryReleaser() {
            return (tenantId, memberId, permitId, operationId) ->
                    acknowledgements.add(permitId + ":" + operationId);
        }

        @Bean
        RevocationGuard revocationGuard() {
            return revocations;
        }

        @Bean
        DeliveryPermits deliveryPermits(ObjectProvider<RevocationGuard> guard) {
            return new DeliveryPermits(guard);
        }

        @Bean
        com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper();
        }

        @Bean
        AiFlowWorkflowMapper aiFlowWorkflowMapper() {
            return workflowMapper;
        }

        @Bean
        AiFlowNodeMapper aiFlowNodeMapper() {
            return nodeMapper;
        }

        @Bean
        AiFlowEdgeMapper aiFlowEdgeMapper() {
            return edgeMapper;
        }

        @Bean
        AiFlowComponentMapper aiFlowComponentMapper() {
            return componentMapper;
        }
    }
}
