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

import com.nageoffer.ai.ragent.agent.runtime.AgentActionController;
import com.nageoffer.ai.ragent.agent.runtime.AgentContract;
import com.nageoffer.ai.ragent.agent.runtime.AgentLedger;
import com.nageoffer.ai.ragent.agent.runtime.P3Properties;
import com.nageoffer.ai.ragent.agent.runtime.SandboxTicketClient;
import com.nageoffer.ai.ragent.ingest.ChatGateway;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.runtime.RunAccessService;
import com.nageoffer.ai.ragent.runtime.RunEventAppender;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * agent 路由组的内嵌装配（E3 单元二收尾六）：P3 受控动作面
 * （{@code GET /runs/{id}/actions}、{@code POST /runs/{id}/approvals}、
 * {@code GET|POST /runs/{id}/reconciliations/**}）。
 *
 * <p>门控与独立 AI 应用一致：{@code ai.integration.enabled=true} +
 * {@code transport=local} + {@code p3.enabled=true}。
 *
 * <p>依赖图：{@link AgentLedger}（动作账本，JdbcTemplate）、
 * {@link SandboxTicketClient}（沙箱票据客户端——指向独立沙箱进程的有界回环端点，
 * 属外部服务而非 platform 回调；{@code p3.sandbox} 未启用时以 SANDBOX_CLOSED fail-closed）、
 * run 组提供的 {@link RunLedgerDao}/{@link RunAccessService}/{@link RunEventAppender}/
 * {@link DeliveryPermits} 与本地端口 {@link RevocationGuard} →
 * {@link AgentActionController}。
 *
 * <p>AgentScope 引擎面（{@code AgentChatController}/{@code AgentConversationController}/
 * {@code AgentMetaController}，{@code @ConditionalOnAgentEngine}）不在本组：其路由不在
 * 网关白名单内，随 E4/E5 前端契约与 Worker 执行链一并装配。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiEmbeddedAgentActionConfiguration {

    /** 本地传输下的 P3 动作装配。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEmbeddedLocal
    public static class LocalTransport {

        /** P3 受控动作门控（与独立 AI 应用 {@code p3.enabled} 同一开关）。 */
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnEmbeddedLocal
        @ConditionalOnProperty(name = "p3.enabled", havingValue = "true")
        @EnableConfigurationProperties(P3Properties.class)
        public static class P3Enabled {

            @Bean
            @ConditionalOnMissingBean
            public AgentLedger agentLedger(JdbcTemplate jdbc, RunLedgerDao runLedgerDao,
                                           RunAccessService runAccessService) {
                return new AgentLedger(jdbc, runLedgerDao, runAccessService);
            }

            @Bean
            @ConditionalOnMissingBean
            public SandboxTicketClient sandboxTicketClient(P3Properties properties) {
                return new SandboxTicketClient(properties);
            }

            /**
             * R12 卡5 修复：注册 {@code agent.run} 的受理契约（{@link AgentContract}）。
             *
             * <p>{@code AgentContract} 是 {@code com.nageoffer.*} 包下的 {@code @Component}，
             * platform 不做根包扫描 ⇒ 从未成为 bean ⇒ {@code RunAdmissionService} 对
             * {@code action=agent.run} 走 "非 P2_ACTIONS 且无 contract" 分支，真实
             * {@code POST /runs} 恒被 400「action is not enabled in P2 core」挡死
             * （F-W5-1；W5 全链验收曾用契约夹具旁路）。此处按本组既有「显式 @Bean new」
             * 惯例补上装配，门控与同组其余 p3 bean 完全一致（p3.enabled）。
             */
            @Bean
            @ConditionalOnMissingBean
            public AgentContract agentContract(P3Properties properties, AgentLedger agentLedger,
                                               RunLedgerDao runLedgerDao, RunAccessService runAccessService,
                                               ChatGateway chatGateway) {
                return new AgentContract(properties, agentLedger, runLedgerDao, runAccessService, chatGateway);
            }

            @Bean
            @ConditionalOnMissingBean
            public AgentActionController agentActionController(AgentLedger agentLedger, RunLedgerDao runLedgerDao,
                                                               RunAccessService runAccessService, JdbcTemplate jdbc,
                                                               PlatformTransactionManager transactionManager,
                                                               P3Properties properties, RunEventAppender runEventAppender,
                                                               SandboxTicketClient sandboxTicketClient,
                                                               RevocationGuard revocation,
                                                               DeliveryPermits deliveryPermits) {
                return new AgentActionController(agentLedger, runLedgerDao, runAccessService, jdbc, transactionManager,
                        properties, runEventAppender, sandboxTicketClient,
                        revocation, deliveryPermits);
            }
        }
    }
}
