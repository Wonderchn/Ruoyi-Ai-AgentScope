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

import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog;
import com.nageoffer.ai.ragent.agent.memory.AgentContextTrimmer;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkill;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkillRegistry;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ToolSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 技能工具遮蔽：手册加载前不让模型看到那些不看手册就会办错的工具，避免绕过前置步骤直接动手
 * <p>
 * 技能的 tool_ids 是「加载后才解锁的工具」，不是「正文会用到的工具」，正文按名字引用任何工具都不受影响。
 * 记 G 为所有启用技能 tool_ids 的并集、L 为上下文里已加载技能 tool_ids 的并集，本轮遮蔽 = G − L：
 * 不在任何 tool_ids 里的工具始终可见，一个工具可挂多份手册，任一加载即放行
 * <p>
 * 解锁只认上下文里那条 load_skill 结果，手册被压缩带走时工具一并收回，两者同生共死
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
public class AgentSkillMaskingMiddleware implements MiddlewareBase {

    /** P3-BIND：运行上下文里的"生效档案 id"键（由 AgentChatServiceImpl 放入；缺失 ⇒ 消费侧 fail-closed）。 */
    public static final String AGENT_PROFILE_ID_ATTRIBUTE = "ragent_agent_profile_id";

    /**
     * 本次推理算出的遮蔽映射寄存在调用上下文里，工具执行前复查同一份结论
     */
    public static final String MASKED_TOOLS_ATTRIBUTE = "ragent_masked_tools";

    private final AgentSkillRegistry skillRegistry;

    /** P3-BIND O-2：MCP 工具 id 全集来源与绑定来源；两者缺席 ⇒ 不做 MCP 收窄（兼容构造/老装配）。 */
    private final org.springframework.beans.factory.ObjectProvider<com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog> toolCatalog;
    private final com.nageoffer.ai.ragent.rag.binding.AgentProfileBindingService bindingService;

    public AgentSkillMaskingMiddleware(AgentSkillRegistry skillRegistry,
                                       org.springframework.beans.factory.ObjectProvider<com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog> toolCatalog,
                                       com.nageoffer.ai.ragent.rag.binding.AgentProfileBindingService bindingService) {
        this.skillRegistry = skillRegistry;
        this.toolCatalog = toolCatalog;
        this.bindingService = bindingService;
    }

    /** 兼容构造：仅注册表（不做 MCP 收窄；既有测试与老装配沿用）。 */
    public AgentSkillMaskingMiddleware(AgentSkillRegistry skillRegistry) {
        this(skillRegistry, null, null);
    }

    /**
     * 排在最内层，看到的是压缩与改写之后的最终消息列表
     */
    @Override
    public int order() {
        return 0;
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext runtimeContext, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        Map<String, String> masked;
        try {
            Object profileId = runtimeContext == null ? null : runtimeContext.get(AGENT_PROFILE_ID_ATTRIBUTE);
            masked = maskedTools(input.messages(), profileId instanceof String id ? id : null);
        } catch (Exception e) {
            // F11:3 fail-closed（F09:3-D1，真机 2026-10-18）：遮蔽算不出来时**不得**回退全量工具。
            // 旧实现"本轮按全量工具推理"会在绑定查询失败（如 tenant.context.missing）时把
            // 未加载手册的写工具放回模型，等于一次查询异常就取消全部技能门。
            Map<String, String> fallback = failClosedMaskedTools();
            log.warn("技能遮蔽计算异常, 本轮按 fail-closed 遮蔽（{} 个工具，不回退全量）", fallback.size(), e);
            if (runtimeContext != null) {
                runtimeContext.put(MASKED_TOOLS_ATTRIBUTE, fallback);
            }
            if (fallback.isEmpty()) {
                return next.apply(input);
            }
            List<ToolSchema> tools = input.tools().stream()
                    .filter(tool -> !fallback.containsKey(tool.getName()))
                    .toList();
            return next.apply(new ReasoningInput(input.messages(), tools, input.options()));
        }
        if (runtimeContext != null) {
            runtimeContext.put(MASKED_TOOLS_ATTRIBUTE, masked);
        }
        if (masked.isEmpty()) {
            return next.apply(input);
        }
        List<ToolSchema> tools = input.tools().stream()
                .filter(tool -> !masked.containsKey(tool.getName()))
                .toList();
        return next.apply(new ReasoningInput(input.messages(), tools, input.options()));
    }

