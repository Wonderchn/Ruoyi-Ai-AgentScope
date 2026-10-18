package com.nageoffer.ai.ragent.coding.app;

import java.util.List;
import java.util.Map;

/** One tool call requested by the model. */
public record ModelToolCall(String toolCallId, String toolName, Map<String, Object> arguments) {

    public ModelToolCall {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        if (toolCallId == null || toolCallId.isBlank() || toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("Invalid model tool call");
        }
    }

    public List<String> argv() {
        Object raw = arguments.get("argv");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }
}
