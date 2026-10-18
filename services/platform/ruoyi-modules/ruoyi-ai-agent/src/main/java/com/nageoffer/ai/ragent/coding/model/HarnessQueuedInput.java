package com.nageoffer.ai.ragent.coding.model;

/** Durable steering input queued for a running/waiting run. */
public record HarnessQueuedInput(String inputId, HarnessInputKind kind, String content,
                                 long createdAt) {

    public HarnessQueuedInput {
        if (inputId == null || inputId.isBlank() || kind == null || content == null
                || content.isBlank() || createdAt <= 0) {
            throw new IllegalArgumentException("Invalid Harness queued input");
        }
    }

    public static HarnessQueuedInput createWithId(String inputId, HarnessInputKind kind,
                                                  String content, long now) {
        return new HarnessQueuedInput(inputId, kind, content, now);
    }
}
