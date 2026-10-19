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

package org.ruoyi.aiweb.embedded;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.authorization.TenantObjectReferenceRepository;
import com.nageoffer.ai.ragent.ingest.PrivateObjectStore;
import com.nageoffer.ai.ragent.media.config.MediaProperties;
import com.nageoffer.ai.ragent.media.delivery.MediaContentStore;
import com.nageoffer.ai.ragent.media.image.JdkWanxHttpTransport;
import com.nageoffer.ai.ragent.media.image.MediaImageProvider;
import com.nageoffer.ai.ragent.media.image.TongYiWanxImageGenerationService;
import com.nageoffer.ai.ragent.media.image.WanxHttpTransport;
import com.nageoffer.ai.ragent.media.task.JdbcMediaTaskRepository;
import com.nageoffer.ai.ragent.media.task.MediaController;
import com.nageoffer.ai.ragent.media.task.MediaTaskRepository;
import com.nageoffer.ai.ragent.media.task.MediaTaskService;
import com.nageoffer.ai.ragent.media.usage.MediaUsageMeter;
import com.nageoffer.ai.ragent.runtime.usage.EgressPolicy;
import com.nageoffer.ai.ragent.runtime.usage.ProviderSpendEnvelope;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

/**
 * F19:1/3/4/5 媒体工作台的内嵌装配（图片生成、服务端任务、私有取流、媒体计价）。
 *
 * <p><b>门控。</b>{@code ai.integration.enabled=true} + {@code transport=local}
 * + {@code p2.enabled=true} + {@code ai.embedded.media.enabled=true}。
 * 私有对象存储与交付许可只在 p2 运行时存在，因此不能更宽。
 *
 * <p><b>依赖缺失的语义。</b>{@link EgressPolicy} 与 {@link ProviderSpendEnvelope}
 * 由 worker 组提供；缺失时不装配假实现，而是在<b>提交付费调用前</b>以
 * {@code DEPENDENCY_UNAVAILABLE/BUDGET_EXCEEDED} 显式拒绝（零 provider 调用）。
 *
 * <p><b>凭据。</b>{@link MediaProperties} 的 apiKey 只来自环境变量引用；
 * 本组不读取、不打印、不落盘任何密钥。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiEmbeddedMediaConfiguration {

    /** 本地传输下的媒体面装配。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEmbeddedLocal
    public static class LocalTransport {

        /** P2 运行面门控（私有对象存储/交付许可只在 p2 存在）。 */
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnEmbeddedLocal
        @ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
        @ConditionalOnProperty(name = "ai.embedded.media.enabled", havingValue = "true", matchIfMissing = true)
        @EnableConfigurationProperties(MediaProperties.class)
        public static class P2MediaEnabled {

            @Bean
            @ConditionalOnMissingBean
            public WanxHttpTransport mediaWanxHttpTransport(MediaProperties properties) {
                return new JdkWanxHttpTransport(properties.getImage().getConnectTimeoutMs(),
                        properties.getImage().getRequestTimeoutMs());
            }

            @Bean
            @ConditionalOnMissingBean
            public TongYiWanxImageGenerationService tongYiWanxImageGenerationService(
                    MediaProperties properties, WanxHttpTransport transport, ObjectMapper objectMapper) {
                return new TongYiWanxImageGenerationService(properties, transport, objectMapper);
            }

            @Bean
            @ConditionalOnMissingBean
            public MediaTaskRepository mediaTaskRepository(JdbcTemplate jdbc) {
                return new JdbcMediaTaskRepository(jdbc);
            }

            @Bean
            @ConditionalOnMissingBean
            public MediaContentStore mediaContentStore(PrivateObjectStore objectStore,
                                                       ObjectProvider<TenantObjectReferenceRepository> objects) {
                TenantObjectReferenceRepository repository = objects.getIfAvailable();
                // 可选登记：撤权/删除沿 MEDIA_TASK 父链传播；缺省不装配时仍按任务属主限定取流。
                return new MediaContentStore(objectStore,
                        repository == null ? null : repository::register);
            }

            @Bean
            @ConditionalOnMissingBean
            public MediaUsageMeter mediaUsageMeter(ObjectProvider<ProviderSpendEnvelope> spend,
                                                   MediaProperties properties) {
                return new MediaUsageMeter(spend, properties);
            }

            @Bean
            @ConditionalOnMissingBean
            public MediaTaskService mediaTaskService(MediaTaskRepository repository,
                                                     MediaImageProvider imageProvider,
                                                     MediaContentStore contentStore,
                                                     MediaUsageMeter usage,
                                                     MediaProperties properties,
                                                     ObjectProvider<EgressPolicy> egress,
                                                     ObjectMapper objectMapper,
                                                     ObjectProvider<Clock> clock) {
                return new MediaTaskService(repository, imageProvider, contentStore, usage, properties,
                        egress.getIfAvailable(), objectMapper, clock.getIfAvailable(Clock::systemUTC));
            }

            @Bean
            @ConditionalOnMissingBean
            public MediaController mediaController(MediaTaskService mediaTaskService,
                                                   DeliveryPermits deliveryPermits) {
                return new MediaController(mediaTaskService, deliveryPermits);
            }
        }
    }
}
