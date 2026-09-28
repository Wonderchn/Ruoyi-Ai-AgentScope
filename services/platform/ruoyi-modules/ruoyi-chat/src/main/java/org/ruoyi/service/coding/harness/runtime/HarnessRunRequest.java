package org.ruoyi.service.coding.harness.runtime;

import org.ruoyi.service.coding.harness.model.HarnessOwner;

public record HarnessRunRequest(HarnessOwner owner, String sessionId, String runId) {
    public HarnessRunRequest {
        if (owner == null || sessionId == null || sessionId.isBlank()
            || runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("Invalid Harness run request");
        }
    }
}
