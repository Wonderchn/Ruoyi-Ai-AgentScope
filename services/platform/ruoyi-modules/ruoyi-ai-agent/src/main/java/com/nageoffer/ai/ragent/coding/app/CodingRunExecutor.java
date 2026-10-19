package com.nageoffer.ai.ragent.coding.app;

import com.nageoffer.ai.ragent.coding.model.HarnessOwner;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Dispatches one durable QUEUED run to the executor; rejection leaves the run QUEUED.
 *
 * <p>F20-A2 C1d：per-run single-flight. Concurrent control commands (resume + steering wake)
 * may both request dispatch; only the first submission starts a model turn. The claim is
 * process-local, matching the single-instance file store; a multi-node deployment needs a
 * durable lease.
 */
public class CodingRunExecutor {

    private final Executor executor;
    private final CodingRunProcessor processor;
    private final ConcurrentMap<String, Boolean> inFlight = new ConcurrentHashMap<>();

    public CodingRunExecutor(Executor executor, CodingRunProcessor processor) {
        this.executor = executor;
        this.processor = processor;
    }

    public void start(HarnessOwner owner, String sessionId, String runId) {
        String key = owner.tenantId() + '\0' + owner.userId() + '\0' + sessionId + '\0' + runId;
        if (inFlight.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    processor.process(owner, sessionId, runId);
                } finally {
                    inFlight.remove(key);
                }
            });
        } catch (RejectedExecutionException rejected) {
            inFlight.remove(key);
            // The durable QUEUED snapshot remains authoritative; a later control command or
            // maintenance pass can dispatch it again.
            org.slf4j.LoggerFactory.getLogger(CodingRunExecutor.class)
                    .warn("coding run dispatch rejected runId={}", runId);
        } catch (RuntimeException failure) {
            inFlight.remove(key);
            throw failure;
        }
    }
}
