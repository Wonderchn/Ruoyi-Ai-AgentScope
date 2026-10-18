package com.nageoffer.ai.ragent.coding.plan;

import java.util.UUID;

/** Idempotency receipt for one approved plan tuple. */
public record PlanApprovalReceipt(
        String idempotencyKey,
        UUID taskId,
        long expectedRevision,
        String expectedHash,
        long approvedRevision,
        long approvedAt
) {

    public boolean matches(UUID requestedTaskId, long requestedRevision, String requestedHash) {
        return taskId.equals(requestedTaskId) && expectedRevision == requestedRevision
                && expectedHash != null && expectedHash.equalsIgnoreCase(requestedHash);
    }
}
