package com.nageoffer.ai.ragent.coding.store;

/** Optimistic concurrency failure: the durable snapshot advanced past the expected revision. */
public class HarnessOptimisticLockException extends HarnessStoreException {

    private static final long serialVersionUID = 1L;

    public HarnessOptimisticLockException(String resource, long expected, long actual) {
        super(resource + " revision conflict: expected " + expected + " but was " + actual);
    }
}
