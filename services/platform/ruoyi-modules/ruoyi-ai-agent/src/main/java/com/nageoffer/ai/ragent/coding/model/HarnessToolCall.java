package com.nageoffer.ai.ragent.coding.model;

import java.util.Map;

/** One model-requested tool call recorded in the transcript. */
public record HarnessToolCall(String toolCallId, String toolName, Map<String, Object> arguments) {

    public HarnessToolCall {
        if (toolCallId == null || toolCallId.isBlank() || toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("Invalid Harness tool call");
        }
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
