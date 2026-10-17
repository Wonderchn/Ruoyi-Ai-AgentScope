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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.ingestion.domain.enums.ChunkEnrichType;
import com.nageoffer.ai.ragent.ingestion.domain.enums.EnhanceType;
import com.nageoffer.ai.ragent.ingestion.domain.settings.ChunkerSettings;
import com.nageoffer.ai.ragent.ingestion.domain.settings.EnhancerSettings;
import com.nageoffer.ai.ragent.ingestion.domain.settings.EnricherSettings;
import com.nageoffer.ai.ragent.ingestion.domain.settings.IndexerSettings;
import com.nageoffer.ai.ragent.ingestion.task.IngestionPipelineReader;
import com.nageoffer.ai.ragent.ingestion.util.PromptTemplateRenderer;
import com.nageoffer.ai.ragent.runtime.CanonicalJson;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.RunEventAppender;
import com.nageoffer.ai.ragent.runtime.config.RunConfigBinding;
import com.nageoffer.ai.ragent.runtime.config.RuntimeModelGatewayPort;
import com.nageoffer.ai.ragent.runtime.exec.RunExecution;
import com.nageoffer.ai.ragent.runtime.exec.RunExecutionGuard;
import com.nageoffer.ai.ragent.runtime.usage.UsageLedgerService;
import org.ruoyi.ai.api.runtime.ChatPort;
import org.ruoyi.ai.api.runtime.ChunkingPort;
import org.ruoyi.ai.api.runtime.DocumentPort;
import org.ruoyi.ai.api.runtime.MinerUPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F06:1/2 canonical 摄取运行面的**逐节点 stage 实现**（由 {@link IngestionPipelineExecutor} 构造，
 * 非独立 bean）。把冻结端口（DocumentPort/MinerUPort/ChunkingPort/EmbeddingGateway/RunScopedChatPort）
 * 映射到六种节点类型；外部调用经 {@code ProviderCallBoundary}，用量落 {@code UsageLedgerService}，
 * 产物写私有存储并在 {@code ai_run_step} 提交检查点（幂等、fence 受控）。
 */
class IngestionStageRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestionStageRunner.class);
    static final int EMBED_BATCH = 10;
    static final int MAX_CHARS_CAP = 8000;
    static final int PARSE_DEADLINE_SECONDS = 600;
    static final int CHAT_MAX_TOKENS = 2048;

    /** 一次执行的固定上下文（run/源/版本/管线），节点间共享。 */
    record StageContext(String tenantId, String runId, String docId, String kbId, String uploadId,
                        String versionId, String pipelineId, int attempt, RunExecution execution,
                        IngestionPipelineReader.PipelineSnapshot snapshot) {
    }

    /** 运行期产物引用（私有存储 key + sha + 结构化摘要）。 */
    record Artifact(String objectKey, String sha256, int size, Map<String, Object> extra) {
    }

    private final DocumentPort documents;
    private final com.nageoffer.ai.ragent.ingest.PrivateObjectStore objectStore;
    private final org.springframework.beans.factory.ObjectProvider<MinerUPort> mineru;
    private final ChunkingPort chunker;
    private final com.nageoffer.ai.ragent.ingest.EmbeddingGateway embeddingGateway;
    private final RuntimeModelGatewayPort chat;
    private final org.springframework.beans.factory.ObjectProvider<
            com.nageoffer.ai.ragent.runtime.config.RunConfigBindingPort> bindingPort;
    private final UsageLedgerService usageLedger;
    private final com.nageoffer.ai.ragent.runtime.exec.ProviderCallBoundary providerBoundary;
    private final com.nageoffer.ai.ragent.runtime.RunAccessService access;
    private final com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao ledger;
    private final ObjectMapper objectMapper;

    IngestionStageRunner(DocumentPort documents, com.nageoffer.ai.ragent.ingest.PrivateObjectStore objectStore,
                         org.springframework.beans.factory.ObjectProvider<MinerUPort> mineru, ChunkingPort chunker,
                         com.nageoffer.ai.ragent.ingest.EmbeddingGateway embeddingGateway, RuntimeModelGatewayPort chat,
                         org.springframework.beans.factory.ObjectProvider<
                                 com.nageoffer.ai.ragent.runtime.config.RunConfigBindingPort> bindingPort,
                         UsageLedgerService usageLedger,
                         com.nageoffer.ai.ragent.runtime.exec.ProviderCallBoundary providerBoundary,
                         com.nageoffer.ai.ragent.runtime.RunAccessService access,
                         com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao ledger, ObjectMapper objectMapper) {
        this.documents = documents;
        this.objectStore = objectStore;
        this.mineru = mineru;
        this.chunker = chunker;
        this.embeddingGateway = embeddingGateway;
        this.chat = chat;
        this.bindingPort = bindingPort;
        this.usageLedger = usageLedger;
        this.providerBoundary = providerBoundary;
        this.access = access;
        this.ledger = ledger;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ stages

    /** fetch：无外部调用；校验受理固定的私有上传件为权威源并提交检查点。 */
    Map<String, Object> fetch(StageContext ctx, IngestionPipelineReader.NodeSnapshot node, RunExecutionGuard guard) {
        DocumentPort.UploadRow upload = documents.findUpload(ctx.tenantId(), ctx.uploadId())
                .orElseThrow(() -> new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        if (!ctx.docId().equals(upload.docId()) || !"STORED".equals(upload.state())
                || !objectStore.exists(upload.objectKey())) {
            throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        }
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("uploadId", upload.uploadId());
        ref.put("docId", ctx.docId());
        ref.put("objectKey", upload.objectKey());
        ref.put("sha256", upload.sha256());
        commit(guard, phase(node, "fetch"), "source registration", ref, upload.sha256(), Map.of("calls", 0));
        return ref;
    }

    /** parser：MinerU submit/awaitResult（取消回调），产 markdown+structured 产物并提交检查点。 */
    Map<String, Object> parse(StageContext ctx, IngestionPipelineReader.NodeSnapshot node, RunExecutionGuard guard,
                              DocumentPort.UploadRow upload) {
        MinerUPort client = mineru.getIfAvailable();
        if (client == null) {
            throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE, "mineru not configured");
        }
        access.current(ctx.execution().run(), java.util.Set.of("kb.read", "document.read"));
        if (!documents.updateVersionStateFenced(ctx.tenantId(), ctx.versionId(), ctx.runId(), ctx.execution().fence(),
                "PARSING", null, null, null, null)) {
            throw new RunApiException(RunErrorCode.VERSION_CONFLICT, "version state conflict");
        }
        MinerUPort.ParseResult result;
        try {
            String jobStep = phase(node, "parse-job");
            Map<String, Object> saved = completedRef(guard, jobStep);
            MinerUPort.ParseJob submitted;
            if (saved == null) {
                byte[] bytes = objectStore.get(upload.objectKey());
                submitted = client.submit(bytes, upload.filename(), upload.sha256());
                commit(guard, jobStep, "mineru job",
                        Map.of("jobId", submitted.jobId(), "fileId", submitted.fileId()), null, Map.of("calls", 0));
            } else {
                submitted = new MinerUPort.ParseJob((String) saved.get("jobId"), (String) saved.get("fileId"));
            }
            result = client.awaitResult(submitted, PARSE_DEADLINE_SECONDS, () -> {
                access.current(ctx.execution().run(), java.util.Set.of("kb.read", "document.read"));
                if (!guard.stillOwned()) {
                    throw new RunApiException(RunErrorCode.VERSION_CONFLICT);
                }
                return guard.isCancelRequested();
            });
        } catch (MinerUPort.ParseCancelledException e) {
            return Map.of("cancelled", true);
        } catch (MinerUPort.JobLostException e) {
            throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE, "mineru job lost");
        }
        access.current(ctx.execution().run(), java.util.Set.of("kb.read", "document.read"));
        if (!guard.stillOwned()) {
            throw new RunApiException(RunErrorCode.VERSION_CONFLICT);
        }
        String parseKey = "tenants/" + ctx.tenantId() + "/docs/" + ctx.docId() + "/" + ctx.versionId()
                + "/parsed-" + result.markdownSha256() + ".md";
        objectStore.put(parseKey, result.markdown().getBytes(StandardCharsets.UTF_8));
        String structuredKey = "tenants/" + ctx.tenantId() + "/docs/" + ctx.docId() + "/" + ctx.versionId()
                + "/pages-" + result.structuredSha256() + ".json";
        objectStore.put(structuredKey, result.structuredContent().getBytes(StandardCharsets.UTF_8));
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("objectKey", parseKey);
        ref.put("hash", result.markdownSha256());
        ref.put("structuredObjectKey", structuredKey);
        ref.put("structuredHash", result.structuredSha256());
        ref.put("jobId", result.jobId());
        ref.put("tier", result.tier());
        ref.put("parserVersion", result.parserVersion());
        ref.put("pageRange", result.pageRange());
        commit(guard, phase(node, "parse"), "parse", ref, result.markdownSha256(), Map.of("calls", 0));
        documents.updateVersionStateFenced(ctx.tenantId(), ctx.versionId(), ctx.runId(), ctx.execution().fence(),
                "PARSED", toJson(ref), null, null, null);
        return ref;
    }

    /**
     * enhancer：按 {@code tasks[]} 逐条 RunScopedChatPort 调用；{@code CONTEXT_ENHANCE} 产出替换文本，
     * 其余类型结果落 ref（canonical 不写向量库 metadata 列）。
     */
    String enhance(StageContext ctx, IngestionPipelineReader.NodeSnapshot node, RunExecutionGuard guard, String text) {
        EnhancerSettings settings = settings(node, EnhancerSettings.class);
        if (settings == null || settings.getTasks() == null || settings.getTasks().isEmpty()) {
            return text;
        }
        RunConfigBinding binding = binding(ctx);
        requireModelMatches(settings.getModelId(), binding);
        String current = text;
        Map<String, Object> outputs = new LinkedHashMap<>();
        int calls = 0;
        for (EnhancerSettings.EnhanceTask task : settings.getTasks()) {
            if (task == null || task.getType() == null) {
                throw new RunApiException(RunErrorCode.BAD_REQUEST, "enhancer task type is required");
            }
            String system = task.getSystemPrompt() != null && !task.getSystemPrompt().isBlank()
                    ? task.getSystemPrompt() : com.nageoffer.ai.ragent.ingestion.prompt.EnhancerPromptManager
                    .systemPrompt(task.getType());
            String user = task.getUserPromptTemplate() != null && !task.getUserPromptTemplate().isBlank()
                    ? PromptTemplateRenderer.render(task.getUserPromptTemplate(), Map.of("text", current, "content", current))
                    : current;
            String stepId = phase(node, "enhance-" + task.getType().getValue());
            String out = chatCall(ctx, guard, stepId, binding, system, user);
            calls++;
            outputs.put(task.getType().getValue(), out);
            if (task.getType() == EnhanceType.CONTEXT_ENHANCE && out != null && !out.isBlank()) {
                current = out;
            }
        }
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("model", binding.modelId());
        ref.put("calls", calls);
        ref.put("outputs", outputs);
        byte[] bytes = current.getBytes(StandardCharsets.UTF_8);
        String hash = CanonicalJson.sha256(current);
        String key = "tenants/" + ctx.tenantId() + "/docs/" + ctx.docId() + "/" + ctx.versionId()
                + "/enhance-" + hash + ".md";
        objectStore.put(key, bytes);
        ref.put("objectKey", key);
        ref.put("hash", hash);
        commit(guard, phase(node, "enhance"), "enhance", ref, hash, Map.of("calls", calls));
        return current;
    }

    /** chunker：canonical 端口（块互斥），{@code chunkSize} 映射到 maxChars；其余旧调参字段不解释。 */
    List<ChunkingPort.ChunkDraft> chunk(StageContext ctx, IngestionPipelineReader.NodeSnapshot node,
                                        RunExecutionGuard guard, Map<String, Object> parseRef, String text) {
        ChunkerSettings settings = settings(node, ChunkerSettings.class);
        int maxChars = maxChars(settings);
        List<ChunkingPort.ChunkDraft> drafts;
        if (parseRef.containsKey("structuredObjectKey")) {
            String artifact = new String(objectStore.get((String) parseRef.get("structuredObjectKey")),
                    StandardCharsets.UTF_8);
            if (!CanonicalJson.sha256(artifact).equals(parseRef.get("structuredHash"))) {
                throw new IllegalStateException("structured parse artifact hash changed");
            }
            drafts = chunker.chunkArtifact(ctx.docId(), ctx.versionId(), artifact, maxChars);
        } else {
            drafts = chunker.chunk(ctx.docId(), ctx.versionId(), text, maxChars);
        }
        if (drafts.isEmpty()) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "chunk empty");
        }
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("strategy", parseRef.containsKey("structuredObjectKey")
                ? ChunkingPort.PAGE_STRATEGY : ChunkingPort.STRATEGY);
        ref.put("maxChars", maxChars);
        ref.put("chunks", drafts.size());
        commit(guard, phase(node, "chunk"), "chunk", ref, null, Map.of("calls", 0));
        documents.updateVersionStateFenced(ctx.tenantId(), ctx.versionId(), ctx.runId(), ctx.execution().fence(),
                "CHUNKING", null, drafts.size(), null, null);
        return drafts;
    }

    /**
     * enricher：逐分块逐任务 RunScopedChatPort 调用，产出富集产物（内容+extras）；canonical 不写
     * 向量库 metadata 列，故 extras 只落私有产物与 ref（内容保持不变）。
     */
    Map<String, Object> enrich(StageContext ctx, IngestionPipelineReader.NodeSnapshot node, RunExecutionGuard guard,
                               List<ChunkingPort.ChunkDraft> drafts) {
        EnricherSettings settings = settings(node, EnricherSettings.class);
        if (settings == null || settings.getTasks() == null || settings.getTasks().isEmpty()) {
            return Map.of("chunks", drafts.size(), "calls", 0);
        }
        RunConfigBinding binding = binding(ctx);
        requireModelMatches(settings.getModelId(), binding);
        List<Map<String, Object>> enriched = new ArrayList<>();
        int calls = 0;
        for (ChunkingPort.ChunkDraft draft : drafts) {
            Map<String, Object> extras = new LinkedHashMap<>();
            for (EnricherSettings.ChunkEnrichTask task : settings.getTasks()) {
                if (task == null || task.getType() == null) {
                    throw new RunApiException(RunErrorCode.BAD_REQUEST, "enricher task type is required");
                }
                String system = task.getSystemPrompt() != null && !task.getSystemPrompt().isBlank()
                        ? task.getSystemPrompt() : com.nageoffer.ai.ragent.ingestion.prompt.EnricherPromptManager
                        .systemPrompt(task.getType());
                String user = task.getUserPromptTemplate() != null && !task.getUserPromptTemplate().isBlank()
                        ? PromptTemplateRenderer.render(task.getUserPromptTemplate(),
                        Map.of("text", draft.content(), "content", draft.content()))
                        : draft.content();
                String stepId = phase(node, "enrich-" + draft.index() + "-" + task.getType().getValue());
                extras.put(task.getType().getValue(), chatCall(ctx, guard, stepId, binding, system, user));
                calls++;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", draft.index());
            item.put("contentHash", draft.contentHash());
            item.put("extras", extras);
            enriched.add(item);
        }
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("model", binding.modelId());
        ref.put("calls", calls);
        ref.put("chunks", drafts.size());
        try {
            byte[] bytes = CanonicalJson.strictMapper().writeValueAsBytes(enriched);
            String hash = CanonicalJson.sha256(new String(bytes, StandardCharsets.UTF_8));
            String key = "tenants/" + ctx.tenantId() + "/docs/" + ctx.docId() + "/" + ctx.versionId()
                    + "/enriched-" + hash + ".json";
            objectStore.put(key, bytes);
            ref.put("objectKey", key);
            ref.put("hash", hash);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("enrich artifact encoding failed", e);
        }
        commit(guard, phase(node, "enrich"), "enrich", ref, null, Map.of("calls", calls));
        return ref;
    }

    /** indexer：分批 embedding（含 provider 边界与结算）→ staging 写入 → READY_TO_PUBLISH → fenced 发布。 */
    void index(StageContext ctx, IngestionPipelineReader.NodeSnapshot node, RunExecutionGuard guard,
               List<ChunkingPort.ChunkDraft> drafts) {
        IndexerSettings settings = settings(node, IndexerSettings.class);
        if (settings != null && settings.getEmbeddingModel() != null && !settings.getEmbeddingModel().isBlank()
                && !settings.getEmbeddingModel().equals(embeddingGateway.model())) {
            throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT, "MODEL_CONFIG_CHANGED");
        }
        int batches = (drafts.size() + EMBED_BATCH - 1) / EMBED_BATCH;
        for (int batch = 0; batch < batches; batch++) {
            String stepId = phase(node, "embed-" + batch);
            if (completedRef(guard, stepId) != null) {
                continue;
            }
            if (guard.isCancelRequested()) {
                throw new IngestionCancelled();
            }
            guard.appendEvent(RunEventAppender.EVENT_STEP_STARTED,
                    Map.of("stepId", stepId, "stepName", "embedding", "attemptId", "a-" + ctx.attempt()));
            int from = batch * EMBED_BATCH;
            int to = Math.min(drafts.size(), from + EMBED_BATCH);
            List<ChunkingPort.ChunkDraft> slice = drafts.subList(from, to);
            List<String> texts = new ArrayList<>();
            for (ChunkingPort.ChunkDraft draft : slice) {
                texts.add(draft.content());
            }
            access.current(ctx.execution().run(), java.util.Set.of("kb.read", "document.read"));
            if (usageLedger.unresolved(ctx.tenantId(), ctx.runId(), stepId)) {
                throw new RunApiException(RunErrorCode.RECONCILIATION_REQUIRED, "MODEL_USAGE_UNKNOWN");
            }
            var operation = providerBoundary.enter(ctx.execution());
            String callId;
            try {
                callId = guard.commitAtomic(() -> usageLedger.startCall(ctx.tenantId(), ctx.runId(), ctx.attempt(),
                        stepId, UsageLedgerService.KIND_EMBEDDING, embeddingGateway.provider(),
                        embeddingGateway.model(), null));
            } catch (RuntimeException ex) {
                operation.close();
                throw ex;
            }
            com.nageoffer.ai.ragent.ingest.EmbeddingGateway.EmbeddingResult embedded;
            try {
                embedded = embeddingGateway.embedBatchWithUsage(texts);
            } catch (RuntimeException e) {
                guard.commitAtomic(() -> {
                    usageLedger.markUnknown(ctx.tenantId(), callId);
                    return null;
                });
                throw e;
            }
            operation.close();
            Map<String, Object> usage = embedded.usageRaw();
            int batchIndex = batch;
            access.current(ctx.execution().run(), java.util.Set.of("kb.read", "document.read"));
            guard.commitAtomic(() -> {
                usageLedger.settle(ctx.tenantId(), callId, embedded.providerRequestId(), usage);
                for (int i = 0; i < slice.size(); i++) {
                    ChunkingPort.ChunkDraft draft = slice.get(i);
                    documents.insertStagingChunk(ctx.tenantId(), ctx.versionId(), draft.chunkKey(), draft.index(),
                            ctx.docId(), ctx.kbId(), draft.content(), draft.contentHash(), draft.charCount(),
                            draft.pageFrom(), draft.pageTo(), embedded.vectors().get(i), embeddingGateway.model());
                }
                Map<String, Object> ref = Map.of("batch", batchIndex, "chunks", slice.size(),
                        "model", embeddingGateway.model(), "dimension", embeddingGateway.dimension());
                commit(guard, stepId, "embedding", ref, null, usage == null ? Map.of("pending", true) : usage);
                return null;
            });
        }
        documents.updateVersionStateFenced(ctx.tenantId(), ctx.versionId(), ctx.runId(), ctx.execution().fence(),
                "READY_TO_PUBLISH", null, drafts.size(), embeddingGateway.model(), embeddingGateway.dimension());

        if (completedRef(guard, phase(node, "publish")) == null) {
            access.current(ctx.execution().run(), java.util.Set.of("kb.read", "document.read"));
            if (guard.isCancelRequested()) {
                throw new IngestionCancelled();
            }
            DocumentPort.DocumentRow current = documents.findDocument(ctx.tenantId(), ctx.docId()).orElse(null);
            if (current == null || current.tombstoned()) {
                throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, "document tombstoned");
            }
            boolean alreadyPublished = ctx.versionId().equals(current.publishedVersionId());
            long staged = documents.countChunks(ctx.tenantId(), ctx.versionId(),
                    alreadyPublished ? "PUBLISHED" : "STAGING");
            if (staged == 0) {
                throw new RunApiException(RunErrorCode.INTERNAL_ERROR, "PUBLISH_NO_CHUNKS");
            }
            boolean published = guard.commitAtomic(() -> documents.publishVersionFenced(ctx.tenantId(), ctx.docId(),
                    ctx.versionId(), ctx.runId(), ctx.execution().fence()));
            if (!published) {
                throw new RunApiException(RunErrorCode.VERSION_CONFLICT, "PUBLISH_REJECTED");
            }
            Map<String, Object> ref = Map.of("versionId", ctx.versionId(), "chunks", staged, "documentId", ctx.docId());
            commit(guard, phase(node, "publish"), "publish", ref, null, Map.of("calls", 0));
        }
    }

    // ------------------------------------------------------------------ helpers

    /** 预算：外部 provider 调用总数（本 run 跨 attempt）不得超过 maxToolCalls。 */
    void requireCallBudget(String tenantId, String runId, Integer maxToolCalls) {
        if (maxToolCalls == null) {
            return;
        }
        UsageLedgerService.RunUsage usage = usageLedger.usageOf(tenantId, runId);
        long total = usage.settledCalls() + usage.pendingReconciliation() + usage.failedCalls();
        if (total >= maxToolCalls) {
            throw new RunApiException(RunErrorCode.BUDGET_EXCEEDED, "maxToolCalls exceeded");
        }
    }

    /** 预算：已持久化 step 行数不得超过 maxSteps。 */
    void requireStepBudget(String tenantId, String runId, Integer maxSteps) {
        if (maxSteps == null) {
            return;
        }
        if (ledger.listSteps(tenantId, runId).size() >= maxSteps) {
            throw new RunApiException(RunErrorCode.BUDGET_EXCEEDED, "maxSteps exceeded");
        }
    }

    /** 从父 run 的直接前驱 COMPLETED 检查点取 ref（重试恢复输入）。 */
    Map<String, Object> predecessorRef(String tenantId, String parentRunId, String stepId) {
        return ledger.latestCompletedStep(tenantId, parentRunId, stepId)
                .filter(step -> "COMPLETED".equals(step.state()))
                .map(step -> readRef(step.refJson()))
                .orElse(null);
    }

    String readText(String objectKey) {
        return new String(objectStore.get(objectKey), StandardCharsets.UTF_8);
    }

    com.nageoffer.ai.ragent.runtime.RunAccessService access() {
        return access;
    }

    String embeddingModel() {
        return embeddingGateway.model();
    }

    RunConfigBinding binding(StageContext ctx) {
        var port = bindingPort.getIfAvailable();
        if (port == null) {
            throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE, "config binding port not wired");
        }
        try {
            return port.requireBoundRevision(ctx.tenantId(), ctx.runId());
        } catch (RuntimeException e) {
            throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE,
                    "config binding unavailable: " + e.getMessage());
        }
    }

    private String chatCall(StageContext ctx, RunExecutionGuard guard, String stepId, RunConfigBinding binding,
                            String system, String user) {
        if (guard.isCancelRequested()) {
            throw new IngestionCancelled();
        }
        access.current(ctx.execution().run(), java.util.Set.of("kb.read", "document.read"));
        var operation = providerBoundary.enter(ctx.execution());
        String callId;
        try {
            callId = guard.commitAtomic(() -> usageLedger.startCall(ctx.tenantId(), ctx.runId(), ctx.attempt(),
                    stepId, UsageLedgerService.KIND_CHAT, binding.providerId(), binding.modelId(), null));
        } catch (RuntimeException ex) {
            operation.close();
            throw ex;
        }
        ChatPort.ChatResult result;
        try {
            List<ChatMessage> messages = List.of(ChatMessage.system(system), ChatMessage.user(user));
            result = chat.stream(binding, messages, CHAT_MAX_TOKENS, delta -> {
            });
        } catch (RuntimeException e) {
            guard.commitAtomic(() -> {
                usageLedger.markUnknown(ctx.tenantId(), callId);
                return null;
            });
            operation.close();
            throw e;
        }
        operation.close();
        guard.commitAtomic(() -> {
            usageLedger.settle(ctx.tenantId(), callId, result.providerRequestId(), result.usageRaw());
            return null;
        });
        return result.content();
    }

    private void requireModelMatches(String declared, RunConfigBinding binding) {
        if (declared != null && !declared.isBlank() && !declared.equals(binding.modelId())) {
            throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT, "MODEL_CONFIG_CHANGED");
        }
    }

    Map<String, Object> completedRef(RunExecutionGuard guard, String stepId) {
        return guard.findStep(stepId)
                .filter(step -> "COMPLETED".equals(step.state()))
                .map(step -> readRef(step.refJson()))
                .orElse(null);
    }

    void commit(RunExecutionGuard guard, String stepId, String stepName, Object ref, String refHash, Object usage) {
        guard.commitStep(stepId, stepName, toJson(ref), refHash, toJson(usage));
        guard.appendEvent(RunEventAppender.EVENT_STEP_COMPLETED,
                Map.of("stepId", stepId, "state", "COMPLETED", "ref", ref == null ? Map.of() : ref));
    }

    private static String phase(IngestionPipelineReader.NodeSnapshot node, String suffix) {
        return node.nodeId() + "-" + suffix;
    }

    private int maxChars(ChunkerSettings settings) {
        if (settings == null || settings.getChunkSize() == null) {
            return ChunkingPort.DEFAULT_MAX_CHARS;
        }
        int size = settings.getChunkSize();
        if (size == -1) {
            return Integer.MAX_VALUE;
        }
        if (size <= 0) {
            return ChunkingPort.DEFAULT_MAX_CHARS;
        }
        return Math.min(size, MAX_CHARS_CAP);
    }

    <T> T settings(IngestionPipelineReader.NodeSnapshot node, Class<T> type) {
        String json = node.settingsJson();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "invalid node settings");
        }
    }

    private Map<String, Object> readRef(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            throw new IllegalStateException("step ref is invalid", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new IllegalStateException("json encoding failed", e);
        }
    }
}
