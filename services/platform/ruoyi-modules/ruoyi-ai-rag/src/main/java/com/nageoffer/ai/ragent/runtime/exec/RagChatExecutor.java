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
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService;
import com.nageoffer.ai.ragent.ingest.ChatGateway;
import com.nageoffer.ai.ragent.ingest.DocumentDao;
import com.nageoffer.ai.ragent.ingest.EmbeddingGateway;
import com.nageoffer.ai.ragent.runtime.P2FaultInjector;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunEventAppender;
import com.nageoffer.ai.ragent.runtime.usage.EgressPolicy;
import com.nageoffer.ai.ragent.runtime.usage.PlatformFactsClient;
import com.nageoffer.ai.ragent.runtime.usage.UsageLedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 可靠 RAG 问答执行器：当前授权 → 检索（published + tenant + ACL）→ 外发白名单 →
 * 模型流式（事件先持久化）→ 引用复核 → 持久结果与用量结算。
 *
 * <p>真实模型/白名单/预算未确认（I2–I4）时：外发默认拒绝（0 提供方调用）。
 * 无检索证据时不制造引用、不调用模型，明确返回证据不足。
 */
@Component
@ConditionalOnProperty(name = "p2.executor.mode", havingValue = "real", matchIfMissing = true)
public class RagChatExecutor implements RunExecutor {

    private static final Logger log = LoggerFactory.getLogger(RagChatExecutor.class);
    private static final int TOP_K = 10;
    private static final double MIN_SCORE = 0.2;
    private static final int DELTA_FLUSH_CHARS = 160;

    private final DocumentDao documentDao;
    private final EmbeddingGateway embeddingGateway;
    private final ChatGateway chatGateway;
    private final EgressPolicy egressPolicy;
    private final UsageLedgerService usageLedger;
    private final ObjectProvider<PlatformFactsClient> platformFacts;
    private final ObjectProvider<com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService> authorization;
    private final ObjectProvider<P2FaultInjector> faultInjector;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    @org.springframework.beans.factory.annotation.Autowired
    private com.nageoffer.ai.ragent.runtime.RunAccessService access;
    @org.springframework.beans.factory.annotation.Autowired
    private ProviderCallBoundary providerBoundary;

