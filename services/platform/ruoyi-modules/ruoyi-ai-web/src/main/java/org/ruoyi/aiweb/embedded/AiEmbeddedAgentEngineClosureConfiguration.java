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

import cn.hutool.core.thread.ThreadFactoryBuilder;
import cn.hutool.core.util.StrUtil;
import com.alibaba.ttl.threadpool.TtlExecutors;
import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.agent.config.ReActAgentProvider;
import com.nageoffer.ai.ragent.agent.confirm.AgentConfirmDenialMiddleware;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryControlMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryExtractionMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.agent.identity.AgentRunScopeRegistry;
import com.nageoffer.ai.ragent.agent.memory.AgentContextCompactionMiddleware;
import com.nageoffer.ai.ragent.agent.memory.AgentContextCompactor;
import com.nageoffer.ai.ragent.agent.memory.AgentContextTrimmer;
import com.nageoffer.ai.ragent.agent.memory.AgentConversationSummarizer;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryConsolidator;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryJudge;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryPipeline;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryProperties;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryRepository;
import com.nageoffer.ai.ragent.agent.memory.AgentUserMemoryMiddleware;
import com.nageoffer.ai.ragent.agent.service.AgentChatService;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunGate;
import com.nageoffer.ai.ragent.agent.service.impl.AgentChatServiceImpl;
import com.nageoffer.ai.ragent.agent.skill.AgentSkillMaskingMiddleware;
import com.nageoffer.ai.ragent.agent.state.PgAgentStateStore;
import com.nageoffer.ai.ragent.agent.tool.AgentMcpClients;
import com.nageoffer.ai.ragent.agent.tool.AgentMcpProperties;
import com.nageoffer.ai.ragent.agent.tool.AgentToolBatchMiddleware;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog;
import com.nageoffer.ai.ragent.agent.trace.AgentTraceEnrichmentMiddleware;
import com.nageoffer.ai.ragent.framework.integration.SaasCapabilityBoundary;
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.knowledge.service.impl.ChunkMetadataResolver;
import com.nageoffer.ai.ragent.rag.config.GuidanceProperties;
import com.nageoffer.ai.ragent.rag.config.OrchestrationProperties;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.guidance.AmbiguityLLMChecker;
import com.nageoffer.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.nageoffer.ai.ragent.rag.core.intent.DefaultIntentClassifier;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNodeRegistry;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.intent.IntentTreeCacheManager;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.DefaultContextFormatter;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.core.retrieval.MultiChannelRetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.KbCollectionProvider;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.RetrievalScopeResolver;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.VectorSearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.WebSearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieval.postprocessor.DeduplicationPostProcessor;
import com.nageoffer.ai.ragent.rag.core.retrieval.postprocessor.EvidenceGatePostProcessor;
import com.nageoffer.ai.ragent.rag.core.retrieval.postprocessor.FusionPostProcessor;
import com.nageoffer.ai.ragent.rag.core.retrieval.postprocessor.MetadataEnrichmentPostProcessor;
import com.nageoffer.ai.ragent.rag.core.retrieval.postprocessor.RerankPostProcessor;
import com.nageoffer.ai.ragent.rag.core.rewrite.MultiQuestionRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryTermMappingService;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkillCacheManager;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkillRegistry;
import com.nageoffer.ai.ragent.rag.core.source.CitationContextEnricher;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentSkillMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import com.nageoffer.ai.ragent.rag.service.KnowledgeSearchFacade;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * S1 引擎最小闭包：{@code ragent.engine.type=agent} 门后的 46–50 个注册点（A1–A5 四组）。
 *
 * <p><b>为什么单独一组而不是继续堆进 {@code AiEmbeddedAgentEngineConfiguration}。</b>
 * 装配清单与 {@code EngineChainReadiness} 的"点名清单"是两件事：前者是<b>怎么装</b>，
 * 后者是<b>缺了怎么响亮失败</b>。把 48 个注册点塞进引擎配置会让同一文件同时承载
 * 门控/公开面/点名/装配四层，点名清单一漂移就没人看得住。本组把装配面独立出来，
 * 由 {@code AiEmbeddedAgentEngineConfiguration.EngineEnabled} 在<b>引擎门内</b>
 * {@code @Import} 挂入 —— 门语义与既有三重门逐字一致
 * （{@code ai.integration.enabled=true} × {@code transport=local} ×
 * {@code ragent.engine.type=agent}），本类自身也重复这三个条件：
 * platform 的组件扫描会独立发现 {@code org.ruoyi} 下的顶层配置类，
 * "条件在 enclosing 配置上"护不住这一路径（{@code ConditionalOnEmbeddedLocal} 的注释记录过同一教训）。
 *
 * <p><b>装配方式是显式列举，不是包扫描。</b>这些类都写在小写包 {@code com.nageoffer.ai.ragent.*}
 * 下并带 {@code @Component}/{@code @Service}，而 platform 扫描根是 {@code org.ruoyi}
 * ——内嵌形态里它们从来不是 bean。本模块族的既定纪律是不做根包扫描、由内嵌配置显式列举
 * （{@code AiEmbeddedAgentCatalogConfiguration} 记录过同名教训）；且包边界不可控：
 * 扫 {@code rag.core.*} 会顺带拉起记忆/图谱等本闭包不要的件。清单的逐项依据见
 * {@code .scratch/s2-s3-startup/ENGINE-CLOSURE-AUDIT.md} §3.2（A1 8 / A2 7 / A3 4 / A4 22 / A5 4）。
 *
 * <p><b>单实例由 {@code @ConditionalOnMissingBean} 收敛，不靠"应该没人重复"。</b>
 * 11 类中 {@code AgentPromptResolver} 由 F09 目录面（{@code AiEmbeddedAgentCatalogConfiguration}）
 * 提供；{@code PgAgentStateStore}/{@code AgentRunGate} 由引擎配置与会话配置"同名同型双声明、一侧
 * {@code @ConditionalOnMissingBean} 退让"的既有机制收敛。这里对 11 类里的每个"根"同样
 * 用 {@code @ConditionalOnMissingBean} 注册，评审口径与 {@code AiEmbeddedAgentDualDeclarationCoexistenceTest}
 * 一致：谁在场谁赢、两侧行为等价；不在场就响亮失败（readiness 点名），<b>绝不为通过启动而加替身</b>。
 *
 * <p><b>{@code OpenAIChatModel} 刻意在本组复刻 {@code @Bean}（而非 {@code @Import(AgentEngineConfiguration.class)}）。</b>
 * {@code AgentEngineConfiguration} 会同时带来 {@code agentStateStore}（无缺省条件），
 * 与引擎配置的 {@code pgAgentStateStore}（{@code @ConditionalOnMissingBean}）形成<b>顺序敏感</b>的
 * 二重声明——装载先后一变就可能是"两个同型 bean"。本组只复刻 {@code agentChatModel}
 * 一个 {@code @Bean}（构造逻辑与 {@code AgentEngineConfiguration:44-89} 逐字一致，含 DeepSeek
 * formatter），连带条件也一并复刻，避免该顺序面被引入。
 *
 * <p><b>阈值与边界（本批故意不装，缺了不报错的那一侧不属本闭包）</b>：
 * <ul>
 *   <li>ES / Milvus / LightRAG / WebSearch 真链：{@code rag.vector.type=pg}、
 *       {@code rag.keyword.type=none}、{@code rag.graph.type} 未设 ⇒ 非向量通道由条件注解天然排除；
 *       {@code WebSearchChannel} <b>在场但默认关</b>（{@code rag.search.channels.web-search.enabled}
 *       缺省 false 且 key 环境变量为空即空转），与"真链未接通"的边界登记一致；</li>
 *   <li>OTel/Langfuse trace 中间件：{@code ReActAgentProvider} 用 {@code ObjectProvider} 取，
 *       缺席合法（F18 op4 维持 NOT_RUN）；</li>
 *   <li>F11 管理面（技能 CRUD/导入导出/绑定/市场）与 {@code AgentSkillAdminServiceImpl}：
 *       本批只装<b>读端</b> registry + 缓存（最小解锁），管理面维持 BLOCKED；</li>
 *   <li>{@code AgentDashboardService/Reader}：跨租户缓存键缺陷未修，继续不装配；</li>
 *   <li>P2/P3 的 {@code agent.run} 执行器族与 rag 会话记忆线
 *       （{@code memoryLoadExecutor}/{@code memorySummaryExecutor} 等）：与 AgentScope ReAct 面
 *       是两条独立链，不在本闭包；</li>
 *   <li><b>egress（R2 登记 → ENGINE-CHAT-ACCOUNTING 切片收敛，2026-10-11）</b>：
 *       {@code OpenAIChatModel} 的外发经 {@link MeteredEngineModelTransport} 套入
 *       {@code EgressPolicy}+{@code ProviderSpendEnvelope}（与 p2 RealChatGateway 同口径；
 *       发送前校验/预占、逐行嗅探 usage 结算；拒绝即不出站）。原"不经封套"边界由本切片
 *       按复核建议收敛；精确 usage 的全链口径（引擎请求是否开 include_usage）单列。
 *       <b>配置矩阵（复核 f3132d9e 登记）</b>：计量信封的 bean 注册点在 p2 工作组
 *       （{@code p2.enabled} + {@code p2.worker.enabled} 双门）——"引擎开而工作组关"
 *       的组合将在启动期以 agentChatModel 缺依赖**响亮失败**（fail-fast：引擎面与
 *       p2 工作组同进退）；如需解耦另批。</li>
 * </ul>
 *
 * <p><b>线程池四件按名注册（R1）</b>：镜像 {@code com.nageoffer...rag.config.ThreadPoolExecutorConfig}
 * 的四个池（同名同型、尺寸/队列/拒绝策略/TTL 包装逐项一致），让内嵌形态不必引入整个线程池配置类
 * （那会连带注册 6 个与本链无关的池，含属会话记忆线的两个）。四个消费者
 * （{@code RetrievalEngine}/{@code MultiChannelRetrievalEngine}/{@code VectorSearchChannel}/
 * {@code IntentResolver}）已同批改为构造参数 {@code @Qualifier} 按名注入——platform 侧
 * {@code ThreadPoolConfig} 有两个 {@code @Primary} 的 {@code Executor}，按类型注入会以
 * {@code NoUniqueBeanDefinitionException}（多个 primary）启动期失败。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
