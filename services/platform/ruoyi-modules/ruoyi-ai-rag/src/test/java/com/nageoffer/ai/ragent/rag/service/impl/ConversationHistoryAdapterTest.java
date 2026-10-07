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

import com.nageoffer.ai.ragent.authorization.TenantConversationReadRepository;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.rag.service.ConversationMessageService;
import com.nageoffer.ai.ragent.rag.service.bo.ConversationMessageBO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RW-01-CHATHIST：{@link ConversationHistoryAdapter} —— "写路径写入读路径所读的表" 的落点判据。
 *
 * <p>本组钉住四件事：
 * <ol>
 *   <li><b>真的写进会话历史</b>：一轮问答 = 用户消息 + 助手消息，助手消息回指用户消息 id，
 *       且两条都带 {@code conversationId}/{@code userId}/{@code messageStatus=NORMAL}；</li>
 *   <li><b>写入期间必须有主体</b>：{@code ConversationMessageService.addMessage} 与
 *       {@code AiDomainWriteIdentity} 都要求 {@code PrincipalContext}，而执行器跑在
 *       {@code RunWorker} 的工作线程上（那里<b>没有</b>主体）——适配器必须从端口入参的
 *       run 权威身份临时绑定，且**写完必须还原**（不把主体泄漏给同线程的后续任务）；</li>
 *   <li><b>归属校验复用读路径那一份</b>：会话不属于该主体（含跨租户）⇒ 拒绝写入，
 *       且<b>一条都不写</b>（不是"部分写入"）；</li>
 *   <li><b>缺身份即拒绝</b>：tenant/member/user 任一为空都不写（不落默认租户、
 *       不写"无成员"哨兵）。</li>
 * </ol>
 */
@Tag("dev")
class ConversationHistoryAdapterTest {

    private static final String TENANT = "t1";
    private static final String OTHER_TENANT = "t2";
    private static final String MEMBER = "platform:t1:1001";
    private static final String USER = "1001";
    private static final String CONVERSATION = "conv-1";

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    private ConversationMessageService history() {
        return mock(ConversationMessageService.class);
    }

