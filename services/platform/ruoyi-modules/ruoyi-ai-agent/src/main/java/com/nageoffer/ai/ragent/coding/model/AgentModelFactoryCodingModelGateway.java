package com.nageoffer.ai.ragent.coding.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.agent.config.AgentModelFactory;
import com.nageoffer.ai.ragent.coding.app.CodingModelGateway;
import com.nageoffer.ai.ragent.coding.app.CodingModelRequest;
import com.nageoffer.ai.ragent.coding.app.CodingModelUnavailableException;
import com.nageoffer.ai.ragent.coding.app.ModelToolCall;
import com.nageoffer.ai.ragent.coding.app.ModelTurn;
import com.nageoffer.ai.ragent.coding.app.PlanDraft;
import com.nageoffer.ai.ragent.coding.model.HarnessMessage;
import com.nageoffer.ai.ragent.coding.model.HarnessUsage;
import com.nageoffer.ai.ragent.coding.plan.CodingPlanState;
import com.nageoffer.ai.ragent.coding.plan.PlanFeedbackState;
import com.nageoffer.ai.ragent.coding.plan.PlanMode;
import com.nageoffer.ai.ragent.coding.plan.PlanReviewState;
import com.nageoffer.ai.ragent.coding.plan.PlanStepState;
import com.nageoffer.ai.ragent.coding.tool.CodingToolDescriptor;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F20-A2 目标 A：把 AgentScope {@link Model} 适配为 coding harness 的模型端口。
 *
 * <p>模型解析只经 {@link AgentModelFactory}（tenant + modelId，fail-closed）；凭据/端点只留在
 * 工厂内部，不进入 gateway、run 状态、事件或异常文案。工具 schema 只暴露名称/描述/参数，
 * 不含工作区路径或租户事实。适配器不执行工具、不绕审批。
 *
 * <p>首回合（plan 为空且 iteration=0）与计划修订回合（plan.mode=PLAN 且未批准）要求模型返回
 * 计划 JSON；解析失败即 {@code PLAN_JSON_REQUIRED}，绝不伪造计划。
 */
public class AgentModelFactoryCodingModelGateway implements CodingModelGateway {

    private static final Duration MODEL_TIMEOUT = Duration.ofSeconds(90);

    /** P1：计划/反馈上下文必须有界，避免把长计划塞满上下文窗口。 */
    private static final int PLAN_CONTEXT_MAX_CHARS = 12_000;

    private static final String PLAN_SYSTEM_PROMPT = """
            You are a coding assistant. This turn is a planning turn.
            Return ONLY one JSON object, with no prose and no markdown fences:
            {"task":"<short task>","steps":[{"id":"<stable-id>","title":"<step>",\
            "dependencies":["<id>"]}],"risks":["<risk>"]}
            steps must be non-empty; dependencies are optional. Do not call tools in this turn.
            """;

    private static final String CODING_SYSTEM_PROMPT = """
            You are a coding assistant working inside an isolated workspace.
            Use the provided tools when needed. Never claim a tool result you did not receive.
            The workspace is read-only unless the tool policy allows writes.
            """;

    private final AgentModelFactory modelFactory;
    private final ObjectMapper objectMapper;

    public AgentModelFactoryCodingModelGateway(AgentModelFactory modelFactory) {
        this(modelFactory, new ObjectMapper());
    }

