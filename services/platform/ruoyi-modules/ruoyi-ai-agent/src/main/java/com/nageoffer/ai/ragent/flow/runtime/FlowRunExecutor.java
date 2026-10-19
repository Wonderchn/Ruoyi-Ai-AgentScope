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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.runtime.CanonicalJson;
import com.nageoffer.ai.ragent.runtime.RunAccessService;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.RunEventAppender;
import com.nageoffer.ai.ragent.runtime.config.ConfigAuthorityUnavailable;
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
import org.ruoyi.ai.api.runtime.ChatPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/**
 * F13:2 {@code flow.run} 执行器（最小切片：START → LLM → END）。
 *
 * <p><b>§3.4 冻结语义。</b>
 * 只读受理时写入 {@code ai_flow_runtime.input} 的快照（不重读图）；按拓扑执行
 * {@code stepId="node:"+nodeUuid}；每步 {@code run.step_started} →
 * {@code guard.commitStep}（幂等）→ 投影 → {@code run.step_completed}；
 * LLM 步经 {@code RunConfigBindingPort → ProviderCallBoundary → UsageLedgerService →
 * RuntimeModelGatewayPort}，delta 每 160 字符 flush 一次 {@code run.output_delta}；
 * 成功写 runtime=3 / END 结果，失败写 runtime=4 + 当前 node=2，取消写
 * runtime=4/remark=CANCELLED。
 *
 * <p><b>已完成 step 不重复外呼。</b>{@code guard.findStep(stepId)} 命中即取 ref；
 * provider 调用已落账但 step 未提交（{@code usage.hasCall}）转
 * {@code NEEDS_RECONCILIATION}，不自动重放。
 */
@Component
@ConditionalOnProperty(name = "p2.executor.mode", havingValue = "real", matchIfMissing = true)
public class FlowRunExecutor implements RunExecutor {

    private static final ObjectMapper JSON = CanonicalJson.strictMapper();
    private static final int DELTA_FLUSH_CHARS = 160;
    private static final int DEFAULT_MAX_TOKENS = 2000;
    private static final int MAX_MAX_TOKENS = 8192;

