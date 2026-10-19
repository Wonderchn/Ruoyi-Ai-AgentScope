package com.nageoffer.ai.ragent.coding.sandbox;

/** Fail-closed sandbox error with a stable machine code. */
public class SandboxCommandException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public SandboxCommandException(String code, String message) {
        super(message);
        this.code = code;
    }

    public SandboxCommandException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
