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

package com.nageoffer.ai.ragent.rag.core.vector;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.rag.core.retrieval.AuthorizedRetrievalScope;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrieveRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * pgvector 向量检索实现（P1.3b 检索守卫）。
 *
 * <p>每个入口的第一段代码都是同一组守卫，且都在 embedding / SQL 之前：
 * {@code scope == null} 抛 {@code ClientException}；空作用域直接空集；
 * 请求侧 collection 与授权集合求交为空直接空集；作用域缺租户直接空集。
 *
 * <p>过滤条件只有一种形状：<b>授权 tenant 与授权 collection 并列</b>。
 * 没有"只按 collection 过滤"的旧形状，也没有"集合为空 → 查全库"的回落分支。
 *
 * <p><b>V3 之前的失败语义</b>：{@code tenant_id} / {@code deleted} 列由 C6 门控的
 * V3 迁移补齐。列还不存在时查询会以 {@link BadSqlGrammarException} 失败，
 * 实现把它记成 ERROR 并返回空集，<b>绝不</b>回落到不带 tenant 的旧语句——
 * 宁可检索能力保持关闭，也不能在共享物理表上做跨租户检索。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
public class PgVectorRetrieverService implements VectorRetrieverService {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;

    @Override
    public List<RetrievedChunk> retrieve(AuthorizedRetrievalScope scope, RetrieveRequest request) {
        // 守卫必须全部落在 embedding 之前：空集语义下的请求连模型调用都不该产生
        if (VectorRetrieverService.mustReturnEmpty(scope, "retrieve") || request == null) {
            return List.of();
        }
        if (!hasExecutableScope(scope, request)) {
            return List.of();
        }
        float[] vector = embedAndNormalize(scope, request.getQuery());
        if (vector.length == 0) {
            return List.of();
        }
        return retrieveByVector(scope, vector, request);
    }

    @Override
    public List<RetrievedChunk> retrieveByVector(AuthorizedRetrievalScope scope, float[] vector,
                                                 RetrieveRequest request) {
        if (VectorRetrieverService.mustReturnEmpty(scope, "retrieveByVector") || request == null) {
            return List.of();
        }
        // 授权作用域必须绑定租户：缺租户的集合条件不构成可执行的检索条件
        String tenantId = scopeTenant(scope);
        List<String> collectionNames = scope.effectiveCollections(request.getEffectiveCollectionNames());
        if (tenantId == null || collectionNames.isEmpty() || vector == null || vector.length == 0) {
            return List.of();
        }
        // 单个或多个逻辑库都通过一条 SQL 过滤，LIMIT 是整个范围的总 TopK
        return queryByCollections(vector, tenantId, collectionNames, request.getTopK());
    }

    @Override
    public float[] embedAndNormalize(AuthorizedRetrievalScope scope, String query) {
        // 本方法不查库，但会调 embedding 模型：守卫先行，未授权请求不产生任何模型调用。
        // 这里必须与 retrieve() 用同一把尺（hasExecutableScope），否则
        // "授权了 KB、但没有任何可检索 collection" 这种作用域会绕开 retrieve() 的守卫，
        // 从本方法直接触发一次白付的模型调用。
        if (VectorRetrieverService.mustReturnEmpty(scope, "embedAndNormalize")
                || !hasExecutableScope(scope, null)) {
            return new float[0];
        }
        return normalize(toArray(embeddingService.embed(query)));
    }

    @Override
    public boolean supportsGlobalRetrieval() {
        return true;
    }

    /**
     * 本次是否具备可执行的检索范围。
     *
     * <p>请求侧的 collectionNames 只是选择条件，必须与授权集合求交；交集为空
     * 表示"本次不该发起任何检索"，绝不回落成"查全库"。
     */
    private static boolean hasExecutableScope(AuthorizedRetrievalScope scope, RetrieveRequest request) {
        if (scopeTenant(scope) == null) {
            return false;
        }
        if (scope.authorizedCollections().isEmpty()) {
            // 没有授权 collection 就没有可执行范围；request 为 null 时（embedAndNormalize）
            // 也只能得到这个结论，不能因为"没有选择条件"就当成"全库可选"。
            return false;
        }
        return request == null
                || !scope.effectiveCollections(request.getEffectiveCollectionNames()).isEmpty();
    }