    private final FlowRuntimeRepository repository;
    private final RunAccessService access;
    private final ObjectProvider<RunConfigBindingPort> bindingPort;
    private final RuntimeModelGatewayPort chat;
    private final UsageLedgerService usage;
    private final ProviderCallBoundary providerBoundary;
    private final ObjectProvider<ConversationContextPort> conversationContext;
    private final ObjectProvider<ConversationHistoryPort> conversationHistory;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public FlowRunExecutor(FlowRuntimeRepository repository, RunAccessService access,
                           ObjectProvider<RunConfigBindingPort> bindingPort, RuntimeModelGatewayPort chat,
                           UsageLedgerService usage, ProviderCallBoundary providerBoundary,
                           ObjectProvider<ConversationContextPort> conversationContext,
                           ObjectProvider<ConversationHistoryPort> conversationHistory,
                           JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.repository = repository;
        this.access = access;
        this.bindingPort = bindingPort;
        this.chat = chat;
        this.usage = usage;
        this.providerBoundary = providerBoundary;
        this.conversationContext = conversationContext;
        this.conversationHistory = conversationHistory;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public String action() {
        return FlowRunContract.ACTION;
    }

    // ------------------------------------------------------------------ execute

    @Override
    public Outcome execute(RunExecution execution) {
        RunExecutionGuard guard = execution.guard();
        RunRecord run = execution.run();
        String tenantId = execution.tenantId();
        String runId = execution.runId();
        if (!FlowRunContract.EXECUTION_VERSION.equals(run.executionVersion())) {
            return Outcome.failed("FLOW_CHECKPOINT_INCOMPATIBLE");
        }
        String runtimeUuid;
        try {
            runtimeUuid = FlowRuntimeRepository.runtimeUuidOf(runId);
        } catch (RunApiException invalid) {
            return Outcome.failed("FLOW_CHECKPOINT_INCOMPATIBLE");
        }
        FlowRuntimeRepository.RuntimeRow runtime = repository.findRuntime(tenantId, runtimeUuid).orElse(null);
        if (runtime == null) {
            return Outcome.failed("FLOW_RUNTIME_MISSING");
        }
        JsonNode wrapper;
        try {
            wrapper = readWrapper(runtime.inputJson());
        } catch (RuntimeException incompatible) {
            markRuntimeFailed(guard, tenantId, runtimeUuid, "FLOW_CHECKPOINT_INCOMPATIBLE");
            return Outcome.failed("FLOW_CHECKPOINT_INCOMPATIBLE");
        }
        JsonNode graph = wrapper.get("graph");
        String graphHash = wrapper.path("graphHash").asText("");
        if (graph == null || graphHash.isBlank()
                || !CanonicalJson.sha256(CanonicalJson.canonicalize(graph)).equals(graphHash)) {
            markRuntimeFailed(guard, tenantId, runtimeUuid, "FLOW_CHECKPOINT_INCOMPATIBLE");
            return Outcome.failed("FLOW_CHECKPOINT_INCOMPATIBLE");
        }
        FlowGraphSnapshot snapshot;
        try {
            snapshot = FlowGraphSnapshot.fromPersisted(graph);
        } catch (RuntimeException incompatible) {
            markRuntimeFailed(guard, tenantId, runtimeUuid, "FLOW_CHECKPOINT_INCOMPATIBLE");
            return Outcome.failed("FLOW_CHECKPOINT_INCOMPATIBLE");
        }
        Map<String, String> inputs = FlowRunContract.readInputs(wrapper.get("payload"));

        ExecutionPrincipal principal;
        try {
            principal = access.current(run, Set.of(FlowRunContract.ACTION));
        } catch (RuntimeException unauthorized) {
            markRuntimeFailed(guard, tenantId, runtimeUuid, "AUTHORIZATION_UNAVAILABLE");
            return Outcome.failed("AUTHORIZATION_UNAVAILABLE");
        }
        // F03:0：conversationId 是受理载荷顶层字段（由受理侧折入 run 行 input，D7a）。
        // 读历史在 markRuntimeRunning/任何 provider 调用之前完成：归属/授权/依赖失败一律
        // fail-closed，0 提供方调用、0 用量。
        JsonNode storedInput = storedInput(execution);
        String conversationId = storedInput.path("conversationId").asText("");
        String question = storedInput.path("inputs").path("input").asText("");
        List<ChatMessage> history;
        try {
            history = loadConversationHistory(execution, principal, conversationId);
        } catch (RunApiException denied) {
            markRuntimeFailed(guard, tenantId, runtimeUuid, denied.errorCode().name());
            return Outcome.failed(denied.errorCode().name());
        } catch (RuntimeException unavailable) {
            markRuntimeFailed(guard, tenantId, runtimeUuid, "AUTHORIZATION_UNAVAILABLE");
            return Outcome.failed("AUTHORIZATION_UNAVAILABLE");
        }
        guard.commitAtomic(() -> {
            repository.markRuntimeRunning(tenantId, runtimeUuid);
            return null;
        });

        FlowGraphSnapshot.Node currentNode = null;
        long currentNodeId = 0L;
        Map<String, String> stepOutputs = new LinkedHashMap<>();
        try {
            for (FlowGraphSnapshot.Node node : snapshot.executionOrder()) {
                currentNode = node;
                String stepId = "node:" + node.uuid();
                OptionalLong resolved = repository.findNodeId(tenantId, runtime.workflowId(), node.uuid());
                currentNodeId = resolved.orElse(0L);
                final long stepNodeId = currentNodeId;

                Optional<RunLedgerDao.StepRow> completed = guard.findStep(stepId);
                if (completed.isPresent()) {
                    String refJson = completed.get().refJson();
                    stepOutputs.put(node.uuid(), textOf(refJson));
                    markNodeSucceeded(guard, tenantId, runtimeUuid, currentNodeId, refJson);
                    continue;
                }
                if (guard.isCancelRequested()) {
                    throw new FlowCancelled(stepId);
                }
                guard.appendEvent(RunEventAppender.EVENT_STEP_STARTED, Map.of(
                        "stepId", stepId, "stepName", stepId, "attemptId", "a-" + execution.attempt()));
                guard.commitAtomic(() -> {
                    repository.markNodeRunning(tenantId, runtimeUuid, stepNodeId);
                    return null;
                });

                StepResult result = switch (node.type()) {
                    case "START" -> new StepResult(Map.of("output", inputs), 0);
                    case "LLM" -> llmStep(execution, guard, snapshot, inputs, stepId, history);
                    case "END" -> endStep(snapshot, node, inputs, stepOutputs);
                    default -> throw new RunApiException(RunErrorCode.BAD_REQUEST,
                            "unsupported flow node type: " + node.type());
                };
                String refJson = toJson(result.ref());
                String refHash = CanonicalJson.sha256(CanonicalJson.canonicalize(
                        objectMapper.valueToTree(result.ref())));
                guard.commitStep(stepId, stepId, refJson, refHash,
                        toJson(Map.of("calls", result.calls())));
                markNodeSucceeded(guard, tenantId, runtimeUuid, currentNodeId, refJson);
                guard.appendEvent(RunEventAppender.EVENT_STEP_COMPLETED, Map.of(
                        "stepId", stepId, "state", "COMPLETED", "ref", result.ref(),
                        "attemptId", "a-" + execution.attempt()));
                stepOutputs.put(node.uuid(), textOf(refJson));
            }

            String finalValue = stepOutputs.getOrDefault(snapshot.endNode().uuid(), "");
            String outputJson = toJson(Map.of("output", Map.of("value", finalValue)));
            ExecutionPrincipal historyPrincipal = principal;
            guard.commitAtomic(() -> {
                repository.markRuntimeSucceeded(tenantId, runtimeUuid, outputJson);
                if (!conversationId.isBlank()) {
                    // F03:0：先写 run 作用域答案（恢复同源），再写会话历史；两者与终态同一事务。
                    // 任一失败都会回滚终态并向上抛出（fail-closed，不静默丢历史）。
                    persistMessage(execution, finalValue, 1);
                    persistConversationHistory(execution, guard, historyPrincipal, conversationId,
                            question, finalValue);
                }
                return null;
            });
            Map<String, Object> outcome = new LinkedHashMap<>();
            outcome.put("runtimeUuid", runtimeUuid);
            outcome.put("graphHash", graphHash);
            outcome.put("output", Map.of("value", finalValue));
            outcome.put("nodes", snapshot.executionOrder().stream()
                    .map(node -> Map.of("uuid", node.uuid(), "type", node.type(), "status", 3)).toList());
            return Outcome.succeeded(outcome);
        } catch (FlowCancelled cancelled) {
            cancel(guard, tenantId, runtimeUuid, currentNodeId);
            return new Outcome("CANCELLED", Map.of("at", cancelled.stepId()), null);
        } catch (NeedsReconciliation reconciliation) {
            markRuntimeFailed(guard, tenantId, runtimeUuid, "NEEDS_RECONCILIATION");
            return new Outcome("NEEDS_RECONCILIATION", Map.of(), "MODEL_USAGE_UNKNOWN");
        } catch (FlowFailure failure) {
            markFailed(guard, tenantId, runtimeUuid, currentNodeId, failure.code());
            return Outcome.failed(failure.code());
        } catch (RunApiException e) {
            if (e.errorCode() == RunErrorCode.VERSION_CONFLICT) {
                throw e;
            }
            markFailed(guard, tenantId, runtimeUuid, currentNodeId, e.errorCode().name());
            throw e;
        } catch (RuntimeException e) {
            markFailed(guard, tenantId, runtimeUuid, currentNodeId, "EXECUTION_FAILED");
            throw e;
        }
    }

    // ------------------------------------------------------------------ steps

    private StepResult llmStep(RunExecution execution, RunExecutionGuard guard,
                               FlowGraphSnapshot snapshot, Map<String, String> inputs, String stepId,
                               List<ChatMessage> history) {
        String tenantId = execution.tenantId();
        String runId = execution.runId();
        RunConfigBinding binding;
        try {
            RunConfigBindingPort port = bindingPort.getIfAvailable();
            if (port == null) {
                throw new ConfigAuthorityUnavailable("run config binding port is not wired");
            }
            binding = port.requireBoundRevision(tenantId, runId);
        } catch (ConfigAuthorityUnavailable unavailable) {
            throw new FlowFailure("MODEL_CONFIG_UNAVAILABLE");
        }
        if (usage.hasCall(tenantId, runId, stepId)) {
            throw new NeedsReconciliation();
        }

        RevocationGuard.Operation operation = providerBoundary.enter(execution);
        String callId;
        try {
            callId = guard.commitAtomic(() -> usage.startCall(tenantId, runId, execution.attempt(), stepId,
                    UsageLedgerService.KIND_CHAT, binding.providerId(), binding.modelId(), null));
        } catch (RuntimeException e) {
            operation.close();
            throw e;
        }

        StringBuilder answer = new StringBuilder();
        StringBuilder pending = new StringBuilder();
        boolean[] cancelled = {false};
        // F03:0：会话历史（旧 → 新）放在本轮 user 之前；本轮回答仍以本轮流程输入为准。
        List<ChatMessage> messages = new ArrayList<>(history);
        messages.add(ChatMessage.user(snapshot.renderLlmPrompt(inputs)));
        ChatPort.ChatResult result;
        try {
            result = chat.stream(binding, messages,
                    maxTokens(execution), delta -> {
                        answer.append(delta);
                        pending.append(delta);
                        if (pending.length() >= DELTA_FLUSH_CHARS) {
                            access.current(execution.run(), Set.of(FlowRunContract.ACTION));
                            guard.appendEvent(RunEventAppender.EVENT_OUTPUT_DELTA,
                                    Map.of("text", pending.toString(), "dropped", false));
                            pending.setLength(0);
                        }
                        if (guard.isCancelRequested()) {
                            cancelled[0] = true;
                            throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT, "run cancelled");
                        }
                    });
        } catch (RuntimeException e) {
            try {
                guard.commitAtomic(() -> {
                    usage.markUnknown(tenantId, callId);
                    return null;
                });
            } catch (RuntimeException ignored) {
                // 失 fence：保留原异常；unknown 结算由对账窗口兜底。
            }
            if (cancelled[0]) {
                throw new FlowCancelled(stepId);
            }
            throw e;
        } finally {
            operation.close();
        }

        if (pending.length() > 0) {
            access.current(execution.run(), Set.of(FlowRunContract.ACTION));
            guard.appendEvent(RunEventAppender.EVENT_OUTPUT_DELTA,
                    Map.of("text", pending.toString(), "dropped", false));
        }
        guard.commitAtomic(() -> {
            usage.settle(tenantId, callId, result.providerRequestId(), result.usageRaw());
            return null;
        });
        if (usage.unresolved(tenantId, runId, stepId)) {
            throw new NeedsReconciliation();
        }
        if (!"stop".equals(result.finishReason())) {
            throw new FlowFailure("MODEL_RESPONSE_INCOMPLETE");
        }
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("text", answer.toString());
        ref.put("callId", callId);
        ref.put("provider", binding.providerId());
        ref.put("model", binding.modelId());
        return new StepResult(ref, 1);
    }

