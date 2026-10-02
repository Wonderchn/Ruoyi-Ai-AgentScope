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

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.security.AuthorizedResourceScope;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.AuthorizedRetrievalScope;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalScopeAuthorizer;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrieveRequest;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.response.SearchResp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P1.3b「绕过 HTTP 层直接调用检索 Bean」的隔离护栏。
 *
 * <p>威胁模型：鉴权可能被接在 controller / 拦截器上。只要检索 Bean 自己不做守卫，
 * 任何直接注入 {@link VectorRetrieverService} 的调用方（内部工具、定时任务、后续新增的
 * 业务入口）都能用"没有作用域"的调用读到别的租户的数据。所以本用例完全不经过 controller，
 * 直接 new 出 PG / Milvus 两个实现并调用其入口。
 *
 * <p><b>为什么"返回空列表"本身不是证据</b>：查了库但没命中同样返回空列表。
 * 唯一能区分"拒绝了这次请求"与"真的查了但没命中"的证据是<b>零 IO</b>——
 * 没有 embedding 调用、没有 SQL、没有 Milvus 客户端调用。因此每个否定用例都同时断言
 * {@code verifyNoInteractions}；每个否定用例后面都配了正向对照，证明这些零交互断言不是空转。
 *
 * <p>注意：Milvus <b>写</b>侧 {@code MilvusVectorStoreService} 与 PG 写侧都已租户化
 * （见 {@code P1VectorWriteTenantTest}），本类只覆盖读侧直连 Bean 的守卫，两者互补。
 */
class P1RetrieverDirectIsolationTest {

    private static final String TENANT = "T1";

    private static final String AUTHORIZED_KB = "kb-t1-private-a";

    private static final String AUTHORIZED_COLLECTION = "kb_t1_private_a";

    /** 另一个租户"碰巧同名"的选择条件：它绝不能成为授权依据。 */
    private static final String FOREIGN_REQUESTED_COLLECTION = "kb_t2_same_selector";

    // ================================================================= PG

    @Test
    @DisplayName("PG：denied() 作用域下三个入口都不产生任何 IO")
    void pgDeniedScopeDoesNoIoOnAnyEntryPoint() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PgVectorRetrieverService service = new PgVectorRetrieverService(jdbcTemplate, embeddingService);

        assertEquals(List.of(), service.retrieve(AuthorizedRetrievalScope.denied(), request("q", 5)));
        assertEquals(List.of(), service.retrieveByVector(AuthorizedRetrievalScope.denied(),
                new float[] {1F, 0F}, request("q", 5)));
        assertEquals(0, service.embedAndNormalize(AuthorizedRetrievalScope.denied(), "q").length,
                "denied 作用域不得产生任何查询向量");

        // "返回空"不足以证明拒绝：查了库但没命中也返回空。这里断言的是零 IO——
        // 没有模型调用（无计费/供应商日志副作用），也没有任何 SQL 到达数据库。
        verifyNoInteractions(embeddingService);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("PG：授权了 KB 但没有任何可检索 collection，仍然零 IO")
    void pgScopeWithoutAuthorizedCollectionsDoesNoIo() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PgVectorRetrieverService service = new PgVectorRetrieverService(jdbcTemplate, embeddingService);

        // 作用域非空（授权了 KB），但 physical collection 集合为空 —— 这不是"没有限制"，而是"没有可查范围"
        AuthorizedRetrievalScope scope = scopeWith(List.of(AUTHORIZED_KB), List.of());

        assertEquals(List.of(), service.retrieve(scope, request("q", 5)));
        assertEquals(List.of(), service.retrieveByVector(scope, new float[] {1F, 0F}, request("q", 5)));
        assertEquals(0, service.embedAndNormalize(scope, "q").length,
                "embedAndNormalize 也要守卫：即使不查库，也不该为无范围请求调用 embedding 模型");

