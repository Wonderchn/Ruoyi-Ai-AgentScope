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

import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.rag.controller.AgentSkillController;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNodeRegistry;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkillCacheManager;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentSkillDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentSkillMapper;
import com.nageoffer.ai.ragent.rag.service.impl.AgentSkillAdminServiceImpl;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S2-F11-A1：Skills 管理面 <b>7 条路由</b>（列表/tool-options/详情/新增/更新/删除/单条启停）
 * 经网关<b>真实可达</b>的端到端判据（红先行样本，形态照 {@code LocalFlowGraphRouteDispatchTest}／
 * {@code LocalKnowledgeAdminRouteDispatchTest}）。
 *
 * <p><b>为什么必须有这一条。</b>本切片新增 7 条白名单路由 + 四条 canonical 动作
 * （skill.list/read/write/delete → ai:skill:*，权限行 V33 沿用 V27 预留 7137-7140+7142）。
 * 静态检查（白名单覆盖、动作契约）证明不了"接得住"：路由登记了但控制器不是 bean、
 * GET 缺交付回执、信封不是整数 code（ragent {@code Result} 的字符串 {@code "0"}）——
 * 三种都会在真实请求上炸成 404/503，而源码扫描全绿（RW-22 教训："只归位不换信封 = 放行了但不可用"）。
 *
 * <p><b>为什么夹具直接登记 handler 三件而不是导入 {@code AiEmbeddedAgentSkillConfiguration}。</b>
 * 红先行要求本判据在**未接线的基线上就能编译并运行**（那时新装配类还不存在），
 * 故 {@link FixtureConfig} 直接 {@code @Import} {@link AgentSkillController} ＋
 * {@link AgentSkillAdminServiceImpl} ＋ {@link AgentSkillCacheManager}（都已在基线存在），
 * 协作者用替身；装配面（门控/@Import）由 {@code AiEmbeddedAgentSkillActivationTest} 另行钉住。
 * 基线运行预期：7 条正例路由在网关即 404（白名单未登记），负例（401/404/内层 404）绿。
 *
 * <p><b>动作映射钉子（本切片的关键负例）</b>：
 * <ul>
 *   <li>tool-options 归 {@code skill.list}（不是 read）——只持 {@code ai:skill:read} 读不到选项；</li>
 *   <li>启停与创建/更新同归 {@code skill.write}；删除独立 {@code skill.delete}（write 不隐含 delete）；</li>
 *   <li>四条动作全部要求**平台管理身份**第二道门（P4 默认=进；只持 scope 的租户成员 403）。</li>
 * </ul>
 *
 * <p><b>负例</b>：白名单外形状 404、内层前缀对外 404、未登录 401。
 */
@Tag("dev")
class LocalAgentSkillRouteDispatchTest {

    private static final String TENANT = "T1";
    private static final String USER = "2101";
    private static final String MEMBER = "platform:T1:2101";
    private static final int PV = 7;
    private static final int AV = 3;

    private static final String SKILL_ID = "sk-1";

    /** 四个技能权限（V33 注册）：读面 = skill.list/skill.read，写面 = skill.write/skill.delete。 */
    private static final Set<String> ALL_PERMISSIONS = Set.of(
            "ai:skill:list", "ai:skill:read", "ai:skill:write", "ai:skill:delete");

    /** 请求期权限集合：网关与内层都读它，故"缺 scope"负例改的是真的判定输入。 */
    private static final AtomicReference<Set<String>> CURRENT_PERMISSIONS =
            new AtomicReference<>(ALL_PERMISSIONS);

    /** 平台管理身份第二道门（P4：skill.* 进 PLATFORM_ADMIN_ACTIONS）；默认 true 走正例。 */
    private static final AtomicBoolean PLATFORM_ADMIN = new AtomicBoolean(true);

    private static Tomcat tomcat;
    private static int port;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static final AgentSkillMapper skillMapper = mock(AgentSkillMapper.class);
    private static final IntentNodeRegistry intentRegistry = mock(IntentNodeRegistry.class);
    private static final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private static final RevocationGuard revocations = mock(RevocationGuard.class);
    private static final List<String> permits = new ArrayList<>();
    private static final List<String> acknowledgements = new ArrayList<>();

