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

import com.nageoffer.ai.ragent.agent.tool.AgentMcpClients.RemoteTool;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.McpToolBinding;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog;
import com.nageoffer.ai.ragent.agent.tool.McpToolProxy;
import com.nageoffer.ai.ragent.rag.binding.AgentProfileBindingService;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkill;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkillRegistry;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Tag;

@Tag("dev")
class SkillLoadToolActivationTest {

    private static final String SKILL_CODE = "return_apply";
    private static final String MCP_TOOL = "return_submit";

    @Test
    void shouldExposeAndExecuteMcpToolAfterLoadingSkillInSameCall() {
        AgentSkill skill = new AgentSkill(SKILL_CODE, "退货办理", "办理退货",
                "先核对订单，再提交退货申请", List.of(MCP_TOOL));
        AgentSkillRegistry registry = mock(AgentSkillRegistry.class);
        when(registry.listEnabled()).thenReturn(List.of(skill));
        when(registry.listEnabledFor("agent-1")).thenReturn(List.of(skill));
        when(registry.findByCodeFor("agent-1", SKILL_CODE)).thenReturn(skill);
        // 生产三参装配：目录提供 MCP id 全集，绑定侧声明该工具**已绑定**给 agent-1
        AgentToolCatalog catalog = mock(AgentToolCatalog.class);
        when(catalog.mcpToolIds()).thenReturn(new java.util.LinkedHashSet<>(List.of(MCP_TOOL)));
        @SuppressWarnings("unchecked")
        final org.springframework.beans.factory.ObjectProvider<AgentToolCatalog> catalogProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(catalogProvider.getIfAvailable()).thenReturn(catalog);
        AgentProfileBindingService bindingService = mock(AgentProfileBindingService.class);
        when(bindingService.boundMcpToolIds("agent-1")).thenReturn(java.util.Set.of(MCP_TOOL));

        McpClientWrapper client = mock(McpClientWrapper.class);
        when(client.getName()).thenReturn("returns");
        when(client.callTool(eq(MCP_TOOL), anyMap(), anyMap())).thenReturn(Mono.just(CallToolResult.builder()
                .content(List.of(new TextContent("退货申请已提交")))
                .isError(false)
                .build()));
        Tool definition = Tool.builder()
                .name(MCP_TOOL)
                .description("提交退货申请")
                .inputSchema(new JsonSchema("object", Map.of(), List.of(), false, null, null))
                .annotations(new ToolAnnotations(null, true, null, null, null, null))
                .build();

        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new SkillLoadTool(registry));
        toolkit.registerAgentTool(new McpToolProxy(McpToolBinding.of(
                MCP_TOOL, "提交退货", "提交退货申请", false, new RemoteTool(definition, client))));
        ActivationModel model = new ActivationModel();
        ReActAgent agent = ReActAgent.builder()
                .name("returns")
                .sysPrompt("按技能手册办理退货")
                .model(model)
                .toolkit(toolkit)
                .stateStore(new InMemoryAgentStateStore())
                .permissionContext(PermissionContextState.builder().mode(PermissionMode.BYPASS).build())
                .middleware(new AgentSkillMaskingMiddleware(registry, catalogProvider, bindingService))
                .maxIters(3)
                .build();

        Msg answer = agent.call(
                        List.of(Msg.builder().name("user").role(MsgRole.USER).textContent("帮我退货").build()),
                        RuntimeContext.builder().userId("user-a").sessionId("session-a")
                                .put(AgentSkillMaskingMiddleware.AGENT_PROFILE_ID_ATTRIBUTE, "agent-1").build())
                .block(Duration.ofSeconds(5));

