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

package com.nageoffer.ai.ragent.agent.skill;

import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkill;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkillRegistry;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F09:3/F11:3 运行期缺陷（真机 2026-10-18）：{@code AgentSkillMaskingMiddleware.onReasoning}
 * 的 catch 此前 **fail-open**（"本轮按全量工具推理"）。绑定查询在 agent 引擎线程上因
 * 缺少可信租户上下文抛 {@code tenant.context.missing} 时，所有技能解锁的工具被放回模型，
 * 违反"空绑定/越权技能零加载"。
 *
 * <p>判据（红先行）：遮蔽计算失败时 **不得**回退全量——
 * <ul>
 *   <li>技能清单 {@code toolIds} 与 MCP 目录 {@code mcpToolIds()} 兜底汇总的工具必须被过滤；</li>
 *   <li>非技能/MCP 的常规工具（如 {@code knowledge_search}）保留；</li>
 *   <li>兜底遮蔽映射写入 {@code MASKED_TOOLS_ATTRIBUTE}（工具执行前复查同样拒绝）。</li>
 * </ul>
 */
@Tag("dev")
class AgentSkillMaskingMiddlewareFailClosedTest {

    private static final String PROFILE = "agent-1";
    private static final String MCP_TOOL = "mcp-x";
    private static final String KNOWLEDGE_TOOL = "knowledge_search";
    private static final String MASK_ERROR = "__mask_error__";

    @Test
    @DisplayName("绑定查询失败：fail-closed 遮蔽技能/MCP 工具，不回退全量工具")
    void maskingFailureDoesNotFallBackToFullToolset() {
        AgentSkillRegistry registry = mock(AgentSkillRegistry.class);
        AgentSkill skill = new AgentSkill("skill-a", "退货办理", "办理退货", "手册正文", List.of(MCP_TOOL));
        when(registry.listEnabled()).thenReturn(List.of(skill));
        // 真机缺陷形状：绑定读取在该线程没有可信租户上下文 ⇒ 抛 tenant.context.missing
        when(registry.listEnabledFor(PROFILE))
                .thenThrow(new IllegalStateException("tenant.context.missing"));

        AgentToolCatalog catalog = mock(AgentToolCatalog.class);
        when(catalog.mcpToolIds()).thenReturn(new LinkedHashSet<>(List.of(MCP_TOOL)));
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentToolCatalog> catalogProvider = mock(ObjectProvider.class);
        when(catalogProvider.getIfAvailable()).thenReturn(catalog);

        AgentSkillMaskingMiddleware middleware = new AgentSkillMaskingMiddleware(registry, catalogProvider, null);
        RuntimeContext context = RuntimeContext.builder()
                .userId("user-a")
                .sessionId("session-a")
                .put(AgentSkillMaskingMiddleware.AGENT_PROFILE_ID_ATTRIBUTE, PROFILE)
                .build();
        List<ToolSchema> tools = List.of(
                ToolSchema.builder().name(KNOWLEDGE_TOOL).description("检索知识库").build(),
                ToolSchema.builder().name(MCP_TOOL).description("提交退货").build());
        ReasoningInput input = new ReasoningInput(List.of(), tools, null);
        AtomicReference<ReasoningInput> forwarded = new AtomicReference<>();

        middleware.onReasoning(null, context, input, reasoningInput -> {
            forwarded.set(reasoningInput);
            return Flux.empty();
        }).blockLast();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().tools()).extracting(ToolSchema::getName)
                .as("遮蔽计算失败时未加载手册的 MCP 工具必须继续遮蔽（不得回退全量）")
                .doesNotContain(MCP_TOOL)
                .contains(KNOWLEDGE_TOOL);
        Object masked = context.get(AgentSkillMaskingMiddleware.MASKED_TOOLS_ATTRIBUTE);
        assertThat(masked)
                .as("工具执行前复查必须看到同一份 fail-closed 遮蔽映射")
                .isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, String> maskedByName = (Map<String, String>) masked;
        assertThat(maskedByName).containsEntry(MCP_TOOL, MASK_ERROR);
    }
}
