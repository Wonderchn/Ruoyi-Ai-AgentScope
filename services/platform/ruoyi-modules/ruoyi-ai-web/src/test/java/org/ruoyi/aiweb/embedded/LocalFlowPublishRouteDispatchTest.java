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

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.flow.dao.entity.AiFlowComponentDO;
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
 * R17 F13-M1：发布/公开面经网关<b>真实可达</b>的端到端判据（红先行）。
 *
 * <p>覆盖：4 条公开 GET（搜索/操作符/公开组件库/公开详情）+ 2 条写面
 * （enabled / public）。形态照 {@code LocalFlowGraphRouteDispatchTest}：
 * 真实 Tomcat + 真实网关 + 真实本地传输 + 真实内嵌装配（{@code AiEmbeddedFlowConfiguration}），
 * 仅 Mapper 用替身。
 *
 * <p><b>关键判据</b>：
 * <ul>
 *   <li>公开字面量路由不被 {@code GET /flows/{uuid}} 或 {@code /flows/{uuid}/nodes} 吃掉；</li>
 *   <li>公开详情不可见时（私有/停用/软删/跨租户/不存在）统一 404 + 同一文案，不铸交付回执；</li>
 *   <li>写面要求 {@code ai:flow:publish}；只持 flow.write/read 的身份不得启停或改公开；</li>
 *   <li>公开读仍要求 {@code ai:flow:list}/{@code ai:flow:read}，不因新增 flow.publish 而放宽；</li>
 *   <li>GET 走字节分支，控制器必须铸回执；写面 JSON 分支不得铸回执（避免许可泄漏）。</li>
 * </ul>
 */
@Tag("dev")
class LocalFlowPublishRouteDispatchTest {

    private static final String TENANT = "T1";
    private static final String USER = "2101";
    private static final String MEMBER = "platform:T1:2101";
    private static final int PV = 7;
    private static final int AV = 3;

    private static final String PUBLIC_TITLE = "f13m1-public-wf";
    private static final String OWN_TITLE = "f13m1-own-wf";

