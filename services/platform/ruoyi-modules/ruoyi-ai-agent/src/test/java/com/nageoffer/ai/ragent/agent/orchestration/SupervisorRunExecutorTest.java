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
import com.nageoffer.ai.ragent.runtime.CanonicalJson;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.RunEventAppender;
import com.nageoffer.ai.ragent.runtime.config.RunConfigBinding;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.exec.RunExecution;
import com.nageoffer.ai.ragent.runtime.exec.RunExecutionGuard;
import com.nageoffer.ai.ragent.runtime.exec.RunExecutor;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F14:0/1/3 · Supervisor 显式委派环（§2.2/§2.3）。
 *
 * <p>覆盖 R6（route:1→child→route:2→child→final 的 step/事件顺序）、R7（未知 alias 零子调用）、
 * R8（父非法 JSON 零子调用）、R9（子工具字段零 step 写入）、R12（已完成 step 复用不重呼）、
 * R14（失 fence 上抛）、委派上限与终态 result 形状。
 */
@Tag("dev")
class SupervisorRunExecutorTest {

    private static final String INPUT = "{\"text\":\"question\",\"agents\":["
            + "{\"name\":\"analyst\",\"instruction\":\"analyze\"},"
            + "{\"name\":\"critic\",\"instruction\":\"critique\"}]}";
    private static final String BUDGET = "{\"maxWallClockSeconds\":300,\"maxSteps\":9,\"maxTokens\":3000}";

    private final SupervisorLedger ledger = mock(SupervisorLedger.class);
    private final SupervisorModelCall modelCall = mock(SupervisorModelCall.class);
    private final P3Properties properties = new P3Properties();
    private final RunExecutionGuard guard = mock(RunExecutionGuard.class);
    private final Map<String, String> replies = new HashMap<>();
    private final List<String> events = new ArrayList<>();

    private SupervisorRunExecutor executor;
    private RunConfigBinding binding;
    private RunRecord run;
    private RunExecution execution;