@ConditionalOnEmbeddedLocal
@ConditionalOnProperty(name = "ragent.engine.type", havingValue = "agent")
@EnableConfigurationProperties({GuidanceProperties.class, SearchChannelProperties.class})
@Import({
        // ---------------------------------------------------------- A2 记忆子闭包（7）
        AgentMemoryJudge.class,
        AgentMemoryConsolidator.class,
        AgentUserMemoryMiddleware.class,
        AgentContextTrimmer.class,
        AgentConversationSummarizer.class,
        AgentContextCompactor.class,
        AgentContextCompactionMiddleware.class,

        // ---------------------------------------------------------- A3 技能/工具中间件（4，其中 3 个登记点）
        AgentSkillCacheManager.class,
        AgentSkillMaskingMiddleware.class,
        AgentToolBatchMiddleware.class,
        AgentConfirmDenialMiddleware.class,

        // ---------------------------------------------------------- A4 检索链闭包（简单构造件）
        MultiQuestionRewriteService.class,
        QueryTermMappingService.class,
        IntentResolver.class,
        IntentGuidanceService.class,
        AmbiguityLLMChecker.class,
        RetrievalEngine.class,
        MultiChannelRetrievalEngine.class,
        CitationContextEnricher.class,
        RAGPromptService.class,
        PromptTemplateLoader.class,
        DefaultContextFormatter.class,
        RetrievalScopeResolver.class,
        KbCollectionProvider.class,
        VectorSearchChannel.class,
        WebSearchChannel.class,
        DeduplicationPostProcessor.class,
        EvidenceGatePostProcessor.class,
        FusionPostProcessor.class,
        MetadataEnrichmentPostProcessor.class,
        RerankPostProcessor.class,
        ChunkMetadataResolver.class,
        // 属性类自足（@Configuration + 字段级 @Value 带默认值）：@Import 即可
        RAGConfigProperties.class
})
public class AiEmbeddedAgentEngineClosureConfiguration {

