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
import org.ruoyi.aiintegration.authorization.OrganizationMatchController.SubjectMatchSource;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * F10-A1：{@code GET /agent/v1/meta} 的**失败层级口径**经网关真实钉死
 * （真实 Tomcat + 真实 {@link AiGatewayController} + 真实本地转送）。
 *
 * <p><b>为什么需要这条判据。</b>meta 的失败形态来自三个层级，而 W6 实测只观察到第一层，
 * 曾被归因成"引擎门控"（复核 P4 已更正）。三层是：
 * <ol>
 *   <li><b>网关 scope 门先行</b>：路由动作 {@code agent.execute} → 权限 {@code ai:agent:execute}
 *       （7120，V6 注"默认不分配"）。默认部署下身份不持有它 ⇒ 网关在**任何转送之前**
 *       返回 403；{@code AgentMetaController:89} 的 {@code requireScope} 是可到达时的第二道，
 *       不是第一道；</li>
 *   <li><b>引擎门控</b>：{@code ragent.engine.type} 未设 ⇒ {@code AgentMetaController}
 *       （{@code @ConditionalOnAgentEngine}）不是 bean ⇒ 内层路由未命中，本地转送以
 *       "internal route unmatched" 收敛为 503（fail-closed；不 200、不 404、不泄露原因）；</li>
 *   <li><b>权威不可得</b>：即使引擎装配、scope 齐备，{@code EngineModelAuthority} 读不到
 *       已发布版本也是 503（由 {@code AgentMetaControllerTest} 的单元判据钉住）。</li>
 * </ol>
 * 本判据把 ①② 两条钉在**真实 HTTP 层**：没有它，"403 是 scope 门先行"与"503 是引擎未装配"
 * 只能靠人工探针记忆，无法防回归（本例回放在树内无先例）。
 *
 * <p><b>NOT_RUN（B 面，不越界声称）</b>：真实引擎的 meta 200 需要
 * {@code ragent.engine.type=agent} × WP-032 11 项硬门 × 已发布模型权威，不在本判据内。
 */
@Tag("dev")
class LocalAgentMetaRouteDispatchTest {

    private static final String TENANT = "T1";
    private static final String USER = "2101";
    private static final String MEMBER = "platform:T1:2101";
    private static final int PV = 7;

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

    /**
     * 当前成员持有的平台权限（每个用例可替换）。
     *
     * <p>默认**不含** {@code ai:agent:execute}：这正是发货配置的实测口径（V6 播种行
     * 7120 默认不分配）——meta 的第一层失败就是它。
     */
    private static final AtomicReference<Set<String>> scopes =
            new AtomicReference<>(Set.of("ai:conversation:read", "ai:conversation:write", "ai:conversation:delete"));

