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
 * <p>过滤同时约束授权 tenant、collection、KB、文档、chunk 和已发布版本。
 * 没有"只按 collection 过滤"的旧形状，也没有"集合为空 → 查全库"的回落分支。
 *
 * <p>授权 schema 不可用时记录错误并拒绝检索，不回落到无租户条件的旧语句。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
public class PgVectorRetrieverService implements VectorRetrieverService {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;
    private boolean currentFactsRequired;
    private boolean executionEnabled;
    private com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService authorization;
    private com.nageoffer.ai.ragent.framework.security.RevocationGuard revocations;

    @org.springframework.beans.factory.annotation.Autowired
    public void configureExecution(@org.springframework.beans.factory.annotation.Value("${ai.integration.enabled:false}") boolean required,
            @org.springframework.beans.factory.annotation.Value("${ai.integration.high-risk.enabled:false}") boolean enabled,
            org.springframework.beans.factory.ObjectProvider<com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService> resources,
            org.springframework.beans.factory.ObjectProvider<com.nageoffer.ai.ragent.framework.security.RevocationGuard> guards){
        currentFactsRequired=required;executionEnabled=enabled;authorization=resources.getIfAvailable();revocations=guards.getIfAvailable();
    }

    private com.nageoffer.ai.ragent.framework.security.RevocationGuard.Operation currentExecution(AuthorizedRetrievalScope scope){
        if(!currentFactsRequired){return null;}
        if(!executionEnabled || authorization==null || revocations==null){throw new com.nageoffer.ai.ragent.framework.exception.ServiceException("retrieval permit unavailable");}
        var principal=com.nageoffer.ai.ragent.framework.context.PrincipalContext.require();
        scope.requireStillValid(principal,principal.policyVersion(),authorization.currentAclVersion(principal.tenantId()));
        var refs=new java.util.LinkedHashSet<String>(scope.authorizedKbRefs());refs.addAll(scope.authorizedDocRefs());
        for(String ref:refs){
            var verdict=authorization.check(principal,scope.action(),ref);
            if(verdict==com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService.Verdict.UNKNOWN){throw new com.nageoffer.ai.ragent.framework.exception.ServiceException("retrieval authorization unavailable");}
            if(verdict!=com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService.Verdict.GRANT){throw new com.nageoffer.ai.ragent.framework.security.StaleVersionException("retrieval scope no longer authorized");}
        }
        return revocations.enter(principal,scope.action(),"tenant:retrieval");
    }

