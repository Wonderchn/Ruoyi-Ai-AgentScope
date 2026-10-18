package com.nageoffer.ai.ragent.coding.app;

/** The model gateway is not configured or the provider refused the turn. */
public class CodingModelUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CodingModelUnavailableException(String message) {
        super(message);
    }
}