        // 若只断言"返回空"，一个"拿不到 collection 就查全库"的实现同样会通过；零 IO 才是判据。
        verifyNoInteractions(embeddingService);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("PG：请求了未授权的 collection 时返回空且零 IO（不得回落成查全库）")
    void pgUnauthorizedRequestedCollectionDoesNotFallBackToGlobal() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PgVectorRetrieverService service = new PgVectorRetrieverService(jdbcTemplate, embeddingService);

        AuthorizedRetrievalScope scope = scopeWith(List.of(AUTHORIZED_KB), List.of(AUTHORIZED_COLLECTION));
        RetrieveRequest foreignRequest = RetrieveRequest.builder()
                .query("q")
                .topK(5)
                .collectionNames(List.of(FOREIGN_REQUESTED_COLLECTION))
                .build();

        assertEquals(List.of(), service.retrieve(scope, foreignRequest),
                "请求的库不在授权集合内，求交为空即【本次不该检索】，绝不能回落到查全部库");
        assertEquals(List.of(), service.retrieveByVector(scope, new float[] {1F, 0F}, foreignRequest));

        // 只断言"空结果"无法区分"正确地拒绝了"与"查了别人的库但恰好没命中"；零 IO 才能区分。
        verifyNoInteractions(embeddingService);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("PG：null 作用域抛 ClientException（接线漏了不能伪装成“没命中”）")
    void pgNullScopeIsRejectedWithClientException() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PgVectorRetrieverService service = new PgVectorRetrieverService(jdbcTemplate, embeddingService);

        assertThrows(ClientException.class, () -> service.retrieve(null, request("q", 5)),
                "null 作用域是接线错误，必须失败而不是静默返回空列表");
        assertThrows(ClientException.class, () -> service.retrieveByVector(null, new float[] {1F, 0F}, request("q", 5)));
        assertThrows(ClientException.class, () -> service.embedAndNormalize(null, "q"));

        verifyNoInteractions(embeddingService);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("PG 正向对照：有效作用域真的会调 embedding 并发出带 tenant_id/deleted 的 SQL（证明上面的零交互断言不是空转）")
    void pgGrantedScopePositiveControlBindsTenant() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(embeddingService.embed("q")).thenReturn(List.of(1.0F, 0.0F));

