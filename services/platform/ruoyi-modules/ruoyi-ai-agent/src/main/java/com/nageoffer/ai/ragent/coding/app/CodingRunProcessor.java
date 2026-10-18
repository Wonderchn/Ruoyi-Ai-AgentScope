package com.nageoffer.ai.ragent.coding.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.coding.approval.ApprovalState;
import com.nageoffer.ai.ragent.coding.approval.ToolApproval;
import com.nageoffer.ai.ragent.coding.event.HarnessEventHub;
import com.nageoffer.ai.ragent.coding.model.HarnessEvent;
import com.nageoffer.ai.ragent.coding.model.HarnessMessage;
import com.nageoffer.ai.ragent.coding.model.HarnessMessageRole;
import com.nageoffer.ai.ragent.coding.model.HarnessOwner;
import com.nageoffer.ai.ragent.coding.model.HarnessRunState;
import com.nageoffer.ai.ragent.coding.model.HarnessRunStatus;
import com.nageoffer.ai.ragent.coding.model.HarnessSessionState;
import com.nageoffer.ai.ragent.coding.plan.CodingPlanState;
import com.nageoffer.ai.ragent.coding.plan.PlanMode;
import com.nageoffer.ai.ragent.coding.store.HarnessOptimisticLockException;
import com.nageoffer.ai.ragent.coding.store.HarnessStore;
import com.nageoffer.ai.ragent.coding.store.HarnessStoreException;
import com.nageoffer.ai.ragent.coding.tool.CodingToolDescriptor;
import com.nageoffer.ai.ragent.coding.tool.CodingToolRuntime;
import com.nageoffer.ai.ragent.coding.tool.ToolExecutionResult;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Durable single-run loop.
 *
 * <p>Every mutation is committed before its event is published. Optimistic-lock conflicts caused
 * by concurrent control commands (steering input, approval, plan) are retried against the latest
 * snapshot; a persistent failure terminalizes the run instead of leaving it RUNNING forever.
 * Provider exception text never enters the durable run or public event payload — only a stable
 * outcome code does.
 */
public class CodingRunProcessor {

    private static final int MAX_PROCESS_ATTEMPTS = 3;
    private static final int MAX_SAVE_ATTEMPTS = 5;
    private static final int TRANSCRIPT_LIMIT = 400;

    private final HarnessStore store;
    private final HarnessEventHub eventHub;
    private final CodingModelGateway modelGateway;
    private final CodingToolRuntime tools;
    private final ObjectMapper objectMapper;

    public CodingRunProcessor(HarnessStore store, HarnessEventHub eventHub,
                              CodingModelGateway modelGateway, CodingToolRuntime tools,
                              ObjectMapper objectMapper) {
        this.store = store;
        this.eventHub = eventHub;
        this.modelGateway = modelGateway;
        this.tools = tools;
        this.objectMapper = objectMapper;
    }

    public void process(HarnessOwner owner, String sessionId, String runId) {
        for (int attempt = 1; attempt <= MAX_PROCESS_ATTEMPTS; attempt++) {
            try {
                processOnce(owner, sessionId, runId);
                return;
            } catch (HarnessOptimisticLockException conflict) {
                // A concurrent control command advanced the snapshot; reload and retry.
                if (attempt == MAX_PROCESS_ATTEMPTS) {
                    terminalizeAfterFailure(owner, sessionId, runId, "CONCURRENT_UPDATE");
                }
            } catch (HarnessStoreException storageFailure) {
                terminalizeAfterFailure(owner, sessionId, runId, "STORAGE_FAILURE");
                return;
            } catch (RuntimeException unexpected) {
                terminalizeAfterFailure(owner, sessionId, runId, "RUN_PROCESSOR_FAILED");
                return;
            }
        }
    }