    @BeforeAll
    static void startContainer() throws Exception {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.register(FixtureConfig.class);
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "fixture", Map.of("ai.integration.enabled", "true",
                        "ai.integration.transport", "local", "p2.enabled", "true")));

        tomcat = new Tomcat();
        tomcat.setBaseDir("target/local-agent-skill-route-tomcat");
        tomcat.setPort(0);
        File docBase = new File("target/local-agent-skill-route-docroot");
        docBase.mkdirs();
        org.apache.catalina.Context ctx = tomcat.addContext("", docBase.getAbsolutePath());

        // 与生产装配同形：内层前缀直接访问一律 404（关闭体）
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
        reset(skillMapper, intentRegistry, revocations);
        permits.clear();
        acknowledgements.clear();
        CURRENT_PERMISSIONS.set(ALL_PERMISSIONS);
        PLATFORM_ADMIN.set(true);

        when(revocations.enter(any(), anyString(), anyString())).thenAnswer(invocation -> {
            permits.add(invocation.getArgument(1) + "/" + invocation.getArgument(2));
            return new RevocationGuard.Operation(revocations,
                    java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString());
        });

        doAnswer(invocation -> {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<AgentSkillDO> page =
                    invocation.getArgument(0);
            page.setRecords(List.of(skillRow()));
            return page;
        }).when(skillMapper).selectPage(any(), any());
        when(skillMapper.selectById(anyString())).thenReturn(skillRow());
        when(skillMapper.selectList(any())).thenReturn(List.of(skillRow()));
        when(skillMapper.exists(any())).thenReturn(false);
        when(skillMapper.insert(any(AgentSkillDO.class))).thenAnswer(invocation -> {
            AgentSkillDO record = invocation.getArgument(0);
            record.setId("sk-created");
            return 1;
        });
        when(skillMapper.updateById(any(AgentSkillDO.class))).thenReturn(1);
        when(skillMapper.deleteById(anyString())).thenReturn(1);
        when(intentRegistry.listMcpToolNodes()).thenReturn(List.of(mcpNode()));

        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    /** 列表/详情替身行：name/content 即断言标记（读路径返回的唯一来源）。 */
    private static AgentSkillDO skillRow() {
        return AgentSkillDO.builder()
                .id(SKILL_ID)
                .skillCode("f11_skill_row")
                .name("F11 冒烟手册（行标记）")
                .description("仅在显式演示要求时使用")
                .content("已加载本冒烟手册；不解锁任何工具。")
                .toolIds(List.of())
                .sortOrder(0)
                .enabled(1)
                .build();
    }

    private static IntentNode mcpNode() {
        return IntentNode.builder()
                .id("node-mcp-1")
                .mcpToolId("demo_tool_alpha")
                .name("演示工具")
                .description("意图树上的演示 MCP 节点")
                .build();
    }

    // ------------------------------------------------------------------ 正例：7 条逐条可达

    @Test
    @DisplayName("7 条技能路由逐条经网关 200：列表/tool-options/详情/新增/更新/删除/启停")
    void allSevenSkillRoutesAreReachableThroughTheGateway() throws Exception {
        assertJsonOk("GET", "/api/ai/v1/agent-catalog/agent-skills?current=1&size=10", null, "f11_skill_row");
        assertJsonOk("GET", "/api/ai/v1/agent-catalog/agent-skills/tool-options", null, "demo_tool_alpha");
        assertJsonOk("GET", "/api/ai/v1/agent-catalog/agent-skills/" + SKILL_ID, null, "已加载本冒烟手册");
        assertJsonOk("POST", "/api/ai/v1/agent-catalog/agent-skills",
                "{\"skillCode\":\"f11_new\",\"name\":\"新技能\",\"description\":\"d\",\"content\":\"c\"}", "sk-created");
        assertJsonOk("PUT", "/api/ai/v1/agent-catalog/agent-skills/" + SKILL_ID,
                "{\"name\":\"改名\"}", null);
        assertJsonOk("DELETE", "/api/ai/v1/agent-catalog/agent-skills/" + SKILL_ID, null, null);
        assertJsonOk("POST", "/api/ai/v1/agent-catalog/agent-skills/" + SKILL_ID + "/enabled?enabled=false",
                null, null);
    }

    @Test
    @DisplayName("GET 走字节分支由控制器自铸回执并本地 ACK；写面零 permit 零 ACK")
    void skillReadsMintDeliveryReceiptsAndWritesMintNone() throws Exception {
        assertJsonOk("GET", "/api/ai/v1/agent-catalog/agent-skills?current=1&size=10", null, null);
        assertJsonOk("GET", "/api/ai/v1/agent-catalog/agent-skills/tool-options", null, null);
        assertJsonOk("GET", "/api/ai/v1/agent-catalog/agent-skills/" + SKILL_ID, null, null);

        assertThat(permits)
                .as("每条 200 的 GET 恰铸一次许可，动作=skill.list/skill.read，引用=控制器自铸形状")
                .containsExactlyInAnyOrder(
                        "skill.list/agent-catalog:skill:list",
                        "skill.list/agent-catalog:skill:tool-options",
                        "skill.read/agent-catalog:skill:" + SKILL_ID);
        awaitAcknowledgements(permits.size());
        assertThat(acknowledgements)
                .as("字节分支的每个 200 必须恰有一次本地回执（缺则内层没铸头，多则许可泄漏）")
                .hasSize(permits.size());

        int before = permits.size();
        assertJsonOk("POST", "/api/ai/v1/agent-catalog/agent-skills",
                "{\"skillCode\":\"f11_new\",\"name\":\"n\",\"description\":\"d\",\"content\":\"c\"}", null);
        assertJsonOk("PUT", "/api/ai/v1/agent-catalog/agent-skills/" + SKILL_ID, "{\"name\":\"n2\"}", null);
        assertJsonOk("DELETE", "/api/ai/v1/agent-catalog/agent-skills/" + SKILL_ID, null, null);
        assertJsonOk("POST", "/api/ai/v1/agent-catalog/agent-skills/" + SKILL_ID + "/enabled?enabled=true",
                null, null);
        assertThat(permits)
                .as("写面走 JSON 分支：网关不会为它释放许可，内层因此不得铸造（否则逐请求泄漏 ACTIVE 许可）")
                .hasSize(before);
    }

    // ------------------------------------------------------------------ 负例：动作映射

    @Test
    @DisplayName("读面拆分：tool-options 归 skill.list；只持 ai:skill:read 读不到列表/选项，反之读不了详情")
    void readSurfaceMapsToListAndReadExactly() throws Exception {
        CURRENT_PERMISSIONS.set(Set.of("ai:skill:read"));
        try {
            assertThat(get("/api/ai/v1/agent-catalog/agent-skills?current=1&size=10").statusCode())
                    .as("列表要 skill.list，不是 read").isEqualTo(403);
            assertThat(get("/api/ai/v1/agent-catalog/agent-skills/tool-options").statusCode())
                    .as("tool-options 归 skill.list（Spec P3），不是 read").isEqualTo(403);
            assertThat(get("/api/ai/v1/agent-catalog/agent-skills/" + SKILL_ID).statusCode())
                    .as("详情要 skill.read").isEqualTo(200);
        } finally {
            CURRENT_PERMISSIONS.set(ALL_PERMISSIONS);
        }

        CURRENT_PERMISSIONS.set(Set.of("ai:skill:list"));
        try {
            assertThat(get("/api/ai/v1/agent-catalog/agent-skills?current=1&size=10").statusCode()).isEqualTo(200);
            assertThat(get("/api/ai/v1/agent-catalog/agent-skills/tool-options").statusCode()).isEqualTo(200);
            assertThat(get("/api/ai/v1/agent-catalog/agent-skills/" + SKILL_ID).statusCode())
                    .as("list 不扩大到 read").isEqualTo(403);
        } finally {
            CURRENT_PERMISSIONS.set(ALL_PERMISSIONS);
        }
    }

    @Test
    @DisplayName("写面拆分：写=skill.write（建/改/启停）、删=skill.delete，互不隐含；未列入的动词一律 403")
    void writeSurfaceMapsToWriteAndDeleteExactly() throws Exception {
        CURRENT_PERMISSIONS.set(Set.of("ai:skill:write"));
        try {
            assertThat(postJson("/api/ai/v1/agent-catalog/agent-skills",
                    "{\"skillCode\":\"f11_x\",\"name\":\"n\",\"description\":\"d\",\"content\":\"c\"}").statusCode())
                    .isEqualTo(200);
            assertThat(putJson("/api/ai/v1/agent-catalog/agent-skills/" + SKILL_ID, "{\"name\":\"n2\"}").statusCode())
                    .isEqualTo(200);
            assertThat(postJson("/api/ai/v1/agent-catalog/agent-skills/" + SKILL_ID + "/enabled?enabled=false",
                    null).statusCode())
                    .isEqualTo(200);
            assertThat(delete("/api/ai/v1/agent-catalog/agent-skills/" + SKILL_ID).statusCode())
                    .as("write 不隐含 delete").isEqualTo(403);
        } finally {
            CURRENT_PERMISSIONS.set(ALL_PERMISSIONS);
        }

        CURRENT_PERMISSIONS.set(Set.of("ai:skill:delete"));
        try {
            assertThat(delete("/api/ai/v1/agent-catalog/agent-skills/" + SKILL_ID).statusCode()).isEqualTo(200);
            assertThat(postJson("/api/ai/v1/agent-catalog/agent-skills",
                    "{\"skillCode\":\"f11_x\",\"name\":\"n\",\"description\":\"d\",\"content\":\"c\"}").statusCode())
                    .as("delete 不扩大到 write").isEqualTo(403);
        } finally {
            CURRENT_PERMISSIONS.set(ALL_PERMISSIONS);
        }
    }

    @Test
    @DisplayName("平台管理身份第二道门（P4）：只持 scope 的租户成员 403，且不触碰内层")
    void skillActionsRequirePlatformAdminSecondGate() throws Exception {
        PLATFORM_ADMIN.set(false);

        assertThat(get("/api/ai/v1/agent-catalog/agent-skills?current=1&size=10").statusCode())
                .as("四条 skill.* 动作全部在 PLATFORM_ADMIN_ACTIONS 里：scope 齐备也必须被第二道门拒绝")
                .isEqualTo(403);
        assertThat(delete("/api/ai/v1/agent-catalog/agent-skills/" + SKILL_ID).statusCode())
                .isEqualTo(403);
        verify(skillMapper, never()).selectPage(any(), any());
        verify(skillMapper, never()).deleteById(anyString());

        PLATFORM_ADMIN.set(true);
        assertThat(get("/api/ai/v1/agent-catalog/agent-skills?current=1&size=10").statusCode())
                .as("平台管理身份 + scope ⇒ 放行").isEqualTo(200);
    }

    @Test
    @DisplayName("白名单外形状 404、内层前缀对外 404、未登录 401")
    void unregisteredShapesAndInternalPrefixStayHidden() throws Exception {
        assertThat(get("/api/ai/v1/agent-catalog/agent-skills/" + SKILL_ID + "/extra").statusCode())
                .as("未登记的 4 段形状不匹配任何白名单条目").isEqualTo(404);
        assertThat(postJson("/api/ai/v1/agent-catalog/agent-skills/" + SKILL_ID, "{}").statusCode())
                .as("POST 未登记 3 段形状（只有 PUT/DELETE 在该形状）").isEqualTo(404);
        assertThat(get("/api/ai/v1/agent-catalog/agent-skills/tool-options/extra").statusCode())
                .isEqualTo(404);

        HttpResponse<String> anonymous = CLIENT.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/ai/v1/agent-catalog/agent-skills"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).as("未登录 → 401（不是 404）").isEqualTo(401);

        HttpResponse<String> internal = get("/internal/ai/v1/agent-catalog/agent-skills");
        assertThat(internal.statusCode()).as("内层前缀不是对外新增面").isEqualTo(404);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 等回执收敛到期望条数（≤3s）：回执在响应提交之后才发生，真实 HTTP 下客户端可能先返回。
     * 超时即由调用方的 {@code hasSize} 断言给出确定失败（本方法不吞错）。
     */
    private static void awaitAcknowledgements(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (acknowledgements.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    /**
     * JSON 面判据：200 + 整数 {@code "code":200} + 非 {@code Result} 的字符串 code +
     * （可选）独有标记。
     */
    private static void assertJsonOk(String method, String path, String body, String marker) throws Exception {
        HttpResponse<String> response = send(method, path, body, "application/json");
        assertThat(response.statusCode())
                .as("%s %s：404=未登记/503=内层不命中或缺回执/403=scope 或平台管理门，都不是通过；实际体=%s",
                        method, path, response.body())
                .isEqualTo(200);
        assertThat(response.body())
                .as("%s %s：必须是整数 code 的信封", method, path)
                .contains("\"code\":200")
                .doesNotContain("\"code\":\"0\"");
        if (marker != null) {
            assertThat(response.body())
                    .as("%s %s：独有标记只可能来自该请求/该替身", method, path)
                    .contains(marker);
        }
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return send("GET", path, null, null);
    }

    private static HttpResponse<String> postJson(String path, String body) throws Exception {
        return send("POST", path, body, "application/json");
    }

    private static HttpResponse<String> putJson(String path, String body) throws Exception {
        return send("PUT", path, body, "application/json");
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

    // ------------------------------------------------------------------ fixture

    @Configuration
    @EnableWebMvc
    @Import({AiGatewayController.class, AiWebEmbeddedConfiguration.class,
            AgentSkillController.class, AgentSkillAdminServiceImpl.class,
            AgentSkillCacheManager.class, BizChangeLogContext.class})
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
            return new CurrentPrincipalResolver() {
                @Override
                public Optional<CurrentMember> resolveCurrentMember() {
                    return Optional.of(new CurrentMember(TENANT, USER, MEMBER));
                }

                @Override
                public boolean isPlatformAdmin() {
                    return PLATFORM_ADMIN.get();
                }
            };
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

        @Bean
        AgentSkillMapper agentSkillMapper() {
            return skillMapper;
        }

        @Bean
        IntentNodeRegistry intentNodeRegistry() {
            return intentRegistry;
        }

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return redis;
        }
    }
}
