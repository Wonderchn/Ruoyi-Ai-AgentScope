package com.nageoffer.ai.ragent.coding.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.coding.approval.ApprovalDecision;
import com.nageoffer.ai.ragent.coding.approval.ToolApproval;
import com.nageoffer.ai.ragent.coding.event.HarnessEventHub;
import com.nageoffer.ai.ragent.coding.model.HarnessApprovalPolicy;
import com.nageoffer.ai.ragent.coding.model.HarnessEvent;
import com.nageoffer.ai.ragent.coding.model.HarnessInputKind;
import com.nageoffer.ai.ragent.coding.model.HarnessMessage;
import com.nageoffer.ai.ragent.coding.model.HarnessMessageRole;
import com.nageoffer.ai.ragent.coding.model.HarnessModelRoute;
import com.nageoffer.ai.ragent.coding.model.HarnessOwner;
import com.nageoffer.ai.ragent.coding.model.HarnessPermissionMode;
import com.nageoffer.ai.ragent.coding.model.HarnessRunState;
import com.nageoffer.ai.ragent.coding.model.HarnessRunStatus;
import com.nageoffer.ai.ragent.coding.model.HarnessSessionState;
import com.nageoffer.ai.ragent.coding.sandbox.DockerSandboxConfig;
import com.nageoffer.ai.ragent.coding.store.FileHarnessStore;
import com.nageoffer.ai.ragent.coding.store.HarnessStore;
import com.nageoffer.ai.ragent.coding.tool.CodingToolRuntime;
import com.nageoffer.ai.ragent.coding.workspace.CodingWorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 复核轮硬化判据：B2 并发 steering 不僵死；C1 先落盘后发事件/审批唯一；C2 订阅修复终态；
 * C4 restore 404；C5 执行期 scope；C6 多 run 长会话 transcript。
 */
@Tag("dev")
class CodingHarnessHardeningTest {

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
    void setUp() throws Exception {
        store = new FileHarnessStore(mapper, temp.resolve("state"));
        workspace = new CodingWorkspaceService(temp.resolve("ws"), null);
        tools = new CodingToolRuntime(workspace, DockerSandboxConfig.UNCONFIGURED, false);
    }

    private HarnessEventHub hub(Executor delivery, HarnessStore backing) {
        return new HarnessEventHub(backing, delivery);
    }

    private CodingHarnessApplicationService service(HarnessEventHub eventHub, Executor runExecutor,
                                                    CodingModelGateway gateway) {
        CodingRunProcessor processor = new CodingRunProcessor(store, eventHub, gateway, tools, mapper);
        return new CodingHarnessApplicationService(store, eventHub, workspace,
                new CodingRunExecutor(runExecutor, processor), tools);
    }

    private HarnessSessionState session(CodingHarnessApplicationService service,
                                        HarnessApprovalPolicy policy, String key) {
        return service.createSession(owner, new CodingHarnessApplicationService.CreateSessionCommand(
                null, "deepseek-flash", HarnessPermissionMode.WORKSPACE_WRITE, policy, "s", key));
    }

    private HarnessRunState run(CodingHarnessApplicationService service, String sessionId,
                                String requirement, String key, Set<String> actions) {
        return service.createRun(owner, sessionId,
                new CodingHarnessApplicationService.CreateRunCommand(requirement, null, key, actions));
    }

    // ------------------------------------------------------------------ B2

    @Test
    void concurrentSteeringDoesNotLeaveTheRunStuckInRunning() throws Exception {
        ExecutorService runExecutor = Executors.newSingleThreadExecutor();
        BlockingGateway gateway = new BlockingGateway();
        HarnessEventHub eventHub = hub(Runnable::run, store);
        CodingHarnessApplicationService service = service(eventHub, runExecutor, gateway);
        try {
            HarnessSessionState session = session(service, HarnessApprovalPolicy.NEVER, "s-b2");
            HarnessRunState queued = run(service, session.sessionId(), "do it", "r-b2", ALL_ACTIONS);
            assertThat(gateway.started.await(5, TimeUnit.SECONDS)).isTrue();

            HarnessRunState steered = service.queueInput(owner, session.sessionId(), queued.runId(),
                    new CodingHarnessApplicationService.QueueInputCommand(HarnessInputKind.STEER,
                            "change direction", "i-b2", ALL_ACTIONS));
            assertThat(steered.pendingInputs()).hasSize(1);

            gateway.release.countDown();
            HarnessRunState terminal = awaitTerminal(service, session.sessionId(), queued.runId());
            assertThat(terminal.status()).isNotEqualTo(HarnessRunStatus.RUNNING);
            assertThat(terminal.status()).isEqualTo(HarnessRunStatus.COMPLETED);
            assertThat(terminal.pendingInputs()).hasSize(1);
            assertThat(service.readMessages(owner, session.sessionId(), 0, 100))
                    .extracting(HarnessMessage::role).contains(HarnessMessageRole.CONTROL);
        } finally {
            runExecutor.shutdownNow();
        }
    }

