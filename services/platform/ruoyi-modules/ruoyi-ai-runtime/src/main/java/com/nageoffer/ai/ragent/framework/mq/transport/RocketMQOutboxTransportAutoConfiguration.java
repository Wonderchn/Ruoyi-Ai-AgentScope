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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.runtime.stream.NotificationBus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQMessageListenerContainerRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * outbox → RocketMQ 传输的装配（C12.2/C12.5-5）。
 *
 * <p><b>默认关闭。</b>整类由 {@code p2.outbox.mq.enabled=true} 门控；属性缺失时本类不生效，
 * 应用里只有 {@code NotificationBusTransport} 一个传输，relay 行为与引入 MQ 之前逐字一致。
 * 这是 D07/C12.5-5 的要求：**验证通过后**才由 T0 决定是否默认开启。
 *
 * <p><b>为什么 {@code @ConditionalOnMissingBean} 必须写具体类而不是 {@code OutboxTransport}。</b>
 * 本应用已经有一个 {@code OutboxTransport} 实现（{@code NotificationBusTransport}）。
 * 若这里按**接口类型**判缺失，条件会因为"已有该类型的 bean"而不成立 ——
 * MQ 传输<b>永远不会被注册</b>，而且没有任何报错：运维看到的是
 * "开关打开了、应用也起来了、但跨实例没有消息"。所以这里按具体类型
 * {@link RocketMQOutboxTransport} 判缺失（同 BRIEF 里 {@code @ConditionalOnMissingBean}
 * 按返回类型推断那条坑）。
 *
 * <p><b>开启但没有 {@code RocketMQTemplate} 时必须响亮失败。</b>
 * 反向的坑是"静默降级"：开关打开了、但 {@code rocketmq.name-server} 没配，
 * starter 的自动装配不会建 {@code RocketMQTemplate}，于是 MQ 传输悄悄不注册、
 * 应用照常启动、outbox 照常被标成已投递——跨实例消息**全丢**而无人报警。
 * 因此这里主动取 {@code ObjectProvider<RocketMQTemplate>}，取不到就抛：
 * 配置说"要 MQ"，那就必须有 MQ，或者明确把开关关掉。
 */
