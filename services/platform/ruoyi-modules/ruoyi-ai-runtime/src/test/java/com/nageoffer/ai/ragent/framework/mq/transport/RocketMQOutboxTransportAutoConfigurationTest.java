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
import com.nageoffer.ai.ragent.runtime.stream.NotificationBusTransport;
import com.nageoffer.ai.ragent.runtime.stream.OutboxTransport;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQMessageListenerContainerRegistrar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * C12.5-5 / C12.2：MQ 传输的**装配门控与共存语义**。
 *
 * <p><b>三条判据都是"静默故障"的防线。</b>
 * <ol>
 *   <li><b>默认关闭</b>：属性缺失时不得出现 MQ 传输 —— 否则 D07 的"验证后才默认开启"
 *       就被一次无意的装配改成了默认开启。</li>
 *   <li><b>与进程内总线共存</b>：这是本 seam 最容易踩的坑。
 *       {@code @ConditionalOnMissingBean} 若按**接口类型** {@code OutboxTransport} 判缺失，
 *       那么在已有 {@code NotificationBusTransport} 的应用里，MQ 传输会被**静默跳过**：
 *       应用起得来、开关是开的、日志没有异常，只是跨实例一条消息都不发。
 *       所以这里断言容器里**同时**有两个 {@code OutboxTransport}。</li>
 *   <li><b>开启但没有客户端时响亮失败</b>：反向的坑是"开关开着但 {@code rocketmq.name-server}
 *       没配"，MQ 传输悄悄不注册，outbox 照常被标成已投递 —— 跨实例消息全丢而无人报警（C2.3）。</li>
 * </ol>
 */
@Tag("dev")
class RocketMQOutboxTransportAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RocketMQOutboxTransportAutoConfiguration.class, BusTransportFixture.class);

    @Test
    @DisplayName("默认关闭：p2.outbox.mq.enabled 缺失时只装配进程内总线，MQ 传输缺席")
    void mqTransportIsAbsentByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(RocketMQOutboxTransport.class);
            assertThat(context.getBeansOfType(OutboxTransport.class))
                    .as("默认只应有一个传输（进程内总线）")
                    .hasSize(1);
        });
    }

    @Test
    @DisplayName("显式关闭：p2.outbox.mq.enabled=false 同样缺席（不是『有值就装』）")
    void mqTransportIsAbsentWhenExplicitlyDisabled() {
        runner.withPropertyValues("p2.outbox.mq.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(RocketMQOutboxTransport.class);
        });
    }

    @Test
    @DisplayName("开启且客户端就位：MQ 传输与进程内总线**同时**存在（不是二选一，也不被 @ConditionalOnMissingBean 压掉）")
    void mqTransportCoexistsWithInProcessBus() {
        runner.withPropertyValues("p2.outbox.mq.enabled=true")
                .withBean(RocketMQTemplate.class, () -> mock(RocketMQTemplate.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RocketMQOutboxTransport.class);
                    assertThat(context.getBeansOfType(OutboxTransport.class))
                            .as("C12.2：MQ 是附加传输，必须与 NotificationBusTransport 并存")
                            .hasSize(2);
                    assertThat(context.getBeansOfType(OutboxTransport.class).values())
                            .anyMatch(NotificationBusTransport.class::isInstance)
                            .anyMatch(RocketMQOutboxTransport.class::isInstance);

                    // 消费侧整链必须一起就位：缺任何一个都等于"消息收不下来"或"收下来也看不见"
                    assertThat(context).hasSingleBean(PgOutboxMqConsumeLedger.class);
                    assertThat(context).hasSingleBean(LedgerBackedOutboxMqReauthorizer.class);
                    assertThat(context).hasSingleBean(NotificationBusOutboxMqHandler.class);
                    assertThat(context).hasSingleBean(OutboxMqIngestService.class);
                    assertThat(context).hasSingleBean(OutboxMqListenerRegistrar.class);
                    // 订阅注册在 afterSingletonsInstantiated 里发生；context 刷新完成即为已注册
                    assertThat(context.getBean(OutboxMqListenerRegistrar.class).registeredEventTypes())
                            .as("每个配置的事件类型都必须注册到订阅")
                            .containsExactlyElementsOf(OutboxMqTopics.RUN_EVENT_TYPES);
                });
    }

    @Test
    @DisplayName("开启但缺 RocketMQTemplate ⇒ 启动失败并指向 rocketmq.name-server，不静默降级")
    void enabledWithoutTemplateFailsLoudly() {
        runner.withPropertyValues("p2.outbox.mq.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("rocketmq.name-server")
                    .hasStackTraceContaining("p2.outbox.mq.enabled");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class BusTransportFixture {

        /** 模拟应用里本来就有的进程内总线传输（C12.2：它必须保留）。 */
        @Bean
        OutboxTransport notificationBusTransport() {
            return new NotificationBusTransport(mock(NotificationBus.class));
        }

        /** 消费侧依赖：应用里本来就有的 NotificationBus 与 JdbcTemplate。 */
        @Bean
        NotificationBus notificationBus() {
            return mock(NotificationBus.class);
        }

        @Bean
        JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }

        /** starter 的注册入口（mock）：真实实现由 rocketmq starter 装配，这里避免依赖 broker 配置。 */
        @Bean
        RocketMQMessageListenerContainerRegistrar rocketMQMessageListenerContainerRegistrar() {
            return mock(RocketMQMessageListenerContainerRegistrar.class);
        }
    }
}
