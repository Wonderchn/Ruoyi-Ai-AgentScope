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

package com.nageoffer.ai.ragent.rag.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.authorization.TenantConversationReadRepository;
import com.nageoffer.ai.ragent.rag.service.ConversationMessageService;
import com.nageoffer.ai.ragent.rag.service.bo.ConversationMessageBO;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.port.ChatAttachmentPort;
import com.nageoffer.ai.ragent.runtime.port.ConversationTurnMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F04 brief8：RAG 附件回合（用户消息 → 绑定 → 助手消息）的**事务整体性**判据。
 *
 * <p>{@code ConversationHistoryAdapter.appendAttachedTurn} 在 refs 非空时把三条写
 * （user 消息、{@code ai_chat_message_attachment} 绑定、assistant 消息）包在**同一个**
 * {@code TransactionTemplate} 里。本类在事务边界上抓真实行为（记录 begin/commit/rollback
 * 与写入顺序），而不是读源码：</p>
 * <ol>
 *   <li>有附件 → 恰好一次事务、一次提交、写入顺序 user → bind → assistant，且绑定的是
 *       本次插入的 user 消息 id（不是 runId/会话 id）；</li>
 *   <li>bind 失败或 assistant 写入失败 → 事务回滚且**不再**发生后续写入（整轮不留半截历史）；</li>
 *   <li>无附件 → 不开附件事务（与原语义一致），也不调用绑定端口；</li>
 *   <li>有附件但缺事务管理器/缺附件端口 → 在写入前（或写 user 后立刻）fail-closed，
 *       不静默丢绑定。</li>
 * </ol>
 */
@Tag("dev")
class ConversationHistoryAttachmentTransactionTest {

    private static final String TENANT = "T1";
    private static final String MEMBER = "platform:T1:2101";
    private static final String USER = "2101";
    private static final String CONVERSATION = "conv-1";
    private static final String ATTACHMENT = "att-0123456789abcdef0123456789abcdef";

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> order = new ArrayList<>();

    private ConversationMessageService messages;
    private ChatAttachmentPort attachments;
    private RecordingTransactions transactions;
    private ConversationHistoryAdapter adapter;

    /** 记录事务边界的替身：不开真事务，但把 begin/commit/rollback 变成可断言事实。 */
    private static final class RecordingTransactions implements PlatformTransactionManager {
        int begun;
        int committed;
        int rolledBack;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            begun++;
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            committed++;
        }

