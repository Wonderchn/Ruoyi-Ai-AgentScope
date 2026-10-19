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

package com.nageoffer.ai.ragent.rag.core.graph;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService.Verdict;
import com.nageoffer.ai.ragent.rag.config.GraphProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionOperations;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图谱投影重建（F08:3）。
 *
 * <p><b>为什么需要显式重建。</b>LightRAG 单实例即单图、读时没有 per-request workspace；
 * 读路径要按租户/KB/doc 隔离，只能依赖主库中"可重建派生、非权威"的投影表。本服务把
 * LightRAG {@code /graphs} 的结果按文档拆分、按主库事实归属后 replace 进投影。
 *
 * <p>失败语义（冻结）：
 * <ul>
 *   <li>文档未入图 → 先 {@code insertTextOrThrow} 再 409 NOT_READY（抽取是异步的，显式重试）；</li>
 *   <li>processing/pending → 409 NOT_READY；failed → 409 FAILED；</li>
 *   <li>{@code is_truncated=true} → 409 TRUNCATED，<b>旧投影完全不动</b>；</li>
 *   <li>LightRAG 不可达/超时/非 2xx → 503；只有截断与解析完成后才开启写事务。</li>
 * </ul>
 */
public class GraphProjectionService {

    /** 重建结果（与 Spec §3.3 #10 对齐）。 */
    public record RebuildResult(int documents, int entities, int relations, int skipped, boolean truncated) {
    }

    private final GraphProjectionRepository repository;
    private final ObjectProvider<LightRagClient> clientProvider;
    private final GraphProperties properties;
    private final AiResourceAuthorizationService authorization;
    private final TransactionOperations transactions;

    public GraphProjectionService(GraphProjectionRepository repository,
                                  ObjectProvider<LightRagClient> clientProvider,
                                  GraphProperties properties,
                                  AiResourceAuthorizationService authorization,
                                  TransactionOperations transactions) {
        this.repository = repository;
        this.clientProvider = clientProvider;
        this.properties = properties;
        this.authorization = authorization;
        this.transactions = transactions;
    }

    public RebuildResult rebuild(String kbId, String docId) {
        ExecutionPrincipal principal = PrincipalContext.require();
        String tenantId = principal.tenantId();
        authorization.requireFunction(principal, "kb.write", "tenant:graph");

        String requestedKb = StrUtil.isBlank(kbId) ? null : kbId.trim();
        String requestedDoc = StrUtil.isBlank(docId) ? null : docId.trim();
        if (requestedKb == null && requestedDoc == null) {
            throw new GraphOperationException(GraphErrorCode.GRAPH_BAD_REQUEST,
                    "kbId 与 docId 必须二选一");
        }

        String targetKb;
        List<GraphProjectionRepository.DocRow> documents;
        boolean wholeKb = requestedDoc == null;
        if (requestedDoc != null) {
            GraphProjectionRepository.DocRow doc = repository.findDocument(tenantId, requestedDoc)
                    .orElseThrow(GraphProjectionService::notFound);
            targetKb = doc.kbId();
            requireWrite(principal, targetKb);
            if (requestedKb != null && !requestedKb.equals(targetKb)) {
                throw notFound();
            }
            requireSuccessful(doc);
            documents = List.of(doc);
        } else {
            targetKb = requestedKb;
            repository.findKnowledgeBase(tenantId, targetKb).orElseThrow(GraphProjectionService::notFound);
            requireWrite(principal, targetKb);
            documents = repository.findRebuildDocuments(tenantId, targetKb);
            if (documents.size() > Math.max(1, properties.getProjection().getMaxDocs())) {
                throw new GraphOperationException(GraphErrorCode.GRAPH_DOCUMENT_LIMIT_EXCEEDED,
                        "本次重建的文档数 " + documents.size() + " 超过上限 "
                                + properties.getProjection().getMaxDocs());
            }
            if (documents.isEmpty()) {
                throw new GraphOperationException(GraphErrorCode.GRAPH_DOCUMENT_EMPTY);
            }
        }

        Map<String, String> texts = loadTexts(tenantId, documents);
        LightRagClient client = clientProvider.getIfAvailable();
        if (client == null) {
            throw new GraphOperationException(GraphErrorCode.GRAPH_BACKEND_UNAVAILABLE,
                    "知识图谱后端未启用（rag.graph.type=none），无法重建投影");
        }
        ensureExtracted(client, documents, texts);

        JsonNode graph;
        try {
            graph = client.fetchGraphStrict("*",
                    properties.getProjection().getMaxDepth(),
                    properties.getProjection().getMaxNodes());
        } catch (IOException backendFailure) {
            throw unavailable(backendFailure);
        }
        if (graph == null || graph.path("is_truncated").asBoolean(false)) {
            // 截断图不能当完整投影写入：旧投影必须原样保留，否则读侧会静默缺边。
            throw new GraphOperationException(GraphErrorCode.GRAPH_PROJECTION_TRUNCATED);
        }

        ParsedProjection parsed = parse(graph, documents);
        transactions.executeWithoutResult(status -> {
            if (wholeKb) {
                repository.deleteKnowledgeBaseProjection(tenantId, targetKb);
            }
            for (GraphProjectionRepository.DocRow doc : documents) {
                repository.replaceDocumentProjection(tenantId, targetKb, doc.id(),
                        parsed.entities().getOrDefault(doc.id(), List.of()),
                        parsed.relations().getOrDefault(doc.id(), List.of()));
            }
        });

        int entities = parsed.entities().values().stream().mapToInt(List::size).sum();
        int relations = parsed.relations().values().stream().mapToInt(List::size).sum();
        return new RebuildResult(documents.size(), entities, relations, parsed.skipped(), false);
    }

