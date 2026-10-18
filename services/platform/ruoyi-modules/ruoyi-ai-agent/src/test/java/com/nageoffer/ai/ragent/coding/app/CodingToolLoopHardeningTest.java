package com.nageoffer.ai.ragent.coding.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.agent.config.AgentModelFactory;
import com.nageoffer.ai.ragent.coding.event.HarnessEventHub;
import com.nageoffer.ai.ragent.coding.model.AgentModelFactoryCodingModelGateway;
import com.nageoffer.ai.ragent.coding.model.HarnessApprovalPolicy;
import com.nageoffer.ai.ragent.coding.model.HarnessMessage;
import com.nageoffer.ai.ragent.coding.model.HarnessMessageRole;
import com.nageoffer.ai.ragent.coding.model.HarnessModelRoute;
import com.nageoffer.ai.ragent.coding.model.HarnessOwner;
import com.nageoffer.ai.ragent.coding.model.HarnessPermissionMode;
import com.nageoffer.ai.ragent.coding.model.HarnessRunState;
import com.nageoffer.ai.ragent.coding.model.HarnessSessionState;
import com.nageoffer.ai.ragent.coding.model.HarnessToolCall;
import com.nageoffer.ai.ragent.coding.plan.CodingPlanState;
import com.nageoffer.ai.ragent.coding.plan.PlanStepState;
import com.nageoffer.ai.ragent.coding.sandbox.DockerSandboxConfig;
import com.nageoffer.ai.ragent.coding.store.FileHarnessStore;
import com.nageoffer.ai.ragent.coding.tool.CodingToolRuntime;
import com.nageoffer.ai.ragent.coding.workspace.CodingWorkspaceService;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C1–C3：真实工具循环的配对消息、工具输出回传与跨回合生成 id。
 *
 * <p>用真实 {@link AgentModelFactoryCodingModelGateway}（fake Model，零网络）驱动处理器，
 * 覆盖 assistant(tool_calls)→tool(result) 配对、read_file 内容进入 TOOL content、
 * 同 run 两个回合的相同空白 id 调用都执行。
 */
@Tag("dev")
class CodingToolLoopHardeningTest {

    private static final Set<String> ALL_ACTIONS = Set.of(
            "coding.use", "coding.write", "coding.approve", "coding.command");
    private static final long NOW = 1_000_000L;

    @TempDir
    Path temp;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HarnessOwner owner = new HarnessOwner("T1", 42L);
    private FileHarnessStore store;
    private CodingWorkspaceService workspace;
    private CodingToolRuntime tools;
    private HarnessSessionState session;
    private Path workspaceRoot;

    @BeforeEach
    void setUp() {
        store = new FileHarnessStore(mapper, temp.resolve("state"));
        workspace = new CodingWorkspaceService(temp.resolve("ws"), null);
        tools = new CodingToolRuntime(workspace, DockerSandboxConfig.UNCONFIGURED, false);
        Path ownerRoot = workspace.resolveHarnessRoot(owner, null);
        session = store.createSession(HarnessSessionState.createWithId("s1", owner,
                ownerRoot.toString(), "deepseek-flash",
                HarnessPermissionMode.WORKSPACE_WRITE, HarnessApprovalPolicy.NEVER, "s", NOW));
        workspaceRoot = Path.of(session.workspace());
        workspace.write(workspaceRoot, "a.txt", "hello-from-tool");
    }

    private HarnessRunState buildRunWithApprovedPlan(int iteration) {
        long now = NOW;
        CodingPlanState draft = CodingPlanState.create("do it", now)
                .replacePlan("1. read the file", List.of(new PlanStepState("s1", "read", List.of())), now);
        CodingPlanState approved = draft.approve(draft.taskId(), draft.revision(),
                draft.canonicalHash(), "k", now);
        HarnessRunState run = HarnessRunState.createWithId("r1", session, "do it", null,
                new HarnessModelRoute(1, session.model(), session.model()), ALL_ACTIONS, now)
                .withCounters(iteration, 0, now)
                .withPlan(approved, now);
        return store.createRun(owner, run);
    }

