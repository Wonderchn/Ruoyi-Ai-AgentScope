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

import com.nageoffer.ai.ragent.authorization.AiDeliveryPermitConfiguration;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentProfileDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentProfileMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentPromptMapper;
import com.nageoffer.ai.ragent.runtime.config.EngineModelAuthority;
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
import org.springframework.data.redis.core.StringRedisTemplate;
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
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F09:4-A：用户端智能体发现面（{@code GET /api/ai/v1/agent/v1/agents}）经网关**真实可达**的判据。
 *
 * <p>与 {@code LocalAgentConversationRouteDispatchTest} 同形，同层覆盖三层真实缺口：
 * <ol>
 *   <li><b>路径层</b>：网关转送目标恒为 {@code /internal/ai/v1 + subPath}；本控制器挂在
 *       {@code /internal/ai/v1} + {@code /agent/v1/agents}，公开路径由
 *       {@code AiGatewayController.ROUTES} 登记；</li>
 *   <li><b>包络层</b>：{@code ApiEnvelope}（整数 {@code code} 与 HTTP 状态一致）；</li>
 *   <li><b>交付层</b>：GET 走字节输出路径，2xx 必须携带
 *       {@code X-AI-Delivery-Permit}/{@code X-AI-Delivery-Operation}（否则
 *       {@code LocalAiGatewayClient:163-170} 抛 {@code delivery receipt missing} ⇒ 503）。</li>
 * </ol>
 *
 * <p><b>为什么这条是本卡的红锚</b>：ROUTES 未登记 {@code /agent/v1/agents} 之前，
 * 公开路径不匹配任何白名单条目 ⇒ 404；本类的 200 用例因此必红（红锚即"缺口真实存在"的证据）。
 *
 * <p><b>负例（安全边界）</b>：缺 {@code agent.execute} 对应 scope ⇒ 403；
 * 多一段/未知路径 ⇒ 404；未登录 ⇒ 401；外部直接请求内部前缀 ⇒ 404 关闭体。
 */
@Tag("dev")
class LocalAgentOptionRouteDispatchTest {

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
    private static final AgentProfileMapper agentProfileMapper = mock(AgentProfileMapper.class);
    private static final AgentPromptMapper agentPromptMapper = mock(AgentPromptMapper.class);
    private static final StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
    private static final EngineModelAuthority modelAuthority = mock(EngineModelAuthority.class);

