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
import com.nageoffer.ai.ragent.ingest.MarkdownChunker;
import com.nageoffer.ai.ragent.ingest.PrivateObjectStore;
import com.nageoffer.ai.ragent.ingest.RealChatGateway;
import com.nageoffer.ai.ragent.ingest.RealEmbeddingGateway;
import com.nageoffer.ai.ragent.ingest.SyntheticChatGateway;
import com.nageoffer.ai.ragent.ingest.SyntheticEmbeddingGateway;
import com.nageoffer.ai.ragent.runtime.P2FaultInjector;
import com.nageoffer.ai.ragent.runtime.P2RuntimeProperties;
import com.nageoffer.ai.ragent.runtime.RunEventAppender;
import com.nageoffer.ai.ragent.runtime.RunWorker;
import com.nageoffer.ai.ragent.runtime.config.RuntimeModelGatewayPort;
import com.nageoffer.ai.ragent.runtime.dao.OutboxDao;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.exec.DocumentIngestExecutor;
import com.nageoffer.ai.ragent.runtime.exec.RagChatExecutor;
import com.nageoffer.ai.ragent.runtime.exec.RunExecutor;
import com.nageoffer.ai.ragent.runtime.exec.RunExecutorRegistry;
import com.nageoffer.ai.ragent.runtime.exec.SyntheticRunExecutor;
import com.nageoffer.ai.ragent.runtime.stream.NotificationBus;
import com.nageoffer.ai.ragent.runtime.stream.OutboxRelay;
import com.nageoffer.ai.ragent.runtime.usage.EgressPolicy;
import com.nageoffer.ai.ragent.runtime.usage.ProviderSpendEnvelope;
import com.nageoffer.ai.ragent.runtime.usage.UsageLedgerService;
import org.ruoyi.ai.api.runtime.ChunkingPort;
import org.ruoyi.ai.api.runtime.DocumentPort;
import org.ruoyi.ai.api.runtime.MinerUPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.List;

