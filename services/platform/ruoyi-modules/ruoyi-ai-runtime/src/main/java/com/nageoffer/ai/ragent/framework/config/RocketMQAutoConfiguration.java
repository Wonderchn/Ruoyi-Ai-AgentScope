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

package com.nageoffer.ai.ragent.framework.config;

import com.nageoffer.ai.ragent.framework.mq.producer.DelegatingTransactionListener;
import com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer;
import com.nageoffer.ai.ragent.framework.mq.producer.RocketMQProducerAdapter;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RocketMQ 消息队列自动装配配置
 *
 * <p><b>归属（E3/C6）</b>：本类只服务**独立运行的 ragent 应用**——它不在内嵌 platform 的
 * {@code AutoConfiguration.imports} 里，所以内嵌态不装配本类。
 *
 * <p><b>「platform 无 RocketMQ」这句旧注释已过时（T0 2026-10-06 实测）</b>：拆
 * {@code ruoyi-admin.jar} 可见 {@code rocketmq-spring-boot-starter-2.3.6}、
 * {@code rocketmq-client-5.3.2}、{@code rocketmq-acl-5.3.2} **已在 platform 单后端产物的
 * classpath 上**。内嵌侧缺的是**装配与传输接线**，不是依赖。
 *
 * <p>内嵌 outbox → RocketMQ 的传输由
 * {@code com.nageoffer.ai.ragent.framework.mq.transport.RocketMQOutboxTransport} 承担（T3，C12.2），
 * 门控 {@code p2.outbox.mq.enabled} **默认关闭**（C12.5-5），由 T0 依据验证证据决定开启。
 * 本类的边界不变：内嵌态不装配它。
 */
@Configuration
public class RocketMQAutoConfiguration {

    @Bean
    public DelegatingTransactionListener delegatingTransactionListener() {
        return new DelegatingTransactionListener();
    }

    @Bean
    public MessageQueueProducer messageQueueProducer(RocketMQTemplate rocketMQTemplate,
                                                     DelegatingTransactionListener transactionListener) {
        return new RocketMQProducerAdapter(rocketMQTemplate, transactionListener);
    }
}
