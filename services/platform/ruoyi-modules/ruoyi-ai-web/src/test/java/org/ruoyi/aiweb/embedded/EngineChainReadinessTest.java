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

import com.nageoffer.ai.ragent.agent.config.ReActAgentProvider;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryPipeline;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryRepository;
import com.nageoffer.ai.ragent.agent.service.AgentChatService;
import com.nageoffer.ai.ragent.agent.tool.AgentMcpClients;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNodeRegistry;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkillRegistry;
import com.nageoffer.ai.ragent.rag.service.KnowledgeSearchFacade;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 引擎门后的启动期点名语义判据（S1，R5 口径）。
 *
 * <p><b>为什么单独立这条判据。</b>{@code EngineChainReadiness} 的语义是
 * "开门后缺任一点名前置即在启动期响亮失败，一次列全"，而不是让容器只报第一个缺失
 * （那会退化成"修一个撞一个"）。判据把它钉死在两个不变量上：
 * <ol>
 *   <li><b>一次列全</b>：缺 N 个时同一条异常里必须出现全部 N 个类型名，不是只报第一个；</li>
 *   <li><b>报数随在场集合变化（R5）</b>：{@code AgentPromptResolver} 由 F09 目录面
 *       （{@code AiEmbeddedAgentCatalogConfiguration}，无引擎门）提供 ⇒ 引擎门开时它已在场，
 *       缺项报数应为"清单减 1"。判据因此<b>按在场集合计算</b>期望值，
 *       <b>不硬编码"11 个缺失"</b>——否则 F09/F11 任一面进出都会让判据假红。</li>
 * </ol>
 *
 * <p>ApplicationContext 用替身：本判据只测 {@code getBeanNamesForType} 的判定与消息组装，
 * 不起真实容器（真实装配链路由 {@code AiEmbeddedAgentEngineClosureAssemblyTest} 钉）。
 */
@Tag("dev")
class EngineChainReadinessTest {

    /** 与 {@code EngineChainReadiness.REQUIRED} 对表的审计清单（冻结 11 类，见 ENGINE-CLOSURE-AUDIT §2）。 */
    private static final List<Class<?>> AUDITED_REQUIRED = List.of(
            AgentChatService.class,
            AgentToolCatalog.class,
            ReActAgentProvider.class,
            AgentPromptResolver.class,
            KnowledgeSearchFacade.class,
            IntentNodeRegistry.class,
            AgentMcpClients.class,
            AgentSkillRegistry.class,
            AgentMemoryPipeline.class,
            AgentMemoryRepository.class,
            OpenAIChatModel.class);

    @Test
    @DisplayName("全在场即放行：11 类都可解析时不抛")
    void allRequiredTypesPresentPassesTheGate() {
        assertThatCode(() -> new AiEmbeddedAgentEngineConfiguration.EngineChainReadiness(
                contextWith(Set.copyOf(AUDITED_REQUIRED))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("一次列全：缺谁点谁，不是只报第一个")
    void everyMissingTypeIsNamedInOneFailure() {
        assertThatThrownBy(() -> new AiEmbeddedAgentEngineConfiguration.EngineChainReadiness(
                contextWith(Set.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ragent.engine.type=agent 已开启")
                .hasMessageContaining("/agent/v1/chat")
                .satisfies(failure -> {
                    String message = failure.getMessage();
                    for (Class<?> required : AUDITED_REQUIRED) {
                        assertThat(message)
                                .as("缺 %s 时同一条异常必须点名它", required.getName())
                                .contains(required.getName());
                    }
                });
    }

    @Test
    @DisplayName("R5：AgentPromptResolver 已在场（F09 目录面）时，缺项报数=清单减 1，且不得点它的名")
    void promptResolverPresenceShrinksTheMissingList() {
        Set<Class<?>> present = Set.of(AgentPromptResolver.class);

        List<String> expectedMissing = AUDITED_REQUIRED.stream()
                .filter(type -> !present.contains(type))
                .map(Class::getName)
                .toList();

        assertThatThrownBy(() -> new AiEmbeddedAgentEngineConfiguration.EngineChainReadiness(
                contextWith(present)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expectedMissing.get(0))
                .satisfies(failure -> {
                    String message = failure.getMessage();
                    assertThat(message)
                            .as("AgentPromptResolver 在场 ⇒ 不得再被点名（硬编码「11 个缺失」会在此假红）")
                            .doesNotContain(AgentPromptResolver.class.getName());
                    long named = AUDITED_REQUIRED.stream()
                            .filter(type -> type != AgentPromptResolver.class)
                            .filter(type -> message.contains(type.getName()))
                            .count();
                    assertThat(named)
                            .as("缺项报数必须等于「在场过滤后的清单大小」，计数=%d", expectedMissing.size())
                            .isEqualTo(expectedMissing.size());
                });
    }

    /**
     * 真实装配下 readiness 的在场判定走 {@code getBeanNamesForType(type, false, false)}：
     * 替身按同一签名回话，保证"判据测的是生产调用路径"。
     */
    private static ApplicationContext contextWith(Set<Class<?>> present) {
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBeanNamesForType(any(Class.class), anyBoolean(), anyBoolean()))
                .thenAnswer(invocation -> present.contains(invocation.getArgument(0, Class.class))
                        ? new String[]{"stub-bean"}
                        : new String[0]);
        return context;
    }

    @Test
    @DisplayName("审计清单与生产 REQUIRED 对表：本判据的期望集不是自说自话")
    void auditedListCoversEveryTypeTheProductionMessageCanName() {
        // 空上下文下生产消息点名的集合必须恰好等于本判据维护的审计清单
        final String message;
        try {
            new AiEmbeddedAgentEngineConfiguration.EngineChainReadiness(contextWith(Set.of()));
            throw new IllegalStateException("空上下文必须失败");
        } catch (IllegalStateException failure) {
            message = failure.getMessage();
        }
        Set<String> named = AUDITED_REQUIRED.stream()
                .map(Class::getName)
                .filter(message::contains)
                .collect(Collectors.toSet());
        assertThat(named)
                .as("生产 REQUIRED 清单若增删类型，本判据必须同步（防清单漂移被静默放过）")
                .hasSameElementsAs(AUDITED_REQUIRED.stream().map(Class::getName).toList());
    }
}
