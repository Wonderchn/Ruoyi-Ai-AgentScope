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

import com.nageoffer.ai.ragent.agent.controller.vo.AgentConversationVO;
import com.nageoffer.ai.ragent.agent.controller.vo.AgentMessageVO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentConversationMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentStateMapper;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.agent.service.ConversationBatchDeleteService;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
 * WP-034：Agent 会话面（F10）经网关**真实可达**的端到端判据。
 *
 * <p><b>为什么必须有这一条。</b>WP-033B 的装配判据只证明了"服务端可装配、身份取
 * PrincipalContext、路由与 AI 侧一致"——都是<b>类内部</b>的性质。而当时的公开面实际
 * 不可用，缺口有三层，且没有任何一条既有判据能发现（这也是本包存在的原因）：
 * <ol>
 *   <li><b>路径层</b>：网关转送目标恒为 {@code /internal/ai/v1 + subPath}，
 *       原控制器注册在 {@code /agent/v1/**} → 内层 handler 永远 miss；</li>
 *   <li><b>包络层</b>：网关强制"单 JSON 对象 + 整数 {@code code} 等于 HTTP 状态"，
 *       而 AI 侧旧 {@code Result} 的 {@code code} 是字符串 {@code "0"} → 503；</li>
 *   <li><b>交付层</b>：网关对 {@code GET} 走字节输出路径，2xx 必须携带
 *       {@code X-AI-Delivery-Permit}/{@code X-AI-Delivery-Operation} 两个 UUID 头 → 否则 503。</li>
 * </ol>
 * 本条判据用<b>真实 Tomcat + 真实网关 + 真实 LocalAiGatewayClient</b> 发真实 HTTP 请求，
 * 一次覆盖三层：任何一层回退，这里立刻红。
 *
 * <p><b>正例</b>：列表 / 消息 / 新建 / 改名 / 单删 / 批量删除六条路由各自 200 且形状正确；
 * 受保护读路径带回执头并真的登记了 permit；批量删除在单次请求内逐资源取 permit 并整批删除
 * （不是"循环调单删"）；无 scope 时 403；白名单外 404；未登录 401；
 * <b>外部直接请求内部路径返回 404 关闭体</b>（证明"内部前缀"不是对外新增面）。
 *
 * <p><b>负例（安全边界）</b>：批量删除的集合契约经网关同样成立（空集合 / 重复 ID / 超限 → 400，
 * 且在取得任何 permit 之前整体拒绝）；通配形态（如 {@code /agent/v1/conversations/1/2/3}）
 * 不匹配任何路由 → 404。
 */
@Tag("dev")
class LocalAgentConversationRouteDispatchTest {

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
    private static final AgentConversationService conversations = mock(AgentConversationService.class);

    @BeforeAll
    static void startContainer() throws Exception {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.register(FixtureConfig.class);
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "fixture", java.util.Map.of("ai.integration.enabled", "true",
                        "ai.integration.transport", "local", "agent.conversation.enabled", "true")));

        tomcat = new Tomcat();
        tomcat.setBaseDir("target/local-agent-conversation-tomcat");
        tomcat.setPort(0);
        File docBase = new File("target/local-agent-conversation-docroot");
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
        reset(jdbc, namedJdbc, transactions, transactionManager, revocations, platformPermits, conversations);
        when(revocations.enter(any(), anyString(), anyString())).thenAnswer(invocation ->
                new RevocationGuard.Operation(revocations,
                        java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString()));
        when(conversations.listByUserId(USER)).thenReturn(List.of(conversation("conv-1")));
        when(conversations.listMessages(eq("conv-1"), eq(USER))).thenReturn(List.of(message("msg-1")));
        when(conversations.listMessages(eq("conv-missing"), eq(USER))).thenReturn(List.of());
    }

    private static AgentConversationVO conversation(String id) {
        return AgentConversationVO.builder().conversationId(id).title("标题").lastTime(new Date(1_700_000_000_000L))
                .turns(2).build();
    }

    private static AgentMessageVO message(String id) {
        return AgentMessageVO.builder().id(id).role("user").content("内容").build();
    }

    // ------------------------------------------------------------------ 正向：四条路由经网关真实可达

    @Test
    void conversationListIsReachableThroughTheGatewayWithDeliveryReceipt() throws Exception {
        HttpResponse<String> response = get("/api/ai/v1/agent/v1/conversations");

        assertThat(response.statusCode())
                .as("WP-034 前这里是 404：路径不在内部前缀下，内层 handler 永远 miss")
                .isEqualTo(200);
        // 包络：整数 code 与状态一致（AI 侧旧 Result 的字符串 "0" 会被网关判为缺 code）
        assertThat(response.body()).contains("\"code\":200").contains("conv-1").contains("标题");
        // 交付证明：GET 走字节输出路径，缺 X-AI-Delivery-Permit/Operation 时 forwardBytes
        // 会抛 "delivery receipt missing" 并收敛为 503。所以**200 本身就是回执存在的证明**。
        // 这两个头是内层与网关之间的私有交付身份，网关不向浏览器转发——据此反向断言不外泄。
        assertThat(response.headers().firstValue("X-AI-Delivery-Permit"))
                .as("交付标识是内部协议，绝不能出现在面向客户端的响应里")
                .isEmpty();
        assertThat(response.headers().firstValue("X-AI-Delivery-Operation")).isEmpty();
        // permit 真的登记了，且用的是会话读动作
        verify(revocations).enter(any(), eq("conversation.read"), eq("tenant:conversations"));
        verify(conversations).listByUserId(USER);
    }

    @Test
    void conversationMessagesAreReachableAndScopedToTheAuthenticatedUser() throws Exception {
        HttpResponse<String> response = get("/api/ai/v1/agent/v1/conversations/conv-1/messages");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"code\":200").contains("msg-1");
        // 身份取 PrincipalContext（不是 UserContext）：userId 必须是登录主体自己的
        verify(conversations).listMessages("conv-1", USER);
        verify(revocations).enter(any(), eq("conversation.read"), eq("conv:conv-1"));
    }

    @Test
    void renameIsReachableAndDelegatesToTheExistingService() throws Exception {
        HttpResponse<String> response = put("/api/ai/v1/agent/v1/conversations/conv-1/title",
                "{\"title\":\"新标题\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"code\":200").contains("\"renamed\":true");
        verify(conversations).rename("conv-1", USER, "新标题");
    }

    @Test
    void deleteIsReachableAndDelegatesToTheExistingService() throws Exception {
        HttpResponse<String> response = delete("/api/ai/v1/agent/v1/conversations/conv-1");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"code\":200").contains("\"deleted\":true");
        verify(conversations).delete("conv-1", USER);
    }

    // ------------------------------------------- 正向续（F10-A1）：create 与 batch-delete 放行

    /**
     * F10-A1：批量删除经网关真实可达（此前刻意 404；D05/RW-01 之后按同一先例放行）。
     *
     * <p><b>为什么这条代替了"有意未放行"的负例。</b>原负例钉的是"批量授权决定之前不开面"；
     * D05 已作出决定、RW-01 已放行 general 路径并交付服务端契约与负例族，前置条件消灭。
     * 此处要求的不只是 200，还包括**受控服务语义**：字典序逐资源 permit（不是复用一个
     * 单资源 permit）、单次调用整批删除（不是循环调单删）。
     */
    @Test
    void batchDeleteIsReachableThroughTheControlledService() throws Exception {
        when(conversations.existsForUser("conv-1", USER)).thenReturn(true);
        when(conversations.existsForUser("conv-2", USER)).thenReturn(true);

        HttpResponse<String> response = post("/api/ai/v1/agent/v1/conversations/batch-delete",
                "{\"conversationIds\":[\"conv-2\",\"conv-1\"]}");

        assertThat(response.statusCode())
                .as("F10-A1 前这里是 404：白名单刻意未登记（批量授权待决定项）")
                .isEqualTo(200);
        assertThat(response.body()).contains("\"code\":200")
                .contains("\"deletedCount\":2").contains("\"permitCount\":2");
        // 逐资源 permit：两个不同资源各取一次，且顺序确定（字典序）；"复用一个单资源 permit"
        // 或乱序取 permit 都会被下面两条 + deleteBatch 的实参顺序钉住
        verify(revocations).enter(any(), eq("conversation.delete"), eq("conv:conv-1"));
        verify(revocations).enter(any(), eq("conversation.delete"), eq("conv:conv-2"));
        verify(conversations).deleteBatch(List.of("conv-1", "conv-2"), USER);
        verify(conversations, never()).delete(anyString(), anyString());
    }

    @Test
    void batchDeleteKeepsTheControlledSetContractThroughTheGateway() throws Exception {
        // 空集合：受控服务整体拒绝（不是静默成功），且不落任何删除
        HttpResponse<String> empty = post("/api/ai/v1/agent/v1/conversations/batch-delete",
                "{\"conversationIds\":[]}");
        assertThat(empty.statusCode()).isEqualTo(400);
        assertThat(empty.body()).contains("BAD_REQUEST");

        // 重复 ID：拒绝而不是静默去重（"实际动作集合 ≠ 请求集合"是契约要防的形态）
        HttpResponse<String> duplicates = post("/api/ai/v1/agent/v1/conversations/batch-delete",
                "{\"conversationIds\":[\"conv-1\",\"conv-1\"]}");
        assertThat(duplicates.statusCode()).isEqualTo(400);
        assertThat(duplicates.body()).contains("BAD_REQUEST");

        // 超过 100：拒绝而不是截断
        String oversize = java.util.stream.IntStream.range(0, 101)
                .mapToObj(i -> "\"conv-" + i + "\"")
                .collect(java.util.stream.Collectors.joining(",", "{\"conversationIds\":[", "]}"));
        HttpResponse<String> tooMany = post("/api/ai/v1/agent/v1/conversations/batch-delete", oversize);
        assertThat(tooMany.statusCode()).isEqualTo(400);
        assertThat(tooMany.body()).contains("BAD_REQUEST");

        // 三条负例都在取得 permit 之前拒绝：删除与 permit 一次都没发生
        verify(conversations, never()).deleteBatch(any(), anyString());
        verify(revocations, never()).enter(any(), anyString(), anyString());
    }

    @Test
    void createIsReachableAndDelegatesToTheExistingService() throws Exception {
        when(conversations.create("新会话")).thenReturn("conv-new");

        HttpResponse<String> response = post("/api/ai/v1/agent/v1/conversations",
                "{\"title\":\"新会话\"}");

        assertThat(response.statusCode())
                .as("F10-A1 前这里是 404：agent 路径没有 create 白名单行（G-52 只放行了 general 路径）")
                .isEqualTo(200);
        assertThat(response.body()).contains("\"code\":200")
                .contains("\"conversationId\":\"conv-new\"").contains("\"created\":true");
        // 归属只来自 PrincipalContext：委托只传标题（TitleRequest 只有 title 字段），
        // 请求体无法携带 tenant/member/user 影响归属
        verify(conversations).create("新会话");
    }

    // ------------------------------------------------------------------ 负向：安全边界

    @Test
    void unmatchedShapesAndUnknownRoutesStayHidden() throws Exception {
        // 通配不成立：多一段就不匹配任何白名单条目
        assertThat(get("/api/ai/v1/agent/v1/conversations/conv-1/extra").statusCode()).isEqualTo(404);
        assertThat(get("/api/ai/v1/agent/v1/unknown").statusCode()).isEqualTo(404);
        assertThat(get("/api/ai/v1/unknown-route").statusCode()).isEqualTo(404);
        // 未登录（缺 Authorization 头）→ 401，不是 404
        assertThat(CLIENT.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/api/ai/v1/agent/v1/conversations"))
                        .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    /**
     * 内部前缀不是对外新增面：外部直接请求它必须拿到 404 关闭体。
     *
     * <p>这一条把"移到内部前缀"与"不新增暴露面"两个要求同时钉住——如果哪天
     * 边界过滤器被摘掉或前缀被改回公开形态，本判据会失败。
     */
    @Test
    void internalPrefixIsNotExternallyReachable() throws Exception {
        HttpResponse<String> response = get("/internal/ai/v1/agent/v1/conversations");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("RESOURCE_NOT_FOUND_OR_FORBIDDEN");
        // 关闭发生在 handler 之前：业务服务一次都没被触碰
        verify(conversations, never()).listByUserId(anyString());
        verify(revocations, never()).enter(any(), anyString(), anyString());
    }

    @Test
    void facesWithoutTheConversationScopeAreRejected() throws Exception {
        // 身份源里该成员只有 kb 读权限：路由动作 conversation.read 未被持有 → 网关 403
        Set<String> original = CURRENT_SCOPES.get();
        CURRENT_SCOPES.set(Set.of("ai:kb:list"));
        try {
            HttpResponse<String> response = get("/api/ai/v1/agent/v1/conversations");

            assertThat(response.statusCode())
                    .as("网关按最小 scope 放行；未持有该权限必须 403，不能落到业务层")
                    .isEqualTo(403);
            verify(conversations, never()).listByUserId(anyString());
            verify(revocations, never()).enter(any(), anyString(), anyString());
        } finally {
            CURRENT_SCOPES.set(original);
        }
    }

    /**
     * 内层也复核 scope：网关已校验不构成内层免检的理由。
     *
     * <p>直接调用控制器（跳过网关）而主体缺该 scope 时必须 403——防止将来有人
     * 把内层控制器接到别的传输路径上时，授权判定在边界之外出现缺口。
     */
    @Test
    void surfaceAlsoEnforcesScopeWithoutTheGateway() {
        var surface = new AiEmbeddedAgentConversationConfiguration.LocalTransport
                .ConversationEnabled.ConversationSurface(conversations,
                providerOf(RevocationGuard.class, revocations),
                new ConversationBatchDeleteService(conversations,
                        providerOf(RevocationGuard.class, revocations)));

        com.nageoffer.ai.ragent.framework.context.PrincipalContext.set(
                new com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal(TENANT, USER, MEMBER,
                        PV, AV, Set.of("ai:kb:list"), "jti", "platform:local", 0L, 0L));
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(surface::listConversations)
                    .as("缺动作 scope 时内层也必须拒绝")
                    .isInstanceOf(com.nageoffer.ai.ragent.framework.security.P04AiException.class);
            verify(conversations, never()).listByUserId(anyString());
        } finally {
            com.nageoffer.ai.ragent.framework.context.PrincipalContext.clear();
        }
    }

    /** 极简 ObjectProvider：只服务"存在/不存在"两种情形的判据。 */
    private static <T> org.springframework.beans.factory.ObjectProvider<T> providerOf(Class<T> type, T value) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }
        };
    }

    // ------------------------------------------------------------------ HTTP 辅助

    private static HttpResponse<String> get(String path) throws Exception {
        return send("GET", path, null);
    }

    private static HttpResponse<String> put(String path, String body) throws Exception {
        return send("PUT", path, body);
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return send("POST", path, body);
    }

    private static HttpResponse<String> delete(String path) throws Exception {
        return send("DELETE", path, null);
    }

    private static HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer synthetic-session");
        if (body != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        } else if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ------------------------------------------------------------------ fixture

    /**
     * 当前成员的 scope 集合。
     *
     * <p>身份源在容器启动时构造，无法在用例里替换 bean；所以用它驱动
     * "该成员持有哪些权限"。网关与内层控制器都在请求期读取它，因此
     * 无 scope 负例可以真的改到判定所依赖的事实（而不是改一个没人读的字段）。
     */
    private static final java.util.concurrent.atomic.AtomicReference<Set<String>> CURRENT_SCOPES =
            new java.util.concurrent.atomic.AtomicReference<>(Set.of(
                    "ai:conversation:read", "ai:conversation:write", "ai:conversation:delete"));

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
            AiEmbeddedRagConfiguration.class, AiEmbeddedAgentConversationConfiguration.class})
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
                    Set.of("ai:conversation:read", "ai:conversation:write", "ai:conversation:delete")));
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

        /**
         * 会话业务服务替身：本判据盯的是"网关是否真能到达、形状是否正确、身份是否被传递"，
         * 不是 AgentConversationServiceImpl 的业务语义（那个由它自己的用例覆盖）。
         */
        @Bean
        AgentConversationService agentConversationService() {
            return conversations;
        }

        /** 会话面门控需要 Redisson（在途流闸门），这里给替身以装配删除能力。 */
        @Bean
        org.redisson.api.RedissonClient redissonClient() {
            return mock(org.redisson.api.RedissonClient.class);
        }

        @Bean
        AgentConversationMapper agentConversationMapper() {
            return mock(AgentConversationMapper.class);
        }

        @Bean
        AgentMessageMapper agentMessageMapper() {
            return mock(AgentMessageMapper.class);
        }

        @Bean
        AgentStateMapper agentStateMapper() {
            return mock(AgentStateMapper.class);
        }
    }
}
