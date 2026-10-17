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

package com.nageoffer.ai.ragent.agent.config;

import com.nageoffer.ai.ragent.agent.confirm.AgentConfirmDenialMiddleware;
import com.nageoffer.ai.ragent.agent.memory.AgentContextCompactionMiddleware;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryPipeline;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryProperties;
import com.nageoffer.ai.ragent.agent.memory.AgentUserMemoryMiddleware;
import com.nageoffer.ai.ragent.agent.skill.AgentSkillMaskingMiddleware;
import com.nageoffer.ai.ragent.agent.state.PgAgentStateStore;
import com.nageoffer.ai.ragent.agent.tool.AgentMcpClients;
import com.nageoffer.ai.ragent.agent.tool.AgentToolBatchMiddleware;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog;
import com.nageoffer.ai.ragent.agent.tool.KnowledgeSearchTool;
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptSlot;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkillRegistry;
import com.nageoffer.ai.ragent.rag.service.KnowledgeSearchFacade;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P3-AGENT-PROMPT · F09:5 离线判据（引擎缓存重建语义）。
 *
 * <p>本类只钉"什么时候<b>不</b>该重建、什么时候必须重建"的边界，以及跨租户不复用：
 * <ol>
 *   <li><b>无变化不重建</b>：同一 persona ＋ 同一目录指纹 ⇒ 同一个实例（不重打 Toolkit）；</li>
 *   <li><b>人设变化必须重建</b>（既有用例只覆盖目录变化）：AGENT_MAIN 变了就是另一个人格，
 *       复用旧实例等于把上一份人设继续下发给模型；</li>
 *   <li><b>跨租户不复用</b>：租户切换后 persona 随之改变 ⇒ 必须给出新实例，
 *       且切回时再次重建（实例缓存不跨租户粘住）；</li>
 *   <li><b>判据钩子</b>：{@code evictStateCache} 只作用于已构建实例，
 *       空缓存时不得顺手把 Agent 建起来（清理动作不产生副作用）。</li>
 * </ol>
 *
 * <p>真引擎/provider 腿（真实模型调用、真实 Redis 广播）为具名 NOT_RUN，见交付报告。
 */
@Tag("dev")
class P3EngineCacheRebuildTest {

    private static final String PERSONA_A = "你是租户 A 的助手";
    private static final String PERSONA_B = "你是租户 B 的助手";
    private static final String TOOL_DESC = "知识库工具描述";

    private AgentPromptResolver agentPromptResolver;
    private AgentToolCatalog toolCatalog;
    private ReActAgentProvider provider;
    private PgAgentStateStore stateStore;

    @BeforeEach
    void setUp() {
        agentPromptResolver = mock(AgentPromptResolver.class);
        when(agentPromptResolver.resolveAll()).thenReturn(persona(PERSONA_A));
        toolCatalog = spy(new AgentToolCatalog(
                mock(KnowledgeSearchFacade.class),
                mock(com.nageoffer.ai.ragent.rag.core.intent.IntentNodeRegistry.class),
                mock(AgentMcpClients.class),
                new AgentMemoryProperties(),
                mock(AgentMemoryPipeline.class),
                providerOf(TenantScopeBinder.class, null),
                providerOf(com.nageoffer.ai.ragent.agent.memory.MemoryEgressSubjectFacts.class, null),
                mock(AgentSkillRegistry.class)));
        stateStore = mock(PgAgentStateStore.class);
        provider = new ReActAgentProvider(
                agentPromptResolver,
                toolCatalog,
                mock(OpenAIChatModel.class),
                stateStore,
                new AgentProperties(),
                mock(AgentUserMemoryMiddleware.class),
                mock(AgentContextCompactionMiddleware.class),
                mock(AgentConfirmDenialMiddleware.class),
                mock(AgentSkillMaskingMiddleware.class),
                new AgentToolBatchMiddleware(),
                absent(),
                absent());
    }

    @Test
    @DisplayName("无变化不重建：同一 persona ＋ 同一目录指纹 ⇒ 同一实例，且不重打 Toolkit")
    void unchangedPersonaAndCatalogReuseTheSameInstance() {
        var first = provider.getAgent();
        var second = provider.getAgent();

        assertThat(second.agent()).isSameAs(first.agent());
        verify(toolCatalog, times(1)).buildToolkit(any(AgentToolCatalog.ResolvedCatalog.class));
    }

    @Test
    @DisplayName("F09:5-②：AGENT_MAIN 变化（目录不变）必须重建，不得继续下发旧人设")
    void personaChangeRebuildsEvenWhenCatalogIsUnchanged() {
        var first = provider.getAgent();
        when(agentPromptResolver.resolveAll()).thenReturn(persona(PERSONA_B));

        var second = provider.getAgent();

        assertThat(second.agent())
                .as("人设变了还复用旧实例，等于把上一份人格继续下发给模型")
                .isNotSameAs(first.agent());
        verify(toolCatalog, times(2)).resolve(any());
    }

    @Test
    @DisplayName("F09:5-③：跨租户不复用——租户切换后给新实例，切回时再次重建（缓存不跨租户粘住）")
    void tenantSwitchNeverReusesAnotherTenantsInstance() {
        var tenantA = provider.getAgent();

        when(agentPromptResolver.resolveAll()).thenReturn(persona(PERSONA_B));
        var tenantB = provider.getAgent();

        assertThat(tenantB.agent())
                .as("租户 B 必须拿到自己的人设实例，而不是复用租户 A 的")
                .isNotSameAs(tenantA.agent());

        when(agentPromptResolver.resolveAll()).thenReturn(persona(PERSONA_A));
        var backToA = provider.getAgent();

        assertThat(backToA.agent())
                .as("切回租户 A 也必须重建（provider 只缓存当前 persona，不做跨租户复用）")
                .isNotSameAs(tenantB.agent())
                .isNotSameAs(tenantA.agent());
    }

    @Test
    @DisplayName("判据钩子：空缓存时 evictStateCache 不建实例、不解析提示词（清理动作零副作用）")
    void evictOnEmptyCacheDoesNotBuildTheAgent() {
        provider.evictStateCache("user-7", "session-9");

        verify(toolCatalog, never()).buildToolkit(any(AgentToolCatalog.ResolvedCatalog.class));
        verify(agentPromptResolver, never()).resolveAll();
    }

    @Test
    @DisplayName("判据钩子：已构建实例上驱逐会话状态只作用于该实例（不触发重建）")
    void evictOnBuiltInstanceTargetsTheCurrentInstanceOnly() {
        var active = provider.getAgent();

        provider.evictStateCache("user-7", "session-9");

        assertThat(provider.getAgent().agent())
                .as("驱逐状态不该顺带把 Agent 重建一遍")
                .isSameAs(active.agent());
        verify(toolCatalog, times(1)).buildToolkit(any(AgentToolCatalog.ResolvedCatalog.class));
    }

    private static Map<String, String> persona(String persona) {
        return Map.of(
                AgentPromptSlot.AGENT_MAIN.name(), persona,
                AgentPromptSlot.KNOWLEDGE_TOOL_DESCRIPTION.name(), TOOL_DESC);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> absent() {
        return mock(ObjectProvider.class);
    }

    private static <T> ObjectProvider<T> providerOf(Class<T> type, T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }
        };
    }
}
