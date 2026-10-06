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

package com.nageoffer.ai.ragent.agent.service;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C4 / D05：受控批量删除的**服务端契约与负例**。
 *
 * <p>这组判据的重点不是"happy path 能删"，而是把 C4 逐条钉成**可观测的负例**：
 * 空集合、超限、重复、有一个不可访问、以及"permit 数量必须等于资源数量"。
 * 少了任何一条，"批量删除"就退化成"循环调单资源删除"——而那种实现每一条单独看都是合法的。
 */
@Tag("dev")
class ConversationBatchDeleteServiceTest {

    private AgentConversationService conversations;
    private RevocationGuard guard;
    private ConversationBatchDeleteService service;
    private ExecutionPrincipal previous;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        conversations = mock(AgentConversationService.class);
        guard = mock(RevocationGuard.class);
        ObjectProvider<RevocationGuard> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(guard);
        service = new ConversationBatchDeleteService(conversations, provider);
        previous = PrincipalContext.get();
        authenticate(1, 1);
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.restore(previous);
    }

    private void authenticate(int policyVersion, int aclVersion) {
        PrincipalContext.set(new ExecutionPrincipal(
                "000000", "7", "platform:000000:7", policyVersion, aclVersion,
                Set.of("conversation.delete"), "jti-batch-test", "test", 0L, Long.MAX_VALUE));
    }

    /** 每个资源都发一个 permit，返回可关闭的替身。 */
    private void permitsAlwaysGranted() {
        when(guard.enter(any(), anyString(), anyString())).thenAnswer(invocation -> {
            RevocationGuard.Operation operation = mock(RevocationGuard.Operation.class);
            when(operation.permitId()).thenReturn("permit-" + invocation.getArgument(2));
            when(operation.operationId()).thenReturn("op-" + invocation.getArgument(2));
            return operation;
        });
    }

    private void allVisible(String... ids) {
        for (String id : ids) {
            when(conversations.existsForUser(id, "7")).thenReturn(true);
        }
    }

    @Test
    @DisplayName("正例：逐资源 permit（数量 = 资源数），按字典序取得，并单次整批删除")
    void deletesWholeSetWithOnePermitPerResourceInDeterministicOrder() {
        permitsAlwaysGranted();
        allVisible("c-b", "c-a", "c-c");

        ConversationBatchDeleteService.Outcome outcome =
                service.deleteAll(List.of("c-b", "c-a", "c-c"));

        assertThat(outcome.deletedCount()).isEqualTo(3);
        assertThat(outcome.permitCount())
                .as("D05：不得复用一个单资源 permit 授权 N 个对象 —— permit 数必须等于资源数")
                .isEqualTo(3);

        ArgumentCaptor<String> refs = ArgumentCaptor.forClass(String.class);
        verify(guard, times(3)).enter(any(), anyString(), refs.capture());
        assertThat(refs.getAllValues())
                .as("必须按**确定顺序**（字典序）取得，否则同一集合的两次请求并发图不可比")
                .containsExactly("conv:c-a", "conv:c-b", "conv:c-c");

        ArgumentCaptor<List<String>> batch = ArgumentCaptor.forClass(List.class);
        verify(conversations).deleteBatch(batch.capture(), anyString());
        assertThat(batch.getValue())
                .as("整批一次交给服务层（单事务），而不是控制器里循环调单删")
                .containsExactly("c-a", "c-b", "c-c");
    }

    @Test
    @DisplayName("空集合 / null 集合一律 400 拒绝，绝不静默成功")
    void emptySetIsRejected() {
        assertThatThrownBy(() -> service.deleteAll(List.of()))
                .isInstanceOf(P04AiException.class)
                .extracting(thrown -> ((P04AiException) thrown).errorCode())
                .isEqualTo(P04AiErrorCode.BAD_REQUEST);
        assertThatThrownBy(() -> service.deleteAll(null))
                .isInstanceOf(P04AiException.class)
                .extracting(thrown -> ((P04AiException) thrown).errorCode())
                .isEqualTo(P04AiErrorCode.BAD_REQUEST);
        verify(conversations, never()).deleteBatch(any(), anyString());
    }

    @Test
    @DisplayName("空白 ID 拒绝（否则会以 'conv:' 这种无意义 ref 取得 permit）")
    void blankIdIsRejected() {
        assertThatThrownBy(() -> service.deleteAll(List.of("c-a", "  ")))
                .isInstanceOf(P04AiException.class)
                .extracting(thrown -> ((P04AiException) thrown).errorCode())
                .isEqualTo(P04AiErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("重复 ID 拒绝，不得静默 distinct 去重")
    void duplicateIdsAreRejected() {
        assertThatThrownBy(() -> service.deleteAll(List.of("c-a", "c-b", "c-a")))
                .isInstanceOf(P04AiException.class)
                .extracting(thrown -> ((P04AiException) thrown).errorCode())
                .isEqualTo(P04AiErrorCode.BAD_REQUEST);
        verify(conversations, never()).deleteBatch(any(), anyString());
    }

    @Test
    @DisplayName("上限 100：恰好 100 通过，101 拒绝（拒绝而不是截断）")
    void batchSizeIsBoundedAtOneHundred() {
        List<String> hundred = new ArrayList<>();
        for (int i = 0; i < ConversationBatchDeleteService.MAX_BATCH; i++) {
            hundred.add(String.format("c-%03d", i));
        }
        permitsAlwaysGranted();
        for (String id : hundred) {
            when(conversations.existsForUser(id, "7")).thenReturn(true);
        }
        assertThat(service.deleteAll(hundred).permitCount()).isEqualTo(100);

        hundred.add("c-100");
        assertThatThrownBy(() -> service.deleteAll(hundred))
                .isInstanceOf(P04AiException.class)
                .extracting(thrown -> ((P04AiException) thrown).errorCode())
                .isEqualTo(P04AiErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("整批有一个不可访问 ⇒ 整体失败，且**一个 permit 都没取得、数据库一行未动**")
    void oneInaccessibleResourceFailsTheWholeBatchWithoutPartialSuccess() {
        permitsAlwaysGranted();
        allVisible("c-a");
        when(conversations.existsForUser("c-b", "7")).thenReturn(false);

        assertThatThrownBy(() -> service.deleteAll(List.of("c-a", "c-b")))
                .isInstanceOf(P04AiException.class)
                .extracting(thrown -> ((P04AiException) thrown).errorCode())
                .isEqualTo(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);

        verify(guard, never()).enter(any(), anyString(), anyString());
        verify(conversations, never()).deleteBatch(any(), anyString());
    }

    @Test
    @DisplayName("epoch 复核：写入前策略/ACL 版本变了 ⇒ 放弃整批并释放已取得的 permit")
    void epochChangeAbortsBeforeAnyWrite() {
        List<RevocationGuard.Operation> issued = new ArrayList<>();
        when(guard.enter(any(), anyString(), anyString())).thenAnswer(invocation -> {
            RevocationGuard.Operation operation = mock(RevocationGuard.Operation.class);
            issued.add(operation);
            // 第一条 permit 取得后，模拟并发的策略/ACL 版本变更
            authenticate(2, 2);
            return operation;
        });
        allVisible("c-a");

        assertThatThrownBy(() -> service.deleteAll(List.of("c-a")))
                .isInstanceOf(P04AiException.class)
                .extracting(thrown -> ((P04AiException) thrown).errorCode())
                .isEqualTo(P04AiErrorCode.POLICY_VERSION_STALE);

        verify(conversations, never()).deleteBatch(any(), anyString());
        assertThat(issued).as("已取得的 permit 必须释放，不能泄漏成悬挂 ACTIVE permit").hasSize(1);
        verify(issued.get(0)).close();
    }

    @Test
    @DisplayName("缺主体 401；无屏障实现 503（不做无屏障的批量删除）")
    void missingPrincipalAndMissingGuardAreRejected() {
        PrincipalContext.clear();
        assertThatThrownBy(() -> service.deleteAll(List.of("c-a")))
                .isInstanceOf(P04AiException.class)
                .extracting(thrown -> ((P04AiException) thrown).errorCode())
                .isEqualTo(P04AiErrorCode.AUTH_REQUIRED);

        authenticate(1, 1);
        @SuppressWarnings("unchecked")
        ObjectProvider<RevocationGuard> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        ConversationBatchDeleteService noGuard =
                new ConversationBatchDeleteService(conversations, empty);
        allVisible("c-a");
        assertThatThrownBy(() -> noGuard.deleteAll(List.of("c-a")))
                .isInstanceOf(P04AiException.class)
                .extracting(thrown -> ((P04AiException) thrown).errorCode())
                .isEqualTo(P04AiErrorCode.AUTHORIZATION_UNAVAILABLE);
    }
}
