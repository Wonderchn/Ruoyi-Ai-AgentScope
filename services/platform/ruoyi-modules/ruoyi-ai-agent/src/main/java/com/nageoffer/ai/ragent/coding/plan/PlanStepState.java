package com.nageoffer.ai.ragent.coding.plan;

import java.util.List;

/** One immutable plan step as drafted by the model. */
public record PlanStepState(String stepId, String title, List<String> dependencyIds) {

    public PlanStepState {
        if (stepId == null || stepId.isBlank() || title == null || title.isBlank()) {
            throw new IllegalArgumentException("Invalid plan step");
        }
        dependencyIds = dependencyIds == null ? List.of() : List.copyOf(dependencyIds);
        if (dependencyIds.contains(stepId)) {
            throw new IllegalArgumentException("A plan step cannot depend on itself");
        }
    }
}
