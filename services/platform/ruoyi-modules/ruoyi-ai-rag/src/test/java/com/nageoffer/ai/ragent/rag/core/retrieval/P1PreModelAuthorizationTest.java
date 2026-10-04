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

package com.nageoffer.ai.ragent.rag.core.retrieval;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.security.AuthorizedResourceScope;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.infra.rerank.RerankService;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import com.nageoffer.ai.ragent.rag.core.retrieval.postprocessor.RerankPostProcessor;
import com.nageoffer.ai.ragent.rag.core.vector.PgVectorRetrieverService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Tag;

/**
 * P1.3b「模型与 IO 之前拒绝」护栏。
 *
 * <p>为什么这条必须单独测：空授权集合最危险的失败模式不是"返回了数据"，而是
 * <b>仍然调用了 embedding / 重排模型</b>。那样虽然最终结果可能是空的，但
 * 未授权请求已经产生了可观测的外部副作用（模型调用、计费、供应商日志），
 * 而且掩盖了"作用域为空"这个本该在更早一层被处理的事实。
 *
 * <p>本测试用 mock 的 {@link EmbeddingService} / {@link RerankService} /
 * {@link JdbcTemplate} 直接断言"零交互"，比断言返回空列表强得多——
 * 返回空也可能是"查了但没命中"。
 */
@Tag("dev")
class P1PreModelAuthorizationTest {

    private static final String TENANT = "T1";
    private static final String MEMBER = "platform:T1:2101";

    // ------------------------------------------------------------------ 向量检索

    @Test
    @DisplayName("空作用域：不调用 embedding、不发 SQL，直接返回空")
    void emptyScopeSkipsEmbeddingAndSql() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PgVectorRetrieverService service = new PgVectorRetrieverService(jdbcTemplate, embeddingService);

        List<RetrievedChunk> result = service.retrieve(AuthorizedRetrievalScope.denied(), request("q", 10));

        assertEquals(List.of(), result);
        verifyNoInteractions(embeddingService);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("作用域非空但有效 collection 为空：仍然不调用 embedding、不发 SQL")
    void scopeWithoutAuthorizedCollectionsSkipsIo() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PgVectorRetrieverService service = new PgVectorRetrieverService(jdbcTemplate, embeddingService);

        // 授权了 KB，但没有任何可检索的物理 collection
        AuthorizedRetrievalScope scope = scopeWith(List.of("kb-t1-private-a"), List.of());

        List<RetrievedChunk> result = service.retrieve(scope, request("q", 10));

        assertEquals(List.of(), result);
        verifyNoInteractions(embeddingService);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("请求的库不在授权集合内：求交后为空，不调用 embedding、不发 SQL（不得回落全库）")
    void requestedCollectionsOutsideScopeDoNotFallBackToGlobal() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PgVectorRetrieverService service = new PgVectorRetrieverService(jdbcTemplate, embeddingService);

        AuthorizedRetrievalScope scope = scopeWith(List.of("kb-t1-private-a"), List.of("kb_t1_private_a"));
        RetrieveRequest request = RetrieveRequest.builder()
                .query("q")
                .topK(10)
                .collectionNames(List.of("kb_t2_same_selector"))
                .build();

        List<RetrievedChunk> result = service.retrieve(scope, request);

        assertEquals(List.of(), result,
                "请求的是一个未授权库；必须返回空，而不是回落到查全部库");
        verifyNoInteractions(embeddingService);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("作用域为 null：抛 ClientException，而不是静默返回空（接线漏了不能伪装成没命中）")
    void nullScopeIsRejectedNotTreatedAsEmpty() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PgVectorRetrieverService service = new PgVectorRetrieverService(jdbcTemplate, embeddingService);

        assertThrows(ClientException.class, () -> service.retrieve(null, request("q", 10)));
        assertThrows(ClientException.class, () -> service.retrieveByVector(null, new float[] {1f, 0f}, request("q", 10)));
        assertThrows(ClientException.class, () -> service.embedAndNormalize(null, "q"));
        verifyNoInteractions(embeddingService);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("embedAndNormalize 空作用域返回空向量且零模型调用")
    void embedAndNormalizeSkipsModelWhenScopeEmpty() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        PgVectorRetrieverService service = new PgVectorRetrieverService(mock(JdbcTemplate.class), embeddingService);

        float[] vector = service.embedAndNormalize(AuthorizedRetrievalScope.denied(), "q");

