package org.ruoyi.service.coding.harness.runtime;

public enum HarnessScheduleResult {
    SCHEDULED,
    ALREADY_SCHEDULED,
    /** Durable QUEUED state remains the outbox; a later maintenance sweep retries admission. */
    DEFERRED_CAPACITY
}
