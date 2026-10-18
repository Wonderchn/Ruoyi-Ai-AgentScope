package com.nageoffer.ai.ragent.coding.model;

/** Immutable model route anchor; the selected model is fixed at run creation. */
public record HarnessModelRoute(int policyVersion, String requestedModel, String selectedModel) {

    public HarnessModelRoute {
        if (policyVersion < 1 || requestedModel == null || requestedModel.isBlank()
                || selectedModel == null || selectedModel.isBlank()) {
            throw new IllegalArgumentException("Invalid Harness model route");
        }
    }
}
