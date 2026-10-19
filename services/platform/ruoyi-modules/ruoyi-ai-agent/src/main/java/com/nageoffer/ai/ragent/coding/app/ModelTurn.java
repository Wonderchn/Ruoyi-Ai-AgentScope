package com.nageoffer.ai.ragent.coding.app;

import com.nageoffer.ai.ragent.coding.model.HarnessUsage;

import java.util.List;

/** One model turn: text, tool calls, or a plan draft. */
public record ModelTurn(String content, String thinking, List<ModelToolCall> toolCalls,
                        PlanDraft planDraft, HarnessUsage usage) {

    public ModelTurn {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? HarnessUsage.empty() : usage;
    }

    public static ModelTurn content(String text) {
        return new ModelTurn(text, null, List.of(), null, HarnessUsage.empty());
    }

    public static ModelTurn toolCalls(List<ModelToolCall> calls) {
        return new ModelTurn("", null, calls, null, HarnessUsage.empty());
    }

    public static ModelTurn plan(PlanDraft draft) {
        return new ModelTurn("", null, List.of(), draft, HarnessUsage.empty());
    }
}