    @BeforeAll
    static void startContainer() throws Exception {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.register(FixtureConfig.class);
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "fixture", java.util.Map.of("ai.integration.enabled", "true",
                        "ai.integration.transport", "local")));

        tomcat = new Tomcat();
        tomcat.setBaseDir("target/local-agent-option-tomcat");
        tomcat.setPort(0);
        File docBase = new File("target/local-agent-option-docroot");
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
        reset(jdbc, namedJdbc, transactions, transactionManager, revocations, platformPermits,
                agentProfileMapper, agentPromptMapper, stringRedisTemplate, modelAuthority);
        when(revocations.enter(any(), anyString(), anyString())).thenAnswer(invocation ->
                new RevocationGuard.Operation(revocations,
                        UUID.randomUUID().toString(), UUID.randomUUID().toString()));
        when(modelAuthority.requirePublished("agent.execute")).thenReturn(new EngineModelAuthority.PublishedModel(
                TENANT, "rev-1", 1L, "bailian", "qwen-max", "catalog-1", "sha256:params",
                "env:DASHSCOPE_API_KEY", "operator-1", Instant.parse("2026-10-06T00:00:00Z")));
        // 租户域自带内置行 ⇒ 服务端不会触碰模板域（模板域经 jdbc 直读，本夹具不桩它）。
        when(agentProfileMapper.selectList(any())).thenReturn(List.of(
                profile("a-builtin", "内置助手", 1, 0, 1_700_000_000_001L),
                profile("a-custom", "迁移前自定义", 0, 0, 1_700_000_000_002L)));
    }

    private static AgentProfileDO profile(String id, String name, int builtin, int active, long createTime) {
        AgentProfileDO profile = new AgentProfileDO();
        profile.setId(id);
        profile.setName(name);
        profile.setDescription("描述-" + id);
        profile.setAvatar("avatar-" + id);
        profile.setBuiltin(builtin);
        profile.setActive(active);
        profile.setCreateTime(new Date(createTime));
        return profile;
    }

    // ------------------------------------------------------------------ 正向：经网关真实可达

    @Test
    void agentOptionsAreReachableThroughTheGatewayWithDeliveryReceipt() throws Exception {
        HttpResponse<String> response = get("/api/ai/v1/agent/v1/agents");

        assertThat(response.statusCode())
                .as("ROUTES 未登记 /agent/v1/agents 之前这里是 404：路由不在白名单里")
                .isEqualTo(200);
        assertThat(response.body()).contains("\"code\":200")
                .contains("\"id\":\"a-builtin\"").contains("内置助手")
                .contains("\"executable\":true");
        // 交付证明：GET 走字节输出路径，缺两个 UUID 回执头时 forwardBytes 会抛
        // "delivery receipt missing" 并收敛为 503。所以 200 本身就是回执存在的证明；
        // 这两个头是内层与网关之间的私有交付身份，网关不向浏览器转发 —— 据此反向断言不外泄。
        assertThat(response.headers().firstValue("X-AI-Delivery-Permit"))
                .as("交付标识是内部协议，绝不能出现在面向客户端的响应里")
                .isEmpty();
        assertThat(response.headers().firstValue("X-AI-Delivery-Operation")).isEmpty();
        // permit 真的登记了（动作与资源引用都是本面的固定契约值）
        verify(revocations).enter(any(), eq("agent.execute"), eq("tenant:agent-options"));
        verify(modelAuthority).requirePublished("agent.execute");
        verify(agentProfileMapper).selectList(any());
    }

    // ------------------------------------------------------------------ 负向：安全边界

    @Test
    void facesWithoutTheAgentExecuteScopeAreRejected() throws Exception {
        // 身份源里该成员只有 kb 读权限：路由动作 agent.execute（→ ai:agent:execute）未被持有 → 网关 403
        Set<String> original = CURRENT_SCOPES.get();
        CURRENT_SCOPES.set(Set.of("ai:kb:list"));
        try {
            HttpResponse<String> response = get("/api/ai/v1/agent/v1/agents");

            assertThat(response.statusCode())
                    .as("网关按最小 scope 放行；未持有该权限必须 403，不能落到业务层")
                    .isEqualTo(403);
            verify(agentProfileMapper, never()).selectList(any());
            verify(revocations, never()).enter(any(), anyString(), anyString());
        } finally {
            CURRENT_SCOPES.set(original);
        }
    }

    @Test
    void unmatchedShapesAndUnknownRoutesStayHidden() throws Exception {
        // 通配不成立：多一段就不匹配任何白名单条目
        assertThat(get("/api/ai/v1/agent/v1/agents/extra").statusCode()).isEqualTo(404);
        assertThat(get("/api/ai/v1/agent/v1/unknown").statusCode()).isEqualTo(404);
        assertThat(get("/api/ai/v1/unknown-route").statusCode()).isEqualTo(404);
        // 未登录（缺 Authorization 头）→ 401，不是 404
        assertThat(CLIENT.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/api/ai/v1/agent/v1/agents"))
                        .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        verify(agentProfileMapper, never()).selectList(any());
    }

    /**
     * 内部前缀不是对外新增面：外部直接请求它必须拿到 404 关闭体，且业务面一次都没被触碰。
     */
    @Test
    void internalPrefixIsNotExternallyReachable() throws Exception {
        HttpResponse<String> response = get("/internal/ai/v1/agent/v1/agents");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("RESOURCE_NOT_FOUND_OR_FORBIDDEN");
        verify(agentProfileMapper, never()).selectList(any());
        verify(revocations, never()).enter(any(), anyString(), anyString());
    }

    // ------------------------------------------------------------------ HTTP 辅助

    private static HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer synthetic-session")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    // ------------------------------------------------------------------ fixture

    /**
     * 当前成员的 scope 集合。
     *
     * <p>身份源在容器启动时构造，无法在用例里替换 bean；所以用它驱动"该成员持有哪些权限"。
     * 网关在请求期读取它（{@code PlatformIdentitySource}），因此无 scope 负例可以真的改到
     * 判定所依赖的事实。
     */
    private static final java.util.concurrent.atomic.AtomicReference<Set<String>> CURRENT_SCOPES =
            new java.util.concurrent.atomic.AtomicReference<>(Set.of("ai:agent:execute"));

    private static PlatformIdentitySource identitySource = new PlatformIdentitySource() {
        @Override
        public TenantState tenantState(String tenantId) {
            return TenantState.ENABLED;
        }

        @Override
        public PlatformIdentity membership(String tenantId, String subject, String membershipId) {
            if (!MEMBER.equals(membershipId)) {
                return null;
            }
            return new PlatformIdentity(TENANT, USER, MEMBER, true, CURRENT_SCOPES.get(), PV);
        }
    };

    @Configuration
    @EnableWebMvc
    @Import({AiGatewayController.class, AiWebEmbeddedConfiguration.class,
            AiEmbeddedRagConfiguration.class, AiEmbeddedAgentCatalogConfiguration.class,
            AiDeliveryPermitConfiguration.class})
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
            return identitySource;
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
            return () -> Optional.of(new AiExecutionFacts(TENANT, USER, MEMBER, PV, AV,
                    Set.of("ai:agent:execute")));
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

        /** 目录面运行期组件（{@code AiEmbeddedAgentCatalogConfiguration} 的构造依赖）。 */
        @Bean
        AgentProfileMapper agentProfileMapper() {
            return agentProfileMapper;
        }

        @Bean
        AgentPromptMapper agentPromptMapper() {
            return agentPromptMapper;
        }

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return stringRedisTemplate;
        }

        /** 发布权威替身：本判据盯的是"端点是否可达、回执是否齐、permit 是否登记"，不是权威解析本身。 */
        @Bean
        EngineModelAuthority engineModelAuthority() {
            return modelAuthority;
        }

        /** 仅供其他内嵌配置的既有 bean 解析使用（本响应面不触碰）。 */
        @Bean
        org.redisson.api.RedissonClient redissonClient() {
            return mock(org.redisson.api.RedissonClient.class);
        }
    }
}