    /** 作用域租户；null/空白表示这不是一个可执行的检索作用域。 */
    private static String scopeTenant(AuthorizedRetrievalScope scope) {
        String tenantId = scope.tenantId();
        return tenantId == null || tenantId.isBlank() ? null : tenantId;
    }

    /**
     * 在"授权 tenant + 授权 collection"范围内执行一次向量相似度检索。
     *
     * <p>单库与全局共用此方法：单库传单元素列表，全局传多元素列表。
     * tenant 条件不是可选的收窄项，而是与 collection 并列的必需条件。
     */
    private List<RetrievedChunk> queryByCollections(float[] vector, String tenantId,
                                                    List<String> collectionNames, int limit) {
        // 提升召回率；迭代扫描保证过滤后仍能填满 LIMIT，消除过滤向量检索的召回悬崖（pgvector >= 0.8）
        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        jdbcTemplate.execute("SET hnsw.ef_search = 200");
        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        jdbcTemplate.execute("SET hnsw.iterative_scan = relaxed_order");

        String vectorLiteral = toVectorLiteral(vector);
        String placeholders = collectionNames.stream().map(c -> "?").collect(java.util.stream.Collectors.joining(", "));

        // 绑定顺序必须与 SQL 文本里 ? 的出现顺序一致：SELECT 打分表达式 → tenant → collections → ORDER BY → LIMIT
        Object[] args = new Object[collectionNames.size() + 4];
        args[0] = vectorLiteral;
        args[1] = tenantId;
        for (int i = 0; i < collectionNames.size(); i++) {
            args[i + 2] = collectionNames.get(i);
        }
        args[collectionNames.size() + 2] = vectorLiteral;
        args[collectionNames.size() + 3] = limit;

        // 文档级 / 版本级收窄暂不做：document_id、doc_version 与 tenant_id、deleted 一样由 C6 门控的
        // V3 迁移补齐。等列存在后必须按"AuthorizedRetrievalScope.authorizedDocRefs() ∩ 请求 refs"下推；
        // 现在就拿请求侧原始 refs 过滤只是"看起来收窄"，并没有与授权事实求交，反而会掩盖未授权命中。
        try {
            // noinspection SqlDialectInspection,SqlNoDataSourceInspection
            return jdbcTemplate.query(
                    "SELECT id, content, collection_name, 1 - (embedding <=> ?::vector) AS score "
                            + "FROM t_knowledge_vector "
                            + "WHERE tenant_id = ? AND deleted = 0 AND collection_name IN (" + placeholders + ") "
                            + "ORDER BY embedding <=> ?::vector LIMIT ?",
                    (rs, rowNum) -> RetrievedChunk.builder()
                            .id(rs.getString("id"))
                            .text(rs.getString("content"))
                            .collectionName(rs.getString("collection_name"))
                            .score(rs.getFloat("score"))
                            .build(),
                    args);
        } catch (BadSqlGrammarException e) {
            // 结构性缺失（V3 未部署）只把检索能力保持关闭：绝不改跑不带 tenant_id 的旧 SQL，
            // 那条语句在共享物理表上等于跨租户检索，是本次改动要消除的缺陷本身。
            log.error("tenant-scoped vector query rejected by schema tenant={} collections={} reason={}; "
                            + "t_knowledge_vector lacks the P1 tenant columns, so the retrieval path stays "
                            + "closed instead of falling back to an unscoped query",
                    tenantId, collectionNames.size(), e.getClass().getSimpleName());
            return List.of();
        }
    }

    private float[] normalize(float[] vector) {
        float norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }

    private float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        return sb.append("]").toString();
    }
}