    public AgentModelFactoryCodingModelGateway(AgentModelFactory modelFactory,
                                               ObjectMapper objectMapper) {
        this.modelFactory = modelFactory;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @Override
    public ModelTurn complete(CodingModelRequest request) {
        Model model = resolveModel(request);
        boolean planTurn = isPlanTurn(request);
        List<Msg> messages = toMessages(request, planTurn);
        List<ToolSchema> tools = planTurn ? List.of() : toToolSchemas(request.tools());
        GenerateOptions options = GenerateOptions.builder().stream(Boolean.TRUE).build();

        List<ChatResponse> responses;
        try {
            Flux<ChatResponse> stream = model.stream(messages, tools, options);
            if (stream == null) {
                throw new CodingModelUnavailableException(
                        CodingModelUnavailableException.MODEL_TURN_FAILED,
                        "coding model returned no stream");
            }
            responses = stream.collectList().block(MODEL_TIMEOUT);
        } catch (CodingModelUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException failure) {
            // Provider/transport failures: stable code only; never leak endpoint or credentials.
            // Sanitized diagnostic: class + message (URLs/keys redacted).
            String detail = String.valueOf(failure.getMessage())
                    .replaceAll("https?://\\S+", "<url>")
                    .replaceAll("sk-[A-Za-z0-9_-]+", "<key>");
            org.slf4j.LoggerFactory.getLogger(AgentModelFactoryCodingModelGateway.class)
                    .warn("coding model turn failed: {}: {}",
                            failure.getClass().getSimpleName(),
                            detail.length() > 300 ? detail.substring(0, 300) : detail);
            throw new CodingModelUnavailableException(
                    CodingModelUnavailableException.MODEL_TURN_FAILED,
                    "coding model turn failed");
        }
        if (responses == null || responses.isEmpty()) {
            throw new CodingModelUnavailableException(
                    CodingModelUnavailableException.MODEL_TURN_FAILED,
                    "coding model returned an empty response");
        }
        return planTurn ? parsePlanTurn(responses) : parseCodingTurn(responses);
    }

    private Model resolveModel(CodingModelRequest request) {
        try {
            Model model = modelFactory.create(request.owner().tenantId(),
                    request.session().model());
            if (model == null) {
                throw new CodingModelUnavailableException(
                        CodingModelUnavailableException.MODEL_NOT_CONFIGURED,
                        "coding model is not configured");
            }
            return model;
        } catch (CodingModelUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException unavailable) {
            // Unknown model / ambiguous provider / missing connection: fail-closed, stable text.
            throw new CodingModelUnavailableException(
                    CodingModelUnavailableException.MODEL_NOT_CONFIGURED,
                    "coding model is not configured");
        }
    }

    /** Plan-first on the first turn; plan-revision while the durable plan is still in PLAN mode. */
    static boolean isPlanTurn(CodingModelRequest request) {
        CodingPlanState plan = request.run().plan();
        if (plan == null) {
            return request.run().iteration() == 0;
        }
        return plan.mode() == PlanMode.PLAN
                && plan.reviewState() != PlanReviewState.APPROVED;
    }

    private List<Msg> toMessages(CodingModelRequest request, boolean planTurn) {
        List<Msg> messages = new ArrayList<>(request.transcript().size() + 2);
        messages.add(Msg.builder().role(MsgRole.SYSTEM)
                .textContent(planTurn ? PLAN_SYSTEM_PROMPT : CODING_SYSTEM_PROMPT).build());
        // P1：BUILD 回合注入已批准计划；REVISION_REQUESTED 回合注入上一版计划 + 反馈。
        // 上下文只作为 SYSTEM 消息，不伪装 USER；无计划/无反馈时不注入。
        String planContext = planContext(request.run().plan());
        if (!planContext.isBlank()) {
            messages.add(Msg.builder().role(MsgRole.SYSTEM).textContent(planContext).build());
        }
        for (HarnessMessage message : request.transcript()) {
            switch (message.role()) {
                case USER, CONTROL -> messages.add(Msg.builder().role(MsgRole.USER)
                        .textContent(message.content() == null ? "" : message.content()).build());
                case SYSTEM -> messages.add(Msg.builder().role(MsgRole.SYSTEM)
                        .textContent(message.content() == null ? "" : message.content()).build());
                case ASSISTANT -> messages.add(assistantMessage(message));
                case TOOL -> messages.add(toolResultMessage(message));
                default -> {
                    // Unknown roles are not sent to the provider.
                }
            }
        }
        return messages;
    }

    /**
     * Plan context for approved BUILD turns and revision turns. Never contains tenant, credentials
     * or infrastructure paths; only the durable plan/feedback text the model itself produced.
     */
    static String planContext(CodingPlanState plan) {
        if (plan == null) {
            return "";
        }
        if (plan.mode() == PlanMode.BUILD) {
            return bounded("Approved execution plan (authoritative; do not re-plan):\n"
                    + planBody(plan, true));
        }
        if (plan.mode() == PlanMode.PLAN
                && plan.reviewState() == PlanReviewState.REVISION_REQUESTED) {
            StringBuilder context = new StringBuilder();
            context.append("Plan revision requested. Previous plan (revision ")
                    .append(plan.revision()).append("):\n")
                    .append(planBody(plan, true));
            if (!plan.feedbackHistory().isEmpty()) {
                context.append("\nOperator feedback (chronological):\n");
                for (PlanFeedbackState feedback : plan.feedbackHistory()) {
                    context.append("- [").append(feedback.feedbackId()).append("] ")
                            .append(feedback.content()).append('\n');
                }
            }
            return bounded(context.toString());
        }
        return "";
    }

    private static String planBody(CodingPlanState plan, boolean includeMarkdown) {
        StringBuilder body = new StringBuilder();
        body.append("Task: ").append(plan.originalRequest()).append('\n');
        if (includeMarkdown && plan.planMarkdown() != null && !plan.planMarkdown().isBlank()) {
            body.append("Markdown:\n").append(plan.planMarkdown()).append('\n');
        }
        body.append("Steps:\n");
        for (PlanStepState step : plan.steps()) {
            body.append("- ").append(step.stepId()).append(": ").append(step.title());
            if (!step.dependencyIds().isEmpty()) {
                body.append(" (after: ").append(String.join(", ", step.dependencyIds()))
                        .append(')');
            }
            body.append('\n');
        }
        return body.toString();
    }

    private static String bounded(String context) {
        if (context.length() <= PLAN_CONTEXT_MAX_CHARS) {
            return context;
        }
        String marker = "\n…[truncated]";
        return context.substring(0, PLAN_CONTEXT_MAX_CHARS - marker.length()) + marker;
    }

    private Msg assistantMessage(HarnessMessage message) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (message.thinking() != null && !message.thinking().isBlank()) {
            blocks.add(ThinkingBlock.builder().thinking(message.thinking()).build());
        }
        if (message.content() != null && !message.content().isBlank()) {
            blocks.add(TextBlock.builder().text(message.content()).build());
        }
        for (HarnessToolCall call : message.toolCalls()) {
            blocks.add(ToolUseBlock.builder().id(call.toolCallId()).name(call.toolName())
                    .input(call.arguments()).build());
        }
        if (blocks.isEmpty()) {
            blocks.add(TextBlock.builder().text("").build());
        }
        return Msg.builder().role(MsgRole.ASSISTANT).content(blocks).build();
    }

