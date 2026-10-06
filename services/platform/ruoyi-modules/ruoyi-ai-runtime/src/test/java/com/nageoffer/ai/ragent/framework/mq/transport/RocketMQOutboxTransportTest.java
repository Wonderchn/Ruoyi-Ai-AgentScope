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

package com.nageoffer.ai.ragent.framework.mq.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.runtime.stream.OutboxMessage;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * C12.2/C12.3/C12.4：outbox → RocketMQ 传输的**投递契约**。
 *
 * <p><b>为什么必须钉住"非 SEND_OK 也算失败"。</b>这是本实现里唯一一处
 * 看起来"过度保守"、实际决定成败的判断。RocketMQ 的 {@code syncSend} 在
 * {@code FLUSH_DISK_TIMEOUT} / {@code FLUSH_SLAVE_TIMEOUT} / {@code SLAVE_NOT_AVAILABLE}
 * 三种状态下**正常返回不抛异常**。若照着"SEND_OK 才成功"以外的写法
 * （例如只 catch 异常、不看 status），relay 会把 outbox 行标成 {@code PUBLISHED}，
 * 而消息可能根本没进 broker —— 这就是 C2.3 禁止的"假执行成功"。
 * 只测 happy path 的测试发现不了它，必须有一条专门的负例。
 */
@Tag("dev")
class RocketMQOutboxTransportTest {

