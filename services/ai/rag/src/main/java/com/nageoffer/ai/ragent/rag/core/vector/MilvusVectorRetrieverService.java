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

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.AuthorizedRetrievalScope;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrieveRequest;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Milvus 向量检索实现（P1.3b 检索守卫）。
 *
 * <p>所有逻辑库共用同一个物理 Collection，标量过滤表达式就是唯一的隔离边界：
 * <b>每个请求都必须带租户条件</b>，collection 条件必须来自
 * {@link AuthorizedRetrievalScope#effectiveCollections} 的求交结果。
 *
 * <p>历史缺陷（已修）：过滤表达式曾在 collection 列表为空时返回 {@code null}，
 * 调用方随即省略 {@code filter(...)}，等于把"没有条件"当成"没有限制"——
 * 一次请求就能扫遍共享库。现在 {@link #buildFilter} 永不返回空表达式，
 * {@link #searchShared} 也拒绝空白表达式。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "milvus", matchIfMissing = true)
public class MilvusVectorRetrieverService implements VectorRetrieverService {

    private final EmbeddingService embeddingService;
    private final MilvusClientV2 milvusClient;
    private final RAGDefaultProperties ragDefaultProperties;

    @Override
    public List<RetrievedChunk> retrieve(AuthorizedRetrievalScope scope, RetrieveRequest retrieveParam) {
        // 守卫必须在 embedding 之前：空集语义下的请求连模型调用都不该产生
        if (VectorRetrieverService.mustReturnEmpty(scope, "retrieve") || retrieveParam == null) {
            return List.of();
        }
        if (scope.effectiveCollections(retrieveParam.getEffectiveCollectionNames()).isEmpty()) {
            return List.of();
        }
        float[] vector = embedAndNormalize(scope, retrieveParam.getQuery());
        if (vector.length == 0) {
            return List.of();
        }
        return retrieveByVector(scope, vector, retrieveParam);
    }

    @Override
    public List<RetrievedChunk> retrieveByVector(AuthorizedRetrievalScope scope, float[] vector,
                                                 RetrieveRequest retrieveParam) {
        if (VectorRetrieverService.mustReturnEmpty(scope, "retrieveByVector") || retrieveParam == null) {
            return List.of();
        }
        // 请求侧 collection 与授权集合求交为空即"本次不该发起检索"，绝不回落成"查全库"
        List<String> collectionNames = scope.effectiveCollections(retrieveParam.getEffectiveCollectionNames());
        if (collectionNames.isEmpty() || vector == null || vector.length == 0) {
            return List.of();
        }
        // 单个或多个逻辑库都在共享物理 Collection 中一次过滤，topK 是整个过滤范围的总预算
        String filter = buildFilter(scope.tenantId(), collectionNames);
        return searchShared(vector, filter, retrieveParam.getTopK());
    }

    @Override
    public float[] embedAndNormalize(AuthorizedRetrievalScope scope, String query) {
        // 本方法不查库，但会调 embedding 模型：守卫先行，未授权请求不产生任何模型调用。
        // 判定与 retrieve() 用同一把尺：空作用域、或"授权了 KB 但没有任何可检索 collection"
        // 都不可能查出任何东西，因此也不该为此白付一次模型调用。
        if (VectorRetrieverService.mustReturnEmpty(scope, "embedAndNormalize")
                || scope.authorizedCollections().isEmpty()) {
            return new float[0];
        }
        return normalize(toArray(embeddingService.embed(query)));
    }

    @Override
    public boolean supportsGlobalRetrieval() {
        return true;
    }

    /**
     * 构造共享 Collection 内的标量过滤表达式。
     *
     * <p>表达式<b>永远</b>包含租户条件：tenant 为 null/空白时退化成恒假条件
     * （{@code tenant_id == ""}），而不是返回 {@code null}——后者会被调用方当作
     * "没有过滤条件"，等于放开整个共享库。
     *
     * <p>collection 列表为空时同样退化成恒假条件：正常链路在
     * {@link #retrieveByVector} 已按空交集返回空集，这里是防止后续调用方
     * 绕过守卫直接调用时的兜底。
     *
     * <p>版本条件（{@code kb_version} / {@code doc_version}）暂不拼接：
     * 这两个字段要等 V3 写路径真正落库之后再进过滤表达式，否则条件恒不匹配，
     * 等于静默查不到任何东西。
     */
    static String buildFilter(String tenantId, List<String> collectionNames) {
        String tenantClause = "tenant_id == \"" + escapeFilterValue(StrUtil.isBlank(tenantId) ? "" : tenantId) + "\"";
        if (collectionNames == null || collectionNames.isEmpty()) {
            return tenantClause + " and collection_name == \"\"";
        }
        if (collectionNames.size() == 1) {
            return tenantClause + " and collection_name == \"" + escapeFilterValue(collectionNames.get(0)) + "\"";
        }
        String inList = collectionNames.stream()
                .map(MilvusVectorRetrieverService::escapeFilterValue)
                .map(value -> "\"" + value + "\"")
                .collect(Collectors.joining(", "));
        return tenantClause + " and collection_name in [" + inList + "]";
    }

    /**
     * 转义表达式字符串字面量里的反斜杠与双引号，避免库名/租户名改写过滤条件。
     */
    static String escapeFilterValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 在共享 collection 内执行一次向量检索。
     *
     * <p>{@code filter} 不是可选项：为空说明调用方绕过了守卫，此时只记 ERROR 并返回空集，
     * 既不发请求，也不"顺手"查全库。
     *
     * @param filter 标量过滤表达式（不可为空白）
     */
    private List<RetrievedChunk> searchShared(float[] vector, String filter, int topK) {
        if (StrUtil.isBlank(filter)) {
            log.error("milvus shared search rejected: blank scalar filter, "
                    + "refusing to search the whole shared collection");
            return List.of();
        }
        if (topK <= 0) {
            log.error("milvus shared search rejected: non-positive topK={}", topK);
            return List.of();
        }

        List<BaseVector> vectors = List.of(new FloatVec(vector));

        Map<String, Object> params = new HashMap<>();
        params.put("metric_type", ragDefaultProperties.getMetricType());
        params.put("ef", 128);

        SearchReq searchReq = SearchReq.builder()
                .collectionName(ragDefaultProperties.getCollectionName())
                .annsField("embedding")
                .data(vectors)
                .topK(topK)
                .searchParams(params)
                .filter(filter)
                .outputFields(List.of("id", "content", "collection_name", "metadata"))
                .build();

        SearchResp resp = milvusClient.search(searchReq);
        List<List<SearchResp.SearchResult>> results = resp.getSearchResults();

        if (results == null || results.isEmpty()) {
            return List.of();
        }

        return results.get(0).stream()
                .map(r -> RetrievedChunk.builder()
                        .id(Objects.toString(r.getEntity().get("id"), ""))
                        .text(Objects.toString(r.getEntity().get("content"), ""))
                        .collectionName(Objects.toString(r.getEntity().get("collection_name"), null))
                        .score(r.getScore())
                        .build())
                .collect(Collectors.toList());
    }

    private static float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    private static float[] normalize(float[] v) {
        double sum = 0.0;
        for (float x : v) sum += x * x;
        double len = Math.sqrt(sum);
        float[] nv = new float[v.length];
        for (int i = 0; i < v.length; i++) nv[i] = (float) (v[i] / len);
        return nv;
    }
}
