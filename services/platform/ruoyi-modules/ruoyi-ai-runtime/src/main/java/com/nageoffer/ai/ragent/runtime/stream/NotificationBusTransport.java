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
 * 把 {@link OutboxTransport} 的 {@code send} 适配到既有进程内 {@link NotificationBus}。
 *
 * <p><b>为什么需要这个适配器，而 relay 不直接调 {@code NotificationBus}。</b>
 * C12.2 要求 {@code NotificationBus} 保留、MQ 是**附加**。在同一个循环里既调总线又调
 * 每个传输实现，会让"谁失败了"说不清：总线失败与 MQ 失败都变成同一个
 * {@code markRetry}，而两者的恢复语义不同（总线是进程内、MQ 可能整段 broker 掉线）。
 * 把它做成一个**传输实现**之后，语义变成一句话：
 * <b>所有已注册的传输都必须成功，这一条才算投递成功</b>——与 C2.3"不丢 outbox、
 * 不返回假执行成功"一致，也避免了"MQ 挂了但总线成功 → 标记 PUBLISHED → 跨实例永久丢消息"。
 *
 * <p>它与 MQ 传输的实现无关，只依赖 relay 交给它的 {@link OutboxMessage}。
 * 本类永不抛异常（{@code NotificationBus.publish} 内部已吞掉订阅端异常），
 * 所以它的存在不会让 relay 因进程内通知而进 DEAD_LETTER。
 */
public class NotificationBusTransport implements OutboxTransport {

    private final NotificationBus bus;

    public NotificationBusTransport(NotificationBus bus) {
        this.bus = bus;
    }

    @Override
    public void send(OutboxMessage message) {
        // 进程内可见性通道：通知丢失不影响事实（SSE 仍以数据库连续游标为准），
        // 因此这里不做重试、不抛异常。
        bus.publish(message.tenantId(), message.runId(), message.sequenceOrZero(), message.eventId());
    }
}
