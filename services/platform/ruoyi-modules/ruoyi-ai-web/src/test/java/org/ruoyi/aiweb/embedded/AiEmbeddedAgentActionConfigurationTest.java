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

import com.nageoffer.ai.ragent.agent.runtime.AgentContract;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.ingest.ChatGateway;
import com.nageoffer.ai.ragent.runtime.RunAccessService;
import com.nageoffer.ai.ragent.runtime.RunEventAppender;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * R12 卡5 · F-W5-1：{@code agent.run} 受理契约的内嵌装配锚。
 *
 * <p><b>为什么必须有这一条。</b>{@code AgentContract} 是 {@code com.nageoffer.*} 包下的
 * {@code @Component}，platform 不做根包扫描 ⇒ 从未成为 bean ⇒ {@code RunAdmissionService}
 * 对 {@code action=agent.run} 走"非 P2_ACTIONS 且无 contract"分支，真实
 * {@code POST /runs} 恒被 400「action is not enabled in P2 core」挡死（F-W5-1）。
 * 少了本判据，装配再次缺位（删掉 {@code agentContract} bean）不会有任何测试变红。
 */
@Tag("dev")
class AiEmbeddedAgentActionConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiEmbeddedAgentActionConfiguration.class)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(RunLedgerDao.class, () -> mock(RunLedgerDao.class))
            .withBean(RunAccessService.class, () -> mock(RunAccessService.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(RunEventAppender.class, () -> mock(RunEventAppender.class))
            .withBean(RevocationGuard.class, () -> mock(RevocationGuard.class))
            .withBean(DeliveryPermits.class, () -> mock(DeliveryPermits.class))
            .withBean(ChatGateway.class, () -> mock(ChatGateway.class));

    @Test
    @DisplayName("p3.enabled=true：agent.run 受理契约必须装配（真实 POST /runs 不再 400）")
    void contractAssembledWhenP3Enabled() {
        runner.withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=local",
                        "p3.enabled=true")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context).hasSingleBean(AgentContract.class);
                    assertThat(context.getBeansOfType(com.nageoffer.ai.ragent.runtime.RuntimeActionContract.class).values())
                            .extracting(com.nageoffer.ai.ragent.runtime.RuntimeActionContract::action)
                            .contains("agent.run");
                });
    }

    @Test
    @DisplayName("p3.enabled 缺位：契约不装配（与同组其余 p3 bean 同门控）")
    void contractAbsentWithoutP3Gate() {
        runner.withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=local")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context).doesNotHaveBean(AgentContract.class);
                    assertThat(context.getBeansOfType(com.nageoffer.ai.ragent.runtime.RuntimeActionContract.class))
                            .isEmpty();
                });
    }
}
