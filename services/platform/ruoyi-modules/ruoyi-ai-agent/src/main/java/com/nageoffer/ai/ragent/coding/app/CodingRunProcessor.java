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
import com.nageoffer.ai.ragent.coding.model.HarnessToolCall;
import com.nageoffer.ai.ragent.coding.plan.CodingPlanState;
import com.nageoffer.ai.ragent.coding.plan.PlanMode;
import com.nageoffer.ai.ragent.coding.plan.PlanReviewState;
import com.nageoffer.ai.ragent.coding.store.HarnessStore;
import com.nageoffer.ai.ragent.coding.tool.CodingToolDescriptor;
import com.nageoffer.ai.ragent.coding.tool.CodingToolRuntime;
import com.nageoffer.ai.ragent.coding.tool.ToolExecutionResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Durable single-run loop.
 *
 * <p>Claims a QUEUED run, executes approved tools, asks the model for one turn, and either
 * completes, suspends for plan approval, or suspends for tool approval. Every transition is
 * committed before its event is published; events use stable ids so a retry cannot duplicate
 * them. Provider exception text never enters the durable run or public event payload — only a
 * stable outcome code does.
 */
public class CodingRunProcessor {

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
        HarnessRunState run = store.findRun(owner, sessionId, runId).orElse(null);
        if (run == null || run.status().isTerminal()) {
            return;
        }
        HarnessSessionState session = store.findSession(owner, sessionId).orElse(null);
        if (session == null) {
            return;
        }
        try {
            if (run.status() == HarnessRunStatus.QUEUED) {
                run = store.saveRun(owner, run.transition(HarnessRunStatus.RUNNING, null, now()),
                        run.revision());
                publish(owner, HarnessEvent.draftWithId(
                        "run-started:" + runId + ":" + run.revision(), sessionId, runId,
                        "run.started", null, null, null,
                        Map.of("status", run.status().name(), "revision", run.revision()), now()));
            } else if (run.status() == HarnessRunStatus.WAITING_FOR_APPROVAL
                    && !run.hasPendingApproval()) {
                run = store.saveRun(owner, run.transition(HarnessRunStatus.QUEUED, null, now()),
                        run.revision());
                run = store.saveRun(owner, run.transition(HarnessRunStatus.RUNNING, null, now()),
                        run.revision());
            }
            run = consumeSettledApprovals(owner, run, session);
            while (true) {
                run = store.findRun(owner, sessionId, runId).orElse(null);
                if (run == null || run.status().isTerminal()) {
                    return;
                }
                if (run.cancellationRequested()) {
                    cancelRun(owner, run);
                    return;
                }
                if (run.hasPendingApproval()) {
                    if (run.status() == HarnessRunStatus.RUNNING) {
                        store.saveRun(owner, run.transition(HarnessRunStatus.WAITING_FOR_APPROVAL,
                                null, now()), run.revision());
                    }
                    return;
                }
                if (run.iteration() >= run.budget().maxIterations()) {
                    fail(owner, run, "BUDGET_EXCEEDED", "Iteration budget exhausted");
                    return;
                }
                if (run.budget().maxToolCalls() > 0
                        && run.toolCallCount() >= run.budget().maxToolCalls()) {
                    fail(owner, run, "BUDGET_EXCEEDED", "Tool call budget exhausted");
                    return;
                }
                List<HarnessMessage> transcript = store.readMessages(owner, sessionId, 0,
                        Math.min(10_000, Math.max(100, run.iteration() * 16 + 100))).stream()
                        .filter(message -> runId.equals(message.runId()))
                        .toList();
                ModelTurn turn;
                try {
                    turn = modelGateway.complete(new CodingModelRequest(owner, session, run,
                            transcript, tools.descriptors()));
                } catch (CodingModelUnavailableException unavailable) {
                    fail(owner, run, "MODEL_NOT_CONFIGURED",
                            "The coding model gateway is not configured");
                    return;
                } catch (RuntimeException providerFailure) {
                    fail(owner, run, "MODEL_TURN_FAILED", "The model turn failed");
                    return;
                }
                if (turn.planDraft() != null) {
                    applyPlanDraft(owner, run, turn.planDraft());
                    return;
                }
                if (!turn.toolCalls().isEmpty()) {
                    if (handleToolCalls(owner, run, session, turn.toolCalls())) {
                        return;
                    }
                    continue;
                }
                complete(owner, run, turn);
                return;
            }
        } catch (IllegalStateException conflict) {
            // A concurrent control command advanced the snapshot. The durable state wins; a later
            // command or dispatch will continue from it.
            org.slf4j.LoggerFactory.getLogger(CodingRunProcessor.class)
                    .debug("coding run processing yielded runId={} reason={}", runId,
                            conflict.getMessage());
        }
    }

    private HarnessRunState consumeSettledApprovals(HarnessOwner owner, HarnessRunState run,
                                                    HarnessSessionState session) {
        Path workspace = Path.of(session.workspace());
        for (ToolApproval observed : run.toolApprovals().values()) {
            if (observed.consumed()) {
                continue;
            }
            HarnessRunState current = store.findRun(owner, run.sessionId(), run.runId())
                    .orElse(run);
            ToolApproval approval = current.toolApprovals().get(observed.approvalId());
            if (approval == null || approval.consumed()) {
                continue;
            }
            if (approval.state() == ApprovalState.APPROVED) {
                ToolExecutionResult result = tools.execute(approval.toolName(), approval.arguments(),
                        workspace, run.runId());
                appendToolReceipt(owner, current, approval, result);
                current = store.findRun(owner, run.sessionId(), run.runId()).orElse(current);
                publish(owner, HarnessEvent.draftWithId("tool-completed:" + approval.approvalId(),
                        run.sessionId(), run.runId(), "tool.completed", null,
                        approval.toolCallId(), approval.approvalId(),
                        Map.of("toolName", approval.toolName(), "error", result.error(),
                                "code", result.code()), now()));
                run = current;
            } else if (approval.state() == ApprovalState.DENIED) {
                ToolExecutionResult denied = ToolExecutionResult.error(approval.toolName(),
                        "APPROVAL_DENIED", "Operator denied this tool call");
                appendToolReceipt(owner, current, approval, denied);
                publish(owner, HarnessEvent.draftWithId("tool-completed:" + approval.approvalId(),
                        run.sessionId(), run.runId(), "tool.completed", null,
                        approval.toolCallId(), approval.approvalId(),
                        Map.of("toolName", approval.toolName(), "error", true,
                                "code", "APPROVAL_DENIED"), now()));
                run = store.findRun(owner, run.sessionId(), run.runId()).orElse(current);
            }
        }
        return run;
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
        ToolApproval settled = approval.markConsumed(now());
        HarnessRunState next = run.withToolApproval(settled, now());
        store.saveRun(owner, next, run.revision());
    }

    private boolean handleToolCalls(HarnessOwner owner, HarnessRunState run,
                                    HarnessSessionState session, List<ModelToolCall> calls) {
        Path workspace = Path.of(session.workspace());
        HarnessRunState current = run;
        for (ModelToolCall call : calls) {
            current = store.findRun(owner, current.sessionId(), current.runId()).orElse(current);
            CodingToolDescriptor descriptor = tools.descriptors().stream()
                    .filter(item -> item.name().equals(call.toolName()))
                    .findFirst().orElse(null);
            if (descriptor == null) {
                appendImmediateToolResult(owner, current, call, ToolExecutionResult.error(
                        call.toolName(), "UNKNOWN_TOOL", "Unknown coding tool: " + call.toolName()));
                current = store.findRun(owner, current.sessionId(), current.runId()).orElse(current);
                continue;
            }
            if (descriptor.mutating() && !session.permissionMode().isWritable()) {
                appendImmediateToolResult(owner, current, call, ToolExecutionResult.error(
                        call.toolName(), "READ_ONLY_WORKSPACE",
                        "The session is read-only; mutating tools are not permitted"));
                current = store.findRun(owner, current.sessionId(), current.runId()).orElse(current);
                continue;
            }
            if (descriptor.mutating() && session.approvalPolicy()
                    != com.nageoffer.ai.ragent.coding.model.HarnessApprovalPolicy.NEVER) {
                ToolApproval approval = ToolApproval.pending(UUID.randomUUID().toString(), owner,
                        run.sessionId(), run.runId(), call.toolCallId(), call.toolName(),
                        descriptor.capability(), summarize(call),
                        call.arguments(), argumentsSha256(call.arguments()), now());
                HarnessRunState next = current.withToolApproval(approval, now())
                        .transition(HarnessRunStatus.WAITING_FOR_APPROVAL, null, now());
                store.saveRun(owner, next, current.revision());
                publish(owner, HarnessEvent.draftWithId("approval-required:" + approval.approvalId(),
                        run.sessionId(), run.runId(), "approval.required", null, call.toolCallId(),
                        approval.approvalId(),
                        Map.of("approvalId", approval.approvalId(),
                                "toolCallId", call.toolCallId(),
                                "toolName", call.toolName(),
                                "capability", descriptor.capability(),
                                "argumentsSha256", approval.argumentsSha256()), now()));
                return true;
            }
            ToolExecutionResult result = tools.execute(call.toolName(), call.arguments(), workspace,
                    run.runId());
            appendImmediateToolResult(owner, current, call, result);
            current = store.findRun(owner, current.sessionId(), current.runId()).orElse(current);
            publish(owner, HarnessEvent.draftWithId("tool-call:" + call.toolCallId(),
                    run.sessionId(), run.runId(), "tool.completed", null, call.toolCallId(), null,
                    Map.of("toolName", call.toolName(), "error", result.error(),
                            "code", result.code()), now()));
        }
        return false;
    }

    private void appendImmediateToolResult(HarnessOwner owner, HarnessRunState run,
                                           ModelToolCall call, ToolExecutionResult result) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("code", result.code());
        metadata.put("toolName", call.toolName());
        store.appendMessage(owner, HarnessMessage.draft(run.sessionId(), run.runId(),
                HarnessMessageRole.TOOL, result.code(), null, List.of(), call.toolCallId(),
                call.toolName(), result.error(), null, metadata, now()));
        HarnessRunState next = run.withCounters(run.iteration(), run.toolCallCount() + 1, now());
        store.saveRun(owner, next, run.revision());
    }

    private void applyPlanDraft(HarnessOwner owner, HarnessRunState run, PlanDraft draft) {
        long now = now();
        CodingPlanState plan = run.plan();
        if (plan == null) {
            plan = CodingPlanState.create(run.originalRequirement(), now);
        }
        if (plan.mode() != PlanMode.PLAN) {
            fail(owner, run, "PLAN_STATE_CONFLICT", "The run already left the planning phase");
            return;
        }
        CodingPlanState drafted = plan.replacePlan(draft.markdown(), draft.steps(), now);
        HarnessRunState next = run.withPlan(drafted, now)
                .transition(HarnessRunStatus.WAITING_FOR_INPUT, null, now);
        store.saveRun(owner, next, run.revision());
        publish(owner, HarnessEvent.draftWithId(
                "plan-drafted:" + drafted.taskId() + ":" + drafted.revision(),
                run.sessionId(), run.runId(), "plan.drafted", null, null, null,
                Map.of("taskId", drafted.taskId().toString(), "revision", drafted.revision(),
                        "hash", drafted.canonicalHash()), now));
    }

    private void complete(HarnessOwner owner, HarnessRunState run, ModelTurn turn) {
        long now = now();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("iteration", run.iteration());
        HarnessMessage message = store.appendMessage(owner, HarnessMessage.draft(run.sessionId(),
                run.runId(), HarnessMessageRole.ASSISTANT, turn.content(), turn.thinking(),
                List.of(), null, null, false, turn.usage(), metadata, now));
        HarnessRunState counted = run.withCounters(run.iteration() + 1, run.toolCallCount(), now);
        HarnessRunState saved = store.saveRun(owner, counted, run.revision());
        publish(owner, HarnessEvent.draftWithId("assistant-completed:" + message.messageId(),
                run.sessionId(), run.runId(), "assistant.completed", null, null, null,
                Map.of("messageId", message.messageId(), "iteration", run.iteration()), now));
        HarnessRunState terminal = store.saveRun(owner,
                saved.transition(HarnessRunStatus.COMPLETED, null, now), saved.revision());
        publish(owner, HarnessEvent.draftWithId("run-terminal:" + run.runId() + ":run.completed",
                run.sessionId(), run.runId(), "run.completed", null, null, null,
                Map.of("status", "COMPLETED", "revision", terminal.revision()), now));
    }

    private void cancelRun(HarnessOwner owner, HarnessRunState run) {
        long now = now();
        HarnessRunState cancelled = run.transition(HarnessRunStatus.CANCELLED, null, now);
        HarnessRunState saved = store.saveRun(owner, cancelled, run.revision());
        publish(owner, HarnessEvent.draftWithId("run-terminal:" + run.runId() + ":run.cancelled",
                run.sessionId(), run.runId(), "run.cancelled", null, null, null,
                Map.of("status", "CANCELLED", "revision", saved.revision(),
                        "code", "USER_CANCELLED"), now));
    }

    private void fail(HarnessOwner owner, HarnessRunState run, String code, String message) {
        long now = now();
        HarnessRunState terminal = store.saveRun(owner,
                run.transition(HarnessRunStatus.FAILED, code, now), run.revision());
        publish(owner, HarnessEvent.draftWithId("run-terminal:" + run.runId() + ":run.failed",
                run.sessionId(), run.runId(), "run.failed", null, null, null,
                Map.of("status", "FAILED", "revision", terminal.revision(), "code", code,
                        "message", message), now));
    }

    private void publish(HarnessOwner owner, HarnessEvent draft) {
        eventHub.publishIdempotent(owner, draft);
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