    private HarnessRunState awaitTerminal(CodingHarnessApplicationService service, String sessionId,
                                          String runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            HarnessRunState run = service.getRun(owner, sessionId, runId);
            if (run.status().isTerminal()) {
                return run;
            }
            Thread.sleep(50);
        }
        return service.getRun(owner, sessionId, runId);
    }

    // ------------------------------------------------------------------ C1

    @Test
    void createRunRepairsItsEventAfterAPublishFailure() {
        FailingHub eventHub = new FailingHub(store, Runnable::run, 1);
        CodingHarnessApplicationService service = service(eventHub, command -> {
            throw new java.util.concurrent.RejectedExecutionException("test executor");
        }, request -> ModelTurn.content("done"));
        HarnessSessionState session = session(service, HarnessApprovalPolicy.NEVER, "s-c1-create");
        HarnessRunState first = run(service, session.sessionId(), "do it", "r-c1-create",
                ALL_ACTIONS);
        assertThat(first.status()).isEqualTo(com.nageoffer.ai.ragent.coding.model.HarnessRunStatus.QUEUED);
        List<HarnessEvent> firstEvents = service.readEvents(owner, session.sessionId(),
                first.runId(), 0, 100);
        assertThat(firstEvents).isEmpty();

        HarnessRunState replay = run(service, session.sessionId(), "do it", "r-c1-create",
                ALL_ACTIONS);
        List<HarnessEvent> repaired = service.readEvents(owner, session.sessionId(),
                replay.runId(), 0, 100);
        assertThat(repaired).filteredOn(event -> event.type().equals("run.created")).hasSize(1);
        assertThat(repaired.get(0).type()).isEqualTo("run.created");
        assertThat(repaired.get(0).timestamp()).isEqualTo(replay.createdAt());
    }

    @Test
    void concurrentApprovalResolutionsProduceExactlyOneDecisionEvent() throws Exception {
        HarnessEventHub eventHub = hub(Runnable::run, store);
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.enqueue(ModelTurn.toolCalls(List.of(new ModelToolCall("call-1", "write_file",
                Map.of("path", "a.txt", "content", "x")))));
        gateway.enqueue(ModelTurn.content("done"));
        CodingHarnessApplicationService service = service(eventHub, Runnable::run, gateway);
        HarnessSessionState session = session(service, HarnessApprovalPolicy.ON_REQUEST, "s-c1-ap");
        HarnessRunState waiting = run(service, session.sessionId(), "do it", "r-c1-ap", ALL_ACTIONS);
        ToolApproval pending = waiting.toolApprovals().values().iterator().next();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ConcurrentLinkedQueue<String> outcomes = new ConcurrentLinkedQueue<>();
        try {
            for (String decisionId : List.of("d-1", "d-2")) {
                pool.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        service.resolveToolApproval(owner, session.sessionId(), waiting.runId(),
                                pending.approvalId(), decisionId, ApprovalDecision.APPROVE, 0,
                                pending.argumentsSha256(), "ok");
                        outcomes.add("ok:" + decisionId);
                    } catch (com.nageoffer.ai.ragent.coding.app.CodingConflictException conflict) {
                        outcomes.add("conflict:" + decisionId);
                    } catch (Exception failure) {
                        outcomes.add("error:" + decisionId + ":" + failure.getClass().getSimpleName());
                    }
                });
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(outcomes).filteredOn(value -> value.startsWith("ok:")).hasSize(1);
        assertThat(outcomes).filteredOn(value -> value.startsWith("conflict:")).hasSize(1);

        List<HarnessEvent> events = service.readEvents(owner, session.sessionId(), waiting.runId(),
                0, 200);
        assertThat(events).filteredOn(event -> event.type().equals("approval.resolved")).hasSize(1);
    }

    @Test
    void duplicateApprovalDecisionReplayKeepsOneEvent() {
        HarnessEventHub eventHub = hub(Runnable::run, store);
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.enqueue(ModelTurn.toolCalls(List.of(new ModelToolCall("call-1", "write_file",
                Map.of("path", "a.txt", "content", "x")))));
        gateway.enqueue(ModelTurn.content("done"));
        CodingHarnessApplicationService service = service(eventHub, Runnable::run, gateway);
        HarnessSessionState session = session(service, HarnessApprovalPolicy.ON_REQUEST, "s-c1-replay");
        HarnessRunState waiting = run(service, session.sessionId(), "do it", "r-c1-replay",
                ALL_ACTIONS);
        ToolApproval pending = waiting.toolApprovals().values().iterator().next();

        service.resolveToolApproval(owner, session.sessionId(), waiting.runId(),
                pending.approvalId(), "d-1", ApprovalDecision.APPROVE, 0,
                pending.argumentsSha256(), null);
        service.resolveToolApproval(owner, session.sessionId(), waiting.runId(),
                pending.approvalId(), "d-1", ApprovalDecision.APPROVE, 0,
                pending.argumentsSha256(), null);
        assertThat(service.readEvents(owner, session.sessionId(), waiting.runId(), 0, 200))
                .filteredOn(event -> event.type().equals("approval.resolved")).hasSize(1);
    }

    // ------------------------------------------------------------------ C2

    @Test
    void subscribingRepairsAMissingTerminalEvent() {
        HarnessEventHub eventHub = hub(Runnable::run, store);
        HarnessSessionState session = store.createSession(HarnessSessionState.createWithId(
                "s-c2", owner, temp.resolve("ws").toAbsolutePath().toString(), "deepseek-flash",
                HarnessPermissionMode.READ_ONLY, HarnessApprovalPolicy.ON_REQUEST, "s",
                System.currentTimeMillis()));
        HarnessRunState running = HarnessRunState.createWithId("r-c2", session, "do it", null,
                new HarnessModelRoute(1, session.model(), session.model()),
                System.currentTimeMillis()).transition(HarnessRunStatus.RUNNING, null,
                System.currentTimeMillis());
        running = store.createRun(owner, running);
        HarnessRunState completed = store.saveRun(owner,
                running.transition(HarnessRunStatus.COMPLETED, null,
                        System.currentTimeMillis()), running.revision());

        List<HarnessEvent> received = new ArrayList<>();
        eventHub.subscribe(owner, session.sessionId(), completed.runId(), 0,
                received::add, error -> {
                });
        assertThat(received).extracting(HarnessEvent::type).contains("run.completed");
        assertThat(store.readEvents(owner, session.sessionId(), completed.runId(), 0, 100))
                .extracting(HarnessEvent::type).contains("run.completed");
    }

    // ------------------------------------------------------------------ C4

    @Test
    void restoringAnUnknownOrForeignSessionIsNotFound() {
        HarnessEventHub eventHub = hub(Runnable::run, store);
        CodingHarnessApplicationService service = service(eventHub, Runnable::run,
                request -> ModelTurn.content("ok"));
        HarnessSessionState session = session(service, HarnessApprovalPolicy.NEVER, "s-c4");

        assertThatThrownBy(() -> service.restoreSession(owner, "s_missing"))
                .isInstanceOf(CodingNotFoundException.class);
        assertThatThrownBy(() -> service.restoreSession(new HarnessOwner("T2", 43L),
                session.sessionId())).isInstanceOf(CodingNotFoundException.class);
    }

    // ------------------------------------------------------------------ C5

    @Test
    void toolScopeIsCheckedBeforeApprovalAndCannotBeBypassedByNever() {
        HarnessEventHub eventHub = hub(Runnable::run, store);
        ScriptedGateway gateway = new ScriptedGateway();
        String marker = "scope-marker-" + System.nanoTime();
        gateway.enqueue(ModelTurn.toolCalls(List.of(new ModelToolCall("call-1", "execute_process",
                Map.of("executable", "sh", "argv", List.of("-c", "touch " + marker))))));
        gateway.enqueue(ModelTurn.content("done"));
        CodingHarnessApplicationService service = service(eventHub, Runnable::run, gateway);
        HarnessSessionState session = session(service, HarnessApprovalPolicy.NEVER, "s-c5");
        // 只授予 coding.write：没有 coding.command，即使客户端选择 NEVER 也不得执行。
        HarnessRunState run = run(service, session.sessionId(), "run a command", "r-c5",
                Set.of("coding.write"));

        assertThat(run.status()).isEqualTo(HarnessRunStatus.COMPLETED);
        HarnessMessage tool = service.readMessages(owner, session.sessionId(), 0, 100).stream()
                .filter(message -> message.role() == HarnessMessageRole.TOOL)
                .findFirst().orElseThrow();
        assertThat(tool.toolError()).isTrue();
        assertThat(tool.metadata().get("code")).isEqualTo("FORBIDDEN");
        assertThat(run.toolCallCount()).isEqualTo(1);
        assertThat(java.nio.file.Files.exists(temp.resolve(marker))).isFalse();
    }

    @Test
    void authorizedCommandStillFailsClosedWhenTheSandboxIsUnconfigured() {
        HarnessEventHub eventHub = hub(Runnable::run, store);
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.enqueue(ModelTurn.toolCalls(List.of(new ModelToolCall("call-1", "execute_process",
                Map.of("executable", "sh", "argv", List.of("-c", "true"))))));
        gateway.enqueue(ModelTurn.content("done"));
        CodingHarnessApplicationService service = service(eventHub, Runnable::run, gateway);
        HarnessSessionState session = session(service, HarnessApprovalPolicy.NEVER, "s-c5b");
        HarnessRunState run = run(service, session.sessionId(), "run a command", "r-c5b",
                ALL_ACTIONS);

        HarnessMessage tool = service.readMessages(owner, session.sessionId(), 0, 100).stream()
                .filter(message -> message.role() == HarnessMessageRole.TOOL)
                .findFirst().orElseThrow();
        assertThat(tool.metadata().get("code")).isEqualTo("SANDBOX_NOT_CONFIGURED");
        assertThat(run.status()).isEqualTo(HarnessRunStatus.COMPLETED);
    }

    // ------------------------------------------------------------------ C6

    @Test
    void transcriptForALaterRunIsNotTruncatedByEarlierRunMessages() {
        HarnessEventHub eventHub = hub(Runnable::run, store);
        CapturingGateway gateway = new CapturingGateway();
        gateway.enqueue(ModelTurn.content("first"));
        gateway.enqueue(ModelTurn.content("second"));
        CodingHarnessApplicationService service = service(eventHub, Runnable::run, gateway);
        HarnessSessionState session = session(service, HarnessApprovalPolicy.NEVER, "s-c6");
        HarnessRunState first = run(service, session.sessionId(), "first requirement", "r-c6-1",
                ALL_ACTIONS);
        for (int index = 0; index < 200; index++) {
            store.appendMessage(owner, HarnessMessage.draft(session.sessionId(), first.runId(),
                    HarnessMessageRole.USER, "noise-" + index, null, List.of(), null, null,
                    false, null, Map.of(), System.currentTimeMillis()));
        }
        run(service, session.sessionId(), "second requirement", "r-c6-2", ALL_ACTIONS);

        CodingModelRequest secondRequest = gateway.requests.stream()
                .filter(request -> request.run().originalRequirement().equals("second requirement"))
                .findFirst().orElseThrow();
        assertThat(secondRequest.transcript()).isNotEmpty();
        assertThat(secondRequest.transcript()).extracting(HarnessMessage::content)
                .contains("second requirement");
        assertThat(secondRequest.transcript()).extracting(HarnessMessage::content)
                .noneMatch(content -> content != null && content.startsWith("noise-"));
    }

    // ------------------------------------------------------------------ helpers

    static final class FailingHub extends HarnessEventHub {
        private final AtomicInteger failures;

        FailingHub(HarnessStore store, Executor executor, int failures) {
            super(store, executor);
            this.failures = new AtomicInteger(failures);
        }

        @Override
        public HarnessEvent publishIdempotent(HarnessOwner owner, HarnessEvent draft) {
            if (failures.getAndDecrement() > 0) {
                throw new IllegalStateException("simulated event publish failure");
            }
            return super.publishIdempotent(owner, draft);
        }
    }

    static final class BlockingGateway implements CodingModelGateway {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public ModelTurn complete(CodingModelRequest request) {
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

    static final class ScriptedGateway implements CodingModelGateway {
        private final Deque<ModelTurn> turns = new ArrayDeque<>();

        void enqueue(ModelTurn turn) {
            turns.add(turn);
        }

        @Override
        public ModelTurn complete(CodingModelRequest request) {
            ModelTurn next = turns.poll();
            return next == null ? ModelTurn.content("script exhausted") : next;
        }
    }

    static final class CapturingGateway implements CodingModelGateway {
        final List<CodingModelRequest> requests = new ArrayList<>();
        private final Deque<ModelTurn> turns = new ArrayDeque<>();

        void enqueue(ModelTurn turn) {
            turns.add(turn);
        }

        @Override
        public ModelTurn complete(CodingModelRequest request) {
            requests.add(request);
            ModelTurn next = turns.poll();
            return next == null ? ModelTurn.content("script exhausted") : next;
        }
    }
}
