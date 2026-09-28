package org.ruoyi.service.coding.harness.app;

public class HarnessNotFoundException extends RuntimeException {
    public HarnessNotFoundException(String resource, String id) {
        super("Unknown Harness " + resource + ": " + id);
    }
}