    // ------------------------------------------------------------------ 前置

    private static void requireSuccessful(GraphProjectionRepository.DocRow doc) {
        if (!"success".equalsIgnoreCase(doc.status())
                || doc.chunkCount() == null || doc.chunkCount() <= 0) {
            throw new GraphOperationException(GraphErrorCode.GRAPH_DOCUMENT_EMPTY);
        }
    }

    private void requireWrite(ExecutionPrincipal principal, String kbId) {
        Verdict verdict = authorization.check(principal, "kb.write", "kb:" + kbId);
        if (verdict == Verdict.UNKNOWN) {
            throw new ServiceException("platform authorization is unavailable");
        }
        if (verdict != Verdict.GRANT) {
            throw notFound();
        }
    }

    private Map<String, String> loadTexts(String tenantId, List<GraphProjectionRepository.DocRow> documents) {
        Map<String, String> texts = new LinkedHashMap<>();
        int totalChars = 0;
        for (GraphProjectionRepository.DocRow doc : documents) {
            String text = repository.loadDocumentText(tenantId, doc.id());
            if (StrUtil.isBlank(text)) {
                throw new GraphOperationException(GraphErrorCode.GRAPH_DOCUMENT_EMPTY);
            }
            totalChars += text.length();
            if (totalChars > properties.getProjection().getMaxChars()) {
                throw new GraphOperationException(GraphErrorCode.GRAPH_TEXT_TOO_LARGE,
                        "本次重建正文 " + totalChars + " 字符，超过上限 "
                                + properties.getProjection().getMaxChars());
            }
            texts.put(doc.id(), text);
        }
        return texts;
    }

    private void ensureExtracted(LightRagClient client,
                                 List<GraphProjectionRepository.DocRow> documents,
                                 Map<String, String> texts) {
        for (GraphProjectionRepository.DocRow doc : documents) {
            String fileSource = GraphFileSource.encode(doc.collectionName(), doc.id());
            LightRagClient.DocumentState state;
            try {
                state = client.documentState(fileSource, doc.id());
            } catch (IOException backendFailure) {
                throw unavailable(backendFailure);
            }
            LightRagClient.DocumentStatus status = state == null
                    ? LightRagClient.DocumentStatus.UNKNOWN : state.status();
            switch (status) {
                case PROCESSED -> {
                    // 可以继续
                }
                case MISSING -> {
                    try {
                        // 首次入图：LightRAG 抽取是异步的，写入成功也不能立刻当 processed。
                        client.insertTextOrThrow(texts.get(doc.id()), fileSource);
                    } catch (IOException backendFailure) {
                        throw unavailable(backendFailure);
                    }
                    throw new GraphOperationException(GraphErrorCode.GRAPH_DOCUMENT_NOT_READY,
                            "文档已提交图谱抽取，请稍后重试");
                }
                case FAILED -> throw new GraphOperationException(GraphErrorCode.GRAPH_DOCUMENT_FAILED,
                        "文档图谱抽取失败: " + doc.docName());
                default -> throw new GraphOperationException(GraphErrorCode.GRAPH_DOCUMENT_NOT_READY,
                        "文档图谱抽取尚未完成: " + doc.docName());
            }
        }
    }

