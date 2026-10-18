package com.nageoffer.ai.ragent.coding.plan;

/** Review lifecycle of the durable plan draft. */
public enum PlanReviewState {
    DRAFT,
    AWAITING_APPROVAL,
    REVISION_REQUESTED,
    APPROVED
}
