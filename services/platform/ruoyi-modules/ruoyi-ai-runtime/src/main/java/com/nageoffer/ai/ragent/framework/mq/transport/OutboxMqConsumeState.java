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
 * 消费端去重账本的**四个状态**（C12.3/C12.4），与 V18 的
 * {@code ck_ai_mq_consume_dedup_state} 约束逐字对应。
 *
 * <p><b>为什么必须是四个状态，而不是一个"已处理"布尔。</b>
 * 至少一次投递下会出现三种完全不同的"非首次"情形，而它们的正确反应相反：
 * <ul>
 *   <li>{@link #PROCESSING}：已有人认领、副作用**尚未确认完成**。租约内重复投递必须跳过
 *       （否则副作用做两次）；但**租约过期后必须允许再认领** —— 那是"消费者崩溃在副作用之前"
 *       的唯一出路。若把"已存在"一律当重复，崩溃那一刻的消息被重投时会被跳过，
 *       事件**永久丢失**，而账上还写着有人认领过。</li>
 *   <li>{@link #CONSUMED}：终局，永不再执行副作用。</li>
 *   <li>{@link #RETRYABLE}：客观失败（handler 抛异常），允许再认领 —— 与"授权拒绝"必须分开。</li>
 *   <li>{@link #REJECTED}：**消费前重新授权被拒**（C2.2）。这类拒绝是**确定性**的：
 *       重投一万次也不会变得可消费。把它记成 RETRYABLE 就是"把拒绝变成无限重投"，
 *       既刷爆队列又掩盖一条本该被人看到的越权/跨租户事件。</li>
 * </ul>
 */
public final class OutboxMqConsumeState {

    /** 已认领，副作用未确认完成（受认领租约保护）。 */
    public static final String PROCESSING = "PROCESSING";
    /** 终局：副作用已完成。 */
    public static final String CONSUMED = "CONSUMED";
    /** 客观失败，可再认领。 */
    public static final String RETRYABLE = "RETRYABLE";
    /** 授权拒绝，永不再认领。 */
    public static final String REJECTED = "REJECTED";

    private OutboxMqConsumeState() {
    }
}