    // ------------------------------------------------------------------ 解析

    private ParsedProjection parse(JsonNode graph, List<GraphProjectionRepository.DocRow> documents) {
        Map<String, GraphProjectionRepository.DocRow> docsById = new LinkedHashMap<>();
        for (GraphProjectionRepository.DocRow doc : documents) {
            docsById.put(doc.id(), doc);
        }
        Map<String, Map<String, GraphProjectionRepository.EntityWriteRow>> entitiesByDoc = new LinkedHashMap<>();
        Map<String, Map<String, GraphProjectionRepository.RelationWriteRow>> relationsByDoc = new LinkedHashMap<>();
        Map<String, String> entityKeys = new LinkedHashMap<>();
        Map<String, Set<String>> nodeSourceDocs = new LinkedHashMap<>();
        Set<String> projectedNodes = new LinkedHashSet<>();
        int skipped = 0;

        for (JsonNode node : array(graph.path("nodes"))) {
            String nodeId = node.path("id").asText("");
            if (StrUtil.isBlank(nodeId)) {
                skipped++;
                continue;
            }
            JsonNode props = node.path("properties");
            String entityKey = StrUtil.isNotBlank(props.path("entity_id").asText(""))
                    ? props.path("entity_id").asText("") : firstLabel(node);
            if (StrUtil.isBlank(entityKey)) {
                entityKey = nodeId;
            }
            String name = entityKey;
            String entityType = props.path("entity_type").asText("");
            String description = cleanMerged(props.path("description").asText(""), "\n");

            List<GraphFileSource> sources = validSources(props.path("file_path").asText(""), docsById);
            if (sources.isEmpty()) {
                // 来源不可解析/不在本次目标文档集：绝不无主放行，计入诊断。
                skipped++;
                continue;
            }
            entityKeys.put(nodeId, entityKey);
            projectedNodes.add(nodeId);
            Set<String> sourceDocs = nodeSourceDocs.computeIfAbsent(nodeId, ignored -> new LinkedHashSet<>());
            for (GraphFileSource source : sources) {
                sourceDocs.add(source.docId());
                Map<String, GraphProjectionRepository.EntityWriteRow> target =
                        entitiesByDoc.computeIfAbsent(source.docId(), ignored -> new LinkedHashMap<>());
                target.putIfAbsent(nodeId, new GraphProjectionRepository.EntityWriteRow(
                        nodeId, entityKey, name, entityType, description,
                        GraphFileSource.encode(source.collectionName(), source.docId())));
            }
        }

        for (JsonNode edge : array(graph.path("edges"))) {
            String sourceNode = edge.path("source").asText("");
            String targetNode = edge.path("target").asText("");
            if (StrUtil.isBlank(sourceNode) || StrUtil.isBlank(targetNode)
                    || !projectedNodes.contains(sourceNode) || !projectedNodes.contains(targetNode)) {
                skipped++;
                continue;
            }
            JsonNode props = edge.path("properties");
            String label = cleanMerged(props.path("keywords").asText(""), " / ");
            if (StrUtil.isBlank(label)) {
                label = edge.path("type").asText("");
            }
            String description = cleanMerged(props.path("description").asText(""), "\n");
            String rawPath = props.path("file_path").asText("");
            boolean derived;
            List<GraphFileSource> sources;
            if (StrUtil.isBlank(rawPath)) {
                derived = true;
                sources = derivedSources(sourceNode, targetNode, nodeSourceDocs, docsById);
            } else {
                derived = false;
                sources = validSources(rawPath, docsById);
                if (sources.isEmpty()) {
                    // 有 file_path 但不在目标集：丢弃该边，不冒充派生
                    skipped++;
                    continue;
                }
            }
            String sourceKey = entityKeys.getOrDefault(sourceNode, "");
            String targetKey = entityKeys.getOrDefault(targetNode, "");
            for (GraphFileSource source : sources) {
                Map<String, GraphProjectionRepository.RelationWriteRow> target =
                        relationsByDoc.computeIfAbsent(source.docId(), ignored -> new LinkedHashMap<>());
                String key = sourceNode + '\u0000' + targetNode + '\u0000' + label;
                target.putIfAbsent(key, new GraphProjectionRepository.RelationWriteRow(
                        sourceNode, targetNode, sourceKey, targetKey, label, description,
                        derived ? "" : GraphFileSource.encode(source.collectionName(), source.docId())));
            }
        }

        Map<String, List<GraphProjectionRepository.EntityWriteRow>> entities = new LinkedHashMap<>();
        entitiesByDoc.forEach((docId, rows) -> entities.put(docId, new ArrayList<>(rows.values())));
        Map<String, List<GraphProjectionRepository.RelationWriteRow>> relations = new LinkedHashMap<>();
        relationsByDoc.forEach((docId, rows) -> relations.put(docId, new ArrayList<>(rows.values())));
        return new ParsedProjection(entities, relations, skipped);
    }

