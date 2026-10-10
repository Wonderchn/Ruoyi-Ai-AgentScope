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

import com.nageoffer.ai.ragent.authorization.AiResourceWriteService;
import com.nageoffer.ai.ragent.authorization.dao.AiAclEpochMapper;
import com.nageoffer.ai.ragent.authorization.dao.AiResourceAclMapper;
import com.nageoffer.ai.ragent.authorization.dao.AiResourceMapper;
import com.nageoffer.ai.ragent.authorization.dao.ResourceSourceRefMapper;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import org.apache.catalina.Context;
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
import org.ruoyi.aiweb.AiWebEmbeddedConfiguration;
import org.ruoyi.aiweb.security.AiInternalAccessBoundaryFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E3 单元二收尾：rag 授权/资源路由组经<b>本地接口</b>端到端（真实 Tomcat 容器）。
 *
 * <p>装配真实 {@code AiEmbeddedRagConfiguration}（真实 AiResourceController +
 * 真实 AiResourceAuthorizationService + 本地事实/许可/检查端口），持久层用 mock 驱动
 * （无需 DB/Redis）：
 * <ul>
 *   <li>白名单 GET 路由经 LocalAiGatewayClient 直调到内嵌 AI 控制器，
 *       信封与交付回执（ACK 经 AiDeliveryReleaser 本地释放）完整；</li>
 *   <li>业务负例语义不减：DENY→404、STALE→409、内部路径对外 404、白名单外 404；</li>
 *   <li>授权链的 platform 事实全部来自本地端口（身份源/组织匹配），无 localhost HTTP。</li>
 * </ul>
 */
@Tag("dev")
class LocalRagRouteDispatchTest {

    private static final String TENANT = "T1";
    private static final String USER = "2101";
    private static final String MEMBER = "platform:T1:2101";
    private static final int PV = 7;
    private static final int AV = 3;

    private static Tomcat tomcat;
    private static int port;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static final AiResourceMapper resourceMapper = mock(AiResourceMapper.class);
    private static final AiResourceAclMapper aclMapper = mock(AiResourceAclMapper.class);
    private static final AiAclEpochMapper epochMapper = mock(AiAclEpochMapper.class);
    private static final ResourceSourceRefMapper sourceRefMapper = mock(ResourceSourceRefMapper.class);
    private static final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private static final NamedParameterJdbcTemplate namedJdbc = mock(NamedParameterJdbcTemplate.class);
    private static final TransactionOperations transactions = mock(TransactionOperations.class);
    private static final RevocationGuard revocations = mock(RevocationGuard.class);
    private static final ProductionAuthorizationProvider platformPermits =
            mock(ProductionAuthorizationProvider.class);

    /** 交付回执发生在响应已提交之后（同一请求线程），测试需等待其完成。 */
    private static volatile java.util.concurrent.CountDownLatch deliveryAcks = new java.util.concurrent.CountDownLatch(0);