@AutoConfiguration
@ConditionalOnClass(RocketMQTemplate.class)
@ConditionalOnProperty(name = "p2.outbox.mq.enabled", havingValue = "true")
@EnableConfigurationProperties(OutboxMqProperties.class)
public class RocketMQOutboxTransportAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RocketMQOutboxTransportAutoConfiguration.class);

    /**
     * RocketMQ 传输。
     *
     * @param templateProvider 明确用 {@code ObjectProvider} 而不是直接注入：直接注入的失败是
     *                         {@code NoSuchBeanDefinitionException}（消息里只有类名），
     *                         而这里能给出"开关开着但没配 name-server"的可操作提示。
     * @param objectMapperProvider Jackson 2 的 {@code ObjectMapper}；取不到时退化为一个默认实例。
     *                         <b>该退化只影响信封的序列化格式，不影响任何权威事实</b>，
     *                         所以不到"响亮失败"的程度，但会打 WARN 留痕。
     */
    @Bean
    @ConditionalOnMissingBean(RocketMQOutboxTransport.class)
    public RocketMQOutboxTransport rocketMqOutboxTransport(ObjectProvider<RocketMQTemplate> templateProvider,
                                                           OutboxMqProperties properties,
                                                           ObjectProvider<ObjectMapper> objectMapperProvider) {
        RocketMQTemplate template = templateProvider.getIfAvailable();
        if (template == null) {
            throw new IllegalStateException("p2.outbox.mq.enabled=true，但容器内没有 RocketMQTemplate"
                    + "（通常是 rocketmq.name-server 未配置）。拒绝静默降级为『只有进程内总线』："
                    + "那会让跨实例消息永久丢失而账上仍标记已投递（C2.3）。"
                    + "请配置 rocketmq.name-server，或把 p2.outbox.mq.enabled 设回 false。");
        }
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable();
        if (objectMapper == null) {
            log.warn("outbox MQ 传输未取得 Jackson ObjectMapper bean，退化为默认实例（仅影响信封序列化格式，不影响权威事实）");
            objectMapper = new ObjectMapper();
        }
        return new RocketMQOutboxTransport(template, properties, objectMapper);
    }

    // ---------------------------------------------------------------- 消费侧

    /**
     * 去重账本（V18 表）。**表不存在时故意不降级**：V18 未部署会在首次消费抛
     * {@code BadSqlGrammarException}，而不是悄悄"没有账本就都算首次"——那等于把去重关掉，
     * 在至少一次投递下直接变成重复副作用（C12.4）。
     */
    @Bean
    @ConditionalOnMissingBean(PgOutboxMqConsumeLedger.class)
    public PgOutboxMqConsumeLedger pgOutboxMqConsumeLedger(JdbcTemplate jdbcTemplate) {
        return new PgOutboxMqConsumeLedger(jdbcTemplate);
    }

    /**
     * 消费前重新授权（C2.2）。默认实现回读权威账本 {@code ai_run_event}，
     * **不沿用生产端授权快照**；事实暂时不可得时抛出让 broker 重投（不记拒绝）。
     */
    @Bean
    @ConditionalOnMissingBean(LedgerBackedOutboxMqReauthorizer.class)
    public OutboxMqReauthorizer ledgerBackedOutboxMqReauthorizer(JdbcTemplate jdbcTemplate) {
        return new LedgerBackedOutboxMqReauthorizer(jdbcTemplate);
    }

    /**
     * 默认副作用：唤醒本进程可见性通道。
     *
     * <p>用 {@code ObjectProvider<NotificationBus>} 并在缺失时**响亮失败**，而不是把
     * bus 变成可空：没有进程内通道时"消费成功"就只剩下账本上的一行，
     * SSE 订阅者什么都不会收到 —— 那是"账上成功、用户看不到"的假成功。
     */
    @Bean
    @ConditionalOnMissingBean(NotificationBusOutboxMqHandler.class)
    public OutboxMqHandler notificationBusOutboxMqHandler(ObjectProvider<NotificationBus> busProvider) {
        NotificationBus bus = busProvider.getIfAvailable();
        if (bus == null) {
            throw new IllegalStateException("p2.outbox.mq.enabled=true，但容器内没有 NotificationBus。"
                    + "MQ 消费者需要一个进程内可见性通道来转达唤醒；没有它就只能记账本、"
                    + "订阅者收不到任何东西（帐上成功、实际不可见）。");
        }
        return new NotificationBusOutboxMqHandler(bus);
    }

    /** 摄取管线：重新授权 → 原子认领去重 → 副作用 → 记终局。 */
    @Bean
    @ConditionalOnMissingBean(OutboxMqIngestService.class)
    public OutboxMqIngestService outboxMqIngestService(OutboxMqReauthorizer reauthorizer,
                                                       OutboxMqConsumeLedger ledger, OutboxMqHandler handler,
                                                       OutboxMqProperties properties) {
        return new OutboxMqIngestService(reauthorizer, ledger, handler, properties.getClaimLeaseSeconds(),
                new java.util.LinkedHashSet<>(properties.getEventTypes()));
    }

    /**
     * 按 eventType 逐个注册订阅（C12.3）。
     *
     * <p>不包一层 {@code @RocketMQMessageListener}：该注解只接受单个 topic，而主题按
     * eventType 分，11 个类型就是 11 个订阅。注册与自检在
     * {@link OutboxMqListenerRegistrar#afterSingletonsInstantiated()} 内完成。
     */
    @Bean
    @ConditionalOnMissingBean(OutboxMqListenerRegistrar.class)
    public OutboxMqListenerRegistrar outboxMqListenerRegistrar(
            ObjectProvider<RocketMQMessageListenerContainerRegistrar> registrarProvider,
            OutboxMqIngestService ingestService, OutboxMqProperties properties) {
        return new OutboxMqListenerRegistrar(registrarProvider, ingestService, properties);
    }
}