    // ---------------------------------------------------------- A1 引擎核心（8）

    /**
     * 引擎对话模型（A1 #11）：构造逻辑与 {@code AgentEngineConfiguration#agentChatModel}
     * 逐字一致（DeepSeek 专用 formatter 的取舍注释见该类）。缺
     * {@code agent.chat.provider}/{@code agent.chat.model} 或供应商端点即抛——
     * 这是<b>故意 fail-fast</b>（审计 §2.2 #11）。
     */
    @Bean
    @ConditionalOnMissingBean(OpenAIChatModel.class)
    public OpenAIChatModel agentChatModel(AgentProperties agentProperties,
                                          AIModelProperties aiModelProperties,
                                          com.nageoffer.ai.ragent.runtime.usage.EgressPolicy engineEgressPolicy,
                                          com.nageoffer.ai.ragent.runtime.usage.ProviderSpendEnvelope engineProviderSpendEnvelope) {
        AgentProperties.Chat chat = agentProperties.getChat();
        if (chat == null || StrUtil.isBlank(chat.getProvider()) || StrUtil.isBlank(chat.getModel())) {
            throw new IllegalStateException("agent.chat.provider / agent.chat.model 未配置");
        }

        Map<String, AIModelProperties.ProviderConfig> providers = aiModelProperties.getProviders();
        AIModelProperties.ProviderConfig provider = providers == null ? null : providers.get(chat.getProvider());
        if (provider == null) {
            throw new IllegalStateException("agent.chat.provider 在 ai.providers 中不存在: " + chat.getProvider());
        }
        String endpointPath = provider.getEndpoints() == null ? null : provider.getEndpoints().get("chat");
        if (StrUtil.isBlank(provider.getUrl()) || StrUtil.isBlank(endpointPath)) {
            throw new IllegalStateException("供应商缺少 url 或 endpoints.chat: " + chat.getProvider());
        }

        // ENGINE-CHAT-ACCOUNTING（2026-10-11 T0；复核建议收敛项）：引擎外发套入与 p2
        // RealChatGateway 同口径的 egress+预占+usage 结算信封（MeteredEngineModelTransport）。
        // 两依赖 bean 直接注入 = 缺即启动期响亮失败（本组的失败语义：不静默降级）；
        // 信封自身的启用/预算/供应商白名单拒绝照常上抛（fail-closed）。
        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .baseUrl(provider.getUrl())
                .endpointPath(endpointPath)
                .apiKey(provider.getApiKey())
                .modelName(chat.getModel())
                .stream(true)
                .httpTransport(new MeteredEngineModelTransport(
                        io.agentscope.core.model.transport.JdkHttpTransport.builder().build(),
                        engineEgressPolicy, engineProviderSpendEnvelope, chat.getProvider(), chat.getModel()))
                // 兼容端点普遍无法同时处理 response_format 与工具调用，统一走 generate_response 兜底
                .nativeStructuredOutputWithTools(false);
        if (ModelProvider.DEEP_SEEK.matches(chat.getProvider())) {
            builder.formatter(new DeepSeekFormatter());
        }
        return builder.build();
    }