    private RocketMQTemplate template;
    private OutboxMqProperties properties;
    private RocketMQOutboxTransport transport;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        template = mock(RocketMQTemplate.class);
        properties = new OutboxMqProperties();
        transport = new RocketMQOutboxTransport(template, properties, MAPPER);
    }

    private static OutboxMessage message() {
        return new OutboxMessage("000000", "e-abc", "r-1", "run.terminal", 7L,
                "{\"status\":\"SUCCEEDED\"}", "op-42");
    }

    private static SendResult ok() {
        SendResult result = new SendResult();
        result.setSendStatus(SendStatus.SEND_OK);
        result.setMsgId("msg-1");
        return result;
    }

    @SuppressWarnings("unchecked")
    private Message<String> sentMessage() {
        ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
        verify(template).syncSend(anyString(), captor.capture(), anyLong());
        return (Message<String>) captor.getValue();
    }

    @Test
    @DisplayName("投递成功：主题按 eventType、key = {tenantId}:{eventId}、属性带租户、信封含权威字段")
    void successfulSendCarriesContractFields() throws Exception {
        when(template.syncSend(anyString(), any(Message.class), anyLong())).thenReturn(ok());

        transport.send(message());

        ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
        verify(template).syncSend(org.mockito.ArgumentMatchers.eq("ai-outbox-run-terminal"), captor.capture(),
                org.mockito.ArgumentMatchers.eq((long) properties.getSendTimeoutMs()));

        Message<String> sent = (Message<String>) captor.getValue();
        assertThat(sent.getHeaders().get(MessageConst.PROPERTY_KEYS))
                .as("C12.3：消息 key = {tenantId}:{eventId}").isEqualTo("000000:e-abc");
        assertThat(sent.getHeaders().get(RocketMQOutboxTransport.HEADER_TENANT_ID)).isEqualTo("000000");
        assertThat(sent.getHeaders().get(RocketMQOutboxTransport.HEADER_EVENT_ID)).isEqualTo("e-abc");
        assertThat(sent.getHeaders().get(RocketMQOutboxTransport.HEADER_EVENT_TYPE)).isEqualTo("run.terminal");
        assertThat(sent.getHeaders().get(RocketMQOutboxTransport.HEADER_SEQ)).isEqualTo("7");

        // 权威字段同时进消息体：本机无 broker，header→user property 的映射无法实测，
        // 因此消费端的正确性不建立在属性之上（见类注释与报告 NOT_RUN）。
        OutboxMqEnvelope envelope = MAPPER.readValue(sent.getPayload(), OutboxMqEnvelope.class);
        assertThat(envelope.getSchemaVersion()).isEqualTo(OutboxMqEnvelope.CURRENT_SCHEMA_VERSION);
        assertThat(envelope.getTenantId()).isEqualTo("000000");
        assertThat(envelope.getEventId()).isEqualTo("e-abc");
        assertThat(envelope.getRunId()).isEqualTo("r-1");
        assertThat(envelope.getEventType()).isEqualTo("run.terminal");
        assertThat(envelope.getSeq()).isEqualTo(7L);
        assertThat(envelope.getOperationKey()).isEqualTo("op-42");
        assertThat(envelope.getPayload()).isEqualTo("{\"status\":\"SUCCEEDED\"}");
        assertThat(envelope.getSentAt()).isPositive();
        assertThat(envelope.dedupKey()).isEqualTo("op:op-42");
    }

    @Test
    @DisplayName("broker 抛异常 ⇒ 抛 OutboxTransportException（绝不正常返回，让 relay 留在 PENDING）")
    void sendFailureThrowsSoRelayKeepsPending() {
        when(template.syncSend(anyString(), any(Message.class), anyLong()))
                .thenThrow(new RuntimeException("broker down"));

        assertThatThrownBy(() -> transport.send(message()))
                .isInstanceOf(OutboxTransportException.class)
                .hasMessageContaining("RocketMQ 投递失败");
    }

    @Test
    @DisplayName("负例：非 SEND_OK 状态必须算失败，不得被当成投递成功")
    void nonSendOkStatusIsATransportFailure() {
        for (SendStatus status : new SendStatus[]{SendStatus.FLUSH_DISK_TIMEOUT, SendStatus.FLUSH_SLAVE_TIMEOUT,
                SendStatus.SLAVE_NOT_AVAILABLE}) {
            RocketMQTemplate local = mock(RocketMQTemplate.class);
            SendResult result = new SendResult();
            result.setSendStatus(status);
            when(local.syncSend(anyString(), any(Message.class), anyLong())).thenReturn(result);

            assertThatThrownBy(() -> new RocketMQOutboxTransport(local, properties, MAPPER).send(message()))
                    .as("sendStatus=%s 不得被当成成功（否则 relay 会标记 PUBLISHED 造成跨实例永久丢失）", status)
                    .isInstanceOf(OutboxTransportException.class)
                    .hasMessageContaining(status.name());
        }
    }

    @Test
    @DisplayName("负例：返回 null（客户端异常形态）同样算失败")
    void nullSendResultIsATransportFailure() {
        when(template.syncSend(anyString(), any(Message.class), anyLong())).thenReturn(null);

        assertThatThrownBy(() -> transport.send(message()))
                .isInstanceOf(OutboxTransportException.class)
                .hasMessageContaining("null-result");
    }

    @Test
    @DisplayName("负例：拼不出主题名时在触达 broker 之前拒绝（不发出一条消费端认不出的消息）")
    void invalidEventTypeIsRejectedBeforeTouchingTheBroker() {
        OutboxMessage blank = new OutboxMessage("000000", "e-1", "r-1", "  ", 1L, "{}", null);

        assertThatThrownBy(() -> transport.send(blank))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
        verifyNoInteractions(template);
    }

    @Test
    @DisplayName("负例：eventId 与 operationKey 同时为空 ⇒ 去重键不可得，同样在触达 broker 之前拒绝")
    void missingDedupKeyIsRejectedBeforeTouchingTheBroker() {
        OutboxMessage noKey = new OutboxMessage("000000", "  ", "r-1", "run.status", 1L, "{}", null);

        assertThatThrownBy(() -> transport.send(noKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("去重键不可得");
        verifyNoInteractions(template);
    }

    @Test
    @DisplayName("正向锚点：上述两个负例不是『任何输入都拒绝』——合法输入确实走到 broker，且只投一次")
    void validMessageReachesTheBrokerExactlyOnce() {
        when(template.syncSend(anyString(), any(Message.class), anyLong())).thenReturn(ok());

        transport.send(message());

        verify(template, times(1)).syncSend(anyString(), any(Message.class), anyLong());
        assertThat(sentMessage().getPayload()).contains("\"eventId\":\"e-abc\"");
    }
}
