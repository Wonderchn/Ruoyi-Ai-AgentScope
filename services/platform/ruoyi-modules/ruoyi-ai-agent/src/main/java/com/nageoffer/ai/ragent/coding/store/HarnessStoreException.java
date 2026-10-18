package com.nageoffer.ai.ragent.coding.store;

/** Unchecked persistence failure for the file-backed harness store. */
public class HarnessStoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public HarnessStoreException(String message) {
        super(message);
    }

    public HarnessStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
