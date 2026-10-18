package com.nageoffer.ai.ragent.coding.event;

/** Closable live subscription for SSE delivery. */
public interface HarnessEventSubscription extends AutoCloseable {

    @Override
    void close();
}
