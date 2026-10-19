package com.nageoffer.ai.ragent.coding.tool;

import java.util.Map;

/**
 * Tool capability used by the approval policy and the model adapter.
 *
 * <p>{@code description} and {@code parameters} are the only facts exposed to the model; they
 * must never contain workspace paths, endpoints, credentials or tenant identifiers.
 */
public record CodingToolDescriptor(String name, String capability, boolean mutating,
                                   String description, Map<String, Object> parameters) {

    public static final String READ = "READ";
    public static final String WRITE = "WRITE";

    public CodingToolDescriptor {
        if (name == null || name.isBlank() || capability == null) {
            throw new IllegalArgumentException("Invalid tool descriptor");
        }
        description = description == null || description.isBlank() ? name : description;
        parameters = parameters == null ? emptySchema() : Map.copyOf(parameters);
    }

    /** Backward-compatible constructor for callers that only need capability facts. */
    public CodingToolDescriptor(String name, String capability, boolean mutating) {
        this(name, capability, mutating, name, emptySchema());
    }

    public static Map<String, Object> emptySchema() {
        return Map.of("type", "object", "properties", Map.of());
    }
}
