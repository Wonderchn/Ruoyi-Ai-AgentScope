package com.nageoffer.ai.ragent.coding.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.coding.event.HarnessEventHub;
import com.nageoffer.ai.ragent.coding.model.HarnessApprovalPolicy;
import com.nageoffer.ai.ragent.coding.model.HarnessEvent;
import com.nageoffer.ai.ragent.coding.model.HarnessMessage;
import com.nageoffer.ai.ragent.coding.model.HarnessModelRoute;
import com.nageoffer.ai.ragent.coding.model.HarnessOwner;
import com.nageoffer.ai.ragent.coding.model.HarnessPermissionMode;
import com.nageoffer.ai.ragent.coding.model.HarnessRunState;
import com.nageoffer.ai.ragent.coding.model.HarnessRunStatus;
import com.nageoffer.ai.ragent.coding.model.HarnessSessionState;
import com.nageoffer.ai.ragent.coding.sandbox.DockerSandboxConfig;
import com.nageoffer.ai.ragent.coding.store.FileHarnessStore;
import com.nageoffer.ai.ragent.coding.store.HarnessOptimisticLockException;
import com.nageoffer.ai.ragent.coding.store.HarnessStore;
import com.nageoffer.ai.ragent.coding.tool.CodingToolRuntime;
import com.nageoffer.ai.ragent.coding.workspace.CodingWorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F20-A2 目标 B：R2 残余并发 C1b–C1e。
 *
 * <p>C1b replay 修复事件后重新调度；C1c 条件 claim 后不得发布矛盾的 run.started；
 * C1d 同一 run 单飞；C1e cancel/resume 乐观冲突映射 409。
 */
@Tag("dev")
class CodingRunConcurrencyHardeningTest {

    private static final Set<String> ALL_ACTIONS = Set.of(
            "coding.use", "coding.write", "coding.approve", "coding.command");

    @TempDir
    Path temp;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HarnessOwner owner = new HarnessOwner("T1", 42L);
    private FileHarnessStore store;
    private CodingWorkspaceService workspace;
    private CodingToolRuntime tools;

    @BeforeEach
    void setUp() {
        store = new FileHarnessStore(mapper, temp.resolve("state"));
        workspace = new CodingWorkspaceService(temp.resolve("ws"), null);
        tools = new CodingToolRuntime(workspace, DockerSandboxConfig.UNCONFIGURED, false);
    }

    private HarnessSessionState session(HarnessStore backing) {
        return backing.createSession(HarnessSessionState.createWithId("s1", owner,
                temp.resolve("ws").toAbsolutePath().toString(), "deepseek-flash",
                HarnessPermissionMode.WORKSPACE_WRITE, HarnessApprovalPolicy.NEVER, "s",
                System.currentTimeMillis()));
    }

    private HarnessRunState queuedRun(HarnessStore backing, HarnessSessionState session) {
        return backing.createRun(owner, HarnessRunState.createWithId("r1", session, "do it", null,
                new HarnessModelRoute(1, session.model(), session.model()), ALL_ACTIONS,
                System.currentTimeMillis()));
    }

    private CodingHarnessApplicationService service(HarnessStore backing, HarnessEventHub hub,
                                                    Executor executor,
                                                    CodingModelGateway gateway) {
        CodingRunProcessor processor = new CodingRunProcessor(backing, hub, gateway, tools, mapper);
        return new CodingHarnessApplicationService(backing, hub, workspace,
                new CodingRunExecutor(executor, processor), tools);
    }

    // ------------------------------------------------------------------ C1b

