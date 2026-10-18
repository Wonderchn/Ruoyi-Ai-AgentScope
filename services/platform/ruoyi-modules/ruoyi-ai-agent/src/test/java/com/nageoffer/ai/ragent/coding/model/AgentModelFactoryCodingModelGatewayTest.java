package com.nageoffer.ai.ragent.coding.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.agent.config.AgentModelFactory;
import com.nageoffer.ai.ragent.coding.app.CodingModelRequest;
import com.nageoffer.ai.ragent.coding.app.CodingModelUnavailableException;
import com.nageoffer.ai.ragent.coding.app.ModelToolCall;
import com.nageoffer.ai.ragent.coding.app.ModelTurn;
import com.nageoffer.ai.ragent.coding.app.PlanDraft;
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
import com.nageoffer.ai.ragent.coding.tool.CodingToolDescriptor;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelException;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F20-A2 目标 A：真实模型适配器的离线判据（fake AgentModelFactory/Model，零网络）。
 * 覆盖 transcript/tool schema/tool call/thinking/usage 映射、计划 JSON 成功/失败、
 * 未知模型与 provider 失败的稳定码/无端点密钥泄漏。
 */
@Tag("dev")
class AgentModelFactoryCodingModelGatewayTest {

    private static final Set<String> ALL_ACTIONS = Set.of(
            "coding.use", "coding.write", "coding.approve", "coding.command");
    private static final long NOW = 1_000_000L;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HarnessOwner owner = new HarnessOwner("T1", 42L);

    private HarnessSessionState session() {
        return HarnessSessionState.createWithId("s1", owner, "/tmp/ws", "deepseek-flash",
                HarnessPermissionMode.WORKSPACE_WRITE, HarnessApprovalPolicy.ON_REQUEST, "t", NOW);
    }

    private HarnessRunState run(HarnessSessionState session, int iteration) {
        HarnessRunState run = HarnessRunState.createWithId("r1", session, "do it", null,
                new HarnessModelRoute(1, session.model(), session.model()), ALL_ACTIONS, NOW);
        return iteration == 0 ? run : run.withCounters(iteration, 0, NOW);
    }

    private HarnessRunState buildRun(HarnessSessionState session) {
        long now = NOW;
        CodingPlanState draft = CodingPlanState.create("do it", now)
                .replacePlan("1. one", List.of(new PlanStepState("s1", "one", List.of())), now);
        CodingPlanState approved = draft.approve(draft.taskId(), draft.revision(),
                draft.canonicalHash(), "k", now);
        return HarnessRunState.createWithId("r1", session, "do it", null,
                new HarnessModelRoute(1, session.model(), session.model()), ALL_ACTIONS, now)
                .withCounters(1, 0, now)
                .withPlan(approved, now);
    }