    private StepResult endStep(FlowGraphSnapshot snapshot, FlowGraphSnapshot.Node node,
                               Map<String, String> inputs, Map<String, String> stepOutputs) {
        String rendered = snapshot.renderEndResult(inputs);
        String value = rendered != null ? rendered : stepOutputs.getOrDefault(snapshot.llmNode().uuid(), "");
        return new StepResult(Map.of("text", value == null ? "" : value), 0);
    }

    // ------------------------------------------------------------------ terminal writes

    private void cancel(RunExecutionGuard guard, String tenantId, String runtimeUuid, long nodeId) {
        try {
            guard.commitAtomic(() -> {
                if (nodeId > 0) {
                    repository.markNodeFailed(tenantId, runtimeUuid, nodeId, "CANCELLED");
                }
                repository.markRuntimeCancelled(tenantId, runtimeUuid);
                return null;
            });
        } catch (RuntimeException ignored) {
            // 失 fence：终态由接管者决定；不伪造成功。
        }
    }

    private void markFailed(RunExecutionGuard guard, String tenantId, String runtimeUuid, long nodeId,
                            String code) {
        try {
            guard.commitAtomic(() -> {
                if (nodeId > 0) {
                    repository.markNodeFailed(tenantId, runtimeUuid, nodeId, code);
                }
                repository.markRuntimeFailed(tenantId, runtimeUuid, code);
                return null;
            });
        } catch (RuntimeException ignored) {
            // 同上：投影失败不得掩盖原始失败。
        }
    }

