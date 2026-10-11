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

package com.nageoffer.ai.ragent.rag.mq;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.integration.SaasBoundaryProperties;
import com.nageoffer.ai.ragent.framework.integration.SaasCapabilityBoundary;
import com.nageoffer.ai.ragent.framework.mq.MessageWrapper;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.entity.MessageFeedbackDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.MessageFeedbackMapper;
import com.nageoffer.ai.ragent.rag.mq.event.MessageFeedbackEvent;
import com.nageoffer.ai.ragent.rag.service.impl.MessageFeedbackServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F17-MQ（B 面接线）：消息反馈消费者在内嵌台架上的派发契约与幂等/乱序/成员授权语义。
 *
 * <p><b>本类补的是哪一块。</b>A1 的真库判据（{@code MessageFeedbackIdempotencyPostgresTest}）钉的是
 * mapper/服务层在真实 PostgreSQL 上的幂等语义；本类钉的是**消费者链路真的走到那条语义上**：
 * 开放边界 → {@code submitFeedbackByEvent} 派发；重复投递不二次插入；乱序保护由条件更新
 * （{@code update_time < submitTime}）承接；取消走占位 upsert；身份（tenant/member）取自
 * 被反馈的持久化消息行；跨用户/非助手消息在写之前拒绝。
 *
 * <p><b>为什么用 mock 而不是真库。</b>真库面已有 gated 判据（A 面）；本类要的是快、可在 CI
 * 无库环境跑的"路由/授权"判据；两者不是替代关系。lambda wrapper 的列名解析需要实体
 * {@code TableInfo}（与 {@code RW23DashboardTenantScopeTest} 同款 {@code @BeforeAll} 初始化）。
 */
@Tag("dev")
class MessageFeedbackConsumerTest {

    private static final String TENANT = "000000";

    private static final String MEMBER = "platform:000000:910101";

    private static final String USER = "910101";

    private static final String MESSAGE_ID = "msg-1";

    private final MessageFeedbackMapper feedbackMapper = mock(MessageFeedbackMapper.class);

    private final ConversationMessageMapper messageMapper = mock(ConversationMessageMapper.class);

    /** 真实服务实现（消费者实际调用的那个类），只把两个 mapper 换成替身。 */
    private final MessageFeedbackServiceImpl service =
            new MessageFeedbackServiceImpl(feedbackMapper, messageMapper, null);

