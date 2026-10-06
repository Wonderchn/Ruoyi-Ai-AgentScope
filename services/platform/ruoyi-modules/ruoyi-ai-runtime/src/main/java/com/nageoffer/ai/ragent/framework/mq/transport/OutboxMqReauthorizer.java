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

/**
 * 消费前**重新授权**（C2.2）。<b>不得沿用生产端授权快照。</b>
 *
 * <p>为什么不能拿"生产端已经授权过"当理由：消息从生产者授权到消费者处理之间，
 * 事实可能已经变了 —— 租户被停用、成员被撤权、run 已被终态封口、事件被清理。
 * 生产端快照描述的是**投递那一刻**的授权，消费端要为自己的副作用在**此刻**负责。
 *
 * <p>三种结果必须能被区分，因为它们的正确反应不同：
 * <ul>
 *   <li>{@link Decision#ALLOW}：可以消费。</li>
 *   <li>{@link Decision#DENY}：**确定不可消费** —— 记录 {@code REJECTED} 并**不再重试**。
 *       重投一万次也不会变得可消费，把它当可重试就是无限重投 + 掩盖一条本该被看到的越权事件。</li>
 *   <li>{@link Decision#UNAVAILABLE}：**授权事实暂时不可得**（数据库不可达等）。
 *       这时既不能放行（拿不到授权就不能消费，C6）也不能记成拒绝（事实不可得 ≠ 被拒），
 *       只能**抛出让 broker 重投** —— 消息不丢，等事实恢复。</li>
 * </ul>
 */
public interface OutboxMqReauthorizer {

    /** 重新授权结论。 */
    enum Decision {
        /** 放行。 */
        ALLOW,
        /** 确定拒绝：记录 REJECTED，永不重试。 */
        DENY,
        /** 授权事实暂时不可得：抛出重投，不记拒绝、不丢消息。 */
        UNAVAILABLE
    }

    /** 消费前的重新授权判定；实现**不得**读取生产端写入的授权快照作为结论。 */
    Decision reauthorize(OutboxMqEnvelope envelope);
}
