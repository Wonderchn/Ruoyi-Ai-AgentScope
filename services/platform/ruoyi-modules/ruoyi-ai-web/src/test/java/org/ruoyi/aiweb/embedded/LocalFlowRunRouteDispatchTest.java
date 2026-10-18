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

import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.flow.runtime.FlowRuntimeRepository;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.runtime.RunAccessService;
import com.nageoffer.ai.ragent.runtime.RunAdmissionService;
import com.nageoffer.ai.ragent.runtime.RunEventAppender;
import com.nageoffer.ai.ragent.runtime.config.EngineModelAuthority;
import com.nageoffer.ai.ragent.runtime.config.RunConfigBindingPort;
import com.nageoffer.ai.ragent.runtime.config.RuntimeModelGatewayPort;
import com.nageoffer.ai.ragent.runtime.exec.ProviderCallBoundary;
import com.nageoffer.ai.ragent.runtime.stream.RunEventStreamService;
import com.nageoffer.ai.ragent.runtime.usage.UsageLedgerService;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * F13:2 runtime 视图面 5 条 ROUTES 经网关真实可达（真实 Tomcat + 真实本地传输 +
 * 真实内嵌装配 {@link AiEmbeddedFlowRunConfiguration}）。
 *
 * <p>关键钉子：
 * <ul>
 *   <li>声明序：{@code GET /flows/runs} 必须排在 {@code GET /flows/{uuid}} 之前；
 *       只持 {@code ai:flow:read} 的请求必须 403（若命中旧详情路由，动作是 flow.read，会 200）；</li>
 *   <li>GET 走字节分支：控制器自铸整数 ApiEnvelope + 两个回执头；写面不铸；</li>
 *   <li>V40：7208/7209、order 72/73、perms 精确。</li>
 * </ul>
 */
@Tag("dev")
class LocalFlowRunRouteDispatchTest {

    private static final String TENANT = "T1";
    private static final String USER = "2101";
    private static final String MEMBER = "platform:T1:2101";
    private static final int PV = 7;
    private static final int AV = 3;
    private static final long USER_ID = 2101L;

    private static final String WORKFLOW = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String RUNTIME = "0123456789abcdef0123456789abcdef";
    private static final String START = "1123456789abcdef0123456789abcdef";

    private static final Set<String> ALL_PERMISSIONS =
            Set.of("ai:flow:run", "ai:flow:run:read", "ai:flow:read", "ai:flow:delete");
    private static final AtomicReference<Set<String>> CURRENT_PERMISSIONS =
            new AtomicReference<>(ALL_PERMISSIONS);

    private static Tomcat tomcat;
    private static int port;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static final FlowRuntimeRepository repository = mock(FlowRuntimeRepository.class);
    private static final RunAdmissionService admission = mock(RunAdmissionService.class);
    private static final RunEventStreamService streamService = mock(RunEventStreamService.class);
    private static final RunAccessService access = mock(RunAccessService.class);
    private static final EngineModelAuthority modelAuthority = mock(EngineModelAuthority.class);
    private static final RunConfigBindingPort bindingPort = mock(RunConfigBindingPort.class);
    private static final RuntimeModelGatewayPort chat = mock(RuntimeModelGatewayPort.class);
    private static final UsageLedgerService usage = mock(UsageLedgerService.class);
    private static final ProviderCallBoundary providerBoundary = mock(ProviderCallBoundary.class);
    private static final RevocationGuard revocations = mock(RevocationGuard.class);
    private static final List<String> permits = new ArrayList<>();
    private static final List<String> acknowledgements = new ArrayList<>();