    /**
     * 轮次身份作用域登记表（s2-tenant-cmp-a1，2026-10-12）：轮末状态保存链在共享池上回调
     * AgentStateStore，受理期捕获经它按 (userId, sessionId) 取回重绑；AgentChatServiceImpl
     * 受理时登记、释放钩子第 2 位注销。存储侧经 @Autowired configureRunScope 取用同型 bean。
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentRunScopeRegistry agentRunScopeRegistry() {
        return new AgentRunScopeRegistry();
    }

    /** 引擎会话服务（A1 #1）：ReAct 供给 + 会话 + 在途任务/闸门 + 记忆管线。 */
    @Bean
    @ConditionalOnMissingBean(AgentChatService.class)
    public AgentChatService agentChatService(ReActAgentProvider agentProvider,
                                             AgentConversationService conversationService,
                                             StreamTaskManager taskManager,
                                             AgentRunGate runGate,
                                             AgentMemoryProperties memoryProperties,
                                             AgentMemoryPipeline memoryPipeline,
                                             AgentRunScopeRegistry runScopeRegistry,
                                             org.springframework.beans.factory.ObjectProvider<com.nageoffer.ai.ragent.runtime.web.DeliveryPermits> deliveryPermits,
                                             org.springframework.beans.factory.ObjectProvider<TenantScopeBinder> tenantScopeBinder,
                                             org.springframework.beans.factory.ObjectProvider<com.nageoffer.ai.ragent.agent.memory.MemoryEgressSubjectFacts> memoryEgressFacts) {
        // ENV-B5b 许可接线：帧级 : ai-delivery 元数据（本地传输对裸数据帧 fail-closed）
        // F16 op1 管线修复（2026-10-12）：后台记忆抽取的租户重绑端口（独立/旧形态缺席时合法）
        // s2-tenant-cmp-a1（2026-10-12）：完成链三处（轮末状态保存/终答落库/工具检索）受理期捕获登记
        // s2-f16egress-a1（2026-10-12）：抽取出站身份的在线重建事实端口（缺席＝拒绝重建、退回捕获形态）
        return new AgentChatServiceImpl(agentProvider, conversationService, taskManager, runGate,
                memoryProperties, memoryPipeline, deliveryPermits, tenantScopeBinder, memoryEgressFacts,
                runScopeRegistry);
    }

