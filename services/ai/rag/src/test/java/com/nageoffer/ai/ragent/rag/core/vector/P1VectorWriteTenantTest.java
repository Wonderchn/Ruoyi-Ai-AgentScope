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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.chunk.model.Chunk;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkMetadata;
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.UpsertResp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P1.3b write-path tenant guard.
 *
 * <p>Why this class exists: the read path had three named guard classes, but the write path - which
 * carries the more destructive operations - had none. A delete or an upsert that loses its tenant
 * condition does not return someone else's data, it <b>removes or overwrites</b> it, and nothing in
 * the response would show that. So these assertions are the only thing standing between a future
 * refactor and silent cross-tenant destruction.
 *
 * <p>Three behaviours are pinned:
 * <ol>
 *   <li>a missing or malformed tenant is rejected before any statement is prepared;</li>
 *   <li>the upsert conflict target is the <b>composite</b> {@code (tenant_id, id)} - an id-only
 *       target lets a later write silently replace another tenant's row;</li>
 *   <li>while the C6-gated V3 columns are absent the write path <b>fails loudly</b> instead of
 *       falling back to the old tenant-less statement.</li>
 * </ol>
 */
class P1VectorWriteTenantTest {

    private static final String TENANT = "T1";
    private static final String COLLECTION = "kb_t1_private_a";
    private static final String DOC_ID = "doc-1";
    private static final String CHUNK_ID = "chunk-1";

    // ------------------------------------------------------------------ tenant validation

    @Test
    @DisplayName("Pg: every method rejects a missing or malformed tenant before touching the database")
    void pgRequiresTenantOnEveryEntryPoint() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PgVectorStoreService service = new PgVectorStoreService(jdbcTemplate, new ObjectMapper());
        List<EmbeddedChunk> chunks = List.of(chunk());

        for (String bad : new String[] {null, "", "   ", "T:1", "x".repeat(65)}) {
            assertThrows(RuntimeException.class,
                    () -> service.indexDocumentChunks(bad, COLLECTION, DOC_ID, chunks),
                    "indexDocumentChunks must reject tenant=" + bad);
            assertThrows(RuntimeException.class,
                    () -> service.updateChunk(bad, COLLECTION, DOC_ID, chunks.get(0)),
                    "updateChunk must reject tenant=" + bad);
            assertThrows(RuntimeException.class,
                    () -> service.deleteDocumentVectors(bad, COLLECTION, DOC_ID),
                    "deleteDocumentVectors must reject tenant=" + bad);
            assertThrows(RuntimeException.class,
                    () -> service.deleteChunkById(bad, COLLECTION, CHUNK_ID),
                    "deleteChunkById must reject tenant=" + bad);
            assertThrows(RuntimeException.class,
                    () -> service.deleteChunksByIds(bad, COLLECTION, List.of(CHUNK_ID)),
                    "deleteChunksByIds must reject tenant=" + bad);
        }

        // 关键：拒绝必须发生在 SQL 之前，而不是"发了一条注定失败的语句再抛错"
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("Milvus: every method rejects a missing or malformed tenant before touching the client")
    void milvusRequiresTenantOnEveryEntryPoint() {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        MilvusVectorStoreService service = new MilvusVectorStoreService(milvusClient, defaults());
        List<EmbeddedChunk> chunks = List.of(chunk());

        for (String bad : new String[] {null, "", "   ", "T:1", "x".repeat(65)}) {
            assertThrows(RuntimeException.class,
                    () -> service.indexDocumentChunks(bad, COLLECTION, DOC_ID, chunks));
            assertThrows(RuntimeException.class,
                    () -> service.updateChunk(bad, COLLECTION, DOC_ID, chunks.get(0)));
            assertThrows(RuntimeException.class,
                    () -> service.deleteDocumentVectors(bad, COLLECTION, DOC_ID));
            assertThrows(RuntimeException.class,
                    () -> service.deleteChunkById(bad, COLLECTION, CHUNK_ID));
            assertThrows(RuntimeException.class,
                    () -> service.deleteChunksByIds(bad, COLLECTION, List.of(CHUNK_ID)));
        }

        verifyNoInteractions(milvusClient);
    }

    @Test
    @DisplayName("requireTenant 与主体侧共用同一字符契约（1..64、不含冒号、无首尾空白）")
    void requireTenantMatchesThePrincipalContract() {
        VectorStoreService.requireTenant("T1");
        VectorStoreService.requireTenant("x".repeat(64));

        assertThrows(ClientException.class, () -> VectorStoreService.requireTenant(null));
        assertThrows(ClientException.class, () -> VectorStoreService.requireTenant(""));
        assertThrows(ClientException.class, () -> VectorStoreService.requireTenant(" T1"));
        assertThrows(ClientException.class, () -> VectorStoreService.requireTenant("T:1"));
        assertThrows(ClientException.class, () -> VectorStoreService.requireTenant("x".repeat(65)));
    }

