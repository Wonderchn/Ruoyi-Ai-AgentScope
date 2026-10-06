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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.ingest.ChatGateway;
import com.nageoffer.ai.ragent.ingest.EmbeddingGateway;
import com.nageoffer.ai.ragent.ingest.PrivateObjectStore;
import com.nageoffer.ai.ragent.ingest.RealChatGateway;
import com.nageoffer.ai.ragent.ingest.SyntheticChatGateway;
import com.nageoffer.ai.ragent.ingest.SyntheticEmbeddingGateway;
import com.nageoffer.ai.ragent.runtime.P2RuntimeProperties;
import com.nageoffer.ai.ragent.runtime.RunWorker;
import com.nageoffer.ai.ragent.runtime.dao.OutboxDao;
import com.nageoffer.ai.ragent.runtime.exec.RunExecutor;
import com.nageoffer.ai.ragent.runtime.exec.RunExecutorRegistry;
import com.nageoffer.ai.ragent.runtime.exec.SyntheticRunExecutor;
import com.nageoffer.ai.ragent.runtime.stream.OutboxRelay;
import com.nageoffer.ai.ragent.runtime.usage.EgressPolicy;
import com.nageoffer.ai.ragent.runtime.usage.ProviderSpendEnvelope;
import com.nageoffer.ai.ragent.runtime.usage.UsageLedgerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ai.api.runtime.ChunkingPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;


