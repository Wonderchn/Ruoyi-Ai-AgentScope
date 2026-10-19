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

package com.nageoffer.ai.ragent.flow.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.runtime.RunAccessService;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.RunEventAppender;
import com.nageoffer.ai.ragent.runtime.config.RunConfigBinding;
import com.nageoffer.ai.ragent.runtime.config.RunConfigBindingPort;
import com.nageoffer.ai.ragent.runtime.config.RuntimeModelGatewayPort;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.exec.ProviderCallBoundary;
import com.nageoffer.ai.ragent.runtime.exec.RunExecution;
import com.nageoffer.ai.ragent.runtime.exec.RunExecutionGuard;
import com.nageoffer.ai.ragent.runtime.exec.RunExecutor;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import com.nageoffer.ai.ragent.runtime.port.ConversationContextPort;
import com.nageoffer.ai.ragent.runtime.port.ConversationHistoryPort;
import com.nageoffer.ai.ragent.runtime.port.ConversationTurnMeta;
import com.nageoffer.ai.ragent.runtime.usage.UsageLedgerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ruoyi.ai.api.runtime.ChatPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F13:2 执行器：§3.4 三步骤 + 事件 + 投影、完成 step 不重复外呼、取消、
 * 失 fence、finishReason!="stop"、输出增量阈值、END 渲染。
 */
@Tag("dev")
class FlowRunExecutorTest {