    private void appendRequirement() {
        store.appendMessage(owner, HarnessMessage.draft(session.sessionId(), "r1",
                HarnessMessageRole.USER, "do it", null, List.of(), null, null, false, null,
                Map.of(), NOW));
    }

    private CodingRunProcessor processor(QueueModel model) {
        HarnessEventHub hub = new HarnessEventHub(store, Runnable::run);
        AgentModelFactory factory = (tenantId, modelId) -> model;
        AgentModelFactoryCodingModelGateway gateway =
                new AgentModelFactoryCodingModelGateway(factory, mapper);
        return new CodingRunProcessor(store, hub, gateway, tools, mapper);
    }

    @Test
    void c1AndC2AssistantToolCallIsPairedAndFileContentReachesTheToolMessage() {
        HarnessRunState run = buildRunWithApprovedPlan(1);
        appendRequirement();
        QueueModel model = new QueueModel(List.of(
                toolUse("", "read_file", Map.of("path", "a.txt")),
                text("done")));

        processor(model).process(owner, session.sessionId(), run.runId());

        List<HarnessMessage> messages = store.readMessages(owner, session.sessionId(), 0, 100);
        HarnessMessage assistantToolCall = messages.stream()
                .filter(message -> message.role() == HarnessMessageRole.ASSISTANT
                        && !message.toolCalls().isEmpty())
                .findFirst().orElseThrow();
        HarnessMessage toolResult = messages.stream()
                .filter(message -> message.role() == HarnessMessageRole.TOOL
                        && "read_file".equals(message.toolName()))
                .findFirst().orElseThrow();

        assertThat(assistantToolCall.sequence()).isLessThan(toolResult.sequence());
        assertThat(assistantToolCall.toolCalls()).extracting(HarnessToolCall::toolName)
                .containsExactly("read_file");
        assertThat(assistantToolCall.toolCalls().get(0).toolCallId())
                .isEqualTo(toolResult.toolCallId());
        assertThat(toolResult.content())
                .as("C2: ToolExecutionResult.data must be rendered into TOOL content")
                .contains("hello-from-tool", "a.txt", "size")
                .isNotEqualTo("ok");
    }

    @Test
    void c3SameBlankIdToolCallExecutesAgainOnALaterTurn() {
        HarnessRunState run = buildRunWithApprovedPlan(1);
        appendRequirement();
        QueueModel model = new QueueModel(List.of(
                toolUse("", "read_file", Map.of("path", "a.txt")),
                toolUse("", "read_file", Map.of("path", "a.txt")),
                text("done")));

        processor(model).process(owner, session.sessionId(), run.runId());

        List<HarnessMessage> toolResults = store.readMessages(owner, session.sessionId(), 0, 100)
                .stream()
                .filter(message -> message.role() == HarnessMessageRole.TOOL
                        && "read_file".equals(message.toolName()))
                .toList();
        assertThat(toolResults).hasSize(2);
        assertThat(toolResults).extracting(HarnessMessage::toolCallId).doesNotHaveDuplicates();
        assertThat(toolResults).allSatisfy(message ->
                assertThat(message.content()).contains("hello-from-tool"));
    }

    private ChatResponse toolUse(String id, String name, Map<String, Object> input) {
        return ChatResponse.builder().content(List.of(ToolUseBlock.builder()
                .id(id).name(name).input(input).build())).build();
    }

    private ChatResponse text(String text) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build())).build();
    }

    private static final class QueueModel implements Model {
        private final Deque<ChatResponse> responses = new ArrayDeque<>();
        private final List<List<Msg>> requests = new ArrayList<>();

        private QueueModel(List<ChatResponse> responses) {
            this.responses.addAll(responses);
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                         GenerateOptions options) {
            requests.add(new ArrayList<>(messages));
            ChatResponse next = responses.poll();
            return next == null
                    ? Flux.just(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("done").build())).build())
                    : Flux.just(next);
        }

        @Override
        public String getModelName() {
            return "deepseek-flash";
        }
    }
}
