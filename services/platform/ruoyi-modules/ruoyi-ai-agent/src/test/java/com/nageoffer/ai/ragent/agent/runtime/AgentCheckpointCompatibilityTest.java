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

package com.nageoffer.ai.ragent.agent.runtime;

import com.nageoffer.ai.ragent.ingest.ChatGateway;
import com.nageoffer.ai.ragent.runtime.P2FaultInjector;
import com.nageoffer.ai.ragent.runtime.RunAccessService;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.exec.RunExecution;
import com.nageoffer.ai.ragent.runtime.exec.RunExecutionGuard;
import com.nageoffer.ai.ragent.runtime.exec.RunExecutor;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import com.nageoffer.ai.ragent.runtime.usage.UsageLedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RW-20 · 「旧 checkpoint 兼容或显式拒绝」判据（F10/F15）。
 *
 * <p><b>本判据钉住的真实缺陷。</b>{@code AgentRunExecutor} 有两处"旧/异样 checkpoint"判定：
 * ① 执行契约版本 {@code run.executionVersion() != "p3-core-v1"}；
 * ② 绑定行的五个条件（{@code agent/engine/catalog/model/checkpoint_version}，在
 * {@link AgentLedger#compatible} 里）。
 * 改前 ① 返回 {@code Outcome.failed("AGENT_CHECKPOINT_INCOMPATIBLE")}（Worker 落
 * {@code error_code=AGENT_CHECKPOINT_INCOMPATIBLE}），而 ② 抛 {@code RUN_STATE_CONFLICT}
 * 被 Worker 的 {@code safeFail} 收成 {@code error_code=RUN_STATE_CONFLICT} ——
 * <b>同一类"旧 checkpoint"对客户端是两个不同事实</b>，工作台无法给出稳定文案，
 * 也无法把"旧版本需要重开"与"运行状态冲突需要刷新"分开。
 *
 * <p>修法：把兼容性判定提成纯函数 {@link AgentLedger#isCompatible}，执行器对两处
 * <b>显式选择同一个终态码</b>；授权门（{@code access.current}）保持独立一步，
 * <b>不得</b>被并进兼容性判定 —— 否则"授权不可用"会被误报成"checkpoint 不兼容"，
 * 把 fail-closed 的原因说错（本判据有专门负例）。
 */
@Tag("dev")
class AgentCheckpointCompatibilityTest {

    private static final String TENANT = "t1";
    private static final String MEMBER = "platform:t1:1001";
    private static final String USER = "1001";
    private static final String MODEL = "synthetic-echo";

    private AgentLedger ledger;
    private RunAccessService access;
    private AgentToolService tools;
    private ChatGateway gateway;
    private AgentRunExecutor executor;

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    @BeforeEach
    void setUp() throws Exception {
        ledger = mock(AgentLedger.class);
        access = mock(RunAccessService.class);
        tools = mock(AgentToolService.class);
        gateway = mock(ChatGateway.class);
        when(gateway.model(any())).thenReturn(MODEL);
        P3Properties properties = new P3Properties();
        // synthetic 绑定：让 bound(execution) 走标明的合成样本，不查配置权威（本判据不测权威）。
        properties.setSyntheticModel(true);
        executor = new AgentRunExecutor(ledger, access, tools, gateway,
                mock(UsageLedgerService.class), properties, mock(JdbcTemplate.class),
                mock(AgentProviderBoundary.class));
        // faults 是 @Autowired 字段：本判据不需要故障注入，但 execute() 会解引用它。
        Field faults = AgentRunExecutor.class.getDeclaredField("faults");
        faults.setAccessible(true);
        faults.set(executor, provider((P2FaultInjector) null));
    }

    private static RunRecord run(String executionVersion) {
        return new RunRecord(TENANT, "run-1", MEMBER, USER, "agent.run", "RUNNING", "hash", "key",
                "{\"text\":\"q\",\"mode\":\"read\"}", "{\"maxTokens\":2000}", executionVersion,
                1, 1, "[{\"type\":\"knowledge_base\",\"id\":\"kb1\"}]", 3L, 1L, 1, 7L,
                "worker-1", null, null, null, null, null, Instant.EPOCH, Instant.EPOCH, null);
    }

    private static AgentLedger.Binding binding(String agent, String engine, String catalog,
                                               String model, int checkpointVersion) {
        return new AgentLedger.Binding(MEMBER, agent, engine, model, catalog, 6, 6, 2000, 0, checkpointVersion);
    }

    private static AgentLedger.Binding frozen() {
        return binding(AgentLedger.AGENT, AgentLedger.ENGINE, AgentLedger.CATALOG, MODEL, 1);
    }

    private RunExecutor.Outcome execute(String executionVersion) {
        return executor.execute(new RunExecution(run(executionVersion), mock(RunExecutionGuard.class)));
    }

    // ------------------------------------------------------------ 纯判定矩阵

    @Test
    @DisplayName("仅五个条件全中才算兼容；任一不同都必须判为不兼容")
    void onlyTheExactFrozenBindingIsCompatible() {
        assertTrue(AgentLedger.isCompatible(frozen(), MODEL));
        assertFalse(AgentLedger.isCompatible(null, MODEL), "绑定缺失不得放行");
        assertFalse(AgentLedger.isCompatible(binding("other-agent", AgentLedger.ENGINE,
                AgentLedger.CATALOG, MODEL, 1), MODEL), "agent_version 不同");
        assertFalse(AgentLedger.isCompatible(binding(AgentLedger.AGENT, "9.9.9",
                AgentLedger.CATALOG, MODEL, 1), MODEL), "engine_version 不同");
        assertFalse(AgentLedger.isCompatible(binding(AgentLedger.AGENT, AgentLedger.ENGINE,
                "p3-tools-v2", MODEL, 1), MODEL), "tool_catalog 不同");
        assertFalse(AgentLedger.isCompatible(binding(AgentLedger.AGENT, AgentLedger.ENGINE,
                AgentLedger.CATALOG, "other-model", 1), MODEL), "绑定模型与当前模型不同");
        assertFalse(AgentLedger.isCompatible(binding(AgentLedger.AGENT, AgentLedger.ENGINE,
                AgentLedger.CATALOG, MODEL, 2), MODEL), "checkpoint_version != 1");
        assertFalse(AgentLedger.isCompatible(frozen(), null), "模型为 null 不得放行");
        assertFalse(AgentLedger.isCompatible(frozen(), "other-model"), "当前模型不同");
    }

    @Test
    @DisplayName("冻结常量就是判据里写的那三个：core-v1 / 2.0.2 / p3-tools-v1")
    void frozenIdentityConstantsArePartOfTheContract() {
        assertEquals("core-v1", AgentLedger.AGENT);
        assertEquals("2.0.2", AgentLedger.ENGINE);
        assertEquals("p3-tools-v1", AgentLedger.CATALOG);
    }

    // ------------------------------------------------------------ 执行器外显

    @Test
    @DisplayName("执行契约版本不匹配 ⇒ FAILED/AGENT_CHECKPOINT_INCOMPATIBLE，且不读绑定、不建引擎")
    void executionVersionMismatchIsExplicitlyRefused() {
        RunExecutor.Outcome outcome = execute("p3-core-v0");

        assertEquals("FAILED", outcome.status());
        assertEquals("AGENT_CHECKPOINT_INCOMPATIBLE", outcome.errorCode());
        verify(ledger, never()).binding(any());
        verify(ledger, never()).compatible(any(AgentLedger.Binding.class), any(), anyString());
        verifyNoInteractions(tools);
    }

    @Test
    @DisplayName("绑定行不兼容 ⇒ 与上一条**同一个**终态码（改前这条是 RUN_STATE_CONFLICT）")
    void bindingMismatchYieldsTheSameStableCode() {
        when(ledger.binding(any())).thenReturn(binding(AgentLedger.AGENT, AgentLedger.ENGINE,
                AgentLedger.CATALOG, MODEL, 2));

        RunExecutor.Outcome outcome = execute("p3-core-v1");

        assertEquals("FAILED", outcome.status());
        assertEquals("AGENT_CHECKPOINT_INCOMPATIBLE", outcome.errorCode(),
                "两处不兼容必须给出同一个客户端可依赖的事实");
        assertNotEquals("RUN_STATE_CONFLICT", outcome.errorCode());
        verify(ledger, never()).compatible(any(AgentLedger.Binding.class), any(), anyString());
        verifyNoInteractions(tools);
    }

    @Test
    @DisplayName("兼容时仍走同一条授权门；授权失败必须原样上抛，不得被吞成 checkpoint 不兼容")
    void authorizationFailureIsNeverReportedAsCheckpointIncompatibility() {
        when(ledger.binding(any())).thenReturn(frozen());
        doThrow(new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE))
                .when(ledger).compatible(any(AgentLedger.Binding.class), any(), anyString());

        RunApiException thrown = assertThrows(RunApiException.class, () -> execute("p3-core-v1"));

        assertEquals(RunErrorCode.AUTHORIZATION_UNAVAILABLE, thrown.errorCode());
        verify(ledger).compatible(any(AgentLedger.Binding.class), any(), anyString());
        verifyNoInteractions(tools);
    }

    @Test
    @DisplayName("兼容路径只读一次绑定行（判定与授权门共用同一份事实）")
    void compatiblePathReadsTheBindingOnce() {
        when(ledger.binding(any())).thenReturn(frozen());
        doThrow(new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE))
                .when(ledger).compatible(any(AgentLedger.Binding.class), any(), anyString());

        assertThrows(RunApiException.class, () -> execute("p3-core-v1"));

        verify(ledger, org.mockito.Mockito.times(1)).binding(any());
        // 兼容性判定与授权门用的是同一次读出的绑定（不是两次各自读一次）
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.times(1)).model(any());
    }
}
