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

package com.nageoffer.ai.ragent.ingestion.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.runtime.CanonicalJson;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.RuntimeActionContract;
import com.nageoffer.ai.ragent.runtime.dto.AdmissionRequest;
import com.nageoffer.ai.ragent.runtime.usage.UsageLedgerService;
import org.ruoyi.ai.api.runtime.DocumentPort;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * F06:1/2 规范动作 {@code ingestion.run} 的受理契约（{@link RuntimeActionContract}）。
 *
 * <p>受理期做**结构与权威**判定：入参白名单（拒绝 URL/凭据/伪造 identity/vectorSpace）、
 * 资源引用恰为 {@code knowledge_base kbId}+{@code document docId}、管线结构合法、
 * 源为**已注册私有上传件**（不是任意 URL）、重试前置（父终态未发布、无未决用量、
 * 重试节点真实失败/待执行且其前驱均已成功）。
 *
 * <p>模型相容（settings.modelId / embeddingModel vs run binding）不在受理期判定——
 * 受理时控制器/受理服务尚未把 binding 交给契约；由执行器按 {@code MODEL_CONFIG_CHANGED}
 * 同口径拒（与 {@code DocumentIngestExecutor} 一致）。
 *
 * <p>{@code onAdmitted} 与受理同事务：建任务行（id=runId）与节点行；重试 run 另建**独立
 * version**（{@code DocumentPort.insertVersion}，run_id=新 runId）。
 */
@Component
public class IngestionRunContract implements RuntimeActionContract {

    public static final String ACTION = "ingestion.run";
    public static final String EXECUTION_VERSION = "p2-ingestion-v1";