    public RagChatExecutor(DocumentDao documentDao, EmbeddingGateway embeddingGateway, ChatGateway chatGateway,
                           EgressPolicy egressPolicy, UsageLedgerService usageLedger,
                           ObjectProvider<PlatformFactsClient> platformFacts,
                           ObjectProvider<com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService> authorization,
                           ObjectProvider<P2FaultInjector> faultInjector, JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.documentDao = documentDao;
        this.embeddingGateway = embeddingGateway;
        this.chatGateway = chatGateway;
        this.egressPolicy = egressPolicy;
        this.usageLedger = usageLedger;
        this.platformFacts = platformFacts;
        this.authorization = authorization;
        this.faultInjector = faultInjector;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public String action() {
        return "rag.chat";
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
        JsonNode input = execution.run().inputJson() == null ? objectMapper.createObjectNode()
                : objectMapper.readTree(execution.run().inputJson());
        String question = input.path("text").asText("");
        if (question.isBlank()) {
            return Outcome.failed("CHAT_INPUT_INVALID");
        }
        Set<String> requestedKbs = requestedKbs(execution.run().resourceRefsJson());
        access.current(execution.run(),Set.of("kb.read"));

        // ---------------- step: authorize（异步重新获取当前主体/策略事实） ----------------
        ExecutionPrincipal principal;
        List<String> authorizedKbs = new ArrayList<>();
        Map<String, Object> authRef = completedStepRef(guard, "authorize");
        if (authRef != null) {
            principal = asyncPrincipal(execution, (Integer) authRef.get("policyVersion"),
                    (Integer) authRef.get("aclVersion"));
            Object kbs = authRef.get("kbs");
            if (kbs instanceof List<?> list) {
                for (Object item : list) {
                    authorizedKbs.add(String.valueOf(item));
                }
            }
        } else {
            if (guard.isCancelRequested()) {
                return new Outcome("CANCELLED", Map.of("at", "authorize"), null);
            }
            guard.appendEvent(RunEventAppender.EVENT_STEP_STARTED, Map.of(
                    "stepId", "authorize", "stepName", "authorize", "attemptId", "a-" + execution.attempt()));
            var factsClient = platformFacts.getIfAvailable();
            var resources = authorization.getIfAvailable();
            if (factsClient == null || resources == null) {
                return Outcome.failed("AUTHORIZATION_UNAVAILABLE");
            }
            PlatformFactsClient.CurrentFacts facts;
            try {
                facts = factsClient.currentFacts(execution.tenantId(), execution.run().subject(),
                        execution.run().memberId());
            } catch (RunApiException e) {
                return Outcome.failed("AUTHORIZATION_UNAVAILABLE");
            }
            if (!facts.enabled()) {
                return Outcome.failed("MEMBERSHIP_INVALID");
            }
            principal = asyncPrincipal(execution, facts.policyVersion(), resources.currentAclVersion(execution.tenantId()));
            for (String kbId : requestedKbs) {
                try {
                    if (resources.check(principal, "kb.read", "kb:" + kbId) == ResourceAuthorizationService.Verdict.GRANT) {
                        authorizedKbs.add(kbId);
                    }
                } catch (RuntimeException e) {
                    return Outcome.failed("AUTHORIZATION_UNAVAILABLE");
                }
            }
            if (authorizedKbs.isEmpty()) {
                // 空 scope：不检索、不 embedding、不调用模型
                return Outcome.failed("NO_AUTHORIZED_SCOPE");
            }
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("kbs", authorizedKbs);
            ref.put("policyVersion", principal.policyVersion());
            ref.put("aclVersion", principal.aclVersion());
            guard.commitStep("authorize", "authorize", toJson(ref), null, toJson(Map.of("calls", 0)));
            guard.appendEvent(RunEventAppender.EVENT_STEP_COMPLETED, Map.of(
                    "stepId", "authorize", "state", "COMPLETED", "ref", ref,
                    "attemptId", "a-" + execution.attempt()));
        }
        if (authorizedKbs.isEmpty()) {
            return Outcome.failed("NO_AUTHORIZED_SCOPE");
        }

        if(!documentDao.matchesEmbeddingModel(execution.tenantId(),authorizedKbs,embeddingGateway.model())) return Outcome.failed("MODEL_CONFIG_CHANGED");

        // ---------------- step: retrieve ----------------
        List<DocumentDao.RetrievedChunk> chunks;
        Map<String, Object> retrieveRef = completedStepRef(guard, "retrieve");
        if (retrieveRef != null) {
            if(!embeddingGateway.model().equals(retrieveRef.get("embeddingModel"))) return Outcome.failed("MODEL_CONFIG_CHANGED");
            chunks = loadChunksFromRef(execution.tenantId(), retrieveRef);
        } else {
            if (guard.isCancelRequested()) {
                return new Outcome("CANCELLED", Map.of("at", "retrieve"), null);
            }
            guard.appendEvent(RunEventAppender.EVENT_STEP_STARTED, Map.of(
                    "stepId", "retrieve", "stepName", "retrieve", "attemptId", "a-" + execution.attempt()));
            access.current(execution.run(),Set.of("kb.read"));
            if(usageLedger.hasCall(execution.tenantId(),execution.runId(),"retrieve")) return new Outcome("NEEDS_RECONCILIATION",Map.of(),"MODEL_USAGE_UNKNOWN");
            var operation=providerBoundary.enter(execution);
            String callId;
            try {callId = guard.commitAtomic(() -> usageLedger.startCall(execution.tenantId(), execution.runId(), execution.attempt(),
                    "retrieve", UsageLedgerService.KIND_EMBEDDING, embeddingGateway.provider(),
                    embeddingGateway.model(), com.nageoffer.ai.ragent.runtime.CanonicalJson.sha256(question)));} catch(RuntimeException ex){operation.close();throw ex;}
            EmbeddingGateway.EmbeddingResult embedded;
            try {
                embedded = embeddingGateway.embedBatchWithUsage(List.of(question));
            } catch (RuntimeException e) {
                guard.commitAtomic(()->{usageLedger.markUnknown(execution.tenantId(),callId);return null;});
                throw e;
            }
            operation.close();
            Map<String,Object> embedUsage=embedded.usageRaw();
            guard.commitAtomic(()->{usageLedger.settle(execution.tenantId(),callId,embedded.providerRequestId(),embedUsage);return null;});
            access.current(execution.run(),Set.of("kb.read"));
            chunks = documentDao.searchPublished(execution.tenantId(), authorizedKbs, embedded.vectors().get(0), TOP_K, MIN_SCORE);
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("chunks", chunkRefs(chunks));
            ref.put("embeddingModel", embeddingGateway.model());
            ref.put("minScore", MIN_SCORE);
            try {
                if(currentCitations(principal,chunks).size()!=chunks.size()) return Outcome.failed("SOURCE_CHANGED");
            } catch (SourceCheckUnavailableException e) {
                log.warn("citation recheck unavailable runId={} stage=retrieve cause={}", execution.runId(),
                        e.getCause() == null ? e.getMessage() : e.getCause().toString());
                return Outcome.failed("AUTHORIZATION_UNAVAILABLE");
            }
            guard.commitStep("retrieve", "retrieve", toJson(ref), null, toJson(embedUsage));
            guard.appendEvent(RunEventAppender.EVENT_STEP_COMPLETED, Map.of(
                    "stepId", "retrieve", "state", "COMPLETED", "ref", ref,
                    "attemptId", "a-" + execution.attempt()));
        }

        if (chunks.isEmpty()) {
            // 无证据：不制造引用、不调用模型；明确说明证据不足
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("answer", "未检索到足够证据，无法回答该问题。");
            result.put("citations", List.of());
            result.put("evidenceInsufficient", true);
            result.put("providerCalls", 0);
            access.current(execution.run(),Set.of("kb.read"));
            guard.commitAtomic(()->{
                usageLedger.finalizeReservation(execution.tenantId(), execution.runId());
                persistMessage(execution, result.get("answer").toString(), List.of(), 1);
                return null;
            });
            return Outcome.succeeded(result);
        }
        principal=access.current(execution.run(),Set.of("kb.read"));
        try {
            if(currentCitations(principal,chunks).size()!=chunks.size()) return Outcome.failed("SOURCE_CHANGED");
        } catch (SourceCheckUnavailableException e) {
            log.warn("citation recheck unavailable runId={} stage=pre-model cause={}", execution.runId(),
                    e.getCause() == null ? e.getMessage() : e.getCause().toString());
            return Outcome.failed("AUTHORIZATION_UNAVAILABLE");
        }

        // ---------------- step: model（外发白名单 → 流式 → 持久事件） ----------------
        Map<String, Object> modelRef = completedStepRef(guard, "model");
        if (modelRef == null) {
            if (guard.isCancelRequested()) {
                return new Outcome("CANCELLED", Map.of("at", "model"), null);
            }
            egressPolicy.requireAllowed(chatGateway.provider());
            access.current(execution.run(),Set.of("kb.read"));
            if(usageLedger.hasCall(execution.tenantId(),execution.runId(),"model")) return new Outcome("NEEDS_RECONCILIATION",Map.of(),"MODEL_USAGE_UNKNOWN");
            guard.appendEvent(RunEventAppender.EVENT_STEP_STARTED, Map.of(
                    "stepId", "model", "stepName", "model", "attemptId", "a-" + execution.attempt()));
            fault(P2FaultInjector.CHAT_BEFORE_PROVIDER);
            var operation=providerBoundary.enter(execution);
            String callId;
            try {callId = guard.commitAtomic(() -> usageLedger.startCall(execution.tenantId(), execution.runId(), execution.attempt(),
                    "model", UsageLedgerService.KIND_CHAT, chatGateway.provider(), chatGateway.model(), null));} catch(RuntimeException ex){operation.close();throw ex;}
            StringBuilder answer = new StringBuilder();
            StringBuilder pending = new StringBuilder();
            final boolean[] cancelled = {false};
            ChatGateway.ChatResult chatResult;
            try {
                chatResult = chatGateway.stream(chatMessages(question, chunks), maxTokens(execution),
                        delta -> {
                            if (cancelled[0]) {
                                throw new com.nageoffer.ai.ragent.runtime.RunApiException(com.nageoffer.ai.ragent.runtime.RunErrorCode.RUN_STATE_CONFLICT);
                            }
                            answer.append(delta);
                            pending.append(delta);
                            if (pending.length() >= DELTA_FLUSH_CHARS) {
                                access.current(execution.run(),Set.of("kb.read"));
                                guard.appendEvent(RunEventAppender.EVENT_OUTPUT_DELTA,
                                        Map.of("text", pending.toString(), "dropped", false));
                                pending.setLength(0);
                            }
                            if (guard.isCancelRequested()) {
                                cancelled[0] = true;
                                throw new com.nageoffer.ai.ragent.runtime.RunApiException(com.nageoffer.ai.ragent.runtime.RunErrorCode.RUN_STATE_CONFLICT);
                            }
                        });
            } catch (RuntimeException e) {
                guard.commitAtomic(()->{usageLedger.markUnknown(execution.tenantId(),callId);return null;});
                if(cancelled[0]) return new Outcome("CANCELLED",Map.of("usage","PENDING_RECONCILIATION"),null);
                throw e;
            }
            operation.close();
            fault(P2FaultInjector.CHAT_AFTER_PROVIDER);
            if (pending.length() > 0) {
                access.current(execution.run(),Set.of("kb.read"));
                guard.appendEvent(RunEventAppender.EVENT_OUTPUT_DELTA,
                        Map.of("text", pending.toString(), "dropped", false));
            }
            guard.commitAtomic(()->{usageLedger.settle(execution.tenantId(),callId,chatResult.providerRequestId(),chatResult.usageRaw());return null;});
            if(usageLedger.unresolved(execution.tenantId(),execution.runId(),"model")) return new Outcome("NEEDS_RECONCILIATION",Map.of(),"MODEL_USAGE_UNKNOWN");
            if(!"stop".equals(chatResult.finishReason())) return Outcome.failed("MODEL_RESPONSE_INCOMPLETE");
            principal=access.current(execution.run(),Set.of("kb.read"));
            List<Map<String, Object>> citations;
            try {
                citations = currentCitations(principal, chunks);
            } catch (SourceCheckUnavailableException e) {
                log.warn("citation recheck unavailable runId={} stage=post-model cause={}", execution.runId(),
                        e.getCause() == null ? e.getMessage() : e.getCause().toString());
                return Outcome.failed("AUTHORIZATION_UNAVAILABLE");
            }
            if(citations.size()!=chunks.size()) return Outcome.failed("SOURCE_CHANGED");
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("callId", callId);
            ref.put("chars", answer.length());
            ref.put("citations", citations);
            ref.put("answer",answer.toString());
            ref.put("provider", chatGateway.provider());
            ref.put("model", chatGateway.model());
            guard.commitAtomic(()->{
                persistMessage(execution,answer.toString(),citations,1);
                return guard.commitStep("model", "model", toJson(ref), null, toJson(Map.of("calls", 1)));
            });
            guard.appendEvent(RunEventAppender.EVENT_STEP_COMPLETED, Map.of(
                    "stepId", "model", "state", "COMPLETED", "ref", ref,
                    "attemptId", "a-" + execution.attempt()));
            guard.commitAtomic(()->{usageLedger.finalizeReservation(execution.tenantId(), execution.runId());return null;});
            if (cancelled[0] || guard.isCancelRequested()) {
                return new Outcome("CANCELLED", Map.of("partial", answer.length(), "citations", citations), null);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("answer", answer.toString());
            result.put("citations", citations);
            result.put("evidenceInsufficient", false);
            result.put("provider", chatGateway.provider());
            result.put("model", chatGateway.model());
            return Outcome.succeeded(result);
        }

        // 已完成模型步骤（恢复场景）：读取持久结果返回
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resumedFrom", "model");
        result.put("citations", modelRef.get("citations"));
        result.put("answer", modelRef.get("answer"));
        guard.commitAtomic(()->{usageLedger.finalizeReservation(execution.tenantId(), execution.runId());return null;});
        return Outcome.succeeded(result);
    }

    private int maxTokens(RunExecution execution) {
        try {
            JsonNode budget = execution.run().budgetJson() == null ? null
                    : objectMapper.readTree(execution.run().budgetJson());
            int tokens = budget == null ? 0 : budget.path("maxTokens").asInt(0);
            return tokens > 0 ? tokens : 2000;
        } catch (Exception e) {
            return 2000;
        }
    }

    private ExecutionPrincipal asyncPrincipal(RunExecution execution, int policyVersion, int aclVersion) {
        long now = java.time.Instant.now().getEpochSecond();
        return new ExecutionPrincipal(execution.tenantId(), execution.run().subject(), execution.run().memberId(),
                Math.max(1, policyVersion), Math.max(1, aclVersion), Set.of("kb.read"),
                "async-" + execution.runId(), "platform", now, now + 300);
    }

    private Set<String> requestedKbs(String resourceRefsJson) {
        Set<String> kbs = new LinkedHashSet<>();
        if (resourceRefsJson == null || resourceRefsJson.isBlank()) {
            return kbs;
        }
        try {
            JsonNode refs = objectMapper.readTree(resourceRefsJson);
            for (JsonNode ref : refs) {
                if(ref.path("ref").asText("").startsWith("kb:")){kbs.add(ref.path("ref").asText().substring(3));continue;}
                if ("knowledge_base".equals(ref.path("type").asText())) {
                    String id = ref.path("id").asText("");
                    if (!id.isBlank() && id.matches("[A-Za-z0-9_-]{1,64}")) {
                        kbs.add(id);
                    }
                }
            }
        } catch (Exception e) {
            throw new RunApiException(com.nageoffer.ai.ragent.runtime.RunErrorCode.BAD_REQUEST,
                    "resourceRefs are invalid");
        }
        return kbs;
    }

    private List<ChatMessage> chatMessages(String question, List<DocumentDao.RetrievedChunk> chunks) {
        StringBuilder context = new StringBuilder();
        context.append("以下是从授权知识库检索到的证据（引用编号 [n]）：\n");
        for (int i = 0; i < chunks.size(); i++) {
            context.append('[').append(i + 1).append("] ").append(chunks.get(i).content()).append('\n');
        }
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("你是可靠知识库问答助手。只依据给定证据回答，"
                + "引用证据时使用 [n] 编号；证据不足时明确说明，不要编造。"));
        messages.add(ChatMessage.user("证据：\n" + context + "\n问题：" + question));
        return messages;
    }

    /** 引用复核：KB 当前授权 + 版本仍为 published + 未 tombstone；不通过即剔除。 */
    /** 引用复核时授权/依赖服务瞬时不可用：fail-closed，但不得与真实来源变化混淆成 SOURCE_CHANGED。 */
    static final class SourceCheckUnavailableException extends RuntimeException {
        SourceCheckUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    List<Map<String, Object>> currentCitations(ExecutionPrincipal principal,
                                               List<DocumentDao.RetrievedChunk> chunks) {
        var resources = authorization.getIfAvailable();
        List<Map<String, Object>> citations = new ArrayList<>();
        for (DocumentDao.RetrievedChunk chunk : chunks) {
            DocumentDao.DocumentRow document = documentDao.findDocument(principal.tenantId(), chunk.docId()).orElse(null);
            if (document == null || document.tombstoned()) {
                continue;
            }
            if (!chunk.versionId().equals(document.publishedVersionId())) {
                continue;
            }
            if (resources == null) {
                throw new SourceCheckUnavailableException("authorization service unavailable during citation recheck", null);
            }
            try {
                if (resources.check(com.nageoffer.ai.ragent.runtime.RunAccessService.scoped(principal,Set.of("document.read")), "document.read", "doc:" + document.docId())
                        != ResourceAuthorizationService.Verdict.GRANT) {
                    continue;
                }
            } catch (SourceCheckUnavailableException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new SourceCheckUnavailableException("citation authorization check failed", e);
            }
            Map<String, Object> citation = new LinkedHashMap<>();
            citation.put("docId", chunk.docId());
            citation.put("docName", document.name());
            citation.put("versionId", chunk.versionId());
            citation.put("chunkKey", chunk.chunkKey());
            citation.put("chunkIndex", chunk.chunkIndex());
            citation.put("pageFrom", chunk.pageFrom());
            citation.put("pageTo", chunk.pageTo());
            citation.put("score", Math.round(chunk.score() * 10000) / 10000.0);
            citation.put("viewRef", "/api/ai/v1/documents/" + chunk.docId() + "/source");
            citations.add(citation);
        }
        return citations;
    }

    private List<Map<String, Object>> chunkRefs(List<DocumentDao.RetrievedChunk> chunks) {
        List<Map<String, Object>> refs = new ArrayList<>();
        for (DocumentDao.RetrievedChunk chunk : chunks) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("docId", chunk.docId());
            ref.put("versionId", chunk.versionId());
            ref.put("chunkKey", chunk.chunkKey());
            ref.put("chunkIndex", chunk.chunkIndex());
            ref.put("score", Math.round(chunk.score() * 10000) / 10000.0);
            refs.add(ref);
        }
        return refs;
    }

    private List<DocumentDao.RetrievedChunk> loadChunksFromRef(String tenantId, Map<String, Object> retrieveRef) {
        List<DocumentDao.RetrievedChunk> chunks = new ArrayList<>();
        Object refs = retrieveRef.get("chunks");
        if (refs instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    String docId = String.valueOf(map.get("docId"));
                    String versionId = String.valueOf(map.get("versionId"));
                    String chunkKey = String.valueOf(map.get("chunkKey"));
                    List<Map<String, Object>> rows = jdbc.query(
                            "SELECT chunk_index, content, page_from, page_to FROM ai_document_chunk "
                                    + "WHERE tenant_id=? AND version_id=? AND chunk_key=? AND state='PUBLISHED'",
                            (rs, rowNum) -> {
                                Map<String, Object> row = new LinkedHashMap<>();
                                row.put("chunkIndex", rs.getInt("chunk_index"));
                                row.put("content", rs.getString("content"));
                                row.put("pageFrom", rs.getObject("page_from"));
                                row.put("pageTo", rs.getObject("page_to"));
                                return row;
                            }, tenantId, versionId, chunkKey);
                    if (!rows.isEmpty()) {
                        Map<String, Object> row = rows.get(0);
                        chunks.add(new DocumentDao.RetrievedChunk(docId, versionId, chunkKey,
                                (Integer) row.get("chunkIndex"), (String) row.get("content"),
                                (Integer) row.get("pageFrom"), (Integer) row.get("pageTo"), 0.0));
                    }
                }
            }
        }
        return chunks;
    }

    private void persistMessage(RunExecution execution, String answer, List<Map<String, Object>> citations, int sequence) {
        jdbc.update("INSERT INTO ai_chat_message (tenant_id, message_id, run_id, sequence, role, content, citations) "
                        + "VALUES (?,?,?,?,'assistant',?,?::jsonb) ON CONFLICT (tenant_id, run_id, sequence) DO NOTHING",
                execution.tenantId(), "msg-" + UUID.randomUUID().toString().replace("-", ""),
                execution.runId(), sequence, answer, toJson(citations));
    }

    private String readPersistedAnswer(RunExecution execution) {
        List<String> rows = jdbc.query("SELECT content FROM ai_chat_message WHERE tenant_id=? AND run_id=? "
                        + "AND role='assistant' ORDER BY sequence LIMIT 1",
                (rs, rowNum) -> rs.getString("content"), execution.tenantId(), execution.runId());
        return rows.isEmpty() ? "" : rows.get(0);
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
