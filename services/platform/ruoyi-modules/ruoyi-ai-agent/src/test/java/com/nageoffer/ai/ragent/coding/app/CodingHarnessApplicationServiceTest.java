package com.nageoffer.ai.ragent.coding.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.coding.approval.ApprovalDecision;
import com.nageoffer.ai.ragent.coding.approval.ApprovalState;
import com.nageoffer.ai.ragent.coding.approval.ToolApproval;
import com.nageoffer.ai.ragent.coding.event.HarnessEventHub;
import com.nageoffer.ai.ragent.coding.model.HarnessApprovalPolicy;
import com.nageoffer.ai.ragent.coding.model.HarnessBudget;
import com.nageoffer.ai.ragent.coding.model.HarnessEvent;
import com.nageoffer.ai.ragent.coding.model.HarnessInputKind;
import com.nageoffer.ai.ragent.coding.model.HarnessMessage;
import com.nageoffer.ai.ragent.coding.model.HarnessMessageRole;
import com.nageoffer.ai.ragent.coding.model.HarnessOwner;
import com.nageoffer.ai.ragent.coding.model.HarnessPermissionMode;
import com.nageoffer.ai.ragent.coding.model.HarnessRunState;
import com.nageoffer.ai.ragent.coding.model.HarnessRunStatus;
import com.nageoffer.ai.ragent.coding.model.HarnessSessionState;
import com.nageoffer.ai.ragent.coding.model.HarnessUsage;
import com.nageoffer.ai.ragent.coding.plan.CodingPlanState;
import com.nageoffer.ai.ragent.coding.plan.PlanMode;
import com.nageoffer.ai.ragent.coding.plan.PlanReviewState;
import com.nageoffer.ai.ragent.coding.plan.PlanStepState;
import com.nageoffer.ai.ragent.coding.sandbox.DockerSandboxConfig;
import com.nageoffer.ai.ragent.coding.store.FileHarnessStore;
import com.nageoffer.ai.ragent.coding.tool.CodingToolDescriptor;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F20-A op0–op3 应用面判据：会话/运行/消息/事件状态机、幂等、隔离、审批与计划控制面。
 * 使用脚本化模型网关，不发起任何真实提供方调用。
 */
@Tag("dev")
class CodingHarnessApplicationServiceTest {

    @TempDir
    Path temp;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HarnessOwner owner = new HarnessOwner("T1", 42L);
    private ScriptedGateway gateway;
    private CodingHarnessApplicationService service;

    @BeforeEach
    void setUp() throws Exception {
        FileHarnessStore store = new FileHarnessStore(mapper, temp.resolve("state"));
        CodingWorkspaceService workspace = new CodingWorkspaceService(
                temp.resolve("workspaces"), null);
        gateway = new ScriptedGateway();
        HarnessEventHub hub = new HarnessEventHub(store, Runnable::run);
        CodingToolRuntime tools = new CodingToolRuntime(workspace, DockerSandboxConfig.UNCONFIGURED, false);
        CodingRunProcessor processor = new CodingRunProcessor(store, hub, gateway, tools, mapper);
        CodingRunExecutor executor = new CodingRunExecutor(Runnable::run, processor);
        service = new CodingHarnessApplicationService(store, hub, workspace, executor, tools);
    }

    private HarnessSessionState session() {
        return service.createSession(owner, new CodingHarnessApplicationService.CreateSessionCommand(
                null, "deepseek-flash", HarnessPermissionMode.WORKSPACE_WRITE,
                HarnessApprovalPolicy.ON_REQUEST, "session", "s-key"));
    }

    private HarnessRunState run(HarnessSessionState session, String key) {
        return service.createRun(owner, session.sessionId(),
                new CodingHarnessApplicationService.CreateRunCommand("do the task", null, key));
    }

    @Test
    void sessionLifecycleIsIdempotentAndOwnerScoped() {
        HarnessSessionState created = session();
        HarnessSessionState replay = service.createSession(owner,
                new CodingHarnessApplicationService.CreateSessionCommand(
                        null, "deepseek-flash", HarnessPermissionMode.WORKSPACE_WRITE,
                        HarnessApprovalPolicy.ON_REQUEST, "session", "s-key"));
        assertThat(replay.sessionId()).isEqualTo(created.sessionId());

        assertThatThrownBy(() -> service.createSession(owner,
                new CodingHarnessApplicationService.CreateSessionCommand(
                        null, "deepseek-flash", HarnessPermissionMode.WORKSPACE_WRITE,
                        HarnessApprovalPolicy.ON_REQUEST, "different", "s-key")))
                .isInstanceOf(CodingConflictException.class);

        service.setSessionPinned(owner, created.sessionId(), true);
        assertThat(service.getSession(owner, created.sessionId()).pinnedAt()).isPositive();
        service.deleteSession(owner, created.sessionId());
        assertThatThrownBy(() -> service.getSession(owner, created.sessionId()))
                .isInstanceOf(CodingNotFoundException.class);
        assertThat(service.restoreSession(owner, created.sessionId()).deletedAt()).isZero();

        assertThatThrownBy(() -> service.getSession(new HarnessOwner("T2", 42L), created.sessionId()))
                .isInstanceOf(CodingNotFoundException.class);
    }