    @BeforeAll
    static void startContainer() throws Exception {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.register(FixtureConfig.class);
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "fixture", java.util.Map.of("ai.integration.enabled", "true",
                        "ai.integration.transport", "local")));

        tomcat = new Tomcat();
        tomcat.setBaseDir("target/local-agent-meta-tomcat");
        tomcat.setPort(0);
        File docBase = new File("target/local-agent-meta-docroot");
        docBase.mkdirs();
        Context ctx = tomcat.addContext("", docBase.getAbsolutePath());

        // 与生产装配同形：内部前缀对外一律 404（关闭体），只有 FORWARD 分派可达。
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
        // 引擎关的夹具：AgentMetaController（@ConditionalOnAgentEngine）**不是 bean**——
        // 本类只导入网关/传输/信封装配，不导入任何引擎面配置。
        scopes.set(Set.of("ai:conversation:read", "ai:conversation:write", "ai:conversation:delete"));
    }

    // ------------------------------------------------------------------ ① scope 门先行：403

    @Test
    void metaWithoutAgentExecuteScopeIsForbiddenAtTheGateway() throws Exception {
        HttpResponse<String> response = get("/api/ai/v1/agent/v1/meta");

        assertThat(response.statusCode())
                .as("默认身份不持有 ai:agent:execute（7120，V6「默认不分配」）："
                        + "网关最小 scope 门在任何转送之前拒绝 —— 403 是当前实测口径，不是引擎门控的 503")
                .isEqualTo(403);
        assertThat(response.body()).contains("\"code\":403").contains("FORBIDDEN");
    }

    // ------------------------------------------------------------------ ② 引擎关：内层未命中 → 503

    @Test
    void metaWithScopeButEngineOffFailsClosedAs503() throws Exception {
        Set<String> original = scopes.get();
        scopes.set(Set.of("ai:conversation:read", "ai:agent:execute"));
        try {
            HttpResponse<String> response = get("/api/ai/v1/agent/v1/meta");

            assertThat(response.statusCode())
                    .as("临时授 7120＋引擎关：网关已放行，但 AgentMetaController 不是 bean ⇒ "
                            + "内层路由未命中 ⇒ fail-closed 503（引擎门控是 scope 门的后一层）")
                    .isEqualTo(503);
            assertThat(response.body())
                    .contains("\"code\":503")
                    .contains("AUTHORIZATION_UNAVAILABLE");
        } finally {
            scopes.set(original);
        }
    }

    // ------------------------------------------------------------------ 结构锚点与登录负例

    @Test
    void metaRouteIsWhitelistedExactlyAsAgentExecute() throws Exception {
        Field field = AiGatewayController.class.getDeclaredField("ROUTES");
        field.setAccessible(true);
        List<?> routes = (List<?>) field.get(null);

        List<String> metaRoutes = new ArrayList<>();
        for (Object route : routes) {
            Method pattern = route.getClass().getDeclaredMethod("pattern");
            pattern.setAccessible(true);
            if (!"/agent/v1/meta".equals(pattern.invoke(route))) {
                continue;
            }
            Method method = route.getClass().getDeclaredMethod("method");
            method.setAccessible(true);
            Method action = route.getClass().getDeclaredMethod("action");
            action.setAccessible(true);
            metaRoutes.add(method.invoke(route) + " " + pattern.invoke(route) + " -> " + action.invoke(route));
        }

        assertThat(metaRoutes)
                .as("meta 的 403 口径依赖这条逐条登记：动作必须是 agent.execute（→ ai:agent:execute）")
                .containsExactly("GET /agent/v1/meta -> agent.execute");
    }

    @Test
    void metaWithoutLoginStays401AndWildcardShapesStay404() throws Exception {
        assertThat(getWithoutLogin("/api/ai/v1/agent/v1/meta").statusCode())
                .as("未登录必须先于任何业务处理被拒（路由命中 → 401）")
                .isEqualTo(401);
        assertThat(get("/api/ai/v1/agent/v1/meta/extra").statusCode()).isEqualTo(404);
        assertThat(get("/api/ai/v1/unknown-route").statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ HTTP 辅助

    private static HttpResponse<String> get(String path) throws Exception {
        return send(path, true);
    }

    private static HttpResponse<String> getWithoutLogin(String path) throws Exception {
        return send(path, false);
    }

    private static HttpResponse<String> send(String path, boolean withLogin) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET();
        if (withLogin) {
            builder.header("Authorization", "Bearer synthetic-session");
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ------------------------------------------------------------------ fixture

    @Configuration
    @EnableWebMvc
    @Import({AiGatewayController.class, AiWebEmbeddedConfiguration.class,
            AiEmbeddedRagConfiguration.class})
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
                    return new PlatformIdentity(TENANT, USER, MEMBER, true, scopes.get(), PV);
                }
            };
        }

        @Bean
        SubjectMatchSource subjectMatchSource() {
            return new SubjectMatchSource() {
                @Override
                public Set<String> currentSubjects(String tenantId, String subject, String action) {
                    return Set.of("member:" + MEMBER, "tenant_all:" + TENANT);
                }

                @Override
                public Optional<SubjectMatchSource.SubjectOrgFacts> orgFacts(String tenantId, String subject) {
                    return Optional.empty();
                }
            };
        }

        @Bean
        AiIdentityPort aiIdentityPort() {
            return () -> Optional.of(new AiExecutionFacts(TENANT, USER, MEMBER, PV, 3, scopes.get()));
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
        com.nageoffer.ai.ragent.framework.security.RevocationGuard defaultRevocationGuard() {
            return revocations;
        }

        @Bean
        ProductionAuthorizationProvider productionAuthorizationProvider() {
            return platformPermits;
        }
    }
}
