package org.ruoyi.service.coding.harness.model;

/** Durable status of one non-idempotent provider request. */
public enum HarnessModelEffectStatus {
    PENDING,
    SETTLED,
    ABANDONED
}
