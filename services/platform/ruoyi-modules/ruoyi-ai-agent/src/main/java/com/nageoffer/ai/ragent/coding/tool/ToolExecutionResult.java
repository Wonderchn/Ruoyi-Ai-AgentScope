package com.nageoffer.ai.ragent.coding.tool;

import java.util.Map;

/** Bounded tool outcome recorded as a TOOL transcript message. */
public record ToolExecutionResult(String toolName, boolean error, String code,
                                  Map<String, Object> data) {

    public ToolExecutionResult {
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public static ToolExecutionResult ok(String toolName, Map<String, Object> data) {
        return new ToolExecutionResult(toolName, false, "ok", data);
    }

    public static ToolExecutionResult error(String toolName, String code, String message) {
        return new ToolExecutionResult(toolName, true, code, Map.of("message", message));
    }
}
