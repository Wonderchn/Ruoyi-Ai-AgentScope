package com.nageoffer.ai.ragent.coding.model;

/** Token accounting; zero means the provider did not report usage. */
public record HarnessUsage(long inputTokens, long outputTokens, long totalTokens) {

    public HarnessUsage {
        if (inputTokens < 0 || outputTokens < 0 || totalTokens < 0) {
            throw new IllegalArgumentException("Harness usage cannot be negative");
        }
    }

    public static HarnessUsage empty() {
        return new HarnessUsage(0, 0, 0);
    }

    public boolean isZero() {
        return inputTokens == 0 && outputTokens == 0 && totalTokens == 0;
    }
}