    private void processOnce(HarnessOwner owner, String sessionId, String runId) {
        HarnessRunState run = store.findRun(owner, sessionId, runId).orElse(null);
        if (run == null || run.status().isTerminal()) {
            return;
        }
        HarnessSessionState session = store.findSession(owner, sessionId).orElse(null);
        if (session == null) {
            return;
        }
        if (run.status() == HarnessRunStatus.QUEUED) {
            HarnessRunState claimed = saveWithRetry(owner, sessionId, runId,
                    current -> current.status() == HarnessRunStatus.QUEUED
                            ? current.transition(HarnessRunStatus.RUNNING, null, now())
                            : current);
            publish(owner, HarnessEvent.draftWithId(
                    "run-started:" + runId + ":" + claimed.revision(), sessionId, runId,
                    "run.started", null, null, null,
                    Map.of("status", "RUNNING", "revision", claimed.revision()), now()));
        } else if (run.status() == HarnessRunStatus.WAITING_FOR_APPROVAL
                && !run.hasPendingApproval()) {
            saveWithRetry(owner, sessionId, runId,
                    current -> current.status() == HarnessRunStatus.WAITING_FOR_APPROVAL
                            && !current.hasPendingApproval()
                            ? current.transition(HarnessRunStatus.QUEUED, null, now())
                                    .transition(HarnessRunStatus.RUNNING, null, now())
                            : current);
        }
        consumeSettledApprovals(owner, runId, session);
        while (true) {
            HarnessRunState current = store.findRun(owner, sessionId, runId).orElse(null);
            if (current == null || current.status().isTerminal()) {
                return;
            }
            if (current.cancellationRequested()) {
                cancelRun(owner, current);
                return;
            }
            if (current.hasPendingApproval()) {
                if (current.status() == HarnessRunStatus.RUNNING) {
                    saveWithRetry(owner, sessionId, runId, state ->
                            state.status() == HarnessRunStatus.RUNNING
                                    ? state.transition(HarnessRunStatus.WAITING_FOR_APPROVAL,
                                    null, now())
                                    : state);
                }
                return;
            }
            if (current.iteration() >= current.budget().maxIterations()) {
                fail(owner, current, "BUDGET_EXCEEDED", "Iteration budget exhausted");
                return;
            }
            if (current.budget().maxToolCalls() > 0
                    && current.toolCallCount() >= current.budget().maxToolCalls()) {
                fail(owner, current, "BUDGET_EXCEEDED", "Tool call budget exhausted");
                return;
            }
            List<HarnessMessage> transcript = readRunTranscript(owner, sessionId, runId,
                    TRANSCRIPT_LIMIT);
            ModelTurn turn;
            try {
                turn = modelGateway.complete(new CodingModelRequest(owner, session, current,
                        transcript, tools.descriptors()));
            } catch (CodingModelUnavailableException unavailable) {
                fail(owner, current, "MODEL_NOT_CONFIGURED",
                        "The coding model gateway is not configured");
                return;
            } catch (RuntimeException providerFailure) {
                fail(owner, current, "MODEL_TURN_FAILED", "The model turn failed");
                return;
            }
            if (turn.planDraft() != null) {
                applyPlanDraft(owner, sessionId, runId, current, turn.planDraft());
                return;
            }
            if (!turn.toolCalls().isEmpty()) {
                if (handleToolCalls(owner, sessionId, runId, session, turn.toolCalls())) {
                    return;
                }
                continue;
            }
            complete(owner, sessionId, runId, current, turn);
            return;
        }
    }

    // ------------------------------------------------------------------ state mutations

    /**
     * Applies one mutation against the latest snapshot and retries optimistic-lock conflicts.
     * The mutation must be idempotent: it can be evaluated more than once.
     */
    private HarnessRunState saveWithRetry(HarnessOwner owner, String sessionId, String runId,
                                          UnaryOperator<HarnessRunState> mutation) {
        HarnessOptimisticLockException last = null;
        for (int attempt = 0; attempt < MAX_SAVE_ATTEMPTS; attempt++) {
            HarnessRunState current = store.findRun(owner, sessionId, runId)
                    .orElseThrow(() -> new HarnessStoreException("Unknown run: " + runId));
            HarnessRunState candidate = mutation.apply(current);
            if (candidate == current) {
                return current;
            }
            try {
                return store.saveRun(owner, candidate, current.revision());
            } catch (HarnessOptimisticLockException conflict) {
                last = conflict;
            }
        }
        throw last == null ? new HarnessOptimisticLockException("run " + runId, 0, 0) : last;
    }

