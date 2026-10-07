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

import com.nageoffer.ai.ragent.agent.controller.AgentConversationController;
import com.nageoffer.ai.ragent.agent.controller.vo.AgentConversationVO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentConversationMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentStateMapper;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.agent.service.ConversationBatchDeleteService;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunGate;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP-033B：Agent 会话面内嵌装配与身份桥的判据。
 *
 * <p>这一组盯的是三件事：
 * <ol>
 *   <li><b>为什么不能直接注册既有控制器</b>：AI 侧 {@code AgentConversationController} 用
 *       {@code UserContext.getUserId()}，而内嵌进程没有任何组件设置 {@code UserContext}
 *       （{@link #aiSideControllerStillReadsTheUnboundUserContext()} 用反射把这条事实钉在源码上，
 *       将来 AI 侧改用 PrincipalContext 时该判据会失败并提醒我们收敛）；</li>
 *   <li><b>身份必须来自 PrincipalContext 且缺身份要拒绝</b>
 *       （{@link #surfaceReadsIdentityFromPrincipalContextAndDelegates()}、
 *       {@link #surfaceRefusesWithoutAnExecutionPrincipal()}）；</li>
 *   <li><b>路由契约不得与 AI 侧漂移</b>
 *       （{@link #surfaceExposesExactlyTheAiSideConversationCrudRoutes()} 逐条对照两侧注解）。</li>
 * </ol>
 *
 * <p>另有一条负例钉住"在途流闸门缺 Redis 时不装配"：
 * {@link #runGateIsAbsentWithoutRedisson()}。该闸门只在删除路径被调用一次且 fail-closed，
 * 本机无 Redis 时删除会以缺依赖明确失败，而不是"查不到在途流就放行"。
 */
@Tag("dev")
class AiEmbeddedAgentConversationConfigurationTest {

    private static final String[] EMBEDDED_LOCAL = {
            "ai.integration.enabled=true", "ai.integration.transport=local"};

    private static final String[] ENABLED = {
            "ai.integration.enabled=true", "ai.integration.transport=local",
            "agent.conversation.enabled=true"};

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiEmbeddedAgentConversationConfiguration.class, Fixture.class);

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    // ------------------------------------------------------------------ 装配与门控

    @Test
    @DisplayName("实际 embedded YAML 启用会话面，防止已接线但发货配置关闭")
    void shippedEmbeddedProfileEnablesConversationSurface() throws IOException {
        withShippedProfile().withBean(RedissonClient.class, () -> mock(RedissonClient.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getProperty("agent.conversation.enabled", Boolean.class))
                            .isTrue();
                    assertThat(context).hasSingleBean(AgentConversationService.class);
                    assertThat(context).hasSingleBean(AgentRunGate.class);
                    assertThat(context).hasSingleBean(
                            AiEmbeddedAgentConversationConfiguration.LocalTransport.ConversationEnabled.ConversationSurface.class);
                });
    }

    @Test
    @EnabledIfSystemProperty(named = "ragent.agent-gate.test.redis-address", matches = ".+",
            disabledReason = "requires an owned isolated Redis endpoint")
    @DisplayName("实际 profile 与真实 Redis 保护运行位：跨客户端互斥、会话匹配和安全释放")
    void shippedProfileProtectsActiveConversationWithRealRedis() throws IOException {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("ragent.agent-gate.test.redis-address"))
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(2);
        RedissonClient first = Redisson.create(config);
        RedissonClient second = Redisson.create(config);
        String userId = "maintainer-test-" + UUID.randomUUID();
        try {
            withShippedProfile().withBean(RedissonClient.class, () -> first)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        AgentRunGate gate = context.getBean(AgentRunGate.class);
                        AgentRunGate peer = new AgentRunGate(second,
                                context.getBean(com.nageoffer.ai.ragent.agent.config.AgentProperties.class));
                        Runnable release = gate.acquire(userId, "task-a", "conv-a");
                        try {
                            assertThat(peer.runningTaskId(userId, "conv-a")).isEqualTo("task-a");
                            assertThat(peer.runningTaskId(userId, "conv-b")).isNull();
                            assertThatThrownBy(() -> peer.acquire(userId, "task-b", "conv-b"))
                                    .isInstanceOf(ClientException.class);
                        } finally {
                            release.run();
                        }
                        Runnable nextRelease = peer.acquire(userId, "task-b", "conv-b");
                        try {
                            release.run();
                            assertThat(gate.runningTaskId(userId, "conv-b")).isEqualTo("task-b");
                        } finally {
                            nextRelease.run();
                        }
                        assertThat(gate.runningTaskId(userId, "conv-b")).isNull();
                    });
        } finally {
            // Only this test's exact unique key is eligible for cleanup.
            if (!second.isShutdown()) {
                second.getBucket("ragent:agent:running:" + userId).delete();
            }
            first.shutdown();
            second.shutdown();
        }
    }

    private ApplicationContextRunner withShippedProfile() throws IOException {
        Path profile = Path.of("..", "..", "ruoyi-admin", "src", "main", "resources", "application-embedded.yml")
                .toAbsolutePath().normalize();
        var sources = new YamlPropertySourceLoader().load("shipped-embedded", new FileSystemResource(profile));
        return runner.withInitializer(context -> sources.forEach(source ->
                context.getEnvironment().getPropertySources().addLast(source)));
    }

    @Test
    @DisplayName("agent.conversation.enabled=true 且 Redisson 就位时会话服务与公开面装配")
    void conversationSurfaceIsAssembledWhenEnabled() {
        runner.withPropertyValues(ENABLED).withBean(org.redisson.api.RedissonClient.class,
                        () -> mock(org.redisson.api.RedissonClient.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AgentConversationService.class);
                    assertThat(context).hasSingleBean(
                            AiEmbeddedAgentConversationConfiguration.LocalTransport.ConversationEnabled.ConversationSurface.class);
                    assertThat(context).hasSingleBean(
                            com.nageoffer.ai.ragent.agent.state.PgAgentStateStore.class);
                });
    }

    @Test
    @DisplayName("缺门控 / 非 local 传输 / 缺集成开关时会话面都不装配")
    void conversationSurfaceIsAbsentUnlessGatedOn() {
        // 只有集成开关，没有 agent.conversation.enabled
        runner.withPropertyValues(EMBEDDED_LOCAL).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AgentConversationService.class);
            assertThat(context).doesNotHaveBean(
                    AiEmbeddedAgentConversationConfiguration.LocalTransport.ConversationEnabled.ConversationSurface.class);
        });
        // 非 local 传输
        runner.withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=http",
                        "agent.conversation.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(AgentConversationService.class));
        // 集成总开关关闭
        runner.withPropertyValues("ai.integration.transport=local", "agent.conversation.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(AgentConversationService.class));
    }

    @Test
    @DisplayName("缺 RedissonClient 时容器响亮失败，而不是让删除绕过在途流保护")
    void assemblyFailsLoudlyWithoutRedisson() {
        runner.withPropertyValues(ENABLED).run(context -> {
            assertThat(context)
                    .as("闸门依赖 Redisson；没有它就必须以缺依赖启动失败——"
                            + "替代方案（'查不到在途流就放行'的降级替身）正是这个闸门要防的缺陷")
                    .hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
        });
    }

    @Test
    @DisplayName("容器里有 RedissonClient 时会话面与闸门都装配")
    void assemblySucceedsWithRedisson() {
        runner.withPropertyValues(ENABLED).withBean(org.redisson.api.RedissonClient.class,
                        () -> mock(org.redisson.api.RedissonClient.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AgentRunGate.class);
                    assertThat(context).hasSingleBean(AgentConversationService.class);
                    assertThat(context).hasSingleBean(
                            AiEmbeddedAgentConversationConfiguration.LocalTransport.ConversationEnabled.ConversationSurface.class);
                });
    }

    // ------------------------------------------------------------------ 身份桥（本包的核心）

    @Test
    @DisplayName("公开面从 PrincipalContext 取身份并把业务委托既有服务")
    void surfaceReadsIdentityFromPrincipalContextAndDelegates() {
        // 公开面的职责边界是"路由 + 身份 + 委托"：这里用替身服务把这条边界单独钉住。
        // 真服务的业务前置（会话归属、在途流闸门）由它自己的用例覆盖，
        // 混在一起会让本判据变成"mock 数据够不够全"的测试。
        AgentConversationService service = mock(AgentConversationService.class);
        when(service.listByUserId("2101")).thenReturn(List.of());
        RevocationGuard guard = mock(RevocationGuard.class);
        when(guard.enter(any(), anyString(), anyString())).thenAnswer(invocation ->
                new RevocationGuard.Operation(guard, java.util.UUID.randomUUID().toString(),
                        java.util.UUID.randomUUID().toString()));
        var surface = new AiEmbeddedAgentConversationConfiguration.LocalTransport.ConversationEnabled
                .ConversationSurface(service, guardProvider(guard),
                        new ConversationBatchDeleteService(service, guardProvider(guard)));

        PrincipalContext.set(principal("T1", "2101"));
        assertThat(surface.listConversations().getBody().data()).isEmpty();
        verify(service).listByUserId("2101");

        surface.listMessages("conv-1");
        verify(service).listMessages("conv-1", "2101");

        surface.rename("conv-1",
                new AiEmbeddedAgentConversationConfiguration.LocalTransport.ConversationEnabled
                        .ConversationSurface.TitleRequest("新标题"));
        verify(service).rename("conv-1", "2101", "新标题");

        surface.delete("conv-1");
        verify(service).delete("conv-1", "2101");

        // 身份确实来自 PrincipalContext：换一个主体，传给服务的 userId 必须跟着变
        PrincipalContext.set(principal("T1", "2102"));
        surface.listConversations();
        verify(service).listByUserId("2102");
    }

    /**
     * WP-034：读路径必须真的登记交付许可（网关对 GET 的硬要求）。
     *
     * <p>没有这一条，{@code listConversations} 的响应头断言就只是"调了某个方法"——
     * 而交付证明缺失在网关侧会被收敛成 503，是这条公开面此前不可达的第三层原因。
     */
    @Test
    @DisplayName("读路径为每次请求登记许可，并把标识放进响应头")
    void readPathsRegisterADeliveryPermitPerRequest() {
        AgentConversationService service = mock(AgentConversationService.class);
        when(service.listByUserId("2101")).thenReturn(List.of());
        RevocationGuard guard = mock(RevocationGuard.class);
        when(guard.enter(any(), anyString(), anyString())).thenAnswer(invocation ->
                new RevocationGuard.Operation(guard, "permit-fixed", "operation-fixed"));
        var surface = new AiEmbeddedAgentConversationConfiguration.LocalTransport.ConversationEnabled
                .ConversationSurface(service, guardProvider(guard),
                        new ConversationBatchDeleteService(service, guardProvider(guard)));

        PrincipalContext.set(principal("T1", "2101"));
        var response = surface.listConversations();

        assertThat(response.getHeaders().getFirst("X-AI-Delivery-Permit")).isEqualTo("permit-fixed");
        assertThat(response.getHeaders().getFirst("X-AI-Delivery-Operation")).isEqualTo("operation-fixed");
        // 动作与资源引用必须与读路径一致：动作错会让 permit 与授权事实对不上
        verify(guard).enter(any(), eq("conversation.read"), eq("tenant:conversations"));
        // 包络必须是整数 code（AI 侧旧 Result 的字符串 "0" 会被网关判为缺 code）
        assertThat(response.getBody().code()).isEqualTo(200);
    }

    /**
     * WP-034：读路径的 permit 释放时机必须保住撤权屏障语义。
     *
     * <p>成功路径**不**能提前 close：permit 要在"字节真的交付给客户端"之前保持 ACTIVE，
     * 那正是撤权屏障等待的窗口；释放由网关的交付回执负责。提前释放会让并发撤权越过
     * 仍在写出的响应——屏障形同取消。
     *
     * <p>失败路径则必须 close：否则异常会留下一个永不释放的 ACTIVE permit，
     * 反过来把租户的屏障永久卡在 PENDING。
     */
    @Test
    @DisplayName("读路径：成功不提前释放 permit，失败必须释放")
    void readPathsReleaseThePermitOnlyOnFailure() {
        AgentConversationService service = mock(AgentConversationService.class);
        when(service.listByUserId("2101")).thenReturn(List.of());
        RevocationGuard guard = mock(RevocationGuard.class);
        when(guard.enter(any(), anyString(), anyString())).thenAnswer(invocation ->
                new RevocationGuard.Operation(guard, "permit-1", "operation-1"));
        var surface = new AiEmbeddedAgentConversationConfiguration.LocalTransport.ConversationEnabled
                .ConversationSurface(service, guardProvider(guard),
                        new ConversationBatchDeleteService(service, guardProvider(guard)));

        PrincipalContext.set(principal("T1", "2101"));
        surface.listConversations();
        verify(guard, never())
                .release(anyString(), anyString());

        // 业务抛错时反向锚点：必须释放，且释放的是本次登记的 operation
        when(service.listByUserId("2101")).thenThrow(new IllegalStateException("业务失败"));
        assertThatThrownBy(surface::listConversations).isInstanceOf(IllegalStateException.class);
        verify(guard).release("permit-1", "operation-1");
    }

    @Test
    @DisplayName("无执行主体时拒绝，而不是退化成\"没有用户限定\"的查询")
    void surfaceRefusesWithoutAnExecutionPrincipal() {
        runner.withPropertyValues(ENABLED).withBean(org.redisson.api.RedissonClient.class,
                        () -> mock(org.redisson.api.RedissonClient.class))
                .run(context -> {
            assertThat(context).hasNotFailed();
            AgentConversationService service = context.getBean(AgentConversationService.class);
            var surface = new AiEmbeddedAgentConversationConfiguration.LocalTransport.ConversationEnabled.ConversationSurface(
                    service, context.getBeanProvider(RevocationGuard.class),
                    new ConversationBatchDeleteService(service, context.getBeanProvider(RevocationGuard.class)));

            PrincipalContext.clear();
            assertThatThrownBy(surface::listConversations)
                    .as("userId 为 null 会让查询退化成无用户限定，必须在入口拒绝")
                    .isInstanceOf(ClientException.class)
                    .hasMessageContaining("缺少执行主体");
            assertThatThrownBy(() -> surface.listMessages("conv-1"))
                    .isInstanceOf(ClientException.class);
            assertThatThrownBy(() -> surface.rename("conv-1", null))
                    .isInstanceOf(ClientException.class);
            assertThatThrownBy(() -> surface.delete("conv-1"))
                    .isInstanceOf(ClientException.class);
        });
    }

    /** 极简 ObjectProvider：只表达"许可执行侧在不在"。 */
    private static org.springframework.beans.factory.ObjectProvider<RevocationGuard> guardProvider(
            RevocationGuard guard) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public RevocationGuard getObject() {
                return guard;
            }

            @Override
            public RevocationGuard getObject(Object... args) {
                return guard;
            }

            @Override
            public RevocationGuard getIfAvailable() {
                return guard;
            }

            @Override
            public RevocationGuard getIfUnique() {
                return guard;
            }
        };
    }

    @Test
    @DisplayName("AI 侧控制器仍然读未绑定的 UserContext（钉住本包必须自建公开面的事实）")
    void aiSideControllerStillReadsTheUnboundUserContext() throws Exception {
        java.nio.file.Path controller = locateAgentConversationControllerSource();
        String source = java.nio.file.Files.readString(controller);
        assertThat(source)
                .as("AI 侧会话控制器用 UserContext.getUserId()；内嵌进程没有任何组件设置 UserContext"
                        + "（全仓 UserContext.set( 只在独立 AI 的拦截器/MQ/调度器与测试里），"
                        + "所以直接注册它会让每个请求拿到 null userId。"
                        + "若将来 AI 侧改用 PrincipalContext，本判据会失败——那时应改为直接复用既有控制器。")
                .contains("UserContext.getUserId()")
                .doesNotContain("PrincipalContext");
    }

    /**
     * 从模块目录向上找到仓库根，再定位 AI 侧控制器源码。
     *
     * <p>写死相对层数会在模块搬迁时静默指错文件（本用例第一版就是这样拿到 NoSuchFile 的），
     * 所以按目录名逐级向上找 {@code services/platform}。
     */
    private static java.nio.file.Path locateAgentConversationControllerSource() {
        java.nio.file.Path cursor = java.nio.file.Path.of("").toAbsolutePath();
        while (cursor != null) {
            java.nio.file.Path candidate = cursor.resolve(java.nio.file.Path.of("services", "platform",
                    "ruoyi-modules", "ruoyi-ai-agent", "src", "main", "java", "com", "nageoffer", "ai",
                    "ragent", "agent", "controller", "AgentConversationController.java"));
            if (java.nio.file.Files.exists(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("从 " + java.nio.file.Path.of("").toAbsolutePath()
                + " 向上未找到 AgentConversationController.java —— 目录结构变了，本判据需要同步调整");
    }

    // ------------------------------------------------------------------ 路由契约一致性

    @Test
    @DisplayName("公开面暴露的会话 CRUD 路由与 AI 侧逐条一致（防契约漂移），且都落在内部前缀下")
    void surfaceExposesExactlyTheAiSideConversationCrudRoutes() {
        Set<String> mine = routesOf(
                AiEmbeddedAgentConversationConfiguration.LocalTransport.ConversationEnabled.ConversationSurface.class);
        Set<String> theirs = routesOf(AgentConversationController.class);

        // WP-035/C4：本包现在**交付** batch-delete 的服务端契约（集合形状校验 + 整体授权 +
        // 单事务 + 逐资源 permit + epoch 复核，判据见 ConversationBatchDeleteServiceTest）。
        // 因此这里不再剔除它 —— 两侧集合应当逐条相同（只差内部前缀）。
        Set<String> expectedMine = new TreeSet<>(theirs);

        // WP-034：内层路由必须带内部前缀，否则网关转送（目标恒为 /internal/ai/v1 + 子路径）必然 miss。
        // 把期望集合整体加前缀，同时保留"逐条与 AI 侧一致"的防漂移语义。
        Set<String> prefixed = new TreeSet<>();
        for (String route : expectedMine) {
            int split = route.indexOf(' ');
            prefixed.add(route.substring(0, split + 1)
                    + AiEmbeddedAgentConversationConfiguration.LocalTransport.ConversationEnabled
                            .ConversationSurface.INTERNAL_PREFIX
                    + route.substring(split + 1));
        }

        assertThat(mine)
                .as("两侧路由集合必须完全相同（只差内部前缀）")
                .isEqualTo(prefixed);
        assertThat(mine)
                .as("batch-delete 的服务端契约已交付，内层必须有这条 handler")
                .anyMatch(route -> route.contains("batch-delete"));
        assertThat(mine)
                .as("每一条都必须落在内部前缀下——否则网关到不了（WP-034 的根因）")
                .allMatch(route -> route.contains(
                        AiEmbeddedAgentConversationConfiguration.LocalTransport.ConversationEnabled
                                .ConversationSurface.INTERNAL_PREFIX + "/agent/v1/"));
    }

    /**
     * C4/D05：批量删除的**公开路径必须仍然 404**。
     *
     * <p>判据从"内层没有这条 handler"改成了"**白名单里没有这条路由**"，因为这才是真正的不变量：
     * 本轮交付的是**服务端契约与负例**，而"是否开放给客户端"是一个独立决定（C4 明文：先完成
     * 服务端契约和负例，再开放批量 UI）。旧判据把两件事绑在一起 —— 一旦交付内层契约它就会红，
     * 而它红的原因并不是"公开面被提前放开了"。现在改为直接读网关白名单（反射
     * {@code AiGatewayController.ROUTES}，与 {@code LocalWhitelistHandlerCoverageTest} 同法），
     * 于是"有人提前放行"会立刻失败，而"交付内层契约"不会误报。
     */
    @Test
    @DisplayName("C4/D05：F03 批量删除已按 D05 逐条放行；Agent 侧仍保持未放行")
    void batchDeleteReachabilityMatchesTheD05Decision() throws Exception {
        java.lang.reflect.Field field = org.ruoyi.aiintegration.web.AiGatewayController.class
                .getDeclaredField("ROUTES");
        field.setAccessible(true);
        java.util.List<?> routes = (java.util.List<?>) field.get(null);

        java.util.List<String> patterns = new java.util.ArrayList<>();
        for (Object route : routes) {
            Method accessor = route.getClass().getDeclaredMethod("pattern");
            accessor.setAccessible(true);
            patterns.add(String.valueOf(accessor.invoke(route)));
        }

        assertThat(patterns)
                .as("锚点：必须真的读到网关白名单，否则本判据会退化成恒真")
                .isNotEmpty()
                .contains("/agent/v1/conversations");
        // RW-01（T0 集成，2026-10-07）：C4 的前置"先完成服务端契约和负例"已由 RW-01 交付，
        // D05 已作出批量授权决定，故 F03 的普通会话批量删除必须已逐条放行。
        assertThat(patterns)
                .as("F03 批量删除必须已逐条放行（D05 已决定，服务端契约与负例已交付）")
                .contains("/conversations/batch-delete");
        assertThat(patterns)
                .as("F03 批量删除不得用通配替代逐条登记")
                .noneMatch(pattern -> pattern.contains("/conversations/**"));
        // Agent 侧维持不放行：F10 的批量面本轮不改可达性（RW-01 有意未申请）。
        assertThat(patterns)
                .as("Agent 侧批量删除仍不放行：内层有契约 ≠ 公开可达，F10 另卡决定")
                .noneMatch(pattern -> pattern.contains("/agent/v1/conversations/batch-delete"));
    }

    /** 从控制器方法注解里抽出 `METHOD path` 形式的集合。 */
    private static Set<String> routesOf(Class<?> controller) {
        Set<String> routes = new TreeSet<>();
        for (Method method : controller.getDeclaredMethods()) {
            GetMapping get = method.getAnnotation(GetMapping.class);
            if (get != null) {
                for (String value : get.value()) {
                    routes.add("GET " + value);
                }
            }
            PutMapping put = method.getAnnotation(PutMapping.class);
            if (put != null) {
                for (String value : put.value()) {
                    routes.add("PUT " + value);
                }
            }
            DeleteMapping delete = method.getAnnotation(DeleteMapping.class);
            if (delete != null) {
                for (String value : delete.value()) {
                    routes.add("DELETE " + value);
                }
            }
            org.springframework.web.bind.annotation.PostMapping post =
                    method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class);
            if (post != null) {
                for (String value : post.value()) {
                    routes.add("POST " + value);
                }
            }
        }
        return routes;
    }

    private static ExecutionPrincipal principal(String tenantId, String userId) {
        // scopes 必须包含被测动作：缺 scope 时公开面会先因越权拒绝（那是另一条判据）。
        return new ExecutionPrincipal(tenantId, userId,
                ExecutionPrincipal.canonicalMembershipId(tenantId, userId), 1, 1,
                Set.of("conversation.read", "conversation.rename", "conversation.delete"),
                "jti-1", "platform:local", 0L, 0L);
    }

    /** 只为满足构造依赖；行为断言不依赖这些替身。 */
    @Configuration(proxyBeanMethods = false)
    static class Fixture {

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
        // 刻意不在这里定义 AgentConversationService 替身：那会因为
        // @ConditionalOnMissingBean 把产品侧的真实 bean 整个顶掉，
        // 于是"装配判据"变成在验证替身。委托断言改用真实 bean 的 spy（见上）。
    }
}
