package com.nageoffer.ai.ragent.coding.app;

import com.nageoffer.ai.ragent.coding.model.HarnessOwner;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Dispatches one durable QUEUED run to the executor; rejection leaves the run QUEUED. */
public class CodingRunExecutor {

    private final Executor executor;
    private final CodingRunProcessor processor;

    public CodingRunExecutor(Executor executor, CodingRunProcessor processor) {
        this.executor = executor;
        this.processor = processor;
    }

    public void start(HarnessOwner owner, String sessionId, String runId) {
        try {
            executor.execute(() -> processor.process(owner, sessionId, runId));
        } catch (RejectedExecutionException rejected) {
            // The durable QUEUED snapshot remains authoritative; a later control command or
            // maintenance pass can dispatch it again.
            org.slf4j.LoggerFactory.getLogger(CodingRunExecutor.class)
                    .warn("coding run dispatch rejected runId={}", runId);
        }
    }
}