    private void consumeSettledApprovals(HarnessOwner owner, String runId,
                                         HarnessSessionState session) {
        Path workspace = Path.of(session.workspace());
        HarnessRunState run = store.findRun(owner, session.sessionId(), runId).orElse(null);
        if (run == null) {
            return;
        }
        for (ToolApproval observed : new ArrayList<>(run.toolApprovals().values())) {
            if (observed.consumed()) {
                continue;
            }
            HarnessRunState current = store.findRun(owner, session.sessionId(), runId).orElse(run);
            ToolApproval approval = current.toolApprovals().get(observed.approvalId());
            if (approval == null || approval.consumed()) {
                continue;
            }
            if (approval.state() == ApprovalState.APPROVED) {
                HarnessMessage existing = findApprovalReceipt(owner, current, approval);
                if (existing == null) {
                    ToolExecutionResult result = tools.execute(approval.toolName(),
                            approval.arguments(), workspace, runId, current.authorizedActions());
                    appendToolReceipt(owner, current, approval, result);
                    publishToolCompleted(owner, current, approval, result.error(), result.code(),
                            now());
                } else {
                    markApprovalConsumed(owner, runId, approval);
                    publishToolCompleted(owner, current, approval, existing.toolError(),
                            String.valueOf(existing.metadata().getOrDefault("code", "ok")),
                            existing.timestamp());
                }
            } else if (approval.state() == ApprovalState.DENIED) {
                HarnessMessage existing = findApprovalReceipt(owner, current, approval);
                if (existing == null) {
                    ToolExecutionResult denied = ToolExecutionResult.error(approval.toolName(),
                            "APPROVAL_DENIED", "Operator denied this tool call");
                    appendToolReceipt(owner, current, approval, denied);
                    publishToolCompleted(owner, current, approval, true, "APPROVAL_DENIED", now());
                } else {
                    markApprovalConsumed(owner, runId, approval);
                    publishToolCompleted(owner, current, approval, true, "APPROVAL_DENIED",
                            existing.timestamp());
                }
            }
        }
    }

    private void markApprovalConsumed(HarnessOwner owner, String runId, ToolApproval approval) {
        saveWithRetry(owner, approval.sessionId(), runId, current -> {
            ToolApproval latest = current.toolApprovals().get(approval.approvalId());
            if (latest == null || latest.consumed()) {
                return current;
            }
            return current.withToolApproval(latest.markConsumed(now()), now())
                    .withCounters(current.iteration(), current.toolCallCount() + 1, now());
        });
    }

    private HarnessMessage findApprovalReceipt(HarnessOwner owner, HarnessRunState run,
                                               ToolApproval approval) {
        for (HarnessMessage message : readRunMessages(owner, run.sessionId(), run.runId())) {
            if (message.role() == HarnessMessageRole.TOOL
                    && approval.approvalId().equals(message.metadata().get("approvalId"))) {
                return message;
            }
        }
        return null;
    }

    private HarnessMessage findToolReceipt(HarnessOwner owner, String sessionId, String runId,
                                           String toolCallId) {
        for (HarnessMessage message : readRunMessages(owner, sessionId, runId)) {
            if (message.role() == HarnessMessageRole.TOOL
                    && toolCallId.equals(message.toolCallId())) {
                return message;
            }
        }
        return null;
    }

    private HarnessMessage findAssistantTurnMessage(HarnessOwner owner, String sessionId,
                                                    String runId, int iteration) {
        for (HarnessMessage message : readRunMessages(owner, sessionId, runId)) {
            if (message.role() == HarnessMessageRole.ASSISTANT
                    && String.valueOf(iteration).equals(
                    String.valueOf(message.metadata().get("iteration")))) {
                return message;
            }
        }
        return null;
    }