    /** ReAct 供给器（A1 #3）：5 个中间件为硬依赖；OTel/trace 两件走 ObjectProvider，缺席合法。 */
    @Bean
    @ConditionalOnMissingBean(ReActAgentProvider.class)
    public ReActAgentProvider reActAgentProvider(AgentPromptResolver agentPromptResolver,
                                                 AgentToolCatalog toolCatalog,
                                                 OpenAIChatModel agentChatModel,
                                                 PgAgentStateStore agentStateStore,
                                                 AgentProperties agentProperties,
                                                 AgentUserMemoryMiddleware userMemoryMiddleware,
                                                 AgentContextCompactionMiddleware contextCompactionMiddleware,
                                                 AgentConfirmDenialMiddleware confirmDenialMiddleware,
                                                 AgentSkillMaskingMiddleware skillMaskingMiddleware,
                                                 AgentToolBatchMiddleware toolBatchMiddleware,
                                                 ObjectProvider<OtelTracingMiddleware> otelTracingMiddleware,
                                                 ObjectProvider<AgentTraceEnrichmentMiddleware> traceEnrichmentMiddleware) {
        return new ReActAgentProvider(agentPromptResolver, toolCatalog, agentChatModel, agentStateStore,
                agentProperties, userMemoryMiddleware, contextCompactionMiddleware, confirmDenialMiddleware,
                skillMaskingMiddleware, toolBatchMiddleware, otelTracingMiddleware, traceEnrichmentMiddleware);
    }

    /** 工具目录（A1 #2）：KB 检索 + 意图树 + MCP + 记忆 + 技能读端的聚合点。 */
    @Bean
    @ConditionalOnMissingBean(AgentToolCatalog.class)
    public AgentToolCatalog agentToolCatalog(KnowledgeSearchFacade knowledgeSearchFacade,
                                             IntentNodeRegistry intentNodeRegistry,
                                             AgentMcpClients mcpClients,
                                             AgentMemoryProperties memoryProperties,
                                             AgentMemoryPipeline memoryPipeline,
                                             AgentSkillRegistry skillRegistry) {
        return new AgentToolCatalog(knowledgeSearchFacade, intentNodeRegistry, mcpClients,
                memoryProperties, memoryPipeline, skillRegistry);
    }

    /**
     * MCP 客户端壳（A1 #7）：{@code SaasCapabilityBoundary} 缺席即关闭（零外呼，
     * P1.2a 已把 {@code init()} 在第一次网络调用之前短路）。
     */
    @Bean
    @ConditionalOnMissingBean(AgentMcpClients.class)
    public AgentMcpClients agentMcpClients(AgentMcpProperties agentMcpProperties,
                                           ObjectProvider<SaasCapabilityBoundary> capabilityBoundary) {
        return new AgentMcpClients(agentMcpProperties, capabilityBoundary);
    }

    /** 技能读端注册表（A1 #8 / V3 最小解锁）：只读 + 租户化缓存，管理面维持 BLOCKED。 */
    @Bean
    @ConditionalOnMissingBean(AgentSkillRegistry.class)
    public AgentSkillRegistry agentSkillRegistry(AgentSkillMapper agentSkillMapper,
                                                 AgentSkillCacheManager cacheManager) {
        return new AgentSkillRegistry(agentSkillMapper, cacheManager);
    }

    /** 记忆管线（A1 #9）：抽取/判优/合并。 */
    @Bean
    @ConditionalOnMissingBean(AgentMemoryPipeline.class)
    public AgentMemoryPipeline agentMemoryPipeline(AgentMemoryRepository memoryRepository,
                                                   AgentMemoryJudge memoryJudge,
                                                   AgentMemoryConsolidator memoryConsolidator,
                                                   AgentMemoryProperties memoryProperties) {
        return new AgentMemoryPipeline(memoryRepository, memoryJudge, memoryConsolidator, memoryProperties);
    }

    /** 记忆仓储（A1 #10）：4 个 Mapper + 属性。 */
    @Bean
    @ConditionalOnMissingBean(AgentMemoryRepository.class)
    public AgentMemoryRepository agentMemoryRepository(AgentMemoryMapper memoryMapper,
                                                       AgentMemoryExtractionMapper extractionMapper,
                                                       AgentMemoryControlMapper controlMapper,
                                                       AgentMessageMapper messageMapper,
                                                       AgentMemoryProperties memoryProperties) {
        return new AgentMemoryRepository(memoryMapper, extractionMapper, controlMapper, messageMapper,
                memoryProperties);
    }