    @Test
    void c1bReplayRepairsTheEventAndDispatchesTheQueuedRun() {
        SwitchableExecutor executor = new SwitchableExecutor();
        CreatedEventFailingHub hub = new CreatedEventFailingHub(store, Runnable::run, 1);
        CountingGateway gateway = new CountingGateway();
        CodingHarnessApplicationService service = service(store, hub, executor, gateway);
        HarnessSessionState session = session(store);

        HarnessRunState first = service.createRun(owner, session.sessionId(),
                new CodingHarnessApplicationService.CreateRunCommand("do it", null, "r-c1b",
                        ALL_ACTIONS));
        assertThat(first.status()).isEqualTo(HarnessRunStatus.QUEUED);
        assertThat(service.readEvents(owner, session.sessionId(), first.runId(), 0, 100)).isEmpty();

        executor.delegate = Runnable::run;
        HarnessRunState replay = service.createRun(owner, session.sessionId(),
                new CodingHarnessApplicationService.CreateRunCommand("do it", null, "r-c1b",
                        ALL_ACTIONS));
        assertThat(replay.status()).isEqualTo(HarnessRunStatus.COMPLETED);
        assertThat(service.readEvents(owner, session.sessionId(), replay.runId(), 0, 100))
                .filteredOn(event -> event.type().equals("run.created")).hasSize(1);
        assertThat(gateway.calls.get()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ C1c

    @Test
    void c1cConcurrentCancelDoesNotProduceAContradictoryRunStartedEvent() {
        HarnessSessionState session = session(store);
        HarnessRunState queued = queuedRun(store, session);
        long now = System.currentTimeMillis();
        HarnessRunState cancelled = store.saveRun(owner,
                queued.requestCancellation(now).transition(HarnessRunStatus.CANCELLED, null, now),
                queued.revision());
        StaleFirstFindStore stale = new StaleFirstFindStore(store, queued);
        HarnessEventHub hub = new HarnessEventHub(stale, Runnable::run);
        CountingGateway gateway = new CountingGateway();
        CodingRunProcessor processor = new CodingRunProcessor(stale, hub, gateway, tools, mapper);

        processor.process(owner, session.sessionId(), cancelled.runId());

        assertThat(gateway.calls.get()).isZero();
        assertThat(stale.readEvents(owner, session.sessionId(), cancelled.runId(), 0, 100))
                .filteredOn(event -> event.type().equals("run.started")).isEmpty();
    }

    // ------------------------------------------------------------------ C1d

    @Test
    void c1dDuplicateDispatchRunsOnlyOneModelTurn() throws Exception {
        HarnessSessionState session = session(store);
        HarnessRunState queued = queuedRun(store, session);
        HarnessEventHub hub = new HarnessEventHub(store, Runnable::run);
        BlockingGateway gateway = new BlockingGateway();
        CodingRunProcessor processor = new CodingRunProcessor(store, hub, gateway, tools, mapper);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CodingRunExecutor executor = new CodingRunExecutor(pool, processor);
            executor.start(owner, session.sessionId(), queued.runId());
            executor.start(owner, session.sessionId(), queued.runId());
            assertThat(gateway.started.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(200);
            assertThat(gateway.calls.get()).as("single-flight").isEqualTo(1);
            gateway.release.countDown();
            awaitTerminal(session, queued.runId());
        } finally {
            pool.shutdownNow();
        }
    }

    private void awaitTerminal(HarnessSessionState session, String runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            HarnessRunState run = store.findRun(owner, session.sessionId(), runId).orElseThrow();
            if (run.status().isTerminal()) {
                return;
            }
            Thread.sleep(50);
        }
    }

    // ------------------------------------------------------------------ C1e

    @Test
    void c1eCancelOptimisticConflictMapsTo409() {
        FailingSaveStore failing = new FailingSaveStore(store);
        HarnessEventHub hub = new HarnessEventHub(failing, Runnable::run);
        CodingHarnessApplicationService service = service(failing, hub, Runnable::run,
                request -> ModelTurn.content("done"));
        HarnessSessionState session = session(failing);
        HarnessRunState queued = queuedRun(failing, session);
        failing.failSaves.set(1);

        assertThatThrownBy(() -> service.cancel(owner, session.sessionId(), queued.runId()))
                .isInstanceOf(CodingConflictException.class);
    }

    @Test
    void c1eResumeOptimisticConflictMapsTo409() {
        FailingSaveStore failing = new FailingSaveStore(store);
        HarnessEventHub hub = new HarnessEventHub(failing, Runnable::run);
        CodingHarnessApplicationService service = service(failing, hub, Runnable::run,
                request -> ModelTurn.content("done"));
        HarnessSessionState session = session(failing);
        HarnessRunState queued = queuedRun(failing, session);
        HarnessRunState suspended = failing.saveRun(owner,
                queued.transition(HarnessRunStatus.SUSPENDED, null, System.currentTimeMillis()),
                queued.revision());
        failing.failSaves.set(1);

        assertThatThrownBy(() -> service.resume(owner, session.sessionId(), suspended.runId()))
                .isInstanceOf(CodingConflictException.class);
    }

    // ------------------------------------------------------------------ helpers

    static final class SwitchableExecutor implements Executor {
        volatile Executor delegate = command -> {
            throw new java.util.concurrent.RejectedExecutionException("executor not ready");
        };

        @Override
        public void execute(Runnable command) {
            delegate.execute(command);
        }
    }

    static final class CreatedEventFailingHub extends HarnessEventHub {
        private final AtomicInteger remaining;

        CreatedEventFailingHub(HarnessStore store, Executor executor, int failures) {
            super(store, executor);
            this.remaining = new AtomicInteger(failures);
        }

        @Override
        public HarnessEvent publishIdempotent(HarnessOwner owner, HarnessEvent draft) {
            if ("run.created".equals(draft.type()) && remaining.getAndDecrement() > 0) {
                throw new IllegalStateException("simulated run.created publish failure");
            }
            return super.publishIdempotent(owner, draft);
        }
    }

    static final class CountingGateway implements CodingModelGateway {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ModelTurn complete(CodingModelRequest request) {
            calls.incrementAndGet();
            return ModelTurn.content("done");
        }
    }

    static final class BlockingGateway implements CodingModelGateway {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public ModelTurn complete(CodingModelRequest request) {
            calls.incrementAndGet();
            started.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new CodingModelUnavailableException("blocked gateway timeout");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CodingModelUnavailableException("interrupted");
            }
            return ModelTurn.content("done");
        }
    }

