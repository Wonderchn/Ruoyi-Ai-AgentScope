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

import com.nageoffer.ai.ragent.runtime.RunAdmissionService;
import com.nageoffer.ai.ragent.runtime.RunLifecycleService;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.ruoyi.aiweb.AiWebEmbeddedConfiguration;
import org.ruoyi.aiweb.security.AiInternalAccessBoundaryFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E3 单元二收尾：runs 路由组经<b>本地接口</b>端到端（真实 Tomcat 容器）。
 *
 * <p>装配真实 {@code AiEmbeddedRunConfiguration}（真实 RunController + RunApiExceptionHandler，
 * 受理/生命周期实现以 mock 驱动——其内部语义由既有 P2 测试覆盖），持久层与平台侧全部 mock：
 * <ul>
 *   <li>{@code POST /runs} 本地转送 → 202 且 body code=200（P2 受理信封），受理参数逐字段；
 *   <li>{@code POST /runs/{id}/cancel} → 200 + runView；缺 expectedVersion 的 resume → 400；
 *   <li>非法 JSON → 400；白名单外 404；无登录 401；scope 不足 → 网关 403（不放行）。</li>
 * </ul>
 */
@Tag("dev")
class LocalRunRouteDispatchTest {

    private static final String TENANT = "T1";
    private static final String USER = "2101";
    private static final String MEMBER = "platform:T1:2101";
    private static final int PV = 7;
    private static final int AV = 3;

    private static Tomcat tomcat;
    private static int port;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private static final NamedParameterJdbcTemplate namedJdbc = mock(NamedParameterJdbcTemplate.class);
    private static final TransactionOperations transactions = mock(TransactionOperations.class);
    private static final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private static final com.nageoffer.ai.ragent.framework.security.RevocationGuard revocations =
            mock(com.nageoffer.ai.ragent.framework.security.RevocationGuard.class);
    private static final ProductionAuthorizationProvider platformPermits =
            mock(ProductionAuthorizationProvider.class);
    private static final RunAdmissionService admission = mock(RunAdmissionService.class);
    private static final RunLifecycleService lifecycle = mock(RunLifecycleService.class);

    /** 每个用例可替换的身份 scope（默认含 run 三动作权限）。 */
    private static final AtomicReference<Set<String>> identityScopes =
            new AtomicReference<>(Set.of("ai:run:submit", "ai:run:cancel", "ai:run:resume", "ai:run:stream"));

