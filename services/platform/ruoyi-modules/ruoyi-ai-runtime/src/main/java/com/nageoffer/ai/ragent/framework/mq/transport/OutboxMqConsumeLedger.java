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
 * 消费端去重账本（C12.3）。**只记录消费进度**，不复制任何业务事实（C2.1）。
 *
 * <p>实现必须让 {@link #claim} 在**一条语句内原子**完成"首次 / 再认领 / 重复"的判定：
 * 先查后写会退化成竞态，两个实例同时投递同一个 key 时会双双认领成功。
 *
 * <p>为什么把"授权拒绝"也写进这个账本（{@link #markRejected}）：拒绝对象本身也是需要可查询的
 * 事实——运维要能回答"这条跨租户/越权消息被拒了几次、为什么被拒"。
 * 但它的状态是 {@code REJECTED} 而不是 {@code RETRYABLE}，语义不混。
 */
public interface OutboxMqConsumeLedger {

    /** 认领结果。 */
    enum ClaimOutcome {
        /** 可以执行副作用（首次，或上次失败/崩溃后重来）。 */
        CLAIMED,
        /** 重复：不得执行副作用。 */
        DUPLICATE
    }

    /**
     * 原子认领。
     *
     * @param leaseSeconds 认领租约。{@code PROCESSING} 超过租约后可被再认领 ——
     *                     这是"消费者崩溃在副作用之前"时事件不被永久丢弃的唯一保证。
     */
    ClaimOutcome claim(OutboxMqEnvelope envelope, int leaseSeconds);

    /** 副作用已完成，进入终局 {@code CONSUMED}。 */
    void markConsumed(String tenantId, String dedupKey);

    /** 消费前授权拒绝（C2.2），进入终局 {@code REJECTED}，永不重试。 */
    void markRejected(String tenantId, String dedupKey, String reason);

    /** 客观失败，置为 {@code RETRYABLE} 允许再认领。 */
    void release(String tenantId, String dedupKey, String reason);
}