    private static final Set<String> ALL_PERMISSIONS = Set.of(
            "ai:flow:list", "ai:flow:read", "ai:flow:publish");

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
        tomcat.setBaseDir("target/local-flow-publish-route-tomcat");
        tomcat.setPort(0);
        File docBase = new File("target/local-flow-publish-route-docroot");
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
        when(workflowMapper.selectOne(any())).thenReturn(workflow(OWN_TITLE));
        when(workflowMapper.update(any(), any())).thenReturn(1);
        when(workflowMapper.insert(any(AiFlowWorkflowDO.class))).thenReturn(1);
        when(workflowMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<AiFlowWorkflowDO> page = invocation.getArgument(0);
            page.setRecords(List.of(workflow(PUBLIC_TITLE)));
            return page;
        });
        when(componentMapper.selectList(any())).thenReturn(List.of(component()));
    }

    private static AiFlowWorkflowDO workflow(String title) {
        AiFlowWorkflowDO row = new AiFlowWorkflowDO();
        row.setId(41L);
        row.setUuid("wf-public-1");
        row.setTitle(title);
        row.setUserId(2101L);
        row.setTenantId(TENANT);
        row.setIsDeleted(0);
        row.setIsPublic(1);
        row.setIsEnable(1);
        return row;
    }

    private static AiFlowComponentDO component() {
        AiFlowComponentDO row = new AiFlowComponentDO();
        row.setId(71L);
        row.setUuid("f13m1-comp-1");
        row.setName("Start");
        row.setTitle("f13m1-component");
        row.setRemark("");
        row.setDisplayOrder(0);
        row.setIsEnable(1);
        row.setIsDeleted(0);
        row.setTenantId(TENANT);
        return row;
    }

    // ------------------------------------------------------------------ 正例：公开面可达

    @Test
    @DisplayName("4 条公开 GET + 2 条写面经网关逐条 200")
    void publicReadsAndPublishWritesAreReachableThroughTheGateway() throws Exception {
        assertJsonOk("GET", "/api/ai/v1/flows/public/search", null, "\"records\"");
        assertJsonOk("GET", "/api/ai/v1/flows/public/operators", null, "\"not contains\"");
        assertJsonOk("GET", "/api/ai/v1/flows/public/component/list", null, "f13m1-component");
        assertJsonOk("GET", "/api/ai/v1/flows/public/wf-public-1", null, OWN_TITLE);
        assertJsonOk("POST", "/api/ai/v1/flows/wf-public-1/enabled?enabled=false", null, null);
        assertJsonOk("POST", "/api/ai/v1/flows/wf-public-1/public?isPublic=true", null, null);
    }

    @Test
    @DisplayName("操作符目录逐字等于 legacy OperatorEnum 的 12 项（name/desc/顺序）")
    void operatorCatalogMatchesLegacyTwelvePairs() throws Exception {
        HttpResponse<String> response = get("/api/ai/v1/flows/public/operators");
        assertThat(response.statusCode()).isEqualTo(200);

        com.fasterxml.jackson.databind.JsonNode data =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body()).get("data");
        assertThat(data).as("操作符目录必须是数组").isNotNull();
        assertThat(data.isArray()).isTrue();
        List<String> pairs = new ArrayList<>();
        data.forEach(node -> pairs.add(node.get("name").asText() + "|" + node.get("desc").asText()));

        assertThat(pairs).containsExactly(
                "contains|包含",
                "not contains|不包含",
                "start with|开始内容是",
                "end with|结束内容是",
                "empty|为空",
                "not empty|不为空",
                "=|等于",
                "!=|不等于",
                ">|大于",
                ">=|大于或等于",
                "<|小于",
                "<=|小于或等于");
    }

    @Test
    @DisplayName("公开搜索不被 /flows/{uuid} 或 /flows/{uuid}/nodes 吃掉")
    void publicLiteralRoutesAreNotSwallowedByVariableFlowRoutes() throws Exception {
        // 搜索页返回分页记录标记（标题 PUBLIC_TITLE）；若命中 GET /flows/{uuid}
        // 会走 selectOne 返回单条 VO（标题 OWN_TITLE），两种形状可区分。
        assertJsonOk("GET", "/api/ai/v1/flows/public/search", null, PUBLIC_TITLE);
        assertJsonOk("GET", "/api/ai/v1/flows/public/search", null, "\"records\"");
        assertJsonOk("GET", "/api/ai/v1/flows/public/operators", null, "start with");
        // 组件面若被公开详情吃掉，返回的是工作流标题而不是组件名。
        assertJsonOk("GET", "/api/ai/v1/flows/public/component/list", null, "\"name\":\"Start\"");
        // 真实 uuid 仍走既有详情语义。
        assertJsonOk("GET", "/api/ai/v1/flows/wf-own-1", null, OWN_TITLE);
    }

    @Test
    @DisplayName("GET 走字节分支由控制器铸回执并 ACK；写面零 permit 零 ACK")
    void publicReadsMintDeliveryReceiptsAndWritesMintNone() throws Exception {
        assertJsonOk("GET", "/api/ai/v1/flows/public/search", null, null);
        assertJsonOk("GET", "/api/ai/v1/flows/public/operators", null, null);
        assertJsonOk("GET", "/api/ai/v1/flows/public/component/list", null, null);
        assertJsonOk("GET", "/api/ai/v1/flows/public/wf-public-1", null, null);

        assertThat(permits)
                .as("每条 200 的 GET 恰铸一次许可")
                .containsExactlyInAnyOrder(
                        "flow.list/flow:public",
                        "flow.list/flow:operators",
                        "flow.read/flow:components:public",
                        "flow.read/flow:public:wf-public-1");
        awaitAcknowledgements(permits.size());
        assertThat(acknowledgements)
                .as("字节分支的每个 200 必须恰有一次本地回执")
                .hasSize(permits.size());

        int before = permits.size();
        assertJsonOk("POST", "/api/ai/v1/flows/wf-public-1/enabled?enabled=true", null, null);
        assertJsonOk("POST", "/api/ai/v1/flows/wf-public-1/public?isPublic=false", null, null);
        assertThat(permits)
                .as("写面走 JSON 分支：控制器不得铸造交付许可")
                .hasSize(before);
    }

    // ------------------------------------------------------------------ 负例：可见性与动作

    @Test
    @DisplayName("公开详情不可见：404 + 同一文案，且不铸交付回执")
    void invisiblePublicDetailIs404WithOneMessageAndNoPermit() throws Exception {
        when(workflowMapper.selectOne(any())).thenReturn(null);

        HttpResponse<String> response = get("/api/ai/v1/flows/public/wf-hidden");

        assertThat(response.statusCode()).as("私有/停用/软删/跨租户统一 404").isEqualTo(404);
        assertThat(response.body())
                .contains("\"code\":404")
                .contains("工作流不存在");
        assertThat(permits).as("不可见时先取数失败：不得留下 ACTIVE 许可").isEmpty();
    }

    @Test
    @DisplayName("写面要求 ai:flow:publish：只持 flow.list/read/write 的身份一律 403")
    void publishWritesRequireFlowPublishNotJustFlowWrite() throws Exception {
        CURRENT_PERMISSIONS.set(Set.of("ai:flow:list", "ai:flow:read", "ai:flow:write"));
        try {
            assertThat(post("/api/ai/v1/flows/wf-public-1/enabled?enabled=true", null).statusCode())
                    .as("持 flow.write 不得启停").isEqualTo(403);
            assertThat(post("/api/ai/v1/flows/wf-public-1/public?isPublic=true", null).statusCode())
                    .as("持 flow.write 不得改公开标志").isEqualTo(403);
            verify(workflowMapper, never()).update(any(), any());
        } finally {
            CURRENT_PERMISSIONS.set(ALL_PERMISSIONS);
        }
    }

    @Test
    @DisplayName("公开读仍要求 flow.list/read：只持 flow.publish 的身份搜索/详情 403")
    void publicReadsAreNotCoveredByThePublishAction() throws Exception {
        CURRENT_PERMISSIONS.set(Set.of("ai:flow:publish"));
        try {
            assertThat(get("/api/ai/v1/flows/public/search").statusCode())
                    .as("发布动作不扩大到公开搜索（flow.list）").isEqualTo(403);
            assertThat(get("/api/ai/v1/flows/public/wf-public-1").statusCode())
                    .as("发布动作不扩大到公开详情（flow.read）").isEqualTo(403);
            clearInvocations(workflowMapper);
            // 写面：publish 单独即可（owner/tenant 边界由服务层承担，此处桩下 200）
            assertThat(post("/api/ai/v1/flows/wf-public-1/enabled?enabled=true", null).statusCode())
                    .isEqualTo(200);
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

    private static void assertJsonOk(String method, String path, String body, String marker) throws Exception {
        HttpResponse<String> response = send(method, path, body, body == null ? null : "application/json");
        assertThat(response.statusCode())
                .as("%s %s：404=未登记/503=内层不命中或缺回执/403=scope；实际体=%s",
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
        return send("POST", path, body, body == null ? null : "application/json");
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