    /**
     * 意图树/分类器（A4 #6）：bean 名必须是 {@code defaultIntentClassifier}
     * （RW-08 闭包表 + {@code IntentResolver} 按名限定）。本件同时满足
     * {@code IntentNodeRegistry} 与 {@code IntentClassifier} 两个面，故缺省判定按前者。
     */
    @Bean
    @ConditionalOnMissingBean(IntentNodeRegistry.class)
    public DefaultIntentClassifier defaultIntentClassifier(LLMService llmService,
                                                           IntentNodeMapper intentNodeMapper,
                                                           PromptTemplateLoader promptTemplateLoader,
                                                           IntentTreeCacheManager intentTreeCacheManager,
                                                           OrchestrationProperties orchestrationProperties) {
        return new DefaultIntentClassifier(llmService, intentNodeMapper, promptTemplateLoader,
                intentTreeCacheManager, orchestrationProperties);
    }

    /** 知识检索门面（A4 主入口）：Agent 模式下 rag 对外的唯一检索窄口。 */
    @Bean
    @ConditionalOnMissingBean(KnowledgeSearchFacade.class)
    public KnowledgeSearchFacade knowledgeSearchFacade(QueryRewriteService queryRewriteService,
                                                       IntentResolver intentResolver,
                                                       IntentGuidanceService guidanceService,
                                                       RetrievalEngine retrievalEngine,
                                                       CitationContextEnricher citationContextEnricher,
                                                       RAGPromptService promptService,
                                                       LLMService llmService) {
        return new KnowledgeSearchFacade(queryRewriteService, intentResolver, guidanceService,
                retrievalEngine, citationContextEnricher, promptService, llmService);
    }

    // ---------------------------------------------------------- A5 线程池（4，按名注册）

    /**
     * RAG 上下文处理池。镜像 {@code ThreadPoolExecutorConfig#ragContextExecutor}
     * （尺寸/队列/拒绝策略/TTL 包装逐项一致）；{@code @ConditionalOnMissingBean(name=...)}
     * 防重复声明。
     */
    @Bean(name = "ragContextExecutor")
    @ConditionalOnMissingBean(name = "ragContextExecutor")
    public Executor ragContextExecutor() {
        int cpuCount = Runtime.getRuntime().availableProcessors();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                cpuCount << 2,
                cpuCount << 2,
                60,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                ThreadFactoryBuilder.create()
                        .setNamePrefix("rag_context_executor_")
                        .build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }

    /** RAG 检索池（通道级并行）。镜像 {@code ThreadPoolExecutorConfig#ragRetrievalExecutor}。 */
    @Bean(name = "ragRetrievalExecutor")
    @ConditionalOnMissingBean(name = "ragRetrievalExecutor")
    public Executor ragRetrievalExecutor() {
        int cpuCount = Runtime.getRuntime().availableProcessors();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                cpuCount << 2,
                cpuCount << 2,
                60,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                ThreadFactoryBuilder.create()
                        .setNamePrefix("rag_retrieval_executor_")
                        .build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }

    /** RAG 内部检索池（向量通道内并行）。镜像 {@code ThreadPoolExecutorConfig#innerRetrievalExecutor}。 */
    @Bean(name = "innerRetrievalExecutor")
    @ConditionalOnMissingBean(name = "innerRetrievalExecutor")
    public Executor innerRetrievalExecutor() {
        int cpuCount = Runtime.getRuntime().availableProcessors();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                cpuCount << 1,
                cpuCount << 2,
                60,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                ThreadFactoryBuilder.create()
                        .setNamePrefix("rag_inner_retrieval_executor_")
                        .build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }

    /** 意图分类池。镜像 {@code ThreadPoolExecutorConfig#intentClassifyExecutor}。 */
    @Bean(name = "intentClassifyExecutor")
    @ConditionalOnMissingBean(name = "intentClassifyExecutor")
    public Executor intentClassifyExecutor() {
        int cpuCount = Runtime.getRuntime().availableProcessors();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                cpuCount,
                cpuCount << 1,
                60,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                ThreadFactoryBuilder.create()
                        .setNamePrefix("intent_classify_executor_")
                        .build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }
}
