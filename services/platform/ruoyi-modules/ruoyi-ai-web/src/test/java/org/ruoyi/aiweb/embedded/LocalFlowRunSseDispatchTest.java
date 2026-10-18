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

import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import org.ruoyi.aiintegration.authorization.OrganizationMatchController;
import org.ruoyi.aiintegration.config.AiIntegrationProperties;
import org.ruoyi.aiintegration.identity.CurrentPrincipalResolver;
import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.ruoyi.aiintegration.identity.ProductionAuthorizationProvider;
import org.ruoyi.aiintegration.web.AiGatewayController;
import org.ruoyi.aiintegration.web.AiGatewayStreamController;
import org.ruoyi.aiweb.AiWebEmbeddedConfiguration;
import org.ruoyi.aiweb.security.AiInternalAccessBoundaryFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.File;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;

/**
 * F13:2 两条 SSE 精确映射（走 {@code AiGatewayStreamController}，不进 ROUTES catch-all）：
 * <ul>
 *   <li>{@code POST /api/ai/v1/flows/{uuid}/run} → 内层 {@code POST /internal/ai/v1/flows/{uuid}/run}，动作 flow.run；</li>
 *   <li>{@code GET /api/ai/v1/flows/runs/{runtimeUuid}/events} → 内层同名，动作 flow.run.read。</li>
 * </ul>
 * 另钉 body 上限 256 KiB、uuid 形状 404、逐帧交付回执。
 */
@Tag("dev")
class LocalFlowRunSseDispatchTest {

    private static final String TENANT = "T1";
    private static final String USER = "2101";
    private static final String MEMBER = "platform:T1:2101";
    private static final int PV = 7;
    private static final int AV = 3;
    private static final String WORKFLOW = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String RUNTIME = "0123456789abcdef0123456789abcdef";
    private static final int BODY_LIMIT = 256 * 1024;

    private static final AtomicReference<Set<String>> CURRENT_PERMISSIONS =
            new AtomicReference<>(Set.of("ai:flow:run", "ai:flow:run:read"));
    private static final AtomicReference<String> LAST_RUN_UUID = new AtomicReference<>();
    private static final AtomicReference<String> LAST_RUN_BODY = new AtomicReference<>();
    private static final AtomicReference<String> LAST_RUNTIME_UUID = new AtomicReference<>();
    private static final AtomicReference<String> LAST_AFTER_SEQ = new AtomicReference<>();

