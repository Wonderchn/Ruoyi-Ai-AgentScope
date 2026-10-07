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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.runtime.stream.OutboxMessage;
import com.nageoffer.ai.ragent.runtime.stream.OutboxTransport;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

/**
 * 把 outbox 消息投递到 RocketMQ 的 {@link OutboxTransport} 实现（C12.2：T2 定义接口，T3 实现传输）。
 *
 * <p><b>归属。</b>传输只负责"把这一条发出去"：不生成 run 状态、不判终态、不写执行账本
 * （C2.1 禁止第二套 Worker 事实/执行账本）。失败语义完全交回 relay：
 * 本类<b>只在确认 broker 收下之后才正常返回</b>，其余一切路径都抛
 * {@link OutboxTransportException}，由 relay 走既有退避 / DEAD_LETTER，
 * outbox 行保持 PENDING 且积压可查（C2.3/C12.4）。
 *
 * <p><b>为什么用 {@link RocketMQTemplate} 而不是复用既有 {@code RocketMQProducerAdapter}。</b>
 * 既有适配器（{@code framework/mq/producer/}，同模块）解决的是**另一类问题**：
 * 独立 AI 应用里"业务写库 + 消息发送"的两种发送形态（同步、事务半消息 + 本地事务回查）。
 * 它的 {@code send(topic, keys, bizDesc, body)} 不接受额外属性、不暴露发送超时、
 * 也**不检查 {@code SendStatus}**——对本传输而言最后一条是硬伤：{@code syncSend} 在
 * {@code FLUSH_DISK_TIMEOUT}/{@code SLAVE_NOT_AVAILABLE} 等状态下会**正常返回**，
 * 而 relay 会据此把 outbox 行标成 {@code PUBLISHED}。那正是 C2.3 禁止的
 * "投递没确认但账上已完成"。
 *
 * <p>它更不适用的是 {@code sendInTransaction}：<b>outbox 本身就是事务性保证</b>
 * （业务状态 + outbox 行同事务提交，C2.1），这里再套一层半消息会引入第二条时序权威，
 * 并且 {@code DelegatingTransactionListener} 回查结果 {@code UNKNOWN} 时不得冒充 COMMIT
 * （D06 明文）——用它反而把已经正确的语义重新变成需要裁决的问题。因此本实现只做**同步发送**，
 * 复用同一支 {@code RocketMQTemplate} 客户端与既有的 {@code MessageWrapper} 信封形状，
 * 不新增第二套生产者装配。
 *
 * <p><b>非 {@code SEND_OK} 一律算失败，明知会造成重复投递。</b>
 * {@code SLAVE_NOT_AVAILABLE} 的语义是"消息已写入 master"，按失败处理会重投。
 * 这是**刻意的**：C2.3 选了"至少一次"而不是"至多一次"，消费端按
 * {@code tenantId + eventId} 去重（C12.3）本来就要求能吞下重复。反过来，
 * 把不确定状态当成功会产生**永久丢失**——两种错误里只有一种可以靠幂等化解。
 */
public class RocketMQOutboxTransport implements OutboxTransport {

    /** 租户属性名（C12.3：租户放消息 key + 属性）。 */
    public static final String HEADER_TENANT_ID = "outboxTenantId";
    /** 事件标识属性名。 */
    public static final String HEADER_EVENT_ID = "outboxEventId";
    /** 事件类型属性名（与主题分类键同源，便于按类型排查）。 */
    public static final String HEADER_EVENT_TYPE = "outboxEventType";
    /** 运行内序号属性名。 */
    public static final String HEADER_SEQ = "outboxSeq";

    private final RocketMQTemplate rocketMQTemplate;
    private final OutboxMqProperties properties;
    private final ObjectMapper objectMapper;

    public RocketMQOutboxTransport(RocketMQTemplate rocketMQTemplate, OutboxMqProperties properties,
                                   ObjectMapper objectMapper) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void send(OutboxMessage message) {
        // 前两项在发出去之前就拒绝：拼不出主题名/去重键说明代码或配置错了，
        // 让它以 IllegalArgumentException 暴露，而不是发出一条"无法被消费端认出"的消息。
        String topic = properties.topicFor(message.eventType());
        String key = message.messageKey();
        OutboxMqTopics.dedupKey(message.eventId(), message.operationKey());

        String body = serialize(OutboxMqEnvelope.from(message));

        Message<String> springMessage = MessageBuilder.withPayload(body)
                .setHeader(MessageConst.PROPERTY_KEYS, key)
                .setHeader(HEADER_TENANT_ID, message.tenantId())
                .setHeader(HEADER_EVENT_ID, message.eventId())
                .setHeader(HEADER_EVENT_TYPE, message.eventType())
                .setHeader(HEADER_SEQ, String.valueOf(message.sequenceOrZero()))
                .build();

        SendResult result;
        try {
            result = rocketMQTemplate.syncSend(topic, springMessage, properties.getSendTimeoutMs());
        } catch (RuntimeException e) {
            throw new OutboxTransportException("RocketMQ 投递失败（broker 不可用或超时）topic=" + topic
                    + " key=" + key, e);
        }

        if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
            String status = result == null ? "null-result" : String.valueOf(result.getSendStatus());
            throw new OutboxTransportException("RocketMQ 未确认收下消息：sendStatus=" + status
                    + "（非 SEND_OK 一律按失败处理，不让 relay 标记 PUBLISHED）topic=" + topic + " key=" + key);
        }
    }

    private String serialize(OutboxMqEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new OutboxTransportException("outbox 信封序列化失败：eventId=" + envelope.getEventId(), e);
        }
    }
}