        @Override
        public void rollback(TransactionStatus status) {
            rolledBack++;
        }
    }

    private static void set(Object target, String name, Object value) {
        for (var field : target.getClass().getDeclaredFields()) {
            if (field.getName().equals(name)) {
                field.setAccessible(true);
                try {
                    field.set(target, value);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
                return;
            }
        }
        throw new IllegalStateException("field " + name + " not found on " + target.getClass().getSimpleName());
    }

    @BeforeEach
    void setUp() {
        messages = mock(ConversationMessageService.class);
        attachments = mock(ChatAttachmentPort.class);
        transactions = new RecordingTransactions();
        when(messages.addMessage(any())).thenAnswer(invocation -> {
            ConversationMessageBO message = invocation.getArgument(0);
            order.add(message.getRole());
            return "user".equals(message.getRole()) ? "m-user-1" : "m-assistant-1";
        });
        doAnswer(invocation -> {
            order.add("bind");
            return null;
        }).when(attachments).bind(any(), any(), any(), any(), any(), any(), any());

        TenantConversationReadRepository conversations = mock(TenantConversationReadRepository.class);
        when(conversations.findConversation(eq(TENANT), eq(MEMBER), eq(CONVERSATION)))
                .thenReturn(Optional.of(new TenantConversationReadRepository.ConversationRow(
                        CONVERSATION, "title", null)));

        adapter = new ConversationHistoryAdapter(messages, conversations);
        set(adapter, "attachments", attachments);
        set(adapter, "attachmentTransactions", transactions);
    }

    /** Restub via the doAnswer() style so Mockito does not invoke the previous answer with a null arg. */
    private void answerMessages(java.util.function.Function<ConversationMessageBO, String> answer) {
        doAnswer(invocation -> answer.apply(invocation.getArgument(0))).when(messages).addMessage(any());
    }

    private JsonNode refs() {
        try {
            return json.readTree("[{\"id\":\"" + ATTACHMENT + "\",\"version\":1}]");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void append(JsonNode refs) {
        adapter.appendAttachedTurn(TENANT, MEMBER, USER, CONVERSATION, "question", "answer",
                ConversationTurnMeta.EMPTY, refs);
    }

    @Test
    @DisplayName("有附件：一个事务内按 user → bind → assistant 写入，绑定本次 user 消息 id")
    void attachedTurnWritesUserBindAssistantInsideOneTransaction() throws Exception {
        append(refs());

        assertThat(order).containsExactly("user", "bind", "assistant");
        assertThat(transactions.begun).isEqualTo(1);
        assertThat(transactions.committed).isEqualTo(1);
        assertThat(transactions.rolledBack).isZero();

        ArgumentCaptor<String> boundMessage = ArgumentCaptor.forClass(String.class);
        verify(attachments).bind(eq(TENANT), eq(MEMBER), eq(USER), eq("RAG"), eq(CONVERSATION),
                boundMessage.capture(), any(JsonNode.class));
        assertThat(boundMessage.getValue())
                .as("绑定的是本次插入的 user 消息 id，不是 runId/会话 id")
                .isEqualTo("m-user-1");

        ArgumentCaptor<ConversationMessageBO> assistant = ArgumentCaptor.forClass(ConversationMessageBO.class);
        verify(messages, org.mockito.Mockito.times(2)).addMessage(assistant.capture());
        assertThat(assistant.getAllValues().get(1).getRole()).isEqualTo("assistant");
        assertThat(assistant.getAllValues().get(1).getReplyToMessageId()).isEqualTo("m-user-1");
    }

    @Test
    @DisplayName("bind 失败：整轮回滚，且不写 assistant 消息")
    void bindFailureRollsBackAndNeverWritesTheAssistantMessage() {
        doThrow(new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN))
                .when(attachments).bind(any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> append(refs()))
                .isInstanceOf(RunApiException.class);

        assertThat(order).containsExactly("user");
        assertThat(transactions.begun).isEqualTo(1);
        assertThat(transactions.committed).isZero();
        assertThat(transactions.rolledBack).isEqualTo(1);
        verify(messages, org.mockito.Mockito.times(1)).addMessage(any());
    }

    @Test
    @DisplayName("assistant 写入失败：整轮回滚（user 消息与绑定不落库）")
    void assistantWriteFailureRollsBackTheWholeTurn() {
        answerMessages(message -> {
            if ("assistant".equals(message.getRole())) {
                throw new IllegalStateException("insert failed");
            }
            order.add(message.getRole());
            return "m-user-1";
        });

        assertThatThrownBy(() -> append(refs()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("insert failed");

        assertThat(order).containsExactly("user", "bind");
        assertThat(transactions.begun).isEqualTo(1);
        assertThat(transactions.committed).isZero();
        assertThat(transactions.rolledBack).isEqualTo(1);
    }

    @Test
    @DisplayName("无附件：不开附件事务，也不调用绑定端口（原语义零变化）")
    void turnWithoutAttachmentsDoesNotOpenTheAttachmentTransaction() {
        append(null);

        assertThat(order).containsExactly("user", "assistant");
        assertThat(transactions.begun).isZero();
        assertThat(transactions.committed).isZero();
        assertThat(transactions.rolledBack).isZero();
        verify(attachments, never()).bind(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("有附件但缺事务管理器：写入前 fail-closed，零写入")
    void refsWithoutTheAttachmentTransactionAreRefusedBeforeWriting() {
        set(adapter, "attachmentTransactions", null);

        assertThatThrownBy(() -> append(refs()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction");

        assertThat(order).isEmpty();
        verifyNoInteractions(messages);
        verify(attachments, never()).bind(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("有附件但缺附件端口：响亮失败并回滚（不静默丢绑定继续写历史）")
    void refsWithoutTheAttachmentPortRollBackTheTurn() {
        set(adapter, "attachments", null);

        assertThatThrownBy(() -> append(refs()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("port");

        assertThat(order).containsExactly("user");
        assertThat(transactions.rolledBack).isEqualTo(1);
        assertThat(transactions.committed).isZero();
    }
}