    /**
     * 遮蔽计算失败时的兜底遮蔽面：技能清单的 {@code toolIds} 与 MCP 目录的 id 全集
     * （各自尽力而为，读取失败只损失该来源的覆盖，不改变 fail-closed 方向）。
     *
     * <p>取值 {@code "__mask_error__"} 让工具执行前的复查（{@code McpToolProxy}）同样拒绝。
     */
    private Map<String, String> failClosedMaskedTools() {
        Map<String, String> fallback = new LinkedHashMap<>();
        try {
            List<AgentSkill> enabled = skillRegistry.listEnabled();
            if (enabled != null) {
                enabled.forEach(skill -> skill.toolIds()
                        .forEach(toolId -> fallback.putIfAbsent(toolId, "__mask_error__")));
            }
        } catch (Exception e) {
            log.warn("技能清单读取失败, 兜底遮蔽面缺少技能工具集", e);
        }
        try {
            AgentToolCatalog catalog = toolCatalog == null ? null : toolCatalog.getIfAvailable();
            if (catalog != null) {
                catalog.mcpToolIds().forEach(toolId -> fallback.putIfAbsent(toolId, "__mask_error__"));
            }
        } catch (Exception e) {
            log.warn("MCP 工具目录读取失败, 兜底遮蔽面缺少 MCP 工具集", e);
        }
        return fallback;
    }

    /**
     * @return 需遮蔽的工具 ID 到其所属技能标识，模型硬闯时用它拼提示
     */
    private Map<String, String> maskedTools(List<Msg> messages, String agentProfileId) {
        // G = 全部**已启用**技能解锁的工具并集（遮蔽面不因"该档案没绑定"而消失，否则越权工具会露出来）
        List<AgentSkill> all = skillRegistry.listEnabled();
        if (all.isEmpty()) {
            return Map.of();
        }
        // L = 已加载 **且** 落在本档案绑定子集内 的技能：
        // 空绑定或有绑定未加载 ⇒ L 为空 ⇒ G 全遮蔽（fail-closed）
        Set<String> bound = agentProfileId == null ? Set.of() : skillRegistry.listEnabledFor(agentProfileId).stream()
                .map(AgentSkill::skillCode)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        Set<String> loaded = loadedSkillCodes(messages);
        loaded.retainAll(bound);
        Map<String, String> masked = new LinkedHashMap<>();
        all.stream()
                .filter(skill -> !loaded.contains(skill.skillCode()))
                .forEach(skill -> skill.toolIds().forEach(toolId -> masked.putIfAbsent(toolId, skill.skillCode())));
        // 一个工具挂在多份手册下时，任一手册已加载（且已绑定）即放行
        all.stream()
                .filter(skill -> loaded.contains(skill.skillCode()))
                .forEach(skill -> skill.toolIds().forEach(masked::remove));
        // O-2：MCP 工具按**生效档案的 MCP 绑定集**收窄——未绑定/空绑定 ⇒ 遮蔽；id 缺失 ⇒ 全遮蔽。
        // 遮蔽面仍取目录全量，只有"可用集"按绑定收窄（沿用 G/L 纪律，避免反向暴露）。
        AgentToolCatalog catalog = toolCatalog == null ? null : toolCatalog.getIfAvailable();
        if (catalog != null) {
            Set<String> boundMcp = agentProfileId == null || bindingService == null
                    ? Set.of()
                    : bindingService.boundMcpToolIds(agentProfileId);
            catalog.mcpToolIds().stream()
                    .filter(toolId -> !boundMcp.contains(toolId))
                    .forEach(toolId -> masked.putIfAbsent(toolId, "__unbound_mcp__"));
        }
        return masked;
    }

    /**
     * 从上下文里捡出已加载的技能，标记由 load_skill 写在结果 metadata 上
     * 裁剪只换正文不动 metadata，占位块要当成没加载：手册不在了工具就得收回去
     */
    private static Set<String> loadedSkillCodes(List<Msg> messages) {
        Set<String> loaded = new HashSet<>();
        for (Msg msg : messages) {
            if (msg.getRole() != MsgRole.TOOL) {
                continue;
            }
            for (ToolResultBlock result : msg.getContentBlocks(ToolResultBlock.class)) {
                if (result.getState() == ToolResultState.SUCCESS
                        && !AgentContextTrimmer.isEvicted(result)
                        && result.getMetadata().get(SkillLoadTool.LOADED_SKILL_METADATA_KEY) instanceof String code) {
                    loaded.add(code);
                }
            }
        }
        return loaded;
    }
}
