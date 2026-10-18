package com.nageoffer.ai.ragent.coding.app;

import com.nageoffer.ai.ragent.coding.approval.ApprovalDecision;
import com.nageoffer.ai.ragent.coding.approval.ApprovalState;
import com.nageoffer.ai.ragent.coding.approval.ToolApproval;
import com.nageoffer.ai.ragent.coding.event.HarnessEventHub;
import com.nageoffer.ai.ragent.coding.model.HarnessApprovalPolicy;
import com.nageoffer.ai.ragent.coding.model.HarnessBudget;
import com.nageoffer.ai.ragent.coding.model.HarnessEvent;
import com.nageoffer.ai.ragent.coding.model.HarnessInputKind;
import com.nageoffer.ai.ragent.coding.model.HarnessMessage;
import com.nageoffer.ai.ragent.coding.model.HarnessMessageRole;
import com.nageoffer.ai.ragent.coding.model.HarnessModelRoute;
import com.nageoffer.ai.ragent.coding.model.HarnessOwner;
import com.nageoffer.ai.ragent.coding.model.HarnessPermissionMode;
import com.nageoffer.ai.ragent.coding.model.HarnessQueuedInput;
import com.nageoffer.ai.ragent.coding.model.HarnessRunState;
import com.nageoffer.ai.ragent.coding.model.HarnessRunStatus;
import com.nageoffer.ai.ragent.coding.model.HarnessSessionState;
import com.nageoffer.ai.ragent.coding.plan.CodingPlanState;
import com.nageoffer.ai.ragent.coding.plan.PlanFeedbackState;
import com.nageoffer.ai.ragent.coding.store.HarnessStore;
import com.nageoffer.ai.ragent.coding.tool.CodingToolRuntime;
import com.nageoffer.ai.ragent.coding.workspace.CodingWorkspaceService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Authenticated command/query facade for the coding harness. Ownership is never derived from
 * client identifiers; every lookup is scoped to the authenticated {@link HarnessOwner}.
 */
public class CodingHarnessApplicationService {

    static final int MAX_PENDING_INPUTS_PER_RUN = 64;
    static final int MAX_PENDING_INPUT_BYTES_PER_RUN = 1_048_576;

    private final HarnessStore store;
    private final HarnessEventHub eventHub;
    private final CodingWorkspaceService workspaceService;
    private final CodingRunExecutor runExecutor;
    private final CodingToolRuntime tools;

    public CodingHarnessApplicationService(HarnessStore store, HarnessEventHub eventHub,
                                           CodingWorkspaceService workspaceService,
                                           CodingRunExecutor runExecutor, CodingToolRuntime tools) {
        this.store = Objects.requireNonNull(store, "store");
        this.eventHub = Objects.requireNonNull(eventHub, "eventHub");
        this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService");
        this.runExecutor = Objects.requireNonNull(runExecutor, "runExecutor");
        this.tools = tools;
    }

    public record CreateSessionCommand(String workspacePath, String model,
                                       HarnessPermissionMode permissionMode,
                                       HarnessApprovalPolicy approvalPolicy, String title,
                                       String idempotencyKey) {
    }

    public record CreateRunCommand(String requirement, HarnessBudget budget,
                                   String idempotencyKey) {
    }

    public record QueueInputCommand(HarnessInputKind kind, String content, String idempotencyKey) {
    }

    // ------------------------------------------------------------------ sessions

