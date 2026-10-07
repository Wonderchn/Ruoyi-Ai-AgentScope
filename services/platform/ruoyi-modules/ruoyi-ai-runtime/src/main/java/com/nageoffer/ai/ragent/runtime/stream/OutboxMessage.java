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

/**
 * 一条待投递的 outbox 消息（C12.2 的传输载荷）。
 *
 * <p><b>为什么要有这个类型，而不是把 {@code OutboxDao.OutboxRow} 直接交给传输层。</b>
 * {@code OutboxRow} 是**存储形态**：它带 {@code attemptCount} 这类只有 relay 需要的
 * 重试账目，而且会随 DAO 的列变化而变。传输实现（MQ）需要的是一份**协议形态**：
 * 租户、事件标识、主题分类键、序号、载荷、可选业务幂等键。
 * 把两者分开，T3 实现 RocketMQ 传输时就不必依赖 relay 的内部重试语义，
 * 也不会因为 DAO 加一个列而被追着改。
 *
 * <p><b>C12.3 的键与去重都由本记录的字段直接支撑。</b>
 * <ul>
 *   <li>主题按 {@code eventType} 分（**不把租户拼进主题名**，避免主题爆炸）；</li>
 *   <li>消息 key = {@code tenantId + ":" + eventId} → 同 key 有序；</li>
 *   <li>消费端去重键 = {@code tenantId + eventId}，或（外部业务动作）
 *       {@code tenantId + operationKey}。</li>
 * </ul>
 * 因此这里必须有 {@code tenantId} 与 {@code eventId}，且 {@code operationKey} 允许为空
 * （不是每个事件都对应一次外部业务动作）。
 *
 * @param tenantId     租户；进消息 key 与属性，<b>不进主题名</b>
 * @param eventId      事件标识；与 tenantId 组成全局去重键与消息 key
 * @param runId        所属 run（可见性/回放的归属，不作为去重键）
 * @param eventType    主题分类键（一类型一主题）
 * @param seq          运行内序号；**仍是可见性有序的权威**（迟到消息不得回退可见状态）
 * @param payload      事件载荷的 JSON 文本；可为 null（事件事实以 {@code ai_run_event} 为准）
 * @param operationKey 可选业务幂等键（外部业务动作用它去重）；无则为 null
 */
public record OutboxMessage(
        String tenantId,
        String eventId,
        String runId,
        String eventType,
        Long seq,
        String payload,
        String operationKey) {

    /**
     * 消息 key：{@code {tenantId}:{eventId}}（C12.3）。
     *
     * <p>放在记录里而不是让每个传输实现各拼一遍：key 形状一旦各写一份就会漂移，
     * 而漂移的后果是"同 key 有序"这条保证在某个实现里悄悄失效。
     */
    public String messageKey() {
        return tenantId + ":" + eventId;
    }

    /** 投递顺序用的序号；缺失按 0（与既有 {@code NotificationBus} 通知口径一致）。 */
    public long sequenceOrZero() {
        return seq == null ? 0L : seq;
    }
}