    /** Delegating store; the first findRun returns a stale snapshot, later calls delegate. */
    static class StaleFirstFindStore extends DelegatingStore {
        private final HarnessRunState stale;
        private final AtomicInteger calls = new AtomicInteger();

        StaleFirstFindStore(HarnessStore delegate, HarnessRunState stale) {
            super(delegate);
            this.stale = stale;
        }

        @Override
        public Optional<HarnessRunState> findRun(HarnessOwner owner, String sessionId,
                                                 String runId) {
            if (calls.getAndIncrement() == 0) {
                return Optional.of(stale);
            }
            return super.findRun(owner, sessionId, runId);
        }
    }

    /** Delegating store that fails saveRun with an optimistic-lock conflict N times. */
    static class FailingSaveStore extends DelegatingStore {
        final AtomicInteger failSaves = new AtomicInteger();

        FailingSaveStore(HarnessStore delegate) {
            super(delegate);
        }

        @Override
        public HarnessRunState saveRun(HarnessOwner owner, HarnessRunState run,
                                       long expectedRevision) {
            if (failSaves.getAndDecrement() > 0) {
                throw new HarnessOptimisticLockException("run " + run.runId(), expectedRevision,
                        expectedRevision + 1);
            }
            return super.saveRun(owner, run, expectedRevision);
        }
    }

    static class DelegatingStore implements HarnessStore {
        protected final HarnessStore delegate;

        DelegatingStore(HarnessStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public HarnessSessionState createSession(HarnessSessionState session) {
            return delegate.createSession(session);
        }

        @Override
        public Optional<HarnessSessionState> findSession(HarnessOwner owner, String sessionId) {
            return delegate.findSession(owner, sessionId);
        }

        @Override
        public List<HarnessSessionState> listSessions(HarnessOwner owner) {
            return delegate.listSessions(owner);
        }

        @Override
        public HarnessSessionState setSessionPinned(HarnessOwner owner, String sessionId,
                                                    boolean pinned) {
            return delegate.setSessionPinned(owner, sessionId, pinned);
        }

        @Override
        public HarnessSessionState setSessionDeleted(HarnessOwner owner, String sessionId,
                                                     boolean deleted) {
            return delegate.setSessionDeleted(owner, sessionId, deleted);
        }

        @Override
        public HarnessSessionState saveSession(HarnessOwner owner, HarnessSessionState session,
                                               long expectedRevision) {
            return delegate.saveSession(owner, session, expectedRevision);
        }

        @Override
        public HarnessRunState createRun(HarnessOwner owner, HarnessRunState run) {
            return delegate.createRun(owner, run);
        }

        @Override
        public Optional<HarnessRunState> findRun(HarnessOwner owner, String sessionId,
                                                 String runId) {
            return delegate.findRun(owner, sessionId, runId);
        }

        @Override
        public List<HarnessRunState> listRuns(HarnessOwner owner, String sessionId) {
            return delegate.listRuns(owner, sessionId);
        }

        @Override
        public HarnessRunState saveRun(HarnessOwner owner, HarnessRunState run,
                                       long expectedRevision) {
            return delegate.saveRun(owner, run, expectedRevision);
        }

        @Override
        public HarnessMessage appendMessage(HarnessOwner owner, HarnessMessage message) {
            return delegate.appendMessage(owner, message);
        }

        @Override
        public List<HarnessMessage> readMessages(HarnessOwner owner, String sessionId,
                                                 long afterSequence, int limit) {
            return delegate.readMessages(owner, sessionId, afterSequence, limit);
        }

        @Override
        public HarnessEvent appendEvent(HarnessOwner owner, HarnessEvent event) {
            return delegate.appendEvent(owner, event);
        }

        @Override
        public List<HarnessEvent> readEvents(HarnessOwner owner, String sessionId, String runId,
                                             long afterSequence, int limit) {
            return delegate.readEvents(owner, sessionId, runId, afterSequence, limit);
        }
    }
}