    @BeforeEach
    void init() {
        binding = new RunConfigBinding("t1", "r1", "agent.supervisor", "rev-1", 1L, "deepseek",
                "deepseek-flash", "cat-v1", "params-1", "cred-1", "op-1", Instant.EPOCH, Map.of());
        run = run(Instant.now(), "supervisor-v1", BUDGET);
        execution = new RunExecution(run, guard);
        executor = new SupervisorRunExecutor(ledger, modelCall, properties);

        when(modelCall.bound(execution)).thenReturn(binding);
        when(modelCall.model(binding)).thenReturn("deepseek-flash");
        when(ledger.binding(run)).thenReturn(new SupervisorLedger.Binding("platform:t1:1001",
                "supervisor-v1", "2.0.2", "deepseek-flash", "supervisor-tools-v1", 8, 1, 3000, 0, 1));
        when(guard.findStep(anyString())).thenReturn(Optional.empty());
        when(guard.isCancelRequested()).thenReturn(false);
        when(guard.commitAtomic(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        when(guard.commitStep(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> step((String) invocation.getArgument(0)));
        when(guard.appendEvent(anyString(), any())).thenAnswer(invocation -> {
            Map<?, ?> payload = invocation.getArgument(1);
            events.add(invocation.getArgument(0) + ":" + payload.get("stepId"));
            return 1L;
        });
        when(modelCall.call(any(), anyString(), anyString(), anyString(), anyInt(), anyString(), any()))
                .thenAnswer(invocation -> reply((String) invocation.getArgument(1)));
    }

    private RunRecord run(Instant startedAt, String executionVersion, String budget) {
        return new RunRecord("t1", "r1", "platform:t1:1001", "1001", "agent.supervisor", "RUNNING",
                null, "key", INPUT, budget, executionVersion, 1, 1, "[]", 1, 1, 1, 1,
                null, null, null, null, null, null, Instant.EPOCH, startedAt, null);
    }

    private static RunLedgerDao.StepRow step(String stepId) {
        return new RunLedgerDao.StepRow(stepId, 1, 1, "name", "COMPLETED", "{}", "hash", "{}", 1L,
                Instant.EPOCH, Instant.EPOCH);
    }

    private static RunLedgerDao.StepRow step(String stepId, String refJson) {
        return new RunLedgerDao.StepRow(stepId, 1, 1, "name", "COMPLETED", refJson, "hash", "{}", 1L,
                Instant.EPOCH, Instant.EPOCH);
    }

    private static String delegate(String agent, String task, String reason) {
        return "{\"kind\":\"delegate\",\"agent\":\"" + agent + "\",\"task\":\"" + task
                + "\",\"reason\":\"" + reason + "\"}";
    }

    private static String child(String answer) {
        return "{\"kind\":\"final\",\"answer\":\"" + answer + "\"}";
    }

    private static String finalAnswer(String answer) {
        return "{\"kind\":\"final\",\"answer\":\"" + answer + "\"}";
    }

    private SupervisorModelCall.RawReply reply(String stepId) {
        String content = replies.get(stepId);
        if (content == null) {
            throw new IllegalStateException("unexpected model call for step " + stepId);
        }
        return new SupervisorModelCall.RawReply(content, "call-" + stepId, "deepseek", "deepseek-flash",
                "{\"total_tokens\":10}");
    }

    private void twoDelegationsThenFinal() {
        replies.put("route:1", delegate("analyst", "do analysis", "r1"));
        replies.put("child:analyst:1", child("analysis answer"));
        replies.put("route:2", delegate("critic", "critique it", "r2"));
        replies.put("child:critic:2", child("critique answer"));
        replies.put("route:3", finalAnswer("final answer"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(RunExecutor.Outcome outcome) {
        return (Map<String, Object>) outcome.terminalResult();
    }

    private void assertErrorEvent(String type, String stepId, String agent, String errorCode) {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(guard, times(1)).appendEvent(eq(type), payload.capture());
        Map<?, ?> error = (Map<?, ?>) payload.getValue();
        assertEquals(stepId, error.get("stepId"));
        if (agent != null) {
            assertEquals(agent, error.get("agent"));
        }
        assertEquals(errorCode, error.get("errorCode"));
    }

    private static String json(Object value) {
        try {
            return CanonicalJson.strictMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------ R6

    @Test
    @DisplayName("R6：route:1→child→route:2→child→final；父/子共用 run 绑定模型，终态 result 形状完整")
    void delegationLoopWritesTheFrozenStepAndEventSequence() {
        twoDelegationsThenFinal();

        RunExecutor.Outcome outcome = executor.execute(execution);

        assertEquals("SUCCEEDED", outcome.status());
        Map<String, Object> result = result(outcome);
        assertEquals("final answer", result.get("answer"));
        assertEquals("2.0.2", result.get("engineVersion"));
        assertEquals("supervisor-v1", result.get("supervisorVersion"));
        assertEquals(false, result.get("synthetic"));
        List<Map<String, Object>> delegations = (List<Map<String, Object>>) result.get("delegations");
        assertEquals(2, delegations.size());
        assertEquals("analyst", delegations.get(0).get("agent"));
        assertEquals("child:analyst:1", delegations.get(0).get("stepId"));
        assertEquals("call-child:analyst:1", delegations.get(0).get("callId"));
        assertEquals("critic", delegations.get(1).get("agent"));
        assertEquals("child:critic:2", delegations.get(1).get("stepId"));

        InOrder order = inOrder(guard);
        order.verify(guard).commitStep(eq("route:1"), eq("supervisor.route"), anyString(), anyString(), eq("{}"));
        order.verify(guard).commitStep(eq("child:analyst:1"), eq("supervisor.child"), anyString(), anyString(),
                anyString());
        order.verify(guard).commitStep(eq("route:2"), eq("supervisor.route"), anyString(), anyString(), eq("{}"));
        order.verify(guard).commitStep(eq("child:critic:2"), eq("supervisor.child"), anyString(), anyString(),
                anyString());
        order.verify(guard).commitStep(eq("supervisor-final"), eq("supervisor.final"), anyString(), anyString(),
                eq("{}"));

        assertEquals(List.of(
                RunEventAppender.EVENT_STEP_STARTED + ":route:1",
                RunEventAppender.EVENT_STEP_STARTED + ":child:analyst:1",
                RunEventAppender.EVENT_STEP_COMPLETED + ":route:1",
                RunEventAppender.EVENT_STEP_COMPLETED + ":child:analyst:1",
                RunEventAppender.EVENT_STEP_STARTED + ":route:2",
                RunEventAppender.EVENT_STEP_STARTED + ":child:critic:2",
                RunEventAppender.EVENT_STEP_COMPLETED + ":route:2",
                RunEventAppender.EVENT_STEP_COMPLETED + ":child:critic:2",
                RunEventAppender.EVENT_STEP_STARTED + ":supervisor-final",
                RunEventAppender.EVENT_STEP_COMPLETED + ":supervisor-final"), events);

        // 父/子 step 的 ref 形状（§2.2）：route 带 taskHash/policy，child 带 answer/callId/provider/model。
        ArgumentCaptor<String> routeRef = ArgumentCaptor.forClass(String.class);
        verify(guard).commitStep(eq("route:1"), eq("supervisor.route"), routeRef.capture(), anyString(), eq("{}"));
        assertTrue(routeRef.getValue().contains("\"taskHash\":\"" + SupervisorProtocol.hash("do analysis") + "\""));
        assertTrue(routeRef.getValue().contains("\"policy\":\"admitted-roster\""));
        ArgumentCaptor<String> childRef = ArgumentCaptor.forClass(String.class);
        verify(guard).commitStep(eq("child:analyst:1"), eq("supervisor.child"), childRef.capture(), anyString(),
                anyString());
        assertTrue(childRef.getValue().contains("\"answer\":\"analysis answer\""));
        assertTrue(childRef.getValue().contains("\"provider\":\"deepseek\""));
        assertTrue(childRef.getValue().contains("\"model\":\"deepseek-flash\""));
    }

    // ------------------------------------------------------------ R7/R8

    @Test
    @DisplayName("R7：未知 alias → SUPERVISOR_UNKNOWN_AGENT，父只调用一次、零子调用、零 step")
    void unknownAgentFailsWithoutChildCall() {
        replies.put("route:1", delegate("ghost", "t", "r"));

        RunExecutor.Outcome outcome = executor.execute(execution);

        assertEquals("FAILED", outcome.status());
        assertEquals("SUPERVISOR_UNKNOWN_AGENT", outcome.errorCode());
        verify(modelCall, times(1)).call(any(), anyString(), anyString(), anyString(), anyInt(), anyString(), any());
        verify(guard, never()).commitStep(anyString(), anyString(), anyString(), anyString(), anyString());
        assertTrue(events.contains(RunEventAppender.EVENT_ERROR + ":route:1"));
    }

    @Test
    @DisplayName("R8：父非法 JSON → SUPERVISOR_INVALID_ROUTE，零子调用、零 step")
    void invalidRouteJsonFailsWithoutChildCall() {
        replies.put("route:1", "not-json");

        RunExecutor.Outcome outcome = executor.execute(execution);

        assertEquals("FAILED", outcome.status());
        assertEquals("SUPERVISOR_INVALID_ROUTE", outcome.errorCode());
        verify(modelCall, times(1)).call(any(), anyString(), anyString(), anyString(), anyInt(), anyString(), any());
        verify(guard, never()).commitStep(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    // ------------------------------------------------------------ R9

    @Test
    @DisplayName("R9：子工具字段 → SUPERVISOR_CHILD_TOOL_DENIED，零 step（route 也不提交）")
    void childToolIsDeniedBeforeAnyStepCommit() {
        replies.put("route:1", delegate("analyst", "do analysis", "r1"));
        replies.put("child:analyst:1", "{\"kind\":\"final\",\"answer\":\"x\",\"tool\":\"shell\"}");

        RunExecutor.Outcome outcome = executor.execute(execution);

        assertEquals("FAILED", outcome.status());
        assertEquals("SUPERVISOR_CHILD_TOOL_DENIED", outcome.errorCode());
        verify(guard, never()).commitStep(anyString(), anyString(), anyString(), anyString(), anyString());
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(guard).appendEvent(eq(RunEventAppender.EVENT_ERROR), payload.capture());
        Map<?, ?> error = (Map<?, ?>) payload.getValue();
        assertEquals("child:analyst:1", error.get("stepId"));
        assertEquals("analyst", error.get("agent"));
        assertEquals("SUPERVISOR_CHILD_TOOL_DENIED", error.get("errorCode"));
    }

    // ------------------------------------------------------------ R12

    @Test
    @DisplayName("R12：supervisor-final 已完成 → 复用 ref（含 engineVersion/supervisorVersion），不重呼任何模型")
    void completedFinalStepIsReused() {
        String ref = json(SupervisorProtocol.finalRef("done", List.of(), false));
        when(guard.findStep("supervisor-final")).thenReturn(Optional.of(step("supervisor-final", ref)));

        RunExecutor.Outcome outcome = executor.execute(execution);

        assertEquals("SUCCEEDED", outcome.status());
        assertEquals("done", result(outcome).get("answer"));
        // F-2：复用路径的终态 result 与 fresh final 同形，版本键不得缺失。
        assertEquals("2.0.2", result(outcome).get("engineVersion"));
        assertEquals("supervisor-v1", result(outcome).get("supervisorVersion"));
        verify(modelCall, never()).call(any(), anyString(), anyString(), anyString(), anyInt(), anyString(), any());
    }

    @Test
    @DisplayName("R12：route:1+child 已完成 → 不重呼第 1 对；只调用 route:2 收敛")
    void completedDelegationIsReused() {
        String routeRef = "{\"agent\":\"analyst\",\"taskHash\":\"h\",\"reason\":\"r\","
                + "\"policy\":\"admitted-roster\",\"callId\":\"call-0\",\"model\":\"deepseek-flash\"}";
        String childRef = "{\"answer\":\"reused answer\",\"callId\":\"call-1\",\"provider\":\"deepseek\","
                + "\"model\":\"deepseek-flash\"}";
        when(guard.findStep("route:1")).thenReturn(Optional.of(step("route:1", routeRef)));
        when(guard.findStep("child:analyst:1")).thenReturn(Optional.of(step("child:analyst:1", childRef)));
        replies.put("route:2", finalAnswer("done after reuse"));

        RunExecutor.Outcome outcome = executor.execute(execution);

        assertEquals("SUCCEEDED", outcome.status());
        assertEquals("done after reuse", result(outcome).get("answer"));
        List<Map<String, Object>> delegations = (List<Map<String, Object>>) result(outcome).get("delegations");
        assertEquals(1, delegations.size());
        assertEquals("analyst", delegations.get(0).get("agent"));
        assertEquals("child:analyst:1", delegations.get(0).get("stepId"));
        assertEquals("call-1", delegations.get(0).get("callId"));
        ArgumentCaptor<String> stepId = ArgumentCaptor.forClass(String.class);
        verify(modelCall, times(1)).call(any(), stepId.capture(), anyString(), anyString(), anyInt(), anyString(),
                any());
        assertEquals("route:2", stepId.getValue());
    }

    // ------------------------------------------------------------ F-1：调用点收敛

    @Test
    @DisplayName("F-1：父模型调用抛协议 Violation（非 stop 等）→ 稳定 FAILED 码 + run.error，不逃逸")
    void parentCallViolationIsReportedWithStableCode() {
        doThrow(new SupervisorProtocol.Violation(SupervisorProtocol.INVALID_ROUTE))
                .when(modelCall).call(any(), eq("route:1"), anyString(), anyString(), anyInt(), anyString(), any());

        RunExecutor.Outcome outcome = executor.execute(execution);

        assertEquals("FAILED", outcome.status());
        assertEquals("SUPERVISOR_INVALID_ROUTE", outcome.errorCode());
        assertErrorEvent(RunEventAppender.EVENT_ERROR, "route:1", null, "SUPERVISOR_INVALID_ROUTE");
        verify(guard, never()).commitStep(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("F-1：子模型调用抛协议 Violation → 稳定 FAILED 码 + run.error(stepId,agent)")
    void childCallViolationIsReportedWithStepAndAgent() {
        replies.put("route:1", delegate("analyst", "do analysis", "r1"));
        doThrow(new SupervisorProtocol.Violation(SupervisorProtocol.CHILD_TOOL_DENIED))
                .when(modelCall).call(any(), eq("child:analyst:1"), anyString(), anyString(), anyInt(),
                        anyString(), any());

        RunExecutor.Outcome outcome = executor.execute(execution);

        assertEquals("FAILED", outcome.status());
        assertEquals("SUPERVISOR_CHILD_TOOL_DENIED", outcome.errorCode());
        assertErrorEvent(RunEventAppender.EVENT_ERROR, "child:analyst:1", "analyst",
                "SUPERVISOR_CHILD_TOOL_DENIED");
        verify(guard, never()).commitStep(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("F-1：模型调用抛 RunApiException（预算/外发等）→ FAILED 稳定码 + run.error")
    void modelCallApiExceptionIsReportedWithStableCode() {
        doThrow(new RunApiException(RunErrorCode.BUDGET_EXCEEDED))
                .when(modelCall).call(any(), eq("route:1"), anyString(), anyString(), anyInt(), anyString(), any());

        RunExecutor.Outcome outcome = executor.execute(execution);

        assertEquals("FAILED", outcome.status());
        assertEquals("BUDGET_EXCEEDED", outcome.errorCode());
        assertErrorEvent(RunEventAppender.EVENT_ERROR, "route:1", null, "BUDGET_EXCEEDED");
    }

    @Test
    @DisplayName("F-1/R14：模型调用失 fence → VERSION_CONFLICT 仍原样上抛（不得吞成 FAILED）")
    void modelCallFenceConflictPropagates() {
        doThrow(new RunApiException(RunErrorCode.VERSION_CONFLICT))
                .when(modelCall).call(any(), eq("route:1"), anyString(), anyString(), anyInt(), anyString(), any());

        RunApiException conflict = assertThrows(RunApiException.class, () -> executor.execute(execution));

        assertEquals(RunErrorCode.VERSION_CONFLICT, conflict.errorCode());
        verify(guard, never()).appendEvent(eq(RunEventAppender.EVENT_ERROR), any());
    }

    // ------------------------------------------------------------ F-3：4 委派在默认预算内可完成

    @Test
    @DisplayName("F-3：默认 9 步内 4 次委派 + final 可完成（硬上限与预算同时可达）")
    void fourDelegationsAndFinalFitDefaultBudget() {
        replies.put("route:1", delegate("analyst", "t1", "r"));
        replies.put("child:analyst:1", child("a1"));
        replies.put("route:2", delegate("critic", "t2", "r"));
        replies.put("child:critic:2", child("a2"));
        replies.put("route:3", delegate("analyst", "t3", "r"));
        replies.put("child:analyst:3", child("a3"));
        replies.put("route:4", delegate("critic", "t4", "r"));
        replies.put("child:critic:4", child("a4"));
        replies.put("supervisor-final", finalAnswer("four done"));

        RunExecutor.Outcome outcome = executor.execute(execution);

        assertEquals("SUCCEEDED", outcome.status());
        assertEquals("four done", result(outcome).get("answer"));
        assertEquals(4, ((List<?>) result(outcome).get("delegations")).size());
        verify(modelCall, times(9)).call(any(), anyString(), anyString(), anyString(), anyInt(), anyString(), any());
        assertTrue(events.contains(RunEventAppender.EVENT_STEP_COMPLETED + ":supervisor-final"));
    }

    // ------------------------------------------------------------ R14 / 预算 / 版本

    @Test
    @DisplayName("R14：失 fence（commitStep 抛 VERSION_CONFLICT）原样上抛，不吞成 FAILED")
    void lostFencePropagates() {
        twoDelegationsThenFinal();
        when(guard.commitStep(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RunApiException(RunErrorCode.VERSION_CONFLICT));

        RunApiException conflict = assertThrows(RunApiException.class, () -> executor.execute(execution));
        assertEquals(RunErrorCode.VERSION_CONFLICT, conflict.errorCode());
    }

    @Test
    @DisplayName("墙钟预算超限 → BUDGET_EXCEEDED，且不发生任何模型调用")
    void wallClockExceededFailsBeforeModelCall() {
        run = run(Instant.now().minusSeconds(1000), "supervisor-v1", "{\"maxWallClockSeconds\":300}");
        execution = new RunExecution(run, guard);
        when(modelCall.bound(execution)).thenReturn(binding);

        RunExecutor.Outcome outcome = executor.execute(execution);

        assertEquals("FAILED", outcome.status());
        assertEquals("BUDGET_EXCEEDED", outcome.errorCode());
        verifyNoInteractions(modelCall);
    }

    @Test
    @DisplayName("执行契约版本不符 → AGENT_CHECKPOINT_INCOMPATIBLE，先于任何读取/调用")
    void executionVersionMismatchFailsClosed() {
        run = run(Instant.now(), "p3-core-v1", BUDGET);
        execution = new RunExecution(run, guard);

        RunExecutor.Outcome outcome = executor.execute(execution);

        assertEquals("FAILED", outcome.status());
        assertEquals("AGENT_CHECKPOINT_INCOMPATIBLE", outcome.errorCode());
        verifyNoInteractions(modelCall);
        verifyNoInteractions(ledger);
    }

    @Test
    @DisplayName("第 5 次仍要求委派 → SUPERVISOR_DELEGATION_LIMIT（4 次委派为硬上限）")
    void fifthDelegationIsRejected() {
        replies.put("route:1", delegate("analyst", "t1", "r"));
        replies.put("child:analyst:1", child("a1"));
        replies.put("route:2", delegate("critic", "t2", "r"));
        replies.put("child:critic:2", child("a2"));
        replies.put("route:3", delegate("analyst", "t3", "r"));
        replies.put("child:analyst:3", child("a3"));
        replies.put("route:4", delegate("critic", "t4", "r"));
        replies.put("child:critic:4", child("a4"));
        replies.put("supervisor-final", delegate("analyst", "t5", "r"));

        RunExecutor.Outcome outcome = executor.execute(execution);

        assertEquals("FAILED", outcome.status());
        assertEquals("SUPERVISOR_DELEGATION_LIMIT", outcome.errorCode());
        verify(modelCall, times(9)).call(any(), anyString(), anyString(), anyString(), anyInt(), anyString(), any());
    }
}