    private static final String TENANT = "T1";
    private static final String USER = "2101";
    private static final String MEMBER = "platform:T1:2101";
    private static final String WORKFLOW = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String START = "0123456789abcdef0123456789abcdef";
    private static final String LLM = "1123456789abcdef0123456789abcdef";
    private static final String END = "2123456789abcdef0123456789abcdef";
    private static final String BOUND_MODEL = "deepseek-flash";
    private static final String RUN_ID = "r-" + "5f".repeat(16);
    private static final String RUNTIME_UUID = RUN_ID.substring(2);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FlowRuntimeRepository repository = mock(FlowRuntimeRepository.class);
    private final RunAccessService access = mock(RunAccessService.class);
    private final ObjectProvider<RunConfigBindingPort> bindingProvider = mock(ObjectProvider.class);
    private final RunConfigBindingPort bindingPort = mock(RunConfigBindingPort.class);
    private final RuntimeModelGatewayPort chat = mock(RuntimeModelGatewayPort.class);
    private final UsageLedgerService usage = mock(UsageLedgerService.class);
    private final ProviderCallBoundary providerBoundary = mock(ProviderCallBoundary.class);
    private final RevocationGuard revocationGuard = mock(RevocationGuard.class);
    private final RunExecutionGuard guard = mock(RunExecutionGuard.class);
    private final ObjectProvider<ConversationContextPort> conversationContext = mock(ObjectProvider.class);
    private final ObjectProvider<ConversationHistoryPort> conversationHistory = mock(ObjectProvider.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private final FlowRunExecutor executor = new FlowRunExecutor(repository, access, bindingProvider, chat, usage,
            providerBoundary, conversationContext, conversationHistory, jdbc, MAPPER);

    FlowRunExecutorTest() {
        when(bindingProvider.getIfAvailable()).thenReturn(bindingPort);
        when(bindingPort.requireBoundRevision(TENANT, RUN_ID)).thenReturn(new RunConfigBinding(
                TENANT, RUN_ID, "flow.run", "rev-1", 1L, "deepseek", BOUND_MODEL, "catalog-1", "params-1",
                "cred-ref", "op", Instant.now(), Map.of()));
        when(providerBoundary.enter(any())).thenReturn(new RevocationGuard.Operation(revocationGuard,
                "permit-1", "op-1"));
        when(usage.startCall(anyString(), anyString(), anyInt(), anyString(), anyString(), anyString(),
                anyString(), any())).thenReturn("call-1");
        when(usage.hasCall(anyString(), anyString(), anyString())).thenReturn(false);
        when(usage.unresolved(anyString(), anyString(), anyString())).thenReturn(false);
        when(guard.attempt()).thenReturn(1);
        when(guard.fence()).thenReturn(1L);
        when(guard.findStep(anyString())).thenReturn(Optional.empty());
        when(guard.isCancelRequested()).thenReturn(false);
        when(guard.appendEvent(anyString(), any())).thenReturn(1L);
        when(guard.commitStep(anyString(), anyString(), anyString(), any(), anyString()))
                .thenReturn(new RunLedgerDao.StepRow("node:" + START, 1, 1, "node:" + START, "COMPLETED",
                        "{}", null, "{}", 1L, Instant.now(), Instant.now()));
        when(guard.commitAtomic(any())).thenAnswer(invocation ->
                ((java.util.function.Supplier<?>) invocation.getArgument(0)).get());
    }

    // ------------------------------------------------------------------ 正例

    @Test
    @DisplayName("三步骤 START→LLM→END：3 个 step_started/step_completed + 输出增量 + 3 行投影 + 终态成功")
    void executesThreeStepsWithEventsAndProjection() {
        stubRuntime(graphSource("hello {name}", "{}"));
        stubChat(outcome -> {
            outcome.accept("a".repeat(100));
            outcome.accept("b".repeat(100));
            outcome.accept("c".repeat(50));
            return new ChatPort.ChatResult("a".repeat(100) + "b".repeat(100) + "c".repeat(50),
                    "prov-1", Map.of("total_tokens", 10), "stop");
        });

        RunExecutor.Outcome outcome = executor.execute(execution());

        assertThat(outcome.status()).isEqualTo("SUCCEEDED");
        Map<String, Object> result = asMap(outcome.terminalResult());
        assertThat(result.get("runtimeUuid")).isEqualTo(RUNTIME_UUID);
        assertThat(result.get("graphHash")).isEqualTo(snapshotOf("hello {name}", "{}").hash());
        assertThat(asMap(result.get("output")).get("value")).isEqualTo("a".repeat(100) + "b".repeat(100)
                + "c".repeat(50));

        verify(guard, times(3)).commitStep(anyString(), anyString(), anyString(), any(), anyString());
        verify(guard, times(3)).appendEvent(eq(RunEventAppender.EVENT_STEP_STARTED), any());
        verify(guard, times(3)).appendEvent(eq(RunEventAppender.EVENT_STEP_COMPLETED), any());
        verify(chat, times(1)).stream(any(), anyList(), anyInt(), any());

        verify(repository).markRuntimeRunning(TENANT, RUNTIME_UUID);
        verify(repository, times(3)).markNodeRunning(eq(TENANT), eq(RUNTIME_UUID), anyLong());
        verify(repository, times(3)).markNodeSucceeded(eq(TENANT), eq(RUNTIME_UUID), anyLong(), anyString());
        verify(repository).markRuntimeSucceeded(eq(TENANT), eq(RUNTIME_UUID), anyString());

        ArgumentCaptor<String> types = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        verify(guard, atLeastOnce()).appendEvent(types.capture(), payloads.capture());
        List<String> deltaTexts = new ArrayList<>();
        for (int i = 0; i < types.getAllValues().size(); i++) {
            if (RunEventAppender.EVENT_OUTPUT_DELTA.equals(types.getAllValues().get(i))) {
                deltaTexts.add(String.valueOf(asMap(payloads.getAllValues().get(i)).get("text")));
            }
        }
        assertThat(deltaTexts).as("100+100 达到 160 阈值时 flush 200；尾部 50 在步骤完成前 flush")
                .containsExactly("a".repeat(100) + "b".repeat(100), "c".repeat(50));
    }

    @Test
    @DisplayName("END 节点 result 模板按输入渲染；缺省时取 LLM 文本")
    void endNodeRendersConfiguredResultTemplate() {
        stubRuntime(graphSource("hello {name}", "{\"result\":\"final {name}\"}"));
        stubChat(outcome -> {
            outcome.accept("answer");
            return new ChatPort.ChatResult("answer", "prov-1", Map.of("total_tokens", 3), "stop");
        });

        RunExecutor.Outcome outcome = executor.execute(execution());

        assertThat(outcome.status()).isEqualTo("SUCCEEDED");
        assertThat(asMap(asMap(outcome.terminalResult()).get("output")).get("value")).isEqualTo("final Ada");
    }

    // ------------------------------------------------------------------ 幂等 / 取消 / fence

    @Test
    @DisplayName("已完成 step 直接取 ref：恢复时不重复调用 provider")
    void completedStepIsReusedWithoutCallingProviderAgain() {
        stubRuntime(graphSource("hello {name}", "{}"));
        when(guard.findStep("node:" + START)).thenReturn(Optional.empty());
        when(guard.findStep("node:" + LLM)).thenReturn(Optional.of(stepRow(LLM, "{\"text\":\"cached\"}")));
        when(guard.findStep("node:" + END)).thenReturn(Optional.empty());

        RunExecutor.Outcome outcome = executor.execute(execution());

        assertThat(outcome.status()).isEqualTo("SUCCEEDED");
        assertThat(asMap(asMap(outcome.terminalResult()).get("output")).get("value")).isEqualTo("cached");
        verify(chat, never()).stream(any(), anyList(), anyInt(), any());
        ArgumentCaptor<Object> started = ArgumentCaptor.forClass(Object.class);
        verify(guard, times(2)).appendEvent(eq(RunEventAppender.EVENT_STEP_STARTED), started.capture());
        assertThat(started.getAllValues()).extracting(payload -> asMap(payload).get("stepId"))
                .doesNotContain("node:" + LLM);
    }

    @Test
    @DisplayName("取消：runtime 记 4/CANCELLED，当前节点 2/CANCELLED，不调用 provider")
    void cancellationMarksRuntimeAndNodeAndReturnsCancelled() {
        stubRuntime(graphSource("hello {name}", "{}"));
        when(guard.isCancelRequested()).thenReturn(true);

        RunExecutor.Outcome outcome = executor.execute(execution());

        assertThat(outcome.status()).isEqualTo("CANCELLED");
        verify(repository).markRuntimeCancelled(TENANT, RUNTIME_UUID);
        verify(repository).markNodeFailed(eq(TENANT), eq(RUNTIME_UUID), eq(101L), eq("CANCELLED"));
        verify(chat, never()).stream(any(), anyList(), anyInt(), any());
        verify(guard, never()).commitStep(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("失 fence：commitStep 抛 VERSION_CONFLICT 时原样上抛（Worker 不写终态）")
    void staleFencePropagatesVersionConflict() {
        stubRuntime(graphSource("hello {name}", "{}"));
        when(guard.commitStep(anyString(), anyString(), anyString(), any(), anyString()))
                .thenThrow(new RunApiException(RunErrorCode.VERSION_CONFLICT, "stale worker fence"));

        assertThatThrownBy(() -> executor.execute(execution()))
                .isInstanceOf(RunApiException.class)
                .extracting(e -> ((RunApiException) e).errorCode())
                .isEqualTo(RunErrorCode.VERSION_CONFLICT);
        verify(chat, never()).stream(any(), anyList(), anyInt(), any());
    }

    @Test
    @DisplayName("finishReason != stop -> FAILED MODEL_RESPONSE_INCOMPLETE，投影记失败")
    void incompleteModelResponseFails() {
        stubRuntime(graphSource("hello {name}", "{}"));
        stubChat(outcome -> {
            outcome.accept("partial");
            return new ChatPort.ChatResult("partial", "prov-1", Map.of("total_tokens", 3), "length");
        });

        RunExecutor.Outcome outcome = executor.execute(execution());

        assertThat(outcome.status()).isEqualTo("FAILED");
        assertThat(outcome.errorCode()).isEqualTo("MODEL_RESPONSE_INCOMPLETE");
        verify(repository).markRuntimeFailed(eq(TENANT), eq(RUNTIME_UUID), anyString());
    }

    @Test
    @DisplayName("provider 调用已落账但 step 未提交：不重复外呼，转 NEEDS_RECONCILIATION")
    void existingProviderCallIsNotRepeated() {
        stubRuntime(graphSource("hello {name}", "{}"));
        when(usage.hasCall(TENANT, RUN_ID, "node:" + LLM)).thenReturn(true);

        RunExecutor.Outcome outcome = executor.execute(execution());

        assertThat(outcome.status()).isEqualTo("NEEDS_RECONCILIATION");
        assertThat(outcome.errorCode()).isEqualTo("MODEL_USAGE_UNKNOWN");
        verify(chat, never()).stream(any(), anyList(), anyInt(), any());
    }

    // ------------------------------------------------------------------ F03:0 会话

    @Test
    @DisplayName("带 conversationId：历史拼在本轮 user 之前，成功收口写 ai_chat_message + conversation-history")
    void conversationIdLoadsHistoryAndWritesBack() {
        stubRuntime(graphSource("hello {name}", "{}"));
        stubChat(outcome -> {
            outcome.accept("answer");
            return new ChatPort.ChatResult("answer", "prov-1", Map.of("total_tokens", 3), "stop");
        });
        ConversationContextPort context = mock(ConversationContextPort.class);
        when(context.load(any(), any())).thenReturn(List.of(
                ChatMessage.user("prior question"), ChatMessage.assistant("prior answer")));
        when(conversationContext.getIfAvailable()).thenReturn(context);
        ConversationHistoryPort history = mock(ConversationHistoryPort.class);
        when(conversationHistory.getIfAvailable()).thenReturn(history);

        RunExecutor.Outcome outcome = executor.execute(execution("conv-1"));

        assertThat(outcome.status()).isEqualTo("SUCCEEDED");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(chat).stream(any(), messages.capture(), anyInt(), any());
        assertThat(messages.getValue()).extracting(ChatMessage::getRole)
                .containsExactly(ChatMessage.Role.USER, ChatMessage.Role.ASSISTANT, ChatMessage.Role.USER);
        assertThat(messages.getValue()).extracting(ChatMessage::getContent)
                .containsExactly("prior question", "prior answer", "hello Ada");

        verify(history).appendAttachedTurn(TENANT, MEMBER, USER, "conv-1", "Ada", "answer",
                ConversationTurnMeta.EMPTY, null);
        verify(guard).commitStep(eq("conversation-history"), eq("conversation-history"),
                anyString(), any(), anyString());
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("INSERT INTO ai_chat_message"),
                any(Object[].class));
    }

    @Test
    @DisplayName("带 conversationId 但读端口缺席：fail-closed，零 provider 调用")
    void missingConversationContextPortFailsClosed() {
        stubRuntime(graphSource("hello {name}", "{}"));

        RunExecutor.Outcome outcome = executor.execute(execution("conv-1"));

        assertThat(outcome.status()).isEqualTo("FAILED");
        assertThat(outcome.errorCode()).isEqualTo("AUTHORIZATION_UNAVAILABLE");
        verify(chat, never()).stream(any(), anyList(), anyInt(), any());
        verify(repository).markRuntimeFailed(eq(TENANT), eq(RUNTIME_UUID), eq("AUTHORIZATION_UNAVAILABLE"));
    }

    @Test
    @DisplayName("读端口拒绝（404 语义）：不降级为空历史，零 provider 调用")
    void conversationContextDenialFailsClosed() {
        stubRuntime(graphSource("hello {name}", "{}"));
        ConversationContextPort context = mock(ConversationContextPort.class);
        when(context.load(any(), any())).thenThrow(
                new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        when(conversationContext.getIfAvailable()).thenReturn(context);

        RunExecutor.Outcome outcome = executor.execute(execution("conv-1"));

        assertThat(outcome.status()).isEqualTo("FAILED");
        assertThat(outcome.errorCode()).isEqualTo("RESOURCE_NOT_FOUND_OR_FORBIDDEN");
        verify(chat, never()).stream(any(), anyList(), anyInt(), any());
    }

    @Test
    @DisplayName("conversation-history 步骤已完成：重放不重复写历史")
    void completedConversationHistoryStepSkipsRewrite() {
        stubRuntime(graphSource("hello {name}", "{}"));
        stubChat(outcome -> {
            outcome.accept("answer");
            return new ChatPort.ChatResult("answer", "prov-1", Map.of("total_tokens", 3), "stop");
        });
        ConversationContextPort context = mock(ConversationContextPort.class);
        when(context.load(any(), any())).thenReturn(List.of());
        when(conversationContext.getIfAvailable()).thenReturn(context);
        ConversationHistoryPort history = mock(ConversationHistoryPort.class);
        when(conversationHistory.getIfAvailable()).thenReturn(history);
        when(guard.findStep("conversation-history")).thenReturn(Optional.of(new RunLedgerDao.StepRow(
                "conversation-history", 1, 1, "conversation-history", "COMPLETED", "{}", null, "{}",
                1L, Instant.now(), Instant.now())));

        RunExecutor.Outcome outcome = executor.execute(execution("conv-1"));

        assertThat(outcome.status()).isEqualTo("SUCCEEDED");
        verify(history, never()).appendAttachedTurn(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), any());
        verify(guard, never()).commitStep(eq("conversation-history"), eq("conversation-history"),
                anyString(), any(), anyString());
    }

    // ------------------------------------------------------------------ helpers

    private RunExecution execution() {
        return execution(null);
    }

    private RunExecution execution(String conversationId) {
        String input = "{\"flowUuid\":\"" + WORKFLOW + "\",\"inputs\":{\"name\":\"Ada\",\"input\":\"Ada\"}"
                + (conversationId == null ? "" : ",\"conversationId\":\"" + conversationId + "\"") + "}";
        RunRecord run = new RunRecord(TENANT, RUN_ID, MEMBER, USER, "flow.run", "RUNNING", "hash", "idem",
                input, "{\"maxTokens\":512}", FlowRunContract.EXECUTION_VERSION, 3, 5, "[]", 2L, 2L, 1, 1L,
                "worker-1", Instant.now().plusSeconds(30), null, null, null, null, Instant.now(),
                Instant.now(), null);
        return new RunExecution(run, guard);
    }

    private void stubRuntime(FlowRuntimeRepository.GraphSource source) {
        FlowGraphSnapshot snapshot = FlowGraphSnapshot.compile(source, BOUND_MODEL);
        var wrapper = MAPPER.createObjectNode();
        wrapper.set("payload", read("{\"flowUuid\":\"" + WORKFLOW + "\",\"inputs\":{\"name\":\"Ada\"}}"));
        wrapper.set("graph", snapshot.toJson());
        wrapper.put("graphHash", snapshot.hash());
        when(repository.findRuntime(TENANT, RUNTIME_UUID)).thenReturn(Optional.of(
                new FlowRuntimeRepository.RuntimeRow(7L, RUNTIME_UUID, 2101L, 41L, wrapper.toString(), null,
                        1, "PENDING", false)));
        when(repository.findNodeId(TENANT, 41L, START)).thenReturn(OptionalLong.of(101L));
        when(repository.findNodeId(TENANT, 41L, LLM)).thenReturn(OptionalLong.of(102L));
        when(repository.findNodeId(TENANT, 41L, END)).thenReturn(OptionalLong.of(103L));
        long now = Instant.now().getEpochSecond();
        when(access.current(any(), any())).thenReturn(new ExecutionPrincipal(TENANT, USER, MEMBER, 3, 5,
                Set.of("flow.run"), "async-" + RUN_ID, "platform", now, now + 300));
    }

    private void stubChat(java.util.function.Function<Consumer<String>, ChatPort.ChatResult> answer) {
        when(chat.stream(any(), anyList(), anyInt(), any())).thenAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(3);
            return answer.apply(onDelta);
        });
    }

