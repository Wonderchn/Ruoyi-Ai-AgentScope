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
import org.ruoyi.aiintegration.web.AiGatewayStreamController;
import org.ruoyi.aiweb.AiWebEmbeddedConfiguration;
import org.ruoyi.aiweb.security.AiInternalAccessBoundaryFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.File;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * E3 单元二收尾：SSE 流式本地传输（真实 Tomcat）。
 *
 * <p>覆盖 {@code LocalAiGatewayClient.forwardEventStream} 的帧协议：
 * <ul>
 *   <li>受保护帧：剥离 {@code : ai-delivery <permit> <operation>} 元数据行，
 *       公共帧原样交付，<b>逐帧</b>本地回执（tenant/member 取网关授权的成员）；</li>
 *   <li>未保护的 ping 帧直通；</li>
 *   <li>未保护却含 data/id/event 的 restricted 帧 → fail-closed（不投递、不回执，
 *       响应未提交时以 503 收口）；</li>
 *   <li>非 200（错误信封）直通，不做帧处理。</li>
 * </ul>
 */
@Tag("dev")
class LocalSseStreamingDispatchTest {

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

    /** 交付回执记录（permit, operation 二元组）。 */
    private static final List<String> deliveryAcks = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startContainer() throws Exception {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.register(FixtureConfig.class);
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "fixture", Map.of("ai.integration.enabled", "true", "ai.integration.transport", "local")));

        tomcat = new Tomcat();
        tomcat.setBaseDir("target/local-sse-tomcat");
        tomcat.setPort(0);
        File docBase = new File("target/local-sse-docroot");
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
        reset(jdbc, namedJdbc, transactions, transactionManager, revocations, platformPermits);
        deliveryAcks.clear();
    }

    private static HttpResponse<String> getEvents(String runId) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/api/ai/v1/runs/" + runId + "/events"))
                        .header("Authorization", "Bearer synthetic-session")
                        .header("Accept", "text/event-stream")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void protectedFramesAreStrippedAndAckedPerFrame() throws Exception {
        HttpResponse<String> response = getEvents("ok");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/event-stream");
        assertThat(response.body()).contains(": ping").contains("id: 1").contains("run.accepted")
                .contains("id: 2").contains("run.status");
        // 交付元数据行绝不外泄给客户端
        assertThat(response.body()).doesNotContain("ai-delivery");
        // 逐帧回执（两个受保护帧 → 两次本地释放，身份取网关授权成员）
        assertThat(deliveryAcks).containsExactlyInAnyOrder("permit-1 operation-1", "permit-2 operation-2");
    }

    @Test
    void unprotectedRestrictedFrameFailsClosed() throws Exception {
        HttpResponse<String> response = getEvents("unprotected");

        // 帧未投递、permit 不回执；响应未提交，按网关口径以 503 收口
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).doesNotContain("secret-data");
        assertThat(deliveryAcks).isEmpty();
    }

    @Test
    void errorEnvelopePassesThroughWithoutFrameProcessing() throws Exception {
        HttpResponse<String> response = getEvents("missing");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("RESOURCE_NOT_FOUND_OR_FORBIDDEN");
        assertThat(deliveryAcks).isEmpty();
    }

    // ------------------------------------------------------------------ fixture

    /** 模拟 AI 侧 SSE 写出（真实 RunStreamController 语义：状态/头 + 保护帧）。 */
    @RestController
    @RequestMapping("/internal/ai/v1")
    static class FixtureSseController {

        @GetMapping("/runs/{runId}/events")
        public void events(@PathVariable String runId, jakarta.servlet.http.HttpServletResponse response)
                throws java.io.IOException {
            if ("missing".equals(runId)) {
                response.setStatus(404);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":404,\"data\":{\"errorCode\":\"RESOURCE_NOT_FOUND_OR_FORBIDDEN\"}}");
                return;
            }
            response.setStatus(200);
            response.setContentType("text/event-stream;charset=UTF-8");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache, no-store");
            response.setHeader("X-Accel-Buffering", "no");
            var out = response.getOutputStream();
            if ("unprotected".equals(runId)) {
                out.write("id: 9\nevent: run.status\ndata: {\"secret-data\":true}\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            }
            out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
            out.write((": ai-delivery permit-1 operation-1\n"
                    + "id: 1\nevent: run.accepted\ndata: {\"runId\":\"r1\"}\n\n").getBytes(StandardCharsets.UTF_8));
            out.write((": ai-delivery permit-2 operation-2\n"
                    + "id: 2\nevent: run.status\ndata: {\"status\":\"RUNNING\"}\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    @Configuration
    @EnableWebMvc
    @Import({AiGatewayStreamController.class, org.ruoyi.aiintegration.web.GatewayAuthorizer.class,
            AiWebEmbeddedConfiguration.class, FixtureSseController.class})
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
                    return new PlatformIdentity(TENANT, USER, MEMBER, true, Set.of("ai:run:stream"), PV);
                }
            };
        }

        @Bean
        OrganizationMatchController.SubjectMatchSource subjectMatchSource() {
            return new OrganizationMatchController.SubjectMatchSource() {
                @Override
                public Optional<SubjectOrgFacts> orgFacts(String tenantId, String subject) {
                    return Optional.empty();
                }
            };
        }

        @Bean
        AiIdentityPort aiIdentityPort() {
            return () -> Optional.of(new AiExecutionFacts(TENANT, USER, MEMBER, PV, AV, Set.of("ai:run:stream")));
        }

        /** 交付回执记录（真实装配里由 AiDeliveryReleaserAdapter 委托 RevocationGuard）。 */
        @Bean
        org.ruoyi.aiweb.transport.AiDeliveryReleaser aiDeliveryReleaser() {
            return (tenantId, memberId, permitId, operationId) -> {
                assertThat(tenantId).isEqualTo(TENANT);
                assertThat(memberId).isEqualTo(MEMBER);
                deliveryAcks.add(permitId + " " + operationId);
            };
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