    // ------------------------------------------------------------------ composite conflict key

    @Test
    @DisplayName("Pg upsert 的冲突目标必须是 (tenant_id, id)：只按 id 会让一个租户覆盖另一个租户的行")
    void pgUpsertUsesCompositeConflictTarget() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PgVectorStoreService service = new PgVectorStoreService(jdbcTemplate, new ObjectMapper());
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        service.updateChunk(TENANT, COLLECTION, DOC_ID, chunk());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), argsCaptor.capture());

        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("ON CONFLICT (tenant_id, id)"),
                "冲突目标必须是复合键，实际 SQL=" + sql);
        assertTrue(sql.contains("tenant_id"), "insert 列清单必须包含 tenant_id，实际 SQL=" + sql);
        // 参数顺序：id, tenant, collection, content, metadata, embedding
        assertEquals(CHUNK_ID, argsCaptor.getValue()[0]);
        assertEquals(TENANT, argsCaptor.getValue()[1], "第二个绑定参数必须是租户");
    }

    @Test
    @DisplayName("Pg 五条语句都带租户条件，且 tenant 绑定在第一个占位符位置")
    void pgStatementsAreAllTenantScoped() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PgVectorStoreService service = new PgVectorStoreService(jdbcTemplate, new ObjectMapper());
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);

        service.deleteDocumentVectors(TENANT, COLLECTION, DOC_ID);
        verify(jdbcTemplate).update(sql.capture(), args.capture());
        assertTenantScoped(sql.getValue(), args.getValue(), "deleteDocumentVectors");

        service.deleteChunkById(TENANT, COLLECTION, CHUNK_ID);
        verify(jdbcTemplate, org.mockito.Mockito.times(2)).update(sql.capture(), args.capture());
        assertTenantScoped(sql.getValue(), args.getValue(), "deleteChunkById");
    }

    // ------------------------------------------------------------------ V3 fail-loud gate

    @Test
    @DisplayName("Pg 缺 V3 列时明确失败，且不回落旧无租户语句")
    void pgFailsLoudlyInsteadOfFallingBackWhenSchemaIsMissing() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PgVectorStoreService service = new PgVectorStoreService(jdbcTemplate, new ObjectMapper());
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenThrow(new BadSqlGrammarException("test", "DELETE ...", new SQLException("column does not exist")));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.deleteChunkById(TENANT, COLLECTION, CHUNK_ID),
                "schema 不具备时必须明确失败：能力保持关闭好过写出无法解释归属的行");

        assertTrue(failure.getMessage() != null && failure.getMessage().contains("deleteChunkById"),
                "错误信息必须点名操作，便于定位，实际=" + failure.getMessage());
        // 只应尝试那一条带租户条件的语句：任何第二次调用都意味着发生了回落
        verify(jdbcTemplate, org.mockito.Mockito.times(1)).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("Pg batch insert 缺列时走同一 V3 闸门；用 raw Collection 规避泛型重载推断")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void pgBatchInsertAlsoFailsLoudly() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PgVectorStoreService service = new PgVectorStoreService(jdbcTemplate, new ObjectMapper());
        // batchUpdate 有多个重载，其中 <T> 版本靠 lambda 推断类型；测试里用 raw Collection 固定到同一重载
        when(jdbcTemplate.batchUpdate(anyString(), any(java.util.Collection.class), anyInt(), any()))
                .thenThrow(new BadSqlGrammarException("test", "INSERT ...", new SQLException("column does not exist")));

        assertThrows(IllegalStateException.class,
                () -> service.indexDocumentChunks(TENANT, COLLECTION, DOC_ID, List.of(chunk())));
    }

    // ------------------------------------------------------------------ Milvus filters

    @Test
    @DisplayName("Milvus 写行必须带顶层 tenant_id 标量字段")
    void milvusRowsCarryTenantScalar() {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        MilvusVectorStoreService service = new MilvusVectorStoreService(milvusClient, defaults());
        InsertResp resp = mock(InsertResp.class);
        when(resp.getInsertCnt()).thenReturn(1L);
        when(milvusClient.insert(any(InsertReq.class))).thenReturn(resp);

        service.indexDocumentChunks(TENANT, COLLECTION, DOC_ID, List.of(chunk()));

        ArgumentCaptor<InsertReq> reqCaptor = ArgumentCaptor.forClass(InsertReq.class);
        verify(milvusClient).insert(reqCaptor.capture());
        Object data = reqCaptor.getValue().getData();
        assertNotNull(data, "写入必须有行数据");
        String rows = data.toString();
        assertTrue(rows.contains("tenant_id"), "写行必须带 tenant_id 标量，实际=" + rows);
        assertTrue(rows.contains(TENANT), "写行的 tenant 必须是当前租户，实际=" + rows);
    }

    @Test
    @DisplayName("Milvus 三条删除路径的 filter 都必须以租户条件开头")
    void milvusDeleteFiltersAlwaysStartWithTenantClause() {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        MilvusVectorStoreService service = new MilvusVectorStoreService(milvusClient, defaults());
        DeleteResp resp = mock(DeleteResp.class);
        when(resp.getDeleteCnt()).thenReturn(0L);
        when(milvusClient.delete(any(DeleteReq.class))).thenReturn(resp);

        service.deleteDocumentVectors(TENANT, COLLECTION, DOC_ID);
        service.deleteChunkById(TENANT, COLLECTION, CHUNK_ID);
        service.deleteChunksByIds(TENANT, COLLECTION, List.of(CHUNK_ID));

        ArgumentCaptor<DeleteReq> reqCaptor = ArgumentCaptor.forClass(DeleteReq.class);
        verify(milvusClient, org.mockito.Mockito.times(3)).delete(reqCaptor.capture());

        for (DeleteReq req : reqCaptor.getAllValues()) {
            String filter = req.getFilter();
            assertNotNull(filter, "删除必须带 filter：无 filter 即整库删除");
            assertTrue(filter.startsWith("tenant_id =="),
                    "filter 必须以租户条件开头，实际=" + filter);
            assertTrue(filter.contains("\"" + TENANT + "\""),
                    "filter 必须绑定当前租户，实际=" + filter);
        }
    }

    @Test
    @DisplayName("Pg 管理面拆除必须带租户条件，缺 V3 列时明确失败而非无租户删除")
    void pgAdminDropIsTenantScopedAndFailsLoudly() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PgVectorStoreAdmin admin = new PgVectorStoreAdmin(jdbcTemplate, defaults());

        // (a) 缺租户：必须在发出任何语句之前拒绝——共享表上无租户的删除就是跨租户删行
        for (String bad : new String[] {null, "", "   ", "T:1", "x".repeat(65)}) {
            assertThrows(RuntimeException.class, () -> admin.dropVectorSpace(bad, COLLECTION),
                    "dropVectorSpace 必须拒绝 tenant=" + bad);
        }
        verifyNoInteractions(jdbcTemplate);

        // (b) 有租户：SQL 必须同时限定租户与知识库
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);
        admin.dropVectorSpace(TENANT, COLLECTION);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), argsCaptor.capture());
        assertTrue(sqlCaptor.getValue().contains("tenant_id = ? AND collection_name = ?"),
                "拆除语句必须同时限定租户与知识库，实际 SQL=" + sqlCaptor.getValue());
        assertEquals(TENANT, argsCaptor.getValue()[0], "租户必须是第一个绑定参数");

        // (c) 缺 V3 列：明确失败，绝不回落到只按 collection_name 删除的旧语句
        JdbcTemplate broken = mock(JdbcTemplate.class);
        PgVectorStoreAdmin brokenAdmin = new PgVectorStoreAdmin(broken, defaults());
        when(broken.update(anyString(), any(Object[].class)))
                .thenThrow(new BadSqlGrammarException("test", "DELETE ...", new SQLException("column does not exist")));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> brokenAdmin.dropVectorSpace(TENANT, COLLECTION));
        assertTrue(failure.getMessage().contains("V3"), "错误信息必须指向缺失的迁移，实际=" + failure.getMessage());
        // 只尝试一条语句：第二次调用就意味着发生了回落
        verify(broken, org.mockito.Mockito.times(1)).update(anyString(), any(Object[].class));
    }

    // ------------------------------------------------------------------ helpers

    private static EmbeddedChunk chunk() {
        return new EmbeddedChunk(new Chunk(CHUNK_ID, 0, "content", "embedding text", ChunkMetadata.empty()),
                new float[] {1.0F, 0.0F});
    }

    private static RAGDefaultProperties defaults() {
        RAGDefaultProperties properties = new RAGDefaultProperties();
        properties.setDimension(2);
        properties.setCollectionName("rag_default_store");
        return properties;
    }

    private static void assertTenantScoped(String sql, Object[] args, String operation) {
        assertTrue(sql.contains("tenant_id = ?"), operation + " 必须带租户条件，实际 SQL=" + sql);
        assertNotNull(args);
        assertEquals(TENANT, args[0], operation + " 的租户必须绑定在第一个占位符，实际=" + java.util.Arrays.toString(args));
    }
}