    @Test
    void completedRunCarriesInitialMessageAndDurableEvents() {
        gateway.enqueue(ModelTurn.content("all done"));
        HarnessSessionState session = session();
        HarnessRunState run = run(session, "r-key");

        assertThat(run.status()).isEqualTo(HarnessRunStatus.COMPLETED);
        List<HarnessMessage> messages = service.readMessages(owner, session.sessionId(), 0, 100);
        assertThat(messages).extracting(HarnessMessage::role)
                .containsExactly(HarnessMessageRole.USER, HarnessMessageRole.ASSISTANT);
        assertThat(messages.get(1).content()).isEqualTo("all done");

        List<HarnessEvent> events = service.readEvents(owner, session.sessionId(), run.runId(), 0, 100);
        assertThat(events).extracting(HarnessEvent::type)
                .containsSubsequence("run.created", "run.started", "assistant.completed", "run.completed");
        assertThat(events).extracting(HarnessEvent::sequence)
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, events.size())
                        .boxed().toList());
    }

    @Test
    void runCreationIsIdempotentAndConflictsOnDifferentRequirement() {
        gateway.enqueue(ModelTurn.content("done"));
        HarnessSessionState session = session();
        HarnessRunState first = run(session, "r-key");

        HarnessRunState replay = run(session, "r-key");
        assertThat(replay.runId()).isEqualTo(first.runId());

        assertThatThrownBy(() -> service.createRun(owner, session.sessionId(),
                new CodingHarnessApplicationService.CreateRunCommand("different", null, "r-key")))
                .isInstanceOf(CodingConflictException.class);
    }

    @Test
    void approvalMustBeResolvedBeforeExecutionAndIsSingleUse() {
        Map<String, Object> args = Map.of("path", "a.txt", "content", "hello");
        gateway.enqueue(ModelTurn.toolCalls(List.of(new ModelToolCall("call-1", "write_file", args))));
        gateway.enqueue(ModelTurn.content("written"));
        HarnessSessionState session = session();
        HarnessRunState waiting = run(session, "r-approval");

        assertThat(waiting.status()).isEqualTo(HarnessRunStatus.WAITING_FOR_APPROVAL);
        assertThat(waiting.toolApprovals()).hasSize(1);
        ToolApproval pending = waiting.toolApprovals().values().iterator().next();
        assertThat(pending.state()).isEqualTo(ApprovalState.PENDING);

        HarnessRunState resolved = service.resolveToolApproval(owner, session.sessionId(),
                waiting.runId(), pending.approvalId(), "d-1", ApprovalDecision.APPROVE, 0,
                pending.argumentsSha256(), "ok");
        assertThat(resolved.status()).isEqualTo(HarnessRunStatus.COMPLETED);
        ToolApproval consumed = resolved.toolApprovals().get(pending.approvalId());
        assertThat(consumed.state()).isEqualTo(ApprovalState.APPROVED);
        assertThat(consumed.consumed()).isTrue();

        List<HarnessMessage> messages = service.readMessages(owner, session.sessionId(), 0, 100);
        assertThat(messages).extracting(HarnessMessage::role)
                .contains(HarnessMessageRole.TOOL);
        assertThat(messages.stream().filter(m -> m.role() == HarnessMessageRole.TOOL)
                .findFirst().orElseThrow().toolError()).isFalse();

        HarnessRunState replay = service.resolveToolApproval(owner, session.sessionId(),
                waiting.runId(), pending.approvalId(), "d-1", ApprovalDecision.APPROVE, 0,
                pending.argumentsSha256(), "ok");
        assertThat(replay.runId()).isEqualTo(resolved.runId());
    }

    @Test
    void rejectedApprovalIsFedBackAsToolError() {
        gateway.enqueue(ModelTurn.toolCalls(List.of(new ModelToolCall("call-1", "write_file",
                Map.of("path", "a.txt", "content", "x")))));
        gateway.enqueue(ModelTurn.content("skipped"));
        HarnessSessionState session = session();
        HarnessRunState waiting = run(session, "r-deny");
        ToolApproval pending = waiting.toolApprovals().values().iterator().next();

        HarnessRunState denied = service.resolveToolApproval(owner, session.sessionId(),
                waiting.runId(), pending.approvalId(), "d-2", ApprovalDecision.DENY, 0,
                pending.argumentsSha256(), "no");
        assertThat(denied.status()).isEqualTo(HarnessRunStatus.COMPLETED);
        HarnessMessage tool = service.readMessages(owner, session.sessionId(), 0, 100).stream()
                .filter(m -> m.role() == HarnessMessageRole.TOOL).findFirst().orElseThrow();
        assertThat(tool.toolError()).isTrue();
        assertThat(tool.metadata().get("code")).isEqualTo("APPROVAL_DENIED");
    }

    @Test
    void planApproveRequiresMatchingRevisionAndHashThenUnblocksRun() {
        gateway.enqueue(ModelTurn.plan(new PlanDraft("## plan\n1. write",
                List.of(new PlanStepState("s1", "write", List.of())))));
        gateway.enqueue(ModelTurn.content("implemented"));
        HarnessSessionState session = session();
        HarnessRunState waiting = run(session, "r-plan");

        assertThat(waiting.status()).isEqualTo(HarnessRunStatus.WAITING_FOR_INPUT);
        CodingPlanState plan = waiting.plan();
        assertThat(plan.reviewState()).isEqualTo(PlanReviewState.AWAITING_APPROVAL);

        assertThatThrownBy(() -> service.approvePlan(owner, session.sessionId(), waiting.runId(),
                plan.taskId(), plan.revision() + 1, plan.canonicalHash(), "p-key"))
                .isInstanceOf(CodingConflictException.class);

        HarnessRunState approved = service.approvePlan(owner, session.sessionId(), waiting.runId(),
                plan.taskId(), plan.revision(), plan.canonicalHash(), "p-key");
        assertThat(approved.status()).isEqualTo(HarnessRunStatus.COMPLETED);
        assertThat(approved.plan().mode()).isEqualTo(PlanMode.BUILD);
        assertThat(service.readEvents(owner, session.sessionId(), waiting.runId(), 0, 200))
                .extracting(HarnessEvent::type).contains("plan.approved");
    }

    @Test
    void planRevisionIsDurableAndReschedules() {
        gateway.enqueue(ModelTurn.plan(new PlanDraft("## plan\n1. write",
                List.of(new PlanStepState("s1", "write", List.of())))));
        gateway.enqueue(ModelTurn.content("revised done"));
        HarnessSessionState session = session();
        HarnessRunState waiting = run(session, "r-revision");
        CodingPlanState plan = waiting.plan();

        HarnessRunState revised = service.requestPlanRevision(owner, session.sessionId(),
                waiting.runId(), plan.taskId(), plan.revision(), plan.canonicalHash(),
                "f-1", "add tests");
        assertThat(revised.status()).isEqualTo(HarnessRunStatus.COMPLETED);
        assertThat(revised.plan().feedbackHistory()).hasSize(1);
        assertThat(service.readEvents(owner, session.sessionId(), waiting.runId(), 0, 200))
                .extracting(HarnessEvent::type).contains("plan.revision.requested");
    }

    @Test
    void cancelIsDurableAndSteeringTerminalRunIsRejected() {
        gateway.enqueue(ModelTurn.plan(new PlanDraft("## plan\n1. wait",
                List.of(new PlanStepState("s1", "wait", List.of())))));
        HarnessSessionState session = session();
        HarnessRunState waiting = run(session, "r-cancel");

        HarnessRunState cancelled = service.cancel(owner, session.sessionId(), waiting.runId());
        assertThat(cancelled.status()).isEqualTo(HarnessRunStatus.CANCELLED);
        assertThat(service.readEvents(owner, session.sessionId(), waiting.runId(), 0, 100))
                .extracting(HarnessEvent::type).contains("run.cancelled");

        assertThatThrownBy(() -> service.queueInput(owner, session.sessionId(), waiting.runId(),
                new CodingHarnessApplicationService.QueueInputCommand(HarnessInputKind.STEER,
                        "steer", "i-1")))
                .isInstanceOf(CodingConflictException.class);
    }

    @Test
    void modelFailureIsAFencedTerminalStateWithoutProviderLeakage() {
        gateway.fail(new CodingModelUnavailableException("provider secret detail"));
        HarnessSessionState session = session();
        HarnessRunState run = run(session, "r-fail");

        assertThat(run.status()).isEqualTo(HarnessRunStatus.FAILED);
        assertThat(run.error()).isEqualTo("MODEL_NOT_CONFIGURED");
        assertThat(run.error()).doesNotContain("secret");
        assertThat(service.readEvents(owner, session.sessionId(), run.runId(), 0, 100))
                .extracting(HarnessEvent::type).contains("run.failed");
    }

    // ---------------------------------------------------------------- helpers

    static final class ScriptedGateway implements CodingModelGateway {
        private final Deque<Object> turns = new ArrayDeque<>();

        void enqueue(ModelTurn turn) {
            turns.add(turn);
        }

        void fail(RuntimeException failure) {
            turns.add(failure);
        }

        @Override
        public ModelTurn complete(CodingModelRequest request) {
            Object next = turns.poll();
            if (next == null) {
                return ModelTurn.content("script exhausted");
            }
            if (next instanceof RuntimeException failure) {
                throw failure;
            }
            return (ModelTurn) next;
        }
    }
}
