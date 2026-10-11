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

import com.nageoffer.ai.ragent.flow.dao.entity.AiFlowComponentDO;
import com.nageoffer.ai.ragent.flow.dao.entity.AiFlowEdgeDO;
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
import java.nio.charset.StandardCharsets;
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
 * S2-F13-A1：AIFlow 图 CRUD 面 <b>12 条路由</b>（节点 4 + 边 4 + 组件库 4）经网关<b>真实可达</b>
 * 的端到端判据（红先行样本，形态照 {@code LocalKnowledgeAdminRouteDispatchTest}／
 * {@code LocalDocumentRouteDispatchTest}）。
 *
 * <p><b>为什么必须有这一条。</b>本切片新增 12 条白名单路由 + 一条 canonical 动作
 * （{@code flow.node.write} → {@code ai:flow:design}，权限行 V32/7203）。静态检查
 * （白名单覆盖、动作契约）证明不了"接得住"：路由登记了但控制器不是 bean、GET 缺交付回执、
 * 信封不是整数 code —— 三种都会在真实请求上炸成 404/503，而源码扫描全绿。
 * 故这里用<b>真实 Tomcat + 真实网关 + 真实本地传输 + 真实内嵌装配</b>
 * （{@code AiEmbeddedFlowConfiguration}）逐条发请求。
 *
 * <p><b>动作映射钉子（本切片的关键负例）</b>：
 * <ul>
 *   <li>持 {@code ai:flow:write}（旧四动作之一）<b>不得</b>写图 —— 图写必须持
 *       {@code ai:flow:design}（否则"新增动作"退化成把 flow.write 扩容）；</li>
 *   <li>持 {@code ai:flow:design}<b>不得</b>读图 —— 读面复用 {@code flow.read}，不因设计动作扩大。</li>
 * </ul>
 *
 * <p><b>负例</b>：白名单外形状 404、内层前缀对外 404、未登录 401。
 */
@Tag("dev")
class LocalFlowGraphRouteDispatchTest {

    private static final String TENANT = "T1";
    private static final String USER = "2101";
    private static final String MEMBER = "platform:T1:2101";
    private static final int PV = 7;
    private static final int AV = 3;

    private static final String WORKFLOW_UUID = "wf-f13a1";

    /** 图面所需权限：读 = ai:flow:read（既有行），写 = ai:flow:design（V32 新行）。 */
    private static final Set<String> ALL_PERMISSIONS = Set.of("ai:flow:read", "ai:flow:design");

    /** 请求期权限集合：网关与内层都读它，故"缺 scope"负例改的是真的判定输入。 */
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
        tomcat.setBaseDir("target/local-flow-graph-route-tomcat");
        tomcat.setPort(0);
        File docBase = new File("target/local-flow-graph-route-docroot");
        docBase.mkdirs();
        org.apache.catalina.Context ctx = tomcat.addContext("", docBase.getAbsolutePath());