import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * WP-031：Worker / 执行器 / outbox 链的内嵌装配判据。
 *
 * <p>这一组测试盯的是"run 永远停在 QUEUED"的三个具体成因：
 * <ol>
 *   <li>AI 侧组件在 {@code com.nageoffer.ai.ragent.*} 下，platform 扫描根是 {@code org.ruoyi}
 *       → 它们从来不是 bean（{@link #workerChainIsAbsentUnlessBothSwitchesAreOn()} 的负例钉住门控，
 *       {@link #workerChainIsAssembledWhenBothSwitchesAreOn()} 钉住真的装配）；</li>
 *   <li>{@code p2.worker.enabled} / {@code p2.outbox.relay-enabled} 的代码默认都是 {@code false}
 *       → {@link #defaultPropertiesKeepTheWorkerOff()} 用真实默认值验证"缺配置就不跑"，
 *       并证明 {@code application-embedded.yml} 必须显式打开；</li>
 *   <li>{@code @Scheduled} 需要 {@code @EnableScheduling}，此前只挂在 snail-job 上
 *       → {@link #schedulingIsEnabledForTheWorkerChain()} 用容器里真实的
 *       {@link ScheduledTaskHolder} 验证任务确实被登记（不是只看注解在不在）。</li>
 * </ol>
 */
@Tag("dev")
class AiEmbeddedWorkerConfigurationTest {

    private static final String[] EMBEDDED_LOCAL = {
            "ai.integration.enabled=true", "ai.integration.transport=local", "p2.enabled=true"};

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // worker 链依赖 run 组提供的 RunLedgerDao/RunEventAppender/NotificationBus/RunAccessService，
            // 所以两组一起装；只用 worker 组会因缺 RunAccessService 启动失败（那是真实依赖，不是测试问题）。
            // D02 之后又多了一条**同类**真实依赖：RealChatGateway 以 required 注入取 ProviderConnectionPort
            // （刻意的：引导缺失要在**启动期**响亮失败，而不是运行期静默没端点）。该端口由
            // RuntimeAuthorityConfiguration 在本模块自持的 AutoConfiguration.imports 里注册 —— 运行期会自动加载，
            // 而这里用的是显式配置清单（withUserConfiguration），**不会**自动聚合 jar 的 imports。
            // ⇒ 必须显式列出，否则本测试测的是"一个现实中不存在的半装配上下文"。
            .withUserConfiguration(AiEmbeddedRunConfiguration.class, AiEmbeddedWorkerConfiguration.class,
                    com.nageoffer.ai.ragent.runtime.config.RuntimeAuthorityConfiguration.class,
                    Fixture.class);

    @Test
    @DisplayName("p2.enabled + p2.worker.enabled 都打开时，worker/执行器/outbox 链完整装配")
    void workerChainIsAssembledWhenBothSwitchesAreOn() {
        runner.withPropertyValues(EMBEDDED_LOCAL)
                .withPropertyValues("p2.worker.enabled=true", "p2.outbox.relay-enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RunWorker.class);
                    assertThat(context).hasSingleBean(RunExecutorRegistry.class);
                    assertThat(context).hasSingleBean(OutboxRelay.class);
                    assertThat(context).hasSingleBean(UsageLedgerService.class);
                    assertThat(context).hasSingleBean(OutboxDao.class);
                    assertThat(context).hasSingleBean(EgressPolicy.class);
                    assertThat(context).hasSingleBean(ChunkingPort.class);
                    assertThat(context).hasSingleBean(ProviderSpendEnvelope.class);
                    // G-55c 锚点：屏障对账组件**必须在**。它无 @ConditionalOnBean（顺序依赖条件会在
                    // 生产上静默缺失 ⇒ 写路径 finally 只能记 ERROR ⇒ (2) 白落）；若谁给它加了条件、
                    // 或本切片又漏了基础设施，这条会红，而不是静默降级。
                    assertThat(context).hasSingleBean(com.nageoffer.ai.ragent.authorization.TenantBarrierReconciler.class);
                    // 真实模式下两个真实执行器都要在（缺它们 run 会收成 ACTION_NOT_ENABLED）
                    assertThat(context).hasBean("ragChatExecutor");
                    assertThat(context).hasBean("documentIngestExecutor");
                    assertThat(context.getBeansOfType(RunExecutor.class)).hasSizeGreaterThanOrEqualTo(2);
                });
    }

    @Test
    @DisplayName("缺少 p2.worker.enabled 时 worker 链不装配（负例：不能靠扫描意外生效）")
    void workerChainIsAbsentUnlessBothSwitchesAreOn() {
        // 只有 p2.enabled：worker 必须缺席
        runner.withPropertyValues(EMBEDDED_LOCAL).run(context -> {
            assertThat(context).hasNotFailed();
            // 负例形态下同样要有 G-55c 锚点：缺席的应该是 worker 链，**不是**屏障对账组件
            assertThat(context).hasSingleBean(com.nageoffer.ai.ragent.authorization.TenantBarrierReconciler.class);
            assertThat(context).doesNotHaveBean(RunWorker.class);
            assertThat(context).doesNotHaveBean(OutboxRelay.class);
            assertThat(context).doesNotHaveBean(RunExecutorRegistry.class);
        });
        // 只有 p2.worker.enabled 而没有 p2.enabled：同样缺席
        runner.withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=local",
                        "p2.worker.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(RunWorker.class));
        // 传输不是 local：缺席
        runner.withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=http",
                        "p2.enabled=true", "p2.worker.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(RunWorker.class));
    }

    @Test
    @DisplayName("用属性类自身的默认值验证：默认配置下 worker 是关的（所以 yml 必须显式打开）")
    void defaultPropertiesKeepTheWorkerOff() {
        P2RuntimeProperties defaults = new P2RuntimeProperties();
        assertThat(defaults.isEnabled())
                .as("p2.enabled 默认 false")
                .isFalse();
        assertThat(defaults.getWorker().isEnabled())
                .as("worker.enabled 默认 false —— 内嵌 yml 若不显式打开，run 就会停在 QUEUED")
                .isFalse();
        assertThat(defaults.getOutbox().isRelayEnabled())
                .as("outbox.relay-enabled 默认 false")
                .isFalse();
    }

    @Test
    @DisplayName("p2.executor.mode=synthetic 时注册表解析到合成执行器，且真实执行器不装配")
    void syntheticModeResolvesTheSyntheticExecutor() {
        runner.withPropertyValues(EMBEDDED_LOCAL)
                .withPropertyValues("p2.worker.enabled=true", "p2.executor.mode=synthetic",
                        "p2.chat.mode=synthetic", "p2.embedding.mode=synthetic")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SyntheticRunExecutor.class);
                    assertThat(context).doesNotHaveBean("ragChatExecutor");
                    assertThat(context).doesNotHaveBean("documentIngestExecutor");
                    assertThat(context.getBean(ChatGateway.class)).isInstanceOf(SyntheticChatGateway.class);
                    assertThat(context.getBean(EmbeddingGateway.class)).isInstanceOf(SyntheticEmbeddingGateway.class);

                    RunExecutorRegistry registry = context.getBean(RunExecutorRegistry.class);
                    assertThat(registry.isSyntheticMode()).isTrue();
                    Optional<RunExecutor> resolved = registry.resolve("chat");
                    assertThat(resolved).as("synthetic 模式下任意 action 都落到显式合成执行器").isPresent();
                    assertThat(resolved.get()).isInstanceOf(SyntheticRunExecutor.class);
                });
    }

    @Test
    @DisplayName("真实模式默认：注册表按 action 解析真实执行器，未知 action 不静默降级")
    void realModeResolvesByActionAndUnknownActionIsEmpty() {
        runner.withPropertyValues(EMBEDDED_LOCAL)
                .withPropertyValues("p2.worker.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ChatGateway.class)).isInstanceOf(RealChatGateway.class);
                    RunExecutorRegistry registry = context.getBean(RunExecutorRegistry.class);
                    assertThat(registry.isSyntheticMode()).isFalse();
                    assertThat(registry.resolve("rag.chat")).isPresent();
                    assertThat(registry.resolve("document.ingest")).isPresent();
                    assertThat(registry.resolve("no-such-action"))
                            .as("未知 action 不降级到任何执行器；RunWorker 会写成 ACTION_NOT_ENABLED 终态")
                            .isEmpty();
                });
    }

    @Test
    @DisplayName("@Scheduled 真的被登记：容器里存在调度任务，而不是只有注解")
    void schedulingIsEnabledForTheWorkerChain() {
        runner.withPropertyValues(EMBEDDED_LOCAL)
                .withPropertyValues("p2.worker.enabled=true", "p2.outbox.relay-enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ScheduledTaskHolder.class);
                    ScheduledTaskHolder holder = context.getBean(ScheduledTaskHolder.class);
                    assertThat(holder.getScheduledTasks())
                            .as("RunWorker.tick / OutboxRelay.relayOnce 必须真的成为调度任务")
                            .isNotEmpty();
                });
        // 负例：worker 关掉时，本组不应打开调度
        runner.withPropertyValues(EMBEDDED_LOCAL)
                .run(context -> assertThat(context).doesNotHaveBean(ScheduledTaskHolder.class));
    }

    @Test
    @DisplayName("p3.enabled 打开时 agent.run 执行器与受控工具面装配；关闭时该动作显式不可用")
    void agentRunExecutorFollowsTheP3Switch() {
        // 打开：agent.run 必须有执行器，否则 run 会被收成 ACTION_NOT_ENABLED
        runner.withPropertyValues(EMBEDDED_LOCAL)
                .withPropertyValues("p2.worker.enabled=true", "p3.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("agentRunExecutor");
                    assertThat(context).hasBean("agentToolService");
                    assertThat(context).hasBean("agentProviderBoundary");
                    RunExecutorRegistry registry = context.getBean(RunExecutorRegistry.class);
                    assertThat(registry.resolve("agent.run"))
                            .as("p3.enabled=true 时 agent.run 必须可解析")
                            .isPresent();
                });
        // 关闭：执行器缺席，动作落到显式终态而不是留在队列
        runner.withPropertyValues(EMBEDDED_LOCAL)
                .withPropertyValues("p2.worker.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("agentRunExecutor");
                    RunExecutorRegistry registry = context.getBean(RunExecutorRegistry.class);
                    assertThat(registry.resolve("agent.run"))
                            .as("p3.enabled 关闭时 agent.run 不可解析 → RunWorker 写 ACTION_NOT_ENABLED")
                            .isEmpty();
                });
    }

    @Test
    @DisplayName("agent.run 组与 P3 动作组的门控必须一致（否则依赖会缺 bean）")
    void agentRunGroupSharesTheSameGatesAsTheP3ActionGroup() {
        Set<String> mine = conditionProperties(AiEmbeddedWorkerConfiguration.LocalTransport.WorkerEnabled
                .AgentRunEnabled.class);
        Set<String> theirs = conditionProperties(AiEmbeddedAgentActionConfiguration.LocalTransport
                .P3Enabled.class);
        assertThat(mine)
                .as("AgentRunEnabled 依赖 P3 动作组提供的 AgentLedger/SandboxTicketClient，"
                        + "门控不一致时其中一边会以缺 bean 启动失败")
                .isEqualTo(theirs);
        assertThat(mine).contains("p3.enabled=true");
    }

    private static Set<String> conditionProperties(Class<?> type) {
        Set<String> out = new TreeSet<>();
        for (ConditionalOnProperty annotation : type.getAnnotationsByType(ConditionalOnProperty.class)) {
            out.add(String.join(",", annotation.name()) + "=" + annotation.havingValue());
        }
        return out;
    }

    /** 只为满足构造依赖；行为断言不依赖这些替身。 */
    @Configuration(proxyBeanMethods = false)
    static class Fixture {

        @Bean
        JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }

        @Bean
        PlatformTransactionManager platformTransactionManager() {
            return mock(PlatformTransactionManager.class);
        }

        /**
         * G-55c 的 {@code TenantBarrierReconciler} 需要它（构造依赖）。
         *
         * <p>为什么必须在这里补：本切片用**显式配置清单**加载
         * {@code RuntimeAuthorityConfiguration}（见 runner 的注释：显式清单不会聚合 jar 的
         * {@code AutoConfiguration.imports}），而该配置声明的 bean 需要什么基础设施，
         * 就得由切片提供什么 —— 这正是"新增 JDBC 依赖的 bean 要同步补齐它所在的切片"。
         * 行为断言不依赖这个替身。
         */
        @Bean
        org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate namedParameterJdbcTemplate() {
            return mock(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        /**
         * 用 {@code DocumentDao} 替身同时满足 {@code DocumentPort} 与 {@code DocumentDao} 两个注入点。
         * 生产里由 {@code AiEmbeddedDocumentConfiguration.P2Enabled} 注册同一个 {@code DocumentDao} bean；
         * 这里若再单独加一个 {@code DocumentPort} 替身，会出现两个候选而使注入歧义。
         */
        @Bean
        com.nageoffer.ai.ragent.ingest.DocumentDao documentDao() {
            return mock(com.nageoffer.ai.ragent.ingest.DocumentDao.class);
        }

        // 生产里这两个 bean 由 AiEmbeddedAgentActionConfiguration.P3Enabled 提供（同一组
        // p3.enabled 门控，见 agentRunGroupSharesTheSameGatesAsTheP3ActionGroup）；
        // 本测试不加载那个配置类，所以用替身满足构造依赖。
        @Bean
        com.nageoffer.ai.ragent.agent.runtime.AgentLedger agentLedger() {
            return mock(com.nageoffer.ai.ragent.agent.runtime.AgentLedger.class);
        }

        @Bean
        com.nageoffer.ai.ragent.agent.runtime.SandboxTicketClient sandboxTicketClient() {
            return mock(com.nageoffer.ai.ragent.agent.runtime.SandboxTicketClient.class);
        }

        @Bean
        com.nageoffer.ai.ragent.framework.security.RevocationGuard revocationGuard() {
            return mock(com.nageoffer.ai.ragent.framework.security.RevocationGuard.class);
        }

        @Bean
        PrivateObjectStore privateObjectStore() {
            return mock(PrivateObjectStore.class);
        }

        // 注意：不要在这里定义 runLedgerDao / runEventAppender / notificationBus / outboxDao——
        // 它们由 AiEmbeddedRunConfiguration 与本组配置提供，重复定义会以
        // BeanDefinitionOverrideException 让容器启动失败（测试替身写错，不是产品装配问题）。
        // JdbcTemplate 是 mock：Mockito 对集合返回类型默认给空集合，所以 DAO 的查询路径不会 NPE。
    }
}
