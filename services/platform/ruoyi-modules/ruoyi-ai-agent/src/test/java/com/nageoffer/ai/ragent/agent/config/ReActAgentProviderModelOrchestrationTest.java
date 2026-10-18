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
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import com.nageoffer.ai.ragent.rag.binding.AgentProfileBindingService;
import com.nageoffer.ai.ragent.rag.binding.AgentProfileBindingService.RuntimeBindings;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNodeRegistry;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptSlot;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkillRegistry;
import com.nageoffer.ai.ragent.rag.service.KnowledgeSearchFacade;
import io.agentscope.core.ReActAgent;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F09:3-C：{@link ReActAgentProvider} 的 MODEL / ORCHESTRATION 运行消费判据（离线）。
 *
 * <p><b>验收口径（写死）。</b>
 * <ul>
 *   <li>MODEL 消费 = 绑定模型被用于该 Agent 的 ReAct 主链（{@code agent.getModel()} 即绑定实例）；
 *       同 provider 内切换是保底能力；跨 provider 的费率缺口由既有预算信封拒绝，不属本包；</li>
 *   <li>ORCHESTRATION 消费 = {@code PLAN_ACT} 映射为 {@code enableTaskList(true)} 的
 *       「ReAct＋持久任务清单」（可观测：工具清单出现 {@code todo_write}），<b>不是</b>
 *       独立 Plan-and-Execute 控制环；{@code REACT} = 现状；</li>
 *   <li>fail-closed：绑定 MODEL 解析不到（工厂缺席/抛错）⇒ 抛，<b>不回落全局模型</b>；</li>
 *   <li>无绑定 ⇒ 与现状完全一致，且模型工厂零调用；</li>
 *   <li>槽位键含 tenant/model/mode；{@code evictStateCache} 覆盖全局槽与全部绑定槽。</li>
 * </ul>
 */
@Tag("dev")
class ReActAgentProviderModelOrchestrationTest {

    private static final String TENANT = "T1";
    private static final String PROFILE = "profile-1";
    private static final String USER = "user-7";
    private static final String SESSION = "session-9";

    private AgentPromptResolver agentPromptResolver;
    private AgentToolCatalog toolCatalog;
    private PgAgentStateStore stateStore;
    private OpenAIChatModel globalModel;
    private AgentProfileBindingService bindingService;
    private AgentModelFactory modelFactory;
    private ReActAgentProvider provider;

    @BeforeEach
    void setUp() {
        PrincipalContext.set(new ExecutionPrincipal(TENANT, "2101", "platform:" + TENANT + ":2101",
                1, 1, Set.of("agent.chat"), "jti", "platform", 0, Long.MAX_VALUE));

        agentPromptResolver = mock(AgentPromptResolver.class);
        when(agentPromptResolver.resolveAll()).thenReturn(persona("你是租户助手"));
        when(agentPromptResolver.effectiveProfileId()).thenReturn(Optional.empty());

        toolCatalog = spy(new AgentToolCatalog(
                mock(KnowledgeSearchFacade.class),
                mock(IntentNodeRegistry.class),
                mock(AgentMcpClients.class),
                new AgentMemoryProperties(),
                mock(AgentMemoryPipeline.class),
                providerOf(TenantScopeBinder.class, null),
                providerOf(com.nageoffer.ai.ragent.agent.memory.MemoryEgressSubjectFacts.class, null),
                mock(AgentSkillRegistry.class)));

        stateStore = mock(PgAgentStateStore.class);
        globalModel = mock(OpenAIChatModel.class);
        when(globalModel.getModelName()).thenReturn("global-model");
        bindingService = mock(AgentProfileBindingService.class);
        modelFactory = mock(AgentModelFactory.class);

        provider = new ReActAgentProvider(
                agentPromptResolver,
                toolCatalog,
                globalModel,
                stateStore,
                new AgentProperties(),
                mock(AgentUserMemoryMiddleware.class),
                mock(AgentContextCompactionMiddleware.class),
                mock(AgentConfirmDenialMiddleware.class),
                mock(AgentSkillMaskingMiddleware.class),
                new AgentToolBatchMiddleware(),
                absent(),
                absent());
        provider.bindModelOrchestration(
                providerOf(AgentProfileBindingService.class, bindingService),
                providerOf(AgentModelFactory.class, modelFactory));
    }

    @AfterEach
    void clear() {
        PrincipalContext.clear();
    }

