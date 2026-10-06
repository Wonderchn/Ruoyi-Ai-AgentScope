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

package com.nageoffer.ai.ragent.runtime.stream;

import com.nageoffer.ai.ragent.runtime.P2RuntimeProperties;
import com.nageoffer.ai.ragent.runtime.dao.OutboxDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C2.3 / C12.2：outbox 投递 seam 的失败语义。
 *
 * <p><b>为什么这条判据是必需的，而不是"测试补数量"。</b>引入 MQ 之后最容易悄悄发生的
 * 退化形态是：relay 仍然先调进程内总线并成功，于是把 outbox 行标成 {@code PUBLISHED}，
 * 而 MQ 那边从头到尾没人发、或发了失败 —— 结果是<b>跨实例永久丢消息，
 * 而账上看起来已经投递完成</b>。这正是 C2.3 明文禁止的"不返回假执行成功"。
 * 只断言"happy path 会调 send"完全发现不了它：必须钉住
 * "**任一传输失败 ⇒ 不得标记 PUBLISHED，且必须留在 PENDING 重试**"。
 */
@Tag("dev")
class OutboxRelayTransportSeamTest {

    private OutboxDao dao;
    private P2RuntimeProperties properties;

    @BeforeEach
    void setUp() {
        dao = mock(OutboxDao.class);
        properties = new P2RuntimeProperties();
        properties.getOutbox().setMaxAttempts(3);
        properties.getOutbox().setBackoffSeconds(5);
        properties.getOutbox().setBatch(10);
        properties.getOutbox().setLockSeconds(30);
    }

    private OutboxDao.OutboxRow row(int attemptCount) {
        return new OutboxDao.OutboxRow("000000", "evt-1", "run-1", "RUN_STATUS",
                7L, attemptCount, "{\"operationKey\":\"op-1\"}", "op-1");
    }

    @SuppressWarnings("unchecked")
    private OutboxRelay relayFor(List<OutboxTransport> transports) {
        return new OutboxRelay(dao, transports, properties, (ObjectProvider<com.nageoffer.ai.ragent.runtime.P2FaultInjector>) mock(ObjectProvider.class));
    }

    @Test
    @DisplayName("全部传输成功时才标记 PUBLISHED，且消息按 C12.2 携带完整字段")
    void allTransportsSucceedThenMarkPublished() {
        when(dao.claim(anyString(), anyInt(), anyInt())).thenReturn(List.of(row(1)));
        List<OutboxMessage> seen = new ArrayList<>();

        relayFor(List.of(seen::add)).relayOnce();

        assertThat(seen).hasSize(1);
        OutboxMessage message = seen.get(0);
        assertThat(message.tenantId()).isEqualTo("000000");
        assertThat(message.eventId()).isEqualTo("evt-1");
        assertThat(message.runId()).isEqualTo("run-1");
        assertThat(message.eventType()).isEqualTo("RUN_STATUS");
        assertThat(message.seq()).isEqualTo(7L);
        assertThat(message.payload()).isEqualTo("{\"operationKey\":\"op-1\"}");
        assertThat(message.operationKey()).isEqualTo("op-1");
        assertThat(message.messageKey()).as("C12.3：消息 key = {tenantId}:{eventId}").isEqualTo("000000:evt-1");

        verify(dao).markPublished("000000", "evt-1");
        verify(dao, never()).markRetry(anyString(), anyString(), anyString(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("传输抛出 ⇒ 绝不标记 PUBLISHED（不返回假成功），留在 PENDING 走退避")
    void transportFailureNeverMarksPublished() {
        when(dao.claim(anyString(), anyInt(), anyInt())).thenReturn(List.of(row(1)));
        OutboxTransport broker = message -> {
            throw new IllegalStateException("broker down");
        };

        relayFor(List.of(broker)).relayOnce();

        verify(dao, never()).markPublished(anyString(), anyString());
        ArgumentCaptor<Boolean> deadLetter = ArgumentCaptor.forClass(Boolean.class);
        verify(dao).markRetry(eq("000000"), eq("evt-1"), anyString(), anyInt(), deadLetter.capture());
        assertThat(deadLetter.getValue())
                .as("attempt=1 < maxAttempts=3 ⇒ 不是 DEAD_LETTER，而是保留可查询积压（C2.3/C12.4）")
                .isFalse();
    }

    @Test
    @DisplayName("『总线成功 + MQ 失败』必须整体算失败：任一传输失败即不得标记 PUBLISHED")
    void partialTransportSuccessIsStillAFailure() {
        when(dao.claim(anyString(), anyInt(), anyInt())).thenReturn(List.of(row(1)));
        List<String> delivered = new ArrayList<>();
        OutboxTransport inProcessBus = message -> delivered.add("bus");
        OutboxTransport broker = message -> {
            throw new IllegalStateException("broker down");
        };

        relayFor(List.of(inProcessBus, broker)).relayOnce();

        assertThat(delivered).as("进程内总线确实被投递过").containsExactly("bus");
        verify(dao, never()).markPublished(anyString(), anyString());
        verify(dao).markRetry(eq("000000"), eq("evt-1"), anyString(), anyInt(), eq(false));
    }

    @Test
    @DisplayName("超过最大尝试次数进 DEAD_LETTER（保留人工重放，不静默丢弃）")
    void exhaustedAttemptsGoToDeadLetter() {
        when(dao.claim(anyString(), anyInt(), anyInt())).thenReturn(List.of(row(3)));
        OutboxTransport broker = message -> {
            throw new IllegalStateException("broker down");
        };

        relayFor(List.of(broker)).relayOnce();

        verify(dao, never()).markPublished(anyString(), anyString());
        verify(dao).markRetry(eq("000000"), eq("evt-1"), anyString(), anyInt(), eq(true));
    }
}
