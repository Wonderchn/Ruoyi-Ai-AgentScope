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
import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.ingest.DocumentDao;
import com.nageoffer.ai.ragent.runtime.P2FaultInjector;
import com.nageoffer.ai.ragent.runtime.P2RuntimeProperties;
import com.nageoffer.ai.ragent.runtime.RunAccessService;
import com.nageoffer.ai.ragent.runtime.RunAdmissionService;
import com.nageoffer.ai.ragent.runtime.RunEventAppender;
import com.nageoffer.ai.ragent.runtime.RunLifecycleService;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.usage.PlatformFactsClient;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import com.nageoffer.ai.ragent.runtime.web.RunApiExceptionHandler;
import com.nageoffer.ai.ragent.runtime.web.RunController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * runs 路由组的内嵌装配（E3 单元二收尾二）：P2 正式受理/生命周期路由
 * （{@code POST /runs}、{@code /runs/{id}/cancel}、{@code /runs/{id}/resume}）。
 *
 * <p>门控与独立 AI 应用一致：{@code ai.integration.enabled=true} +
 * {@code transport=local} + {@code p2.enabled=true}（P0.4 实验路径保持关闭；
 * P0.4 的 {@code RunAcceptanceController} 不装配）。
 *
 * <p>依赖图（全部为 platform 主数据源上的 JdbcTemplate/事务，无 HTTP）：
 * {@link RunLedgerDao}（运行账本）→ {@link RunEventAppender}（事件追加）→
 * {@link RunAdmissionService}（受理幂等/预算预占）与 {@link RunLifecycleService}
 * （取消/恢复，fence 语义）→ {@link RunController}。
 * {@link RunApiExceptionHandler} 是 AI 包作用域的 advice（basePackages 限定
 * {@code com.nageoffer.ai.ragent.runtime.web}/{@code ingest}），注册后只影响 AI 控制器。
 *
 * <p>Worker 执行链（RunWorker/执行器/模型与嵌入提供方）不在本组：需要真实外部提供方
 * 配置，属 E5/E6 逐能力单元；本组只装配受理与生命周期面。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiEmbeddedRunConfiguration {

    /** 本地传输下的 runs 装配。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "ai.integration.transport", havingValue = "local")
    public static class LocalTransport {

        /** P2 正式运行路径门控（与独立 AI 应用 {@code p2.enabled} 同一开关）。 */
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
        @EnableConfigurationProperties(P2RuntimeProperties.class)
        public static class P2Enabled {

            @Bean
            @ConditionalOnMissingBean
            public RunLedgerDao runLedgerDao(JdbcTemplate jdbc) {
                return new RunLedgerDao(jdbc);
            }

            @Bean
            @ConditionalOnMissingBean
            public RunEventAppender runEventAppender(RunLedgerDao runLedgerDao, ObjectMapper objectMapper) {
                return new RunEventAppender(runLedgerDao, objectMapper);
            }

            @Bean
            @ConditionalOnMissingBean
            public RunAccessService runAccessService(
                    ObjectProvider<AiResourceAuthorizationService> resources,
                    ObjectProvider<PlatformFactsClient> facts,
                    ObjectProvider<RunLedgerDao> ledger,
                    ObjectProvider<DocumentDao> documents) {
                return new RunAccessService(resources, facts, ledger, documents);
            }

            @Bean
            @ConditionalOnMissingBean
            public RunAdmissionService runAdmissionService(RunLedgerDao runLedgerDao, RunEventAppender runEventAppender,
                                                           P2RuntimeProperties properties,
                                                           PlatformTransactionManager transactionManager,
                                                           ObjectProvider<P2FaultInjector> faultInjector) {
                return new RunAdmissionService(runLedgerDao, runEventAppender, properties, transactionManager,
                        faultInjector);
            }

            @Bean
            @ConditionalOnMissingBean
            public RunLifecycleService runLifecycleService(RunLedgerDao runLedgerDao, RunEventAppender runEventAppender) {
                return new RunLifecycleService(runLedgerDao, runEventAppender);
            }

            @Bean
            @ConditionalOnMissingBean
            public DeliveryPermits deliveryPermits(ObjectProvider<
                    com.nageoffer.ai.ragent.framework.security.RevocationGuard> guard) {
                // 交付许可（runtime.web 域）：SSE/下载/上传共用，由 run 组提供
                return new DeliveryPermits(guard);
            }

            @Bean
            @ConditionalOnMissingBean
            public com.nageoffer.ai.ragent.runtime.stream.NotificationBus notificationBus() {
                return new com.nageoffer.ai.ragent.runtime.stream.NotificationBus();
            }

            @Bean
            @ConditionalOnMissingBean
            public com.nageoffer.ai.ragent.runtime.stream.RunEventStreamService runEventStreamService(
                    RunLedgerDao runLedgerDao, RunLifecycleService runLifecycleService,
                    com.nageoffer.ai.ragent.runtime.stream.NotificationBus notificationBus,
                    P2RuntimeProperties properties, ObjectMapper objectMapper,
                    ObjectProvider<AiResourceAuthorizationService> authorization,
                    DeliveryPermits deliveryPermits) {
                return new com.nageoffer.ai.ragent.runtime.stream.RunEventStreamService(runLedgerDao,
                        runLifecycleService, notificationBus, properties, objectMapper, authorization,
                        deliveryPermits);
            }

            @Bean
            @ConditionalOnMissingBean
            public com.nageoffer.ai.ragent.runtime.web.RunStreamController runStreamController(
                    com.nageoffer.ai.ragent.runtime.stream.RunEventStreamService runEventStreamService) {
                return new com.nageoffer.ai.ragent.runtime.web.RunStreamController(runEventStreamService);
            }

            @Bean
            @ConditionalOnMissingBean
            public RunController runController(RunAdmissionService runAdmissionService,
                                               RunLifecycleService runLifecycleService,
                                               P2RuntimeProperties properties,
                                               ObjectProvider<AiResourceAuthorizationService> authorization) {
                return new RunController(runAdmissionService, runLifecycleService, properties, authorization);
            }

            @Bean
            @ConditionalOnMissingBean
            public RunApiExceptionHandler runApiExceptionHandler() {
                return new RunApiExceptionHandler();
            }
        }
    }
}