    @BeforeAll
    static void startContainer() throws Exception {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.register(FixtureConfig.class);
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "fixture", Map.of("ai.integration.enabled", "true", "ai.integration.transport", "local")));

        tomcat = new Tomcat();
        tomcat.setBaseDir("target/local-rag-route-tomcat");
        tomcat.setPort(0);
        File docBase = new File("target/local-rag-route-docroot");
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
        reset(resourceMapper, aclMapper, epochMapper, sourceRefMapper, jdbc, namedJdbc, transactions,
                revocations, platformPermits);

        when(epochMapper.findVersion(TENANT)).thenReturn(Optional.of(AV));
        when(resourceMapper.findByPk(TENANT, "KB", "kb-1")).thenReturn(Optional.of(
                new AiResourceMapper.AiResourceRow(TENANT, "KB", "kb-1", MEMBER, null, null, null, "ACTIVE", 1L)));
        when(resourceMapper.listActive(TENANT)).thenReturn(List.of(
                new AiResourceMapper.AiResourceRow(TENANT, "KB", "kb-1", MEMBER, null, null, null, "ACTIVE", 1L)));
        when(aclMapper.findByResource(TENANT, "KB", "kb-1")).thenReturn(List.of(
                new AiResourceAclMapper.AclRow("acl-1", TENANT, "KB", "kb-1", "MEMBER", MEMBER,
                        "kb.read", null, MEMBER)));
        when(aclMapper.listTenantRows(TENANT)).thenReturn(List.of(
                new AiResourceAclMapper.AclRow("acl-1", TENANT, "KB", "kb-1", "MEMBER", MEMBER,
                        "kb.read", null, MEMBER)));
        when(sourceRefMapper.findChildren(TENANT, "KB", "kb-1")).thenReturn(List.of());
        when(namedJdbc.query(contains("FROM platform.ai_knowledge_base"), anyMap(), any(RowMapper.class)))
                .thenReturn(List.of(new AiResourceWriteService.KnowledgeBaseView(
                        "kb-1", "kb-one", "text-embedding-3", "p1-col", MEMBER, null)));
        when(revocations.enter(any(), any(), any())).thenAnswer(invocation ->
                new RevocationGuard.Operation(revocations, java.util.UUID.randomUUID().toString(),
                        java.util.UUID.randomUUID().toString()));
        deliveryAcks = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            deliveryAcks.countDown();
            return null;
        }).when(revocations).releaseDelivery(any(), any(), any(), any());
    }

    private static HttpResponse<String> get(String path, boolean withLogin) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (withLogin) {
            builder.header("Authorization", "Bearer synthetic-session");
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String body, boolean withLogin) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (withLogin) {
            builder.header("Authorization", "Bearer synthetic-session");
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void knowledgeBaseListGoesLocalWithEnvelopeAndDeliveryAck() throws Exception {
        HttpResponse<String> response = get("/api/ai/v1/knowledge-bases", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"code\":200").contains("kb-1").contains("kb-one");
        // 交付回执经本地 releaser 释放（跨进程形态是独立 POST；内嵌形态无 HTTP）
        assertThat(deliveryAcks.await(5, java.util.concurrent.TimeUnit.SECONDS))
                .as("交付回执应在响应提交后完成").isTrue();
        verify(revocations).releaseDelivery(org.mockito.ArgumentMatchers.eq(TENANT),
                org.mockito.ArgumentMatchers.eq(MEMBER), any(), any());
    }

    @Test
    void knowledgeBaseGetGoesLocalWithDocumentRefs() throws Exception {
        HttpResponse<String> response = get("/api/ai/v1/knowledge-bases/kb-1", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"code\":200").contains("\"kbId\":\"kb-1\"");
    }

    @Test
    void denyStays404WithoutExistenceLeak() throws Exception {
        // kb-2 未注册：DENY 与不存在同形
        HttpResponse<String> response = get("/api/ai/v1/knowledge-bases/kb-2", true);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("RESOURCE_NOT_FOUND_OR_FORBIDDEN");
    }

    @Test
    void staleAclVersionStays409() throws Exception {
        // epoch 前进（8）而主体 av=3：STALE，要求重取版本后重试
        when(epochMapper.findVersion(TENANT)).thenReturn(Optional.of(AV + 5));

        HttpResponse<String> response = get("/api/ai/v1/knowledge-bases/kb-1", true);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("POLICY_VERSION_STALE");
    }

    @Test
    void internalPathIs404ForExternalRequests() throws Exception {
        HttpResponse<String> response = get("/internal/ai/v1/knowledge-bases", false);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isEqualTo(AiInternalAccessBoundaryFilter.CLOSED_BODY);
    }

    @Test
    void whitelistOutsideStays404AndMissingLoginStays401() throws Exception {
        assertThat(get("/api/ai/v1/unknown-route", true).statusCode()).isEqualTo(404);
        assertThat(get("/api/ai/v1/knowledge-bases", false).statusCode()).isEqualTo(401);
    }

    // ------------------------------------------- N2：F03 批量删除的**公开可达性**正腿（RW-01）

    /**
     * N2：{@code POST /api/ai/v1/conversations/batch-delete} 的公开可达性正腿。
     *
     * <p><b>为什么此前这块是空的。</b>全仓对该<b>公开</b>路径发 HTTP 的测试此前为 0：护栏只验
     * "白名单里有一行 + 内层有 handler"（源码级），而"经网关真的能走到 F03 控制器"没有任何判据。
     * 源码级护栏对"路由登记了但装配没生效""客户端可见子路径写错"这一类缺陷是结构性看不见的。
     *
     * <p><b>判据选择。</b>F03 的集合形状校验（① {@code requireWellFormedSet}）在
     * <b>任何授权之前</b>执行，且只有 F03 控制器会产出 {@code BAD_REQUEST}+{@code errorCode}。
     * 因此"空集合 ⇒ 400 + {@code data.errorCode=BAD_REQUEST}"**只可能**来自
     * "白名单命中 → 内层前缀命中 → 控制器被调用"这条真实链路：
     * <ul>
     *   <li>未放行/未映射 ⇒ 404（白名单外与内层裸路径都如此）；</li>
     *   <li>映射了但信封是字符串 code ⇒ 503（"缺少包络 code"，D2 实测定案）；</li>
     *   <li>体为空但放行了 ⇒ 也会是 400，所以本判据额外断言<b>整数</b> {@code code} 与符号码，
     *       把"网关自己产生的错误"排除在外（网关不产出 AI 侧符号码）。</li>
     * </ul>
     *
     * <p><b>真实外发 NOTE。</b>本组不经模型/检索外发，也不连真库（JDBC 为替身）——
     * 它验的是<b>网关到 F03 控制器的可达性与信封</b>，不是删除语义本身（后者由
     * {@code ConversationBatchDeleteServiceTest}/{@code HttpTest} 覆盖）。真库端到端仍 NOT_RUN。
     */
    @Test
    void f03BatchDeleteIsPubliclyReachableThroughTheGateway() throws Exception {
        HttpResponse<String> response =
                post("/api/ai/v1/conversations/batch-delete", "{\"conversationIds\":[]}", true);

        // 关键否定：不是"没路由"（404）、不是"没登录"（401）、更不是信封被网关收敛（503）
        assertThat(response.statusCode())
                .as("F03 公开路由必须真的可达（404=没放行/没映射，503=信封不是整数 code）")
                .isEqualTo(400);
        // 关键肯定：错误码来自 AI 侧（网关不会产出它），且信封是**整数** code
        assertThat(response.body())
                .contains("\"code\":400")
                .contains("BAD_REQUEST");
    }

    @Test
    @DisplayName("F03 正腿：缺身份 401、内层路径对外 404、白名单精确不含相邻路径")
    void f03BatchDeleteStaysClosedOutsideTheWhitelistedShape() throws Exception {
        // 缺登录：必须先于任何业务处理被拒
        assertThat(post("/api/ai/v1/conversations/batch-delete", "{\"conversationIds\":[]}", false).statusCode())
                .isEqualTo(401);

        // 内层路径直接对外：边界过滤器关成 404，不得穿透
        HttpResponse<String> internal =
                post("/internal/ai/v1/conversations/batch-delete", "{\"conversationIds\":[]}", false);
        assertThat(internal.statusCode()).isEqualTo(404);
        assertThat(internal.body()).isEqualTo(AiInternalAccessBoundaryFilter.CLOSED_BODY);

        // 白名单是**逐条**的，不是前缀通配：相邻/相似路径不得被放行
        assertThat(post("/api/ai/v1/conversations/batch-delete-extra", "{}", true).statusCode()).isEqualTo(404);
        assertThat(post("/api/ai/v1/conversations/batch-delete/all", "{}", true).statusCode()).isEqualTo(404);
        // F10-A1 起 Agent 路径同名端点也已放行（可达性判据见 LocalAgentConversationRouteDispatchTest）；
        // 这里只钉白名单仍是逐条形状：Agent 路径下的相邻/相似路径不得被前缀放行
        assertThat(post("/api/ai/v1/agent/v1/conversations/batch-delete/all", "{}", true).statusCode())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("F03 负例语义：重复 id 与超限都在集合形状层整体拒绝（不截断、不去重）")
    void f03BatchDeleteRejectsMalformedSetsWholesale() throws Exception {
        HttpResponse<String> duplicate =
                post("/api/ai/v1/conversations/batch-delete", "{\"conversationIds\":[\"c1\",\"c1\"]}", true);
        assertThat(duplicate.statusCode()).isEqualTo(400);
        assertThat(duplicate.body()).contains("BAD_REQUEST");

        StringBuilder tooMany = new StringBuilder("{\"conversationIds\":[");
        for (int i = 0; i < 101; i++) {
            tooMany.append(i > 0 ? "," : "").append("\"c").append(i).append("\"");
        }
        tooMany.append("]}");
        assertThat(post("/api/ai/v1/conversations/batch-delete", tooMany.toString(), true).statusCode())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("F03 正腿续：形状合法但无授权资源 ⇒ 停在授权步，并以 AI 侧符号码外显")
    void f03BatchDeleteReachesTheAuthorizationGateForAWellFormedSet() throws Exception {
        // 形状合法 ⇒ 越过 ①；本 fixture 未给 conversation.delete 的 ACL/事实 ⇒ 必须停在 ③，
        // 以符号码外显。这条把"可达性"与"授权闸门在路径上"分开钉住。
        HttpResponse<String> response =
                post("/api/ai/v1/conversations/batch-delete", "{\"conversationIds\":[\"conv-unregistered\"]}", true);

        assertThat(response.body())
                .as("必须带 AI 侧符号码（网关自身不会产出），证明链路已进入 F03 并且信封是整数 code")
                .contains("\"code\":" + response.statusCode());
        assertThat(response.body()).containsAnyOf("RESOURCE_NOT_FOUND_OR_FORBIDDEN", "AUTHORIZATION_UNAVAILABLE");
        assertThat(response.statusCode()).isIn(403, 404, 409, 503);
    }

    // ------------------------------------------------------------------ fixture

    @Configuration
    @EnableWebMvc
    @Import({AiGatewayController.class, AiWebEmbeddedConfiguration.class, AiEmbeddedRagConfiguration.class})
    static class FixtureConfig {

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
                    return new PlatformIdentity(TENANT, USER, MEMBER, true,
                            Set.of("ai:kb:list", "ai:kb:read", "ai:conversation:delete"), PV);
                }
            };
        }

        @Bean
        OrganizationMatchController.SubjectMatchSource subjectMatchSource() {
            return new OrganizationMatchController.SubjectMatchSource() {
                @Override
                public Set<String> currentSubjects(String tenantId, String subject, String action) {
                    // 与 RuoYiPlatformIdentitySource 同一编码：member:<canonical membershipId>
                    return Set.of("member:" + MEMBER, "tenant_all:" + TENANT);
                }

                @Override
                public boolean withinDataScope(String tenantId, String subject, String action,
                                               String ownerMember, String ownerDept) {
                    return true;
                }

                @Override
                public boolean subjectExists(String tenant, String ref) {
                    return true;
                }

                @Override
                public Optional<SubjectOrgFacts> orgFacts(String tenantId, String subject) {
                    return Optional.empty();
                }
            };
        }

        @Bean
        AiIdentityPort aiIdentityPort() {
            return () -> {
                AiIdentityPort override = identityPortOverride.get();
                if (override != null) {
                    return override.currentFacts();
                }
                return Optional.of(new AiExecutionFacts(TENANT, USER, MEMBER, PV, AV,
                        Set.of("ai:kb:list", "ai:kb:read", "ai:conversation:delete")));
            };
        }

        @Bean
        AiResourceMapper aiResourceMapper() {
            return resourceMapper;
        }

        @Bean
        AiResourceAclMapper aiResourceAclMapper() {
            return aclMapper;
        }

        @Bean
        AiAclEpochMapper aiAclEpochMapper() {
            return epochMapper;
        }

        @Bean
        ResourceSourceRefMapper resourceSourceRefMapper() {
            return sourceRefMapper;
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

        /**
         * RW-06 集成（T0）：运行配置目录装配（{@code runtimeCatalogService}）需要
         * {@code PlatformTransactionManager}，本 fixture 原先只提供 {@code TransactionOperations}，
         * 导致整个 {@code AnnotationConfigWebApplicationContext} 初始化失败 —— 表现为
         * **所有**请求 500（而不是本类想验的 200/401/404），属于"路由故障被误报成服务端故障"。
         *
         * <p>这里给一个**可执行但不做真实事务**的替身：{@code TransactionTemplate.execute}
         * 能正常跑回调（返回 {@code SimpleTransactionStatus}）。刻意不用 Mockito mock ——
         * mock 在 {@code getTransaction} 上返回 null，一旦真被调用会把"没验到"变成 NPE 500。
         */
        @Bean
        PlatformTransactionManager platformTransactionManager() {
            return new PlatformTransactionManager() {
                @Override
                public TransactionStatus getTransaction(TransactionDefinition definition) {
                    return new SimpleTransactionStatus();
                }

                @Override
                public void commit(TransactionStatus status) {
                }

                @Override
                public void rollback(TransactionStatus status) {
                }
            };
        }

        /** 同名覆盖装配内的 DefaultRevocationGuard：持久层用 mock，路由与授权链保持真实。 */
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
