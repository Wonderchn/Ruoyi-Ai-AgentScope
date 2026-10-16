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

package com.nageoffer.ai.ragent.agent.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.agent.config.ReActAgentProvider;
import com.nageoffer.ai.ragent.agent.config.ReActAgentProvider.ActiveAgent;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentConversationDO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentConversationMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.agent.dto.AgentBlock;
import com.nageoffer.ai.ragent.agent.dto.AgentConfirmCall;
import com.nageoffer.ai.ragent.agent.dto.AgentConfirmSettlement;
import com.nageoffer.ai.ragent.agent.enums.AgentMemoryTriggerType;
import com.nageoffer.ai.ragent.agent.enums.AgentMessageStatus;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryOutcome;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryPipeline;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryProperties;
import com.nageoffer.ai.ragent.agent.service.AgentChatService;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunGate;
import com.nageoffer.ai.ragent.agent.state.PgAgentStateStore;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.ResolvedCatalog;
import com.nageoffer.ai.ragent.authorization.AiResourceWriteService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R17 W5（deny 腿）两缺陷的红/绿判据。
 *
 * <ul>
 *   <li><b>D1 工具块终态缺失</b>（{@link AgentConversationServiceImpl#settlePendingConfirm}）：
 *       拒绝结算后卡片消息里同批工具块必须进 denied，present 缺陷下永远停在 awaiting；</li>
 *   <li><b>D2 ASKING 不退出可恢复集合</b>（{@link AgentChatServiceImpl} 拒绝续跑收尾）：
 *       框架只追加 DENIED 结果、不改写 assistant tool_use 的 ASKING，轮末存盘后残留
 *       {@code ai_agent_state} 的 {@code state=asking}；收尾必须把它精确覆写成终态。</li>
 * </ul>
 *
 * <p>判据字面量锚与真机套件同源：{@code confirm-legs.py} 的 CD.2 读
 * {@code blocks[].status}（tool=denied / confirm=denied / message_status=NORMAL），CD.2 细节读
 * {@code ai_agent_state.payload->'context'[].content[].state}（不许再有 {@code asking}）。
 */
@Tag("dev")
class W5DenySettlementTest {

    // ---------------------------------------------------------------- D1 夹具（会话结算面）

    private static final String TENANT_ID = "T1";
    private static final String USER_ID = "2101";
    private static final String MEMBER_ID = "platform:" + TENANT_ID + ":" + USER_ID;
    private static final String CONVERSATION_ID = "c-2002";

    static {
        // 脱离 SqlSession 时 lambda 列名缓存是空的，条件构造器取不出 SQL 片段
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentMessageDO.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentConversationDO.class);
    }

    private AgentConversationMapper conversationMapper;
    private AgentMessageMapper messageMapper;
    private AgentConversationServiceImpl conversationService;

    // ---------------------------------------------------------------- D2 夹具（拒绝续跑面）

    private ReActAgentProvider agentProvider;
    private AgentConversationService conversationPort;
    private StreamTaskManager taskManager;
    private AgentRunGate runGate;
    private AgentMemoryProperties memoryProperties;
    private AgentMemoryPipeline memoryPipeline;
    private AtomicInteger gateReleased;
    private ReActAgent agent;
    private AgentChatService chatService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // -------- D1
        conversationMapper = mock(AgentConversationMapper.class);
        messageMapper = mock(AgentMessageMapper.class);
        PgAgentStateStore agentStateStore = mock(PgAgentStateStore.class);
        AgentRunGate d1RunGate = mock(AgentRunGate.class);
        ReActAgentProvider d1Provider = mock(ReActAgentProvider.class);
        org.springframework.beans.factory.ObjectProvider<ReActAgentProvider> agentProviderRef =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(agentProviderRef.getIfAvailable()).thenReturn(d1Provider);
        AiResourceWriteService resourceWriteService = mock(AiResourceWriteService.class);
        org.springframework.beans.factory.ObjectProvider<AiResourceWriteService> resourceWriteServiceRef =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(resourceWriteServiceRef.getIfAvailable()).thenReturn(resourceWriteService);
        conversationService = new AgentConversationServiceImpl(
                conversationMapper, messageMapper, agentStateStore, d1RunGate, agentProviderRef,
                resourceWriteServiceRef);

        // -------- D2
        agentProvider = mock(ReActAgentProvider.class);
        conversationPort = mock(AgentConversationService.class);
        taskManager = mock(StreamTaskManager.class);
        runGate = mock(AgentRunGate.class);
        agent = mock(ReActAgent.class);
        memoryProperties = new AgentMemoryProperties();
        memoryProperties.setLongTermEnabled(false);
        memoryPipeline = mock(AgentMemoryPipeline.class);
        chatService = new AgentChatServiceImpl(agentProvider, conversationPort, taskManager, runGate,
                memoryProperties, memoryPipeline,
                emptyProvider(com.nageoffer.ai.ragent.runtime.web.DeliveryPermits.class),
                emptyProvider(com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder.class),
                emptyProvider(com.nageoffer.ai.ragent.agent.memory.MemoryEgressSubjectFacts.class),
                new com.nageoffer.ai.ragent.agent.identity.AgentRunScopeRegistry());
        gateReleased = new AtomicInteger();
        when(runGate.acquire(anyString(), anyString(), anyString())).thenReturn(gateReleased::incrementAndGet);
        when(agentProvider.getAgent()).thenReturn(new ActiveAgent(
                agent, new ResolvedCatalog("知识库工具描述", null, List.of(), List.of(), List.of())));
        when(memoryPipeline.extract(anyString(), anyString(), any(AgentMemoryTriggerType.class)))
                .thenReturn(new AgentMemoryOutcome(AgentMemoryOutcome.Status.BELOW_THRESHOLD, 0, 1, false));
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
        UserContext.clear();
    }

    // ================================================================ D1

    @Test
    void denySettlementShouldDriveCardAndToolBlocksToTerminalState() {
        PrincipalContext.set(principalOf(TENANT_ID));
        AgentMessageDO message = pendingCardMessage();
        when(conversationMapper.selectOne(any())).thenReturn(existingConversation());
        when(messageMapper.selectOne(any())).thenReturn(message);

        conversationService.settlePendingConfirm(CONVERSATION_ID, USER_ID, "m-4004", false);

        AgentBlock tool = message.getBlocks().get(0);
        AgentBlock confirm = message.getBlocks().get(1);
        // 缺陷面：present 下工具块永远停在 awaiting，套件 CD.2 tool_status=denied 读红
        assertThat(tool.getStatus()).isEqualTo("denied");
        assertThat(confirm.getStatus()).isEqualTo("denied");
        assertThat(message.getMessageStatus()).isEqualTo(AgentMessageStatus.NORMAL.name());
        // 结算块要有终态才解除新提问阻塞
        verify(messageMapper).update(any(AgentMessageDO.class), any());
    }

    @Test
    void approveSettlementShouldKeepToolBlockUntouched() {
        PrincipalContext.set(principalOf(TENANT_ID));
        AgentMessageDO message = pendingCardMessage();
        when(conversationMapper.selectOne(any())).thenReturn(existingConversation());
        when(messageMapper.selectOne(any())).thenReturn(message);

        conversationService.settlePendingConfirm(CONVERSATION_ID, USER_ID, "m-4004", true);

        // 回归义务：approve 腿工具块如实留档 awaiting（新续跑消息另行呈现真实执行）
        assertThat(message.getBlocks().get(0).getStatus()).isEqualTo("awaiting");
        assertThat(message.getBlocks().get(1).getStatus()).isEqualTo("approved");
        assertThat(message.getMessageStatus()).isEqualTo(AgentMessageStatus.NORMAL.name());
    }

    @Test
    void expireSettlementShouldKeepToolBlockUntouched() {
        PrincipalContext.set(principalOf(TENANT_ID));
        AgentMessageDO message = pendingCardMessage();
        when(conversationMapper.selectOne(any())).thenReturn(existingConversation());
        when(messageMapper.selectOne(any())).thenReturn(message);

        conversationService.expirePendingConfirm(CONVERSATION_ID, USER_ID, "m-4004");

        // 回归义务：expire 腿只判卡片失效，工具块保持原样
        assertThat(message.getBlocks().get(0).getStatus()).isEqualTo("awaiting");
        assertThat(message.getBlocks().get(1).getStatus()).isEqualTo("expired");
    }

    private static AgentMessageDO pendingCardMessage() {
        return AgentMessageDO.builder()
                .id("m-4004")
                .tenantId(TENANT_ID)
                .memberId(MEMBER_ID)
                .userId(USER_ID)
                .conversationId(CONVERSATION_ID)
                .role("assistant")
                .replyToMessageId("m-3003")
                .messageStatus(AgentMessageStatus.AWAITING_CONFIRM.name())
                .blocks(new ArrayList<>(List.of(
                        AgentBlock.builder().kind("tool").name("flush_memory")
                                .displayName("记忆整理").status("awaiting").toolCallId("tc-148479859").build(),
                        AgentBlock.builder().kind("confirm").status("pending")
                                .calls(List.of(AgentConfirmCall.builder().toolCallId("tc-148479859")
                                        .name("flush_memory").displayName("记忆整理").build()))
                                .build())))
                .build();
    }

    // ================================================================ D2

    @Test
    void denyResumeShouldClearResidualAskingState() {
        UserContext.set(LoginUser.builder().userId(USER_ID).username("tester").build());
        AgentState state = askingState();
        when(agent.getAgentState(USER_ID, CONVERSATION_ID)).thenReturn(state);
        when(conversationPort.getPendingConfirm(CONVERSATION_ID, USER_ID, "m-4004"))
                .thenReturn(new AgentConfirmSettlement("会话标题", "m-3003"));
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.empty());

        chatService.confirmPendingTool(CONVERSATION_ID, "m-4004", false, new SseEmitter());

        // 缺陷面：present 下 ASKING 残留、且没人覆写存盘，套件读 ai_agent_state 得 asking_rows=1
        ToolUseBlock after = state.getContext().get(0).getContentBlocks(ToolUseBlock.class).get(0);
        assertThat(after.getState()).isNotEqualTo(ToolCallState.ASKING);
        assertThat(after.getState()).isEqualTo(ToolCallState.FINISHED);
        verify(agent).saveAgentState(USER_ID, CONVERSATION_ID);
        assertThat(gateReleased.get()).isOne();
    }

    @Test
    void approveResumeShouldNotRewriteAskingState() {
        UserContext.set(LoginUser.builder().userId(USER_ID).username("tester").build());
        AgentState state = askingState();
        when(agent.getAgentState(USER_ID, CONVERSATION_ID)).thenReturn(state);
        when(conversationPort.getPendingConfirm(CONVERSATION_ID, USER_ID, "m-4004"))
                .thenReturn(new AgentConfirmSettlement("会话标题", "m-3003"));
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.empty());

        chatService.confirmPendingTool(CONVERSATION_ID, "m-4004", true, new SseEmitter());

        // 回归义务：approve 腿的 ASKING 由框架真跑覆盖，本包不得额外改写或补存盘
        ToolUseBlock after = state.getContext().get(0).getContentBlocks(ToolUseBlock.class).get(0);
        assertThat(after.getState()).isEqualTo(ToolCallState.ASKING);
        verify(agent, never()).saveAgentState(USER_ID, CONVERSATION_ID);
        assertThat(gateReleased.get()).isOne();
    }

    private static AgentState askingState() {
        ToolUseBlock asking = ToolUseBlock.builder().id("tc-148479859").name("flush_memory")
                .input(Map.of()).state(ToolCallState.ASKING).build();
        return AgentState.builder()
                .userId(USER_ID)
                .sessionId(CONVERSATION_ID)
                .addMessage(AssistantMessage.builder().content(asking).build())
                .build();
    }

    // ================================================================ 公共夹具

    private static AgentConversationDO existingConversation() {
        return AgentConversationDO.builder()
                .id("1")
                .tenantId(TENANT_ID)
                .memberId(MEMBER_ID)
                .userId(USER_ID)
                .conversationId(CONVERSATION_ID)
                .title("原会话")
                .build();
    }

    private static ExecutionPrincipal principalOf(String tenantId) {
        return new ExecutionPrincipal(
                tenantId, USER_ID, "platform:" + tenantId + ":" + USER_ID, 7, 3,
                Set.of(), "jti-" + tenantId, "platform", 1_700_000_000L, 1_700_000_060L);
    }

    /** 归属/出站端口在判据里缺席即可：本类不铺对应行为。 */
    private static <T> org.springframework.beans.factory.ObjectProvider<T> emptyProvider(Class<T> type) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public T getObject() {
                throw new IllegalStateException("no " + type.getSimpleName());
            }

            @Override
            public T getObject(Object... args) {
                return getObject();
            }

            @Override
            public T getIfAvailable() {
                return null;
            }

            @Override
            public T getIfUnique() {
                return null;
            }
        };
    }
}
