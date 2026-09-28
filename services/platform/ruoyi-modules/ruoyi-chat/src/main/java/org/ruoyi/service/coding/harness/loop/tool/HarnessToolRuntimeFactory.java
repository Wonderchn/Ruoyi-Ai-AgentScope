package org.ruoyi.service.coding.harness.loop.tool;

import org.ruoyi.service.coding.harness.model.HarnessRunState;
import org.ruoyi.service.coding.harness.model.HarnessSessionState;

public interface HarnessToolRuntimeFactory {
    HarnessToolRuntime create(HarnessSessionState session, HarnessRunState run);
}