    private static final Set<String> INPUT_KEYS =
            Set.of("pipelineId", "docId", "kbId", "uploadId", "retryNodeId");
    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final java.util.regex.Pattern PIPELINE_ID = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,20}");

    private final IngestionPipelineReader pipelines;
    private final IngestionTaskRepository tasks;
    private final DocumentPort documents;
    private final UsageLedgerService usage;
    private final ObjectMapper objectMapper;

    public IngestionRunContract(IngestionPipelineReader pipelines, IngestionTaskRepository tasks,
                                DocumentPort documents, UsageLedgerService usage,
                                ObjectMapper objectMapper) {
        this.pipelines = pipelines;
        this.tasks = tasks;
        this.documents = documents;
        this.usage = usage;
        this.objectMapper = objectMapper;
    }

    @Override
    public String action() {
        return ACTION;
    }

    @Override
    public String executionVersion() {
        return EXECUTION_VERSION;
    }

    @Override
    public void validate(ExecutionPrincipal principal, AdmissionRequest request) {
        String tenantId = principal.tenantId();
        JsonNode input = request.input();
        if (input == null || !input.isObject()) {
            throw bad("input must be an object");
        }
        input.fieldNames().forEachRemaining(name -> {
            if (!INPUT_KEYS.contains(name)) {
                throw bad("unsupported input field: " + name);
            }
        });
        String pipelineId = requireText(input, "pipelineId");
        String docId = requireText(input, "docId");
        String kbId = requireText(input, "kbId");
        String uploadId = requireText(input, "uploadId");
        if (!PIPELINE_ID.matcher(pipelineId).matches()) {
            throw bad("invalid pipelineId");
        }
        if (!ID.matcher(docId).matches() || !ID.matcher(kbId).matches() || !ID.matcher(uploadId).matches()) {
            throw bad("invalid resource id");
        }
        requireResourceRefs(request, kbId, docId);

        IngestionPipelineReader.PipelineSnapshot snapshot = pipelines.load(tenantId, pipelineId)
                .orElseThrow(() -> new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        String structureError = pipelines.structureError(snapshot);
        if (structureError != null) {
            throw bad(structureError);
        }

        if (request.retryOf() == null) {
            validateSource(tenantId, docId, kbId, uploadId);
            return;
        }
        validateRetry(principal, request, docId, uploadId, snapshot);
    }

    @Override
    public void onAdmitted(ExecutionPrincipal principal, String runId, AdmissionRequest request) {
        String tenantId = principal.tenantId();
        JsonNode input = request.input();
        String pipelineId = input.path("pipelineId").asText("");
        String docId = input.path("docId").asText("");
        String kbId = input.path("kbId").asText("");
        String uploadId = input.path("uploadId").asText("");
        String retryNodeId = input.path("retryNodeId").asText(null);
        String retryOf = request.retryOf();

        DocumentPort.UploadRow upload = documents.findUpload(tenantId, uploadId)
                .orElseThrow(() -> new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));

        String versionId;
        if (retryOf == null) {
            versionId = documents.findVersionByUpload(tenantId, docId, uploadId)
                    .orElseThrow(() -> new RunApiException(RunErrorCode.INTERNAL_ERROR, "document version missing"))
                    .versionId();
        } else {
            versionId = "ver-" + UUID.randomUUID().toString().replace("-", "");
            documents.insertVersion(tenantId, versionId, docId, uploadId, runId);
        }

        IngestionPipelineReader.PipelineSnapshot snapshot = pipelines.load(tenantId, pipelineId)
                .orElseThrow(() -> new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("runId", runId);
        metadata.put("pipelineId", pipelineId);
        metadata.put("docId", docId);
        metadata.put("kbId", kbId);
        metadata.put("uploadId", uploadId);
        metadata.put("versionId", versionId);
        metadata.put("pipelineHash", snapshot.hash());
        metadata.put("sourceSha256", upload.sha256());
        if (retryOf != null) {
            metadata.put("retryOf", retryOf);
            metadata.put("retryNodeId", retryNodeId);
        }
        tasks.createTask(tenantId, runId, pipelineId, "file", upload.objectKey(), upload.filename(),
                "pending", toJson(metadata), principal.userId());

        int order = 0;
        for (IngestionPipelineReader.NodeSnapshot node : snapshot.enabledChain()) {
            tasks.upsertNode(tenantId, runId, pipelineId, node.nodeId(), node.nodeType(), order++,
                    "pending", 0L, null, null, null);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void validateSource(String tenantId, String docId, String kbId, String uploadId) {
        DocumentPort.DocumentRow document = documents.findDocument(tenantId, docId)
                .orElseThrow(() -> new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        if (document.tombstoned() || !kbId.equals(document.kbId())) {
            throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        }
        DocumentPort.UploadRow upload = documents.findUpload(tenantId, uploadId)
                .orElseThrow(() -> new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        if (!docId.equals(upload.docId()) || !kbId.equals(upload.kbId()) || !"STORED".equals(upload.state())) {
            throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        }
        DocumentPort.VersionRow version = documents.findVersionByUpload(tenantId, docId, uploadId)
                .orElseThrow(() -> new RunApiException(RunErrorCode.INTERNAL_ERROR, "document version missing"));
        if ("TOMBSTONED".equals(version.state())) {
            throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        }
        if ("PUBLISHED".equals(version.state())) {
            throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT, "document version already published");
        }
    }

    private void validateRetry(ExecutionPrincipal principal, AdmissionRequest request,
                               String docId, String uploadId, IngestionPipelineReader.PipelineSnapshot snapshot) {
        String tenantId = principal.tenantId();
        String retryNodeId = request.input().path("retryNodeId").asText("");
        if (retryNodeId.isBlank()) {
            throw bad("retryNodeId is required for a retry run");
        }
        List<IngestionPipelineReader.NodeSnapshot> chain = snapshot.enabledChain();
        int index = -1;
        for (int i = 0; i < chain.size(); i++) {
            if (chain.get(i).nodeId().equals(retryNodeId)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            throw bad("retryNodeId is not part of the pipeline");
        }
        IngestionTaskRepository.TaskRow parent = tasks.findTask(tenantId, request.retryOf())
                .orElseThrow(() -> new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        if (!parent.id().equals(request.retryOf())) {
            throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        }
        Map<String, Object> metadata = parseMetadata(parent.metadataJson());
        String parentVersionId = (String) metadata.get("versionId");
        if (parentVersionId == null) {
            throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        }
        DocumentPort.VersionRow parentVersion = documents.findVersion(tenantId, parentVersionId)
                .orElseThrow(() -> new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        if ("PUBLISHED".equals(parentVersion.state()) || "TOMBSTONED".equals(parentVersion.state())) {
            throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT, "parent run version cannot be retried");
        }
        if (usage.usageOf(tenantId, request.retryOf()).pendingReconciliation() > 0) {
            throw new RunApiException(RunErrorCode.RECONCILIATION_REQUIRED,
                    "parent run has unresolved provider usage");
        }
        IngestionTaskRepository.NodeRow node = tasks.findNode(tenantId, request.retryOf(), retryNodeId)
                .orElseThrow(() -> new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        if (!Set.of("failed", "pending").contains(node.status())) {
            throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT,
                    "retry node is not a failed or pending node");
        }
        // 前驱必须全部成功：仅从被选节点的直接前驱 COMPLETED 检查点恢复
        for (int i = 0; i < index; i++) {
            IngestionTaskRepository.NodeRow predecessor =
                    tasks.findNode(tenantId, request.retryOf(), chain.get(i).nodeId()).orElse(null);
            if (predecessor == null || !"success".equals(predecessor.status())) {
                throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT,
                        "predecessor node is not completed: " + chain.get(i).nodeId());
            }
        }
        // 父任务必须属于同一管线/源，避免跨管线/跨源重试
        if (!snapshot.pipelineId().equals(metadata.get("pipelineId"))
                || !docId.equals(metadata.get("docId")) || !uploadId.equals(metadata.get("uploadId"))) {
            throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT, "retry must reuse the parent pipeline and source");
        }
    }

    private static void requireResourceRefs(AdmissionRequest request, String kbId, String docId) {
        List<AdmissionRequest.ResourceRef> refs = request.resourceRefs();
        if (refs == null || refs.size() != 2) {
            throw bad("resourceRefs must be exactly the knowledge_base and document");
        }
        boolean kb = refs.stream().anyMatch(r -> "knowledge_base".equals(r.type()) && kbId.equals(r.id()));
        boolean doc = refs.stream().anyMatch(r -> "document".equals(r.type()) && docId.equals(r.id()));
        if (!kb || !doc) {
            throw bad("resourceRefs must match the submitted knowledge base and document");
        }
    }

    private static String requireText(JsonNode input, String field) {
        String value = input.path(field).asText("");
        if (value.isBlank()) {
            throw bad(field + " is required");
        }
        return value;
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String toJson(Object value) {
        try {
            return CanonicalJson.strictMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new RunApiException(RunErrorCode.INTERNAL_ERROR, "metadata encoding failed");
        }
    }

    private static RunApiException bad(String message) {
        return new RunApiException(RunErrorCode.BAD_REQUEST, message);
    }
}