/**
 * WP-031：Worker / 执行器 / outbox 投递链的内嵌装配。
 *
 * <p><b>为什么需要单独一组。</b>AI 侧这些组件都写在 {@code com.nageoffer.ai.ragent.*} 下并带
 * {@code @Component}，而 platform 应用的扫描根是 {@code org.ruoyi}——所以它们在内嵌形态里
 * <b>从来不是 bean</b>。叠加两个默认值，run 会永久停在 {@code QUEUED}：
 * <ul>
 *   <li>{@code P2RuntimeProperties.Worker.enabled} 与 {@code Outbox.relayEnabled} 的代码默认都是
 *       {@code false}，而 {@code application-embedded.yml} 里此前没有打开；</li>
 *   <li>{@code @Scheduled}（{@code RunWorker.tick} / {@code OutboxRelay.relayOnce}）需要
 *       {@code @EnableScheduling}，此前只有 {@code SnailJobConfig} 在
 *       {@code snail-job.enabled=true} 时打开——内嵌形态并不依赖 snail-job。</li>
 * </ul>
 * 本组把这三件事一起修掉，并沿用既有内嵌装配的写法（{@code @AutoConfiguration} +
 * {@code transport=local} + 功能开关嵌套 {@code @Configuration} + {@code @ConditionalOnMissingBean}）。
 *
 * <p><b>失败语义不放松。</b>没有对应 action 的执行器时，{@code RunWorker} 会把 run 收成
 * {@code FAILED / ACTION_NOT_ENABLED} 的<b>显式终态</b>，而不是留在队列里假装还在跑。
 * 未配置提供方（空 API key、spend 未启用）同样是显式拒绝，不做静默降级。
 *
 * <p>真实提供方（{@code RealChatGateway} / {@code RealEmbeddingGateway}）只在对应 mode 下注册，
 * 密钥仍走环境变量引用；本组不读取、不打印任何密钥。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiEmbeddedWorkerConfiguration {

    /** 本地传输下的 worker 链装配。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "ai.integration.transport", havingValue = "local")
    public static class LocalTransport {

        /**
         * Worker 链门控：{@code p2.enabled} + {@code p2.worker.enabled}。
         *
         * <p>{@code @EnableScheduling} 放在这里而不是全局：只有真正要跑 worker/relay 时才打开调度，
         * 不改变 platform 其它模块的既有调度姿态（{@code snail-job} 仍是它自己的开关）。
         */
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
        @ConditionalOnProperty(name = "p2.worker.enabled", havingValue = "true")
        @EnableConfigurationProperties(P2RuntimeProperties.class)
        @EnableScheduling
        public static class WorkerEnabled {

            // ---------------------------------------------------------- 协作件

            @Bean
            @ConditionalOnMissingBean
            public EgressPolicy egressPolicy() {
                return new EgressPolicy();
            }

            @Bean
            @ConditionalOnMissingBean
            public UsageLedgerService usageLedgerService(JdbcTemplate jdbc) {
                return new UsageLedgerService(jdbc);
            }

            @Bean
            @ConditionalOnMissingBean
            public OutboxDao outboxDao(JdbcTemplate jdbc) {
                return new OutboxDao(jdbc);
            }

            /**
             * 进程内通知总线作为**一个传输实现**参与 outbox 投递（C12.2）。
             *
             * <p>这样 {@code OutboxRelay} 的投递语义是一句话："所有已注册传输都必须成功"，
             * 而不是"调总线 + 也许还有别的东西"。将来 T3 追加 RocketMQ 传输时，
             * 两个实现会**同时**被投递（MQ 是附加不是替代），无需再改 relay 或本方法。
             *
             * <p>返回类型写成具体类：{@code @ConditionalOnMissingBean} 不带参数时按方法返回类型
             * 推断要检查的类型，写成接口会让第二个实现被整体跳过（见本仓已登记的坑）。
             */
            @Bean
            @ConditionalOnMissingBean
            public com.nageoffer.ai.ragent.runtime.stream.NotificationBusTransport
            notificationBusTransport(NotificationBus notificationBus) {
                return new com.nageoffer.ai.ragent.runtime.stream.NotificationBusTransport(notificationBus);
            }

            /**
             * 真实提供方调用前的边界检查。
             *
             * <p>{@code RealChatGateway} / {@code RealEmbeddingGateway} 用 {@code @Autowired}
             * （required）注入它，所以它必须和真实网关一起装配；它自己依赖 run 组的
             * {@link com.nageoffer.ai.ragent.runtime.RunAccessService}。
             */
            @Bean
            @ConditionalOnMissingBean
            public com.nageoffer.ai.ragent.runtime.exec.ProviderCallBoundary providerCallBoundary(
                    com.nageoffer.ai.ragent.runtime.RunAccessService runAccessService,
                    ObjectProvider<com.nageoffer.ai.ragent.framework.security.RevocationGuard> revocationGuards) {
                return new com.nageoffer.ai.ragent.runtime.exec.ProviderCallBoundary(runAccessService, revocationGuards);
            }

            @Bean
            @ConditionalOnMissingBean
            public ProviderSpendEnvelope providerSpendEnvelope(
                    JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                    @Value("${p2.providers.spend.enabled:false}") boolean enabled,
                    @Value("${p2.providers.spend.execution-id:}") String executionId,
                    @Value("${p2.providers.spend.cap-cny:20}") BigDecimal cap) {
                // 默认 enabled=false：reserve() 会以 BUDGET_EXCEEDED 显式拒绝，而不是放过未授权花费。
                return new ProviderSpendEnvelope(jdbc, transactionManager, enabled, executionId, cap);
            }

            @Bean
            @ConditionalOnMissingBean
            public ChunkingPort chunkingPort() {
                return new MarkdownChunker();
            }

            @Bean
            @ConditionalOnMissingBean
            @ConditionalOnProperty(name = "p2.embedding.mode", havingValue = "real", matchIfMissing = true)
            public EmbeddingGateway realEmbeddingGateway(
                    EgressPolicy egressPolicy,
                    @Value("${p2.providers.dashscope.api-key:${DASHSCOPE_API_KEY:}}") String apiKey) {
                return new RealEmbeddingGateway(apiKey, egressPolicy);
            }

            @Bean
            @ConditionalOnMissingBean
            @ConditionalOnProperty(name = "p2.embedding.mode", havingValue = "synthetic")
            public EmbeddingGateway syntheticEmbeddingGateway(
                    @Value("${rag.default.dimension:1536}") int dimension) {
                return new SyntheticEmbeddingGateway(dimension);
            }

            @Bean
            @ConditionalOnMissingBean
            @ConditionalOnProperty(name = "p2.chat.mode", havingValue = "real", matchIfMissing = true)
            public ChatGateway realChatGateway(
                    EgressPolicy egressPolicy,
                    @Value("${p2.providers.deepseek.api-key:${DEEPSEEK_API_KEY:}}") String apiKey) {
                return new RealChatGateway(apiKey, egressPolicy);
            }

            @Bean
            @ConditionalOnMissingBean
            @ConditionalOnProperty(name = "p2.chat.mode", havingValue = "synthetic")
            public ChatGateway syntheticChatGateway(
                    @Value("${p2.chat.synthetic-delta-delay-ms:0}") long deltaDelayMs) {
                return new SyntheticChatGateway(deltaDelayMs);
            }

            // ---------------------------------------------------------- 执行器

            /**
             * G-41 收口后的消费点（2026-10-06 与 T2m 端口语义对齐后改）：执行器只依赖
             * {@link RuntimeModelGatewayPort} 窄门（run 作用域三方法），宽 {@code ChatGateway}
             * 的无身份方法不在执行链可见面上。
             *
             * <p>委托链：窄门 bean（{@code RuntimeAuthorityConfiguration#runtimeModelGatewayPort}，
             * 经 {@code ObjectProvider<RunScopedChatPort>} 延迟解析）→ {@code ChatGateway}
             * （它 extends {@code RunScopedChatPort}）→ 本组按 {@code p2.chat.mode} 互斥注册的
             * real/synthetic 网关。因此上面两个 {@code ChatGateway} bean 保留不动——
             * 它们不再是执行器的直接依赖，而是委托链的源头；{@code p2.chat.mode} 配成非法值
             * （两个条件都不命中）时，委托在使用期抛 {@code ConfigAuthorityUnavailable}
             * （fail-closed，D02 响亮失败），装配期不静默。
             */
            @Bean
            @ConditionalOnMissingBean
            @ConditionalOnProperty(name = "p2.executor.mode", havingValue = "real", matchIfMissing = true)
            public RagChatExecutor ragChatExecutor(
                    DocumentPort documentDao, EmbeddingGateway embeddingGateway,
                    RuntimeModelGatewayPort chatGateway,
                    EgressPolicy egressPolicy, UsageLedgerService usageLedger,
                    ObjectProvider<com.nageoffer.ai.ragent.runtime.usage.PlatformFactsClient> platformFacts,
                    ObjectProvider<com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService> authorization,
                    ObjectProvider<P2FaultInjector> faultInjector, JdbcTemplate jdbc, ObjectMapper objectMapper) {
                return new RagChatExecutor(documentDao, embeddingGateway, chatGateway, egressPolicy, usageLedger,
                        platformFacts, authorization, faultInjector, jdbc, objectMapper);
            }

            @Bean
            @ConditionalOnMissingBean
            @ConditionalOnProperty(name = "p2.executor.mode", havingValue = "real", matchIfMissing = true)
            public DocumentIngestExecutor documentIngestExecutor(
                    DocumentPort documentDao, PrivateObjectStore objectStore,
                    ObjectProvider<MinerUPort> mineru, ChunkingPort chunker, EmbeddingGateway embeddingGateway,
                    UsageLedgerService usageLedger, ObjectProvider<P2FaultInjector> faultInjector,
                    ObjectMapper objectMapper) {
                return new DocumentIngestExecutor(documentDao, objectStore, mineru, chunker, embeddingGateway,
                        usageLedger, faultInjector, objectMapper);
            }

            /**
             * 专属测试模式：{@code p2.executor.mode=synthetic}。
             *
             * <p>它与真实执行器互斥（{@link RunExecutorRegistry} 在 synthetic 模式下只认这一个），
             * 用于在<b>没有真实提供方</b>的情况下验证受理→认领→执行→终态→事件投递整条链。
             * 合成结果不得当作真实模型签收。
             */
            @Bean
            @ConditionalOnMissingBean
            @ConditionalOnProperty(name = "p2.executor.mode", havingValue = "synthetic")
            public SyntheticRunExecutor syntheticRunExecutor(ObjectProvider<P2FaultInjector> faultInjector) {
                return new SyntheticRunExecutor(faultInjector);
            }

            // ---------------------------------------------------------- 注册表与 worker

            @Bean
            @ConditionalOnMissingBean
            public RunExecutorRegistry runExecutorRegistry(List<RunExecutor> executors,
                                                           ObjectProvider<SyntheticRunExecutor> synthetic,
                                                           P2RuntimeProperties properties) {
                return new RunExecutorRegistry(executors, synthetic, properties);
            }

            @Bean
            @ConditionalOnMissingBean
            public RunWorker runWorker(RunLedgerDao runLedgerDao, RunEventAppender runEventAppender,
                                       RunExecutorRegistry runExecutorRegistry, P2RuntimeProperties properties,
                                       ObjectProvider<P2FaultInjector> faultInjector) {
                return new RunWorker(runLedgerDao, runEventAppender, runExecutorRegistry, properties, faultInjector);
            }

            /**
             * outbox relay。
             *
             * <p>依赖从 {@code NotificationBus} 换成 {@code List<OutboxTransport>}（C12.2）：
             * 投递必须覆盖**全部**已注册传输（进程内总线 + 将来的 RocketMQ），
             * 任一失败即整条留在 PENDING 走退避 —— 不允许"总线成功就标记 PUBLISHED"
             * 那种跨实例静默丢消息的形态。至少有一个传输（{@code NotificationBusTransport}）
             * 由本组保证，所以这个 List 不会为空。
             */
            @Bean
            @ConditionalOnMissingBean
            public OutboxRelay outboxRelay(OutboxDao outboxDao,
                                           java.util.List<com.nageoffer.ai.ragent.runtime.stream.OutboxTransport> transports,
                                           P2RuntimeProperties properties,
                                           ObjectProvider<P2FaultInjector> faultInjector) {
                return new OutboxRelay(outboxDao, transports, properties, faultInjector);
            }

            /**
             * WP-033A：{@code agent.run} 动作的执行器与它的受控工具面。
             *
             * <p>放在 worker 组里是因为它复用本组的 {@link ChatGateway} 与 {@link UsageLedgerService}；
             * 再叠加 {@code p3.enabled} 门控，因为 Agent 的工具调用要走 P3 的动作账本/审批/沙箱票据。
             * 两者任一关闭时 {@code agent.run} 会由 {@link RunWorker} 收成
             * {@code FAILED / ACTION_NOT_ENABLED} 的显式终态——不是留在队列里。
             *
             * <p>{@link com.nageoffer.ai.ragent.agent.runtime.AgentRunExecutor} 的
             * {@code executionVersion} 绑定 {@code p3-core-v1}：版本不符时它自己返回
             * {@code AGENT_CHECKPOINT_INCOMPATIBLE}，不做旧检查点的静默降级。
             */
            @Configuration(proxyBeanMethods = false)
            @ConditionalOnProperty(name = "p3.enabled", havingValue = "true")
            @EnableConfigurationProperties(com.nageoffer.ai.ragent.agent.runtime.P3Properties.class)
            public static class AgentRunEnabled {

                @Bean
                @ConditionalOnMissingBean
                public com.nageoffer.ai.ragent.agent.runtime.AgentProviderBoundary agentProviderBoundary(
                        com.nageoffer.ai.ragent.runtime.RunAccessService runAccessService,
                        com.nageoffer.ai.ragent.framework.security.RevocationGuard revocationGuard,
                        EgressPolicy egressPolicy) {
                    return new com.nageoffer.ai.ragent.agent.runtime.AgentProviderBoundary(
                            runAccessService, revocationGuard, egressPolicy);
                }

                @Bean
                @ConditionalOnMissingBean
                public com.nageoffer.ai.ragent.agent.runtime.AgentToolService agentToolService(
                        JdbcTemplate jdbc, com.nageoffer.ai.ragent.agent.runtime.AgentLedger agentLedger,
                        com.nageoffer.ai.ragent.runtime.RunAccessService runAccessService,
                        com.nageoffer.ai.ragent.ingest.DocumentDao documentDao, EmbeddingGateway embeddingGateway,
                        UsageLedgerService usageLedger,
                        com.nageoffer.ai.ragent.agent.runtime.SandboxTicketClient sandboxTicketClient,
                        com.nageoffer.ai.ragent.framework.security.RevocationGuard revocationGuard,
                        ObjectProvider<P2FaultInjector> faultInjector,
                        com.nageoffer.ai.ragent.agent.runtime.AgentProviderBoundary agentProviderBoundary) {
                    return new com.nageoffer.ai.ragent.agent.runtime.AgentToolService(jdbc, agentLedger,
                            runAccessService, documentDao, embeddingGateway, usageLedger, sandboxTicketClient,
                            revocationGuard, faultInjector, agentProviderBoundary);
                }

                @Bean
                @ConditionalOnMissingBean
                public com.nageoffer.ai.ragent.agent.runtime.AgentRunExecutor agentRunExecutor(
                        com.nageoffer.ai.ragent.agent.runtime.AgentLedger agentLedger,
                        com.nageoffer.ai.ragent.runtime.RunAccessService runAccessService,
                        com.nageoffer.ai.ragent.agent.runtime.AgentToolService agentToolService,
                        ChatGateway chatGateway, UsageLedgerService usageLedger,
                        com.nageoffer.ai.ragent.agent.runtime.P3Properties properties, JdbcTemplate jdbc,
                        com.nageoffer.ai.ragent.agent.runtime.AgentProviderBoundary agentProviderBoundary) {
                    return new com.nageoffer.ai.ragent.agent.runtime.AgentRunExecutor(agentLedger, runAccessService,
                            agentToolService, chatGateway, usageLedger, properties, jdbc, agentProviderBoundary);
                }
            }
        }
    }
}
