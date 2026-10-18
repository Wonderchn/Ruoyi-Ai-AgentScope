package com.nageoffer.ai.ragent.coding.model;

/** Message ledger roles. TOOL results must reference their tool call. */
public enum HarnessMessageRole {
    USER,
    ASSISTANT,
    TOOL,
    CONTROL,
    SYSTEM
}
