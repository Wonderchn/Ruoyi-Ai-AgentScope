package com.nageoffer.ai.ragent.coding.app;

/** Requested harness resource does not exist for the authenticated owner. */
public class CodingNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CodingNotFoundException(String resource, String id) {
        super("Unknown " + resource + ": " + id);
    }
}
