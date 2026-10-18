package com.nageoffer.ai.ragent.coding.plan;

/** Durable plan feedback. */
public record PlanFeedbackState(String feedbackId, String content, long createdAt) {

    public PlanFeedbackState {
        if (feedbackId == null || feedbackId.isBlank() || content == null || content.isBlank()
                || createdAt <= 0) {
            throw new IllegalArgumentException("Invalid plan feedback");
        }
    }
}