    private static List<GraphFileSource> validSources(
            String rawPath, Map<String, GraphProjectionRepository.DocRow> docsById) {
        List<GraphFileSource> valid = new ArrayList<>();
        for (GraphFileSource source : GraphFileSource.parseAll(rawPath)) {
            GraphProjectionRepository.DocRow doc = docsById.get(source.docId());
            if (doc != null && source.collectionName().equals(doc.collectionName())) {
                valid.add(source);
            }
        }
        return valid;
    }

    private static List<GraphFileSource> derivedSources(
            String sourceNode, String targetNode,
            Map<String, Set<String>> nodeSourceDocs,
            Map<String, GraphProjectionRepository.DocRow> docsById) {
        Set<String> docIds = new LinkedHashSet<>();
        docIds.addAll(nodeSourceDocs.getOrDefault(sourceNode, Set.of()));
        docIds.addAll(nodeSourceDocs.getOrDefault(targetNode, Set.of()));
        List<GraphFileSource> sources = new ArrayList<>();
        for (String docId : docIds) {
            GraphProjectionRepository.DocRow doc = docsById.get(docId);
            if (doc != null) {
                sources.add(new GraphFileSource(doc.collectionName(), docId));
            }
        }
        return sources;
    }

    private static List<JsonNode> array(JsonNode node) {
        List<JsonNode> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(values::add);
        }
        return values;
    }

    private static String firstLabel(JsonNode node) {
        JsonNode labels = node.path("labels");
        if (labels.isArray() && !labels.isEmpty()) {
            return labels.get(0).asText("");
        }
        return "";
    }

    /** 归一 LightRAG 的多来源合并串（与读路径同一口径）。 */
    private static String cleanMerged(String raw, String joiner) {
        if (StrUtil.isBlank(raw)) {
            return "";
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String part : raw.split("<SEP>")) {
            String piece = part.trim();
            if (StrUtil.isNotBlank(piece)) {
                seen.add(piece);
            }
        }
        return String.join(joiner, seen);
    }

    private static GraphOperationException unavailable(IOException failure) {
        return new GraphOperationException(GraphErrorCode.GRAPH_BACKEND_UNAVAILABLE,
                "知识图谱后端不可用: " + failure.getMessage());
    }

    private static GraphOperationException notFound() {
        return new GraphOperationException(GraphErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
    }

    private record ParsedProjection(
            Map<String, List<GraphProjectionRepository.EntityWriteRow>> entities,
            Map<String, List<GraphProjectionRepository.RelationWriteRow>> relations,
            int skipped) {
    }
}