    private void appendToolReceipt(HarnessOwner owner, HarnessRunState run, ToolApproval approval,
                                   ToolExecutionResult result) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("approvalId", approval.approvalId());
        metadata.put("code", result.code());
        metadata.put("toolName", approval.toolName());
        store.appendMessage(owner, HarnessMessage.draft(run.sessionId(), run.runId(),
                HarnessMessageRole.TOOL, result.code(), null, List.of(), approval.toolCallId(),
                approval.toolName(), result.error(), null, metadata, now()));
        // N4：已批准执行也计入 toolCallCount，避免 ON_REQUEST 下无限绕过工具预算。
        markApprovalConsumed(owner, run.runId(), approval);
    }

    private boolean handleToolCalls(HarnessOwner owner, String sessionId, String runId,
                                    HarnessSessionState session, List<ModelToolCall> calls) {
        Path workspace = Path.of(session.workspace());
        for (ModelToolCall call : calls) {
            HarnessRunState current = store.findRun(owner, sessionId, runId)
                    .orElseThrow(() -> new HarnessStoreException("Unknown run: " + runId));
            if (current.status().isTerminal()) {
                return true;
            }
            String requiredAction = CodingToolRuntime.requiredAction(call.toolName());
            CodingToolDescriptor descriptor = tools.descriptors().stream()
                    .filter(item -> item.name().equals(call.toolName()))
                    .findFirst().orElse(null);
            if (requiredAction == null || descriptor == null) {
                appendImmediateToolResult(owner, current, call, ToolExecutionResult.error(
                        call.toolName(), "UNKNOWN_TOOL", "Unknown coding tool: " + call.toolName()));
                continue;
            }
            // C5：执行期 scope 检查先于审批与执行；客户端选择的 approvalPolicy=NEVER 不能授权。
            if (!current.authorizedActions().contains(requiredAction)) {
                appendImmediateToolResult(owner, current, call, ToolExecutionResult.error(
                        call.toolName(), "FORBIDDEN",
                        "The run is not authorized for " + requiredAction));
                continue;
            }
            if (descriptor.mutating() && !session.permissionMode().isWritable()) {
                appendImmediateToolResult(owner, current, call, ToolExecutionResult.error(
                        call.toolName(), "READ_ONLY_WORKSPACE",
                        "The session is read-only; mutating tools are not permitted"));
                continue;
            }
            HarnessMessage existingReceipt = findToolReceipt(owner, sessionId, runId,
                    call.toolCallId());
            if (existingReceipt != null) {
                publish(owner, HarnessEvent.draftWithId("tool-call:" + call.toolCallId(),
                        sessionId, runId, "tool.completed", null, call.toolCallId(), null,
                        Map.of("toolName", call.toolName(), "error", existingReceipt.toolError(),
                                "code", String.valueOf(existingReceipt.metadata()
                                        .getOrDefault("code", "ok"))),
                        existingReceipt.timestamp()));
                continue;
            }
            if (descriptor.mutating() && session.approvalPolicy()
                    != com.nageoffer.ai.ragent.coding.model.HarnessApprovalPolicy.NEVER) {
                ToolApproval approval = ToolApproval.pending(UUID.randomUUID().toString(), owner,
                        sessionId, runId, call.toolCallId(), call.toolName(),
                        descriptor.capability(), summarize(call),
                        call.arguments(), argumentsSha256(call.arguments()), now());
                HarnessRunState next = current.withToolApproval(approval, now())
                        .transition(HarnessRunStatus.WAITING_FOR_APPROVAL, null, now());
                saveWithRetry(owner, sessionId, runId, state ->
                        state.status() == HarnessRunStatus.RUNNING
                                && !state.toolApprovals().containsKey(approval.approvalId())
                                ? state.withToolApproval(approval, now())
                                .transition(HarnessRunStatus.WAITING_FOR_APPROVAL, null, now())
                                : state);
                publish(owner, HarnessEvent.draftWithId(
                        "approval-required:" + approval.approvalId(), sessionId, runId,
                        "approval.required", null, call.toolCallId(), approval.approvalId(),
                        Map.of("approvalId", approval.approvalId(),
                                "toolCallId", call.toolCallId(),
                                "toolName", call.toolName(),
                                "capability", descriptor.capability(),
                                "argumentsSha256", approval.argumentsSha256()), approval.createdAt()));
                return true;
            }
            ToolExecutionResult result = tools.execute(call.toolName(), call.arguments(), workspace,
                    runId, current.authorizedActions());
            long receiptTimestamp = appendImmediateToolResult(owner, current, call, result);
            publish(owner, HarnessEvent.draftWithId("tool-call:" + call.toolCallId(),
                    sessionId, runId, "tool.completed", null, call.toolCallId(), null,
                    Map.of("toolName", call.toolName(), "error", result.error(),
                            "code", result.code()), receiptTimestamp));
        }
        // 每个模型工具回合都推进 iteration，保证无限工具循环受 budget.maxIterations 约束。
        saveWithRetry(owner, sessionId, runId, current -> current.status().isTerminal()
                ? current : current.withCounters(current.iteration() + 1,
                current.toolCallCount(), now()));
        return false;
    }

    private long appendImmediateToolResult(HarnessOwner owner, HarnessRunState run,
                                           ModelToolCall call, ToolExecutionResult result) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("code", result.code());
        metadata.put("toolName", call.toolName());
        long timestamp = now();
        store.appendMessage(owner, HarnessMessage.draft(run.sessionId(), run.runId(),
                HarnessMessageRole.TOOL, result.code(), null, List.of(), call.toolCallId(),
                call.toolName(), result.error(), null, metadata, timestamp));
        saveWithRetry(owner, run.sessionId(), run.runId(), current -> current.status().isTerminal()
                ? current : current.withCounters(current.iteration(),
                current.toolCallCount() + 1, now()));
        return timestamp;
    }

    private void publishToolCompleted(HarnessOwner owner, HarnessRunState run, ToolApproval approval,
                                      boolean error, String code, long timestamp) {
        publish(owner, HarnessEvent.draftWithId("tool-completed:" + approval.approvalId(),
                run.sessionId(), run.runId(), "tool.completed", null, approval.toolCallId(),
                approval.approvalId(),
                Map.of("toolName", approval.toolName(), "error", error, "code", code), timestamp));
    }

    private void applyPlanDraft(HarnessOwner owner, String sessionId, String runId,
                                HarnessRunState run, PlanDraft draft) {
        HarnessRunState terminal;
        try {
            terminal = saveWithRetry(owner, sessionId, runId, current -> {
                if (current.status().isTerminal()) {
                    return current;
                }
                CodingPlanState plan = current.plan();
                if (plan == null) {
                    plan = CodingPlanState.create(current.originalRequirement(), now());
                }
                if (plan.mode() != PlanMode.PLAN) {
                    throw new IllegalStateException("The run already left the planning phase");
                }
                CodingPlanState drafted = plan.replacePlan(draft.markdown(), draft.steps(), now());
                return current.withPlan(drafted, now())
                        .transition(HarnessRunStatus.WAITING_FOR_INPUT, null, now());
            });
        } catch (IllegalStateException conflict) {
            fail(owner, run, "PLAN_STATE_CONFLICT", "The run already left the planning phase");
            return;
        }
        CodingPlanState plan = terminal.plan();
        if (plan != null) {
            publish(owner, HarnessEvent.draftWithId(
                    "plan-drafted:" + plan.taskId() + ":" + plan.revision(),
                    sessionId, runId, "plan.drafted", null, null, null,
                    Map.of("taskId", plan.taskId().toString(), "revision", plan.revision(),
                            "hash", plan.canonicalHash()), plan.updatedAt()));
        }
    }

    private void complete(HarnessOwner owner, String sessionId, String runId,
                          HarnessRunState run, ModelTurn turn) {
        long now = now();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("iteration", run.iteration());
        HarnessMessage message = findAssistantTurnMessage(owner, sessionId, runId, run.iteration());
        if (message == null) {
            message = store.appendMessage(owner, HarnessMessage.draft(sessionId,
                    runId, HarnessMessageRole.ASSISTANT, turn.content(), turn.thinking(),
                    List.of(), null, null, false, turn.usage(), metadata, now));
        }
        HarnessRunState terminal = saveWithRetry(owner, sessionId, runId, current -> {
            if (current.status().isTerminal()) {
                return current;
            }
            return current.withCounters(current.iteration() + 1, current.toolCallCount(), now)
                    .transition(HarnessRunStatus.COMPLETED, null, now);
        });
        if (terminal.status() != HarnessRunStatus.COMPLETED) {
            return;
        }
        publish(owner, HarnessEvent.draftWithId("assistant-completed:" + message.messageId(),
                sessionId, runId, "assistant.completed", null, null, null,
                Map.of("messageId", message.messageId(), "iteration", run.iteration()), now));
        publish(owner, HarnessEvent.draftWithId("run-terminal:" + runId + ":run.completed",
                sessionId, runId, "run.completed", null, null, null,
                Map.of("status", "COMPLETED", "revision", terminal.revision()), now));
    }

    private void cancelRun(HarnessOwner owner, HarnessRunState run) {
        long now = now();
        HarnessRunState saved = saveWithRetry(owner, run.sessionId(), run.runId(),
                current -> current.status().isTerminal()
                        ? current : current.transition(HarnessRunStatus.CANCELLED, null, now));
        publish(owner, HarnessEvent.draftWithId("run-terminal:" + run.runId() + ":run.cancelled",
                run.sessionId(), run.runId(), "run.cancelled", null, null, null,
                Map.of("status", "CANCELLED", "revision", saved.revision(),
                        "code", "USER_CANCELLED"), now));
    }

    private void fail(HarnessOwner owner, HarnessRunState run, String code, String message) {
        long now = now();
        try {
            HarnessRunState terminal = saveWithRetry(owner, run.sessionId(), run.runId(),
                    current -> current.status().isTerminal()
                            ? current : current.transition(HarnessRunStatus.FAILED, code, now));
            publish(owner, HarnessEvent.draftWithId("run-terminal:" + run.runId() + ":run.failed",
                    run.sessionId(), run.runId(), "run.failed", null, null, null,
                    Map.of("status", "FAILED", "revision", terminal.revision(), "code", code,
                            "message", message), now));
        } catch (RuntimeException publishOrSaveFailure) {
            org.slf4j.LoggerFactory.getLogger(CodingRunProcessor.class)
                    .warn("coding run failure could not be persisted runId={} code={}",
                            run.runId(), code);
        }
    }

    private void terminalizeAfterFailure(HarnessOwner owner, String sessionId, String runId,
                                         String code) {
        HarnessRunState run = store.findRun(owner, sessionId, runId).orElse(null);
        if (run == null || run.status().isTerminal()) {
            return;
        }
        fail(owner, run, code, "The run processor could not commit a consistent state");
    }

    private List<HarnessMessage> readRunTranscript(HarnessOwner owner, String sessionId,
                                                   String runId, int limit) {
        // C6：先按 runId 过滤再取尾部；不能"取最旧 N 条再过滤"而丢掉当前 run 的上下文。
        List<HarnessMessage> runMessages = readRunMessages(owner, sessionId, runId);
        if (runMessages.size() <= limit) {
            return runMessages;
        }
        return List.copyOf(runMessages.subList(runMessages.size() - limit, runMessages.size()));
    }

    private List<HarnessMessage> readRunMessages(HarnessOwner owner, String sessionId,
                                                 String runId) {
        List<HarnessMessage> runMessages = new ArrayList<>();
        long cursor = 0;
        while (true) {
            List<HarnessMessage> page = store.readMessages(owner, sessionId, cursor, 1_000);
            if (page.isEmpty()) {
                break;
            }
            for (HarnessMessage message : page) {
                if (runId.equals(message.runId())) {
                    runMessages.add(message);
                }
                cursor = Math.max(cursor, message.sequence());
            }
            if (page.size() < 1_000) {
                break;
            }
        }
        return List.copyOf(runMessages);
    }

    private void publish(HarnessOwner owner, HarnessEvent draft) {
        try {
            eventHub.publishIdempotent(owner, draft);
        } catch (RuntimeException publishFailure) {
            // Durable state is already committed. A missing non-terminal event is repaired by the
            // next control command; a missing terminal event is repaired by ensureTerminalEvent.
            org.slf4j.LoggerFactory.getLogger(CodingRunProcessor.class)
                    .warn("coding harness event publish failed runId={} type={}",
                            draft.runId(), draft.type());
        }
    }

    private String summarize(ModelToolCall call) {
        Object path = call.arguments().get("path");
        Object executable = call.arguments().get("executable");
        if (executable != null) {
            return call.toolName() + " " + executable;
        }
        return path == null ? call.toolName() : call.toolName() + " " + path;
    }

    private String argumentsSha256(Map<String, Object> arguments) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(new TreeMap<>(arguments));
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(json));
        } catch (Exception error) {
            throw new IllegalStateException("Cannot digest tool arguments", error);
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
