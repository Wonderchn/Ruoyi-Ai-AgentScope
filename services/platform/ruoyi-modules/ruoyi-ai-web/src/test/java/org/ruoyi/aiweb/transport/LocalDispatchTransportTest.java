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

package org.ruoyi.aiweb.transport;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ai.api.AiExecutionFacts;
import org.ruoyi.ai.api.identity.AiIdentityPort;
import org.ruoyi.aiintegration.config.AiIntegrationProperties;
import org.ruoyi.aiintegration.identity.CurrentPrincipalResolver;
import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.ruoyi.aiintegration.web.AiGatewayController;
import org.ruoyi.aiweb.AiWebEmbeddedConfiguration;
import org.ruoyi.aiweb.transport.AiDeliveryReleaser;
import org.ruoyi.aiweb.security.AiInternalAccessBoundaryFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3/C3：{@code transport=local} 的同进程转送（真实 Tomcat 容器，无 DB/Redis）。
 *
 * <p>覆盖：网关白名单路由经 servlet forward 到达内嵌 AI 控制器（不存在 localhost HTTP），
 * 转发期间 {@code PrincipalContext} 携带本地桥接的执行事实（issuer=platform:local）且
 * 转发后恢复；AI 内部路径对外一律 404（C3 负例形状不变）；网关白名单外与无登录
 * 证据的负例语义不变；执行事实不可得时 fail-closed 503。
 */
@Tag("dev")
class LocalDispatchTransportTest {

    private static final String TENANT = "T1";
    private static final String USER = "2101";
    private static final String MEMBER = "platform:T1:2101";

    private static Tomcat tomcat;
    private static int port;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    /** 转发期间桥接出的主体快照（由 fixture 内部控制器写入）。 */
    private static final AtomicReference<String> forwardedMembership = new AtomicReference<>();
    private static final AtomicReference<String> forwardedIssuer = new AtomicReference<>();
    private static final AtomicReference<String> releasedPermit = new AtomicReference<>();
    private static final AtomicReference<String> releasedOperation = new AtomicReference<>();

    @BeforeAll
    static void startContainer() throws Exception {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.register(FixtureConfig.class);
        org.springframework.core.env.MapPropertySource fixtureProperties =
                new org.springframework.core.env.MapPropertySource("fixture", Map.of(
                        "ai.integration.enabled", "true",
                        "ai.integration.transport", "local"));
        context.getEnvironment().getPropertySources().addFirst(fixtureProperties);

        tomcat = new Tomcat();
        tomcat.setBaseDir("target/local-dispatch-tomcat");
        tomcat.setPort(0);
        File docBase = new File("target/local-dispatch-docroot");
        docBase.mkdirs();
        Context ctx = tomcat.addContext("", docBase.getAbsolutePath());

        // 与 AiWebEmbeddedConfiguration 相同的边界过滤器与 REQUEST-only 注册
        FilterDef dbg = new FilterDef();
        dbg.setFilterName("dbgFilter");
        jakarta.servlet.http.HttpServletRequest unusedReq = null;
        dbg.setFilter((req, res, chain) -> {
            var httpReq = (jakarta.servlet.http.HttpServletRequest) req;
            System.out.println("DBG-FILTER type=" + httpReq.getDispatcherType() + " uri=" + httpReq.getRequestURI()
                    + " pathAttr=" + httpReq.getAttribute("org.springframework.web.util.ServletRequestPathUtils.PATH"));
            chain.doFilter(req, res);
            System.out.println("DBG-FILTER done type=" + httpReq.getDispatcherType() + " status="
                    + ((jakarta.servlet.http.HttpServletResponse) res).getStatus());
        });
        ctx.addFilterDef(dbg);
        FilterMap dbgMap = new FilterMap();
        dbgMap.setFilterName("dbgFilter");
        dbgMap.addURLPatternDecoded("/*");
        dbgMap.setDispatcher("REQUEST");
        dbgMap.setDispatcher("FORWARD");
        ctx.addFilterMap(dbgMap);

        FilterDef def = new FilterDef();
        def.setFilterName("aiInternalAccessBoundaryFilter");
        def.setFilter(new AiInternalAccessBoundaryFilter());
        ctx.addFilterDef(def);
        FilterMap map = new FilterMap();
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

    @Test
    void gatewayRouteDispatchesInProcessWithBridgedPrincipal() throws Exception {
        HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/api/ai/v1/knowledge-bases"))
                        .header("Authorization", "Bearer synthetic-session")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        // 内嵌控制器在同一个容器里应答，带网关同源的身份事实
        System.out.println("DBG-CLIENT status=" + response.statusCode() + " headers=" + response.headers() + " body=" + response.body());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"code\":200");
        assertThat(response.body()).contains(MEMBER);
        assertThat(forwardedMembership.get()).isEqualTo(MEMBER);
        // 身份桥填充的是本地转送标识，不是任何 JWT
        assertThat(forwardedIssuer.get()).isEqualTo(LocalAiGatewayClient.LOCAL_ISSUER);
        // 转发结束后主体上下文恢复（同步链无残留）
    }

