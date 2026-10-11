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
import com.nageoffer.ai.ragent.agent.config.ReActAgentProvider;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryControlMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryExtractionMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentStateMapper;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryPipeline;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryRepository;
import com.nageoffer.ai.ragent.agent.service.AgentChatService;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunGate;
import com.nageoffer.ai.ragent.agent.service.impl.AgentChatServiceImpl;
import com.nageoffer.ai.ragent.agent.state.PgAgentStateStore;
import com.nageoffer.ai.ragent.agent.tool.AgentMcpClients;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.rerank.RerankService;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.rag.config.OrchestrationProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.DefaultIntentClassifier;
import com.nageoffer.ai.ragent.rag.core.intent.IntentClassifier;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNodeRegistry;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.intent.IntentTreeCacheManager;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.retrieval.MultiChannelRetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.VectorSearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieval.postprocessor.SearchResultPostProcessor;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryTermMappingCacheManager;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkillCacheManager;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkillRegistry;
import com.nageoffer.ai.ragent.rag.core.vector.VectorRetrieverService;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentSkillMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.QueryTermMappingMapper;
import com.nageoffer.ai.ragent.rag.service.KnowledgeSearchFacade;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * S1 引擎最小闭包装配判据：{@code ragent.engine.type=agent} 门开 + 闭包在场 ⇒ 启动成功、
 * 单实例、限定符生效（红先行；装配前本判据以启动失败收场——readiness 点名缺项）。
 *
 * <p><b>钉住的四件事</b>：
 * <ol>
 *   <li><b>门开即装配、缺即响亮失败</b>：本夹具在装配前会以
 *       {@code EngineChainReadiness} 的"缺失前置"异常（或消费者缺 bean）启动失败；
 *       装配后 {@code hasNotFailed()} 且 11 类在 readiness 口径下各恰一个；</li>
 *   <li><b>单实例</b>：{@code PgAgentStateStore} / {@code IntentClassifier}（含
 *       {@code IntentNodeRegistry} 面）/ {@code OpenAIChatModel} 各恰 1——
 *       装配批不得引入第二个声明（审计 §3.6 #5 的 N3 护栏）；</li>
 *   <li><b>Executor 限定符生效（R1）</b>：夹具复刻 platform 真实的<b>两个 {@code @Primary}
 *       Executor</b>（{@code ThreadPoolConfig} 同形）。四条消费链按名取到闭包的池——
 *       字段实参照断言到具名 bean 本体，而不是任何一个 {@code @Primary} 候选；</li>
 *   <li><b>检索链边界</b>：只装 Vector + WebSearch 两通道（ES/Milvus/LightRAG 天然不在场），
 *       且 WebSearch 真链默认关（{@code rag.search.channels.web-search.enabled=false}
 *       ⇒ 空转不外出，R2 边界内的默认安全态）。</li>
 * </ol>
 *
 * <p><b>替身的边界</b>：叶依赖（Mapper/Redis/Redis 客户端/LLM/向量/重排/缓存管理器）以 mock
 * 满足构造；{@code AgentPromptResolver} 属 F09 目录面（{@code AiEmbeddedAgentCatalogConfiguration}，
 * 与本闭包不同批），此处只以替身占"类型在场"，其真实装配由 F09 判据族负责。
 */
@Tag("dev")
class AiEmbeddedAgentEngineClosureAssemblyTest {

    private ApplicationContextRunner runner() {
        return runner(mock(com.nageoffer.ai.ragent.runtime.usage.EgressPolicy.class));
    }