    private Msg toolResultMessage(HarnessMessage message) {
        String text = message.content() == null || message.content().isBlank()
                ? (message.toolError() ? "tool execution failed" : "ok")
                : message.content();
        ToolResultBlock block = ToolResultBlock.builder()
                .id(message.toolCallId())
                .name(message.toolName())
                .output(TextBlock.builder().text(text).build())
                .state(message.toolError() ? ToolResultState.ERROR : ToolResultState.SUCCESS)
                .build();
        return Msg.builder().role(MsgRole.TOOL).content(block).build();
    }

    private List<ToolSchema> toToolSchemas(List<CodingToolDescriptor> descriptors) {
        List<ToolSchema> schemas = new ArrayList<>(descriptors.size());
        for (CodingToolDescriptor descriptor : descriptors) {
            schemas.add(ToolSchema.builder()
                    .name(descriptor.name())
                    .description(descriptor.description())
                    .parameters(descriptor.parameters())
                    .build());
        }
        return schemas;
    }

    private ModelTurn parseCodingTurn(List<ChatResponse> responses) {
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        Map<String, ModelToolCall> calls = new LinkedHashMap<>();
        UsageTotal usage = new UsageTotal();
        for (ChatResponse response : responses) {
            usage.add(response.getUsage());
            List<ContentBlock> blocks = response.getContent();
            if (blocks == null) {
                continue;
            }
            for (ContentBlock block : blocks) {
                if (block instanceof TextBlock textBlock) {
                    text.append(textBlock.getText());
                } else if (block instanceof ThinkingBlock thinkingBlock) {
                    thinking.append(thinkingBlock.getThinking());
                } else if (block instanceof ToolUseBlock toolUse) {
                    calls.put(toolUse.getId(), new ModelToolCall(toolUse.getId(), toolUse.getName(),
                            toolUse.getInput() == null ? Map.of() : toolUse.getInput()));
                }
            }
        }
        if (text.isEmpty() && calls.isEmpty()) {
            StringBuilder diag = new StringBuilder("empty turn: responses=")
                    .append(responses.size()).append(" thinkingChars=").append(thinking.length());
            for (ChatResponse response : responses) {
                diag.append(" finish=").append(response.getFinishReason()).append(" blocks=");
                List<ContentBlock> blocks = response.getContent();
                if (blocks != null) {
                    for (ContentBlock block : blocks) {
                        diag.append(block.getClass().getSimpleName()).append(',');
                    }
                }
            }
            org.slf4j.LoggerFactory.getLogger(AgentModelFactoryCodingModelGateway.class)
                    .warn(diag.toString());
            throw new CodingModelUnavailableException(
                    CodingModelUnavailableException.MODEL_TURN_FAILED,
                    "coding model returned an empty turn");
        }
        return new ModelTurn(text.toString(),
                thinking.isEmpty() ? null : thinking.toString(),
                List.copyOf(calls.values()), null, usage.toHarnessUsage());
    }