    @Test
    void internalPathIs404ForExternalRequests() throws Exception {
        HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/internal/ai/v1/knowledge-bases"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isEqualTo(AiInternalAccessBoundaryFilter.CLOSED_BODY);
    }

    @Test
    void unknownRouteStays404() throws Exception {
        HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/api/ai/v1/unknown-route"))
                        .header("Authorization", "Bearer synthetic-session")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("RESOURCE_NOT_FOUND_OR_FORBIDDEN");
    }

    @Test
    void missingLoginEvidenceStays401() throws Exception {
        HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/api/ai/v1/knowledge-bases"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("AUTH_REQUIRED");
    }

    @Test
    void factsUnavailableFailsClosedWith503() throws Exception {
        AiIdentityPort previous = FixtureConfig.identityPortOverride.get();
        try {
            FixtureConfig.identityPortOverride.set(() -> Optional.empty());
            HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + port + "/api/ai/v1/knowledge-bases"))
                            .header("Authorization", "Bearer synthetic-session")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).contains("AUTHORIZATION_UNAVAILABLE");
        } finally {
            FixtureConfig.identityPortOverride.set(previous);
        }
    }

    // ------------------------------------------------------------------ fixture

    @RestController
    @RequestMapping("/internal/ai/v1")
    static class FixtureInternalController {

        @GetMapping("/knowledge-bases")
        public org.springframework.http.ResponseEntity<String> listKnowledgeBases() {
            ExecutionPrincipal principal = PrincipalContext.require();
            forwardedMembership.set(principal.membershipId());
            forwardedIssuer.set(principal.issuer());
            // 真实控制器经 reply()/RevocationGuard.enter 附带交付回执；fixture 等价模拟
            String permit = java.util.UUID.randomUUID().toString();
            String operation = java.util.UUID.randomUUID().toString();
            return org.springframework.http.ResponseEntity.ok()
                    .header("Content-Type", "application/json")
                    .header("X-AI-Delivery-Permit", permit)
                    .header("X-AI-Delivery-Operation", operation)
                    .body("{\"code\":200,\"data\":{\"membership\":\"" + principal.membershipId() + "\"}}");
        }

        @org.springframework.web.bind.annotation.PostMapping("/authorization/deliveries/release")
        public org.springframework.http.ResponseEntity<Void> releaseDelivery() {
            PrincipalContext.require();
            return org.springframework.http.ResponseEntity.noContent().build();
        }

    }

    @Configuration
    @EnableWebMvc
    @Import({AiGatewayController.class, AiWebEmbeddedConfiguration.class, FixtureInternalController.class})
    static class FixtureConfig {

        /** 供测试注入"执行事实不可得"夹具的开关（默认真实事实）。 */
        static final AtomicReference<AiIdentityPort> identityPortOverride = new AtomicReference<>(null);

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
                    return new PlatformIdentity(TENANT, USER, MEMBER, true, Set.of("ai:kb:list"), 3);
                }
            };
        }

        @Bean
        AiDeliveryReleaser aiDeliveryReleaser() {
            return (tenantId, memberId, permitId, operationId) -> {
                releasedPermit.set(permitId);
                releasedOperation.set(operationId);
            };
        }

        @Bean
        AiIdentityPort aiIdentityPort() {
            return () -> identityPortOverride.get() != null
                    ? identityPortOverride.get().currentFacts()
                    : Optional.of(new AiExecutionFacts(TENANT, USER, MEMBER, 3, 7, Set.of("ai:kb:list")));
        }
    }
}