    private TenantConversationReadRepository conversationsOwnedBy(String tenant, String member) {
        TenantConversationReadRepository repository = mock(TenantConversationReadRepository.class);
        when(repository.findConversation(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> tenant.equals(invocation.getArgument(0))
                        && member.equals(invocation.getArgument(1))
                        ? Optional.of(new TenantConversationReadRepository.ConversationRow(
                                invocation.getArgument(2), "synthetic", Timestamp.from(Instant.now())))
                        : Optional.empty());
        return repository;
    }

    @Test
    @DisplayName("一轮问答写成两条历史：用户消息在前、助手消息回指它，且都带会话与用户")
    void appendTurnWritesUserThenAssistantIntoTheConversationHistory() {
        ConversationMessageService history = history();
        when(history.addMessage(any())).thenReturn("msg-user-1", "msg-assistant-1");

        new ConversationHistoryAdapter(history, conversationsOwnedBy(TENANT, MEMBER))
                .appendTurn(TENANT, MEMBER, USER, CONVERSATION, "问题", "答案");

        ArgumentCaptor<ConversationMessageBO> captor = ArgumentCaptor.forClass(ConversationMessageBO.class);
        verify(history, org.mockito.Mockito.times(2)).addMessage(captor.capture());
        List<ConversationMessageBO> written = captor.getAllValues();

        ConversationMessageBO user = written.get(0);
        assertEquals("user", user.getRole());
        assertEquals("问题", user.getContent());
        assertEquals(CONVERSATION, user.getConversationId());
        assertEquals(USER, user.getUserId());
        assertEquals("NORMAL", user.getMessageStatus());
        assertNull(user.getReplyToMessageId(), "用户消息不应回指任何消息");

        ConversationMessageBO assistant = written.get(1);
        assertEquals("assistant", assistant.getRole());
        assertEquals("答案", assistant.getContent());
        assertEquals(CONVERSATION, assistant.getConversationId());
        assertEquals(USER, assistant.getUserId());
        assertEquals("msg-user-1", assistant.getReplyToMessageId(), "助手消息必须回指本轮用户消息");
    }

    @Test
    @DisplayName("写入期间绑定 run 权威身份，且写完还原（不把主体泄漏到工作线程）")
    void appendTurnBindsRunIdentityOnlyForTheDurationOfTheWrite() {
        ConversationMessageService history = history();
        when(history.addMessage(any())).thenAnswer(invocation -> {
            ExecutionPrincipal bound = PrincipalContext.get();
            assertNotNull(bound, "写入期间必须有主体，否则 AiDomainWriteIdentity 会 fail-closed");
            assertEquals(TENANT, bound.tenantId());
            assertEquals(USER, bound.userId());
            assertEquals(MEMBER, bound.membershipId());
            return "msg-1";
        });

        assertNull(PrincipalContext.get(), "前置：工作线程本来没有主体");

        new ConversationHistoryAdapter(history, conversationsOwnedBy(TENANT, MEMBER))
                .appendTurn(TENANT, MEMBER, USER, CONVERSATION, "q", "a");

        assertNull(PrincipalContext.get(), "写完后必须还原：主体不得留在工作线程上");
    }

    @Test
    @DisplayName("会话不属于该主体（含跨租户）⇒ 拒绝写入，且一条都不写")
    void appendTurnRefusesWhenTheConversationIsNotOwnedByTheSubject() {
        ConversationMessageService history = history();
        TenantConversationReadRepository repository = conversationsOwnedBy(TENANT, MEMBER);

        // 同租户但不同成员：读路径同样读不到（"不存在"外显），写路径据此拒绝
        assertThrows(IllegalStateException.class, () -> new ConversationHistoryAdapter(history, repository)
                .appendTurn(TENANT, "platform:t1:2002", "2002", CONVERSATION, "q", "a"));

        // 跨租户：用 t2 的身份去写 t1 的会话
        assertThrows(IllegalStateException.class, () -> new ConversationHistoryAdapter(history, repository)
                .appendTurn(OTHER_TENANT, "platform:t2:1001", USER, CONVERSATION, "q", "a"));

        verifyNoInteractions(history);
    }

    @Test
    @DisplayName("缺 tenant/member/user/conversationId 任一 ⇒ 拒绝写入（不落默认租户、不写哨兵成员）")
    void appendTurnRefusesWithoutFullIdentity() {
        ConversationMessageService history = history();
        TenantConversationReadRepository repository = conversationsOwnedBy(TENANT, MEMBER);
        ConversationHistoryAdapter adapter = new ConversationHistoryAdapter(history, repository);

        assertThrows(IllegalStateException.class, () -> adapter.appendTurn("", MEMBER, USER, CONVERSATION, "q", "a"));
        assertThrows(IllegalStateException.class, () -> adapter.appendTurn(TENANT, "", USER, CONVERSATION, "q", "a"));
        assertThrows(IllegalStateException.class, () -> adapter.appendTurn(TENANT, MEMBER, "", CONVERSATION, "q", "a"));
        assertThrows(IllegalStateException.class, () -> adapter.appendTurn(TENANT, MEMBER, USER, "", "q", "a"));

        verifyNoInteractions(history);
        verify(repository, never()).findConversation(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("端口是 ai-runtime 的接口（方向约束：ai-rag 依赖 ai-runtime，不能反向）")
    void portLivesInTheRuntimeModule() {
        assertEquals("com.nageoffer.ai.ragent.runtime.port.ConversationHistoryPort",
                com.nageoffer.ai.ragent.runtime.port.ConversationHistoryPort.class.getName());
        assertEquals(Set.of("appendTurn"), java.util.Arrays.stream(
                        com.nageoffer.ai.ragent.runtime.port.ConversationHistoryPort.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).collect(java.util.stream.Collectors.toSet()));
    }
}
