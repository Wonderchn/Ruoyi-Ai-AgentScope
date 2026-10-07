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

import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.agent.controller.AgentChatController;
import com.nageoffer.ai.ragent.agent.controller.AgentMetaController;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentStateMapper;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryProperties;
import com.nageoffer.ai.ragent.agent.service.AgentChatService;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunGate;
import com.nageoffer.ai.ragent.agent.state.PgAgentStateStore;
import com.nageoffer.ai.ragent.agent.tool.AgentMcpProperties;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import com.nageoffer.ai.ragent.runtime.config.EngineModelAuthority;
import com.nageoffer.ai.ragent.runtime.config.PublishedModelAuthority;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.redisson.api.RedissonClient;

import java.util.ArrayList;
import java.util.List;

/**
 * WP-033 / C13：AgentScope 引擎公开面的内嵌装配（F10 的 chat / confirm / stop / meta 四条）。
 *
 * <p><b>为什么需要单独一组。</b>AI 侧这些组件都写在 {@code com.nageoffer.ai.ragent.*} 下并带
 * {@code @Component}/{@code @Service}，而 platform 应用的扫描根是 {@code org.ruoyi}
 * —— 内嵌形态里它们<b>从来不是 bean</b>。结果是引擎四条公开面<b>根本没有 handler</b>：
 * 不是"注册在了错误的路径上"，而是"没有注册"。C13.3-2 要求的"根路径残留处理"因此在
 * **源码层面**完成（{@code AgentChatController}/{@code AgentMetaController} 已加类级
 * {@code @RequestMapping("/internal/ai/v1")}）。<b>精确范围</b>：归零的是
 * **引擎面这两个控制器**的 {@code /agent/v1/**} 字面量，<b>不是全仓</b> ——
 * {@code AgentConversationController} 的根级会话路径是**有意保留**的例外，
 * 范围与理由写在 {@code AgentEngineSurfaceRouteTest} 的 allowlist 里。
 * 而"是否真的成为 bean"由本组负责。
 * 这正对应 T0 要的那条证据：<b>{@code @RestController} 加类级 {@code @RequestMapping}
 * 本身不会让它成为 bean</b> —— 注册者是本类，方式是
 * {@code @Bean} 显式 {@code new}（与 {@code AiEmbeddedRunConfiguration:151} 注册
 * {@code RunController}、{@code AiEmbeddedRagConfiguration:200} 注册
 * {@code AiResourceController}、{@code AiEmbeddedDocumentConfiguration:114} 注册
 * {@code UploadController}、{@code AiEmbeddedAgentActionConfiguration:87} 注册
 * {@code AgentActionController} 同款），发现路径是
 * {@code ruoyi-ai-web/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}。
 *
 * <p><b>门控刻意独立于 {@code agent.conversation.enabled}。</b>会话 CRUD 不需要 ReAct 引擎；
 * 引擎面需要 Redisson（{@code AgentRunGate} / {@code StreamTaskManager}）、AgentScope SDK 与
 * 真实模型。沿用独立 AI 应用与 {@code @ConditionalOnAgentEngine} 的同一开关
 * {@code ragent.engine.type=agent}：<b>默认关闭</b>，不改变当前 embedded 部署的行为。
 *
 * <p><b>不制造半个公开面：缺前置就响亮失败。</b>开启 {@code ragent.engine.type=agent}
 * 而引擎链不完全装配时，{@link #engineChainReadiness} 会在启动期抛出点名清单的异常，
 * <b>而不是</b>让上下文起来、白名单放行、真实请求拿到 404/503。
 * C3.2 与 {@code LocalWhitelistHandlerCoverageTest} 的教训是同一件事：
 * "放行了但没人接"必须被结构性排除，而不是靠人工记得。
 *
 * <p><b>当前仍然缺失的前置（NOT_COMPLETE，逐条点名）。</b>
 * 引擎面完整可跑还需要下面前置，它们属 <b>WP-032</b>（知识/向量/检索链，T3 租约）
 * 与 WP-033 的 ReAct 装配部分：
 * <ul>
 *   <li>{@code KnowledgeSearchFacade} 及其链
 *       （{@code QueryRewriteService}/{@code IntentResolver}/{@code IntentGuidanceService}/
 *       {@code RetrievalEngine}/{@code CitationContextEnricher}/{@code RAGPromptService}）
 *       —— {@code AgentToolCatalog} 的构造依赖；</li>
 *   <li>{@code AgentPromptResolver}（{@code AgentProfileMapper}/{@code AgentPromptMapper}/
 *       {@code AgentPromptCacheManager}）、{@code IntentNodeRegistry}、
 *       {@code AgentSkillRegistry}、{@code AgentMcpClients}、
 *       {@code AgentMemoryRepository}/{@code AgentMemoryJudge}/{@code AgentMemoryConsolidator}、
 *       {@code AgentContextTrimmer}/{@code AgentContextCompactor}、
 *       {@code ReActAgentProvider}、{@code AgentChatServiceImpl}、{@code OpenAIChatModel}。</li>
 * </ul>
 * 这些不是"配置漏了一行"，而是**整个检索/记忆/人设链尚未装配**。本组刻意
 * <b>不</b>用替身/空实现把它们补齐：替身会让"引擎可用"这个结论变成假的
 * （返回空检索结果、没有工具目录），而 {@code AgentMetaController} 会把
 * 空能力清单当真事实对外声明。宁可启动期点名失败。
 *
 * <p><b>失败语义不放松。</b>{@code EngineModelAuthority} 由本组提供**唯一实现**
 * （{@code PublishedModelAuthority}，数据库发布版本），<b>不提供**任何** YAML 兜底实现</b>：
 * 读不到权威时它抛 {@code ConfigAuthorityUnavailable}，公开面映射为 503（C1.1 fail-closed）。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiEmbeddedAgentEngineConfiguration {

    /** 本地传输下的引擎面装配。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEmbeddedLocal
    public static class LocalTransport {

        /**
         * 引擎门控：{@code ragent.engine.type=agent}（与独立 AI 应用同一开关）。
         *
         * <p>{@code @EnableConfigurationProperties} 覆盖引擎面用到的三组属性。
         * 这里刻意<b>不再</b>写 {@code @Bean AgentProperties}：该类自带
         * {@code @Configuration} + {@code @ConfigurationProperties}，再补一个同类型 bean 会让
         * 容器里出现两个同类型 bean（注入点直接变成
         * {@code expected single matching bean but found 2}），
         * 与 {@code AiEmbeddedModelConfiguration:116-123} 登记过的坑同源。
         */
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnEmbeddedLocal
        @ConditionalOnProperty(name = "ragent.engine.type", havingValue = "agent")
        @EnableConfigurationProperties({AgentProperties.class, AgentMemoryProperties.class,
                AgentMcpProperties.class})
        public static class EngineEnabled {

            // ---------------------------------------------------------- 配置权威（D02/C1）

            /**
             * 运行配置发布权威：数据库不可变已发布版本是**唯一**运行权威。
             *
             * <p><b>只注册这一个实现</b>。不提供 YAML/内存替身，也不加"读不到就用默认值"的
             * 分支 —— C1.1 明文禁止静默回退，而一个"方便的"兜底 bean 会让开关姿态变成
             * "看起来接了权威、实际永远走兜底"。
             */
            @Bean
            @ConditionalOnMissingBean
            public EngineModelAuthority engineModelAuthority(JdbcTemplate jdbc) {
                return new PublishedModelAuthority(jdbc);
            }

            // ---------------------------------------------------------- 引擎面协作件

            /**
             * Agent 持久化状态仓（检查点/step 状态的库内副本）。
             *
             * <p>与 {@code AiEmbeddedAgentConversationConfiguration} 的同名 bean 共存时由
             * {@code @ConditionalOnMissingBean} 去重（返回类型是具体类，不按接口推断）。
             */
            @Bean
            @ConditionalOnMissingBean
            public PgAgentStateStore pgAgentStateStore(AgentStateMapper agentStateMapper) {
                return new PgAgentStateStore(agentStateMapper);
            }

            /** 引擎运行的在途流闸门（Redisson 支撑；无 Redis 时以缺依赖响亮失败，不降级）。 */
            @Bean
            @ConditionalOnMissingBean
            public AgentRunGate agentRunGate(RedissonClient redissonClient, AgentProperties agentProperties) {
                return new AgentRunGate(redissonClient, agentProperties);
            }

            /**
             * 流式任务管理器（取消/停止的进程内 + Redis 协同权威）。
             *
             * <p>它是 {@code AgentChatServiceImpl.stopTask} 的落点：没有它，"停止"端点
             * 即使可达也无法真正终止任何在途流 —— 只会回一个成功的空包络。
             */
            @Bean
            @ConditionalOnMissingBean
            public StreamTaskManager streamTaskManager(RedissonClient redissonClient) {
                return new StreamTaskManager(redissonClient);
            }

            // ---------------------------------------------------------- 公开面（C13.1 四条）

            /**
             * 引擎对话面三条（chat SSE / confirm SSE / stop）。
             *
             * <p>依赖直接写在方法签名上，是刻意的：{@code AgentChatService} 缺席时容器
             * <b>启动失败</b>而不是"少一条路由"。先由
             * {@link #engineChainReadiness} 给出点名清单，再落到容器异常。
             */
            @Bean
            @ConditionalOnMissingBean
            public AgentChatController agentChatController(AgentChatService agentChatService,
                                                           AgentProperties agentProperties) {
                return new AgentChatController(agentChatService, agentProperties);
            }

            /**
             * 引擎探活/能力面（meta）。
             *
             * <p>{@code EngineModelAuthority} 使这里的模型名来自数据库发布版本而不是 YAML
             * （C13.6 / C1.4）；读不到权威时端点返回 503，<b>不回退 YAML</b>。
             */
            @Bean
            @ConditionalOnMissingBean
            public AgentMetaController agentMetaController(AgentProperties agentProperties,
                                                           AgentToolCatalog agentToolCatalog,
                                                           EngineModelAuthority engineModelAuthority) {
                return new AgentMetaController(agentProperties, agentToolCatalog, engineModelAuthority);
            }

            // ---------------------------------------------------------- 前置点名（响亮失败）

            /**
             * 引擎链前置点名。
             *
             * <p><b>为什么需要它而不是让容器自己抛。</b>容器只会给出<b>第一个</b>缺失的 bean
             * （{@code NoSuchBeanDefinitionException: No qualifying bean of type ...}），
             * 修一个再撞下一个；而"这些前置属 WP-032"这个结论只有把清单一次性列全才看得出来。
             * 更关键的是：只有点名才能让"引擎面已接通"这个声称在启动期就被否掉，
             * 而不是让白名单先放行、真实请求拿 404，再由人去发现。
             *
             * <p>刻意用 {@link ObjectProvider} 而不是把依赖写成方法参数：写成参数会让本 bean
             * 在任何一种缺失下直接变成 {@code NoSuchBeanDefinitionException}，回到"只报第一个"。
             */
            @Bean
            public EngineChainReadiness engineChainReadiness(ApplicationContext context) {
                return new EngineChainReadiness(context);
            }
        }
    }

    /**
     * 引擎链就绪检查：缺任一点名前置即在启动期失败。
     *
     * <p>检查的是**类型是否可解析**（而不是某个具体 bean 名），以免把合法的
     * {@code @Primary}/装饰形态误判为缺失。
     */
    public static final class EngineChainReadiness {

        /** 引擎面运行所必需、且当前不在 platform 内嵌扫描/装配范围内的类型。 */
        private static final List<Class<?>> REQUIRED = List.of(
                AgentChatService.class,
                AgentToolCatalog.class,
                com.nageoffer.ai.ragent.agent.config.ReActAgentProvider.class,
                com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver.class,
                com.nageoffer.ai.ragent.rag.service.KnowledgeSearchFacade.class,
                com.nageoffer.ai.ragent.rag.core.intent.IntentNodeRegistry.class,
                com.nageoffer.ai.ragent.agent.tool.AgentMcpClients.class,
                com.nageoffer.ai.ragent.rag.core.skill.AgentSkillRegistry.class,
                com.nageoffer.ai.ragent.agent.memory.AgentMemoryPipeline.class,
                com.nageoffer.ai.ragent.agent.memory.AgentMemoryRepository.class,
                io.agentscope.extensions.model.openai.OpenAIChatModel.class);

        public EngineChainReadiness(ApplicationContext context) {
            List<String> missing = new ArrayList<>();
            for (Class<?> type : REQUIRED) {
                if (context.getBeanNamesForType(type, false, false).length == 0) {
                    missing.add(type.getName());
                }
            }
            if (!missing.isEmpty()) {
                throw new IllegalStateException(
                        "ragent.engine.type=agent 已开启，但 Agent 引擎链未完全装配；"
                                + "引擎面（/agent/v1/chat、/agent/v1/chat/confirm、/agent/v1/stop、"
                                + "/agent/v1/meta）在缺前置的情况下不得放行 —— 否则会出现"
                                + "『白名单已登记但内层无人接』的假公开面。"
                                + "缺失前置（属 WP-032 / WP-033 装配范围）：" + missing);
            }
        }
    }
}