    private static FlowRuntimeRepository.GraphSource graphSource(String prompt, String endConfig) {
        String end = endConfig == null || endConfig.isBlank() ? "{}" : endConfig;
        try {
            var parsed = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(end);
            if (!parsed.has("type")) {
                parsed.put("type", "END");
            }
            end = parsed.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new FlowRuntimeRepository.GraphSource(41L, WORKFLOW,
                List.of(new FlowRuntimeRepository.NodeSource(11L, START, "start", 0L, "{}",
                                "{\"type\":\"START\"}"),
                        new FlowRuntimeRepository.NodeSource(12L, LLM, "llm", 0L, "{}",
                                "{\"type\":\"LLM\",\"prompt\":\"" + prompt + "\",\"model_name\":\""
                                        + BOUND_MODEL + "\"}"),
                        new FlowRuntimeRepository.NodeSource(13L, END, "end", 0L, "{}", end)),
                List.of(new FlowRuntimeRepository.EdgeSource(START, LLM),
                        new FlowRuntimeRepository.EdgeSource(LLM, END)),
                Map.of());
    }

    private static FlowGraphSnapshot snapshotOf(String prompt, String endConfig) {
        return FlowGraphSnapshot.compile(graphSource(prompt, endConfig), BOUND_MODEL);
    }

    private static RunLedgerDao.StepRow stepRow(String nodeUuid, String refJson) {
        return new RunLedgerDao.StepRow("node:" + nodeUuid, 1, 1, "node:" + nodeUuid, "COMPLETED",
                refJson, null, "{}", 1L, Instant.now(), Instant.now());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
    }

    private static com.fasterxml.jackson.databind.JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
