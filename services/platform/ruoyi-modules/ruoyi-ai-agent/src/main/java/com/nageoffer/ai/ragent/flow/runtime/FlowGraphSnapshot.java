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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.ai.ragent.runtime.CanonicalJson;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F13:2 冻结规则 §3.1/§3.2：三节点线性图快照、类型解析、模板变量与 graphHash。
 *
 * <p><b>快照形态（冻结）。</b>
 * {@code {schemaVersion:1, workflowUuid, nodes:[{uuid,type,title,prompt,nodeConfig,inputConfig}]
 * 按 uuid 升序, edges:[{source,target}] 按 source,target 升序}}；
 * {@code graphHash = sha256(CanonicalJson.canonicalize(snapshot))}。
 *
 * <p><b>类型解析顺序。</b>{@code node_config.type} 存在即采用；否则
 * {@code workflow_component_id>0} 时按组件名（Start→START / Answer→LLM / End→END）；
 * 两者都没有即 400 {@code flow node type missing}。
 *
 * <p><b>本切片边界。</b>只支持 1 START + 1 LLM + 1 END、2 条边；分支/循环/并行/审批
 * 明确不在本切片（见 Spec §3.1）。
 */
public final class FlowGraphSnapshot {

    private static final ObjectMapper JSON = CanonicalJson.strictMapper();
    private static final Set<String> TYPES = Set.of("START", "LLM", "END");
    private static final Pattern TEMPLATE_VARIABLE = Pattern.compile("\\{([A-Za-z0-9_]{1,32})}");

    public record Node(String uuid, String type, String title, String prompt,
                       JsonNode nodeConfig, JsonNode inputConfig) {

        public String modelName() {
            return nodeConfig != null && nodeConfig.hasNonNull("model_name")
                    && nodeConfig.get("model_name").isTextual()
                    ? nodeConfig.get("model_name").asText() : null;
        }

        public String resultTemplate() {
            return nodeConfig != null && nodeConfig.hasNonNull("result")
                    && nodeConfig.get("result").isTextual()
                    ? nodeConfig.get("result").asText() : null;
        }
    }

    public record Edge(String source, String target) {
    }

    private final String workflowUuid;
    private final List<Node> nodes;
    private final List<Edge> edges;
    private final ObjectNode json;

    private FlowGraphSnapshot(String workflowUuid, List<Node> nodes, List<Edge> edges) {
        this.workflowUuid = workflowUuid;
        List<Node> sortedNodes = new ArrayList<>(nodes);
        sortedNodes.sort(Comparator.comparing(Node::uuid));
        List<Edge> sortedEdges = new ArrayList<>(edges);
        sortedEdges.sort(Comparator.comparing(Edge::source).thenComparing(Edge::target));
        this.nodes = List.copyOf(sortedNodes);
        this.edges = List.copyOf(sortedEdges);
        this.json = canonicalJson();
    }

    // ------------------------------------------------------------------ compile

    public static FlowGraphSnapshot compile(FlowRuntimeRepository.GraphSource source, String boundModel) {
        if (source == null || source.nodes() == null || source.edges() == null) {
            throw bad("flow topology is invalid");
        }
        List<Node> nodes = new ArrayList<>();
        for (FlowRuntimeRepository.NodeSource raw : source.nodes()) {
            nodes.add(compileNode(raw, source.componentNames()));
        }
        List<Edge> edges = new ArrayList<>();
        for (FlowRuntimeRepository.EdgeSource raw : source.edges()) {
            edges.add(new Edge(raw.source(), raw.target()));
        }
        FlowGraphSnapshot snapshot = new FlowGraphSnapshot(source.workflowUuid(), nodes, edges);
        snapshot.requireLinearTopology();
        snapshot.requireBoundModel(boundModel);
        return snapshot;
    }

    private static Node compileNode(FlowRuntimeRepository.NodeSource raw, Map<Long, String> componentNames) {
        JsonNode config = parseObject(raw.nodeConfigJson(), "flow node config must be an object");
        JsonNode inputConfig = parseObject(raw.inputConfigJson(), "flow node input config must be an object");
        if (inputConfig == null) {
            inputConfig = JSON.createObjectNode();
        }
        String type = resolveType(raw, config, componentNames);
        String prompt = null;
        if ("LLM".equals(type)) {
            JsonNode promptNode = config == null ? null : config.get("prompt");
            if (promptNode == null || !promptNode.isTextual() || promptNode.asText().isBlank()) {
                throw bad("flow node prompt is required");
            }
            prompt = promptNode.asText();
            if (prompt.length() > 20000) {
                throw bad("flow node prompt is too long");
            }
        }
        return new Node(raw.uuid(), type, raw.title(), prompt, config, inputConfig);
    }

