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

package com.nageoffer.ai.ragent.ingestion.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.ingestion.task.IngestionPipelineReader;
import com.nageoffer.ai.ragent.ingestion.task.IngestionRunContract;
import com.nageoffer.ai.ragent.ingestion.task.IngestionTaskRepository;
import com.nageoffer.ai.ragent.runtime.CanonicalJson;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.config.RunConfigBindingPort;
import com.nageoffer.ai.ragent.runtime.config.RuntimeModelGatewayPort;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.exec.ProviderCallBoundary;
import com.nageoffer.ai.ragent.runtime.exec.RunExecution;
import com.nageoffer.ai.ragent.runtime.exec.RunExecutionGuard;
import com.nageoffer.ai.ragent.runtime.exec.RunExecutor;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import com.nageoffer.ai.ragent.runtime.usage.UsageLedgerService;
import org.ruoyi.ai.api.runtime.ChunkingPort;
import org.ruoyi.ai.api.runtime.DocumentPort;
import org.ruoyi.ai.api.runtime.MinerUPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F06:1/2 canonical 摄取运行面执行器：单链逐节点（fetcher/parser/enhancer/chunker/enricher/indexer）
 * 映射冻结端口，取消检查点、预算口径、单节点重试前驱产物复用。
 *
 * <p>规范动作 {@code ingestion.run}；执行版本 {@link IngestionRunContract#EXECUTION_VERSION}。
 * 逐节点进度写 {@code ai_ingestion_task_node}，产物检查点写 {@code ai_run_step}（fence 受控、幂等）。
 */
@Component
@ConditionalOnProperty(name = "p2.executor.mode", havingValue = "real", matchIfMissing = true)
public class IngestionPipelineExecutor implements RunExecutor {

    private static final Logger log = LoggerFactory.getLogger(IngestionPipelineExecutor.class);

    private final IngestionPipelineReader pipelines;
    private final IngestionTaskRepository tasks;
    private final DocumentPort documents;
    private final IngestionStageRunner runner;
    private final UsageLedgerService usageLedger;
    private final ObjectMapper objectMapper;

    public IngestionPipelineExecutor(IngestionPipelineReader pipelines, IngestionTaskRepository tasks,
                                     DocumentPort documents,
                                     com.nageoffer.ai.ragent.ingest.PrivateObjectStore objectStore,
                                     ObjectProvider<MinerUPort> mineru, ChunkingPort chunker,
                                     com.nageoffer.ai.ragent.ingest.EmbeddingGateway embeddingGateway,
                                     RuntimeModelGatewayPort chat,
                                     ObjectProvider<RunConfigBindingPort> bindingPort,
                                     UsageLedgerService usageLedger, ProviderCallBoundary providerBoundary,
                                     com.nageoffer.ai.ragent.runtime.RunAccessService access,
                                     RunLedgerDao ledger, ObjectMapper objectMapper) {
        this.pipelines = pipelines;
        this.tasks = tasks;
        this.documents = documents;
        this.usageLedger = usageLedger;
        this.objectMapper = objectMapper;
        this.runner = new IngestionStageRunner(documents, objectStore, mineru, chunker, embeddingGateway, chat,
                bindingPort, usageLedger, providerBoundary, access, ledger, objectMapper);
    }

    @Override
    public String action() {
        return IngestionRunContract.ACTION;
    }

    private static final class State {
        Map<String, Object> parseRef;
        String text;
        List<ChunkingPort.ChunkDraft> drafts;
    }

    @Override
    public Outcome execute(RunExecution execution) {
        RunExecutionGuard guard = execution.guard();
        RunRecord run = execution.run();
        String tenantId = execution.tenantId();
        String runId = execution.runId();
        if (!IngestionRunContract.EXECUTION_VERSION.equals(run.executionVersion())) {
            return Outcome.failed("INGEST_CHECKPOINT_INCOMPATIBLE");
        }
        JsonNode input = CanonicalJson.inputOf(run.inputJson(), objectMapper);
        String pipelineId = input.path("pipelineId").asText("");
        String docId = input.path("docId").asText("");
        String kbId = input.path("kbId").asText("");
        String uploadId = input.path("uploadId").asText("");
        String retryNodeId = input.path("retryNodeId").asText(null);

        IngestionTaskRepository.TaskRow task = tasks.findTask(tenantId, runId).orElse(null);
        if (task == null) {
            return Outcome.failed("INGEST_TASK_MISSING");
        }
        Map<String, Object> metadata = parseMap(task.metadataJson());
        String versionId = (String) metadata.get("versionId");
        if (versionId == null || pipelineId.isBlank()) {
            return Outcome.failed("INGEST_TASK_MISSING");
        }
        IngestionPipelineReader.PipelineSnapshot snapshot;
        try {
            snapshot = pipelines.load(tenantId, pipelineId).orElse(null);
        } catch (RuntimeException e) {
            return Outcome.failed("INGEST_PIPELINE_INVALID");
        }
        if (snapshot == null) {
            return Outcome.failed("INGEST_PIPELINE_MISSING");
        }
        DocumentPort.DocumentRow document = documents.findDocument(tenantId, docId).orElse(null);
        DocumentPort.UploadRow upload = documents.findUpload(tenantId, uploadId).orElse(null);
        DocumentPort.VersionRow version = documents.findVersion(tenantId, versionId).orElse(null);
        if (document == null || upload == null || version == null || document.tombstoned()
                || !kbId.equals(document.kbId()) || !docId.equals(upload.docId()) || !kbId.equals(upload.kbId())
                || !docId.equals(version.docId())) {
            return failTask(tenantId, runId, "DOCUMENT_UNAVAILABLE");
        }
        com.nageoffer.ai.ragent.runtime.RunAccessService access = null;
        try {
            access = this.runnerAccess();
            access.current(run, Set.of("kb.read", "document.read"));
        } catch (RuntimeException e) {
            return failTask(tenantId, runId, "AUTHORIZATION_UNAVAILABLE");
        }

        JsonNode budget = parseJson(run.budgetJson());
        Integer maxToolCalls = budget != null && budget.hasNonNull("maxToolCalls")
                ? budget.get("maxToolCalls").asInt() : null;
        Integer maxSteps = budget != null && budget.hasNonNull("maxSteps") ? budget.get("maxSteps").asInt() : null;

        List<IngestionPipelineReader.NodeSnapshot> chain = snapshot.enabledChain();
        State state = new State();
        int start = 0;
        IngestionPipelineReader.NodeSnapshot currentNode = null;
        int currentNodeIndex = -1;
        long currentNodeBegan = 0L;
        try {
            if (retryNodeId != null && !retryNodeId.isBlank()) {
                int idx = indexOf(chain, retryNodeId);
                if (idx < 0) {
                    return failTask(tenantId, runId, "INGEST_RETRY_NODE_INVALID");
                }
                start = idx;
                String selectedType = chain.get(idx).nodeType();
                if (("enricher".equals(selectedType) || "indexer".equals(selectedType))) {
                    int chunkerIdx = indexOfType(chain, "chunker");
                    if (chunkerIdx >= 0) {
                        start = Math.min(start, chunkerIdx);
                    }
                }
                String parentRunId = (String) metadata.get("retryOf");
                hydrate(tenantId, parentRunId, chain, start, state);
                for (int j = 0; j < start; j++) {
                    IngestionPipelineReader.NodeSnapshot precedent = chain.get(j);
                    tasks.upsertNode(tenantId, runId, pipelineId, precedent.nodeId(), precedent.nodeType(), j,
                            "success", 0L, "reused from parent run", null, null);
                }
            }

            tasks.markRunning(tenantId, runId);
            IngestionStageRunner.StageContext ctx = new IngestionStageRunner.StageContext(tenantId, runId, docId,
                    kbId, uploadId, versionId, pipelineId, execution.attempt(), execution, snapshot);

            for (int i = start; i < chain.size(); i++) {
                IngestionPipelineReader.NodeSnapshot node = chain.get(i);
                if (guard.isCancelRequested()) {
                    tasks.markTerminal(tenantId, runId, "cancelled", null, null);
                    return new Outcome("CANCELLED", Map.of("at", node.nodeId()), null);
                }
                tasks.upsertNode(tenantId, runId, pipelineId, node.nodeId(), node.nodeType(), i,
                        "running", 0L, null, null, null);
                long began = System.currentTimeMillis();
                currentNode = node;
                currentNodeIndex = i;
                currentNodeBegan = began;
                try {
                    state = runNode(ctx, node, guard, upload, state, maxToolCalls, maxSteps);
                } catch (IngestionCancelled cancelled) {
                    tasks.upsertNode(tenantId, runId, pipelineId, node.nodeId(), node.nodeType(), i,
                            "failed", System.currentTimeMillis() - began, "cancelled", null, null);
                    tasks.markTerminal(tenantId, runId, "cancelled", null, null);
                    return new Outcome("CANCELLED", Map.of("at", node.nodeId()), null);
                }
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("phase", node.nodeType());
                output.put("ref", nodeRef(node, state));
                tasks.upsertNode(tenantId, runId, pipelineId, node.nodeId(), node.nodeType(), i,
                        "success", System.currentTimeMillis() - began, null, null, toJson(output));
            }
        } catch (IngestionCancelled cancelled) {
            tasks.markTerminal(tenantId, runId, "cancelled", null, null);
            return new Outcome("CANCELLED", Map.of(), null);
        } catch (RuntimeException e) {
            log.warn("ingestion run failed runId={} cause={}", runId, e.getMessage());
            if (currentNode != null) {
                tasks.upsertNode(tenantId, runId, pipelineId, currentNode.nodeId(), currentNode.nodeType(),
                        currentNodeIndex, "failed", System.currentTimeMillis() - currentNodeBegan, null, brief(e), null);
            }
            tasks.markTerminal(tenantId, runId, "failed", null, brief(e));
            throw e;
        }

        Integer chunkCount = state.drafts == null ? null : state.drafts.size();
        tasks.markTerminal(tenantId, runId, "completed", chunkCount, null);
        guard.commitAtomic(() -> {
            usageLedger.finalizeReservation(tenantId, runId);
            return null;
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("docId", docId);
        result.put("versionId", versionId);
        result.put("chunks", chunkCount);
        result.put("embeddingModel", runnerModel());
        return Outcome.succeeded(result);
    }

    private State runNode(IngestionStageRunner.StageContext ctx, IngestionPipelineReader.NodeSnapshot node,
                          RunExecutionGuard guard, DocumentPort.UploadRow upload, State state,
                          Integer maxToolCalls, Integer maxSteps) {
        switch (node.nodeType()) {
            case "fetcher" -> {
                runner.requireStepBudget(ctx.tenantId(), ctx.runId(), maxSteps);
                runner.fetch(ctx, node, guard);
            }
            case "parser" -> {
                runner.requireCallBudget(ctx.tenantId(), ctx.runId(), maxToolCalls);
                runner.requireStepBudget(ctx.tenantId(), ctx.runId(), maxSteps);
                Map<String, Object> ref = runner.parse(ctx, node, guard, upload);
                if (Boolean.TRUE.equals(ref.get("cancelled"))) {
                    throw new IngestionCancelled();
                }
                state.parseRef = ref;
                state.text = runner.readText((String) ref.get("objectKey"));
            }
            case "enhancer" -> {
                runner.requireCallBudget(ctx.tenantId(), ctx.runId(), maxToolCalls);
                state.text = runner.enhance(ctx, node, guard, state.text);
            }
            case "chunker" -> {
                runner.requireStepBudget(ctx.tenantId(), ctx.runId(), maxSteps);
                state.drafts = runner.chunk(ctx, node, guard,
                        state.parseRef == null ? Map.of() : state.parseRef, state.text);
            }
            case "enricher" -> {
                runner.requireCallBudget(ctx.tenantId(), ctx.runId(), maxToolCalls);
                runner.enrich(ctx, node, guard, state.drafts);
            }
            case "indexer" -> {
                runner.requireCallBudget(ctx.tenantId(), ctx.runId(), maxToolCalls);
                runner.requireStepBudget(ctx.tenantId(), ctx.runId(), maxSteps);
                runner.index(ctx, node, guard, state.drafts);
            }
            default -> throw new RunApiException(RunErrorCode.BAD_REQUEST, "unknown node type: " + node.nodeType());
        }
        return state;
    }

    /** 单节点重试：从父 run 的直接前驱已完成检查点恢复输入（不重调 provider；哈希校验）。 */
    private void hydrate(String tenantId, String parentRunId, List<IngestionPipelineReader.NodeSnapshot> chain,
                         int start, State state) {
        if (parentRunId == null) {
            throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT, "retry requires parent run id");
        }
        int parserIdx = indexOfType(chain, "parser");
        int enhancerIdx = indexOfType(chain, "enhancer");
        if (parserIdx >= 0 && parserIdx < start) {
            Map<String, Object> ref = runner.predecessorRef(tenantId, parentRunId,
                    chain.get(parserIdx).nodeId() + "-parse");
            if (ref == null || ref.get("objectKey") == null) {
                throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT, "missing predecessor parse checkpoint");
            }
            String markdown = runner.readText((String) ref.get("objectKey"));
            if (ref.get("hash") != null && !CanonicalJson.sha256(markdown).equals(ref.get("hash"))) {
                throw new RunApiException(RunErrorCode.RECONCILIATION_REQUIRED, "predecessor artifact hash mismatch");
            }
            state.parseRef = ref;
            state.text = markdown;
        }
        if (enhancerIdx >= 0 && enhancerIdx < start) {
            Map<String, Object> ref = runner.predecessorRef(tenantId, parentRunId,
                    chain.get(enhancerIdx).nodeId() + "-enhance");
            if (ref == null || ref.get("objectKey") == null) {
                throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT, "missing predecessor enhance checkpoint");
            }
            String text = runner.readText((String) ref.get("objectKey"));
            if (ref.get("hash") != null && !CanonicalJson.sha256(text).equals(ref.get("hash"))) {
                throw new RunApiException(RunErrorCode.RECONCILIATION_REQUIRED, "predecessor artifact hash mismatch");
            }
            state.text = text;
        }
    }

    private Object nodeRef(IngestionPipelineReader.NodeSnapshot node, State state) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("nodeId", node.nodeId());
        if (state.drafts != null) {
            ref.put("chunks", state.drafts.size());
        }
        return ref;
    }

    private com.nageoffer.ai.ragent.runtime.RunAccessService runnerAccess() {
        return runner.access();
    }

    private String runnerModel() {
        return runner.embeddingModel();
    }

    private Outcome failTask(String tenantId, String runId, String code) {
        tasks.markTerminal(tenantId, runId, "failed", null, code);
        return Outcome.failed(code);
    }

    private static int indexOf(List<IngestionPipelineReader.NodeSnapshot> chain, String nodeId) {
        for (int i = 0; i < chain.size(); i++) {
            if (chain.get(i).nodeId().equals(nodeId)) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfType(List<IngestionPipelineReader.NodeSnapshot> chain, String type) {
        for (int i = 0; i < chain.size(); i++) {
            if (chain.get(i).nodeType().equals(type)) {
                return i;
            }
        }
        return -1;
    }

    private Map<String, Object> parseMap(String json) {
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

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static String brief(RuntimeException e) {
        String message = e.getMessage();
        if (message == null) {
            return e.getClass().getSimpleName();
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
