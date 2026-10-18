package com.nageoffer.ai.ragent.coding.approval;

import com.nageoffer.ai.ragent.coding.model.HarnessOwner;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Control-plane approval for one mutating tool call.
 *
 * <p>Identity is bound to owner + session + run + tool call; resolution requires the exact
 * durable revision and argument digest. Replaying the same decision id is idempotent; any other
 * decision after resolution is rejected instead of silently rewriting history.
 */
public record ToolApproval(
        int schemaVersion,
        String approvalId,
        String sessionId,
        String runId,
        String tenantId,
        Long userId,
        String toolCallId,
        String toolName,
        String capability,
        String summary,
        Map<String, Object> arguments,
        String argumentsSha256,
        ApprovalState state,
        long revision,
        String decisionId,
        ApprovalDecision decision,
        boolean consumed,
        String note,
        long createdAt,
        long resolvedAt,
        Long resolvedBy
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public ToolApproval {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        if (schemaVersion < 1 || approvalId == null || approvalId.isBlank()
                || sessionId == null || sessionId.isBlank() || runId == null || runId.isBlank()
                || tenantId == null || tenantId.isBlank() || userId == null || userId <= 0
                || toolCallId == null || toolCallId.isBlank() || toolName == null
                || toolName.isBlank() || capability == null || state == null || revision < 0
                || argumentsSha256 == null || !argumentsSha256.matches("[0-9a-f]{64}")
                || createdAt <= 0 || resolvedAt < 0) {
            throw new IllegalArgumentException("Invalid Harness tool approval");
        }
    }

    public static ToolApproval pending(String approvalId, HarnessOwner owner, String sessionId,
                                       String runId, String toolCallId, String toolName,
                                       String capability, String summary,
                                       Map<String, Object> arguments, String argumentsSha256,
                                       long now) {
        return new ToolApproval(CURRENT_SCHEMA_VERSION, approvalId, sessionId, runId,
                owner.tenantId(), owner.userId(), toolCallId, toolName, capability, summary,
                arguments, argumentsSha256, ApprovalState.PENDING, 0, null, null, false, null,
                now, 0, null);
    }

    public HarnessOwner owner() {
        return new HarnessOwner(tenantId, userId);
    }

    /**
     * @throws IllegalStateException stale revision, mismatched digest, or already resolved with
     *         a different decision
     */
    public ToolApproval resolve(String requestedDecisionId, ApprovalDecision requestedDecision,
                                long expectedRevision, String expectedArgumentsSha256,
                                HarnessOwner caller, String requestedSessionId, String requestedNote,
                                long now) {
        if (!owner().equals(caller) || !sessionId.equals(requestedSessionId)) {
            throw new IllegalArgumentException("Approval does not belong to the authenticated owner");
        }
        if (state != ApprovalState.PENDING) {
            if (decisionId != null && decisionId.equals(requestedDecisionId)
                    && decision == requestedDecision) {
                return this;
            }
            throw new IllegalStateException("approval already resolved");
        }
        if (requestedDecision == null) {
            throw new IllegalArgumentException("decision is required");
        }
        if (requestedDecisionId == null || requestedDecisionId.isBlank()) {
            throw new IllegalArgumentException("decisionId is required");
        }
        if (expectedRevision != revision) {
            throw new IllegalStateException("stale approval revision: expected "
                    + expectedRevision + " but was " + revision);
        }
        if (!constantTimeEquals(argumentsSha256, expectedArgumentsSha256)) {
            throw new IllegalStateException("approval arguments digest changed");
        }
        return new ToolApproval(schemaVersion, approvalId, sessionId, runId, tenantId, userId,
                toolCallId, toolName, capability, summary, arguments, argumentsSha256,
                requestedDecision == ApprovalDecision.APPROVE
                        ? ApprovalState.APPROVED : ApprovalState.DENIED,
                revision + 1, requestedDecisionId, requestedDecision, false,
                normalizeNote(requestedNote), createdAt, now, userId);
    }

    /** Marks a settled tool call (approved or denied) as handled so it cannot run twice. */
    public ToolApproval markConsumed(long now) {
        if (state != ApprovalState.APPROVED && state != ApprovalState.DENIED) {
            throw new IllegalStateException("only a settled tool call can be consumed");
        }
        if (consumed) {
            throw new IllegalStateException("approval already consumed");
        }
        return new ToolApproval(schemaVersion, approvalId, sessionId, runId, tenantId, userId,
                toolCallId, toolName, capability, summary, arguments, argumentsSha256, state,
                revision + 1, decisionId, decision, true, note, createdAt, resolvedAt, resolvedBy);
    }

    private static String normalizeNote(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static boolean constantTimeEquals(String actual, String expected) {
        if (actual == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                expected.strip().toLowerCase(java.util.Locale.ROOT)
                        .getBytes(StandardCharsets.US_ASCII));
    }
}
