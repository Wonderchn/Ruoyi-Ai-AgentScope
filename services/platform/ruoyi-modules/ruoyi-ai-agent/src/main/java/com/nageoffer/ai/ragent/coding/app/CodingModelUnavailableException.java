package com.nageoffer.ai.ragent.coding.app;

/**
 * The model gateway is not configured, the model cannot be resolved, or the provider turn failed.
 *
 * <p>The {@code code} is the stable run-terminal code (never provider text). Messages must not
 * contain endpoints, credentials, or raw provider exception bodies.
 */
public class CodingModelUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String MODEL_NOT_CONFIGURED = "MODEL_NOT_CONFIGURED";
    public static final String MODEL_TURN_FAILED = "MODEL_TURN_FAILED";
    public static final String PLAN_JSON_REQUIRED = "PLAN_JSON_REQUIRED";

    private final String code;

    public CodingModelUnavailableException(String message) {
        this(MODEL_NOT_CONFIGURED, message);
    }

    public CodingModelUnavailableException(String code, String message) {
        super(message);
        this.code = code == null || code.isBlank() ? MODEL_NOT_CONFIGURED : code;
    }

    public String code() {
        return code;
    }
}