        // 与生产装配同形：内层前缀直接访问一律 404（关闭体）
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
        when(workflowMapper.selectOne(any())).thenReturn(workflowRow());
        when(nodeMapper.selectList(any())).thenReturn(List.of(nodeRow()));
        when(nodeMapper.selectOne(any())).thenReturn(nodeRow());
        when(nodeMapper.insert(any(AiFlowNodeDO.class))).thenReturn(1);
        when(nodeMapper.update(any(), any())).thenReturn(1);
        when(edgeMapper.selectList(any())).thenReturn(List.of(edgeRow()));
        when(edgeMapper.selectOne(any())).thenReturn(edgeRow());
        when(edgeMapper.insert(any(AiFlowEdgeDO.class))).thenReturn(1);
        when(edgeMapper.update(any(), any())).thenReturn(1);
        when(componentMapper.selectList(any())).thenReturn(List.of(componentRow()));
        when(componentMapper.selectOne(any())).thenReturn(componentRow());
        when(componentMapper.insert(any(AiFlowComponentDO.class))).thenReturn(1);
        when(componentMapper.update(any(), any())).thenReturn(1);
    }

    private static AiFlowWorkflowDO workflowRow() {
        AiFlowWorkflowDO row = new AiFlowWorkflowDO();
        row.setId(41L);
        row.setUuid(WORKFLOW_UUID);
        row.setUserId(Long.valueOf(USER));
        row.setTenantId(TENANT);
        row.setIsDeleted(0);
        row.setIsPublic(0);
        row.setIsEnable(1);
        row.setTitle("f13a1-workflow");
        return row;
    }

    /** 列表替身行：uuid 即断言用标记（读路径返回的唯一来源）。 */
    private static AiFlowNodeDO nodeRow() {
        AiFlowNodeDO row = new AiFlowNodeDO();
        row.setId(51L);
        row.setUuid("f13-node-row");
        row.setWorkflowId(41L);
        row.setUserId(Long.valueOf(USER));
        row.setTitle("f13-node-row");
        row.setInputConfig("{}");
        row.setPositionX(0.0);
        row.setPositionY(0.0);
        row.setWorkflowComponentId(0L);
        row.setIsDeleted(0);
        row.setTenantId(TENANT);
        return row;
    }

    private static AiFlowEdgeDO edgeRow() {
        AiFlowEdgeDO row = new AiFlowEdgeDO();
        row.setId(61L);
        row.setUuid("f13-edge-row");
        row.setWorkflowId(41L);
        row.setSourceNodeUuid("n-1");
        row.setSourceHandle("out");
        row.setTargetNodeUuid("n-2");
        row.setIsDeleted(0);
        row.setTenantId(TENANT);
        return row;
    }

    private static AiFlowComponentDO componentRow() {
        AiFlowComponentDO row = new AiFlowComponentDO();
        row.setId(71L);
        row.setUuid("f13-comp-row");
        row.setName("Start");
        row.setTitle("f13-comp-row");
        row.setRemark("");
        row.setDisplayOrder(0);
        row.setIsEnable(1);
        row.setIsDeleted(0);
        row.setTenantId(TENANT);
        return row;
    }

    // ------------------------------------------------------------------ 正例：12 条逐条可达

    @Test
    @DisplayName("12 条图路由逐条经网关 200：节点 4 + 边 4 + 组件库 4")
    void allTwelveGraphRoutesAreReachableThroughTheGateway() throws Exception {
        // ---- 节点面 4 ----
        assertJsonOk("GET", "/api/ai/v1/flows/" + WORKFLOW_UUID + "/nodes", null, "f13-node-row");
        assertJsonOk("POST", "/api/ai/v1/flows/" + WORKFLOW_UUID + "/nodes",
                "{\"title\":\"f13-node-created\"}", "f13-node-created");
        assertJsonOk("PUT", "/api/ai/v1/flows/" + WORKFLOW_UUID + "/nodes/n-1",
                "{\"title\":\"f13-node-renamed\"}", "f13-node-renamed");
        assertJsonOk("DELETE", "/api/ai/v1/flows/" + WORKFLOW_UUID + "/nodes/n-1", null, null);

        // ---- 边面 4 ----
        assertJsonOk("GET", "/api/ai/v1/flows/" + WORKFLOW_UUID + "/edges", null, "f13-edge-row");
        assertJsonOk("POST", "/api/ai/v1/flows/" + WORKFLOW_UUID + "/edges",
                "{\"sourceNodeUuid\":\"n-1\",\"targetNodeUuid\":\"n-2\"}", null);
        assertJsonOk("PUT", "/api/ai/v1/flows/" + WORKFLOW_UUID + "/edges/e-1",
                "{\"sourceNodeUuid\":\"n-1\",\"sourceHandle\":\"out\",\"targetNodeUuid\":\"n-2\"}", null);
        assertJsonOk("DELETE", "/api/ai/v1/flows/" + WORKFLOW_UUID + "/edges/e-1", null, null);

        // ---- 组件库面 4 ----
        assertJsonOk("GET", "/api/ai/v1/flow-components", null, "f13-comp-row");
        assertJsonOk("POST", "/api/ai/v1/flow-components",
                "{\"name\":\"Start\",\"title\":\"f13-comp-created\"}", "f13-comp-created");
        assertJsonOk("PUT", "/api/ai/v1/flow-components/c-1",
                "{\"name\":\"Start\",\"title\":\"f13-comp-renamed\"}", "f13-comp-renamed");
        assertJsonOk("DELETE", "/api/ai/v1/flow-components/c-1", null, null);
    }

    @Test
    @DisplayName("GET 走字节分支由控制器自铸回执并本地 ACK；写面零 permit 零 ACK")
    void graphReadsMintDeliveryReceiptsAndWritesMintNone() throws Exception {
        assertJsonOk("GET", "/api/ai/v1/flows/" + WORKFLOW_UUID + "/nodes", null, null);
        assertJsonOk("GET", "/api/ai/v1/flows/" + WORKFLOW_UUID + "/edges", null, null);
        assertJsonOk("GET", "/api/ai/v1/flow-components", null, null);

        assertThat(permits)
                .as("每条 200 的 GET 恰铸一次许可，动作=flow.read，引用=控制器自铸形状")
                .containsExactlyInAnyOrder(
                        "flow.read/flow:" + WORKFLOW_UUID,
                        "flow.read/flow:" + WORKFLOW_UUID,
                        "flow.read/flow:components");
        awaitAcknowledgements(permits.size());
        assertThat(acknowledgements)
                .as("字节分支的每个 200 必须恰有一次本地回执（缺则内层没铸头，多则许可泄漏）")
                .hasSize(permits.size());

        int before = permits.size();
        assertJsonOk("POST", "/api/ai/v1/flows/" + WORKFLOW_UUID + "/nodes", "{\"title\":\"x\"}", null);
        assertJsonOk("PUT", "/api/ai/v1/flows/" + WORKFLOW_UUID + "/nodes/n-1", "{\"title\":\"y\"}", null);
        assertJsonOk("DELETE", "/api/ai/v1/flows/" + WORKFLOW_UUID + "/nodes/n-1", null, null);
        assertThat(permits)
                .as("写面走 JSON 分支：网关不会为它释放许可，内层因此不得铸造（否则逐请求泄漏 ACTIVE 许可）")
                .hasSize(before);
    }

    // ------------------------------------------------------------------ 负例：动作映射

    @Test
    @DisplayName("图写必须持 ai:flow:design：只持 flow.write/delete/read 身份一律 403（在 handler 之前）")
    void graphWritesRequireTheDesignActionNotJustFlowWrite() throws Exception {
        CURRENT_PERMISSIONS.set(Set.of("ai:flow:list", "ai:flow:read", "ai:flow:write", "ai:flow:delete"));
        try {
            // 读面（flow.read）不受影响
            assertThat(get("/api/ai/v1/flows/" + WORKFLOW_UUID + "/nodes").statusCode())
                    .as("读图在 flow.read 之下，不因缺 design 而拒").isEqualTo(200);
            clearInvocations(workflowMapper);

            assertThat(post("/api/ai/v1/flows/" + WORKFLOW_UUID + "/nodes", "{\"title\":\"x\"}").statusCode())
                    .as("持 flow.write（旧动作）不得写图").isEqualTo(403);
            assertThat(put("/api/ai/v1/flows/" + WORKFLOW_UUID + "/nodes/n-1", "{\"title\":\"x\"}").statusCode())
                    .isEqualTo(403);
            assertThat(delete("/api/ai/v1/flows/" + WORKFLOW_UUID + "/nodes/n-1").statusCode())
                    .isEqualTo(403);
            assertThat(post("/api/ai/v1/flows/" + WORKFLOW_UUID + "/edges",
                    "{\"sourceNodeUuid\":\"n-1\",\"targetNodeUuid\":\"n-2\"}").statusCode())
                    .isEqualTo(403);
            assertThat(post("/api/ai/v1/flow-components", "{\"name\":\"Start\"}").statusCode())
                    .isEqualTo(403);

            verify(workflowMapper, never()).selectOne(any());
        } finally {
            CURRENT_PERMISSIONS.set(ALL_PERMISSIONS);
        }
    }

    @Test
    @DisplayName("图读在 flow.read 之下：只持 ai:flow:design 身份读图 403")
    void graphReadsAreNotCoveredByTheDesignAction() throws Exception {
        CURRENT_PERMISSIONS.set(Set.of("ai:flow:design"));
        try {
            assertThat(get("/api/ai/v1/flows/" + WORKFLOW_UUID + "/nodes").statusCode())
                    .as("设计动作不扩大到读").isEqualTo(403);
            assertThat(get("/api/ai/v1/flows/" + WORKFLOW_UUID + "/edges").statusCode())
                    .isEqualTo(403);
            assertThat(get("/api/ai/v1/flow-components").statusCode())
                    .isEqualTo(403);
            // 写面：design 单独即可（owner/tenant 边界由服务层承担，此处桩下 200）
            assertThat(post("/api/ai/v1/flows/" + WORKFLOW_UUID + "/nodes", "{\"title\":\"x\"}").statusCode())
                    .isEqualTo(200);
        } finally {
            CURRENT_PERMISSIONS.set(ALL_PERMISSIONS);
        }
    }

    @Test
    @DisplayName("白名单外形状 404、内层前缀对外 404、未登录 401")
    void unregisteredShapesAndInternalPrefixStayHidden() throws Exception {
        assertThat(get("/api/ai/v1/flows/" + WORKFLOW_UUID + "/graph").statusCode())
                .as("未登记的 3 段形状不匹配任何白名单条目").isEqualTo(404);
        assertThat(get("/api/ai/v1/flows/" + WORKFLOW_UUID + "/nodes/n-1").statusCode())
                .as("GET 未登记的 4 段形状（PUT/DELETE 才是它的方法）").isEqualTo(404);
        assertThat(get("/api/ai/v1/flow-components/c-1/extra").statusCode()).isEqualTo(404);

        HttpResponse<String> anonymous = CLIENT.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/api/ai/v1/flows/"
                                + WORKFLOW_UUID + "/nodes"))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).as("未登录 → 401（不是 404）").isEqualTo(401);

        HttpResponse<String> internal = get("/internal/ai/v1/flows/" + WORKFLOW_UUID + "/nodes");
        assertThat(internal.statusCode()).as("内层前缀不是对外新增面").isEqualTo(404);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 等回执收敛到期望条数（≤3s）：回执在响应提交之后才发生，真实 HTTP 下客户端可能先返回。
     * 超时即由调用方的 {@code hasSize} 断言给出确定失败（本方法不吞错）。
     */
    private static void awaitAcknowledgements(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (acknowledgements.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    /**
     * JSON 面判据：200 + 整数 {@code "code":200} + 非 {@code Result} 的字符串 code +
     * （可选）独有标记。
     */
    private static void assertJsonOk(String method, String path, String body, String marker) throws Exception {
        HttpResponse<String> response = send(method, path, body, "application/json");
        assertThat(response.statusCode())
                .as("%s %s：404=未登记/503=内层不命中或缺回执/403=scope，都不是通过；实际体=%s",
                        method, path, response.body())
                .isEqualTo(200);
        assertThat(response.body())
                .as("%s %s：必须是整数 code 的信封", method, path)
                .contains("\"code\":200")
                .doesNotContain("\"code\":\"0\"");
        if (marker != null) {
            assertThat(response.body())
                    .as("%s %s：独有标记只可能来自该请求/该替身", method, path)
                    .contains(marker);
        }
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return send("GET", path, null, null);
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return send("POST", path, body, "application/json");
    }

    private static HttpResponse<String> put(String path, String body) throws Exception {
        return send("PUT", path, body, "application/json");
    }

    private static HttpResponse<String> delete(String path) throws Exception {
        return send("DELETE", path, null, null);
    }

    private static HttpResponse<String> send(String method, String path, String body, String contentType)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer synthetic-session");
        if (body != null) {
            builder.header("Content-Type", contentType);
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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

        /** 真实装配（含 AiEmbeddedFlowConfiguration，F13-A1 起含图面）的 Mapper 替身：
         *  业务语义各有其自己的用例（本判据只验"经网关真的接得住"）。 */
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
