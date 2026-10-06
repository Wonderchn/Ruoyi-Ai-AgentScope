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

import com.alibaba.ttl.threadpool.TtlExecutors;
import com.nageoffer.ai.ragent.framework.trace.RagStreamTraceSupport;
import com.nageoffer.ai.ragent.infra.chat.AIHubMixChatClient;
import com.nageoffer.ai.ragent.infra.chat.BaiLianChatClient;
import com.nageoffer.ai.ragent.infra.chat.ChatClient;
import com.nageoffer.ai.ragent.infra.chat.DeepSeekChatClient;
import com.nageoffer.ai.ragent.infra.chat.LlmFirstPacketProbe;
import com.nageoffer.ai.ragent.infra.chat.OllamaChatClient;
import com.nageoffer.ai.ragent.infra.chat.RoutingLLMService;
import com.nageoffer.ai.ragent.infra.chat.SiliconFlowChatClient;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.embedding.AIHubMixEmbeddingClient;
import com.nageoffer.ai.ragent.infra.embedding.BaiLianEmbeddingClient;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingClient;
import com.nageoffer.ai.ragent.infra.embedding.OllamaEmbeddingClient;
import com.nageoffer.ai.ragent.infra.embedding.RoutingEmbeddingService;
import com.nageoffer.ai.ragent.infra.embedding.SiliconFlowEmbeddingClient;
import com.nageoffer.ai.ragent.infra.model.ChatTierConfigValidator;
import com.nageoffer.ai.ragent.infra.model.ModelHealthStore;
import com.nageoffer.ai.ragent.infra.model.ModelRoutingExecutor;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import com.nageoffer.ai.ragent.infra.rerank.NoopRerankClient;
import com.nageoffer.ai.ragent.infra.rerank.RerankClient;
import com.nageoffer.ai.ragent.infra.rerank.RoutingRerankService;
import com.nageoffer.ai.ragent.infra.vlm.RoutingVlmService;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WP-030：统一模型路由与提供方装配的内嵌形态。
 *
 * <p><b>为什么需要单独一组。</b>模型路由链的编排件与服务面
 * （{@code RoutingLLMService}/{@code RoutingEmbeddingService}/{@code RoutingRerankService}/
 * {@code RoutingVlmService} 及其协作者）写在 {@code com.nageoffer.ai.ragent.infra.*} 下、
 * 由 {@code @Service}/{@code @Component} 标注，而 platform 应用的扫描根是 {@code org.ruoyi}
 * ——内嵌形态里它们<b>从来不是 bean</b>。
 * 更隐蔽的是：连基类 {@code AbstractOpenAIStyleChatClient} 用 {@code @Autowired} 需要的
 * {@code syncHttpClient}/{@code streamingHttpClient}/{@code modelStreamExecutor}/
 * {@code RagStreamTraceSupport} 也不在扫描范围内（它们由 {@code rag.config} 下的
 * {@code @Configuration} 提供）。因此"注册 ChatClient"本身就会以缺依赖启动失败。
 *
 * <p><b>本组提供什么。</b>配置绑定（{@link AIModelProperties}）、HTTP 与线程池基础设施、
 * trace 端口实现、模型选择/健康/容错执行器、5 个提供商 {@code ChatClient}、4 个嵌入客户端、
 * 重排的 noop 客户端，以及作为 F03 标题生成与真实 chat run 前置的 {@link RoutingLLMService}。
 *
 * <p><b>与既有 P2 {@code ChatGateway} 的关系（不制造第二套权威）。</b>
 * {@code RealChatGateway} 是 P2 运行链（受理→认领→执行→终态）的对话出口，固定单一
 * DeepSeek 模型且"提供方不确定绝不重试"；本组是 {@code LLMService} 面（档位/候选/健康熔断）
 * 的路由权威。两者服务不同调用方，<b>本组不替换、不包装也不改写 P2 网关</b>，
 * 因此不会出现"两套目录各自决定实际模型"的第三种状态。
 *
 * <p><b>失败语义不放松。</b>未配置提供方时 {@link ModelSelector} 会丢弃该候选并告警，
 * 候选全空时路由执行器抛显式异常，而不是静默降级到某个"默认模型"。
 * 档位结构性错误由 {@link ChatTierConfigValidator} 在启动期 fail-fast。
 *
 * <p><b>密钥。</b>API key 只从环境变量解引用（{@code ${DASHSCOPE_API_KEY:}} 等），
 * 本组不读取、不打印、不落盘任何密钥，也不为缺失密钥提供内置默认值。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiEmbeddedModelConfiguration {

    /** 本地传输下的模型路由链装配。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "ai.integration.transport", havingValue = "local")
    public static class LocalTransport {

        /**
         * 模型路由链门控：{@code ai.model.enabled}。
         *
         * <p>刻意与 {@code p2.enabled}（运行链）分开：F03 聊天标题、意图改写等
         * {@code LLMService} 调用方在运行链之外也需要路由，两者不应互相绑定。
         * 关闭时不注册任何模型 bean——调用方以缺 bean 明确失败，不做静默降级。
         */
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnProperty(name = "ai.model.enabled", havingValue = "true")
        @EnableConfigurationProperties(AIModelProperties.class)
        public static class ModelEnabled {

            // ---------------------------------------------------------- 配置权威

            /**
             * 模型配置绑定由 {@code @EnableConfigurationProperties(AIModelProperties.class)} 提供，
             * 这里刻意不再写一个 {@code @Bean AIModelProperties}：那会让容器里出现同类型的第二个
             * bean（一个来自本组、一个来自属性注册），注入点直接变成
             * {@code expected single matching bean but found 2}；用 {@code @ConditionalOnMissingBean}
             * 补救也不可靠，因为条件评估时机可能让"未绑定"的那个先成立。
             * 单一来源即可满足全部注入点。
             */

            /**
             * 档位配置启动期校验（tiers ↔ candidates 互相引用、Tier 枚举全覆盖、
             * deep 档必须有支持思考的候选）。结构性错误阻止启动，不留到请求期静默降级。
             */
            @Bean
            @ConditionalOnMissingBean
            public ChatTierConfigValidator chatTierConfigValidator(AIModelProperties properties) {
                return new ChatTierConfigValidator(properties);
            }

            // ---------------------------------------------------------- HTTP / 线程池基础设施

            /**
             * 流式 HTTP 客户端（{@code @Primary}，与 AI 侧 {@code HttpClientConfig} 语义逐字一致）：
             * 无读超时/调用超时，由档位首包预算与 {@code LlmFirstPacketProbe} 控制。
             */
            @Bean(name = "streamingHttpClient")
            @Primary
            @ConditionalOnMissingBean(name = "streamingHttpClient")
            public OkHttpClient streamingHttpClient() {
                return new OkHttpClient.Builder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .writeTimeout(Duration.ofSeconds(60))
                        .readTimeout(Duration.ZERO)
                        .callTimeout(Duration.ZERO)
                        .retryOnConnectionFailure(true)
                        .build();
            }

            /** 同步 HTTP 客户端：连接 10s / 写 30s / 读 30s / 调用 45s。 */
            @Bean(name = "syncHttpClient")
            @ConditionalOnMissingBean(name = "syncHttpClient")
            public OkHttpClient syncHttpClient() {
                return new OkHttpClient.Builder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .writeTimeout(Duration.ofSeconds(30))
                        .readTimeout(Duration.ofSeconds(30))
                        .callTimeout(Duration.ofSeconds(45))
                        .retryOnConnectionFailure(true)
                        .build();
            }

            /**
             * 模型流式输出线程池。
             *
             * <p>用 {@link TtlExecutors} 包装以保住跨线程的 trace/身份上下文——与 AI 侧
             * {@code ThreadPoolExecutorConfig.modelStreamExecutor} 的既有语义一致
             * （那里以 {@code AbortPolicy} 拒绝而不是无限排队）。
             */
            @Bean(name = "modelStreamExecutor")
            @ConditionalOnMissingBean(name = "modelStreamExecutor")
            public Executor modelStreamExecutor() {
                int cpus = Runtime.getRuntime().availableProcessors();
                ThreadPoolExecutor executor = new ThreadPoolExecutor(
                        Math.max(2, cpus >> 1),
                        Math.max(4, cpus),
                        60L,
                        TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(200),
                        runnable -> {
                            Thread thread = new Thread(runnable,
                                    "model_stream_executor_" + THREAD_SEQ.incrementAndGet());
                            thread.setDaemon(true);
                            return thread;
                        },
                        new ThreadPoolExecutor.AbortPolicy()
                );
                return TtlExecutors.getTtlExecutor(executor);
            }

            /**
             * 流式 trace 端口的内嵌实现：不落 trace 表，返回共享的空 span。
             *
             * <p>刻意的取舍：{@code RagStreamTraceSupportImpl} 依赖
             * {@code RagTraceRecordService}/{@code RagTraceProperties} 与 trace 数据表，
             * 属于运营面（WP-046）。模型路由只需要端口可注入，因此这里提供明确登记的
             * "不记录"实现——{@code RagStreamTraceSupport.NOOP_SPAN} 的契约就是所有方法 no-op，
             * 不做假记录、不写半截 RUNNING 行。
             */
            @Bean
            @ConditionalOnMissingBean
            public RagStreamTraceSupport ragStreamTraceSupport() {
                return (name, type) -> RagStreamTraceSupport.NOOP_SPAN;
            }

            // ---------------------------------------------------------- 编排

            @Bean
            @ConditionalOnMissingBean
            public ModelHealthStore modelHealthStore(AIModelProperties properties) {
                return new ModelHealthStore(properties);
            }

            @Bean
            @ConditionalOnMissingBean
            public ModelSelector modelSelector(AIModelProperties properties, ModelHealthStore healthStore) {
                return new ModelSelector(properties, healthStore);
            }

            @Bean
            @ConditionalOnMissingBean
            public ModelRoutingExecutor modelRoutingExecutor(ModelHealthStore healthStore) {
                return new ModelRoutingExecutor(healthStore);
            }

            @Bean
            @ConditionalOnMissingBean
            public LlmFirstPacketProbe llmFirstPacketProbe() {
                return new LlmFirstPacketProbe();
            }

            // ---------------------------------------------------------- 提供商客户端

            /**
             * 五个 chat 提供商客户端。
             *
             * <p><b>返回类型必须写成具体客户端类，不能写成 {@code ChatClient}。</b>
             * {@code @ConditionalOnMissingBean} 不带参数时按<b>方法返回类型</b>推断要检查的类型：
             * 若五个方法都返回 {@code ChatClient}，第一个注册成功后，其余四个都会因为
             * "容器里已经有了一个 ChatClient"而被整体跳过——结果是<b>只有 deepseek 可用</b>，
             * 目录里 bailian/ollama/aihubmix 的候选在路由期全部解析不到客户端而被静默丢弃。
             * 写具体类型后每个条件只看自己那个类，五个客户端才能共存。
             */
            @Bean
            @ConditionalOnMissingBean
            public DeepSeekChatClient deepSeekChatClient() {
                return new DeepSeekChatClient();
            }

            @Bean
            @ConditionalOnMissingBean
            public BaiLianChatClient baiLianChatClient() {
                return new BaiLianChatClient();
            }

            @Bean
            @ConditionalOnMissingBean
            public OllamaChatClient ollamaChatClient() {
                return new OllamaChatClient();
            }

            @Bean
            @ConditionalOnMissingBean
            public SiliconFlowChatClient siliconFlowChatClient() {
                return new SiliconFlowChatClient();
            }

            @Bean
            @ConditionalOnMissingBean
            public AIHubMixChatClient aiHubMixChatClient() {
                return new AIHubMixChatClient();
            }

            // ---------------------------------------------------------- 嵌入 / 重排客户端

            /**
             * 嵌入客户端（同上：返回类型必须是具体类，否则只会注册第一个）。
             *
             * <p>与 chat 客户端不同，它们用<b>构造参数</b>取 {@code OkHttpClient} 而不是
             * {@code @Autowired} 字段，因此必须在这里显式 new——否则 {@code ai.embedding}
             * 目录里登记的候选（bailian/ollama/aihubmix）在路由期全部解析不到客户端。
             * 用的是同步客户端（嵌入是短请求短响应，不该挂流式的无读超时）。
             */
            @Bean
            @ConditionalOnMissingBean
            public BaiLianEmbeddingClient baiLianEmbeddingClient(
                    @Qualifier("syncHttpClient") OkHttpClient syncHttpClient) {
                return new BaiLianEmbeddingClient(syncHttpClient);
            }

            @Bean
            @ConditionalOnMissingBean
            public OllamaEmbeddingClient ollamaEmbeddingClient(
                    @Qualifier("syncHttpClient") OkHttpClient syncHttpClient) {
                return new OllamaEmbeddingClient(syncHttpClient);
            }

            @Bean
            @ConditionalOnMissingBean
            public SiliconFlowEmbeddingClient siliconFlowEmbeddingClient(
                    @Qualifier("syncHttpClient") OkHttpClient syncHttpClient) {
                return new SiliconFlowEmbeddingClient(syncHttpClient);
            }

            @Bean
            @ConditionalOnMissingBean
            public AIHubMixEmbeddingClient aiHubMixEmbeddingClient(
                    @Qualifier("syncHttpClient") OkHttpClient syncHttpClient) {
                return new AIHubMixEmbeddingClient(syncHttpClient);
            }

            /** 重排的显式 noop 候选（{@code provider: noop}）：无重排后端时的登记项，不是静默降级。 */
            @Bean
            @ConditionalOnMissingBean
            public NoopRerankClient noopRerankClient() {
                return new NoopRerankClient();
            }

            // ---------------------------------------------------------- 对外服务面

            /**
             * 路由式 LLM 服务（{@code @Primary}）：档位选择 → 健康熔断 → 逐候选容错。
             * 它是 F03 标题生成与真实 chat run 的前置。
             */
            @Bean
            @ConditionalOnMissingBean
            @Primary
            public RoutingLLMService routingLLMService(ModelSelector selector, ModelHealthStore healthStore,
                                                       ModelRoutingExecutor executor,
                                                       LlmFirstPacketProbe firstPacketProbe,
                                                       java.util.List<ChatClient> clients) {
                return new RoutingLLMService(selector, healthStore, executor, firstPacketProbe, clients);
            }

            @Bean
            @ConditionalOnMissingBean
            @Primary
            public RoutingEmbeddingService routingEmbeddingService(ModelSelector selector,
                                                                  ModelRoutingExecutor executor,
                                                                  List<EmbeddingClient> clients) {
                return new RoutingEmbeddingService(selector, executor, clients);
            }

            @Bean
            @ConditionalOnMissingBean
            @Primary
            public RoutingRerankService routingRerankService(ModelSelector selector, ModelRoutingExecutor executor,
                                                            List<RerankClient> clients) {
                return new RoutingRerankService(selector, executor, clients);
            }

            @Bean
            @ConditionalOnMissingBean
            @Primary
            public RoutingVlmService routingVlmService(ModelSelector selector,
                                                      @Qualifier("syncHttpClient") OkHttpClient syncHttpClient) {
                return new RoutingVlmService(selector, syncHttpClient);
            }
        }
    }

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();
}