    private ModelTurn parsePlanTurn(List<ChatResponse> responses) {
        StringBuilder text = new StringBuilder();
        boolean toolCall = false;
        UsageTotal usage = new UsageTotal();
        for (ChatResponse response : responses) {
            usage.add(response.getUsage());
            List<ContentBlock> blocks = response.getContent();
            if (blocks == null) {
                continue;
            }
            for (ContentBlock block : blocks) {
                if (block instanceof TextBlock textBlock) {
                    text.append(textBlock.getText());
                } else if (block instanceof ToolUseBlock) {
                    toolCall = true;
                }
            }
        }
        if (toolCall || text.isEmpty()) {
            throw new CodingModelUnavailableException(
                    CodingModelUnavailableException.PLAN_JSON_REQUIRED,
                    "the model did not return the required plan JSON");
        }
        PlanDraft draft;
        try {
            draft = parsePlanJson(stripFences(text.toString()));
        } catch (CodingModelUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException invalid) {
            throw new CodingModelUnavailableException(
                    CodingModelUnavailableException.PLAN_JSON_REQUIRED,
                    "the model did not return the required plan JSON");
        }
        return new ModelTurn("", null, List.of(), draft, usage.toHarnessUsage());
    }

    private PlanDraft parsePlanJson(String json) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception invalidJson) {
            throw new CodingModelUnavailableException(
                    CodingModelUnavailableException.PLAN_JSON_REQUIRED,
                    "the model did not return the required plan JSON");
        }
        if (root == null || !root.isObject()) {
            throw planJsonRequired();
        }
        JsonNode stepsNode = root.path("steps");
        if (!stepsNode.isArray() || stepsNode.isEmpty()) {
            throw planJsonRequired();
        }
        List<PlanStepState> steps = new ArrayList<>();
        for (JsonNode step : stepsNode) {
            String id = step.path("id").asText("");
            String title = step.path("title").asText("");
            if (id.isBlank() || title.isBlank()) {
                throw planJsonRequired();
            }
            List<String> dependencies = new ArrayList<>();
            JsonNode dependenciesNode = step.path("dependencies");
            if (dependenciesNode.isArray()) {
                for (JsonNode dependency : dependenciesNode) {
                    String value = dependency.asText("");
                    if (!value.isBlank()) {
                        dependencies.add(value);
                    }
                }
            }
            steps.add(new PlanStepState(id, title, dependencies));
        }
        String task = root.path("task").asText("Plan");
        String markdown = root.path("planMarkdown").asText(root.path("markdown").asText(""));
        if (markdown == null || markdown.isBlank()) {
            markdown = synthesizeMarkdown(task, steps, root.path("risks"));
        }
        return new PlanDraft(markdown, steps);
    }

    private String synthesizeMarkdown(String task, List<PlanStepState> steps, JsonNode risks) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(task).append("\n\n");
        int index = 1;
        for (PlanStepState step : steps) {
            markdown.append(index++).append(". ").append(step.title());
            if (!step.dependencyIds().isEmpty()) {
                markdown.append(" (after: ").append(String.join(", ", step.dependencyIds()))
                        .append(')');
            }
            markdown.append('\n');
        }
        if (risks != null && risks.isArray() && !risks.isEmpty()) {
            markdown.append("\n## Risks\n");
            for (JsonNode risk : risks) {
                String value = risk.asText("");
                if (!value.isBlank()) {
                    markdown.append("- ").append(value).append('\n');
                }
            }
        }
        return markdown.toString().strip();
    }

    private static CodingModelUnavailableException planJsonRequired() {
        return new CodingModelUnavailableException(
                CodingModelUnavailableException.PLAN_JSON_REQUIRED,
                "the model did not return the required plan JSON");
    }

    private static String stripFences(String text) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline >= 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            int closing = trimmed.lastIndexOf("```");
            if (closing >= 0) {
                trimmed = trimmed.substring(0, closing);
            }
        }
        return trimmed.strip();
    }

    private static final class UsageTotal {
        private long input;
        private long output;
        private long total;

        private void add(ChatUsage usage) {
            if (usage == null) {
                return;
            }
            input += Math.max(0, usage.getInputTokens());
            output += Math.max(0, usage.getOutputTokens());
            total += Math.max(0, usage.getTotalTokens());
        }

        private HarnessUsage toHarnessUsage() {
            return new HarnessUsage(input, output, total);
        }
    }
}