    private static Tomcat tomcat;
    private static int port;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private static final NamedParameterJdbcTemplate namedJdbc = mock(NamedParameterJdbcTemplate.class);
    private static final TransactionOperations transactions = mock(TransactionOperations.class);
    private static final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private static final RevocationGuard revocations = mock(RevocationGuard.class);
    private static final ProductionAuthorizationProvider platformPermits =
            mock(ProductionAuthorizationProvider.class);
    private static final List<String> deliveryAcks = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startContainer() throws Exception {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.register(FixtureConfig.class);
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "fixture", Map.of("ai.integration.enabled", "true", "ai.integration.transport", "local")));

        tomcat = new Tomcat();
        tomcat.setBaseDir("target/local-flow-run-sse-tomcat");
        tomcat.setPort(0);
        File docBase = new File("target/local-flow-run-sse-docroot");
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
        reset(jdbc, namedJdbc, transactions, transactionManager, revocations, platformPermits);
        deliveryAcks.clear();
        LAST_RUN_UUID.set(null);
        LAST_RUN_BODY.set(null);
        LAST_RUNTIME_UUID.set(null);
        LAST_AFTER_SEQ.set(null);
        CURRENT_PERMISSIONS.set(Set.of("ai:flow:run", "ai:flow:run:read"));
    }

    // ------------------------------------------------------------------ 正例

    @Test
    @DisplayName("POST /flows/{uuid}/run：动作 flow.run，body 原样到达内层，帧去掉 ai-delivery 并逐帧回执")
    void postFlowRunIsForwardedAsSseWithFlowRunScope() throws Exception {
        CURRENT_PERMISSIONS.set(Set.of("ai:flow:run"));
        String body = "{\"inputs\":{\"name\":\"Ada\"}}";

        HttpResponse<String> response = postFlowRun(WORKFLOW, body, "idem-1");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/event-stream");
        assertThat(response.body()).contains("id: 1").contains("run.accepted");
        assertThat(response.body()).doesNotContain("ai-delivery");
        assertThat(LAST_RUN_UUID.get()).isEqualTo(WORKFLOW);
        assertThat(LAST_RUN_BODY.get()).contains("\"name\":\"Ada\"");
        assertThat(deliveryAcks).contains("permit-run op-run");
    }

    @Test
    @DisplayName("GET /flows/runs/{runtimeUuid}/events：动作 flow.run.read，afterSeq 透传，逐帧回执")
    void getFlowRunEventsIsForwardedWithFlowRunReadScope() throws Exception {
        CURRENT_PERMISSIONS.set(Set.of("ai:flow:run:read"));

        HttpResponse<String> response = getFlowRunEvents(RUNTIME, 3L);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("id: 2").contains("run.status");
        assertThat(LAST_RUNTIME_UUID.get()).isEqualTo(RUNTIME);
        assertThat(LAST_AFTER_SEQ.get()).isEqualTo("3");
        assertThat(deliveryAcks).contains("permit-events op-events");
    }

    // ------------------------------------------------------------------ 负例

    @Test
    @DisplayName("动作隔离：flow.run 不能读流，flow.run.read 不能起跑")
    void theTwoSseEndpointsUseDistinctActions() throws Exception {
        CURRENT_PERMISSIONS.set(Set.of("ai:flow:run"));
        try {
            assertThat(getFlowRunEvents(RUNTIME, null).statusCode()).isEqualTo(403);
        } finally {
            CURRENT_PERMISSIONS.set(Set.of("ai:flow:run:read"));
        }
        assertThat(postFlowRun(WORKFLOW, "{\"inputs\":{}}", "idem-2").statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("POST 体上限 256 KiB：超限 400，且不进入内层")
    void oversizedFlowRunBodyIsRejectedBeforeForwarding() throws Exception {
        String oversized = "{\"inputs\":{\"pad\":\"" + "x".repeat(BODY_LIMIT) + "\"}}";
        assertThat(oversized.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(BODY_LIMIT);

        HttpResponse<String> response = postFlowRun(WORKFLOW, oversized, "idem-3");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(LAST_RUN_UUID.get()).isNull();
        assertThat(deliveryAcks).isEmpty();
    }

    @Test
    @DisplayName("uuid 形状不是 [0-9a-f]{32} 时 404，不进入内层")
    void invalidUuidShapeIsRejected() throws Exception {
        assertThat(postFlowRun("not-a-uuid", "{\"inputs\":{}}", "idem-4").statusCode()).isEqualTo(404);
        assertThat(getFlowRunEvents("not-a-uuid", null).statusCode()).isEqualTo(404);
        assertThat(LAST_RUN_UUID.get()).isNull();
        assertThat(LAST_RUNTIME_UUID.get()).isNull();
    }

    @Test
    @DisplayName("两条 SSE 路径不在 ROUTES catch-all 里（精确映射先于通用转发）")
    void ssePathsAreNotRegisteredInTheCatchAllRouteTable() throws Exception {
        Field field = AiGatewayController.class.getDeclaredField("ROUTES");
        field.setAccessible(true);
        List<?> routes = (List<?>) field.get(null);
        for (Object route : routes) {
            var method = route.getClass().getDeclaredMethod("method");
            method.setAccessible(true);
            var pattern = route.getClass().getDeclaredMethod("pattern");
            pattern.setAccessible(true);
            String value = method.invoke(route) + " " + pattern.invoke(route);
            assertThat(value)
                    .as("SSE 路径必须由 AiGatewayStreamController 精确映射，而不是 ROUTES")
                    .isNotEqualTo("POST /flows/{uuid}/run")
                    .isNotEqualTo("GET /flows/runs/{runtimeUuid}/events");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static HttpResponse<String> postFlowRun(String uuid, String body, String idempotencyKey)
            throws Exception {
        return CLIENT.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/api/ai/v1/flows/" + uuid + "/run"))
                        .header("Authorization", "Bearer synthetic-session")
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .header("Idempotency-Key", idempotencyKey)
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> getFlowRunEvents(String runtimeUuid, Long afterSeq) throws Exception {
        String query = afterSeq == null ? "" : "?afterSeq=" + afterSeq;
        return CLIENT.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/api/ai/v1/flows/runs/"
                                + runtimeUuid + "/events" + query))
                        .header("Authorization", "Bearer synthetic-session")
                        .header("Accept", "text/event-stream")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ------------------------------------------------------------------ fixture

    /** 内层 SSE 接面：模拟真实 FlowRunController / FlowRuntimeController 的帧语义。 */
    @RestController
    @RequestMapping("/internal/ai/v1")
    static class FixtureFlowSseController {

        @PostMapping("/flows/{uuid}/run")
        public void run(@PathVariable("uuid") String uuid, HttpServletRequest request,
                        HttpServletResponse response) throws java.io.IOException {
            LAST_RUN_UUID.set(uuid);
            LAST_RUN_BODY.set(new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            response.setStatus(200);
            response.setContentType("text/event-stream;charset=UTF-8");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache, no-store");
            var out = response.getOutputStream();
            out.write((": ai-delivery permit-run op-run\n"
                    + "id: 1\nevent: run.accepted\ndata: {\"runId\":\"r-synthetic\"}\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        @GetMapping("/flows/runs/{runtimeUuid}/events")
        public void events(@PathVariable("runtimeUuid") String runtimeUuid,
                           @RequestParam(value = "afterSeq", required = false) String afterSeq,
                           HttpServletResponse response) throws java.io.IOException {
            LAST_RUNTIME_UUID.set(runtimeUuid);
            LAST_AFTER_SEQ.set(afterSeq);
            response.setStatus(200);
            response.setContentType("text/event-stream;charset=UTF-8");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache, no-store");
            var out = response.getOutputStream();
            out.write((": ai-delivery permit-events op-events\n"
                    + "id: 2\nevent: run.status\ndata: {\"status\":\"RUNNING\"}\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    @Configuration
    @EnableWebMvc
    @Import({AiGatewayStreamController.class, org.ruoyi.aiintegration.web.GatewayAuthorizer.class,
            AiWebEmbeddedConfiguration.class, FixtureFlowSseController.class})
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
        OrganizationMatchController.SubjectMatchSource subjectMatchSource() {
            return (tenantId, subject) -> Optional.empty();
        }

        @Bean
        AiIdentityPort aiIdentityPort() {
            return () -> Optional.of(new AiExecutionFacts(TENANT, USER, MEMBER, PV, AV,
                    CURRENT_PERMISSIONS.get()));
        }

        @Bean
        org.ruoyi.aiweb.transport.AiDeliveryReleaser aiDeliveryReleaser() {
            return (tenantId, memberId, permitId, operationId) -> deliveryAcks.add(permitId + " " + operationId);
        }

        @Bean
        JdbcTemplate jdbcTemplate() {
            return jdbc;
        }

        @Bean
        NamedParameterJdbcTemplate namedParameterJdbcTemplate() {
            return namedJdbc;
        }

        @Bean
        TransactionOperations transactionOperations() {
            return transactions;
        }

        @Bean
        PlatformTransactionManager platformTransactionManager() {
            return transactionManager;
        }

        @Bean
        com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper();
        }

        @Bean
        RevocationGuard defaultRevocationGuard() {
            return revocations;
        }

        @Bean
        ProductionAuthorizationProvider productionAuthorizationProvider() {
            return platformPermits;
        }
    }
}
