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

package com.nageoffer.ai.ragent.agent.orchestration;

import com.nageoffer.ai.ragent.agent.runtime.P3Properties;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.ingest.ChatGateway;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.config.RunConfigBinding;
import com.nageoffer.ai.ragent.runtime.config.RunConfigBindingPort;
import com.nageoffer.ai.ragent.runtime.exec.RunExecution;
import com.nageoffer.ai.ragent.runtime.exec.RunExecutionGuard;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import com.nageoffer.ai.ragent.runtime.usage.UsageLedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F14:0/1/3 · run 绑定模型调用（R10/R11/R13）。
 *
 * <p>R10：取消后不再外呼（已完成的调用最多 1 次 provider）；R11：token 预算耗尽不再调用；
 * R13：{@code usage.hasCall} 未结算 → NEEDS_RECONCILIATION，不自动重放。
 * 另覆盖结算/加 token/非 stop 收敛/提供方异常/synthetic 夹具。
 */
@Tag("dev")
class SupervisorModelCallTest {

    private static final String STEP = "route:1";

    private final ChatGateway gateway = mock(ChatGateway.class);
    private final UsageLedgerService usage = mock(UsageLedgerService.class);
    private final SupervisorLedger ledger = mock(SupervisorLedger.class);
    private final SupervisorProviderBoundary boundary = mock(SupervisorProviderBoundary.class);
    private final RunConfigBindingPort bindingPort = mock(RunConfigBindingPort.class);
    private final P3Properties properties = new P3Properties();
    private final RunExecutionGuard guard = mock(RunExecutionGuard.class);
    private final RevocationGuard revocation = mock(RevocationGuard.class);
    private final RevocationGuard.Operation operation = new RevocationGuard.Operation(revocation, "permit-1", "op-1");

    private RunConfigBinding binding;
    private RunRecord run;
    private RunExecution execution;
    private SupervisorModelCall modelCall;

