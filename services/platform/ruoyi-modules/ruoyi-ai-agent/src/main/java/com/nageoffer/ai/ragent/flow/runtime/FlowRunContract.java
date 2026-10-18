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
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.runtime.CanonicalJson;
import com.nageoffer.ai.ragent.runtime.RunAccessService;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.RuntimeActionContract;
import com.nageoffer.ai.ragent.runtime.config.ConfigAuthorityUnavailable;
import com.nageoffer.ai.ragent.runtime.config.EngineModelAuthority;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.dto.AdmissionRequest;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * F13:2 规范动作 {@code flow.run} 的受理契约（{@link RuntimeActionContract}）。
 *
 * <p><b>受理期判定（§3.1/§3.2 + D5/D7）。</b>
 * <ul>
 *   <li>输入白名单 {@code flowUuid/inputs}；inputs 仅字符串、键 {@code [A-Za-z0-9_]{1,32}}、
 *       ≤16 个、值 ≤8192；</li>
 *   <li>可执行谓词（tenant + 未删 + 启用 + 本人或公开）0 行 → 404，不区分原因；</li>
 *   <li>图快照编译（三节点线性拓扑/类型/prompt/模型覆盖）与模板变量
 *       （⊆ 输入键 ∪ {@code input}）；</li>
 *   <li>D5：{@code requireFunction("flow.run","flow:"+uuid)} 在线复核；</li>
 *   <li>D7：retry 父 run 必须 {@code flow.run} 且终态，复用父
 *       {@code ai_flow_runtime.input.graph} 快照，不重读当前图。</li>
 * </ul>
 *
 * <p><b>onAdmitted 与受理同事务</b>（{@code RunAdmissionService.doAdmit} 内）：
 * 写 1 行 runtime（status=1）＋每节点 1 行 runtime_node（status=1/remark=PENDING）。
 * {@code runtime.uuid = runId.substring(2)}（varchar(32)，坑 1）。
 */
@Component
public class FlowRunContract implements RuntimeActionContract {

    public static final String ACTION = "flow.run";
    public static final String EXECUTION_VERSION = "f13-flow-v1";

    private static final Set<String> INPUT_FIELDS = Set.of("flowUuid", "inputs");
    private static final String UUID_PATTERN = "[0-9a-f]{32}";
    private static final String INPUT_KEY_PATTERN = "[A-Za-z0-9_]{1,32}";
    private static final int MAX_INPUTS = 16;
    private static final int MAX_INPUT_VALUE = 8192;

    private static final ObjectMapper JSON = CanonicalJson.strictMapper();

    private final FlowRuntimeRepository repository;
    private final RunAccessService access;
    private final ObjectProvider<EngineModelAuthority> authorityProvider;
    private final RunLedgerDao runs;

    public FlowRunContract(FlowRuntimeRepository repository, RunAccessService access,
                           ObjectProvider<EngineModelAuthority> authorityProvider, RunLedgerDao runs) {
        this.repository = repository;
        this.access = access;
        this.authorityProvider = authorityProvider;
        this.runs = runs;
    }

    @Override
    public String action() {
        return ACTION;
    }

    @Override
    public String executionVersion() {
        return EXECUTION_VERSION;
    }

    // ------------------------------------------------------------------ validate

