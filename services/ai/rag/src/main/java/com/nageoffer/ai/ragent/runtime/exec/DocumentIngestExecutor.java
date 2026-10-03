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

package com.nageoffer.ai.ragent.runtime.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.ingest.DocumentDao;
import com.nageoffer.ai.ragent.ingest.EmbeddingGateway;
import com.nageoffer.ai.ragent.ingest.LocalMinerUClient;
import com.nageoffer.ai.ragent.ingest.MarkdownChunker;
import com.nageoffer.ai.ragent.ingest.PrivateObjectStore;
import com.nageoffer.ai.ragent.ingest.UploadService;
import com.nageoffer.ai.ragent.runtime.P2FaultInjector;
import com.nageoffer.ai.ragent.runtime.RunEventAppender;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.usage.UsageLedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档版本摄入执行器：解析（本地 MinerU）→ 分块 → 分批 embedding（检查点）→ 原子发布。
 *
 * <p>阶段持久化在 {@code ai_run_step}，批次失败可从已提交检查点继续，不重复发布/计量；
 * 发布前复核当前权限/版本/fence/tombstone；晚到结果不能覆盖新版本。
 */
@Component
@ConditionalOnProperty(name = "p2.executor.mode", havingValue = "real", matchIfMissing = true)
public class DocumentIngestExecutor implements RunExecutor {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestExecutor.class);
    private static final int EMBED_BATCH = 16;

    private final DocumentDao documentDao;
    private final PrivateObjectStore objectStore;
    private final ObjectProvider<LocalMinerUClient> mineru;
    private final MarkdownChunker chunker;
    private final EmbeddingGateway embeddingGateway;
    private final UsageLedgerService usageLedger;
    private final ObjectProvider<P2FaultInjector> faultInjector;
    private final ObjectMapper objectMapper;

    public DocumentIngestExecutor(DocumentDao documentDao, PrivateObjectStore objectStore,
                                  ObjectProvider<LocalMinerUClient> mineru, MarkdownChunker chunker,
                                  EmbeddingGateway embeddingGateway, UsageLedgerService usageLedger,
                                  ObjectProvider<P2FaultInjector> faultInjector, ObjectMapper objectMapper) {
        this.documentDao = documentDao;
        this.objectStore = objectStore;
        this.mineru = mineru;
        this.chunker = chunker;
        this.embeddingGateway = embeddingGateway;
        this.usageLedger = usageLedger;
        this.faultInjector = faultInjector;
        this.objectMapper = objectMapper;
    }

    @Override
    public String action() {
        return "document.ingest";
    }

    private void fault(String hook) {
        P2FaultInjector injector = faultInjector.getIfAvailable();
        if (injector != null) {
            injector.checkpoint(hook);
        }
    }

    @Override
    public Outcome execute(RunExecution execution) throws Exception {
        RunExecutionGuard guard = execution.guard();
        JsonNode input = UploadService.inputOf(execution.run().inputJson(), objectMapper);
        String docId = input.path("docId").asText("");
        String uploadId = input.path("uploadId").asText("");
        String versionId = input.path("versionId").asText("");
        String kbId = input.path("kbId").asText("");
        if (docId.isBlank() || uploadId.isBlank() || versionId.isBlank() || kbId.isBlank()) {
            return Outcome.failed("INGEST_INPUT_INVALID");
        }
        DocumentDao.DocumentRow document = documentDao.findDocument(execution.tenantId(), docId).orElse(null);
        DocumentDao.UploadRow upload = documentDao.findUpload(execution.tenantId(), uploadId).orElse(null);
        if (document == null || upload == null || document.tombstoned()) {
            return Outcome.failed("DOCUMENT_UNAVAILABLE");
        }

        // ---------------- step: parse ----------------
        Map<String, Object> parseRef = completedStepRef(guard, "parse");
        if (parseRef != null) {
            // 复用已完成解析产物
        } else {
            if (guard.isCancelRequested()) {
                return new Outcome("CANCELLED", Map.of("at", "parse"), null);
            }
            guard.appendEvent(RunEventAppender.EVENT_STEP_STARTED, Map.of(
                    "stepId", "parse", "stepName", "parse", "attemptId", "a-" + execution.attempt()));
            LocalMinerUClient client = mineru.getIfAvailable();
            if (client == null) {
                return Outcome.failed("MINERU_NOT_CONFIGURED");
            }
            if (!documentDao.updateVersionStateFenced(execution.tenantId(), versionId, execution.runId(),
                    execution.fence(), "PARSING", null, null, null, null)) {
                return Outcome.failed("VERSION_STATE_CONFLICT");
            }
            byte[] bytes = objectStore.get(upload.objectKey());
            LocalMinerUClient.ParseResult result;
            try {
                result = client.parse(bytes, upload.filename(), upload.sha256(), 600);
            } catch (LocalMinerUClient.JobLostException e) {
                // 服务重启丢任务索引：有完整持久产物则复用，否则本 attempt 明确失败并由上层重试新 attempt
                return Outcome.failed("MINERU_JOB_LOST");
            }
            String parseKey = "tenants/" + execution.tenantId() + "/docs/" + docId + "/" + versionId + "/parsed.md";
            objectStore.put(parseKey, result.markdown().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            parseRef = new LinkedHashMap<>();
            parseRef.put("objectKey", parseKey);
            parseRef.put("hash", result.markdownSha256());
            parseRef.put("jobId", result.jobId());
            parseRef.put("fileId", result.fileId());
            parseRef.put("tier", result.tier());
            parseRef.put("parserVersion", result.parserVersion());
            parseRef.put("durationMs", result.durationMs());
            parseRef.put("pageRange", result.pageRange());
            String refJson = toJson(parseRef);
            guard.commitStep("parse", "parse", refJson, result.markdownSha256(), toJson(Map.of("calls", 0)));
            guard.appendEvent(RunEventAppender.EVENT_STEP_COMPLETED, Map.of(
                    "stepId", "parse", "state", "COMPLETED", "ref", parseRef,
                    "attemptId", "a-" + execution.attempt()));
            documentDao.updateVersionStateFenced(execution.tenantId(), versionId, execution.runId(),
                    execution.fence(), "PARSED", refJson, null, null, null);
        }

        // ---------------- step: chunk ----------------
        List<MarkdownChunker.ChunkDraft> drafts = null;
        if (completedStepRef(guard, "chunk") != null) {
            // 复用已完成分块检查点
        } else {
            if (guard.isCancelRequested()) {
                return new Outcome("CANCELLED", Map.of("at", "chunk"), null);
            }
            guard.appendEvent(RunEventAppender.EVENT_STEP_STARTED, Map.of(
                    "stepId", "chunk", "stepName", "chunk", "attemptId", "a-" + execution.attempt()));
            String markdown = new String(objectStore.get((String) parseRef.get("objectKey")),
                    java.nio.charset.StandardCharsets.UTF_8);
            drafts = chunker.chunk(docId, versionId, markdown, MarkdownChunker.DEFAULT_MAX_CHARS);
            if (drafts.isEmpty()) {
                return Outcome.failed("CHUNK_EMPTY");
            }
            Map<String, Object> ref = Map.of("strategy", MarkdownChunker.STRATEGY, "chunks", drafts.size());
            guard.commitStep("chunk", "chunk", toJson(ref), null, toJson(Map.of("calls", 0)));
            guard.appendEvent(RunEventAppender.EVENT_STEP_COMPLETED, Map.of(
                    "stepId", "chunk", "state", "COMPLETED", "ref", ref, "attemptId", "a-" + execution.attempt()));
            documentDao.updateVersionStateFenced(execution.tenantId(), versionId, execution.runId(),
                    execution.fence(), "CHUNKING", null, drafts.size(), null, null);
        }
        if (drafts == null) {
            // 检查点复用：重新分块以得到本 attempt 的确定性 draft 列表（同键同内容，不重复发布）
            String markdown = new String(objectStore.get((String) parseRef.get("objectKey")),
                    java.nio.charset.StandardCharsets.UTF_8);
            drafts = chunker.chunk(docId, versionId, markdown, MarkdownChunker.DEFAULT_MAX_CHARS);
        }

        // ---------------- steps: embed batches ----------------
        int batches = (drafts.size() + EMBED_BATCH - 1) / EMBED_BATCH;
        for (int batch = 0; batch < batches; batch++) {
            String stepId = "embed-" + batch;
            if (completedStepRef(guard, stepId) != null) {
                continue;
            }
            if (guard.isCancelRequested()) {
                return new Outcome("CANCELLED", Map.of("at", stepId), null);
            }
            guard.appendEvent(RunEventAppender.EVENT_STEP_STARTED, Map.of(
                    "stepId", stepId, "stepName", "embedding", "attemptId", "a-" + execution.attempt()));
            int from = batch * EMBED_BATCH;
            int to = Math.min(drafts.size(), from + EMBED_BATCH);
            List<MarkdownChunker.ChunkDraft> slice = drafts.subList(from, to);
            List<String> texts = new ArrayList<>();
            for (MarkdownChunker.ChunkDraft draft : slice) {
                texts.add(draft.content());
            }
            String callId = usageLedger.startCall(execution.tenantId(), execution.runId(), execution.attempt(),
                    stepId, UsageLedgerService.KIND_EMBEDDING, embeddingGateway.provider(),
                    embeddingGateway.model(), null);
            List<List<Float>> vectors;
            try {
                vectors = embeddingGateway.embedBatch(texts);
            } catch (RuntimeException e) {
                usageLedger.markFailed(execution.tenantId(), callId, e.getClass().getSimpleName());
                throw e;
            }
            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("batchSize", texts.size());
            if ("synthetic".equals(embeddingGateway.provider())) {
                usage.put("synthetic", true);
                usageLedger.settle(execution.tenantId(), callId, null, usage);
            } else {
                // 真实提供方 usage 未知：保持待核对，不按 0 结算
                usageLedger.settle(execution.tenantId(), callId, null, null);
            }
            for (int i = 0; i < slice.size(); i++) {
                MarkdownChunker.ChunkDraft draft = slice.get(i);
                documentDao.insertStagingChunk(execution.tenantId(), versionId, draft.chunkKey(), draft.index(),
                        docId, kbId, draft.content(), draft.contentHash(), draft.charCount(),
                        draft.pageFrom(), draft.pageTo(), vectors.get(i), embeddingGateway.model());
            }
            Map<String, Object> ref = Map.of("batch", batch, "chunks", slice.size(),
                    "model", embeddingGateway.model(), "dimension", embeddingGateway.dimension());
            guard.commitStep(stepId, "embedding", toJson(ref), null, toJson(usage));
            guard.appendEvent(RunEventAppender.EVENT_STEP_COMPLETED, Map.of(
                    "stepId", stepId, "state", "COMPLETED", "ref", ref, "usage", usage,
                    "attemptId", "a-" + execution.attempt()));
        }
        documentDao.updateVersionStateFenced(execution.tenantId(), versionId, execution.runId(), execution.fence(),
                "READY_TO_PUBLISH", null, drafts.size(), embeddingGateway.model(), embeddingGateway.dimension());

        // ---------------- step: publish ----------------
        if (completedStepRef(guard, "publish") == null) {
            if (guard.isCancelRequested()) {
                return new Outcome("CANCELLED", Map.of("at", "publish"), null);
            }
            DocumentDao.DocumentRow current = documentDao.findDocument(execution.tenantId(), docId).orElse(null);
            if (current == null || current.tombstoned()) {
                // tombstone 优先：旧任务不能复活
                return new Outcome("CANCELLED", Map.of("reason", "document tombstoned"), null);
            }
            long staged = documentDao.countChunks(execution.tenantId(), versionId, "STAGING");
            if (staged == 0) {
                return Outcome.failed("PUBLISH_NO_CHUNKS");
            }
            fault(P2FaultInjector.PUBLISH_BEFORE_SWAP);
            boolean published = documentDao.publishVersionFenced(execution.tenantId(), docId, versionId,
                    execution.runId(), execution.fence());
            if (!published) {
                return Outcome.failed("PUBLISH_REJECTED");
            }
            fault(P2FaultInjector.PUBLISH_AFTER_SWAP);
            Map<String, Object> ref = Map.of("versionId", versionId, "chunks", staged,
                    "documentId", docId);
            guard.commitStep("publish", "publish", toJson(ref), null, toJson(Map.of("calls", 0)));
            guard.appendEvent(RunEventAppender.EVENT_STEP_COMPLETED, Map.of(
                    "stepId", "publish", "state", "COMPLETED", "ref", ref,
                    "attemptId", "a-" + execution.attempt()));
        }

        usageLedger.finalizeReservation(execution.tenantId(), execution.runId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("docId", docId);
        result.put("versionId", versionId);
        result.put("chunks", drafts.size());
        result.put("embeddingModel", embeddingGateway.model());
        result.put("dimension", embeddingGateway.dimension());
        result.put("mineruTier", parseRef.get("tier"));
        return Outcome.succeeded(result);
    }

    private Map<String, Object> completedStepRef(RunExecutionGuard guard, String stepId) {
        return guard.findStep(stepId)
                .filter(step -> "COMPLETED".equals(step.state()))
                .map(step -> {
                    try {
                        return objectMapper.readValue(step.refJson() == null ? "{}" : step.refJson(),
                                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                                });
                    } catch (Exception e) {
                        throw new IllegalStateException("step ref is invalid", e);
                    }
                })
                .orElse(null);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("json encoding failed", e);
        }
    }
}
