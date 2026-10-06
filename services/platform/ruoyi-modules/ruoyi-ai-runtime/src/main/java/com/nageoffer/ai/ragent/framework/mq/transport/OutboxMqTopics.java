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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * outbox MQ 的**主题命名与事件类型目录**（C12.3）。
 *
 * <p><b>契约原文</b>：「主题按 {@code eventType} 分；租户不拼进主题名（避免主题爆炸）；
 * 租户放消息 key + 属性。」
 *
 * <p><b>为什么需要一个专门的类是危险的。</b>主题名是**跨进程协议**：生产端按
 * {@code eventType} 拼出来，消费端要按同一个 {@code eventType} 订阅同一串字符。
 * 如果两边各写一份拼法（哪怕只是大小写或分隔符差一个字符），症状是
 * 「消息发出去了、消费者也起来了、但队列里没有流量」——最难查的一类静默故障。
 * 因此命名规则只在这里实现一次，生产端与消费端都调本类。
 *
 * <p><b>RocketMQ 主题名约束</b>（客户端校验 {@code ^[%|a-zA-Z0-9_-]+$}，长度 ≤ 127）：
 * 本仓库的 {@code RunEventAppender} 事件类型常量形如 {@code run.accepted}、{@code run.output_delta}，
 * **点号不在允许字符集内**。若不处理就直接拼，第一次真投递会抛
 * {@code MQClientException: The specified topic is invalid}（响亮失败，不是静默错投）。
 * 这里采取**确定性替换**（非允许字符 → {@code -}）而不是抛异常：
 * 事件类型是代码常量，运维不能靠"改配置"绕过；而抛光会让新增事件类型在**首次真投递**
 * 就失败——那已经是生产事故。替换是纯函数，同一输入恒等同一主题名，生产/消费两侧一致。
 *
 * <p>替换是**多对一**的（{@code run.status} 与 {@code run-status} 会落到同一主题）。这在本语义下
 * 不是缺陷：主题只做"分类投递"，真正的类型判据是消息属性与消息体里的 {@code eventType}，
 * 消费端会按**原值**比对（见消费侧 {@code OutboxMqIngestService}），所以替换不会让事件被误处理。
 */
public final class OutboxMqTopics {

    /** RocketMQ 主题名最大长度。 */
    public static final int ROCKETMQ_TOPIC_MAX_LENGTH = 127;

    /** 默认主题前缀；生产端与消费端必须取同一个值。 */
    public static final String DEFAULT_TOPIC_PREFIX = "ai-outbox-";

    /**
     * 本仓库 {@code outbox_event.event_type} 的**已知取值**（单一事实来源：{@code RunEventAppender} 的
     * {@code EVENT_*} 常量）。
     *
     * <p>为什么要在传输层再列一遍：RocketMQ 的订阅是**一主题一订阅**（{@code @RocketMQMessageListener}
     * 只接受单个 {@code topic}），传输层必须知道"要为哪些 eventType 建立订阅"。
     * 重复列举本身有漂移风险，因此 {@code OutboxMqEventTypeCoverageTest} 用反射读
     * {@code RunEventAppender} 的全部 {@code public static final String} 常量，
     * 断言**本清单恰好等于那个集合**：新增一个事件类型而没有加订阅，测试**会红**，
     * 而不是让新事件在 MQ 上静默无人消费。
     */
    public static final List<String> RUN_EVENT_TYPES = List.of(
            "run.accepted",
            "run.status",
            "run.step_started",
            "run.step_completed",
            "run.output_delta",
            "run.approval_required",
            "run.approval_resolved",
            "run.usage",
            "run.reconciliation_required",
            "run.error",
            "run.terminal");

    private OutboxMqTopics() {
    }

    /**
     * {@code eventType} → 主题名（确定性纯函数）。
     *
     * @param topicPrefix 配置的前缀；空白则用 {@link #DEFAULT_TOPIC_PREFIX}
     * @param eventType   事件类型；空白是**配置/代码错误**，拒绝而不是拼出半个主题名
     */
    public static String topicFor(String topicPrefix, String eventType) {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("outbox MQ 主题事件类型不能为空（C12.3：主题按 eventType 分）");
        }
        String prefix = (topicPrefix == null || topicPrefix.isBlank()) ? DEFAULT_TOPIC_PREFIX : topicPrefix;
        String topic = prefix + sanitize(eventType);
        if (topic.length() > ROCKETMQ_TOPIC_MAX_LENGTH) {
            throw new IllegalArgumentException("outbox MQ 主题名超长（" + topic.length() + " > "
                    + ROCKETMQ_TOPIC_MAX_LENGTH + "）：" + topic);
        }
        return topic;
    }

    /** 把事件类型里的非主题名字符替换为 {@code -}；结果为空则抛（不返回一个空壳主题名）。 */
    public static String sanitize(String eventType) {
        StringBuilder sb = new StringBuilder(eventType.length());
        for (int i = 0; i < eventType.length(); i++) {
            char c = eventType.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_';
            if (ok) {
                sb.append(c);
            } else {
                sb.append('-');
            }
        }
        String out = sb.toString();
        if (out.isBlank()) {
            throw new IllegalArgumentException("outbox MQ 事件类型净化后为空：" + eventType);
        }
        return out;
    }

    /**
     * 去重键取值（C12.3）。
     *
     * <p>运行事件用 {@code eventId}；外部业务动作（{@code operationKey} 非空）用 {@code operationKey}。
     * 由本方法统一决定，避免生产端与消费端对"用哪个键去重"各写一份。
     */
    public static String dedupKey(String eventId, String operationKey) {
        if (operationKey != null && !operationKey.isBlank()) {
            return "op:" + operationKey;
        }
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("outbox MQ 去重键不可得：eventId 与 operationKey 同时为空");
        }
        return "evt:" + eventId;
    }

    /** 已知事件类型集合（供订阅注册去重与排序，保证注册顺序确定）。 */
    public static Set<String> knownEventTypes() {
        return new LinkedHashSet<>(RUN_EVENT_TYPES);
    }
}