    private static String resolveType(FlowRuntimeRepository.NodeSource raw, JsonNode config,
                                      Map<Long, String> componentNames) {
        JsonNode explicit = config == null ? null : config.get("type");
        if (explicit != null && !explicit.isNull()) {
            String value = explicit.isTextual() ? explicit.asText() : explicit.toString();
            if (!TYPES.contains(value)) {
                throw bad("unsupported flow node type: " + value);
            }
            return value;
        }
        String componentName = raw.componentId() > 0 && componentNames != null
                ? componentNames.get(raw.componentId()) : null;
        if (componentName == null) {
            throw bad("flow node type missing");
        }
        return switch (componentName) {
            case "Start" -> "START";
            case "Answer" -> "LLM";
            case "End" -> "END";
            default -> throw bad("unsupported flow node type: " + componentName);
        };
    }

    private void requireLinearTopology() {
        if (nodes.size() != 3) {
            throw bad("flow topology is invalid: exactly 3 nodes are required");
        }
        long starts = nodes.stream().filter(node -> "START".equals(node.type())).count();
        long llms = nodes.stream().filter(node -> "LLM".equals(node.type())).count();
        long ends = nodes.stream().filter(node -> "END".equals(node.type())).count();
        if (starts != 1 || llms != 1 || ends != 1) {
            throw bad("flow topology is invalid: exactly one START, one LLM and one END are required");
        }
        if (edges.size() != 2) {
            throw bad("flow topology is invalid: exactly 2 edges are required");
        }
        Node start = startNode();
        Node llm = llmNode();
        Node end = endNode();
        Set<String> expected = new LinkedHashSet<>();
        expected.add(start.uuid() + "\u0000" + llm.uuid());
        expected.add(llm.uuid() + "\u0000" + end.uuid());
        Set<String> actual = new LinkedHashSet<>();
        for (Edge edge : edges) {
            if (edge.source() == null || edge.target() == null || edge.source().equals(edge.target())) {
                throw bad("flow topology is invalid");
            }
            actual.add(edge.source() + "\u0000" + edge.target());
        }
        if (!expected.equals(actual)) {
            throw bad("flow topology is invalid: edges must be START->LLM->END");
        }
    }

    // ------------------------------------------------------------------ persisted

    /** D7/执行期：从 {@code ai_flow_runtime.input.graph} 回读；形状不符即拒绝。 */
    public static FlowGraphSnapshot fromPersisted(JsonNode graph) {
        if (graph == null || !graph.isObject()) {
            throw bad("flow graph snapshot is invalid");
        }
        if (graph.path("schemaVersion").asInt(-1) != 1) {
            throw bad("flow graph snapshot schemaVersion is invalid");
        }
        String workflowUuid = graph.path("workflowUuid").asText("");
        if (!workflowUuid.matches("[0-9a-f]{32}")) {
            throw bad("flow graph snapshot workflowUuid is invalid");
        }
        JsonNode nodesNode = graph.get("nodes");
        JsonNode edgesNode = graph.get("edges");
        if (nodesNode == null || !nodesNode.isArray() || edgesNode == null || !edgesNode.isArray()) {
            throw bad("flow graph snapshot is invalid");
        }
        List<Node> nodes = new ArrayList<>();
        for (JsonNode raw : nodesNode) {
            if (!raw.isObject()) {
                throw bad("flow graph snapshot is invalid");
            }
            String uuid = raw.path("uuid").asText("");
            String type = raw.path("type").asText("");
            if (!uuid.matches("[A-Za-z0-9_-]{1,64}") || !TYPES.contains(type)) {
                throw bad("flow graph snapshot is invalid");
            }
            JsonNode nodeConfig = raw.get("nodeConfig");
            if (nodeConfig != null && !nodeConfig.isNull() && !nodeConfig.isObject()) {
                throw bad("flow graph snapshot is invalid");
            }
            JsonNode inputConfig = raw.get("inputConfig");
            if (inputConfig != null && !inputConfig.isNull() && !inputConfig.isObject()) {
                throw bad("flow graph snapshot is invalid");
            }
            String prompt = raw.hasNonNull("prompt") ? raw.get("prompt").asText(null) : null;
            String title = raw.hasNonNull("title") ? raw.get("title").asText(null) : null;
            nodes.add(new Node(uuid, type, title, prompt,
                    nodeConfig == null || nodeConfig.isNull() ? null : nodeConfig,
                    inputConfig == null || inputConfig.isNull() ? JSON.createObjectNode() : inputConfig));
        }
        List<Edge> edges = new ArrayList<>();
        for (JsonNode raw : edgesNode) {
            if (!raw.isObject()) {
                throw bad("flow graph snapshot is invalid");
            }
            edges.add(new Edge(raw.path("source").asText(""), raw.path("target").asText("")));
        }
        FlowGraphSnapshot snapshot = new FlowGraphSnapshot(workflowUuid, nodes, edges);
        snapshot.requireLinearTopology();
        return snapshot;
    }