    // ------------------------------------------------------------------ 无绑定 = 现状

    @Test
    @DisplayName("无绑定：使用全局模型、实例复用，模型工厂与绑定服务零调用（行为与现状一致）")
    void withoutBindingKeepsGlobalModelAndNeverConsultsFactory() {
        ReActAgentProvider.ActiveAgent first = provider.getAgent();
        ReActAgentProvider.ActiveAgent second = provider.getAgent();

        assertThat(second.agent()).isSameAs(first.agent());
        assertThat(first.agent().getModel()).isSameAs(globalModel);
        verifyNoInteractions(modelFactory);
        verify(bindingService, never()).runtimeBindings(any());
    }

    // ------------------------------------------------------------------ MODEL 消费

    @Test
    @DisplayName("MODEL 绑定：ReAct 主链实际使用绑定模型（同 provider 内切换）")
    void boundModelIsUsedByTheReActAgent() {
        OpenAIChatModel bound = model("deepseek-chat");
        when(modelFactory.create(TENANT, "deepseek-chat")).thenReturn(bound);
        bound("deepseek-chat", "REACT");

        ReActAgentProvider.ActiveAgent active = provider.getAgent();

        assertThat(active.agent().getModel()).isSameAs(bound);
        assertThat(active.agent().getModel().getModelName()).isEqualTo("deepseek-chat");
        verify(modelFactory).create(TENANT, "deepseek-chat");
    }

    @Test
    @DisplayName("fail-closed：绑定 MODEL 但工厂缺席 ⇒ 抛具名异常，不回落全局模型")
    void modelBindingFailsClosedWhenFactoryMissing() {
        bound("deepseek-chat", "REACT");
        provider.bindModelOrchestration(
                providerOf(AgentProfileBindingService.class, bindingService),
                providerOf(AgentModelFactory.class, null));

        assertThatThrownBy(() -> provider.getAgent())
                .isInstanceOf(AgentModelFactory.AgentModelUnavailableException.class)
                .hasMessageContaining("deepseek-chat");
    }

    @Test
    @DisplayName("fail-closed：工厂解析抛错 ⇒ 原样上抛，不回落全局模型")
    void modelBindingPropagatesFactoryFailure() {
        when(modelFactory.create(TENANT, "deepseek-chat"))
                .thenThrow(new AgentModelFactory.AgentModelUnavailableException("目录里没有该模型"));
        bound("deepseek-chat", "REACT");

        assertThatThrownBy(() -> provider.getAgent())
                .isInstanceOf(AgentModelFactory.AgentModelUnavailableException.class)
                .hasMessageContaining("目录里没有该模型");
    }

    // ------------------------------------------------------------------ ORCHESTRATION 消费

    @Test
    @DisplayName("PLAN_ACT：ReAct＋持久任务清单（todo_write 在），REACT 现状（不在）；两种模式各自成槽")
    void planActEnablesTaskListWhileReactKeepsCurrentShape() {
        OpenAIChatModel bound = model("deepseek-chat");
        when(modelFactory.create(TENANT, "deepseek-chat")).thenReturn(bound);

        bound("deepseek-chat", "PLAN_ACT");
        ReActAgentProvider.ActiveAgent planAct = provider.getAgent();
        assertThat(planAct.agent().getToolkit().getToolNames())
                .as("PLAN_ACT 的可观测证据：持久任务清单工具 todo_write 必须出现")
                .contains("todo_write");

        bound("deepseek-chat", "REACT");
        ReActAgentProvider.ActiveAgent react = provider.getAgent();
        assertThat(react.agent()).isNotSameAs(planAct.agent());
        assertThat(react.agent().getToolkit().getToolNames())
                .as("REACT 不得启用任务清单（现状行为）")
                .doesNotContain("todo_write");
    }

    // ------------------------------------------------------------------ 槽位隔离与重建

