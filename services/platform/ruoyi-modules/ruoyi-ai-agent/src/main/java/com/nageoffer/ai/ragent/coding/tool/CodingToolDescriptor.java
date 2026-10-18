package com.nageoffer.ai.ragent.coding.tool;

/** Tool capability used by the approval policy. */
public record CodingToolDescriptor(String name, String capability, boolean mutating) {

    public static final String READ = "READ";
    public static final String WRITE = "WRITE";

    public CodingToolDescriptor {
        if (name == null || name.isBlank() || capability == null) {
            throw new IllegalArgumentException("Invalid tool descriptor");
        }
    }
}
