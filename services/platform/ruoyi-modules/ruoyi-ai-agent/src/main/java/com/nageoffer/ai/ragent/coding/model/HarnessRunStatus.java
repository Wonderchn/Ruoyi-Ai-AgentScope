package com.nageoffer.ai.ragent.coding.model;

/** Durable run lifecycle. */
public enum HarnessRunStatus {
    QUEUED,
    RUNNING,
    WAITING_FOR_INPUT,
    WAITING_FOR_APPROVAL,
    SUSPENDED,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    public boolean canTransitionTo(HarnessRunStatus target) {
        if (target == null || target == this || isTerminal()) {
            return false;
        }
        return switch (this) {
            case QUEUED -> target == RUNNING || target == CANCELLED || target == SUSPENDED;
            case RUNNING -> target == WAITING_FOR_INPUT || target == WAITING_FOR_APPROVAL
                    || target == SUSPENDED || target == COMPLETED || target == FAILED
                    || target == CANCELLED;
            case WAITING_FOR_INPUT, WAITING_FOR_APPROVAL ->
                    target == QUEUED || target == CANCELLED || target == SUSPENDED;
            case SUSPENDED -> target == QUEUED || target == CANCELLED || target == FAILED;
            default -> false;
        };
    }
}
