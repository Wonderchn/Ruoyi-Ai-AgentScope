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
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.ingest.ChatGateway;
import com.nageoffer.ai.ragent.runtime.RunAccessService;
import com.nageoffer.ai.ragent.runtime.config.RunConfigBindingPort;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.usage.EgressPolicy;
import com.nageoffer.ai.ragent.runtime.usage.UsageLedgerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * F14:0/1/3 · 内嵌装配门控（R15/R16）。
 *
 * <p>p2+p3 装配受理契约与统一账本；执行器组再要求 {@code p2.worker.enabled=true} 与
 * {@code p2.executor.mode=real}。synthetic 执行器模式下 Supervisor 真实执行器不得注册
 * （{@code RunExecutorRegistry} 只认显式假执行器）。
 */
@Tag("dev")
class AiEmbeddedMultiAgentConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiEmbeddedMultiAgentConfiguration.class)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(RunLedgerDao.class, () -> mock(RunLedgerDao.class))
            .withBean(RunAccessService.class, () -> mock(RunAccessService.class))
            .withBean(RevocationGuard.class, () -> mock(RevocationGuard.class))
            .withBean(EgressPolicy.class, EgressPolicy::new)
            .withBean(ChatGateway.class, () -> mock(ChatGateway.class))
            .withBean(UsageLedgerService.class, () -> mock(UsageLedgerService.class))
            .withBean(RunConfigBindingPort.class, () -> mock(RunConfigBindingPort.class));

    private static final String[] BASE = {
            "ai.integration.enabled=true", "ai.integration.transport=local",
            "p2.enabled=true", "p3.enabled=true"};

    private ApplicationContextRunner configured(String... extra) {
        String[] properties = new String[BASE.length + extra.length];
        System.arraycopy(BASE, 0, properties, 0, BASE.length);
        System.arraycopy(extra, 0, properties, BASE.length, extra.length);
        return runner.withPropertyValues(properties);
    }

    @Test
    @DisplayName("R15：p2+p3 装配受理契约与统一账本；worker 未开时不装配执行器组")
    void contractAndLedgerAssembledWithoutWorker() {
        configured().run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context).hasSingleBean(SupervisorLedger.class);
            assertThat(context).hasSingleBean(SupervisorRunContract.class);
            assertThat(context).doesNotHaveBean(SupervisorRunExecutor.class);
            assertThat(context).doesNotHaveBean(SupervisorModelCall.class);
            assertThat(context).doesNotHaveBean(SupervisorProviderBoundary.class);
            assertThat(context.getBeansOfType(com.nageoffer.ai.ragent.runtime.RuntimeActionContract.class).values())
                    .extracting(com.nageoffer.ai.ragent.runtime.RuntimeActionContract::action)
                    .contains("agent.supervisor");
        });
    }

    @Test
    @DisplayName("R16：worker 打开且 executor.mode=real 时装配完整执行器组")
    void executorAssembledWhenWorkerAndRealMode() {
        configured("p2.worker.enabled=true", "p2.executor.mode=real").run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context).hasSingleBean(SupervisorLedger.class);
            assertThat(context).hasSingleBean(SupervisorRunContract.class);
            assertThat(context).hasSingleBean(SupervisorProviderBoundary.class);
            assertThat(context).hasSingleBean(SupervisorModelCall.class);
            assertThat(context).hasSingleBean(SupervisorRunExecutor.class);
            assertThat(context.getBeansOfType(SupervisorRunExecutor.class).values())
                    .extracting(SupervisorRunExecutor::action)
                    .containsExactly("agent.supervisor");
        });
    }

    @Test
    @DisplayName("R16：executor.mode=real 缺省（matchIfMissing）按 real 装配")
    void executorAssembledWithDefaultRealMode() {
        configured("p2.worker.enabled=true").run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context).hasSingleBean(SupervisorRunExecutor.class);
        });
    }

    @Test
    @DisplayName("R16：synthetic 执行器模式不注册 Supervisor 真实执行器")
    void executorAbsentInSyntheticMode() {
        configured("p2.worker.enabled=true", "p2.executor.mode=synthetic").run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context).hasSingleBean(SupervisorRunContract.class);
            assertThat(context).hasSingleBean(SupervisorLedger.class);
            assertThat(context).doesNotHaveBean(SupervisorRunExecutor.class);
            assertThat(context).doesNotHaveBean(SupervisorModelCall.class);
            assertThat(context).doesNotHaveBean(SupervisorProviderBoundary.class);
        });
    }

    @Test
    @DisplayName("R15：p3 关闭时受理契约不装配（同组其余 p3 bean 同门控）")
    void nothingAssembledWithoutP3() {
        runner.withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=local",
                        "p2.enabled=true", "p2.worker.enabled=true")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context).doesNotHaveBean(SupervisorRunContract.class);
                    assertThat(context).doesNotHaveBean(SupervisorLedger.class);
                    assertThat(context).doesNotHaveBean(SupervisorRunExecutor.class);
                });
    }

    @Test
    @DisplayName("R15：p2 关闭时受理契约不装配")
    void nothingAssembledWithoutP2() {
        runner.withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=local",
                        "p3.enabled=true", "p2.worker.enabled=true")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context).doesNotHaveBean(SupervisorRunContract.class);
                    assertThat(context).doesNotHaveBean(SupervisorLedger.class);
                });
    }

    @Test
    @DisplayName("R15：非 local 传输不装配")
    void nothingAssembledOutsideLocalTransport() {
        runner.withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=http",
                        "p2.enabled=true", "p3.enabled=true", "p2.worker.enabled=true")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context).doesNotHaveBean(SupervisorRunContract.class);
                    assertThat(context).doesNotHaveBean(SupervisorRunExecutor.class);
                });
    }
}