    @BeforeAll
    static void startContainer() throws Exception {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.register(FixtureConfig.class);
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "fixture", Map.of("ai.integration.enabled", "true", "ai.integration.transport", "local",
                        "p2.enabled", "true", "p2.executor.mode", "real")));

        tomcat = new Tomcat();
        tomcat.setBaseDir("target/local-flow-run-route-tomcat");
        tomcat.setPort(0);
        File docBase = new File("target/local-flow-run-route-docroot");
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
        reset(repository, admission, streamService, access, modelAuthority, bindingPort, chat, usage,
                providerBoundary, revocations);
        permits.clear();
        acknowledgements.clear();
        CURRENT_PERMISSIONS.set(ALL_PERMISSIONS);
        when(revocations.enter(any(), anyString(), anyString())).thenAnswer(invocation -> {
            permits.add(invocation.getArgument(1) + "/" + invocation.getArgument(2));
            return new RevocationGuard.Operation(revocations,
                    UUID.randomUUID().toString(), UUID.randomUUID().toString());
        });
        when(access.resources()).thenReturn(mock(AiResourceAuthorizationService.class));
        when(repository.listRuntimes(eq(TENANT), eq(USER_ID), any(), anyInt(), anyInt()))
                .thenReturn(List.of(runtimeView()));
        when(repository.countRuntimes(eq(TENANT), eq(USER_ID), any())).thenReturn(1L);
        when(repository.findRuntimeView(TENANT, USER_ID, RUNTIME)).thenReturn(Optional.of(runtimeView()));
        when(repository.listRuntimeNodes(TENANT, USER_ID, RUNTIME)).thenReturn(List.of(nodeView()));
        when(repository.softDeleteByWorkflow(TENANT, USER_ID, WORKFLOW)).thenReturn(2);
        when(repository.softDeleteRuntime(TENANT, USER_ID, RUNTIME)).thenReturn(true);
    }

    // ------------------------------------------------------------------ 5 条路由

    @Test
    @DisplayName("5 条 runtime 视图路由逐条 200：列表/详情/节点/清理/软删")
    void allFiveRuntimeRoutesAreReachableThroughTheGateway() throws Exception {
        assertJsonOk("GET", "/api/ai/v1/flows/runs?currentPage=1&pageSize=10", "\"status\":3");
        assertJsonOk("GET", "/api/ai/v1/flows/runs/" + RUNTIME, "\"runStatus\":\"SUCCEEDED\"");
        assertJsonOk("GET", "/api/ai/v1/flows/runs/" + RUNTIME + "/nodes",
                "\"nodeUuid\":\"" + START + "\"");
        assertJsonOk("POST", "/api/ai/v1/flows/runs/clear?wfUuid=" + WORKFLOW, "\"cleared\":2");
        assertJsonOk("DELETE", "/api/ai/v1/flows/runs/" + RUNTIME, null);
    }

    @Test
    @DisplayName("GET 走字节分支：控制器先取数后铸回执；写面零 permit")
    void runtimeReadsMintReceiptsAndWritesMintNone() throws Exception {
        assertJsonOk("GET", "/api/ai/v1/flows/runs?currentPage=1&pageSize=10", null);
        assertJsonOk("GET", "/api/ai/v1/flows/runs/" + RUNTIME, null);
        assertJsonOk("GET", "/api/ai/v1/flows/runs/" + RUNTIME + "/nodes", null);

        assertThat(permits).containsExactlyInAnyOrder(
                "flow.run.read/flow:run:mine",
                "flow.run.read/flow:run:" + RUNTIME,
                "flow.run.read/flow:run:" + RUNTIME);
        awaitAcknowledgements(permits.size());
        assertThat(acknowledgements).hasSize(permits.size());

        int before = permits.size();
        assertJsonOk("POST", "/api/ai/v1/flows/runs/clear?wfUuid=" + WORKFLOW, null);
        assertJsonOk("DELETE", "/api/ai/v1/flows/runs/" + RUNTIME, null);
        assertThat(permits).as("写面走 JSON 分支：不铸回执（否则永久 ACTIVE 许可泄漏）")
                .hasSize(before);
    }

    @Test
    @DisplayName("声明序：只持 ai:flow:read 时 /flows/runs 与 /flows/runs/{uuid} 仍 403（证明走 flow.run.read）")
    void runRoutesDoNotFallThroughToTheWorkflowDetailRoute() throws Exception {
        CURRENT_PERMISSIONS.set(Set.of("ai:flow:read"));
        try {
            assertThat(get("/api/ai/v1/flows/runs?currentPage=1&pageSize=10").statusCode())
                    .as("若声明序反了，这条会命中 GET /flows/{uuid}（动作 flow.read）而 200")
                    .isEqualTo(403);
            assertThat(get("/api/ai/v1/flows/runs/" + RUNTIME).statusCode())
                    .as("若声明序反了，这条会命中 GET /flows/{uuid}/nodes（动作 flow.read）")
                    .isEqualTo(403);
        } finally {
            CURRENT_PERMISSIONS.set(ALL_PERMISSIONS);
        }
    }

    @Test
    @DisplayName("写面动作：清理/软删必须持 ai:flow:delete；只持 run.read 时 403")
    void writeRoutesRequireFlowDelete() throws Exception {
        CURRENT_PERMISSIONS.set(Set.of("ai:flow:run", "ai:flow:run:read"));
        try {
            assertThat(post("/api/ai/v1/flows/runs/clear?wfUuid=" + WORKFLOW, "{}").statusCode())
                    .isEqualTo(403);
            assertThat(delete("/api/ai/v1/flows/runs/" + RUNTIME).statusCode()).isEqualTo(403);
        } finally {
            CURRENT_PERMISSIONS.set(ALL_PERMISSIONS);
        }
    }

    @Test
    @DisplayName("白名单外形状 404、内层前缀对外 404、未登录 401")
    void unregisteredShapesAndInternalPrefixStayHidden() throws Exception {
        assertThat(get("/api/ai/v1/flows/runs/" + RUNTIME + "/extra").statusCode()).isEqualTo(404);
        assertThat(get("/api/ai/v1/flows/runs/" + RUNTIME + "/nodes/extra").statusCode()).isEqualTo(404);

        HttpResponse<String> anonymous = CLIENT.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/api/ai/v1/flows/runs"))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);

        assertThat(get("/internal/ai/v1/flows/runs").statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ 守卫

    @Test
    @DisplayName("ROUTES 精确 5 条（方法/模式/动作），且 GET /flows/runs 在 GET /flows/{uuid} 之前")
    void routeTableHasExactFiveEntriesInTheRequiredOrder() throws Exception {
        List<String> routes = whitelistPatterns();
        assertThat(routes).contains(
                "GET /flows/runs",
                "GET /flows/runs/{runtimeUuid}",
                "GET /flows/runs/{runtimeUuid}/nodes",
                "POST /flows/runs/clear",
                "DELETE /flows/runs/{runtimeUuid}");
        assertThat(indexOf(routes, "GET /flows/runs"))
                .as("GET /flows/runs 必须在 GET /flows/{uuid} 之前（matchRoute 声明序首个命中）")
                .isLessThan(indexOf(routes, "GET /flows/{uuid}"));
        assertThat(indexOf(routes, "GET /flows/runs/{runtimeUuid}"))
                .isLessThan(indexOf(routes, "GET /flows/{uuid}/nodes"));
        assertThat(actionOf("GET /flows/runs")).isEqualTo("flow.run.read");
        assertThat(actionOf("POST /flows/runs/clear")).isEqualTo("flow.delete");
        assertThat(actionOf("DELETE /flows/runs/{runtimeUuid}")).isEqualTo("flow.delete");
    }

    @Test
    @DisplayName("V40：7208/7209、parent 7100、order 72/73、perms 精确（默认不分配）")
    void v40DeclaresTheTwoRunPermissions() throws Exception {
        String sql = Files.readString(locate(Path.of("services", "platform", "docs", "script", "sql",
                "postgres", "V40__aiflow_run_permissions.sql")), StandardCharsets.UTF_8);
        assertThat(sql)
                .contains("7208")
                .contains("7209")
                .contains("'ai:flow:run'")
                .contains("'ai:flow:run:read'")
                .contains("'F'")
                .contains("7100, 72")
                .contains("7100, 73");
        assertThat(sql).doesNotContain("sys_role_menu");
    }

    // ------------------------------------------------------------------ helpers

    private static FlowRuntimeRepository.RuntimeView runtimeView() {
        return new FlowRuntimeRepository.RuntimeView(RUNTIME, WORKFLOW, 3, "", "{\"output\":{\"value\":\"ok\"}}",
                Instant.now(), Instant.now(), "SUCCEEDED", null);
    }

    private static FlowRuntimeRepository.RuntimeNodeView nodeView() {
        return new FlowRuntimeRepository.RuntimeNodeView(101L, START, 3, "", "{}", "{\"text\":\"ok\"}",
                "COMPLETED", "{\"text\":\"ok\"}", Instant.now(), Instant.now());
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return send("GET", path, null, null);
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return send("POST", path, body, "application/json");
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

    private static void assertJsonOk(String method, String path, String marker) throws Exception {
        HttpResponse<String> response = send(method, path, "POST".equals(method) ? "{}" : null,
                "POST".equals(method) ? "application/json" : null);
        assertThat(response.statusCode())
                .as("%s %s：404=未登记/503=内层不命中或缺回执/403=scope；实际体=%s",
                        method, path, response.body())
                .isEqualTo(200);
        assertThat(response.body()).contains("\"code\":200").doesNotContain("\"code\":\"0\"");
        if (marker != null) {
            assertThat(response.body()).contains(marker);
        }
    }

    private static void awaitAcknowledgements(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (acknowledgements.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    private static List<String> whitelistPatterns() throws Exception {
        Field field = AiGatewayController.class.getDeclaredField("ROUTES");
        field.setAccessible(true);
        List<?> routes = (List<?>) field.get(null);
        List<String> out = new ArrayList<>();
        for (Object route : routes) {
            out.add(access(route, "method") + " " + access(route, "pattern"));
        }
        return out;
    }

    private static String actionOf(String pattern) throws Exception {
        Field field = AiGatewayController.class.getDeclaredField("ROUTES");
        field.setAccessible(true);
        List<?> routes = (List<?>) field.get(null);
        for (Object route : routes) {
            if (pattern.equals(access(route, "method") + " " + access(route, "pattern"))) {
                return (String) access(route, "action");
            }
        }
        throw new IllegalStateException("route missing: " + pattern);
    }

    private static Object access(Object target, String accessor) throws Exception {
        Method method = target.getClass().getDeclaredMethod(accessor);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static int indexOf(List<String> routes, String route) {
        int index = routes.indexOf(route);
        assertThat(index).as(route + " 未登记").isGreaterThanOrEqualTo(0);
        return index;
    }

    private static Path locate(Path relative) {
        Path cursor = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && cursor != null; i++) {
            Path candidate = cursor.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("cannot locate " + relative);
    }

    // ------------------------------------------------------------------ fixture

    @Configuration
    @EnableWebMvc
    @Import({AiGatewayController.class, AiWebEmbeddedConfiguration.class, AiEmbeddedFlowRunConfiguration.class})
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

        // ---------------------------------------------------------- F13:2 assembly deps

        @Bean
        FlowRuntimeRepository flowRuntimeRepository() {
            return repository;
        }

        @Bean
        RunAdmissionService runAdmissionService() {
            return admission;
        }

        @Bean
        RunEventStreamService runEventStreamService() {
            return streamService;
        }

        @Bean
        RunAccessService runAccessService() {
            return access;
        }

        @Bean
        EngineModelAuthority engineModelAuthority() {
            return modelAuthority;
        }

        @Bean
        RunConfigBindingPort runConfigBindingPort() {
            return bindingPort;
        }

        @Bean
        RuntimeModelGatewayPort runtimeModelGatewayPort() {
            return chat;
        }

        @Bean
        UsageLedgerService usageLedgerService() {
            return usage;
        }

        @Bean
        ProviderCallBoundary providerCallBoundary() {
            return providerBoundary;
        }

        @Bean
        com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao runLedgerDao() {
            return mock(com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao.class);
        }

        @Bean
        RunEventAppender runEventAppender() {
            return mock(RunEventAppender.class);
        }
    }
}