    @BeforeAll
    static void initTableInfo() {
        // 单测无 Spring/MyBatis 启动：lambda wrapper 的列名解析（乱序保护断言的 SQL 片段）需要实体 TableInfo
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, MessageFeedbackDO.class);
        TableInfoHelper.initTableInfo(assistant, ConversationMessageDO.class);
    }

    @BeforeEach
    @AfterEach
    void clearPrincipal() {
        // 消费者路径没有执行主体（身份取自持久化消息行）；残留主体会让 applyFromPersistedFact 走"易主"分支
        PrincipalContext.clear();
    }

    // ------------------------------------------------------------------ 派发与身份

    @Test
    @DisplayName("派发：开放边界下事件直达服务；反馈行身份取自持久化消息行（tenant/member 回填），审计时间用事件 submitTime")
    void dispatchesAndBackfillsIdentityFromThePersistedMessageRow() {
        when(messageMapper.selectOne(any())).thenReturn(assistantMessage());
        when(feedbackMapper.selectOne(any())).thenReturn(null);

        MessageFeedbackEvent event = MessageFeedbackEvent.builder()
                .messageId(MESSAGE_ID).userId(USER).vote(1).reason("引用准确")
                .submitTime(1700000000000L).build();
        consumer(boundary(true)).onMessage(wrapperOf(event));

        ArgumentCaptor<MessageFeedbackDO> written = ArgumentCaptor.forClass(MessageFeedbackDO.class);
        verify(feedbackMapper).upsertActiveFeedback(written.capture());
        assertThat(written.getValue().getMessageId()).isEqualTo(MESSAGE_ID);
        assertThat(written.getValue().getConversationId()).isEqualTo("conv-1");
        assertThat(written.getValue().getUserId()).isEqualTo(USER);
        assertThat(written.getValue().getVote()).isEqualTo(1);
        assertThat(written.getValue().getTenantId())
                .as("身份来自已持久化消息行，不来自事件载荷").isEqualTo(TENANT);
        assertThat(written.getValue().getMemberId()).isEqualTo(MEMBER);
        assertThat(written.getValue().getCreateTime()).isEqualTo(new Date(1700000000000L));
        assertThat(written.getValue().getUpdateTime()).isEqualTo(new Date(1700000000000L));
        verify(feedbackMapper, never()).upsertCancelledFeedback(any());
    }

    // ------------------------------------------------------------------ 幂等 / 乱序

    @Test
    @DisplayName("重复投递：既有行存在 ⇒ 就地条件更新、绝不二次插入（至少一次投递下的幂等路由）")
    void duplicateDeliveryUpdatesInPlaceInsteadOfInsertingAgain() {
        when(messageMapper.selectOne(any())).thenReturn(assistantMessage());
        when(feedbackMapper.selectOne(any())).thenReturn(MessageFeedbackDO.builder().id("fb-1").build());

        MessageFeedbackEvent event = MessageFeedbackEvent.builder()
                .messageId(MESSAGE_ID).userId(USER).vote(1).submitTime(2000L).build();
        consumer(boundary(true)).onMessage(wrapperOf(event));

        verify(feedbackMapper, never()).upsertActiveFeedback(any());
        ArgumentCaptor<Wrapper> updateWrapper = wrapperCaptor();
        verify(feedbackMapper).update(any(MessageFeedbackDO.class), updateWrapper.capture());
        assertThat(updateWrapper.getValue().getSqlSegment())
                .as("乱序保护：仅当本次事件晚于既有 update_time 才允许覆盖（WHERE update_time < submitTime）")
                .contains("update_time").contains("<");
    }

    @Test
    @DisplayName("乱序事件：较早 submitTime 的事件不本地短路，仍走同一条条件更新，最终由 SQL 谓词拒绝覆写")
    void olderEventStillGoesThroughTheSameConditionalUpdate() {
        when(messageMapper.selectOne(any())).thenReturn(assistantMessage());
        when(feedbackMapper.selectOne(any())).thenReturn(MessageFeedbackDO.builder().id("fb-1").build());

        // 乱序到货：事件的 submitTime 比既有行见过的时间早（真实判定在 SQL：update_time < submitTime 为假 ⇒ 0 行）
        MessageFeedbackEvent older = MessageFeedbackEvent.builder()
                .messageId(MESSAGE_ID).userId(USER).vote(-1).submitTime(1L).build();
        consumer(boundary(true)).onMessage(wrapperOf(older));

        verify(feedbackMapper, never()).upsertActiveFeedback(any());
        ArgumentCaptor<Wrapper> updateWrapper = wrapperCaptor();
        verify(feedbackMapper).update(any(MessageFeedbackDO.class), updateWrapper.capture());
        assertThat(updateWrapper.getValue().getSqlSegment())
                .as("不提供'先判断再跳过'的本地分支：乱序保护只有 SQL 谓词一处真值来源")
                .contains("update_time").contains("<");
    }

    // ------------------------------------------------------------------ 取消

    @Test
    @DisplayName("取消事件：走取消占位 upsert（vote 由 mapper SQL 落为 0/deleted=1），身份同样取自消息行")
    void cancelEventUsesTheDedicatedCancelledUpsert() {
        when(messageMapper.selectOne(any())).thenReturn(assistantMessage());

        MessageFeedbackEvent event = MessageFeedbackEvent.builder()
                .messageId(MESSAGE_ID).userId(USER).cancelled(true).submitTime(3000L).build();
        consumer(boundary(true)).onMessage(wrapperOf(event));

        ArgumentCaptor<MessageFeedbackDO> written = ArgumentCaptor.forClass(MessageFeedbackDO.class);
        verify(feedbackMapper).upsertCancelledFeedback(written.capture());
        verify(feedbackMapper, never()).upsertActiveFeedback(any());
        assertThat(written.getValue().getVote())
                .as("取消占位：vote 置空由 mapper SQL 落为 0（读面按 deleted 过滤）").isNull();
        assertThat(written.getValue().getTenantId()).isEqualTo(TENANT);
        assertThat(written.getValue().getMemberId()).isEqualTo(MEMBER);
        assertThat(written.getValue().getUpdateTime()).isEqualTo(new Date(3000L));
    }

    // ------------------------------------------------------------------ 成员 / 目标授权

    @Test
    @DisplayName("成员授权：事件用户不是消息归属者（消息查询按 userId 限域取不到）⇒ 拒绝且零写入")
    void eventForAnotherUsersMessageIsRejectedWithZeroWrites() {
        // loadAssistantMessage 的谓词含 user_id = event.userId：他人消息在查询层就取不到
        when(messageMapper.selectOne(any())).thenReturn(null);

        MessageFeedbackEvent event = MessageFeedbackEvent.builder()
                .messageId(MESSAGE_ID).userId("910999").vote(1).submitTime(4000L).build();

        assertThatThrownBy(() -> consumer(boundary(true)).onMessage(wrapperOf(event)))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("消息不存在");
        verifyNoInteractions(feedbackMapper);
    }

    @Test
    @DisplayName("目标授权：非助手消息不接受反馈（role 门）⇒ 拒绝且零写入")
    void feedbackOnNonAssistantMessageIsRejected() {
        ConversationMessageDO userMessage = assistantMessage();
        userMessage.setRole("user");
        when(messageMapper.selectOne(any())).thenReturn(userMessage);

        MessageFeedbackEvent event = MessageFeedbackEvent.builder()
                .messageId(MESSAGE_ID).userId(USER).vote(1).submitTime(5000L).build();

        assertThatThrownBy(() -> consumer(boundary(true)).onMessage(wrapperOf(event)))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("仅支持对助手消息反馈");
        verifyNoInteractions(feedbackMapper);
    }

    // ------------------------------------------------------------------ 关闭语义（不弱化）

    @Test
    @DisplayName("关闭边界：第一行拒绝，连消息查询都不发生（关闭 = 零副作用）")
    void closedBoundaryRejectsBeforeAnyMapperAccess() {
        MessageFeedbackEvent event = MessageFeedbackEvent.builder()
                .messageId(MESSAGE_ID).userId(USER).vote(1).submitTime(6000L).build();

        SaasCapabilityBoundary.ClosedCapabilityException failure = assertThrows(
                SaasCapabilityBoundary.ClosedCapabilityException.class,
                () -> consumer(boundary(false)).onMessage(wrapperOf(event)));
        assertThat(failure.capability())
                .isEqualTo(SaasCapabilityBoundary.LegacyCapability.MESSAGE_FEEDBACK_CONSUMER);
        verifyNoInteractions(feedbackMapper, messageMapper);
    }

    // ------------------------------------------------------------------ 夹具

    private MessageFeedbackConsumer consumer(SaasCapabilityBoundary boundary) {
        return new MessageFeedbackConsumer(service, providerOf(boundary));
    }

    private static ConversationMessageDO assistantMessage() {
        return ConversationMessageDO.builder()
                .id(MESSAGE_ID)
                .conversationId("conv-1")
                .tenantId(TENANT)
                .memberId(MEMBER)
                .userId(USER)
                .role("assistant")
                .content("answer")
                .deleted(0)
                .build();
    }

    private static MessageWrapper<MessageFeedbackEvent> wrapperOf(MessageFeedbackEvent event) {
        return MessageWrapper.<MessageFeedbackEvent>builder()
                .keys(event.getUserId() + ":" + event.getMessageId())
                .body(event)
                .build();
    }

    private static SaasCapabilityBoundary boundary(boolean legacyOpen) {
        return new SaasCapabilityBoundary(new SaasBoundaryProperties(
                false, true, new SaasBoundaryProperties.CustomerApi(false), legacyOpen));
    }

    /**
     * 最小 {@link ObjectProvider} 替身：只回答"取不取得到边界"
     * （与 {@code SaasCapabilityBoundaryTest.FixedBoundaryProvider} 同形）。
     */
    private static ObjectProvider<SaasCapabilityBoundary> providerOf(SaasCapabilityBoundary boundary) {
        return new ObjectProvider<>() {
            @Override
            public SaasCapabilityBoundary getObject() {
                if (boundary == null) {
                    throw new IllegalStateException("no boundary bean");
                }
                return boundary;
            }

            @Override
            public SaasCapabilityBoundary getObject(Object... args) {
                return getObject();
            }

            @Override
            public SaasCapabilityBoundary getIfAvailable() {
                return boundary;
            }

            @Override
            public SaasCapabilityBoundary getIfUnique() {
                return boundary;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Wrapper> wrapperCaptor() {
        return ArgumentCaptor.forClass(Wrapper.class);
    }
}