    @BeforeEach
    void init() {
        binding = new RunConfigBinding("t1", "r1", "agent.supervisor", "rev-1", 1L, "deepseek",
                "deepseek-flash", "cat-v1", "params-1", "cred-1", "op-1", Instant.EPOCH, Map.of());
        run = new RunRecord("t1", "r1", "platform:t1:1001", "1001", "agent.supervisor", "RUNNING",
                null, "key", "{}", "{\"maxTokens\":3000}", "supervisor-v1", 1, 1, "[]", 1, 1, 1, 1,
                null, null, null, null, null, null, Instant.EPOCH, Instant.EPOCH, null);
        execution = new RunExecution(run, guard);
        modelCall = new SupervisorModelCall(gateway, usage, ledger, boundary, properties, bindingPort);

        when(bindingPort.requireBoundRevision("t1", "r1")).thenReturn(binding);
        when(gateway.provider(binding)).thenReturn("deepseek");
        when(gateway.model(binding)).thenReturn("deepseek-flash");
        when(ledger.binding(run)).thenReturn(new SupervisorLedger.Binding("platform:t1:1001",
                "supervisor-v1", "2.0.2", "deepseek-flash", "supervisor-tools-v1", 8, 1, 3000, 0, 1));
        when(usage.hasCall(anyString(), anyString(), anyString())).thenReturn(false);
        when(usage.startCall(anyString(), anyString(), anyInt(), anyString(), anyString(), anyString(),
                anyString(), anyString())).thenReturn("call-1");
        when(usage.unresolved(anyString(), anyString(), anyString())).thenReturn(false);
        when(guard.isCancelRequested()).thenReturn(false);
        when(guard.attempt()).thenReturn(1);
        when(guard.commitAtomic(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        when(boundary.enter(any(), anyString())).thenReturn(operation);
        when(gateway.stream(eq(binding), anyList(), anyInt(), any()))
                .thenReturn(new ChatGateway.ChatResult("{\"kind\":\"final\",\"answer\":\"a\"}", "req-1",
                        Map.of("total_tokens", 12), "stop"));
    }

    private SupervisorModelCall.RawReply call() {
        return modelCall.call(execution, STEP, "system", "user", 100, SupervisorProtocol.INVALID_ROUTE,
                () -> "{\"kind\":\"final\",\"answer\":\"synthetic\"}");
    }

    // ------------------------------------------------------------ R10

    @Test
    @DisplayName("R10：取消先于外呼 → CANCELLED，0 次 provider、0 次 startCall")
    void cancelBeforeCallDoesNotInvokeProvider() {
        when(guard.isCancelRequested()).thenReturn(true);

        SupervisorProtocol.Suspension suspension = assertThrows(SupervisorProtocol.Suspension.class, this::call);

        assertEquals("CANCELLED", suspension.status());
        verify(boundary, never()).enter(any(), anyString());
        verify(usage, never()).startCall(anyString(), anyString(), anyInt(), anyString(), anyString(),
                anyString(), anyString(), anyString());
        verify(gateway, never()).stream(any(), anyList(), anyInt(), any());
    }

    @Test
    @DisplayName("R10：调用返回后收到取消 → CANCELLED，provider 恰 1 次；已消耗 token 仍入账")
    void cancelAfterResponseStopsAtOneProviderCall() {
        when(guard.isCancelRequested()).thenReturn(false, true);

        SupervisorProtocol.Suspension suspension = assertThrows(SupervisorProtocol.Suspension.class, this::call);

        assertEquals("CANCELLED", suspension.status());
        verify(gateway, times(1)).stream(eq(binding), anyList(), anyInt(), any());
        verify(usage, times(1)).settle(eq("t1"), eq("call-1"), eq("req-1"), any());
        verify(ledger).addTokens(guard, 12);
        verify(revocation).release("permit-1", "op-1");
    }

    // ------------------------------------------------------------ R11

    @Test
    @DisplayName("R11：token 预算耗尽 → BUDGET_EXCEEDED，不再外呼")
    void tokenBudgetExhaustedDoesNotInvokeProvider() {
        when(ledger.binding(run)).thenReturn(new SupervisorLedger.Binding("platform:t1:1001",
                "supervisor-v1", "2.0.2", "deepseek-flash", "supervisor-tools-v1", 8, 1, 3000, 3000, 1));

        RunApiException exceeded = assertThrows(RunApiException.class, this::call);

        assertEquals(RunErrorCode.BUDGET_EXCEEDED, exceeded.errorCode());
        verify(boundary, never()).enter(any(), anyString());
        verify(gateway, never()).stream(any(), anyList(), anyInt(), any());
        verify(usage, never()).startCall(anyString(), anyString(), anyInt(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    // ------------------------------------------------------------ R13

    @Test
    @DisplayName("R13：同 step 已有 provider 调用未结算 → NEEDS_RECONCILIATION，不自动重放")
    void existingCallRequiresReconciliation() {
        when(usage.hasCall("t1", "r1", STEP)).thenReturn(true);

        SupervisorProtocol.Suspension suspension = assertThrows(SupervisorProtocol.Suspension.class, this::call);

        assertEquals("NEEDS_RECONCILIATION", suspension.status());
        verify(gateway, never()).stream(any(), anyList(), anyInt(), any());
        verify(boundary, never()).enter(any(), anyString());
    }

    @Test
    @DisplayName("settle 后仍有未决调用 → NEEDS_RECONCILIATION")
    void unresolvedSettlementRequiresReconciliation() {
        when(usage.unresolved("t1", "r1", STEP)).thenReturn(true);

        SupervisorProtocol.Suspension suspension = assertThrows(SupervisorProtocol.Suspension.class, this::call);

        assertEquals("NEEDS_RECONCILIATION", suspension.status());
    }

    @Test
    @DisplayName("usage 缺 total_tokens → NEEDS_RECONCILIATION（未知不按 0 入账）")
    void missingTotalTokensRequiresReconciliation() {
        when(gateway.stream(eq(binding), anyList(), anyInt(), any()))
                .thenReturn(new ChatGateway.ChatResult("{\"kind\":\"final\",\"answer\":\"a\"}", "req-1",
                        Map.of("prompt_tokens", 1), "stop"));

        SupervisorProtocol.Suspension suspension = assertThrows(SupervisorProtocol.Suspension.class, this::call);

        assertEquals("NEEDS_RECONCILIATION", suspension.status());
        verify(ledger, never()).addTokens(any(), anyInt());
    }

    // ------------------------------------------------------------ 结算/异常/合成

    @Test
    @DisplayName("正常调用：startCall→provider→settle→addTokens，返回 raw reply 与 usage")
    void normalCallSettlesAndAddsTokens() {
        SupervisorModelCall.RawReply reply = call();

        assertEquals("{\"kind\":\"final\",\"answer\":\"a\"}", reply.content());
        assertEquals("call-1", reply.callId());
        assertEquals("deepseek", reply.provider());
        assertEquals("deepseek-flash", reply.model());
        assertTrue(reply.usageJson().contains("\"total_tokens\":12"));
        verify(usage).startCall("t1", "r1", 1, STEP, UsageLedgerService.KIND_CHAT, "deepseek",
                "deepseek-flash", com.nageoffer.ai.ragent.runtime.CanonicalJson.sha256("user"));
        verify(usage).settle("t1", "call-1", "req-1", Map.of("total_tokens", 12));
        verify(ledger).addTokens(guard, 12);
        verify(revocation).release("permit-1", "op-1");
    }

    @Test
    @DisplayName("提供方异常 → markUnknown + NEEDS_RECONCILIATION，permit 仍关闭")
    void providerFailureMarksUnknown() {
        when(gateway.stream(eq(binding), anyList(), anyInt(), any()))
                .thenThrow(new IllegalStateException("provider down"));

        SupervisorProtocol.Suspension suspension = assertThrows(SupervisorProtocol.Suspension.class, this::call);

        assertEquals("NEEDS_RECONCILIATION", suspension.status());
        verify(usage).markUnknown("t1", "call-1");
        verify(revocation).release("permit-1", "op-1");
    }

    @Test
    @DisplayName("finishReason 非 stop → 协议 Violation（与父/子错误码一致）")
    void nonStopFinishIsViolation() {
        when(gateway.stream(eq(binding), anyList(), anyInt(), any()))
                .thenReturn(new ChatGateway.ChatResult("{}", "req-1", Map.of("total_tokens", 1), "length"));

        SupervisorProtocol.Violation violation = assertThrows(SupervisorProtocol.Violation.class, this::call);

        assertEquals(SupervisorProtocol.INVALID_ROUTE, violation.errorCode());
    }

    @Test
    @DisplayName("synthetic 模式：绑定走合成身份，回复由确定性夹具提供，不外呼 provider")
    void syntheticBranchUsesFixture() {
        properties.setSyntheticModel(true);
        when(gateway.provider(any())).thenReturn(com.nageoffer.ai.ragent.ingest.SyntheticChatGateway.SYNTHETIC_PROVIDER);
        when(gateway.model(any())).thenReturn(com.nageoffer.ai.ragent.ingest.SyntheticChatGateway.SYNTHETIC_MODEL);

        SupervisorModelCall.RawReply reply = call();

        assertEquals("{\"kind\":\"final\",\"answer\":\"synthetic\"}", reply.content());
        assertEquals("synthetic", reply.provider());
        verify(gateway, never()).stream(any(), anyList(), anyInt(), any());
        verify(ledger).addTokens(guard, 10);
        verify(usage).settle(eq("t1"), eq("call-1"), eq("synthetic-call-1"),
                eq(Map.of("synthetic", true, "total_tokens", 10)));
    }

    @Test
    @DisplayName("startCall 时失 fence → VERSION_CONFLICT 上抛，permit 关闭")
    void lostFenceAtStartCallPropagates() {
        // doThrow：避免 when(...) 重新触发既有 answer（lambda 参数在桩调用时为 null）。
        doThrow(new RunApiException(RunErrorCode.VERSION_CONFLICT)).when(guard).commitAtomic(any());

        RunApiException conflict = assertThrows(RunApiException.class, this::call);

        assertEquals(RunErrorCode.VERSION_CONFLICT, conflict.errorCode());
        verify(revocation).release("permit-1", "op-1");
        verify(usage, never()).settle(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("transcript 超过 30000 字符 → BUDGET_EXCEEDED（不截断后静默外呼）")
    void transcriptLimitIsEnforced() {
        String longPrompt = "x".repeat(SupervisorProtocol.MAX_TRANSCRIPT + 1);

        RunApiException exceeded = assertThrows(RunApiException.class, () -> modelCall.call(execution, STEP,
                "system", longPrompt, 100, SupervisorProtocol.INVALID_ROUTE, () -> "{}"));

        assertEquals(RunErrorCode.BUDGET_EXCEEDED, exceeded.errorCode());
        verify(boundary, never()).enter(any(), anyString());
        verify(gateway, never()).stream(any(), anyList(), anyInt(), any());
    }

    @Test
    @DisplayName("父/子共用同一绑定模型：bound 解析一次、provider/model 取自同一 binding")
    void parentAndChildShareTheBoundModel() {
        call();
        verify(gateway).model(binding);
        verify(gateway).provider(binding);
        List<ChatMessage> expected = List.of(ChatMessage.system("system"), ChatMessage.user("user"));
        verify(gateway).stream(eq(binding), eq(expected), eq(100), any());
    }
}