    @Override
    public void validate(ExecutionPrincipal principal, AdmissionRequest request) {
        JsonNode input = request.input();
        if (input == null || !input.isObject()) {
            throw bad("flow input must be an object");
        }
        input.fieldNames().forEachRemaining(name -> {
            if (!INPUT_FIELDS.contains(name)) {
                throw bad("unsupported flow input field: " + name);
            }
        });
        String workflowUuid = input.path("flowUuid").asText("");
        if (!workflowUuid.matches(UUID_PATTERN)) {
            throw bad("invalid flowUuid");
        }
        Map<String, String> inputs = readInputs(input);
        if (request.retryOf() != null) {
            validateRetry(principal, request.retryOf(), workflowUuid, inputs);
            return;
        }
        FlowRuntimeRepository.GraphSource source = repository
                .findExecutableGraph(principal.tenantId(), workflowUuid, FlowRuntimeRepository.ownerUserId(principal))
                .orElseThrow(() -> new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        FlowGraphSnapshot snapshot = FlowGraphSnapshot.compile(source, requireBoundModel(principal.tenantId()));
        snapshot.requireTemplateInputs(inputs);
        requireFunction(principal, workflowUuid);
    }

    private void validateRetry(ExecutionPrincipal principal, String retryOf, String workflowUuid,
                               Map<String, String> inputs) {
        RunRecord parent = runs.findRun(principal.tenantId(), retryOf)
                .orElseThrow(() -> new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        if (!ACTION.equals(parent.action())) {
            throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        }
        if (!parent.isTerminal()) {
            throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT,
                    "retry requires a terminal flow.run");
        }
        String parentUuid;
        try {
            parentUuid = FlowRuntimeRepository.runtimeUuidOf(retryOf);
        } catch (RunApiException e) {
            throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        }
        FlowRuntimeRepository.ParentGraph parentGraph = repository.findParentGraph(principal.tenantId(), parentUuid)
                .orElseThrow(() -> new RunApiException(RunErrorCode.RUN_STATE_CONFLICT,
                        "parent flow runtime is unavailable"));
        JsonNode wrapper = parseWrapper(parentGraph.inputJson());
        FlowGraphSnapshot snapshot = persistedSnapshot(wrapper);
        if (!snapshot.workflowUuid().equals(workflowUuid)) {
            throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT,
                    "retry must reuse the parent flow");
        }
        snapshot.requireTemplateInputs(inputs);
        snapshot.requireBoundModel(requireBoundModel(principal.tenantId()));
        requireFunction(principal, workflowUuid);
    }

    // ------------------------------------------------------------------ onAdmitted

    @Override
    public void onAdmitted(ExecutionPrincipal principal, String runId, AdmissionRequest request) {
        String tenantId = principal.tenantId();
        String runtimeUuid = FlowRuntimeRepository.runtimeUuidOf(runId);
        long userId = FlowRuntimeRepository.ownerUserId(principal);
        JsonNode payload = request.input();
        if (payload == null || !payload.isObject()) {
            throw bad("flow input must be an object");
        }
        ObjectNode wrapper = JSON.createObjectNode();
        wrapper.set("payload", payload);

        if (request.retryOf() != null) {
            String parentUuid = FlowRuntimeRepository.runtimeUuidOf(request.retryOf());
            FlowRuntimeRepository.ParentGraph parentGraph = repository.findParentGraph(tenantId, parentUuid)
                    .orElseThrow(() -> new RunApiException(RunErrorCode.RUN_STATE_CONFLICT,
                            "parent flow runtime is unavailable"));
            JsonNode parentWrapper = parseWrapper(parentGraph.inputJson());
            JsonNode graph = parentWrapper.get("graph");
            String graphHash = parentWrapper.path("graphHash").asText("");
            FlowGraphSnapshot snapshot = persistedSnapshot(parentWrapper);
            wrapper.set("graph", graph);
            wrapper.put("graphHash", graphHash);
            long runtimeId = repository.insertRuntime(tenantId, runtimeUuid, userId,
                    parentGraph.workflowId(), wrapper.toString());
            for (FlowGraphSnapshot.Node node : snapshot.nodes()) {
                OptionalLong nodeId = repository.findNodeId(tenantId, parentGraph.workflowId(), node.uuid());
                if (nodeId.isEmpty()) {
                    throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT,
                            "parent flow node is no longer resolvable: " + node.uuid());
                }
                repository.insertRuntimeNode(tenantId, runtimeId, nodeId.getAsLong(), userId);
            }
            return;
        }