    private ApplicationContextRunner runner(com.nageoffer.ai.ragent.runtime.usage.EgressPolicy engineEgressPolicy) {
        return new ApplicationContextRunner()
                .withAllowBeanDefinitionOverriding(false)
                .withConfiguration(AutoConfigurations.of(AiEmbeddedAgentEngineConfiguration.class))
                .withUserConfiguration(CompetingExecutorFixture.class)
                .withPropertyValues(
                        "ai.integration.enabled=true",
                        "ai.integration.transport=local",
                        "ragent.engine.type=agent",
                        "agent.chat.provider=deepseek",
                        "agent.chat.model=deepseek-flash")
                // ---- 引擎配置自身协作者（与既有判据同形）
                .withBean(AgentStateMapper.class, () -> mock(AgentStateMapper.class))
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(RedissonClient.class, AiEmbeddedAgentEngineClosureAssemblyTest::redisson)
                // ---- 闭包叶依赖：只满足构造，不做行为断言
                .withBean(AgentConversationService.class, () -> mock(AgentConversationService.class))
                .withBean(LLMService.class, () -> mock(LLMService.class))
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(AgentSkillMapper.class, () -> mock(AgentSkillMapper.class))
                // ENGINE-CHAT-ACCOUNTING：agentChatModel 的计量信封两依赖（mock 直通）
                .withBean(com.nageoffer.ai.ragent.runtime.usage.EgressPolicy.class, () -> engineEgressPolicy)
                .withBean(com.nageoffer.ai.ragent.runtime.usage.ProviderSpendEnvelope.class,
                        () -> mock(com.nageoffer.ai.ragent.runtime.usage.ProviderSpendEnvelope.class))
                .withBean(AgentMemoryMapper.class, () -> mock(AgentMemoryMapper.class))
                .withBean(AgentMemoryExtractionMapper.class, () -> mock(AgentMemoryExtractionMapper.class))
                .withBean(AgentMemoryControlMapper.class, () -> mock(AgentMemoryControlMapper.class))
                .withBean(AgentMessageMapper.class, () -> mock(AgentMessageMapper.class))
                .withBean(com.nageoffer.ai.ragent.agent.dao.mapper.AgentContextCompactionMapper.class,
                        () -> mock(com.nageoffer.ai.ragent.agent.dao.mapper.AgentContextCompactionMapper.class))
                .withBean(IntentNodeMapper.class, () -> mock(IntentNodeMapper.class))
                .withBean(IntentTreeCacheManager.class, () -> mock(IntentTreeCacheManager.class))
                .withBean(QueryTermMappingMapper.class, () -> mock(QueryTermMappingMapper.class))
                .withBean(QueryTermMappingCacheManager.class, () -> mock(QueryTermMappingCacheManager.class))
                .withBean(OrchestrationProperties.class, () -> mock(OrchestrationProperties.class))
                .withBean(KnowledgeBaseMapper.class, () -> mock(KnowledgeBaseMapper.class))
                .withBean(KnowledgeChunkMapper.class, () -> mock(KnowledgeChunkMapper.class))
                .withBean(KnowledgeDocumentMapper.class, () -> mock(KnowledgeDocumentMapper.class))
                .withBean(RerankService.class, () -> mock(RerankService.class))
                .withBean(VectorRetrieverService.class, () -> mock(VectorRetrieverService.class))
                .withBean("syncHttpClient", OkHttpClient.class, () -> mock(OkHttpClient.class))
                .withBean(AIModelProperties.class, AiEmbeddedAgentEngineClosureAssemblyTest::aiModelProperties)
                // F09 目录面（不属本闭包）：只占类型在场
                .withBean(AgentPromptResolver.class, () -> mock(AgentPromptResolver.class));
    }

    @Test
    @DisplayName("门开+闭包在场：启动成功，readiness 不再抛，11 类各恰一个")
    void gateOpenWithClosureStartsAndPassesEveryReadinessType() {
        runner().run(context -> {
            assertThat(context.getStartupFailure())
                    .as("开门且闭包齐备必须启动成功；启动失败即 readiness 或装配链点名（红先行证据即此失败）")
                    .isNull();

            assertThat(context).hasSingleBean(AiEmbeddedAgentEngineConfiguration.EngineChainReadiness.class);
            assertThat(context.getBeansOfType(PgAgentStateStore.class))
                    .as("PgAgentStateStore 只允许一个定义（引擎配置与闭包不得双份）")
                    .hasSize(1);
            assertThat(context.getBeansOfType(OpenAIChatModel.class)).hasSize(1);
            assertThat(context.getBeansOfType(IntentClassifier.class)).hasSize(1);
            assertThat(context.getBeansOfType(IntentNodeRegistry.class)).hasSize(1);
            assertThat(context.getBean(IntentClassifier.class)).isInstanceOf(DefaultIntentClassifier.class);
            assertThat(context.getBean(AgentChatService.class)).isInstanceOf(AgentChatServiceImpl.class);

            for (Class<?> required : List.of(
                    AgentChatService.class, AgentToolCatalog.class, ReActAgentProvider.class,
                    KnowledgeSearchFacade.class, IntentNodeRegistry.class, AgentMcpClients.class,
                    AgentSkillRegistry.class, AgentMemoryPipeline.class, AgentMemoryRepository.class,
                    OpenAIChatModel.class, AgentPromptResolver.class)) {
                assertThat(context.getBeanNamesForType(required, false, false))
                        .as("readiness 前置类型必须恰好一个：%s", required.getName())
                        .hasSize(1);
            }

            // V3 最小解锁：读端技能件在场（管理面/导入导出不在本批）
            assertThat(context).hasSingleBean(AgentSkillRegistry.class);
            assertThat(context).hasSingleBean(AgentSkillCacheManager.class);
        });
    }

    @Test
    @DisplayName("R1：两个 @Primary Executor 在场时，四条消费链仍按名取到闭包的池")
    void qualifiedExecutorsWinOverCompetingPrimaryExecutors() throws Exception {
        runner().run(context -> {
            assertThat(context.getStartupFailure()).as("两个 @Primary 候选在场也必须照常启动").isNull();

            assertThat(context.getBean("ragContextExecutor"))
                    .isSameAs(fieldValue(context.getBean(RetrievalEngine.class), "ragContextExecutor"));
            assertThat(context.getBean("ragRetrievalExecutor"))
                    .isSameAs(fieldValue(context.getBean(MultiChannelRetrievalEngine.class), "ragRetrievalExecutor"));
            assertThat(context.getBean("innerRetrievalExecutor"))
                    .isSameAs(fieldValue(context.getBean(VectorSearchChannel.class), "retrievalExecutor"));
            assertThat(context.getBean("intentClassifyExecutor"))
                    .isSameAs(fieldValue(context.getBean(IntentResolver.class), "intentClassifyExecutor"));

            for (String name : List.of("ragContextExecutor", "ragRetrievalExecutor",
                    "innerRetrievalExecutor", "intentClassifyExecutor")) {
                assertThat(context.getBean(name)).isInstanceOf(Executor.class);
            }
        });
    }