        assertThat(answer).isNotNull();
        assertThat(model.reasoningTurns.get()).isEqualTo(3);
        assertThat(model.mcpResult).isNotNull();
        assertThat(model.mcpResult.getState()).isEqualTo(ToolResultState.SUCCESS);
        verify(client).callTool(eq(MCP_TOOL), anyMap(), anyMap());
    }

    private static ToolResultBlock result(List<Msg> messages, String toolName) {
        return messages.stream()
                .filter(message -> message.getRole() == MsgRole.TOOL)
                .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                .filter(block -> toolName.equals(block.getName()))
                .findFirst()
                .orElse(null);
    }

    private static ChatResponse toolCall(String id, String name, Map<String, Object> input, String content) {
        ToolUseBlock call = ToolUseBlock.builder().id(id).name(name).input(input).content(content).build();
        return ChatResponse.builder().content(List.of(call)).finishReason("tool_calls").build();
    }

    /**
     * P3-BIND/F11:3 掩码三段端到端（段①/③与跨 Agent）：
     * 未绑定（＝解绑/停用后的同一代码路径）⇒ load_skill **拒载**，且该技能解锁的 MCP 工具**保持遮蔽**。
     */
    @Test
    void unboundOrCrossAgentSkillIsNotLoadableAndStaysMasked() {
        AgentSkillRegistry registry = mock(AgentSkillRegistry.class);
        AgentSkill skill = new AgentSkill(SKILL_CODE, "退货办理", "办理退货", "手册正文", List.of(MCP_TOOL));
        when(registry.listEnabled()).thenReturn(List.of(skill));
        // agent-2 的绑定子集为空（等价于"从没绑定"或"已解绑/停用后被排除"）
        when(registry.listEnabledFor("agent-2")).thenReturn(List.of());
        when(registry.findByCodeFor("agent-2", SKILL_CODE)).thenReturn(null);

        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new SkillLoadTool(registry));
        McpClientWrapper client = mock(McpClientWrapper.class);
        when(client.getName()).thenReturn("returns");
        toolkit.registerAgentTool(new McpToolProxy(McpToolBinding.of(MCP_TOOL, "提交退货", "提交退货申请", false,
                new RemoteTool(Tool.builder().name(MCP_TOOL).description("提交退货申请")
                        .inputSchema(new JsonSchema("object", Map.of(), List.of(), false, null, null))
                        .annotations(new ToolAnnotations(null, true, null, null, null, null)).build(),
                        client))));
        MaskAssertModel model = new MaskAssertModel();
        ReActAgent agent = ReActAgent.builder()
                .name("returns")
                .sysPrompt("按技能手册办理退货")
                .model(model)
                .toolkit(toolkit)
                .stateStore(new InMemoryAgentStateStore())
                .permissionContext(PermissionContextState.builder().mode(PermissionMode.BYPASS).build())
                .middleware(new AgentSkillMaskingMiddleware(registry))
                .maxIters(3)
                .build();

        Msg answer = agent.call(
                        List.of(Msg.builder().name("user").role(MsgRole.USER).textContent("帮我退货").build()),
                        RuntimeContext.builder().userId("user-b").sessionId("session-b")
                                .put(AgentSkillMaskingMiddleware.AGENT_PROFILE_ID_ATTRIBUTE, "agent-2").build())
                .block(Duration.ofSeconds(5));

        assertThat(answer).isNotNull();
        assertThat(model.loadState).as("未绑定/解绑后必须拒载").isEqualTo(ToolResultState.ERROR);
    }

    /** 段①/③ 的模型侧断言：未绑定时 MCP 工具始终不可见，load_skill 返回 ERROR。 */
    private static final class MaskAssertModel implements Model {

        private int turns;
        private ToolResultState loadState;

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            turns++;
            if (turns == 1) {
                assertThat(tools).extracting(ToolSchema::getName)
                        .contains(SkillLoadTool.TOOL_NAME)
                        .as("未绑定档案下，技能解锁的工具必须保持遮蔽")
                        .doesNotContain(MCP_TOOL);
                return Flux.just(toolCall("load-2", SkillLoadTool.TOOL_NAME,
                        Map.of("skill_code", SKILL_CODE), "{\"skill_code\":\"return_apply\"}"));
            }
            ToolResultBlock loadResult = result(messages, SkillLoadTool.TOOL_NAME);
            assertThat(loadResult).isNotNull();
            loadState = loadResult.getState();
            assertThat(tools).extracting(ToolSchema::getName).doesNotContain(MCP_TOOL);
            return Flux.just(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("结束").build()))
                    .finishReason("stop")
                    .build());
        }

        @Override
        public String getModelName() {
            return "mask-assert-model";
        }
    }

    /**
     * O-2 专属：**技能已绑定并可加载，但该技能解锁的 MCP 工具未绑定给本档案** ⇒ 工具仍不可见（不得因手册加载而放行）。
     */
    @Test
    void loadedSkillDoesNotUnmaskAnUnboundMcpTool() {
        AgentSkillRegistry registry = mock(AgentSkillRegistry.class);
        AgentSkill skill = new AgentSkill(SKILL_CODE, "退货办理", "办理退货", "手册正文", List.of(MCP_TOOL));
        when(registry.listEnabled()).thenReturn(List.of(skill));
        when(registry.listEnabledFor("agent-3")).thenReturn(List.of(skill));
        when(registry.findByCodeFor("agent-3", SKILL_CODE)).thenReturn(skill);

        AgentToolCatalog catalog = mock(AgentToolCatalog.class);
        when(catalog.mcpToolIds()).thenReturn(new java.util.LinkedHashSet<>(List.of(MCP_TOOL)));
        @SuppressWarnings("unchecked")
        final org.springframework.beans.factory.ObjectProvider<AgentToolCatalog> catalogProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(catalogProvider.getIfAvailable()).thenReturn(catalog);
        // 关键：agent-3 绑定了技能，但**没有**绑定该 MCP 工具
        AgentProfileBindingService bindingService = mock(AgentProfileBindingService.class);
        when(bindingService.boundMcpToolIds("agent-3")).thenReturn(java.util.Set.of());

        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new SkillLoadTool(registry));
        McpClientWrapper client = mock(McpClientWrapper.class);
        when(client.getName()).thenReturn("returns");
        toolkit.registerAgentTool(new McpToolProxy(McpToolBinding.of(MCP_TOOL, "提交退货", "提交退货申请", false,
                new RemoteTool(Tool.builder().name(MCP_TOOL).description("提交退货申请")
                        .inputSchema(new JsonSchema("object", Map.of(), List.of(), false, null, null))
                        .annotations(new ToolAnnotations(null, true, null, null, null, null)).build(), client))));
        UnboundMcpModel model = new UnboundMcpModel();
        ReActAgent agent = ReActAgent.builder()
                .name("returns").sysPrompt("按技能手册办理退货").model(model).toolkit(toolkit)
                .stateStore(new InMemoryAgentStateStore())
                .permissionContext(PermissionContextState.builder().mode(PermissionMode.BYPASS).build())
                .middleware(new AgentSkillMaskingMiddleware(registry, catalogProvider, bindingService))
                .maxIters(3).build();

        Msg answer = agent.call(
                        List.of(Msg.builder().name("user").role(MsgRole.USER).textContent("帮我退货").build()),
                        RuntimeContext.builder().userId("user-c").sessionId("session-c")
                                .put(AgentSkillMaskingMiddleware.AGENT_PROFILE_ID_ATTRIBUTE, "agent-3").build())
                .block(Duration.ofSeconds(5));

        assertThat(answer).isNotNull();
        assertThat(model.skillLoaded).as("技能本身应可加载（已绑定）").isTrue();
    }

    /** 每一轮都断言：未绑定的 MCP 工具**始终**不可见（即便技能已加载）。 */
    private static final class UnboundMcpModel implements Model {

        private int turns;
        private boolean skillLoaded;

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            turns++;
            assertThat(tools).extracting(ToolSchema::getName)
                    .as("未绑定的 MCP 工具在任何一轮都不得出现")
                    .doesNotContain(MCP_TOOL);
            if (turns == 1) {
                return Flux.just(toolCall("load-3", SkillLoadTool.TOOL_NAME,
                        Map.of("skill_code", SKILL_CODE), "{\"skill_code\":\"return_apply\"}"));
            }
            ToolResultBlock loadResult = result(messages, SkillLoadTool.TOOL_NAME);
            skillLoaded = loadResult != null && loadResult.getState() == ToolResultState.SUCCESS;
            return Flux.just(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("结束").build()))
                    .finishReason("stop").build());
        }

        @Override
        public String getModelName() {
            return "unbound-mcp-model";
        }
    }

    private static final class ActivationModel implements Model {

        private final AtomicInteger reasoningTurns = new AtomicInteger();
        private ToolResultBlock mcpResult;

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            reasoningTurns.incrementAndGet();
            ToolResultBlock loadResult = result(messages, SkillLoadTool.TOOL_NAME);
            if (loadResult == null) {
                assertThat(tools).extracting(ToolSchema::getName)
                        .contains(SkillLoadTool.TOOL_NAME)
                        .doesNotContain(MCP_TOOL);
                return Flux.just(toolCall("load-1", SkillLoadTool.TOOL_NAME,
                        Map.of("skill_code", SKILL_CODE), "{\"skill_code\":\"return_apply\"}"));
            }

            assertThat(loadResult.getState()).as("load_skill result: %s", loadResult.getOutput())
                    .isEqualTo(ToolResultState.SUCCESS);
            assertThat(loadResult.getMetadata()).containsEntry(
                    SkillLoadTool.LOADED_SKILL_METADATA_KEY, SKILL_CODE);
            assertThat(loadResult.getOutput()).extracting(block -> ((TextBlock) block).getText())
                    .singleElement().asString()
                    .contains("先核对订单，再提交退货申请")
                    .doesNotContain("本技能解锁的工具");
            assertThat(tools).extracting(ToolSchema::getName).contains(MCP_TOOL);

            ToolResultBlock submitResult = result(messages, MCP_TOOL);
            if (submitResult == null) {
                return Flux.just(toolCall("submit-1", MCP_TOOL, Map.of(), "{}"));
            }
            mcpResult = submitResult;
            return Flux.just(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("退货办理完成").build()))
                    .finishReason("stop")
                    .build());
        }

        @Override
        public String getModelName() {
            return "deterministic-skill-activation-model";
        }
    }
}