    @Override
    public List<RetrievedChunk> retrieve(AuthorizedRetrievalScope scope, RetrieveRequest request) {
        // 守卫必须全部落在 embedding 之前：空集语义下的请求连模型调用都不该产生
        if (VectorRetrieverService.mustReturnEmpty(scope, "retrieve") || request == null) {
            return List.of();
        }
        if (!hasExecutableScope(scope, request)) {
            return List.of();
        }
        try(var execution=currentExecution(scope)){
        float[] vector = embedAndNormalize(scope, request.getQuery());
        if (vector.length == 0) {
            return List.of();
        }
        return retrieveByVector(scope, vector, request);
        }
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
        if (tenantId == null || collectionNames.isEmpty() || scope.authorizedDocRefs().isEmpty()
                || scope.publishedChunkRefs().isEmpty() || vector == null || vector.length == 0) {
            return List.of();
        }
        // 单个或多个逻辑库都通过一条 SQL 过滤，LIMIT 是整个范围的总 TopK
        try(var execution=currentExecution(scope)){
            return queryByCollections(scope, vector, tenantId, collectionNames, request.getTopK());
        }
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
        try(var execution=currentExecution(scope)){
            return normalize(toArray(embeddingService.embed(query)));
        }
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
        if (scope.authorizedCollections().isEmpty() || scope.authorizedDocRefs().isEmpty()
                || scope.publishedChunkRefs().isEmpty()) {
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
    private List<RetrievedChunk> queryByCollections(AuthorizedRetrievalScope scope, float[] vector, String tenantId,
                                                    List<String> collectionNames, int limit) {
        // 提升召回率；迭代扫描保证过滤后仍能填满 LIMIT，消除过滤向量检索的召回悬崖（pgvector >= 0.8）
        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        jdbcTemplate.execute("SET hnsw.ef_search = 200");
        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        jdbcTemplate.execute("SET hnsw.iterative_scan = relaxed_order");

        String vectorLiteral = toVectorLiteral(vector);
        String placeholders = collectionNames.stream().map(c -> "?").collect(java.util.stream.Collectors.joining(", "));

        // 绑定顺序：打分向量、tenant、collections、KB、文档、chunk、排序向量、limit。
        var args = new java.util.ArrayList<Object>();
        args.add(vectorLiteral); args.add(tenantId); args.addAll(collectionNames);
        var kbIds = scope.authorizedKbRefs().stream().map(ref -> ref.substring(3)).toList();
        var docIds = scope.authorizedDocRefs().stream().map(ref -> ref.substring(4)).toList();
        var chunkIds = scope.publishedChunkRefs().stream().map(ref -> ref.startsWith("chunk:") ? ref.substring(6) : ref).toList();
        args.addAll(kbIds); args.addAll(docIds); args.addAll(chunkIds);
        args.add(vectorLiteral); args.add(Math.max(1, Math.min(100, limit)));
        String kbSlots = kbIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(","));
        String docSlots = docIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(","));
        String chunkSlots = chunkIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(","));

        // 仅使用服务端投影的授权集合，并核对 registry 当前发布版本。
        try {
            // noinspection SqlDialectInspection,SqlNoDataSourceInspection
            return jdbcTemplate.query(
                    "SELECT v.id, v.content, v.collection_name, 1 - (v.embedding <=> ?::vector) AS score "
                            + "FROM t_knowledge_vector v JOIN t_knowledge_document d ON d.tenant_id=v.tenant_id AND d.id=v.document_id "
                            + "JOIN ai_resource dr ON dr.tenant_id=d.tenant_id AND dr.resource_type='DOCUMENT' AND dr.resource_id=d.id "
                            + "JOIN ai_resource kr ON kr.tenant_id=d.tenant_id AND kr.resource_type='KB' AND kr.resource_id=d.kb_id "
                            + "WHERE v.tenant_id = ? AND v.deleted = 0 AND d.deleted=0 AND d.enabled=1 AND dr.status='ACTIVE' AND kr.status='ACTIVE' "
                            + "AND dr.parent_type='KB' AND dr.parent_id=d.kb_id AND v.doc_version=dr.resource_version "
                            + "AND v.collection_name IN (" + placeholders + ") AND d.kb_id IN ("+kbSlots+") "
                            + "AND v.document_id IN ("+docSlots+") AND v.id IN ("+chunkSlots+") "
                            + "ORDER BY v.embedding <=> ?::vector LIMIT ?",
                    (rs, rowNum) -> RetrievedChunk.builder()
                            .id(rs.getString("id"))
                            .text(rs.getString("content"))
                            .collectionName(rs.getString("collection_name"))
                            .score(rs.getFloat("score"))
                            .build(),
                    args.toArray());
        } catch (BadSqlGrammarException e) {
            // 结构性缺失（V3 未部署）只把检索能力保持关闭：绝不改跑不带 tenant_id 的旧 SQL，
            // 那条语句在共享物理表上等于跨租户检索，是本次改动要消除的缺陷本身。
            log.error("tenant-scoped vector query rejected by schema tenant={} collections={} reason={}; "
                            + "t_knowledge_vector lacks the P1 tenant columns, so the retrieval path stays "
                            + "closed instead of falling back to an unscoped query",
                    tenantId, collectionNames.size(), e.getClass().getSimpleName());
            throw new com.nageoffer.ai.ragent.framework.exception.ServiceException("authorized vector schema unavailable");
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
