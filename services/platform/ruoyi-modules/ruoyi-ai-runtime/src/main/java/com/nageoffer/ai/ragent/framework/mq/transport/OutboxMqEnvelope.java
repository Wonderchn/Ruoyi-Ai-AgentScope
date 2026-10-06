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

import com.nageoffer.ai.ragent.runtime.stream.OutboxMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * outbox 跨实例投递的**线上信封**（wire format）。
 *
 * <p><b>为什么不直接把 {@link OutboxMessage} 序列化发出去。</b>
 * {@link OutboxMessage} 是**relay 的进程内协议**：它的字段集合会随 relay/DAO 的需要变化
 * （T2 拥有它）。线上格式一旦发布就不能随便变——旧实例还在跑，新实例已经按新字段发，
 * 字段一改就是跨版本不兼容。因此这里固定一份**带 {@code schemaVersion} 的信封**，
 * 并且<b>把权威字段全部放在消息体里</b>，而不是只放在消息属性里：
 *
 * <ul>
 *   <li>消息属性（user property）的 Spring→RocketMQ 映射由 starter 的转换器决定，
 *       本机**没有 broker**，我无法实测"header 一定变成 user property"（见报告 NOT_RUN）。
 *       把权威字段放消息体后，消费端**不依赖**属性是否送达，结论仍然是确定的。</li>
 *   <li>属性仍然照 C12.3 设置（租户进 key + 属性），供运维在控制台按租户筛消息；
 *       但**消费端的正确性不建立在属性之上**。</li>
 * </ul>
 *
 * <p><b>信封里的字段与 {@link OutboxMessage} 一一对应，不做增强。</b>传输层不得成为
 * 第二套事实来源（C2.1）：它不生成 run 状态、不判终态、不写执行账本，只搬运。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxMqEnvelope implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 当前线上版本。消费端遇到未知版本**拒绝**而不是猜字段（见 ingest 的版本闸门）。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    @Builder.Default
    private int schemaVersion = CURRENT_SCHEMA_VERSION;

    private String tenantId;
    private String eventId;
    private String runId;
    private String eventType;
    private Long seq;
    private String operationKey;

    /** 事件载荷 JSON 文本；事实以 {@code ai_run_event} 为准，这里只做投递。 */
    private String payload;

    /** 生产端发出时刻（毫秒）。仅用于运维观测，**不作为任何时序权威**（seq 才是）。 */
    private long sentAt;

    /** 从 relay 的进程内消息构造线上信封。 */
    public static OutboxMqEnvelope from(OutboxMessage message) {
        return OutboxMqEnvelope.builder()
                .schemaVersion(CURRENT_SCHEMA_VERSION)
                .tenantId(message.tenantId())
                .eventId(message.eventId())
                .runId(message.runId())
                .eventType(message.eventType())
                .seq(message.seq())
                .operationKey(message.operationKey())
                .payload(message.payload())
                .sentAt(System.currentTimeMillis())
                .build();
    }

    /** 去重键（C12.3）；与生产端用同一实现，避免两侧对"用哪个键"各写一份。 */
    public String dedupKey() {
        return OutboxMqTopics.dedupKey(eventId, operationKey);
    }

    /** 消息 key：{@code {tenantId}:{eventId}}，保证同 key 有序。 */
    public String messageKey() {
        return tenantId + ":" + eventId;
    }

    /**
     * 投递序号；缺失按 0。
     *
     * <p><b>刻意不叫 {@code getSequenceOrZero()}。</b>本类其余访问器由 Lombok {@code @Data} 生成
     * （{@code getSeq()} 等），但这个方法是有实际判定的派生值而非字段读取，命名与
     * T2 的 {@code OutboxMessage.sequenceOrZero()} 保持一致 —— 两处同名，调用方不必记两套。
     * 调用点写 {@code getSequenceOrZero()} 会编译失败（这是好事：编译期就暴露，而不是静默取到 null）。
     */
    public long sequenceOrZero() {
        return seq == null ? 0L : seq;
    }
}
