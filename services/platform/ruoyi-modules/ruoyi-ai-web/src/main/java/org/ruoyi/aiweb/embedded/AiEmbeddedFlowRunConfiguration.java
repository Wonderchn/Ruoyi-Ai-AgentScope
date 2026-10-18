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
import com.nageoffer.ai.ragent.flow.runtime.FlowRunContract;
import com.nageoffer.ai.ragent.flow.runtime.FlowRunController;
import com.nageoffer.ai.ragent.flow.runtime.FlowRunExecutor;
import com.nageoffer.ai.ragent.flow.runtime.FlowRuntimeController;
import com.nageoffer.ai.ragent.flow.runtime.FlowRuntimeRepository;
import com.nageoffer.ai.ragent.runtime.RunAccessService;
import com.nageoffer.ai.ragent.runtime.RunAdmissionService;
import com.nageoffer.ai.ragent.runtime.config.EngineModelAuthority;
import com.nageoffer.ai.ragent.runtime.config.RuntimeModelGatewayPort;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.exec.ProviderCallBoundary;
import com.nageoffer.ai.ragent.runtime.stream.RunEventStreamService;
import com.nageoffer.ai.ragent.runtime.usage.UsageLedgerService;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * F13:2 AIFlow 运行面（flow.run）的内嵌装配。
 *
 * <p><b>为什么需要这一组。</b>{@code FlowRunController}/{@code FlowRuntimeController}/
 * {@code FlowRunContract}/{@code FlowRunExecutor} 都在
 * {@code com.nageoffer.ai.ragent.flow.runtime} 下，而 platform 的扫描根是
 * {@code org.ruoyi} ⇒ 内嵌形态里它们从来不是 bean。三件套同形：
 * 显式装配 + {@code AutoConfiguration.imports} + {@code ROUTES}/{@code AiGatewayStreamController}
 * 精确登记。
 *
 * <p><b>闭包。</b>契约/控制器/投影只依赖 platform 主数据源 {@link JdbcTemplate}、
 * run 组既有 bean（{@link RunAccessService}/{@link RunAdmissionService}/
 * {@link RunEventStreamService}/{@link DeliveryPermits}）与运行权威端口；执行器另需
 * {@link RuntimeModelGatewayPort}/{@link UsageLedgerService}/{@link ProviderCallBoundary}
 * 与 worker 组的 {@code RunConfigBindingPort}。以上 bean 由 {@code AiEmbeddedRunConfiguration}
 * / {@code AiEmbeddedWorkerConfiguration} / {@code RuntimeAuthorityConfiguration} 提供；
 * 缺一即启动期/使用点响亮失败，不做半装配。
 *
 * <p><b>门控。</b>主装配跟随 {@code p2.enabled}；执行器另跟随
 * {@code p2.executor.mode=real}（与既有 RagChatExecutor/DocumentIngestExecutor 同口径）。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiEmbeddedFlowRunConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEmbeddedLocal
    @ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
    static class RuntimeEnabled {

        @Bean
        @ConditionalOnMissingBean
        public FlowRuntimeRepository flowRuntimeRepository(JdbcTemplate jdbc) {
            return new FlowRuntimeRepository(jdbc);
        }

        @Bean
        @ConditionalOnMissingBean
        public FlowRunContract flowRunContract(FlowRuntimeRepository flowRuntimeRepository,
                                               RunAccessService runAccessService,
                                               ObjectProvider<EngineModelAuthority> engineModelAuthority,
                                               RunLedgerDao runLedgerDao) {
            return new FlowRunContract(flowRuntimeRepository, runAccessService, engineModelAuthority,
                    runLedgerDao);
        }

        @Bean
        @ConditionalOnMissingBean
        public FlowRunController flowRunController(RunAdmissionService runAdmissionService,
                                                   RunEventStreamService runEventStreamService) {
            return new FlowRunController(runAdmissionService, runEventStreamService);
        }

        @Bean
        @ConditionalOnMissingBean
        public FlowRuntimeController flowRuntimeController(FlowRuntimeRepository flowRuntimeRepository,
                                                           DeliveryPermits deliveryPermits,
                                                           RunEventStreamService runEventStreamService,
                                                           ObjectMapper objectMapper) {
            return new FlowRuntimeController(flowRuntimeRepository, deliveryPermits, runEventStreamService,
                    objectMapper);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEmbeddedLocal
    @ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
    @ConditionalOnProperty(name = "p2.executor.mode", havingValue = "real", matchIfMissing = true)
    static class ExecutorEnabled {

        @Bean
        @ConditionalOnMissingBean
        public FlowRunExecutor flowRunExecutor(FlowRuntimeRepository flowRuntimeRepository,
                                               RunAccessService runAccessService,
                                               ObjectProvider<
                                                       com.nageoffer.ai.ragent.runtime.config.RunConfigBindingPort>
                                                       runConfigBindingPort,
                                               RuntimeModelGatewayPort runtimeModelGatewayPort,
                                               UsageLedgerService usageLedgerService,
                                               ProviderCallBoundary providerCallBoundary,
                                               ObjectMapper objectMapper) {
            return new FlowRunExecutor(flowRuntimeRepository, runAccessService, runConfigBindingPort,
                    runtimeModelGatewayPort, usageLedgerService, providerCallBoundary, objectMapper);
        }
    }
}