    // ------------------------------------------------------------------ rules

    /** §3.1(3)：model_name 只允许等于受理绑定的发布版本模型。 */
    public void requireBoundModel(String boundModel) {
        Node llm = llmNode();
        JsonNode configured = llm.nodeConfig() == null ? null : llm.nodeConfig().get("model_name");
        if (configured != null && !configured.isNull()) {
            if (configured.isTextual() && boundModel != null && boundModel.equals(configured.asText())) {
                return;
            }
            throw bad("flow model override is not allowed");
        }
    }

    /** §3.1(4)：模板变量 ⊆ 运行输入键 ∪ {input}；缺变量 400。 */
    public void requireTemplateInputs(Map<String, String> inputs) {
        Set<String> allowed = new LinkedHashSet<>(inputs.keySet());
        allowed.add("input");
        checkTemplate(llmNode().prompt(), allowed);
        checkTemplate(endNode().resultTemplate(), allowed);
    }

    private static void checkTemplate(String template, Set<String> allowed) {
        if (template == null || template.isBlank()) {
            return;
        }
        Matcher matcher = TEMPLATE_VARIABLE.matcher(template);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!allowed.contains(name)) {
                throw bad("flow input missing: " + name);
            }
        }
    }

    public String renderLlmPrompt(Map<String, String> inputs) {
        return render(llmNode().prompt(), inputs);
    }

    /** END {@code node_config.result}；缺省返回 null（调用方取 LLM 文本）。 */
    public String renderEndResult(Map<String, String> inputs) {
        String template = endNode().resultTemplate();
        if (template == null || template.isBlank()) {
            return null;
        }
        return render(template, inputs);
    }

    public static String render(String template, Map<String, String> inputs) {
        if (template == null) {
            return "";
        }
        String result = template;
        for (Map.Entry<String, String> entry : inputs.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        result = result.replace("{input}", inputs.getOrDefault("input", ""));
        return result;
    }

    // ------------------------------------------------------------------ accessors

    public String workflowUuid() {
        return workflowUuid;
    }

    public List<Node> nodes() {
        return nodes;
    }

    public List<Edge> edges() {
        return edges;
    }

    public Node startNode() {
        return firstOfType("START");
    }

    public Node llmNode() {
        return firstOfType("LLM");
    }

    public Node endNode() {
        return firstOfType("END");
    }

    private Node firstOfType(String type) {
        for (Node node : nodes) {
            if (type.equals(node.type())) {
                return node;
            }
        }
        throw bad("flow topology is invalid: missing " + type + " node");
    }

    /** 执行顺序由冻结拓扑决定：START -> LLM -> END。 */
    public List<Node> executionOrder() {
        return List.of(startNode(), llmNode(), endNode());
    }

    public ObjectNode toJson() {
        return json.deepCopy();
    }

    public String hash() {
        return CanonicalJson.sha256(CanonicalJson.canonicalize(json));
    }

    private ObjectNode canonicalJson() {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("workflowUuid", workflowUuid);
        ArrayNode nodeArray = root.putArray("nodes");
        for (Node node : nodes) {
            ObjectNode item = nodeArray.addObject();
            item.put("uuid", node.uuid());
            item.put("type", node.type());
            if (node.title() == null) {
                item.putNull("title");
            } else {
                item.put("title", node.title());
            }
            if (node.prompt() == null) {
                item.putNull("prompt");
            } else {
                item.put("prompt", node.prompt());
            }
            if (node.nodeConfig() == null) {
                item.putNull("nodeConfig");
            } else {
                item.set("nodeConfig", node.nodeConfig());
            }
            item.set("inputConfig", node.inputConfig());
        }
        ArrayNode edgeArray = root.putArray("edges");
        for (Edge edge : edges) {
            ObjectNode item = edgeArray.addObject();
            item.put("source", edge.source());
            item.put("target", edge.target());
        }
        return root;
    }

    // ------------------------------------------------------------------ helpers

    private static JsonNode parseObject(String raw, String message) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode parsed = JSON.readTree(raw);
            if (parsed == null || !parsed.isObject()) {
                throw bad(message);
            }
            return parsed;
        } catch (RunApiException e) {
            throw e;
        } catch (Exception e) {
            throw bad(message);
        }
    }

    private static RunApiException bad(String message) {
        return new RunApiException(RunErrorCode.BAD_REQUEST, message);
    }
}