        assertEquals(0, vector.length, "空作用域不应产生任何向量");
        verifyNoInteractions(embeddingService);
    }

    @Test
    @DisplayName("正向对照：有效作用域确实会调用 embedding 并进入 SQL 路径（证明零交互断言不是空转）")
    void grantedScopeActuallyCallsEmbeddingAndSql() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(embeddingService.embed(anyString())).thenReturn(List.of(1.0f, 0.0f));
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        PgVectorRetrieverService service = new PgVectorRetrieverService(jdbcTemplate, embeddingService);
        AuthorizedRetrievalScope scope = scopeWith(List.of("kb-t1-private-a"), List.of("kb_t1_private_a"));

        service.retrieve(scope, request("q", 10));

        // 正向对照：这两条断言成立，上面那些 verifyNoInteractions 才有意义
        verify(embeddingService, times(1)).embed("q");
        verify(jdbcTemplate, times(1))
                .query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class));
        // 迭代扫描设置也只在守卫通过后执行
        verify(jdbcTemplate).execute("SET hnsw.ef_search = 200");
    }

    @Test
    @DisplayName("正向对照：SQL 必须带 tenant 与 deleted 条件，且 collection 条件来自授权集合")
    void grantedScopeSqlCarriesTenantAndAuthorizedCollections() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(embeddingService.embed(anyString())).thenReturn(List.of(1.0f, 0.0f));

        final String[] capturedSql = new String[1];
        final Object[][] capturedArgs = new Object[1][];
        // 用 ArgumentCaptor 而不是 thenAnswer：mock 的 query 返回空列表时 RowMapper 根本不会被调用，
        // 从 answer 里取 invoke 的实参反而会拿到 lambda 自身的返回类型，断言就变成了测 mock。
        org.mockito.ArgumentCaptor<String> sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Object[]> argsCaptor = org.mockito.ArgumentCaptor.forClass(Object[].class);
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        PgVectorRetrieverService service = new PgVectorRetrieverService(jdbcTemplate, embeddingService);
        AuthorizedRetrievalScope scope = scopeWith(List.of("kb-t1-private-a"), List.of("kb_t1_private_a"));
        service.retrieve(scope, request("q", 10));

        verify(jdbcTemplate).query(sqlCaptor.capture(),
                any(org.springframework.jdbc.core.RowMapper.class), argsCaptor.capture());
        capturedSql[0] = sqlCaptor.getValue();
        capturedArgs[0] = argsCaptor.getValue();

        String sql = capturedSql[0];
        assertNotNull(sql, "有效作用域下必须真的执行检索 SQL");
        assertTrue(sql.contains("tenant_id = ?"), "过滤条件必须包含 tenant_id，实际 SQL=" + sql);
        assertTrue(sql.contains("deleted = 0"), "过滤条件必须排除 tombstone，实际 SQL=" + sql);
        assertTrue(sql.contains("collection_name IN"), "collection 条件必须存在，实际 SQL=" + sql);
        assertTrue(java.util.Arrays.asList(capturedArgs[0]).contains(TENANT),
                "绑定的参数里必须出现当前租户，实际参数=" + java.util.Arrays.toString(capturedArgs[0]));
    }

    // ------------------------------------------------------------------ 重排

    @Test
    @DisplayName("候选为空：不调用 rerank 模型（空集在模型之前收敛）")
    void emptyCandidatesSkipRerankModel() {
        RerankService rerankService = mock(RerankService.class);
        RAGConfigProperties properties = new RAGConfigProperties();
        properties.setRerankEnabled(true);
        RerankPostProcessor processor = new RerankPostProcessor(rerankService, properties);

        SearchContext context = SearchContext.builder().originalQuestion("q").build();
        List<RetrievedChunk> result = processor.process(List.of(), List.of(), context);

        assertEquals(List.of(), result);
        verifyNoInteractions(rerankService);
    }

    @Test
    @DisplayName("SearchContext 未携带作用域时，getAuthorizedScope() 返回拒绝态而不是 null")
    void contextWithoutScopeYieldsDeniedScope() {
        SearchContext context = SearchContext.builder().originalQuestion("q").build();

        AuthorizedRetrievalScope scope = context.getAuthorizedScope();
        assertNotNull(scope, "缺作用域必须是明确的拒绝态，不能是 null（null 极易被当成没有限制）");
        assertTrue(scope.isEmpty(), "缺作用域必须是空授权");
        assertThrows(ClientException.class, context::requireAuthorizedScope,
                "requireAuthorizedScope 用于「必须已授权」的位置，缺作用域应当直接失败");
    }

    // ------------------------------------------------------------------ 辅助

    private static RetrieveRequest request(String query, int topK) {
        return RetrieveRequest.builder().query(query).topK(topK).build();
    }

    /** 构造一个"已授权"作用域：授权资源 + 指定物理 collection。 */
    private static AuthorizedRetrievalScope scopeWith(List<String> kbRefs, List<String> collections) {
        ExecutionPrincipal principal = new ExecutionPrincipal(
                TENANT, "2101", MEMBER, 7, 3, Set.of("ai:kb:retrieve"), "jti-1", "platform",
                1_700_000_000L, 1_700_000_060L);
        AuthorizedResourceScope resourceScope =
                AuthorizedResourceScope.granted(principal, RetrievalScopeAuthorizer.ACTION_KB_RETRIEVE,
                        kbRefs, 1_700_000_000_000L);
        return AuthorizedRetrievalScope.of(resourceScope,kbRefs.stream().map(ref->ref.startsWith("kb:")?ref:"kb:"+ref).toList(),
                List.of("doc:doc-1"),List.of("chunk:chunk-1"),collections);
    }
}