    private void markRuntimeFailed(RunExecutionGuard guard, String tenantId, String runtimeUuid, String code) {
        markFailed(guard, tenantId, runtimeUuid, 0L, code);
    }

    private void markNodeSucceeded(RunExecutionGuard guard, String tenantId, String runtimeUuid, long nodeId,
                                   String refJson) {
        if (nodeId <= 0) {
            return;
        }
        guard.commitAtomic(() -> {
            repository.markNodeSucceeded(tenantId, runtimeUuid, nodeId, refJson);
            return null;
        });
    }

    // ------------------------------------------------------------------ helpers

    /** run 行受理 input（含 D7a 折入的顶层 conversationId）；畸形按空对象处理，不冒充历史。 */
    private JsonNode storedInput(RunExecution execution) {
        String inputJson = execution.run().inputJson();
        if (inputJson == null || inputJson.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            JsonNode input = JSON.readTree(inputJson);
            return input != null && input.isObject() ? input : JSON.createObjectNode();
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }

    /**
     * 载入已授权会话历史；无 conversationId 是唯一合法空历史。
     * 端口缺席/归属/授权/依赖失败一律抛出，由调用方 fail-closed。
     */
    private List<ChatMessage> loadConversationHistory(RunExecution execution,
                                                      ExecutionPrincipal principal,
                                                      String conversationId) {
        if (conversationId.isBlank()) {
            return List.of();
        }
        ConversationContextPort context = conversationContext == null
                ? null : conversationContext.getIfAvailable();
        if (context == null) {
            throw new IllegalStateException(
                    "conversationId requires a wired authorized context consumer but the conversation context port is absent"
                            + " (runId=" + execution.runId() + ", conversationId=" + conversationId + ")");
        }
        return context.load(execution.run(), principal);
    }

    /** run 作用域的 assistant 答案（恢复路径同源）；与历史写同事务。 */
    private void persistMessage(RunExecution execution, String answer, int sequence) {
        jdbc.update("INSERT INTO ai_chat_message (tenant_id, message_id, run_id, sequence, role, content, citations) "
                        + "VALUES (?,?,?,?,'assistant',?,?::jsonb) ON CONFLICT (tenant_id, run_id, sequence) DO NOTHING",
                execution.tenantId(), "msg-" + UUID.randomUUID().toString().replace("-", ""),
                execution.runId(), sequence, answer, toJson(List.of()));
    }

    /**
     * 成功收口追加会话历史（幂等：conversation-history 步骤已 COMPLETED 即跳过）。
     * 载荷带 conversationId 但端口未接线时抛错（run 失败可见），不静默少写一轮。
     */
    private void persistConversationHistory(RunExecution execution, RunExecutionGuard guard,
                                            ExecutionPrincipal principal, String conversationId,
                                            String question, String answer) {
        if (conversationId.isBlank()) {
            return;
        }
        Optional<RunLedgerDao.StepRow> completed = guard.findStep("conversation-history");
        if (completed.isPresent() && "COMPLETED".equals(completed.get().state())) {
            return;
        }
        ConversationHistoryPort history = conversationHistory == null
                ? null : conversationHistory.getIfAvailable();
        if (history == null) {
            throw new IllegalStateException(
                    "conversation history port is not wired but the accepted payload carries conversationId="
                            + conversationId + " (runId=" + execution.runId() + ")");
        }
        history.appendAttachedTurn(execution.tenantId(), principal.membershipId(), principal.userId(),
                conversationId, question, answer, ConversationTurnMeta.EMPTY, null);
        guard.commitStep("conversation-history", "conversation-history",
                toJson(Map.of("conversationId", conversationId)), null, toJson(Map.of("calls", 0)));
    }

    private JsonNode readWrapper(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            throw new IllegalStateException("flow runtime input is missing");
        }
        try {
            JsonNode wrapper = JSON.readTree(inputJson);
            if (wrapper == null || !wrapper.isObject()) {
                throw new IllegalStateException("flow runtime input is invalid");
            }
            return wrapper;
        } catch (Exception e) {
            throw new IllegalStateException("flow runtime input is invalid", e);
        }
    }

