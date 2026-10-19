package com.nageoffer.ai.ragent.coding.model;

/**
 * Bounded run budget. {@code 0} token ceilings mean "not configured"; iteration and tool
 * counters are always bounded so a run cannot loop forever.
 */
public record HarnessBudget(
        long maxInputTokens,
        long maxOutputTokens,
        int maxIterations,
        int maxToolCalls
) {

    public HarnessBudget {
        if (maxInputTokens < 0 || maxOutputTokens < 0 || maxIterations < 1 || maxToolCalls < 0) {
            throw new IllegalArgumentException("Invalid Harness budget");
        }
    }

    public static HarnessBudget defaults() {
        return new HarnessBudget(0, 0, 24, 64);
    }

    public static HarnessBudget forFollowUp(HarnessBudget previous) {
        return previous == null ? defaults() : previous;
    }

    public boolean inputTokensExceeded(long used) {
        return maxInputTokens > 0 && used > maxInputTokens;
    }

    public boolean outputTokensExceeded(long used) {
        return maxOutputTokens > 0 && used > maxOutputTokens;
    }
}
