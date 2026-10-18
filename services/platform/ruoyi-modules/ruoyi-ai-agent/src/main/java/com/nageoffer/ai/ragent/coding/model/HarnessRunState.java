package com.nageoffer.ai.ragent.coding.model;

import com.nageoffer.ai.ragent.coding.approval.ToolApproval;
import com.nageoffer.ai.ragent.coding.plan.CodingPlanState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Durable control-plane snapshot for one execution of a session. */
public record HarnessRunState(
        int schemaVersion,
        String runId,
        String sessionId,
        String tenantId,
        Long userId,
        HarnessRunStatus status,
        String originalRequirement,
        HarnessPermissionMode permissionMode,
        long permissionRevision,
        HarnessBudget budget,
        HarnessModelRoute modelRoute,
        CodingPlanState plan,
        List<HarnessQueuedInput> pendingInputs,
        Map<String, ToolApproval> toolApprovals,
        int iteration,
        int toolCallCount,
        boolean cancellationRequested,
        String error,
        long createdAt,
        long updatedAt,
        long revision
) {

    public static final int CURRENT_SCHEMA_VERSION = 2;

    public HarnessRunState {
        pendingInputs = pendingInputs == null ? List.of() : List.copyOf(pendingInputs);
        toolApprovals = toolApprovals == null ? Map.of() : Map.copyOf(toolApprovals);
        budget = budget == null ? HarnessBudget.defaults() : budget;
        if (schemaVersion < 1 || runId == null || runId.isBlank() || sessionId == null
                || sessionId.isBlank() || tenantId == null || tenantId.isBlank() || userId == null
                || userId <= 0 || status == null || originalRequirement == null
                || originalRequirement.isBlank() || permissionMode == null
                || permissionRevision < 0 || iteration < 0 || toolCallCount < 0
                || createdAt <= 0 || updatedAt <= 0 || revision < 0) {
            throw new IllegalArgumentException("Invalid Harness run state");
        }
        HarnessOwner aggregateOwner = new HarnessOwner(tenantId, userId);
        toolApprovals.forEach((approvalId, approval) -> {
            if (approval == null || !approvalId.equals(approval.approvalId())
                    || !runId.equals(approval.runId()) || !sessionId.equals(approval.sessionId())
                    || !aggregateOwner.equals(approval.owner())) {
                throw new IllegalArgumentException("Tool approval does not belong to this run");
            }
        });
    }

    public static HarnessRunState createWithId(String runId, HarnessSessionState session,
                                               String requirement, HarnessBudget budget,
                                               HarnessModelRoute modelRoute, long now) {
        if (modelRoute == null) {
            throw new IllegalArgumentException("modelRoute is required");
        }
        return new HarnessRunState(CURRENT_SCHEMA_VERSION, runId, session.sessionId(),
                session.tenantId(), session.userId(), HarnessRunStatus.QUEUED, requirement,
                session.permissionMode(), session.revision(),
                budget == null ? HarnessBudget.defaults() : budget, modelRoute, null,
                List.of(), Map.of(), 0, 0, false, null, now, now, 0);
    }

    public static HarnessRunState create(HarnessSessionState session, String requirement,
                                         HarnessBudget budget, HarnessModelRoute route, long now) {
        return createWithId(UUID.randomUUID().toString(), session, requirement, budget, route, now);
    }

    public HarnessOwner owner() {
        return new HarnessOwner(tenantId, userId);
    }

    public HarnessRunState transition(HarnessRunStatus target, String transitionError, long now) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("Invalid run transition: " + status + " -> " + target);
        }
        return copy(target, plan, pendingInputs, toolApprovals, iteration, toolCallCount,
                cancellationRequested, transitionError, now, revision);
    }

    public HarnessRunState withPlan(CodingPlanState newPlan, long now) {
        return copy(status, newPlan, pendingInputs, toolApprovals, iteration, toolCallCount,
                cancellationRequested, error, now, revision);
    }

    public HarnessRunState enqueue(HarnessQueuedInput input, long now) {
        List<HarnessQueuedInput> next = new ArrayList<>(pendingInputs);
        next.add(input);
        return copy(status, plan, next, toolApprovals, iteration, toolCallCount,
                cancellationRequested, error, now, revision);
    }

    public HarnessRunState withPendingInputs(List<HarnessQueuedInput> inputs, long now) {
        return copy(status, plan, inputs, toolApprovals, iteration, toolCallCount,
                cancellationRequested, error, now, revision);
    }

    public HarnessRunState withToolApproval(ToolApproval approval, long now) {
        if (approval == null || !runId.equals(approval.runId())
                || !sessionId.equals(approval.sessionId()) || !owner().equals(approval.owner())) {
            throw new IllegalArgumentException("Tool approval does not belong to this run");
        }
        Map<String, ToolApproval> next = new LinkedHashMap<>(toolApprovals);
        next.put(approval.approvalId(), approval);
        return copy(status, plan, pendingInputs, next, iteration, toolCallCount,
                cancellationRequested, error, now, revision);
    }

    public boolean hasPendingApproval() {
        return toolApprovals.values().stream()
                .anyMatch(approval -> approval.state()
                        == com.nageoffer.ai.ragent.coding.approval.ApprovalState.PENDING);
    }

    public HarnessRunState withCounters(int newIteration, int newToolCallCount, long now) {
        return copy(status, plan, pendingInputs, toolApprovals, newIteration, newToolCallCount,
                cancellationRequested, error, now, revision);
    }

    public HarnessRunState requestCancellation(long now) {
        if (status.isTerminal()) {
            return this;
        }
        return copy(status, plan, pendingInputs, toolApprovals, iteration, toolCallCount,
                true, error, now, revision);
    }

    public HarnessRunState withRevision(long newRevision) {
        return copy(status, plan, pendingInputs, toolApprovals, iteration, toolCallCount,
                cancellationRequested, error, updatedAt, newRevision);
    }

    private HarnessRunState copy(HarnessRunStatus newStatus, CodingPlanState newPlan,
                                 List<HarnessQueuedInput> newInputs,
                                 Map<String, ToolApproval> newApprovals,
                                 int newIteration, int newToolCallCount, boolean cancelRequested,
                                 String newError, long now, long newRevision) {
        return new HarnessRunState(schemaVersion, runId, sessionId, tenantId, userId, newStatus,
                originalRequirement, permissionMode, permissionRevision, budget, modelRoute,
                newPlan, newInputs, newApprovals, newIteration, newToolCallCount, cancelRequested,
                newError, createdAt, now, newRevision);
    }
}
