package com.nageoffer.ai.ragent.coding.model;

/** Requested ceiling of a session; it is never a client-granted authority. */
public enum HarnessPermissionMode {
    READ_ONLY,
    WORKSPACE_WRITE;

    public boolean isWritable() {
        return this == WORKSPACE_WRITE;
    }
}