        final String[] capturedSql = new String[1];
        final Object[][] capturedArgs = new Object[1][];
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    capturedSql[0] = invocation.getArgument(0);
                    // 必须走 getRawArguments()：Mockito 5.x 对 getArguments()/getArgument(i) 会
                    // <b>展开 varargs</b>，于是 getArgument(2) 拿到的是第一个 vararg 元素（String），
                    // 而不是调用方绑定的那个 Object[]。这不是实现缺陷——任何实现都无法满足那种取法。
                    capturedArgs[0] = (Object[]) invocation.getRawArguments()[2];
                    return List.<RetrievedChunk>of();
                });

        PgVectorRetrieverService service = new PgVectorRetrieverService(jdbcTemplate, embeddingService);
        AuthorizedRetrievalScope scope = scopeWith(List.of(AUTHORIZED_KB), List.of(AUTHORIZED_COLLECTION));

        assertEquals(List.of(), service.retrieve(scope, request("q", 5)));

        // (a) 有作用域时必须真的产生 embedding 调用
        verify(embeddingService, times(1)).embed("q");
        // (b) 有作用域时必须真的发出检索 SQL，且过滤条件同时含 tenant 与 tombstone
        assertNotNull(capturedSql[0], "有效作用域下必须真的执行检索 SQL");
        assertTrue(capturedSql[0].contains("tenant_id = ?"),
                "SQL 过滤条件必须包含 tenant_id，实际 SQL=" + capturedSql[0]);
        assertTrue(capturedSql[0].contains("deleted = 0"),
                "SQL 必须排除 tombstone，实际 SQL=" + capturedSql[0]);
        assertTrue(capturedSql[0].contains("collection_name IN (?)"),
                "授权 collection 必须作为求交结果下推到 SQL，实际 SQL=" + capturedSql[0]);
        // (c) 绑定顺序必须与 SQL 文本里 ? 的出现顺序一致：打分向量 → tenant → 授权 collection → 排序向量 → LIMIT。
        // 只断言"参数里包含 tenant"太弱：tenant 落到错误的位置上同样会"包含"，
        // 但那样过滤的就不是租户列，而是别的列——隔离会在看不见的地方失效。
        Object[] args = capturedArgs[0];
        assertNotNull(args, "检索 SQL 必须带绑定参数");
        assertEquals(TENANT, args[1], "第二个绑定参数必须是当前租户，实际=" + Arrays.toString(args));
        assertEquals(AUTHORIZED_COLLECTION, args[2],
                "第三个绑定参数必须是授权 collection（紧随 tenant），实际=" + Arrays.toString(args));
    }

    // ================================================================= Milvus

    @Test
    @DisplayName("Milvus：denied() 作用域下三个入口都零 IO（不碰 MilvusClientV2 / EmbeddingService）")
    void milvusDeniedScopeDoesNoIoOnAnyEntryPoint() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        MilvusVectorRetrieverService service =
                new MilvusVectorRetrieverService(embeddingService, milvusClient, milvusProperties());

        assertEquals(List.of(), service.retrieve(AuthorizedRetrievalScope.denied(), request("q", 5)));
        assertEquals(List.of(), service.retrieveByVector(AuthorizedRetrievalScope.denied(),
                new float[] {1F, 0F}, request("q", 5)));
        assertEquals(0, service.embedAndNormalize(AuthorizedRetrievalScope.denied(), "q").length);

        // 共享物理 collection 下，一次客户端调用就可能跨租户取数；所以这里必须证明连客户端都没被碰过，
        // 而不是仅仅证明"返回了空列表"（后者与"搜了但没命中"不可区分）。
        verifyNoInteractions(milvusClient);
        verifyNoInteractions(embeddingService);
    }

    @Test
    @DisplayName("Milvus：授权了 KB 但没有 collection，或请求的是未授权 collection，都零 IO")
    void milvusEmptyIntersectionDoesNoIo() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        MilvusVectorRetrieverService service =
                new MilvusVectorRetrieverService(embeddingService, milvusClient, milvusProperties());

        AuthorizedRetrievalScope noCollections = scopeWith(List.of(AUTHORIZED_KB), List.of());
        assertEquals(List.of(), service.retrieve(noCollections, request("q", 5)));

        AuthorizedRetrievalScope scoped = scopeWith(List.of(AUTHORIZED_KB), List.of(AUTHORIZED_COLLECTION));
        RetrieveRequest foreignRequest = RetrieveRequest.builder()
                .query("q")
                .topK(5)
                .collectionNames(List.of(FOREIGN_REQUESTED_COLLECTION))
                .build();
        assertEquals(List.of(), service.retrieve(scoped, foreignRequest));

        // 空结果不足以证明拒绝：Milvus 侧同样必须证明没有客户端交互、没有模型调用。
        verifyNoInteractions(milvusClient);
        verifyNoInteractions(embeddingService);
    }

    @Test
    @DisplayName("Milvus：null 作用域抛 ClientException 且零 IO")
    void milvusNullScopeIsRejectedWithClientException() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        MilvusVectorRetrieverService service =
                new MilvusVectorRetrieverService(embeddingService, milvusClient, milvusProperties());

        assertThrows(ClientException.class, () -> service.retrieve(null, request("q", 5)));
        assertThrows(ClientException.class, () -> service.retrieveByVector(null, new float[] {1F, 0F}, request("q", 5)));
        assertThrows(ClientException.class, () -> service.embedAndNormalize(null, "q"));

        verifyNoInteractions(milvusClient);
        verifyNoInteractions(embeddingService);
    }

    @Test
    @DisplayName("Milvus 正向对照：有效作用域恰好发起一次 search，且过滤器含 tenant_id")
    void milvusGrantedScopePositiveControlFiltersByTenant() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        when(embeddingService.embed("q")).thenReturn(List.of(1.0F, 0.0F));

        SearchResp.SearchResult hit = SearchResp.SearchResult.builder()
                .entity(Map.of("id", "chunk-1", "content", "hello", "collection_name", AUTHORIZED_COLLECTION))
                .score(0.9F)
                .build();
        SearchResp searchResp = SearchResp.builder()
                .searchResults(List.of(List.of(hit)))
                .build();
        when(milvusClient.search(any(SearchReq.class))).thenReturn(searchResp);

        MilvusVectorRetrieverService service =
                new MilvusVectorRetrieverService(embeddingService, milvusClient, milvusProperties());
        AuthorizedRetrievalScope scope = scopeWith(List.of(AUTHORIZED_KB), List.of(AUTHORIZED_COLLECTION));

        List<RetrievedChunk> result = service.retrieve(scope, request("q", 5));

        // 正向对照：证明上面的零交互断言不是空转——同一条路径在有作用域时确实会走到客户端
        ArgumentCaptor<SearchReq> searchCaptor = ArgumentCaptor.forClass(SearchReq.class);
        verify(milvusClient, times(1)).search(searchCaptor.capture());
        verify(embeddingService, times(1)).embed("q");

        String filter = searchCaptor.getValue().getFilter();
        assertNotNull(filter, "搜索请求必须携带标量过滤表达式，缺失等于在共享 collection 上做无过滤检索");
        assertTrue(filter.contains("tenant_id"), "过滤器必须包含租户条件，实际 filter=" + filter);
        assertTrue(filter.startsWith("tenant_id == \"" + TENANT + "\""),
                "租户条件必须在过滤器最前面，实际 filter=" + filter);
        assertTrue(filter.contains(AUTHORIZED_COLLECTION),
                "过滤器必须把授权 collection 作为条件，实际 filter=" + filter);
        assertEquals("rag_shared_collection", searchCaptor.getValue().getCollectionName(),
                "所有逻辑库共用同一个物理 collection");

        assertEquals(1, result.size(), "命中应被映射为检索结果");
        assertEquals("chunk-1", result.get(0).getId());
        assertEquals(AUTHORIZED_COLLECTION, result.get(0).getCollectionName());
    }

    // ================================================================= 辅助

    private static RetrieveRequest request(String query, int topK) {
        return RetrieveRequest.builder().query(query).topK(topK).build();
    }

    private static RAGDefaultProperties milvusProperties() {
        RAGDefaultProperties properties = new RAGDefaultProperties();
        properties.setCollectionName("rag_shared_collection");
        properties.setMetricType("COSINE");
        return properties;
    }

    /**
     * 构造生产语义的已授权检索作用域（与 RetrievalScopeAuthorizer 的装配一致）。
     *
     * <p>注意 {@code collections} 与 {@code kbRefs} 是两件事：前者是"在哪些逻辑库里查"的选择条件，
     * 后者才是授权事实。传空的 {@code collections} 得到的是"授权了 KB 但没有可查范围"，
     * 而不是"没有限制"。
     */
    private static AuthorizedRetrievalScope scopeWith(List<String> kbRefs, List<String> collections) {
        ExecutionPrincipal principal = new ExecutionPrincipal(
                TENANT, "2101", "platform:T1:2101", 7, 3, Set.of("ai:kb:retrieve"), "jti-1", "platform",
                1_700_000_000L, 1_700_000_060L);
        AuthorizedResourceScope resourceScope = AuthorizedResourceScope.granted(
                principal, RetrievalScopeAuthorizer.ACTION_KB_RETRIEVE, kbRefs, 1_700_000_000_000L);
        return AuthorizedRetrievalScope.of(resourceScope, kbRefs.stream().map(id->"kb:"+id).toList(),
                List.of("doc:doc-1"), List.of("chunk:chunk-1"), collections);
    }
}