    @Test
    @DisplayName("检索链边界：只装 Vector+WebSearch 两通道，ES/Milvus/LightRAG 不在场，WebSearch 真链默认关")
    void retrievalClosureKeepsExcludedBackendsOutAndWebSearchClosed() {
        runner().run(context -> {
            assertThat(context.getStartupFailure()).isNull();

            assertThat(context.getBeansOfType(SearchChannel.class).values().stream()
                    .map(channel -> channel.getClass().getSimpleName())
                    .toList())
                    .as("ES/Milvus/LightRAG 通道不得被本闭包顺带拉起")
                    .containsExactlyInAnyOrder("VectorSearchChannel", "WebSearchChannel");
            assertThat(context.getBeansOfType(SearchResultPostProcessor.class))
                    .as("5 个无条件后置处理器全数在场")
                    .hasSize(5);
            assertThat(context.getBean(SearchChannelProperties.class).getChannels().getWebSearch().isEnabled())
                    .as("WebSearch 真链默认关（shipped 无 web-search.enabled）⇒ 通道在场但空转不外出（R2）")
                    .isFalse();
        });
    }

    @Test
    @DisplayName("ENGINE-CHAT-ACCOUNTING：闭包装配的引擎模型真挂在计量链上——egress 拒绝时模型流以拒绝错误上行（删接线行本判据红）")
    void engineModelIsWiredToTheMeteredTransport() {
        com.nageoffer.ai.ragent.runtime.usage.EgressPolicy denied =
                mock(com.nageoffer.ai.ragent.runtime.usage.EgressPolicy.class);
        doThrow(new IllegalStateException("egress denied by test")).when(denied).requireAllowed(anyString());

        runner(denied).run(context -> {
            assertThat(context).hasNotFailed();
            io.agentscope.extensions.model.openai.OpenAIChatModel model =
                    context.getBean(io.agentscope.extensions.model.openai.OpenAIChatModel.class);
            reactor.core.publisher.Flux<io.agentscope.core.model.ChatResponse> flux = model.stream(
                    java.util.List.of(io.agentscope.core.message.Msg.builder()
                            .role(io.agentscope.core.message.MsgRole.USER).textContent("ping").build()),
                    java.util.List.of(),
                    io.agentscope.core.model.GenerateOptions.builder().build());
            assertThatThrownBy(() -> flux.collectList().block())
                    .hasMessageContaining("egress denied by test");
        });
    }

    // ------------------------------------------------------------------ 夹具

    /**
     * 复刻 platform 真实线程池面：{@code ThreadPoolConfig} 的两个 {@code @Primary} Executor。
     *
     * <p>没有这两竞争者，R1 的限定符修复在本判据里就是空转——四条消费链即使不写限定符
     * 也能唯一解析。把它们放进夹具后，未修复形态的注入必然歧义/错配，判据才对修复敏感。
     */
    @Configuration(proxyBeanMethods = false)
    static class CompetingExecutorFixture {

        @Bean(name = "scheduledExecutorService")
        @Primary
        ScheduledExecutorService scheduledExecutorService() {
            return mock(ScheduledExecutorService.class);
        }

        @Bean(name = "mainExecutor")
        @Primary
        AsyncTaskExecutor mainExecutor() {
            return mock(AsyncTaskExecutor.class);
        }
    }

    /**
     * {@code AgentEngineConfiguration.agentChatModel} 的配置前置：deepseek 提供方 + chat 端点。
     * key 为空即"无凭据"——本判据只构造模型对象，不做任何真实外发。
     */
    private static AIModelProperties aiModelProperties() {
        AIModelProperties properties = new AIModelProperties();
        AIModelProperties.ProviderConfig deepseek = new AIModelProperties.ProviderConfig();
        deepseek.setUrl("http://127.0.0.1:9");
        deepseek.setApiKey("");
        deepseek.setEndpoints(Map.of("chat", "/v1/chat/completions"));
        properties.setProviders(Map.of("deepseek", deepseek));
        return properties;
    }

    /** 与既有判据同形的 Redisson 替身（{@code StreamTaskManager.@PostConstruct} 需要 topic）。 */
    private static RedissonClient redisson() {
        RedissonClient client = mock(RedissonClient.class);
        when(client.getTopic(anyString())).thenReturn(mock(org.redisson.api.RTopic.class));
        return client;
    }

    /** 沿继承链取私有字段实参（限定符是否真的生效，看注入进消费者的池是哪一只）。 */
    private static Object fieldValue(Object target, String fieldName) throws Exception {
        for (Class<?> type = target.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException notHere) {
                // 继续沿父类链找
            }
        }
        throw new NoSuchFieldException(target.getClass().getName() + "#" + fieldName);
    }
}
