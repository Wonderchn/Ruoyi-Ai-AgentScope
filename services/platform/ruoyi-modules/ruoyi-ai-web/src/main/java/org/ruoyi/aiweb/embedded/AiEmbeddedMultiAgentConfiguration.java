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

import com.nageoffer.ai.ragent.agent.orchestration.SupervisorLedger;
import com.nageoffer.ai.ragent.agent.orchestration.SupervisorModelCall;
import com.nageoffer.ai.ragent.agent.orchestration.SupervisorProviderBoundary;
import com.nageoffer.ai.ragent.agent.orchestration.SupervisorRunContract;
import com.nageoffer.ai.ragent.agent.orchestration.SupervisorRunExecutor;
import com.nageoffer.ai.ragent.agent.runtime.P3Properties;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.ingest.ChatGateway;
import com.nageoffer.ai.ragent.runtime.RunAccessService;
import com.nageoffer.ai.ragent.runtime.config.RunConfigBindingPort;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.usage.EgressPolicy;
import com.nageoffer.ai.ragent.runtime.usage.UsageLedgerService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * F14:0/1/3 Supervisor 多 Agent 编排的内嵌装配。
 *
 * <p><b>为什么需要它。</b>编排类位于 {@code com.nageoffer.ai.ragent.agent.orchestration}
 * （不在 platform 的扫描根 {@code org.ruoyi} 下），必须由 {@code AutoConfiguration.imports}
 * 显式列举后经本组的 {@code @Bean} 成为 bean；否则 {@code POST /runs} 对
 * {@code action=agent.supervisor} 恒为"action is not enabled"，且 worker 无执行器。
 *
 * <p><b>门控（与同族内嵌配置一致）。</b>{@code ai.integration.enabled=true} +
 * {@code transport=local} + {@code p2.enabled} + {@code p3.enabled} 才装配受理契约与账本；
 * 执行器组再叠加 {@code p2.worker.enabled=true} 与 {@code p2.executor.mode=real}
 * （{@code synthetic} 模式由 {@code RunExecutorRegistry} 只认显式假执行器，Supervisor
 * 真实执行器不得抢跑）。{@code p2.executor.mode} 缺省视为 real（与既有 worker 组同口径）。
 *
 * <p><b>唯一共享面。</b>本类需要在
 * {@code ruoyi-ai-web/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 追加一行；该文件由协调者单写，执行者只交 {@code F14-SHARED.patch}。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiEmbeddedMultiAgentConfiguration {

    /** 本地传输下的多 Agent 编排装配。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEmbeddedLocal
    public static class LocalTransport {

        /**
         * 受理契约与统一账本：{@code p2.enabled} + {@code p3.enabled}。
         *
         * <p>受理契约必须在 worker 关闭时也存在：受理与执行是两个门（受理先发生）；
         * 没有 worker 时 run 会停在 QUEUED，而不是在受理侧被误判为未知动作。
         */
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnEmbeddedLocal
        @ConditionalOnProperty(name = {"p2.enabled", "p3.enabled"}, havingValue = "true")
        @EnableConfigurationProperties(P3Properties.class)
        public static class MultiAgentEnabled {

            @Bean
            @ConditionalOnMissingBean
            public SupervisorLedger supervisorLedger(JdbcTemplate jdbc) {
                return new SupervisorLedger(jdbc);
            }

            @Bean
            @ConditionalOnMissingBean
            public SupervisorRunContract supervisorRunContract(P3Properties properties,
                                                               SupervisorLedger supervisorLedger,
                                                               RunLedgerDao runLedgerDao,
                                                               RunAccessService runAccessService) {
                return new SupervisorRunContract(properties, supervisorLedger, runLedgerDao, runAccessService);
            }

            /**
             * 执行器组：受理之后真正的模型调用链，额外要求 worker 已开启且执行器为 real。
             *
             * <p>依赖（{@code ChatGateway}/{@code UsageLedgerService}/{@code EgressPolicy}）由
             * worker 组提供，所以本组必须晚于同一门控；{@code RunConfigBindingPort} 由
             * {@code RuntimeAuthorityConfiguration}（{@code ai.integration.enabled}）提供。
             */
            @Configuration(proxyBeanMethods = false)
            @ConditionalOnEmbeddedLocal
            @ConditionalOnProperty(name = {"p2.enabled", "p2.worker.enabled", "p3.enabled"}, havingValue = "true")
            @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
                    "'real'.equals('${p2.executor.mode:real}')")
            public static class SupervisorExecutorEnabled {

                @Bean
                @ConditionalOnMissingBean
                public SupervisorProviderBoundary supervisorProviderBoundary(RunAccessService runAccessService,
                                                                            RevocationGuard revocationGuard,
                                                                            EgressPolicy egressPolicy) {
                    return new SupervisorProviderBoundary(runAccessService, revocationGuard, egressPolicy);
                }

                @Bean
                @ConditionalOnMissingBean
                public SupervisorModelCall supervisorModelCall(ChatGateway chatGateway,
                                                              UsageLedgerService usageLedgerService,
                                                              SupervisorLedger supervisorLedger,
                                                              SupervisorProviderBoundary supervisorProviderBoundary,
                                                              P3Properties properties,
                                                              RunConfigBindingPort runConfigBindingPort) {
                    return new SupervisorModelCall(chatGateway, usageLedgerService, supervisorLedger,
                            supervisorProviderBoundary, properties, runConfigBindingPort);
                }

                @Bean
                @ConditionalOnMissingBean
                public SupervisorRunExecutor supervisorRunExecutor(SupervisorLedger supervisorLedger,
                                                                   SupervisorModelCall supervisorModelCall,
                                                                   P3Properties properties) {
                    return new SupervisorRunExecutor(supervisorLedger, supervisorModelCall, properties);
                }
            }
        }
    }
}
