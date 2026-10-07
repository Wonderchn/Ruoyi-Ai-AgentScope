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

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * outbox → RocketMQ 传输侧配置（{@code p2.outbox.mq.*}）。
 *
 * <p><b>为什么单独一个前缀，而不塞进 T2 的 {@code P2RuntimeProperties}。</b>
 * MQ 是**可选附加传输**（C12.2），它的开关不应改变 relay 的核心语义，也不应让
 * {@code p2.enabled}/{@code p2.outbox.relay-enabled} 的含义被顺带改写。
 * 单独前缀（{@code p2.outbox.mq}）让"MQ 关着"与"MQ 开着但坏了"两种情况在配置上就分开，
 * 且不需要 T2 改自己的类。
 *
 * <p><b>默认关闭是契约，不是保守。</b>D07/C12.5-5 明文：验证 Boot 4 兼容、重复/乱序、
 * 消费者崩溃/重平衡与租户边界**之后**才默认开启；开关默认 {@code false}，
 * 由 T0 依据验证证据决定是否开启。
 */
@ConfigurationProperties(prefix = "p2.outbox.mq")
public class OutboxMqProperties {

    /** C12.5-5 / D07：默认关闭。 */
    private boolean enabled = false;

    /** 主题前缀；生产端与消费端必须同值（见 {@link OutboxMqTopics}）。 */
    private String topicPrefix = OutboxMqTopics.DEFAULT_TOPIC_PREFIX;

    /** 同步发送超时（毫秒）。超时按**投递失败**处理：outbox 行留 PENDING、积压可查（C12.4）。 */
    private int sendTimeoutMs = 3000;

    /** 消费失败的最大重投次数（由 broker 侧的重试机制承担；消费端据此判定进 DLQ）。 */
    private int maxReconsumeTimes = 16;

    /** 并发消费线程数。 */
    private int consumeThreadMax = 4;

    /** 去重账本保留小时数（清理用；不改变去重语义的**正确性**，只控制表体积）。 */
    private int dedupRetentionHours = 168;

    /**
     * 去重记录进入可再认领状态的租约秒数。
     *
     * <p><b>为什么必须有这个租约，而不是简单地"已存在就跳过"。</b>至少一次投递下真实会发生的
     * 事故是：消费者认领了去重键 → 进程崩溃在副作用之前 → 消息被重投 → 若把"已存在"一律当重复，
     * 这条事件就**永远丢了**（账上还写着有人认领过）。因此 {@code PROCESSING} 只在租约内视为
     * "有人在处理"，超时后允许再认领；而 {@code CONSUMED} 才是终局，永不再执行副作用。
     */
    private int claimLeaseSeconds = 300;

    /** 需要建立订阅的事件类型；默认覆盖 {@link OutboxMqTopics#RUN_EVENT_TYPES}。 */
    private List<String> eventTypes = new ArrayList<>(OutboxMqTopics.RUN_EVENT_TYPES);

    /** 消费容器实例名后缀，用于多实例隔离（留空则用客户端默认实例名）。 */
    private String consumerInstanceSuffix = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTopicPrefix() {
        return topicPrefix;
    }

    public void setTopicPrefix(String topicPrefix) {
        this.topicPrefix = topicPrefix;
    }

    public int getSendTimeoutMs() {
        return sendTimeoutMs;
    }

    public void setSendTimeoutMs(int sendTimeoutMs) {
        this.sendTimeoutMs = sendTimeoutMs;
    }

    public int getMaxReconsumeTimes() {
        return maxReconsumeTimes;
    }

    public void setMaxReconsumeTimes(int maxReconsumeTimes) {
        this.maxReconsumeTimes = maxReconsumeTimes;
    }

    public int getConsumeThreadMax() {
        return consumeThreadMax;
    }

    public void setConsumeThreadMax(int consumeThreadMax) {
        this.consumeThreadMax = consumeThreadMax;
    }

    public int getDedupRetentionHours() {
        return dedupRetentionHours;
    }

    public void setDedupRetentionHours(int dedupRetentionHours) {
        this.dedupRetentionHours = dedupRetentionHours;
    }

    public int getClaimLeaseSeconds() {
        return claimLeaseSeconds;
    }

    public void setClaimLeaseSeconds(int claimLeaseSeconds) {
        this.claimLeaseSeconds = claimLeaseSeconds;
    }

    public List<String> getEventTypes() {
        return eventTypes;
    }

    public void setEventTypes(List<String> eventTypes) {
        this.eventTypes = eventTypes;
    }

    public String getConsumerInstanceSuffix() {
        return consumerInstanceSuffix;
    }

    public void setConsumerInstanceSuffix(String consumerInstanceSuffix) {
        this.consumerInstanceSuffix = consumerInstanceSuffix;
    }

    /** 该事件类型对应的主题名（生产端与消费端共用同一规则）。 */
    public String topicFor(String eventType) {
        return OutboxMqTopics.topicFor(topicPrefix, eventType);
    }
}