    public HarnessSessionState createSession(HarnessOwner owner, CreateSessionCommand command) {
        if (command == null || command.model() == null || command.model().isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        Path workspace = canonicalWorkspace(owner, command.workspacePath());
        String model = command.model().strip();
        HarnessPermissionMode permissionMode = command.permissionMode() == null
                ? HarnessPermissionMode.READ_ONLY : command.permissionMode();
        HarnessApprovalPolicy approvalPolicy = command.approvalPolicy() == null
                ? HarnessApprovalPolicy.ON_REQUEST : command.approvalPolicy();
        String sessionId = stableId("session", owner, null, command.idempotencyKey());
        HarnessSessionState existing = store.findSession(owner, sessionId).orElse(null);
        if (existing != null) {
            if (!existing.workspace().equals(workspace.toString())
                    || !existing.model().equals(model)
                    || existing.permissionMode() != permissionMode
                    || existing.approvalPolicy() != approvalPolicy
                    || !Objects.equals(existing.title(), command.title())) {
                throw new CodingConflictException(
                        "idempotencyKey was already used for a different session request");
            }
            return existing;
        }
        long now = now();
        return store.createSession(HarnessSessionState.createWithId(sessionId, owner,
                workspace.toString(), model, permissionMode, approvalPolicy, command.title(), now));
    }

    public List<HarnessSessionState> listSessions(HarnessOwner owner) {
        return store.listSessions(owner);
    }

    public HarnessSessionState getSession(HarnessOwner owner, String sessionId) {
        return store.findSession(owner, sessionId)
                .filter(session -> session.deletedAt() == 0)
                .orElseThrow(() -> new CodingNotFoundException("session", sessionId));
    }

    public HarnessSessionState setSessionPinned(HarnessOwner owner, String sessionId,
                                                boolean pinned) {
        getSession(owner, sessionId);
        return store.setSessionPinned(owner, sessionId, pinned);
    }

    public void deleteSession(HarnessOwner owner, String sessionId) {
        getSession(owner, sessionId);
        store.setSessionDeleted(owner, sessionId, true);
    }

    public HarnessSessionState restoreSession(HarnessOwner owner, String sessionId) {
        return store.setSessionDeleted(owner, sessionId, false);
    }

    // ------------------------------------------------------------------ runs

    public List<HarnessRunState> listRuns(HarnessOwner owner, String sessionId) {
        getSession(owner, sessionId);
        return store.listRuns(owner, sessionId);
    }

    public HarnessRunState getRun(HarnessOwner owner, String sessionId, String runId) {
        getSession(owner, sessionId);
        return store.findRun(owner, sessionId, runId)
                .orElseThrow(() -> new CodingNotFoundException("run", runId));
    }

    public HarnessRunState createRun(HarnessOwner owner, String sessionId,
                                     CreateRunCommand command) {
        if (command == null || command.requirement() == null || command.requirement().isBlank()) {
            throw new IllegalArgumentException("requirement is required");
        }
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        HarnessBudget budget = command.budget() == null
                ? HarnessBudget.defaults() : command.budget();
        HarnessSessionState session = getSession(owner, sessionId);
        String runId = stableId("run", owner, sessionId, command.idempotencyKey());
        HarnessRunState existing = store.findRun(owner, sessionId, runId).orElse(null);
        if (existing != null) {
            if (!existing.originalRequirement().equals(command.requirement())
                    || !existing.budget().equals(budget)) {
                throw new CodingConflictException(
                        "idempotencyKey was already used for a different run request");
            }
            return existing;
        }
        requireNoActiveRun(owner, session);
        long now = now();
        HarnessModelRoute route = new HarnessModelRoute(1, session.model(), session.model());
        HarnessRunState run = HarnessRunState.createWithId(runId, session, command.requirement(),
                budget, route, now);
        run = store.createRun(owner, run);
        publish(owner, HarnessEvent.draftWithId("run-created:" + runId, sessionId, runId,
                "run.created", null, null, null,
                Map.of("status", run.status().name(), "model", route.selectedModel()), now));
        store.saveSession(owner, session.withActiveRun(runId, now), session.revision());
        appendInitialMessageIfMissing(owner, run, now);
        runExecutor.start(owner, sessionId, runId);
        return store.findRun(owner, sessionId, runId).orElse(run);
    }

    public HarnessRunState queueInput(HarnessOwner owner, String sessionId, String runId,
                                      QueueInputCommand command) {
        if (command == null || command.content() == null || command.content().isBlank()
                || command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("Queued input requires kind, content and key");
        }
        HarnessRunState run = getRun(owner, sessionId, runId);
        if (run.status().isTerminal()) {
            if (command.kind() == HarnessInputKind.FOLLOW_UP) {
                return createRun(owner, sessionId, new CreateRunCommand(command.content(),
                        HarnessBudget.forFollowUp(run.budget()),
                        "follow-up:" + command.idempotencyKey()));
            }
            throw new CodingConflictException("Cannot steer a terminal run");
        }
        long now = now();
        String inputId = stableId("input", owner, sessionId,
                runId + "\u0000" + command.idempotencyKey());
        HarnessQueuedInput existing = run.pendingInputs().stream()
                .filter(input -> input.inputId().equals(inputId)).findFirst().orElse(null);
        if (existing != null && (existing.kind() != command.kind()
                || !existing.content().equals(command.content()))) {
            throw new CodingConflictException(
                    "idempotencyKey was already used for a different queued input");
        }
        HarnessRunState next = run;
        if (existing == null) {
            requirePendingInputCapacity(run, command.content());
            HarnessQueuedInput input = HarnessQueuedInput.createWithId(inputId, command.kind(),
                    command.content(), now);
            next = next.enqueue(input, now);
            publish(owner, HarnessEvent.draftWithId(
                    "input-queued:" + inputId, sessionId, runId, "input.queued", null, null, null,
                    Map.of("inputId", inputId, "kind", command.kind().name()), now));
        }
        boolean wake = next.status() == HarnessRunStatus.WAITING_FOR_INPUT;
        if (wake) {
            next = next.transition(HarnessRunStatus.QUEUED, null, now);
        }
        HarnessRunState saved = next == run ? run : store.saveRun(owner, next, run.revision());
        appendControlMessageIfMissing(owner, run, inputId, command.content(), now);
        if (wake) {
            runExecutor.start(owner, sessionId, runId);
            return store.findRun(owner, sessionId, runId).orElse(saved);
        }
        return saved;
    }

    public HarnessRunState cancel(HarnessOwner owner, String sessionId, String runId) {
        HarnessRunState run = getRun(owner, sessionId, runId);
        if (run.status().isTerminal()) {
            return run;
        }
        long now = now();
        HarnessRunState requested = store.saveRun(owner, run.requestCancellation(now), run.revision());
        publish(owner, HarnessEvent.draftWithId("run-cancel-requested:" + runId, sessionId, runId,
                "run.cancel.requested", null, null, null,
                Map.of("status", requested.status().name(), "revision", requested.revision(),
                        "code", "USER_CANCEL_REQUESTED"), now));
        HarnessRunState terminal = requested.transition(HarnessRunStatus.CANCELLED, null, now);
        HarnessRunState saved = store.saveRun(owner, terminal, requested.revision());
        publish(owner, HarnessEvent.draftWithId("run-terminal:" + runId + ":run.cancelled",
                sessionId, runId, "run.cancelled", null, null, null,
                Map.of("status", "CANCELLED", "revision", saved.revision(),
                        "code", "USER_CANCELLED"), now));
        return saved;
    }

    public HarnessRunState resume(HarnessOwner owner, String sessionId, String runId) {
        HarnessRunState run = getRun(owner, sessionId, runId);
        if (run.status() == HarnessRunStatus.RUNNING) {
            return run;
        }
        if (run.status() != HarnessRunStatus.SUSPENDED
                && run.status() != HarnessRunStatus.QUEUED) {
            throw new CodingConflictException("Only a suspended run can be resumed");
        }
        if (run.status() == HarnessRunStatus.SUSPENDED) {
            long now = now();
            HarnessRunState queued = store.saveRun(owner,
                    run.transition(HarnessRunStatus.QUEUED, null, now), run.revision());
            publish(owner, HarnessEvent.draftWithId("run-queued-resume:" + runId, sessionId, runId,
                    "run.queued", null, null, null,
                    Map.of("status", "QUEUED", "reason", "resume", "revision", queued.revision()),
                    now));
            runExecutor.start(owner, sessionId, runId);
            return store.findRun(owner, sessionId, runId).orElse(queued);
        }
        runExecutor.start(owner, sessionId, runId);
        return store.findRun(owner, sessionId, runId).orElse(run);
    }

    // ------------------------------------------------------------------ approvals

    public HarnessRunState resolveToolApproval(HarnessOwner owner, String sessionId, String runId,
                                               String approvalId, String decisionId,
                                               ApprovalDecision decision, long expectedRevision,
                                               String argumentsSha256, String note) {
        HarnessRunState run = getRun(owner, sessionId, runId);
        ToolApproval approval = run.toolApprovals().get(approvalId);
        if (approval == null) {
            throw new CodingNotFoundException("tool approval", approvalId);
        }
        long now = now();
        ToolApproval resolved;
        try {
            resolved = approval.resolve(decisionId, decision, expectedRevision, argumentsSha256,
                    owner, sessionId, note, now);
        } catch (IllegalStateException conflict) {
            throw new CodingConflictException(conflict.getMessage(), conflict);
        }
        if (resolved == approval) {
            return run;
        }
        HarnessRunState next = run.withToolApproval(resolved, now);
        boolean pending = next.hasPendingApproval();
        boolean schedule = next.status() == HarnessRunStatus.WAITING_FOR_APPROVAL && !pending;
        if (schedule) {
            next = next.transition(HarnessRunStatus.QUEUED, null, now);
        }
        publish(owner, HarnessEvent.draftWithId(
                "approval-resolved:" + approvalId + ":" + decisionId, sessionId, runId,
                "approval.resolved", null, approval.toolCallId(), approvalId,
                Map.of("decision", decision.name(), "state", resolved.state().name()), now));
        HarnessRunState saved = store.saveRun(owner, next, run.revision());
        if (schedule) {
            runExecutor.start(owner, sessionId, runId);
            return store.findRun(owner, sessionId, runId).orElse(saved);
        }
        return saved;
    }

    // ------------------------------------------------------------------ plan

    public HarnessRunState approvePlan(HarnessOwner owner, String sessionId, String runId,
                                       UUID taskId, long expectedRevision, String expectedHash,
                                       String idempotencyKey) {
        HarnessRunState run = getRun(owner, sessionId, runId);
        CodingPlanState plan = run.plan();
        if (plan == null) {
            throw new CodingConflictException("Run has no authoritative execution plan");
        }
        long now = now();
        CodingPlanState approved;
        try {
            approved = plan.approve(taskId, expectedRevision, expectedHash, idempotencyKey, now);
        } catch (IllegalStateException conflict) {
            throw new CodingConflictException(conflict.getMessage(), conflict);
        }
        if (approved == plan) {
            return run;
        }
        HarnessRunState next = run.withPlan(approved, now);
        boolean schedule = next.status() == HarnessRunStatus.WAITING_FOR_INPUT;
        if (schedule) {
            next = next.transition(HarnessRunStatus.QUEUED, null, now);
        }
        publish(owner, HarnessEvent.draftWithId(
                "plan-approved:" + approved.taskId() + ":" + idempotencyKey, sessionId, runId,
                "plan.approved", null, null, null,
                Map.of("taskId", approved.taskId().toString(), "revision", approved.revision(),
                        "hash", approved.canonicalHash(), "idempotencyKey", idempotencyKey), now));
        HarnessRunState saved = store.saveRun(owner, next, run.revision());
        if (schedule) {
            runExecutor.start(owner, sessionId, runId);
            return store.findRun(owner, sessionId, runId).orElse(saved);
        }
        return saved;
    }

    public HarnessRunState requestPlanRevision(HarnessOwner owner, String sessionId, String runId,
                                               UUID taskId, long expectedRevision,
                                               String expectedHash, String feedbackId,
                                               String content) {
        if (feedbackId == null || feedbackId.isBlank() || content == null || content.isBlank()) {
            throw new IllegalArgumentException("feedbackId and content are required");
        }
        HarnessRunState run = getRun(owner, sessionId, runId);
        CodingPlanState plan = run.plan();
        if (plan == null) {
            throw new CodingConflictException("Run has no authoritative execution plan");
        }
        String normalizedContent = content.strip();
        PlanFeedbackState prior = plan.feedbackHistory().stream()
                .filter(feedback -> feedback.feedbackId().equals(feedbackId))
                .findFirst().orElse(null);
        if (prior != null && !prior.content().equals(normalizedContent)) {
            throw new CodingConflictException(
                    "feedbackId was already used with different plan feedback");
        }
        boolean idempotentReplay = prior != null && plan.taskId().equals(taskId);
        if (!idempotentReplay && (!plan.taskId().equals(taskId)
                || plan.revision() != expectedRevision
                || !constantTimeEquals(plan.canonicalHash(), expectedHash))) {
            throw new CodingConflictException(
                    "Plan feedback targets a stale taskId, revision, or hash");
        }
        long now = now();
        CodingPlanState.PlanReviewRequestResult result;
        try {
            result = plan.requestRevisionWithResult(feedbackId, normalizedContent, now);
        } catch (IllegalStateException conflict) {
            throw new CodingConflictException(conflict.getMessage(), conflict);
        }
        if (!result.changed()) {
            return run;
        }
        HarnessRunState next = run.withPlan(result.plan(), now);
        boolean schedule = next.status() == HarnessRunStatus.WAITING_FOR_INPUT;
        if (schedule) {
            next = next.transition(HarnessRunStatus.QUEUED, null, now);
        }
        PlanFeedbackState durable = result.plan().feedbackHistory().stream()
                .filter(feedback -> feedback.feedbackId().equals(feedbackId))
                .findFirst().orElseThrow();
        publish(owner, HarnessEvent.draftWithId(
                "plan-revision:" + feedbackId, sessionId, runId, "plan.revision.requested",
                null, null, null,
                Map.of("taskId", taskId.toString(), "feedbackId", feedbackId,
                        "revision", result.plan().revision(),
                        "hash", result.plan().canonicalHash()), durable.createdAt()));
        HarnessRunState saved = store.saveRun(owner, next, run.revision());
        if (schedule) {
            runExecutor.start(owner, sessionId, runId);
            return store.findRun(owner, sessionId, runId).orElse(saved);
        }
        return saved;
    }

    // ------------------------------------------------------------------ reads

    public List<HarnessMessage> readMessages(HarnessOwner owner, String sessionId,
                                             long afterSequence, int limit) {
        getSession(owner, sessionId);
        return store.readMessages(owner, sessionId, afterSequence, limit);
    }

    public List<HarnessEvent> readEvents(HarnessOwner owner, String sessionId, String runId,
                                         long afterSequence, int limit) {
        HarnessRunState run = getRun(owner, sessionId, runId);
        if (run.status().isTerminal()) {
            eventHub.ensureTerminalEvent(owner, sessionId, runId);
        }
        return store.readEvents(owner, sessionId, runId, afterSequence, limit);
    }

    public CodingToolRuntime tools() {
        return tools;
    }

    // ------------------------------------------------------------------ helpers

    private void requireNoActiveRun(HarnessOwner owner, HarnessSessionState session) {
        if (session.activeRunId() == null) {
            return;
        }
        HarnessRunState active = store.findRun(owner, session.sessionId(), session.activeRunId())
                .orElse(null);
        if (active != null && !active.status().isTerminal()) {
            throw new CodingConflictException("Session already has an active run: " + active.runId());
        }
    }

    private void appendInitialMessageIfMissing(HarnessOwner owner, HarnessRunState run, long now) {
        String inputId = "run-create:" + run.runId();
        if (messageWithInputIdExists(owner, run.sessionId(), inputId)) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", HarnessInputKind.INITIAL.name());
        metadata.put("inputId", inputId);
        store.appendMessage(owner, HarnessMessage.draft(run.sessionId(), run.runId(),
                HarnessMessageRole.USER, run.originalRequirement(), null, List.of(), null, null,
                false, null, metadata, now));
    }

    private void appendControlMessageIfMissing(HarnessOwner owner, HarnessRunState run,
                                               String inputId, String content, long now) {
        if (messageWithInputIdExists(owner, run.sessionId(), inputId)) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", "STEER");
        metadata.put("queued", true);
        metadata.put("inputId", inputId);
        store.appendMessage(owner, HarnessMessage.draft(run.sessionId(), run.runId(),
                HarnessMessageRole.CONTROL, content, null, List.of(), null, null, false, null,
                metadata, now));
    }

    private boolean messageWithInputIdExists(HarnessOwner owner, String sessionId, String inputId) {
        long cursor = 0;
        while (true) {
            List<HarnessMessage> page = store.readMessages(owner, sessionId, cursor, 1_000);
            if (page.isEmpty()) {
                return false;
            }
            for (HarnessMessage message : page) {
                if (inputId.equals(message.metadata().get("inputId"))) {
                    return true;
                }
                cursor = message.sequence();
            }
        }
    }

    private void requirePendingInputCapacity(HarnessRunState run, String content) {
        if (run.pendingInputs().size() >= MAX_PENDING_INPUTS_PER_RUN) {
            throw new CodingConflictException("Pending input queue limit exceeded");
        }
        long bytes = content.getBytes(StandardCharsets.UTF_8).length;
        for (HarnessQueuedInput pending : run.pendingInputs()) {
            bytes += pending.content().getBytes(StandardCharsets.UTF_8).length;
        }
        if (bytes > MAX_PENDING_INPUT_BYTES_PER_RUN) {
            throw new CodingConflictException("Pending input byte budget exceeded");
        }
    }

    private Path canonicalWorkspace(HarnessOwner owner, String requested) {
        Path root = workspaceService.resolveHarnessRoot(owner, requested);
        try {
            Files.createDirectories(root);
            return root.toRealPath();
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("Cannot open workspace: " + root, error);
        }
    }

    private void publish(HarnessOwner owner, HarnessEvent event) {
        eventHub.publishIdempotent(owner, event);
    }

    private String stableId(String domain, HarnessOwner owner, String sessionId,
                            String idempotencyKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, domain);
            updateDigest(digest, owner.tenantId());
            updateDigest(digest, owner.userId().toString());
            updateDigest(digest, sessionId == null ? "" : sessionId);
            updateDigest(digest, idempotencyKey);
            byte[] value = digest.digest();
            StringBuilder encoded = new StringBuilder(50);
            encoded.append(switch (domain) {
                case "session" -> "s_";
                case "run" -> "r_";
                case "input" -> "i_";
                case "event" -> "e_";
                default -> throw new IllegalArgumentException("Unknown stable id domain");
            });
            for (int index = 0; index < 24; index++) {
                encoded.append(Character.forDigit((value[index] >>> 4) & 0xf, 16));
                encoded.append(Character.forDigit(value[index] & 0xf, 16));
            }
            return encoded.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private boolean constantTimeEquals(String actual, String expected) {
        if (actual == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                expected.strip().toLowerCase(java.util.Locale.ROOT)
                        .getBytes(StandardCharsets.US_ASCII));
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