    private List<CodingToolDescriptor> tools() {
        return List.of(
                new CodingToolDescriptor("read_file", "READ", false, "Read a workspace file",
                        Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string")))),
                new CodingToolDescriptor("write_file", "WRITE", true, "Write a workspace file",
                        Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string"),
                                "content", Map.of("type", "string")))));
    }

    private List<HarnessMessage> transcript() {
        return List.of(
                HarnessMessage.draft("s1", "r1", HarnessMessageRole.USER, "hello", null,
                        List.of(), null, null, false, null, Map.of(), NOW),
                HarnessMessage.draft("s1", "r1", HarnessMessageRole.ASSISTANT, "calling", null,
                        List.of(new HarnessToolCall("call-1", "read_file", Map.of("path", "a.txt"))),
                        null, null, false, null, Map.of(), NOW + 1),
                HarnessMessage.draft("s1", "r1", HarnessMessageRole.TOOL, "file content", null,
                        List.of(), "call-1", "read_file", false, null, Map.of(), NOW + 2));
    }

    @Test
    void mapsTranscriptToolsToolCallsThinkingAndUsage() {
        HarnessSessionState session = session();
        HarnessRunState run = buildRun(session);
        CapturingModel model = new CapturingModel(Flux.just(ChatResponse.builder()
                .content(List.of(
                        TextBlock.builder().text("done").build(),
                        ThinkingBlock.builder().thinking("because").build(),
                        ToolUseBlock.builder().id("call-2").name("read_file")
                                .input(Map.of("path", "b.txt")).build()))
                .usage(ChatUsage.builder().inputTokens(10).outputTokens(5).time(0.2).build())
                .build()));
        AgentModelFactoryCodingModelGateway gateway = new AgentModelFactoryCodingModelGateway(
                (tenantId, modelId) -> model, mapper);

        ModelTurn turn = gateway.complete(new CodingModelRequest(owner, session, run,
                transcript(), tools()));

        assertThat(model.capturedMessages).hasSize(5);
        assertThat(model.capturedMessages.get(0).getRole()).isEqualTo(MsgRole.SYSTEM);
        assertThat(model.capturedMessages.get(1).getRole()).isEqualTo(MsgRole.SYSTEM);
        assertThat(model.capturedMessages.get(1).getTextContent())
                .contains("Approved execution plan", "1. one", "s1: one")
                .doesNotContain("T1", "42", "/tmp/ws");
        assertThat(model.capturedMessages.get(2).getRole()).isEqualTo(MsgRole.USER);
        assertThat(model.capturedMessages.get(2).getTextContent()).isEqualTo("hello");
        assertThat(model.capturedMessages.get(3).getRole()).isEqualTo(MsgRole.ASSISTANT);
        assertThat(model.capturedMessages.get(3).getContentBlocks(ToolUseBlock.class))
                .anySatisfy(block -> {
                    assertThat(block.getId()).isEqualTo("call-1");
                    assertThat(block.getName()).isEqualTo("read_file");
                    assertThat(block.getInput()).containsEntry("path", "a.txt");
                });
        assertThat(model.capturedMessages.get(4).getRole()).isEqualTo(MsgRole.TOOL);
        assertThat(model.capturedMessages.get(4).getContentBlocks(ToolResultBlock.class))
                .anySatisfy(block -> {
                    assertThat(block.getId()).isEqualTo("call-1");
                    assertThat(block.getName()).isEqualTo("read_file");
                });

        assertThat(model.capturedTools).hasSize(2);
        assertThat(model.capturedTools.get(0).getName()).isEqualTo("read_file");
        assertThat(model.capturedTools.get(0).getDescription()).isEqualTo("Read a workspace file");
        assertThat(model.capturedTools.get(0).getParameters())
                .isEqualTo(Map.of("type", "object",
                        "properties", Map.of("path", Map.of("type", "string"))));
        assertThat(model.capturedTools.toString()).doesNotContain("/tmp/ws");

        assertThat(turn.content()).isEqualTo("done");
        assertThat(turn.thinking()).isEqualTo("because");
        assertThat(turn.toolCalls()).hasSize(1);
        assertThat(turn.toolCalls().get(0).toolName()).isEqualTo("read_file");
        assertThat(turn.toolCalls().get(0).arguments()).containsEntry("path", "b.txt");
        assertThat(turn.usage().inputTokens()).isEqualTo(10);
        assertThat(turn.usage().outputTokens()).isEqualTo(5);
        assertThat(turn.usage().totalTokens()).isEqualTo(15);
    }

    @Test
    void firstPlanTurnRequiresJsonAndBuildsPlanDraft() {
        HarnessSessionState session = session();
        HarnessRunState run = run(session, 0);
        String json = "{\"task\":\"refactor\",\"steps\":[{\"id\":\"s1\",\"title\":\"edit\","
                + "\"dependencies\":[]}],\"risks\":[\"regression\"]}";
        CapturingModel model = new CapturingModel(Flux.just(ChatResponse.builder()
                .content(List.of(TextBlock.builder().text("```json\n" + json + "\n```").build()))
                .build()));
        AgentModelFactoryCodingModelGateway gateway = new AgentModelFactoryCodingModelGateway(
                (tenantId, modelId) -> model, mapper);

        ModelTurn turn = gateway.complete(new CodingModelRequest(owner, session, run,
                transcript(), tools()));

        assertThat(model.capturedTools).as("plan turn must not advertise tools").isEmpty();
        assertThat(turn.planDraft()).isNotNull();
        PlanDraft plan = turn.planDraft();
        assertThat(plan.steps()).extracting(PlanStepState::stepId).containsExactly("s1");
        assertThat(plan.steps().get(0).title()).isEqualTo("edit");
        assertThat(plan.markdown()).contains("refactor", "edit", "regression");
    }

    @Test
    void malformedPlanJsonFailsWithoutFakingAPlan() {
        HarnessSessionState session = session();
        HarnessRunState run = run(session, 0);
        CapturingModel model = new CapturingModel(Flux.just(ChatResponse.builder()
                .content(List.of(TextBlock.builder().text("I will refactor the code.").build()))
                .build()));
        AgentModelFactoryCodingModelGateway gateway = new AgentModelFactoryCodingModelGateway(
                (tenantId, modelId) -> model, mapper);

        assertThatThrownBy(() -> gateway.complete(new CodingModelRequest(owner, session, run,
                transcript(), tools())))
                .isInstanceOf(CodingModelUnavailableException.class)
                .satisfies(error -> assertThat(
                        ((CodingModelUnavailableException) error).code())
                        .isEqualTo("PLAN_JSON_REQUIRED"));
    }

    @Test
    void unknownModelIsFailClosedAndLeakFree() {
        AgentModelFactoryCodingModelGateway gateway = new AgentModelFactoryCodingModelGateway(
                (tenantId, modelId) -> {
                    throw new AgentModelFactory.AgentModelUnavailableException(
                            "endpoint https://secret.example key sk-secret");
                }, mapper);
        HarnessSessionState session = session();
        HarnessRunState run = run(session, 1);

        assertThatThrownBy(() -> gateway.complete(new CodingModelRequest(owner, session, run,
                transcript(), tools())))
                .isInstanceOf(CodingModelUnavailableException.class)
                .satisfies(error -> {
                    CodingModelUnavailableException unavailable =
                            (CodingModelUnavailableException) error;
                    assertThat(unavailable.code()).isEqualTo("MODEL_NOT_CONFIGURED");
                    assertThat(unavailable.getMessage())
                            .doesNotContain("https", "secret", "sk-", "endpoint");
                });
    }

    @Test
    void providerFailureIsStableAndLeakFree() {
        CapturingModel model = new CapturingModel(Flux.error(
                new ModelException("endpoint https://secret.example key sk-secret")));
        AgentModelFactoryCodingModelGateway gateway = new AgentModelFactoryCodingModelGateway(
                (tenantId, modelId) -> model, mapper);
        HarnessSessionState session = session();
        HarnessRunState run = run(session, 1);

        assertThatThrownBy(() -> gateway.complete(new CodingModelRequest(owner, session, run,
                transcript(), tools())))
                .isInstanceOf(CodingModelUnavailableException.class)
                .satisfies(error -> {
                    CodingModelUnavailableException unavailable =
                            (CodingModelUnavailableException) error;
                    assertThat(unavailable.code()).isEqualTo("MODEL_TURN_FAILED");
                    assertThat(unavailable.getMessage())
                            .doesNotContain("https", "secret", "sk-", "endpoint");
                });
    }

    @Test
    void revisionTurnInjectsPreviousPlanAndFeedbackAsSystemContext() {
        HarnessSessionState session = session();
        CodingPlanState draft = CodingPlanState.create("do it", NOW)
                .replacePlan("OLD PLAN BODY", List.of(new PlanStepState("s1", "old step",
                        List.of())), NOW);
        CodingPlanState revised = draft.requestRevision("f1", "add regression tests", NOW + 1);
        HarnessRunState run = HarnessRunState.createWithId("r1", session, "do it", null,
                new HarnessModelRoute(1, session.model(), session.model()), ALL_ACTIONS, NOW)
                .withCounters(1, 0, NOW)
                .withPlan(revised, NOW);
        CapturingModel model = new CapturingModel(Flux.just(ChatResponse.builder()
                .content(List.of(TextBlock.builder().text("{\"steps\":[{\"id\":\"s1\","
                        + "\"title\":\"old step\"}]}").build()))
                .build()));
        AgentModelFactoryCodingModelGateway gateway = new AgentModelFactoryCodingModelGateway(
                (tenantId, modelId) -> model, mapper);

        gateway.complete(new CodingModelRequest(owner, session, run, transcript(), tools()));

        assertThat(model.capturedMessages.get(1).getRole()).isEqualTo(MsgRole.SYSTEM);
        assertThat(model.capturedMessages.get(1).getTextContent())
                .contains("Plan revision requested", "OLD PLAN BODY", "s1: old step",
                        "add regression tests", "f1")
                .doesNotContain("T1", "/tmp/ws");
        assertThat(model.capturedTools).isEmpty();
    }

    @Test
    void noPlanTurnDoesNotInjectPlanContext() {
        HarnessSessionState session = session();
        HarnessRunState run = run(session, 1);
        CapturingModel model = new CapturingModel(Flux.just(ChatResponse.builder()
                .content(List.of(TextBlock.builder().text("done").build())).build()));
        AgentModelFactoryCodingModelGateway gateway = new AgentModelFactoryCodingModelGateway(
                (tenantId, modelId) -> model, mapper);

        gateway.complete(new CodingModelRequest(owner, session, run, transcript(), tools()));

        assertThat(model.capturedMessages).hasSize(4);
        assertThat(model.capturedMessages.get(0).getRole()).isEqualTo(MsgRole.SYSTEM);
        assertThat(model.capturedMessages.get(1).getRole()).isNotEqualTo(MsgRole.SYSTEM);
        assertThat(model.capturedMessages.get(1).getTextContent())
                .doesNotContain("Approved execution plan", "Plan revision requested");
    }

    private static final class CapturingModel implements Model {
        private final Flux<ChatResponse> response;
        private List<Msg> capturedMessages = List.of();
        private List<ToolSchema> capturedTools = List.of();

        private CapturingModel(Flux<ChatResponse> response) {
            this.response = response;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                         GenerateOptions options) {
            capturedMessages = new ArrayList<>(messages);
            capturedTools = tools == null ? List.of() : new ArrayList<>(tools);
            return response;
        }

        @Override
        public String getModelName() {
            return "deepseek-flash";
        }
    }
}
