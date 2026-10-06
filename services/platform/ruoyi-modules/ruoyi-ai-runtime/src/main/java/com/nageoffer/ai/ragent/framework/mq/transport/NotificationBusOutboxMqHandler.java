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

import com.nageoffer.ai.ragent.runtime.stream.NotificationBus;

/**
 * 默认副作用：把跨实例收到的 outbox 消息**唤醒到本进程**的可见性通道（{@link NotificationBus}）。
 *
 * <p><b>刻意只做唤醒，不做任何业务写入。</b>C2.1 明文禁止传输层新建第二套 Worker 事实或
 * 执行账本：run 状态、终态判定、执行账本都归 T2。这一层"收到消息 → 叫醒本地 SSE 订阅者"
 * 与生产端的 {@code NotificationBusTransport} 是同一件事的两端（一个在本进程直接投递，
 * 一个在别的实例上通过 broker 触发），因此它天然幂等：唤醒丢失也不影响事实，
 * 订阅端仍以 {@code ai_run_event} 的 seq 游标为权威。
 *
 * <p><b>跨实例唤醒不是"每实例一次"。</b>去重账本是**集群级**的（C12.3 的键就是
 * tenant + event），所以同一条事件只会有**一个**实例走到这里。这对 SSE 是安全的，
 * 因为 T2 的流式可见性以数据库连续游标轮询为补偿：拿不到唤醒的实例只是晚一点
 * 从库里读到事件，不会漏内容。这一取舍在此显式写出，避免后人误以为"唤醒是全实例广播"。
 */
public class NotificationBusOutboxMqHandler implements OutboxMqHandler {

    private final NotificationBus bus;

    public NotificationBusOutboxMqHandler(NotificationBus bus) {
        this.bus = bus;
    }

    @Override
    public void handle(OutboxMqEnvelope envelope) {
        bus.publish(envelope.getTenantId(), envelope.getRunId(), envelope.sequenceOrZero(), envelope.getEventId());
    }
}