    @BeforeAll
    static void startContainer() throws Exception {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.register(FixtureConfig.class);
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "fixture", Map.of("ai.integration.enabled", "true", "ai.integration.transport", "local",
                        "p2.enabled", "true")));

        tomcat = new Tomcat();
        tomcat.setBaseDir("target/local-run-route-tomcat");
        tomcat.setPort(0);
        File docBase = new File("target/local-run-route-docroot");
        docBase.mkdirs();
        Context ctx = tomcat.addContext("", docBase.getAbsolutePath());

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
    void baselineStubs() {
        reset(jdbc, namedJdbc, transactions, transactionManager, revocations, platformPermits, admission, lifecycle);
        identityScopes.set(Set.of("ai:run:submit", "ai:run:cancel", "ai:run:resume", "ai:run:stream"));
        when(revocations.enter(any(), any(), any())).thenAnswer(invocation ->
                new com.nageoffer.ai.ragent.framework.security.RevocationGuard.Operation(
                        revocations, java.util.UUID.randomUUID().toString(),
                        java.util.UUID.randomUUID().toString()));
    }

    private static HttpResponse<String> post(String path, String body, boolean withLogin) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "same-key")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (withLogin) {
            builder.header("Authorization", "Bearer synthetic-session");
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path, boolean withLogin) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (withLogin) {
            builder.header("Authorization", "Bearer synthetic-session");
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void runSubmitGoesLocalWithAcceptedEnvelope() throws Exception {
        when(admission.admit(any(), eq("same-key"), any())).thenReturn(
                new RunAdmissionService.AdmissionResult("run-test", "QUEUED", Instant.parse("2026-01-01T00:00:00Z"), false));

        HttpResponse<String> response = post("/api/ai/v1/runs",
                "{\"schemaVersion\":1,\"action\":\"rag.chat\",\"input\":{\"text\":\"本地转送\"},"
                        + "\"budget\":{\"maxTokens\":2000}}", true);

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).contains("\"code\":200").contains("\"runId\":\"run-test\"");
        var captor = org.mockito.ArgumentCaptor.forClass(com.nageoffer.ai.ragent.runtime.dto.AdmissionRequest.class);
        verify(admission).admit(any(), eq("same-key"), captor.capture());
        assertThat(captor.getValue().input().path("text").asText()).isEqualTo("本地转送");
        assertThat(captor.getValue().budget().path("maxTokens").asInt()).isEqualTo(2000);
    }

    @Test
    void runCancelGoesLocalWithRunView() throws Exception {
        when(lifecycle.cancel(any(), eq("run-1"), any())).thenReturn(runRecord("run-1", "CANCELLED"));

        HttpResponse<String> response = post("/api/ai/v1/runs/run-1/cancel", "{\"expectedVersion\":2}", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"code\":200").contains("\"runId\":\"run-1\"")
                .contains("\"status\":\"CANCELLED\"");
    }

    @Test
    void runResumeWithoutExpectedVersionStays400() throws Exception {
        HttpResponse<String> response = post("/api/ai/v1/runs/run-1/resume", "{}", true);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("BAD_REQUEST");
    }

    @Test
    void invalidJsonNeverAdmits() throws Exception {
        HttpResponse<String> response = post("/api/ai/v1/runs", "{\"action\":\"rag.chat\",\"action\":\"x\"}", true);

        assertThat(response.statusCode()).isEqualTo(400);
        verify(admission, org.mockito.Mockito.never()).admit(any(), any(), any());
    }

    @Test
    void insufficientScopeIsForbiddenByGateway() throws Exception {
        identityScopes.set(Set.of("ai:kb:list"));

        HttpResponse<String> response = post("/api/ai/v1/runs", "{}", true);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("FORBIDDEN");
        verify(admission, org.mockito.Mockito.never()).admit(any(), any(), any());
    }

    @Test
    void runEventsSseRouteIsWiredAndFailsClosedWithoutAclEpoch() throws Exception {
        // 真实 RunStreamController/RunEventStreamService 已装配：请求到达真实 SSE 服务与授权链；
        // mock 夹具没有 ai_acl_epoch 行 → 授权判定 UNKNOWN → SSE 服务写 503 信封（不放行，不订阅）
        HttpResponse<String> response = get("/api/ai/v1/runs/missing-run/events", true);

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("AUTHORIZATION_UNAVAILABLE");
    }

    @Test
    void whitelistAndLoginNegativesStay() throws Exception {
        assertThat(get("/api/ai/v1/unknown-route", true).statusCode()).isEqualTo(404);
        assertThat(post("/api/ai/v1/runs", "{}", false).statusCode()).isEqualTo(401);
    }

    private static RunRecord runRecord(String runId, String status) {
        return new RunRecord(TENANT, runId, MEMBER, USER, "rag.chat", status, "hash", "same-key",
                "{}", "{}", "v1", PV, AV, "[]", 3L, 4L, 1, 5L, null, null, null, null, null,
                Instant.parse("2026-01-01T00:00:01Z"), Instant.parse("2026-01-01T00:00:00Z"), null, null);
    }

    // ------------------------------------------------------------------ fixture

    @Configuration
    @EnableWebMvc
    @Import({AiGatewayController.class, org.ruoyi.aiintegration.web.AiGatewayStreamController.class,
            org.ruoyi.aiintegration.web.GatewayAuthorizer.class, AiWebEmbeddedConfiguration.class,
            AiEmbeddedRagConfiguration.class, AiEmbeddedRunConfiguration.class})
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
                    return new PlatformIdentity(TENANT, USER, MEMBER, true, identityScopes.get(), PV);
                }
            };
        }

        @Bean
        OrganizationMatchController.SubjectMatchSource subjectMatchSource() {
            return new OrganizationMatchController.SubjectMatchSource() {
                @Override
                public Set<String> currentSubjects(String tenantId, String subject, String action) {
                    return Set.of("member:" + MEMBER, "tenant_all:" + TENANT);
                }

                @Override
                public Optional<SubjectOrgFacts> orgFacts(String tenantId, String subject) {
                    return Optional.empty();
                }
            };
        }

        @Bean
        AiIdentityPort aiIdentityPort() {
            return () -> Optional.of(new AiExecutionFacts(TENANT, USER, MEMBER, PV, AV, identityScopes.get()));
        }

        @Bean
        JdbcTemplate jdbcTemplate() {
            return jdbc;
        }

        /** 与生产一致：应用权威 Jackson 2 ObjectMapper（测试用默认配置实例）。 */
        @Bean
        com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper();
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
        com.nageoffer.ai.ragent.framework.security.RevocationGuard defaultRevocationGuard() {
            return revocations;
        }

        @Bean
        ProductionAuthorizationProvider productionAuthorizationProvider() {
            return platformPermits;
        }

        /** 同名覆盖装配实现：路由/信封真实，受理与生命周期语义由既有 P2 测试覆盖。 */
        @Bean
        RunAdmissionService runAdmissionService() {
            return admission;
        }

        @Bean
        RunLifecycleService runLifecycleService() {
            return lifecycle;
        }
    }
}
