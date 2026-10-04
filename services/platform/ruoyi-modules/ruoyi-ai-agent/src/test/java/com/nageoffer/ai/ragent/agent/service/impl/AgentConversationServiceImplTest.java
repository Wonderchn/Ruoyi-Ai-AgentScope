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
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.agent.config.ReActAgentProvider;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentConversationDO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentConversationMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.agent.dto.AgentBlock;
import com.nageoffer.ai.ragent.agent.dto.AgentConfirmSettlement;
import com.nageoffer.ai.ragent.agent.enums.AgentMessageStatus;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunGate;
import com.nageoffer.ai.ragent.agent.state.PgAgentStateStore;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Tag;

@Tag("dev")
class AgentConversationServiceImplTest {

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
    private PgAgentStateStore agentStateStore;
    private AgentRunGate runGate;
    private ReActAgentProvider agentProvider;
    private AgentConversationServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        conversationMapper = mock(AgentConversationMapper.class);
        messageMapper = mock(AgentMessageMapper.class);
        agentStateStore = mock(PgAgentStateStore.class);
        runGate = mock(AgentRunGate.class);
        agentProvider = mock(ReActAgentProvider.class);
        ObjectProvider<ReActAgentProvider> agentProviderRef = mock(ObjectProvider.class);
        when(agentProviderRef.getIfAvailable()).thenReturn(agentProvider);
        when(conversationMapper.delete(any())).thenReturn(1);
        when(messageMapper.delete(any())).thenReturn(1);
        service = new AgentConversationServiceImpl(
                conversationMapper, messageMapper, agentStateStore, runGate, agentProviderRef);
        // P1.3d：每个入口都要从执行主体解析 (tenant, member)，这里统一给出
        PrincipalContext.set(principalOf(TENANT_ID));
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        PrincipalContext.clear();
    }

    /**
     * 与 P1ObjectOwnershipTest.principalOf 同一写法：完整合法主体，membership 为 canonical 引用
     */
    private static ExecutionPrincipal principalOf(String tenantId) {
        return new ExecutionPrincipal(
                tenantId, USER_ID, "platform:" + tenantId + ":" + USER_ID, 7, 3,
                Set.of(), "jti-" + tenantId, "platform", 1_700_000_000L, 1_700_000_060L);
    }

    @Test
    void shouldEvictAgentStateCacheWhenConversationDeleted() {
        service.delete(CONVERSATION_ID, USER_ID);

        // 表清了内存不清，单例 Agent 会带着已删记忆继续对话并把状态写回 PG
        verify(agentProvider).evictStateCache(USER_ID, CONVERSATION_ID);
    }

    @Test
    void shouldDeleteOnlyWithinTenantScope() {
        service.delete(CONVERSATION_ID, USER_ID);

        // 删除谓词必须钉死租户：id/conversationId 只是租户内的引用
        ArgumentCaptor<Wrapper<AgentConversationDO>> conversationWhere = wrapperCaptor();
        verify(conversationMapper).delete(conversationWhere.capture());
        assertThat(conversationWhere.getValue().getSqlSegment())
                .contains("tenant_id").contains("member_id")
                .contains("conversation_id").contains("user_id");
        ArgumentCaptor<Wrapper<AgentMessageDO>> messageWhere = wrapperCaptor();
        verify(messageMapper).delete(messageWhere.capture());
        assertThat(messageWhere.getValue().getSqlSegment())
                .contains("tenant_id").contains("member_id")
                .contains("conversation_id");
    }

    @Test
    void shouldEvictEachConversationWhenBatchDeleted() {
        service.deleteBatch(List.of(CONVERSATION_ID, "c-3003", CONVERSATION_ID), USER_ID);

        // 重复 ID 去重后每个会话各驱逐一次
        verify(agentProvider, times(1)).evictStateCache(USER_ID, CONVERSATION_ID);
        verify(agentProvider, times(1)).evictStateCache(USER_ID, "c-3003");
    }

    @Test
    void shouldEvictOnlyAfterTransactionCommits() {
        TransactionSynchronizationManager.initSynchronization();

        service.delete(CONVERSATION_ID, USER_ID);

        // 事务还没提就驱逐内存，一旦回滚就成了表还在记忆没了
        verify(agentProvider, never()).evictStateCache(USER_ID, CONVERSATION_ID);
        commitCurrentTransaction();
        verify(agentProvider).evictStateCache(USER_ID, CONVERSATION_ID);
    }

    @Test
    void shouldEvictBatchAfterSingleCommit() {
        TransactionSynchronizationManager.initSynchronization();

        service.deleteBatch(List.of(CONVERSATION_ID, "c-3003"), USER_ID);

        verify(agentProvider, never()).evictStateCache(any(), any());
        commitCurrentTransaction();
        verify(agentProvider, times(1)).evictStateCache(USER_ID, CONVERSATION_ID);
        verify(agentProvider, times(1)).evictStateCache(USER_ID, "c-3003");
    }

    @Test
    void shouldRejectDeleteWhileConversationIsRunning() {
        when(runGate.runningTaskId(USER_ID, CONVERSATION_ID)).thenReturn("t-9001");

        assertThatThrownBy(() -> service.delete(CONVERSATION_ID, USER_ID))
                .hasMessageContaining("正在生成中");

        // 放行就会让在途流把状态和消息写回已删会话，留下够不着的残行
        verify(conversationMapper, never()).delete(any());
        verify(messageMapper, never()).delete(any());
        verify(agentStateStore, never()).delete(any(), any());
    }

    @Test
    void shouldAllowDeleteWhenAnotherConversationIsRunning() {
        // 该用户确实有流在跑，但跑的是别的会话，不该连累这一个
        when(runGate.runningTaskId(USER_ID, CONVERSATION_ID)).thenReturn(null);

        service.delete(CONVERSATION_ID, USER_ID);

        verify(conversationMapper).delete(any());
        verify(agentProvider).evictStateCache(USER_ID, CONVERSATION_ID);
    }

    @Test
    void shouldRejectWholeBatchWhenOneConversationIsRunning() {
        TransactionSynchronizationManager.initSynchronization();
        when(runGate.runningTaskId(USER_ID, "c-3003")).thenReturn("t-9001");

        assertThatThrownBy(() -> service.deleteBatch(List.of(CONVERSATION_ID, "c-3003"), USER_ID))
                .hasMessageContaining("正在生成中");

        // 整批一个事务，挡下一个就全回滚，驱逐缓存不该走到
        verify(agentProvider, never()).evictStateCache(any(), any());
    }

    @Test
    void shouldReadConfirmationContextWithoutSettlingCard() {
        when(conversationMapper.selectOne(any())).thenReturn(existingConversation("原会话"));
        AgentMessageDO message = pendingConfirmation();
        when(messageMapper.selectOne(any())).thenReturn(message);

        AgentConfirmSettlement context = service.getPendingConfirm(CONVERSATION_ID, USER_ID, "m-4004");

        assertThat(context.title()).isEqualTo("原会话");
        assertThat(context.replyToMessageId()).isEqualTo("m-3003");
        assertThat(message.getMessageStatus()).isEqualTo(AgentMessageStatus.AWAITING_CONFIRM.name());
        assertThat(message.getBlocks().get(0).getStatus()).isEqualTo("pending");
        verify(messageMapper, never()).update(any(AgentMessageDO.class), any());
    }

    @Test
    void shouldRevalidateCardWhenSettlingAfterRead() {
        when(conversationMapper.selectOne(any())).thenReturn(existingConversation("原会话"));
        AgentMessageDO message = pendingConfirmation();
        when(messageMapper.selectOne(any())).thenReturn(message);
        service.getPendingConfirm(CONVERSATION_ID, USER_ID, "m-4004");
        message.setMessageStatus(AgentMessageStatus.NORMAL.name());

        assertThatThrownBy(() -> service.settlePendingConfirm(CONVERSATION_ID, USER_ID, "m-4004", true))
                .hasMessageContaining("已处理");

        verify(messageMapper, never()).update(any(AgentMessageDO.class), any());
    }

    private static AgentMessageDO pendingConfirmation() {
        AgentMessageDO message = assistantRow("m-4004", "m-3003", "确认操作", AgentMessageStatus.AWAITING_CONFIRM);
        message.setBlocks(List.of(AgentBlock.builder().kind("confirm").status("pending").build()));
        return message;
    }

    @Test
    void shouldPurgeResidueWhenConversationRecreated() {
        // 会话行查不到就要建：同号的状态与消息只可能是删除后的残骸
        when(conversationMapper.selectOne(any())).thenReturn(null);

        service.touchConversation(CONVERSATION_ID, USER_ID, "本轮提问");

        verify(agentStateStore).delete(USER_ID, CONVERSATION_ID);
        ArgumentCaptor<Wrapper<AgentMessageDO>> residueWhere = wrapperCaptor();
        verify(messageMapper).delete(residueWhere.capture());
        // 残骸清理也只能清本租户的：同号残骸在他租户是别人的活跃会话
        assertThat(residueWhere.getValue().getSqlSegment())
                .contains("tenant_id").contains("member_id").contains("conversation_id");
        verify(agentProvider).evictStateCache(USER_ID, CONVERSATION_ID);
    }

    @Test
    void shouldPurgeBeforeCreatingConversationRow() {
        when(conversationMapper.selectOne(any())).thenReturn(null);
        InOrder order = inOrder(agentStateStore, conversationMapper);

        service.touchConversation(CONVERSATION_ID, USER_ID, "本轮提问");

        // 清失败就不该建行：留下「会话行是新的、记忆是旧的」比不建更糟，重试还会再清一遍
        order.verify(agentStateStore).delete(USER_ID, CONVERSATION_ID);
        ArgumentCaptor<AgentConversationDO> inserted = ArgumentCaptor.forClass(AgentConversationDO.class);
        order.verify(conversationMapper).insert(inserted.capture());
        // 归属列必须来自执行主体：V4 起非空，漏写直接落不了库，更不许落成他租户的行
        assertThat(inserted.getValue().getTenantId()).isEqualTo(TENANT_ID);
        assertThat(inserted.getValue().getMemberId()).isEqualTo(MEMBER_ID);
    }

    @Test
    void shouldNotPurgeWhenConversationStillAlive() {
        when(conversationMapper.selectOne(any())).thenReturn(existingConversation("老会话"));

        service.touchConversation(CONVERSATION_ID, USER_ID, "本轮提问");

        // 会话还活着，清就是把用户的记忆和消息删了
        verify(agentStateStore, never()).delete(any(), any());
        verify(messageMapper, never()).delete(any());
        verify(agentProvider, never()).evictStateCache(any(), any());
    }

    @Test
    void shouldDeclareTransactionOnDeletePaths() throws NoSuchMethodException {
        // 三步删中途失败会留半删状态，注解掉了就没人拦
        assertThat(AgentConversationServiceImpl.class
                .getDeclaredMethod("delete", String.class, String.class)
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(AgentConversationServiceImpl.class
                .getDeclaredMethod("deleteBatch", List.class, String.class)
                .getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void shouldReturnExistingTitleWhenInsertLosesRace() {
        // 并发首问：两侧都查空各自插入，落败方撞唯一索引
        when(conversationMapper.selectOne(any())).thenReturn(null, existingConversation("赢家标题"));
        when(conversationMapper.insert(any(AgentConversationDO.class)))
                .thenThrow(new DuplicateKeyException("uk_agent_conversation_user"));

        String title = service.touchConversation(CONVERSATION_ID, USER_ID, "本轮提问");

        assertThat(title).isEqualTo("赢家标题");
        verify(conversationMapper, times(2)).selectOne(any());
    }

    @Test
    void shouldRethrowWhenDuplicateKeyIsNotRecoverable() {
        // 撞键却重查不到，说明冲突另有来源，不能吞掉
        when(conversationMapper.selectOne(any())).thenReturn(null, (AgentConversationDO) null);
        when(conversationMapper.insert(any(AgentConversationDO.class)))
                .thenThrow(new DuplicateKeyException("uk_agent_conversation_user"));

        assertThatThrownBy(() -> service.touchConversation(CONVERSATION_ID, USER_ID, "本轮提问"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    // ---------------------------------------------------------- P1.3d 主体与写入归属

    @Test
    void shouldStampTenantScopeOnInsertedUserMessage() {
        service.addUserMessage(CONVERSATION_ID, USER_ID, "你好");

        ArgumentCaptor<AgentMessageDO> inserted = ArgumentCaptor.forClass(AgentMessageDO.class);
        verify(messageMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getTenantId()).isEqualTo(TENANT_ID);
        assertThat(inserted.getValue().getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(inserted.getValue().getUserId()).isEqualTo(USER_ID);
    }

    @Test
    void shouldStampTenantScopeOnInsertedAssistantMessage() {
        service.addAssistantMessage(CONVERSATION_ID, USER_ID, "答复", null, null, null,
                AgentMessageStatus.NORMAL, 12L);

        ArgumentCaptor<AgentMessageDO> inserted = ArgumentCaptor.forClass(AgentMessageDO.class);
        verify(messageMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getTenantId()).isEqualTo(TENANT_ID);
        assertThat(inserted.getValue().getMemberId()).isEqualTo(MEMBER_ID);
    }

    @Test
    void shouldRejectEveryEntryWithoutPrincipal() {
        PrincipalContext.clear();

        // 无主体一律拒绝：宁可失败也不落成跨租户可见的行
        assertThatThrownBy(() -> service.touchConversation(CONVERSATION_ID, USER_ID, "q"))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> service.addUserMessage(CONVERSATION_ID, USER_ID, "q"))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> service.addAssistantMessage(CONVERSATION_ID, USER_ID, "a", null,
                null, null, AgentMessageStatus.NORMAL, null))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> service.listByUserId(USER_ID)).isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> service.listMessages(CONVERSATION_ID, USER_ID))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> service.hasPendingConfirm(CONVERSATION_ID, USER_ID))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> service.getPendingConfirm(CONVERSATION_ID, USER_ID, "m-1"))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> service.settlePendingConfirm(CONVERSATION_ID, USER_ID, "m-1", true))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> service.expirePendingConfirm(CONVERSATION_ID, USER_ID, "m-1"))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> service.rename(CONVERSATION_ID, USER_ID, "新名字"))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> service.delete(CONVERSATION_ID, USER_ID)).isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> service.deleteBatch(List.of(CONVERSATION_ID), USER_ID))
                .isInstanceOf(ClientException.class);

        // 拒绝发生在任何 DAO 访问之前
        verifyNoMapperInteractions();
    }

    private void verifyNoMapperInteractions() {
        verifyNoInteractions(conversationMapper, messageMapper, agentStateStore, runGate, agentProvider);
    }

    /**
     * Wrapper 泛型捕获器：MyBatis-Plus 的条件谓词整体作为一个参数进出 mock
     */
    @SuppressWarnings("unchecked")
    private static <T> ArgumentCaptor<Wrapper<T>> wrapperCaptor() {
        return (ArgumentCaptor<Wrapper<T>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Wrapper.class);
    }

    private static AgentMessageDO assistantRow(String id, String replyTo, String content, AgentMessageStatus status) {
        return AgentMessageDO.builder()
                .id(id)
                .tenantId(TENANT_ID)
                .memberId(MEMBER_ID)
                .userId(USER_ID)
                .role("assistant")
                .content(content)
                .replyToMessageId(replyTo)
                .messageStatus(status.name())
                .build();
    }

    /**
     * 模拟事务提交：驱动已注册的同步回调走 afterCommit
     */
    private void commitCurrentTransaction() {
        List.copyOf(TransactionSynchronizationManager.getSynchronizations())
                .forEach(TransactionSynchronization::afterCommit);
    }

    private AgentConversationDO existingConversation(String title) {
        return AgentConversationDO.builder()
                .id("conv-row-1")
                .tenantId(TENANT_ID)
                .memberId(MEMBER_ID)
                .conversationId(CONVERSATION_ID)
                .userId(USER_ID)
                .title(title)
                .lastTime(new Date())
                .build();
    }
}
