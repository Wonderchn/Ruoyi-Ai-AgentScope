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
 * outbox 投递传输的 seam（C2.4 / C12.2）。<b>接口归 T2 定义，RocketMQ 传输由 T3 实现。</b>
 *
 * <p><b>契约。</b>实现只管"把这一条发出去"：
 * <ul>
 *   <li>正常返回 = 该条已交给传输层；</li>
 *   <li><b>抛异常 = 投递失败</b>，由 {@link OutboxRelay} 走既有退避 / DEAD_LETTER 路径
 *       （outbox 行保持 {@code PENDING}，积压仍可查询）。实现<b>不得</b>在失败时返回正常值
 *       —— 那会让 relay 标记 {@code PUBLISHED}，产生"投递没发生但账上已完成"的假成功，
 *       正是 C2.3 明文禁止的"不返回假执行成功"。</li>
 * </ul>
 *
 * <p><b>MQ 是附加，不是替代（C12.2）。</b>{@link NotificationBus} 是进程内可见性通道，
 * 必须保留；本接口是多出来的**跨实例**传输。因此 {@code OutboxRelay} 会同时投递到
 * 进程内总线与全部已注册的传输实现，而不是二选一。T3 <b>不得</b>借这个 seam
 * 新建第二套 Worker 事实或第二套执行账本（C2.1）——传输只搬运事件，事实仍在 T2 的
 * run/checkpoint/审批与 {@code ai_run_event} 里。
 *
 * <p><b>默认姿态。</b>MQ 开关默认关闭（C12.5-5）：没有任何实现注册时，
 * relay 仍然只走进程内总线，行为与引入本接口之前逐字一致。
 */
public interface OutboxTransport {

    /** 投递一条消息；抛出即视为投递失败。 */
    void send(OutboxMessage message);
}