    @Test
    @DisplayName("槽位隔离：不同模型/模式各成槽；切回命中同一实例；人设变化在该槽重建")
    void boundSlotsAreIsolatedAndRebuiltByPersona() {
        OpenAIChatModel modelA = model("model-a");
        OpenAIChatModel modelB = model("model-b");
        when(modelFactory.create(TENANT, "model-a")).thenReturn(modelA);
        when(modelFactory.create(TENANT, "model-b")).thenReturn(modelB);

        bound("model-a", "REACT");
        ReActAgentProvider.ActiveAgent slotA = provider.getAgent();

        bound("model-b", "REACT");
        ReActAgentProvider.ActiveAgent slotB = provider.getAgent();
        assertThat(slotB.agent()).isNotSameAs(slotA.agent());
        assertThat(slotB.agent().getModel()).isSameAs(modelB);

        bound("model-a", "REACT");
        assertThat(provider.getAgent().agent()).isSameAs(slotA.agent());

        when(agentPromptResolver.resolveAll()).thenReturn(persona("你是另一个租户助手"));
        ReActAgentProvider.ActiveAgent rebuilt = provider.getAgent();
        assertThat(rebuilt.agent()).isNotSameAs(slotA.agent());
        assertThat(rebuilt.agent().getModel()).isSameAs(modelA);
    }

    @Test
    @DisplayName("evictStateCache：遍历全局槽与全部绑定槽；空缓存不建实例")
    void evictCoversEveryCacheSlotWithoutBuilding() {
        provider.evictStateCache(USER, SESSION);
        verify(toolCatalog, never()).buildToolkit(any(AgentToolCatalog.ResolvedCatalog.class));
        verify(agentPromptResolver, never()).resolveAll();

        ReActAgentProvider.ActiveAgent global = provider.getAgent();

        OpenAIChatModel modelA = model("model-a");
        OpenAIChatModel modelB = model("model-b");
        when(modelFactory.create(TENANT, "model-a")).thenReturn(modelA);
        when(modelFactory.create(TENANT, "model-b")).thenReturn(modelB);
        bound("model-a", "REACT");
        ReActAgentProvider.ActiveAgent slotA = provider.getAgent();
        bound("model-b", "PLAN_ACT");
        ReActAgentProvider.ActiveAgent slotB = provider.getAgent();

        markState(global.agent(), USER, SESSION);
        markState(slotA.agent(), USER, SESSION);
        markState(slotB.agent(), USER, SESSION);

        provider.evictStateCache(USER, SESSION);

        assertThat(hasState(global.agent(), USER, SESSION)).isFalse();
        assertThat(hasState(slotA.agent(), USER, SESSION)).isFalse();
        assertThat(hasState(slotB.agent(), USER, SESSION)).isFalse();
        verify(toolCatalog, times(3)).buildToolkit(any(AgentToolCatalog.ResolvedCatalog.class));
    }

    // ------------------------------------------------------------------ 夹具

    private void bound(String modelId, String mode) {
        when(agentPromptResolver.effectiveProfileId()).thenReturn(Optional.of(PROFILE));
        when(bindingService.runtimeBindings(PROFILE)).thenReturn(new RuntimeBindings(
                Optional.ofNullable(modelId), mode, Set.of(), Set.of(), "binding-v1"));
    }

    private static OpenAIChatModel model(String name) {
        OpenAIChatModel model = mock(OpenAIChatModel.class);
        when(model.getModelName()).thenReturn(name);
        return model;
    }

    private static Map<String, String> persona(String persona) {
        return Map.of(
                AgentPromptSlot.AGENT_MAIN.name(), persona,
                AgentPromptSlot.KNOWLEDGE_TOOL_DESCRIPTION.name(), "知识库工具描述");
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

    // 会话状态缓存在 AgentScope 2.0.2 的 ReActAgent 内是私有 ConcurrentHashMap；
    // 这里用反射放入/检查标记，验证 evict 是否真的遍历到每个槽（不依赖真引擎）。
    private static final Field STATE_CACHE = stateCacheField();
    private static final Method SLOT_KEY = slotKeyMethod();

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void markState(ReActAgent agent, String userId, String sessionId) {
        try {
            ConcurrentHashMap<String, Object> cache = (ConcurrentHashMap<String, Object>) STATE_CACHE.get(agent);
            cache.put((String) SLOT_KEY.invoke(null, userId, sessionId), new Object());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean hasState(ReActAgent agent, String userId, String sessionId) {
        try {
            ConcurrentHashMap<String, Object> cache = (ConcurrentHashMap<String, Object>) STATE_CACHE.get(agent);
            return cache.containsKey(SLOT_KEY.invoke(null, userId, sessionId));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Field stateCacheField() {
        try {
            Field field = ReActAgent.class.getDeclaredField("stateCache");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Method slotKeyMethod() {
        try {
            Method method = ReActAgent.class.getDeclaredMethod("slotKey", String.class, String.class);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }
}