    private int maxTokens(RunExecution execution) {
        try {
            JsonNode budget = execution.run().budgetJson() == null ? null
                    : JSON.readTree(execution.run().budgetJson());
            int tokens = budget == null ? 0 : budget.path("maxTokens").asInt(0);
            return Math.min(MAX_MAX_TOKENS, Math.max(1, tokens > 0 ? tokens : DEFAULT_MAX_TOKENS));
        } catch (Exception e) {
            return DEFAULT_MAX_TOKENS;
        }
    }

    private static String textOf(String refJson) {
        if (refJson == null || refJson.isBlank()) {
            return "";
        }
        try {
            JsonNode ref = JSON.readTree(refJson);
            return ref != null && ref.hasNonNull("text") ? ref.get("text").asText() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("json encoding failed", e);
        }
    }

    private record StepResult(Map<String, Object> ref, int calls) {
    }

    private static final class FlowCancelled extends RuntimeException {
        private final String stepId;

        FlowCancelled(String stepId) {
            this.stepId = stepId;
        }

        String stepId() {
            return stepId;
        }
    }

    private static final class NeedsReconciliation extends RuntimeException {
    }

    private static final class FlowFailure extends RuntimeException {
        private final String code;

        FlowFailure(String code) {
            this.code = code;
        }

        String code() {
            return code;
        }
    }
}
