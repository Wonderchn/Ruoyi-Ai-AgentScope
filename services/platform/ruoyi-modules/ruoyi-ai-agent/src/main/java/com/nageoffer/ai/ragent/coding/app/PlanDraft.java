package com.nageoffer.ai.ragent.coding.app;

import com.nageoffer.ai.ragent.coding.plan.PlanStepState;

import java.util.List;

/** A plan draft proposed by the model; control-plane approval is still required. */
public record PlanDraft(String markdown, List<PlanStepState> steps) {

    public PlanDraft {
        steps = steps == null ? List.of() : List.copyOf(steps);
        if (markdown == null || markdown.isBlank() || steps.isEmpty()) {
            throw new IllegalArgumentException("A plan draft requires markdown and at least one step");
        }
    }
}
