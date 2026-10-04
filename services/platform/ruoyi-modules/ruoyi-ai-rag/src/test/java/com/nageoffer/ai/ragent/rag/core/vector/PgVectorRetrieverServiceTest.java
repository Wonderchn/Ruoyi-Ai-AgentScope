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
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.security.AuthorizedResourceScope;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.rag.core.retrieval.AuthorizedRetrievalScope;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrieveRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Tag;

@Tag("dev")
class PgVectorRetrieverServiceTest {

    @Test
    @DisplayName("多Collection使用单条IN查询并只携带一个总LIMIT")
    @SuppressWarnings("unchecked")
    void queryMultipleCollectionsWithOneSharedLimit() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        when(embeddingService.embed("报销流程")).thenReturn(List.of(3.0F, 4.0F));
        when(jdbcTemplate.query(
                anyString(),
                any(RowMapper.class),
                any(Object[].class)
        )).thenReturn(List.<RetrievedChunk>of());

        PgVectorRetrieverService service = new PgVectorRetrieverService(jdbcTemplate, embeddingService);
        service.retrieve(grantedScope(), RetrieveRequest.builder()
                .query("报销流程")
                .collectionNames(List.of("kb-finance", "kb-policy"))
                .topK(7)
                .build());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, times(1)).query(
                sqlCaptor.capture(),
                any(RowMapper.class),
                argsCaptor.capture()
        );

        assertTrue(sqlCaptor.getValue().contains("collection_name IN (?, ?)"));
        Object[] args = argsCaptor.getValue();
        // 参数顺序：向量字面量、tenant_id、授权 collection 列表、再次向量字面量（ORDER BY）、LIMIT。
        // tenant 必须排在 collection 之前——过滤条件的第一条永远是租户，它不能被"可选化"。
        assertEquals("tenant-1", args[1], "第二个参数必须是当前租户");
        assertEquals("kb-finance", args[2]);
        assertEquals("kb-policy", args[3]);
        assertEquals(7, args[args.length-1], "SQL 只能有一个跨 Collection 共享的 LIMIT");
        verify(embeddingService, times(1)).embed("报销流程");
    }

    /**
     * 构造生产语义的授权作用域：请求里的两个逻辑库都必须在授权集合内，
     * 否则求交后为空，SQL 就不会带上 {@code collection_name IN (?, ?)}
     */
    private static AuthorizedRetrievalScope grantedScope() {
        List<String> collections = List.of("kb-finance", "kb-policy");
        ExecutionPrincipal principal = new ExecutionPrincipal("tenant-1", "1001",
                ExecutionPrincipal.canonicalMembershipId("tenant-1", "1001"),
                1, 1, Set.of(), "jti-test-1", "test-issuer", 0L, 0L);
        AuthorizedResourceScope resourceScope = AuthorizedResourceScope.granted(
                principal, "kb.retrieve", collections, 0L);
        return AuthorizedRetrievalScope.of(resourceScope,collections.stream().map(id->"kb:"+id).toList(),
                List.of("doc:doc-1"),List.of("chunk:chunk-1"),collections);
    }
}
