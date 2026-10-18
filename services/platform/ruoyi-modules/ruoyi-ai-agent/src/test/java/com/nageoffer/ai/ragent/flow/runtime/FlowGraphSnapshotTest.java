/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.flow.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F13:2 冻结规则 §3.1/§3.2：节点类型解析、三节点线性拓扑、LLM 配置约束、
 * 快照规范形与 graphHash。全部为纯内存判据（不触 DB / provider）。
 */
@Tag("dev")
class FlowGraphSnapshotTest {

    private static final String WORKFLOW = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String START = "0123456789abcdef0123456789abcdef";
    private static final String LLM = "1123456789abcdef0123456789abcdef";
    private static final String END = "2123456789abcdef0123456789abcdef";
    private static final String BOUND_MODEL = "deepseek-flash";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ------------------------------------------------------------------ 正例

    @Test
    @DisplayName("三节点快照：按 uuid 升序、边按 source/target 升序、hash 规范稳定")
    void compilesCanonicalThreeNodeSnapshot() {
        FlowGraphSnapshot snapshot = compile(graphSource(
                node(11L, START, "start", 0L, "{}"),
                node(12L, LLM, "llm", 0L, "{\"prompt\":\"hello {name}\",\"model_name\":\"" + BOUND_MODEL + "\"}"),
                node(13L, END, "end", 0L, "{\"result\":\"final {name}\"}")));

        assertThat(snapshot.workflowUuid()).isEqualTo(WORKFLOW);
        assertThat(snapshot.nodes()).extracting(FlowGraphSnapshot.Node::uuid)
                .containsExactly(START, LLM, END);
        assertThat(snapshot.edges()).extracting(FlowGraphSnapshot.Edge::source)
                .containsExactly(START, LLM);
        assertThat(snapshot.startNode().uuid()).isEqualTo(START);
        assertThat(snapshot.llmNode().uuid()).isEqualTo(LLM);
        assertThat(snapshot.endNode().uuid()).isEqualTo(END);
        assertThat(snapshot.executionOrder()).extracting(FlowGraphSnapshot.Node::type)
                .containsExactly("START", "LLM", "END");
        assertThat(snapshot.hash()).matches("[0-9a-f]{64}");
        assertThat(snapshot.toJson().path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(snapshot.toJson().path("workflowUuid").asText()).isEqualTo(WORKFLOW);

        // 同一输入 -> 同一 hash（无时间戳/迭代序）
        FlowGraphSnapshot again = compile(graphSource(
                node(11L, START, "start", 0L, "{}"),
                node(12L, LLM, "llm", 0L, "{\"prompt\":\"hello {name}\",\"model_name\":\"" + BOUND_MODEL + "\"}"),
                node(13L, END, "end", 0L, "{\"result\":\"final {name}\"}")));
        assertThat(again.hash()).isEqualTo(snapshot.hash());
    }

    @Test
    @DisplayName("类型回退：node_config.type 缺失时按 workflow_component_id 的组件名映射 Start/Answer/End")
    void fallsBackToComponentNames() {
        FlowGraphSnapshot snapshot = FlowGraphSnapshot.compile(new FlowRuntimeRepository.GraphSource(
                41L, WORKFLOW,
                List.of(node(11L, START, "s", 9L, null), node(12L, LLM, "l", 10L,
                                "{\"prompt\":\"hi\"}"),
                        node(13L, END, "e", 11L, null)),
                List.of(new FlowRuntimeRepository.EdgeSource(START, LLM),
                        new FlowRuntimeRepository.EdgeSource(LLM, END)),
                Map.of(9L, "Start", 10L, "Answer", 11L, "End")), BOUND_MODEL);

        assertThat(snapshot.nodes()).extracting(FlowGraphSnapshot.Node::type)
                .containsExactly("START", "LLM", "END");
    }

    @Test
    @DisplayName("模板渲染：{name} 用输入值，{input} 是保留变量")
    void rendersInputAndReservedPlaceholder() {
        FlowGraphSnapshot snapshot = compile(graphSource(
                node(11L, START, "start", 0L, "{}"),
                node(12L, LLM, "llm", 0L, "{\"prompt\":\"hi {name} {input}\"}"),
                node(13L, END, "end", 0L, "{}")));

        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("name", "Ada");
        inputs.put("input", "raw");
        snapshot.requireTemplateInputs(inputs);
        assertThat(snapshot.renderLlmPrompt(inputs)).isEqualTo("hi Ada raw");
    }

    @Test
    @DisplayName("持久化快照回读：hash 与节点/边顺序保持不变")
    void persistedSnapshotRoundTripsToTheSameHash() {
        FlowGraphSnapshot snapshot = compile(graphSource(
                node(11L, START, "start", 0L, "{}"),
                node(12L, LLM, "llm", 0L, "{\"prompt\":\"hello {name}\"}"),
                node(13L, END, "end", 0L, "{}")));

        FlowGraphSnapshot parsed = FlowGraphSnapshot.fromPersisted(snapshot.toJson());
        assertThat(parsed.hash()).isEqualTo(snapshot.hash());
        assertThat(parsed.nodes()).extracting(FlowGraphSnapshot.Node::uuid)
                .containsExactly(START, LLM, END);
        assertThat(parsed.workflowUuid()).isEqualTo(WORKFLOW);
    }

    // ------------------------------------------------------------------ 类型 / 拓扑 400

    @Test
    @DisplayName("node_config.type 非法 -> 400 unsupported flow node type: <type>")
    void unsupportedExplicitTypeIsBadRequest() {
        assertThatThrownBy(() -> compile(graphSource(
                node(11L, START, "start", 0L, "{\"type\":\"HTTP\"}"),
                node(12L, LLM, "llm", 0L, "{\"prompt\":\"hi\"}"),
                node(13L, END, "end", 0L, "{}"))))
                .isInstanceOf(RunApiException.class)
                .hasMessage("unsupported flow node type: HTTP")
                .extracting(e -> ((RunApiException) e).errorCode())
                .isEqualTo(RunErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("组件名非 Start/Answer/End -> 400 unsupported flow node type: <name>")
    void unsupportedComponentNameIsBadRequest() {
        FlowRuntimeRepository.GraphSource source = new FlowRuntimeRepository.GraphSource(
                41L, WORKFLOW,
                List.of(node(11L, START, "s", 9L, null), node(12L, LLM, "l", 10L,
                                "{\"prompt\":\"hi\"}"),
                        node(13L, END, "e", 11L, null)),
                List.of(new FlowRuntimeRepository.EdgeSource(START, LLM),
                        new FlowRuntimeRepository.EdgeSource(LLM, END)),
                Map.of(9L, "Start", 10L, "HttpRequest", 11L, "End"));

        assertThatThrownBy(() -> FlowGraphSnapshot.compile(source, BOUND_MODEL))
                .isInstanceOf(RunApiException.class)
                .hasMessage("unsupported flow node type: HttpRequest");
    }

    @Test
    @DisplayName("既无 type 也无组件引用 -> 400 flow node type missing")
    void missingTypeIsBadRequest() {
        assertThatThrownBy(() -> compile(new FlowRuntimeRepository.GraphSource(
                41L, WORKFLOW,
                List.of(node(11L, START, "start", 0L, null),
                        node(12L, LLM, "llm", 0L, "{\"type\":\"LLM\",\"prompt\":\"hi\"}"),
                        node(13L, END, "end", 0L, "{\"type\":\"END\"}")),
                List.of(new FlowRuntimeRepository.EdgeSource(START, LLM),
                        new FlowRuntimeRepository.EdgeSource(LLM, END)),
                Map.of())))
                .isInstanceOf(RunApiException.class)
                .hasMessage("flow node type missing");
    }

    @Test
    @DisplayName("节点数不是恰 3 / 边不是恰 2 条 START->LLM->END -> 400 flow topology is invalid")
    void topologyMustBeExactlyThreeNodesAndTwoLinearEdges() {
        // 两个节点
        assertThatThrownBy(() -> compile(new FlowRuntimeRepository.GraphSource(
                41L, WORKFLOW,
                List.of(node(11L, START, "s", 0L, "{\"type\":\"START\"}"),
                        node(13L, END, "e", 0L, "{\"type\":\"END\"}")),
                List.of(new FlowRuntimeRepository.EdgeSource(START, END)),
                Map.of())))
                .isInstanceOf(RunApiException.class)
                .hasMessageContaining("flow topology is invalid");

        // 边反向
        assertThatThrownBy(() -> compile(new FlowRuntimeRepository.GraphSource(
                41L, WORKFLOW,
                List.of(node(11L, START, "s", 0L, "{\"type\":\"START\"}"),
                        node(12L, LLM, "l", 0L, "{\"type\":\"LLM\",\"prompt\":\"hi\"}"),
                        node(13L, END, "e", 0L, "{\"type\":\"END\"}")),
                List.of(new FlowRuntimeRepository.EdgeSource(LLM, START),
                        new FlowRuntimeRepository.EdgeSource(LLM, END)),
                Map.of())))
                .isInstanceOf(RunApiException.class)
                .hasMessageContaining("flow topology is invalid");

        // 自环
        assertThatThrownBy(() -> compile(new FlowRuntimeRepository.GraphSource(
                41L, WORKFLOW,
                List.of(node(11L, START, "s", 0L, "{\"type\":\"START\"}"),
                        node(12L, LLM, "l", 0L, "{\"type\":\"LLM\",\"prompt\":\"hi\"}"),
                        node(13L, END, "e", 0L, "{\"type\":\"END\"}")),
                List.of(new FlowRuntimeRepository.EdgeSource(LLM, LLM),
                        new FlowRuntimeRepository.EdgeSource(LLM, END)),
                Map.of())))
                .isInstanceOf(RunApiException.class)
                .hasMessageContaining("flow topology is invalid");
    }

    @Test
    @DisplayName("LLM prompt 必填非空且 <= 20000")
    void llmPromptIsRequiredAndBounded() {
        assertThatThrownBy(() -> compile(graphSource(
                node(11L, START, "start", 0L, "{}"),
                node(12L, LLM, "llm", 0L, "{}"),
                node(13L, END, "end", 0L, "{}"))))
                .isInstanceOf(RunApiException.class)
                .hasMessage("flow node prompt is required");

        String tooLong = "x".repeat(20001);
        assertThatThrownBy(() -> compile(graphSource(
                node(11L, START, "start", 0L, "{}"),
                node(12L, LLM, "llm", 0L, "{\"prompt\":\"" + tooLong + "\"}"),
                node(13L, END, "end", 0L, "{}"))))
                .isInstanceOf(RunApiException.class)
                .hasMessage("flow node prompt is too long");
    }

    @Test
    @DisplayName("model_name 与受理绑定模型不同 -> 400 flow model override is not allowed")
    void modelOverrideIsRefused() {
        assertThatThrownBy(() -> compile(graphSource(
                node(11L, START, "start", 0L, "{}"),
                node(12L, LLM, "llm", 0L, "{\"prompt\":\"hi\",\"model_name\":\"gpt-4o\"}"),
                node(13L, END, "end", 0L, "{}"))))
                .isInstanceOf(RunApiException.class)
                .hasMessage("flow model override is not allowed");
    }

    @Test
    @DisplayName("模板变量不在输入键且不是 {input} -> 400 flow input missing: <name>")
    void missingTemplateInputIsRefused() {
        FlowGraphSnapshot snapshot = compile(graphSource(
                node(11L, START, "start", 0L, "{}"),
                node(12L, LLM, "llm", 0L, "{\"prompt\":\"hello {missing}\"}"),
                node(13L, END, "end", 0L, "{}")));

        assertThatThrownBy(() -> snapshot.requireTemplateInputs(Map.of("name", "Ada")))
                .isInstanceOf(RunApiException.class)
                .hasMessage("flow input missing: missing");
    }

    // ------------------------------------------------------------------ helpers

    private static FlowGraphSnapshot compile(FlowRuntimeRepository.GraphSource source) {
        return FlowGraphSnapshot.compile(source, BOUND_MODEL);
    }

    private static FlowRuntimeRepository.GraphSource graphSource(FlowRuntimeRepository.NodeSource start,
                                                                 FlowRuntimeRepository.NodeSource llm,
                                                                 FlowRuntimeRepository.NodeSource end) {
        return new FlowRuntimeRepository.GraphSource(41L, WORKFLOW,
                List.of(withType(start, "START"), withType(llm, "LLM"), withType(end, "END")),
                List.of(new FlowRuntimeRepository.EdgeSource(start.uuid(), llm.uuid()),
                        new FlowRuntimeRepository.EdgeSource(llm.uuid(), end.uuid())),
                Map.of());
    }

    /** 测试夹具默认补 type；显式给出的 type（如 HTTP 负例）保持原样。 */
    private static FlowRuntimeRepository.NodeSource withType(FlowRuntimeRepository.NodeSource node, String type) {
        com.fasterxml.jackson.databind.node.ObjectNode config;
        try {
            config = node.nodeConfigJson() == null || node.nodeConfigJson().isBlank()
                    ? MAPPER.createObjectNode()
                    : (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(node.nodeConfigJson());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        if (!config.has("type")) {
            config.put("type", type);
        }
        return new FlowRuntimeRepository.NodeSource(node.id(), node.uuid(), node.title(), node.componentId(),
                node.inputConfigJson(), config.toString());
    }

    private static FlowRuntimeRepository.NodeSource node(long id, String uuid, String title, long componentId,
                                                         String nodeConfig) {
        return new FlowRuntimeRepository.NodeSource(id, uuid, title, componentId, "{}", nodeConfig);
    }
}
