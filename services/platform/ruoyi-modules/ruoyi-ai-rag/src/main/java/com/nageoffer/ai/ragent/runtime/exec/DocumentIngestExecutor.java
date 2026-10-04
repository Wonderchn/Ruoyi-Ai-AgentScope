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
    private static final int EMBED_BATCH = 10;

    private final DocumentDao documentDao;
    private final PrivateObjectStore objectStore;
    private final ObjectProvider<LocalMinerUClient> mineru;
    private final MarkdownChunker chunker;
    private final EmbeddingGateway embeddingGateway;
    private final UsageLedgerService usageLedger;
    private final ObjectProvider<P2FaultInjector> faultInjector;
    private final ObjectMapper objectMapper;
    @org.springframework.beans.factory.annotation.Autowired
    private com.nageoffer.ai.ragent.runtime.RunAccessService access;
    @org.springframework.beans.factory.annotation.Autowired
    private ProviderCallBoundary providerBoundary;

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
        var version=documentDao.findVersion(execution.tenantId(),versionId).orElse(null);
        if (document == null || upload == null || version==null || document.tombstoned()
                || !document.kbId().equals(kbId) || !upload.docId().equals(docId) || !upload.kbId().equals(kbId)
                || !version.docId().equals(docId) || !version.uploadId().equals(uploadId)) {
            return Outcome.failed("DOCUMENT_UNAVAILABLE");
        }
        access.current(execution.run(),java.util.Set.of("kb.read","document.read"));

        if(!documentDao.matchesEmbeddingModel(execution.tenantId(),java.util.List.of(kbId),embeddingGateway.model())
                || version.embeddingModel()!=null && !version.embeddingModel().equals(embeddingGateway.model())) return Outcome.failed("MODEL_CONFIG_CHANGED");

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
            LocalMinerUClient.ParseResult result;
            try {
                var saved=completedStepRef(guard,"parse-job");
                LocalMinerUClient.ParseJob submitted;
                if(saved==null) {
                    fault(P2FaultInjector.MINERU_BEFORE_JOB);
                    byte[] bytes=objectStore.get(upload.objectKey());
                    submitted=client.submit(bytes,upload.filename(),upload.sha256());
                    guard.commitStep("parse-job","mineru job",toJson(Map.of("jobId",submitted.jobId(),"fileId",submitted.fileId())),null,null);
                    fault(P2FaultInjector.MINERU_AFTER_JOB_CREATE);
                } else {submitted=new LocalMinerUClient.ParseJob((String)saved.get("jobId"),(String)saved.get("fileId"));}
                result=client.awaitResult(submitted,600,()->{
                    access.current(execution.run(),java.util.Set.of("kb.read","document.read"));
                    if(!guard.stillOwned()) throw new com.nageoffer.ai.ragent.runtime.RunApiException(com.nageoffer.ai.ragent.runtime.RunErrorCode.VERSION_CONFLICT);
                    return guard.isCancelRequested();
                });
            } catch(LocalMinerUClient.ParseCancelledException e) {
                return new Outcome("CANCELLED",Map.of("at","parse"),null);
            } catch (LocalMinerUClient.JobLostException e) {
                // Durable job vanished. End this run honestly; a new run may repeat pure parsing.
                return Outcome.failed("MINERU_JOB_LOST");
            }
            access.current(execution.run(),java.util.Set.of("kb.read","document.read"));
            if(!guard.stillOwned()) throw new com.nageoffer.ai.ragent.runtime.RunApiException(com.nageoffer.ai.ragent.runtime.RunErrorCode.VERSION_CONFLICT);
            String parseKey = "tenants/" + execution.tenantId() + "/docs/" + docId + "/" + versionId + "/parsed-"+result.markdownSha256()+".md";
            objectStore.put(parseKey, result.markdown().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String structuredKey = "tenants/" + execution.tenantId() + "/docs/" + docId + "/" + versionId
                    + "/pages-" + result.structuredSha256() + ".json";
            objectStore.put(structuredKey, result.structuredContent().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            parseRef = new LinkedHashMap<>();
            parseRef.put("objectKey", parseKey);
            parseRef.put("hash", result.markdownSha256());
            parseRef.put("structuredObjectKey", structuredKey);
            parseRef.put("structuredHash", result.structuredSha256());
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
            drafts = chunkDrafts(docId, versionId, parseRef, markdown);
            if (drafts.isEmpty()) {
                return Outcome.failed("CHUNK_EMPTY");
            }
            Map<String, Object> ref = Map.of("strategy", parseRef.containsKey("structuredObjectKey")
                    ? MarkdownChunker.PAGE_STRATEGY : MarkdownChunker.STRATEGY, "chunks", drafts.size());
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
            drafts = chunkDrafts(docId, versionId, parseRef, markdown);
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
            access.current(execution.run(),java.util.Set.of("kb.read","document.read"));
            if(usageLedger.unresolved(execution.tenantId(),execution.runId(),stepId)) return new Outcome("NEEDS_RECONCILIATION",Map.of(),"MODEL_USAGE_UNKNOWN");
            var operation=providerBoundary.enter(execution);
            String callId;
            try {callId = guard.commitAtomic(()->usageLedger.startCall(execution.tenantId(), execution.runId(), execution.attempt(),
                    stepId, UsageLedgerService.KIND_EMBEDDING, embeddingGateway.provider(),
                    embeddingGateway.model(), null));} catch(RuntimeException ex){operation.close();throw ex;}
            EmbeddingGateway.EmbeddingResult embedded;
            try {
                embedded = embeddingGateway.embedBatchWithUsage(texts);
            } catch (RuntimeException e) {
                guard.commitAtomic(()->{usageLedger.markUnknown(execution.tenantId(),callId);return null;});
                throw e;
            }
            operation.close();
            Map<String, Object> usage = embedded.usageRaw();
            int batchIndex=batch;
            access.current(execution.run(),java.util.Set.of("kb.read","document.read"));
            guard.commitAtomic(()->{
            usageLedger.settle(execution.tenantId(),callId,embedded.providerRequestId(),usage);
            for (int i = 0; i < slice.size(); i++) {
                MarkdownChunker.ChunkDraft draft = slice.get(i);
                documentDao.insertStagingChunk(execution.tenantId(), versionId, draft.chunkKey(), draft.index(),
                        docId, kbId, draft.content(), draft.contentHash(), draft.charCount(),
                        draft.pageFrom(), draft.pageTo(), embedded.vectors().get(i), embeddingGateway.model());
                fault(P2FaultInjector.EMBEDDING_AFTER_STAGING_CHUNK);
            }
            Map<String, Object> ref = Map.of("batch", batchIndex, "chunks", slice.size(),
                    "model", embeddingGateway.model(), "dimension", embeddingGateway.dimension());
            guard.commitStep(stepId, "embedding", toJson(ref), null, toJson(usage));
            guard.appendEvent(RunEventAppender.EVENT_STEP_COMPLETED, Map.of(
                    "stepId", stepId, "state", "COMPLETED", "ref", ref, "usage", usage==null?Map.of("pending",true):usage,
                    "attemptId", "a-" + execution.attempt()));
            return null;
            });
        }
        documentDao.updateVersionStateFenced(execution.tenantId(), versionId, execution.runId(), execution.fence(),
                "READY_TO_PUBLISH", null, drafts.size(), embeddingGateway.model(), embeddingGateway.dimension());

        // ---------------- step: publish ----------------
        if (completedStepRef(guard, "publish") == null) {
            access.current(execution.run(),java.util.Set.of("kb.read","document.read"));
            if (guard.isCancelRequested()) {
                return new Outcome("CANCELLED", Map.of("at", "publish"), null);
            }
            DocumentDao.DocumentRow current = documentDao.findDocument(execution.tenantId(), docId).orElse(null);
            if (current == null || current.tombstoned()) {
                // tombstone 优先：旧任务不能复活
                return new Outcome("CANCELLED", Map.of("reason", "document tombstoned"), null);
            }
            boolean alreadyPublished=versionId.equals(current.publishedVersionId());
            long staged = documentDao.countChunks(execution.tenantId(), versionId, alreadyPublished?"PUBLISHED":"STAGING");
            if (staged == 0) {
                return Outcome.failed("PUBLISH_NO_CHUNKS");
            }
            fault(P2FaultInjector.PUBLISH_BEFORE_SWAP);
            boolean published = guard.commitAtomic(()->documentDao.publishVersionFenced(execution.tenantId(), docId, versionId,
                    execution.runId(), execution.fence()));
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

        guard.commitAtomic(()->{usageLedger.finalizeReservation(execution.tenantId(), execution.runId());return null;});
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("docId", docId);
        result.put("versionId", versionId);
        result.put("chunks", drafts.size());
        result.put("embeddingModel", embeddingGateway.model());
        result.put("dimension", embeddingGateway.dimension());
        result.put("mineruTier", parseRef.get("tier"));
        return Outcome.succeeded(result);
    }

    private List<MarkdownChunker.ChunkDraft> chunkDrafts(String docId, String versionId,
                                                       Map<String, Object> parseRef, String markdown) {
        if (!parseRef.containsKey("structuredObjectKey")) {
            return chunker.chunk(docId, versionId, markdown, MarkdownChunker.DEFAULT_MAX_CHARS);
        }
        String artifact = new String(objectStore.get((String) parseRef.get("structuredObjectKey")),
                java.nio.charset.StandardCharsets.UTF_8);
        if (!com.nageoffer.ai.ragent.runtime.CanonicalJson.sha256(artifact).equals(parseRef.get("structuredHash"))) {
            throw new IllegalStateException("structured parse artifact hash changed");
        }
        return chunker.chunkPages(docId, versionId,
                com.nageoffer.ai.ragent.ingest.MinerUPageContent.decode(artifact, objectMapper),
                MarkdownChunker.DEFAULT_MAX_CHARS);
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
