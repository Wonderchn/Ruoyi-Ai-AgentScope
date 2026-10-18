package com.nageoffer.ai.ragent.coding.app;

/** Harness state or idempotency key conflicts with the durable snapshot. */
public class CodingConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CodingConflictException(String message) {
        super(message);
    }

    public CodingConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
