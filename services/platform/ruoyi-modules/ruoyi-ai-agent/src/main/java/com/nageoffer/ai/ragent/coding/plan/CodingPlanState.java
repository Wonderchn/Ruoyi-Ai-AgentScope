package com.nageoffer.ai.ragent.coding.plan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable, revisioned plan aggregate (simplified from the legacy PlanAggregate).
 *
 * <p>The control plane can approve exactly one immutable {@code taskId + revision + hash}
 * tuple. Model tools never invoke this path. Feedback is durable and idempotent by
 * {@code feedbackId}; a different body for the same id is a conflict, never an overwrite.
 */
public record CodingPlanState(
        int schemaVersion,
        UUID taskId,
        long revision,
        PlanMode mode,
        PlanReviewState reviewState,
        String originalRequest,
        String planMarkdown,
        List<PlanStepState> steps,
        List<PlanFeedbackState> feedbackHistory,
        Map<String, PlanApprovalReceipt> approvalReceipts,
        long createdAt,
        long updatedAt
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public CodingPlanState {
        if (schemaVersion < 1 || taskId == null || revision < 0 || mode == null
                || reviewState == null || originalRequest == null || originalRequest.isBlank()) {
            throw new IllegalArgumentException("Invalid plan aggregate identity or state");
        }
        planMarkdown = planMarkdown == null ? "" : planMarkdown;
        steps = steps == null ? List.of() : List.copyOf(steps);
        feedbackHistory = feedbackHistory == null ? List.of() : List.copyOf(feedbackHistory);
        approvalReceipts = approvalReceipts == null ? Map.of() : Map.copyOf(approvalReceipts);
        if (createdAt <= 0 || updatedAt < createdAt) {
            throw new IllegalArgumentException("Invalid plan aggregate timestamps");
        }
        LinkedHashSet<String> stepIds = new LinkedHashSet<>();
        for (PlanStepState step : steps) {
            if (!stepIds.add(step.stepId())) {
                throw new IllegalArgumentException("Duplicate plan step id: " + step.stepId());
            }
        }
    }

    public static CodingPlanState create(UUID taskId, String originalRequest, long now) {
        return new CodingPlanState(CURRENT_SCHEMA_VERSION, taskId, 0, PlanMode.PLAN,
                PlanReviewState.DRAFT, originalRequest, "", List.of(), List.of(), Map.of(), now, now);
    }

    public static CodingPlanState create(String originalRequest, long now) {
        return create(UUID.randomUUID(), originalRequest, now);
    }

    public CodingPlanState replacePlan(String markdown, List<PlanStepState> replacementSteps,
                                       long now) {
        requireMode(PlanMode.PLAN, "replace the plan");
        requireMonotonicTime(now);
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalArgumentException("planMarkdown is required");
        }
        List<PlanStepState> normalized = replacementSteps == null ? List.of() : replacementSteps;
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("A reviewed plan must define at least one step");
        }
        for (PlanStepState step : normalized) {
            for (String dependency : step.dependencyIds()) {
                if (normalized.stream().noneMatch(candidate -> candidate.stepId().equals(dependency))) {
                    throw new IllegalArgumentException("Unknown plan dependency: " + dependency);
                }
            }
        }
        return new CodingPlanState(CURRENT_SCHEMA_VERSION, taskId, revision + 1, mode,
                PlanReviewState.AWAITING_APPROVAL, originalRequest, markdown, normalized,
                feedbackHistory, approvalReceipts, createdAt, now);
    }

    /** Records feedback without changing task identity or the immutable original request. */
    public PlanReviewRequestResult requestRevisionWithResult(String feedbackId, String content,
                                                             long now) {
        CodingPlanState next = requestRevision(feedbackId, content, now);
        return new PlanReviewRequestResult(next, next != this);
    }

    public CodingPlanState requestRevision(String feedbackId, String content, long now) {
        if (feedbackId == null || feedbackId.isBlank()) {
            throw new IllegalArgumentException("feedbackId is required");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("plan feedback content is required");
        }
        String normalizedContent = content.strip();
        for (PlanFeedbackState existing : feedbackHistory) {
            if (existing.feedbackId().equals(feedbackId)) {
                if (existing.content().equals(normalizedContent)) {
                    return this;
                }
                throw new IllegalStateException("idempotency conflict for plan feedback " + feedbackId);
            }
        }
        requireMode(PlanMode.PLAN, "request a plan revision");
        if (reviewState != PlanReviewState.AWAITING_APPROVAL) {
            throw new IllegalStateException("Plan feedback requires an awaiting-approval draft");
        }
        requireMonotonicTime(now);
        List<PlanFeedbackState> next = new ArrayList<>(feedbackHistory);
        next.add(new PlanFeedbackState(feedbackId, normalizedContent, now));
        return new CodingPlanState(CURRENT_SCHEMA_VERSION, taskId, revision + 1, mode,
                PlanReviewState.REVISION_REQUESTED, originalRequest, planMarkdown, steps, next,
                approvalReceipts, createdAt, now);
    }

    /**
     * Approves exactly one immutable taskId/revision/hash tuple. Reusing the same idempotency
     * key with the same command is a no-op; rebinding it is a conflict.
     */
    public CodingPlanState approve(UUID requestedTaskId, long expectedRevision,
                                   String expectedHash, String idempotencyKey, long now) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        PlanApprovalReceipt existing = approvalReceipts.get(idempotencyKey);
        if (existing != null) {
            if (existing.matches(requestedTaskId, expectedRevision, expectedHash)) {
                return this;
            }
            throw new IllegalStateException("idempotency conflict for plan approval "
                    + idempotencyKey);
        }
        requireMode(PlanMode.PLAN, "approve the plan");
        if (reviewState != PlanReviewState.AWAITING_APPROVAL) {
            throw new IllegalStateException("Only an awaiting-approval plan can be approved");
        }
        if (!taskId.equals(requestedTaskId)) {
            throw new IllegalStateException("stale plan taskId");
        }
        if (revision != expectedRevision) {
            throw new IllegalStateException("stale plan revision: expected "
                    + expectedRevision + " but was " + revision);
        }
        String actualHash = canonicalHash();
        if (!constantTimeEquals(actualHash, expectedHash)) {
            throw new IllegalStateException("stale plan hash");
        }
        long approvedRevision = revision + 1;
        Map<String, PlanApprovalReceipt> receipts = new LinkedHashMap<>(approvalReceipts);
        receipts.put(idempotencyKey, new PlanApprovalReceipt(idempotencyKey, taskId,
                expectedRevision, expectedHash, approvedRevision, now));
        return new CodingPlanState(CURRENT_SCHEMA_VERSION, taskId, approvedRevision, PlanMode.BUILD,
                PlanReviewState.APPROVED, originalRequest, planMarkdown, steps, feedbackHistory,
                receipts, createdAt, now);
    }

    /** Deterministic digest over the semantic aggregate (timestamps excluded). */
    public String canonicalHash() {
        StringBuilder canonical = new StringBuilder(512);
        update(canonical, Integer.toString(schemaVersion));
        update(canonical, taskId.toString());
        update(canonical, Long.toString(revision));
        update(canonical, mode.name());
        update(canonical, reviewState.name());
        update(canonical, originalRequest);
        update(canonical, planMarkdown);
        for (PlanStepState step : steps) {
            update(canonical, step.stepId());
            update(canonical, step.title());
            for (String dependency : step.dependencyIds()) {
                update(canonical, dependency);
            }
            update(canonical, "|");
        }
        for (PlanFeedbackState feedback : feedbackHistory) {
            update(canonical, feedback.feedbackId());
            update(canonical, feedback.content());
        }
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** Carries the state plus whether the call actually advanced it (for event emission). */
    public record PlanReviewRequestResult(CodingPlanState plan, boolean changed) {
    }

    private void requireMode(PlanMode expected, String action) {
        if (mode != expected) {
            throw new IllegalStateException("Plan mode " + mode + " cannot " + action);
        }
    }

    private void requireMonotonicTime(long now) {
        if (now < createdAt) {
            throw new IllegalArgumentException("Plan timestamps must be monotonic");
        }
    }

    private static void update(StringBuilder builder, String value) {
        String safe = value == null ? "" : value;
        builder.append(safe.length()).append(':').append(safe);
    }

    private static boolean constantTimeEquals(String actual, String expected) {
        if (actual == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                expected.strip().toLowerCase(java.util.Locale.ROOT)
                        .getBytes(StandardCharsets.US_ASCII));
    }

    public CodingPlanState withRevision(long newRevision) {
        return new CodingPlanState(schemaVersion, taskId, newRevision, mode, reviewState,
                originalRequest, planMarkdown, steps, feedbackHistory, approvalReceipts,
                createdAt, updatedAt);
    }

    public boolean hasStep(String stepId) {
        return steps.stream().anyMatch(step -> Objects.equals(step.stepId(), stepId));
    }
}
