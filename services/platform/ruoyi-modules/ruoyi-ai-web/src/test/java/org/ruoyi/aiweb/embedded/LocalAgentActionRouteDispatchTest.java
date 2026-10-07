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

import com.nageoffer.ai.ragent.agent.runtime.AgentLedger;
import com.nageoffer.ai.ragent.agent.runtime.SandboxTicketClient;
import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.runtime.RunAccessService;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E3 单元二收尾：agent（P3 受控动作）路由组经<b>本地接口</b>端到端（真实 Tomcat）。
 *
 * <p>装配真实 {@code AiEmbeddedAgentActionConfiguration}（真实 AgentActionController，
 * 动作账本/沙箱票据/run 账本以 mock 驱动——其内部语义由既有 P3 测试覆盖）：
 * <ul>
 *   <li>{@code GET /runs/{id}/actions} → 200 + 交付回执头；
 *   <li>{@code POST /runs/{id}/approvals} 形状合法但审批人策略关闭 → 403（fail-closed）；
 *   <li>{@code GET /runs/{id}/reconciliations/{actionId}} 未知动作 → 404；
 *   <li>非 agent.run 的 run → 404（不泄露存在性）；白名单外/无登录负例保持。</li>
 * </ul>
 */
@Tag("dev")
class LocalAgentActionRouteDispatchTest {

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
    private static final RevocationGuard revocations = mock(RevocationGuard.class);
    private static final ProductionAuthorizationProvider platformPermits =
            mock(ProductionAuthorizationProvider.class);
    private static final AgentLedger agentLedger = mock(AgentLedger.class);
    private static final RunLedgerDao runLedgerDao = mock(RunLedgerDao.class);
    private static final RunAccessService runAccessService = mock(RunAccessService.class);
    private static final AiResourceAuthorizationService authorizationService =
            mock(AiResourceAuthorizationService.class);
    private static final SandboxTicketClient sandbox = mock(SandboxTicketClient.class);

    @BeforeAll
    static void startContainer() throws Exception {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.register(FixtureConfig.class);
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "fixture", Map.of("ai.integration.enabled", "true", "ai.integration.transport", "local",
                        "p2.enabled", "true", "p2.object-store.type", "fs",
                        "p2.object-store.root", "target/local-agent-objects", "p3.enabled", "true")));

        tomcat = new Tomcat();
        tomcat.setBaseDir("target/local-agent-route-tomcat");
        tomcat.setPort(0);
        File docBase = new File("target/local-agent-route-docroot");
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
        reset(jdbc, namedJdbc, transactions, transactionManager, revocations, platformPermits,
                agentLedger, runLedgerDao, runAccessService, authorizationService, sandbox);
        when(revocations.enter(any(), any(), any())).thenAnswer(invocation ->
                new RevocationGuard.Operation(revocations, java.util.UUID.randomUUID().toString(),
                        java.util.UUID.randomUUID().toString()));
        when(runAccessService.resources()).thenReturn(authorizationService);
        when(runLedgerDao.findRun(TENANT, "run-1")).thenReturn(Optional.of(runRecord("run-1", "agent.run")));
        when(agentLedger.actions(any())).thenReturn(List.of());
        when(agentLedger.action(eq(TENANT), any())).thenReturn(Optional.empty());
    }

    private static RunRecord runRecord(String runId, String action) {
        return new RunRecord(TENANT, runId, MEMBER, USER, action, "WAITING_APPROVAL", "hash", "key",
                "{}", "{}", "v1", PV, AV, "[]", 3L, 4L, 1, 5L, null, null, null, null, null,
                null, Instant.parse("2026-01-01T00:00:00Z"), null, null);
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + path))
                        .header("Authorization", "Bearer synthetic-session").GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + path))
                        .header("Authorization", "Bearer synthetic-session")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void runActionsGoLocalWithDeliveryPermit() throws Exception {
        HttpResponse<String> response = get("/api/ai/v1/runs/run-1/actions");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"code\":200").contains("\"data\":[]");
        verify(revocations).enter(any(), eq("run.get"), eq("run:run-1"));
    }

    @Test
    void approvalWithInitiatorPolicyClosedStays403() throws Exception {
        HttpResponse<String> response = post("/api/ai/v1/runs/run-1/approvals",
                "{\"actionId\":\"act-1\",\"argsHash\":\"h\",\"toolVersion\":\"v1\",\"target\":\"t\","
                        + "\"approvalVersion\":1,\"decision\":\"ALLOW\"}");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("APPROVER_POLICY_CLOSED");
    }

    @Test
    void reconciliationOfUnknownActionStays404() throws Exception {
        HttpResponse<String> response = get("/api/ai/v1/runs/run-1/reconciliations/act-missing");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("RESOURCE_NOT_FOUND_OR_FORBIDDEN");
    }

    @Test
    void nonAgentRunIsHiddenAndNegativesStay() throws Exception {
        when(runLedgerDao.findRun(TENANT, "run-2")).thenReturn(Optional.of(runRecord("run-2", "rag.chat")));

        HttpResponse<String> hidden = get("/api/ai/v1/runs/run-2/actions");
        assertThat(hidden.statusCode()).isEqualTo(404);

        assertThat(get("/api/ai/v1/unknown-route").statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ fixture

    @Configuration
    @EnableWebMvc
    @Import({AiGatewayController.class, AiWebEmbeddedConfiguration.class, AiEmbeddedRagConfiguration.class,
            AiEmbeddedRunConfiguration.class, AiEmbeddedDocumentConfiguration.class,
            AiEmbeddedAgentActionConfiguration.class})
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
                    return new PlatformIdentity(TENANT, USER, MEMBER, true,
                            Set.of("ai:run:read", "ai:run:approve", "ai:run:reconcile"), PV);
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
            return () -> Optional.of(new AiExecutionFacts(TENANT, USER, MEMBER, PV, AV,
                    Set.of("ai:run:read", "ai:run:approve", "ai:run:reconcile")));
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

        /** 同名覆盖装配实现：路由/信封/许可真实，账本与沙箱语义由既有 P3 测试覆盖。 */
        @Bean
        AgentLedger agentLedger() {
            return agentLedger;
        }

        @Bean
        RunLedgerDao runLedgerDao() {
            return runLedgerDao;
        }

        @Bean
        RunAccessService runAccessService() {
            return runAccessService;
        }

        @Bean
        SandboxTicketClient sandboxTicketClient() {
            return sandbox;
        }
    }
}
