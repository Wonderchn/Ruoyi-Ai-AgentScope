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

package com.nageoffer.ai.ragent.rag.core.keyword;

import cn.hutool.core.collection.CollUtil;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.framework.integration.SaasCapabilityBoundary;
import com.nageoffer.ai.ragent.rag.config.KeywordProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 基于 Elasticsearch 的关键词索引服务，{@code rag.keyword.type=es} 时才装配
 * <p>
 * 只写关键词文本与检索所需元信息，不写向量；文档主键 {@code _id} 取 chunkId，与向量库主键对齐才能保证
 * 跨模态去重与融合一致；所有知识库写同一物理索引、以 {@code collection_name} 区分，与向量库共享
 * collection 同构
 *
 * <p>P1.2a 只关<b>启动期</b>的共享索引自动创建（{@link #initSharedIndex()}）：
 * 默认启动不对 ES 发起任何请求。检索/写入方向的租户与授权 filter 由 P1.3b 负责，
 * 不因本单元关闭而假装已经隔离。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.keyword", name = "type", havingValue = "es")
public class EsKeywordIndexService implements KeywordIndexService {

    private static final int MAX_CONTENT_LENGTH = 65535;

    private final ElasticsearchClient esClient;
    private final KeywordProperties keywordProperties;
    /**
     * 旧能力关闭判定；缺席按关闭处理（不默认放行）。
     */
    private final ObjectProvider<SaasCapabilityBoundary> capabilityBoundary;

    /**
     * 启动即幂等确保共享索引存在，与向量共享 collection 的启动初始化对称。
     *
     * <p>P1.2a：默认关闭，不做 {@code indices().exists()/create()} 任何远端调用。
     */
    @PostConstruct
    public void initSharedIndex() {
        initSharedIndex(capabilityBoundary == null ? null : capabilityBoundary.getIfAvailable());
    }

    /** 显式边界版本：关闭时抛受控异常，且不含任何 ES 请求。 */
    void initSharedIndex(SaasCapabilityBoundary boundary) {
        if (boundary == null) {
            throw new SaasCapabilityBoundary.ClosedCapabilityException(
                    SaasCapabilityBoundary.LegacyCapability.ES_SHARED_INDEX_INITIALIZER);
        }
        boundary.requireOpen(SaasCapabilityBoundary.LegacyCapability.ES_SHARED_INDEX_INITIALIZER);
        ensureSharedIndex();
    }

    @Override
    public void indexDocumentChunks(String tenantId, String collectionName, String docId, List<EmbeddedChunk> chunks) {
        KeywordIndexService.requireTenant(tenantId);
        if (CollUtil.isEmpty(chunks)) {
            return;
        }
        ensureSharedIndex();

        String index = sharedIndex();
        BulkRequest.Builder bulk = new BulkRequest.Builder();
        for (EmbeddedChunk chunk : chunks) {
            String chunkId = chunk.chunkId();
            Map<String, Object> doc = buildDocument(tenantId, collectionName, docId, chunk);
            bulk.operations(op -> op.index(idx -> idx.index(index).id(chunkId).document(doc)));
        }

        try {
            BulkResponse resp = esClient.bulk(bulk.build());
            if (resp.errors()) {
                log.warn("ES 关键词索引部分失败, tenant={}, collection={}, docId={}", tenantId, collectionName, docId);
            } else {
                log.info("ES 关键词索引写入成功, tenant={}, collection={}, docId={}, rows={}",
                        tenantId, collectionName, docId, chunks.size());
            }
        } catch (Exception e) {
            throw new RuntimeException("ES 关键词索引写入失败, tenant=" + tenantId
                    + ", collection=" + collectionName + ", docId=" + docId, e);
        }
    }

    @Override
    public void updateChunk(String tenantId, String collectionName, String docId, EmbeddedChunk chunk) {
        indexDocumentChunks(tenantId, collectionName, docId, List.of(chunk));
    }

    @Override
    public void deleteDocumentIndex(String tenantId, String collectionName, String docId) {
        KeywordIndexService.requireTenant(tenantId);
        try {
            esClient.deleteByQuery(d -> d
                    .index(sharedIndex())
                    .ignoreUnavailable(true)
                    .allowNoIndices(true)
                    .query(q -> q.bool(b -> b
                            .filter(f -> f.term(t -> t.field("tenant_id").value(tenantId)))
                            .filter(f -> f.term(t -> t.field("collection_name").value(collectionName)))
                            .filter(f -> f.term(t -> t.field("doc_id").value(docId))))));
            log.info("ES 关键词索引按文档删除成功, tenant={}, collection={}, docId={}", tenantId, collectionName, docId);
        } catch (Exception e) {
            if (isNotFound(e)) {
                log.info("ES 共享索引不存在，跳过按文档删除, tenant={}, collection={}, docId={}",
                        tenantId, collectionName, docId);
                return;
            }
            throw new RuntimeException("ES 关键词索引删除失败, tenant=" + tenantId
                    + ", collection=" + collectionName + ", docId=" + docId, e);
        }
    }

    @Override
    public void deleteChunkById(String tenantId, String collectionName, String chunkId) {
        KeywordIndexService.requireTenant(tenantId);
        // 用 delete-by-query 而不是按 _id 直删：ES 的 delete API 只接受 _id，无法附加租户条件。
        // 单条删除也必须带租户条件——"chunkId 全局唯一"是主键属性、不是授权依据，
        // 一旦 id 生成方变化（迁移/导入/换 ID 策略），仅按 id 删除就会跨租户删数据。
        try {
            int deleted = deleteChunks(tenantId, List.of(chunkId));
            log.info("ES 关键词索引按 chunk 删除成功, tenant={}, collection={}, chunkId={}, deleted={}",
                    tenantId, collectionName, chunkId, deleted);
        } catch (Exception e) {
            if (isNotFound(e)) {
                log.info("ES 共享索引不存在，跳过按 chunk 删除, tenant={}, chunkId={}", tenantId, chunkId);
                return;
            }
            throw new RuntimeException("ES 关键词索引删除失败, tenant=" + tenantId
                    + ", collection=" + collectionName + ", chunkId=" + chunkId, e);
        }
    }

    @Override
    public void deleteChunksByIds(String tenantId, String collectionName, List<String> chunkIds) {
        KeywordIndexService.requireTenant(tenantId);
        if (CollUtil.isEmpty(chunkIds)) {
            return;
        }
        try {
            int deleted = deleteChunks(tenantId, chunkIds);
            log.info("ES 关键词索引批量删除成功, tenant={}, collection={}, count={}, deleted={}",
                    tenantId, collectionName, chunkIds.size(), deleted);
        } catch (Exception e) {
            if (isNotFound(e)) {
                log.info("ES 共享索引不存在，跳过批量删除, tenant={}, count={}", tenantId, chunkIds.size());
                return;
            }
            throw new RuntimeException("ES 关键词索引批量删除失败, tenant=" + tenantId
                    + ", collection=" + collectionName, e);
        }
    }

    @Override
    public void deleteByCollection(String tenantId, String collectionName) {
        KeywordIndexService.requireTenant(tenantId);
        try {
            esClient.deleteByQuery(d -> d
                    .index(sharedIndex())
                    .ignoreUnavailable(true)
                    .allowNoIndices(true)
                    .query(q -> q.bool(b -> b
                            .filter(f -> f.term(t -> t.field("tenant_id").value(tenantId)))
                            .filter(f -> f.term(t -> t.field("collection_name").value(collectionName))))));
            log.info("ES 关键词索引按知识库删除成功, tenant={}, collection={}", tenantId, collectionName);
        } catch (Exception e) {
            if (isNotFound(e)) {
                log.info("ES 共享索引不存在，跳过按知识库删除, tenant={}, collection={}", tenantId, collectionName);
                return;
            }
            throw new RuntimeException("ES 关键词索引按知识库删除失败, tenant=" + tenantId
                    + ", collection=" + collectionName, e);
        }
    }

    /**
     * 按 chunkId 删除，且**恒定**带租户条件。
     *
     * <p>为什么不用 bulk 的 delete 操作：ES 的 delete 以 {@code _id} 定位，无法附带过滤条件，
     * 而 {@code _id} 取的是 chunkId。要用租户条件约束删除，就只能在 query 语义里做，
     * 所以这里统一走 delete-by-query，写入侧与删除侧的租户口径因此不可能出现分叉。
     *
     * @return 实际删除的文档数
     */
    private int deleteChunks(String tenantId, List<String> chunkIds) throws Exception {
        var resp = esClient.deleteByQuery(d -> d
                .index(sharedIndex())
                .ignoreUnavailable(true)
                .allowNoIndices(true)
                .refresh(true)
                .query(q -> q.bool(b -> b
                        .filter(f -> f.term(t -> t.field("tenant_id").value(tenantId)))
                        .filter(f -> f.terms(t -> t.field("_id")
                                .terms(v -> v.value(chunkIds.stream()
                                        .map(FieldValue::of)
                                        .collect(Collectors.toList()))))))));
        Long deleted = resp.deleted();
        return deleted == null ? 0 : deleted.intValue();
    }

    private boolean isNotFound(Exception e) {
        return e instanceof ElasticsearchException esException
                && esException.response() != null
                && esException.response().status() == 404;
    }

    /**
     * 判断是否为「索引已存在」异常
     * 并发首次写入时，多个线程同时 exists()=false 后争相 create()，
     * 落后者会收到 resource_already_exists_exception，此时视作创建成功
     */
    private boolean isAlreadyExists(Exception e) {
        return e instanceof ElasticsearchException esException
                && esException.response() != null
                && esException.response().error() != null
                && "resource_already_exists_exception".equals(esException.response().error().type());
    }

    /**
     * 确保共享索引存在，不存在则按 ik 分词创建
     * ik_max_word / ik_smart 需安装 IK 分词插件
     */
    private void ensureSharedIndex() {
        String index = sharedIndex();
        try {
            boolean exists = esClient.indices().exists(e -> e.index(index)).value();
            if (exists) {
                return;
            }
            String analyzer = keywordProperties.getEs().getAnalyzer();
            String searchAnalyzer = keywordProperties.getEs().getSearchAnalyzer();
            esClient.indices().create(c -> c
                    .index(index)
                    .mappings(m -> m
                            .properties("content", p -> p.text(t -> t.analyzer(analyzer).searchAnalyzer(searchAnalyzer)))
                            .properties("tenant_id", p -> p.keyword(k -> k))
                            .properties("collection_name", p -> p.keyword(k -> k))
                            .properties("doc_id", p -> p.keyword(k -> k))
                            .properties("chunk_index", p -> p.integer(i -> i))));
            log.info("ES 关键词共享索引已创建, index={}, analyzer={}/{}", index, analyzer, searchAnalyzer);
        } catch (Exception e) {
            if (isAlreadyExists(e)) {
                // 并发写入时已由其他线程建好同名索引，视作成功
                log.info("ES 关键词共享索引已由并发写入创建，跳过, index={}", index);
                return;
            }
            throw new RuntimeException("ES 关键词共享索引创建失败, index=" + index, e);
        }
    }

    private Map<String, Object> buildDocument(String tenantId, String collectionName, String docId, EmbeddedChunk chunk) {
        String content = chunk.content() == null ? "" : chunk.content();
        if (content.length() > MAX_CONTENT_LENGTH) {
            content = content.substring(0, MAX_CONTENT_LENGTH);
        }

        Map<String, Object> doc = new HashMap<>();
        doc.put("content", content);
        // 租户写进文档体，删除的 delete-by-query 才能按它过滤；
        // 只加字段不加写入，会让所有删除条件静默匹配不到任何行（漏删），比误删更难发现。
        doc.put("tenant_id", tenantId);
        doc.put("collection_name", collectionName);
        doc.put("doc_id", docId);
        doc.put("chunk_index", chunk.index());
        return doc;
    }

    private String sharedIndex() {
        return keywordProperties.sharedIndex();
    }
}