        String workflowUuid = payload.path("flowUuid").asText("");
        if (!workflowUuid.matches(UUID_PATTERN)) {
            throw bad("invalid flowUuid");
        }
        FlowRuntimeRepository.GraphSource source = repository
                .findExecutableGraph(tenantId, workflowUuid, userId)
                .orElseThrow(() -> new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        FlowGraphSnapshot snapshot = FlowGraphSnapshot.compile(source, requireBoundModel(tenantId));
        wrapper.set("graph", snapshot.toJson());
        wrapper.put("graphHash", snapshot.hash());
        long runtimeId = repository.insertRuntime(tenantId, runtimeUuid, userId, source.workflowId(),
                wrapper.toString());
        Map<String, Long> nodeIds = new LinkedHashMap<>();
        for (FlowRuntimeRepository.NodeSource node : source.nodes()) {
            nodeIds.put(node.uuid(), node.id());
        }
        for (FlowGraphSnapshot.Node node : snapshot.nodes()) {
            Long nodeId = nodeIds.get(node.uuid());
            if (nodeId == null) {
                throw new RunApiException(RunErrorCode.INTERNAL_ERROR,
                        "flow node id mapping is incomplete: " + node.uuid());
            }
            repository.insertRuntimeNode(tenantId, runtimeId, nodeId, userId);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** 读取 {@code input.inputs}：仅字符串、键/数量/长度受限。 */
    static Map<String, String> readInputs(JsonNode input) {
        JsonNode inputsNode = input == null ? null : input.get("inputs");
        if (inputsNode == null || inputsNode.isNull()) {
            return new LinkedHashMap<>();
        }
        if (!inputsNode.isObject()) {
            throw bad("flow inputs must be an object");
        }
        if (inputsNode.size() > MAX_INPUTS) {
            throw bad("flow inputs must have at most " + MAX_INPUTS + " entries");
        }
        Map<String, String> inputs = new LinkedHashMap<>();
        inputsNode.fields().forEachRemaining(entry -> {
            if (!entry.getKey().matches(INPUT_KEY_PATTERN)) {
                throw bad("invalid flow input key: " + entry.getKey());
            }
            if (!entry.getValue().isTextual()) {
                throw bad("flow input values must be strings");
            }
            String value = entry.getValue().asText();
            if (value.length() > MAX_INPUT_VALUE) {
                throw bad("flow input value is too long: " + entry.getKey());
            }
            inputs.put(entry.getKey(), value);
        });
        return inputs;
    }

    private String requireBoundModel(String tenantId) {
        EngineModelAuthority authority = authorityProvider.getIfAvailable();
        if (authority == null) {
            throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE,
                    "config authority unavailable: refusing flow admission");
        }
        try {
            return authority.requirePublished(ACTION).modelId();
        } catch (ConfigAuthorityUnavailable unavailable) {
            throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE,
                    "config authority unavailable: refusing flow admission ("
                            + unavailable.getMessage() + ")");
        }
    }

    private void requireFunction(ExecutionPrincipal principal, String workflowUuid) {
        access.resources().requireFunction(RunAccessService.scoped(principal, Set.of(ACTION)),
                ACTION, "flow:" + workflowUuid);
    }

    private static JsonNode parseWrapper(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT, "flow runtime input is missing");
        }
        try {
            JsonNode wrapper = JSON.readTree(inputJson);
            if (wrapper == null || !wrapper.isObject() || wrapper.get("graph") == null) {
                throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT, "flow runtime input is invalid");
            }
            return wrapper;
        } catch (RunApiException e) {
            throw e;
        } catch (Exception e) {
            throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT, "flow runtime input is invalid");
        }
    }

    private static FlowGraphSnapshot persistedSnapshot(JsonNode wrapper) {
        JsonNode graph = wrapper.get("graph");
        String graphHash = wrapper.path("graphHash").asText("");
        if (graph == null || graphHash.isBlank()
                || !CanonicalJson.sha256(CanonicalJson.canonicalize(graph)).equals(graphHash)) {
            throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT,
                    "parent flow checkpoint is incompatible");
        }
        try {
            return FlowGraphSnapshot.fromPersisted(graph);
        } catch (RunApiException incompatible) {
            throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT,
                    "parent flow checkpoint is incompatible");
        }
    }

    private static RunApiException bad(String message) {
        return new RunApiException(RunErrorCode.BAD_REQUEST, message);
    }
}
