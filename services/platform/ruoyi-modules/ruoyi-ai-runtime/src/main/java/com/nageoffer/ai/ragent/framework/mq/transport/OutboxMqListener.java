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

import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一个事件类型的 outbox 订阅入口：把 broker 投来的信封交给
 * {@link OutboxMqIngestService}（授权 → 去重 → 副作用）。
 *
 * <p><b>本类不带 {@code @RocketMQMessageListener}，是刻意的。</b>
 * 该注解只接受**单个** {@code topic}（`RocketMQMessageListener` 没有 `topicPattern` 成员，
 * 已 `javap` 核实），而 C12.3 要求主题按 {@code eventType} 分 —— 11 个事件类型就是
 * 11 个订阅。用一个带注解的类配一个主题只能覆盖 1/11；写 11 个几乎相同的注解类则
 * 每次新增事件类型都要记得加一个（漏了就静默无人订阅）。
 * 因此订阅由 {@link OutboxMqListenerRegistrar} 按配置清单**逐个注册**，
 * 并由启动期形状自检与单测保证"清单 == 注册数"。
 *
 * <p><b>异常语义 = 重投。</b>{@link #onMessage} 不吞异常：
 * {@code OutboxMqIngestService} 只在"已消费/重复/确定性拒绝"时正常返回，
 * 客观失败与授权事实不可得都会抛出，由 broker 重投直至
 * {@code maxReconsumeTimes}，之后进 RocketMQ 的 {@code %DLQ%} 主题。
 *
 * <p><b>本类的运行时行为未经真机验证：{@code RUNTIME_UNVERIFIED（无 broker）}。</b>
 * 已核实的是：① 编译通过；② 注册器在单测中按 11 个事件类型各注册一次、清单与
 * {@code RunEventAppender.EVENT_*} 恰好相等。**未核实**的是真 broker 下订阅真的生效、
 * 消息真的往返 —— 那需要 broker（T0 正在让 T8 尝试最小单机）。
 */
public class OutboxMqListener implements RocketMQListener<OutboxMqEnvelope> {

    private static final Logger log = LoggerFactory.getLogger(OutboxMqListener.class);

    private final OutboxMqIngestService ingestService;
    private final String subscribedEventType;

    public OutboxMqListener(OutboxMqIngestService ingestService, String subscribedEventType) {
        this.ingestService = ingestService;
        this.subscribedEventType = subscribedEventType;
    }

    /** 该监听器负责的事件类型（注册与自检用）。 */
    public String subscribedEventType() {
        return subscribedEventType;
    }

    @Override
    public void onMessage(OutboxMqEnvelope envelope) {
        OutboxMqIngestService.IngestOutcome outcome = ingestService.ingest(envelope);
        if (outcome != OutboxMqIngestService.IngestOutcome.CONSUMED) {
            // DUPLICATE / REJECTED 都是"正常结束、无副作用"，走 debug：它们在高并发与
            // 至少一次投递下会常态出现，用 warn 会把真正的失败淹掉。
            log.debug("outbox MQ 未执行副作用：outcome={}, type={}, eventId={}", outcome, subscribedEventType,
                    envelope == null ? null : envelope.getEventId());
        }
    }
}
