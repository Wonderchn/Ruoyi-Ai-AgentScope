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
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * PG 向量写侧实现（P1.3b 租户贯通）。
 *
 * <p>{@code t_knowledge_vector} 是所有租户共用的物理表，因此每一条写语句都必须带 {@code tenant_id}：
 * 删除按 {@code (tenant_id, id)} 收窄，upsert 的冲突目标必须是 {@code (tenant_id, id)} 复合键。
 * 冲突目标只剩 {@code (id)} 时，后写的租户会直接覆盖先写租户的同一行。
 *
 * <p>{@code tenant_id} 列与 {@code (tenant_id, id)} 唯一键由 C6 门控的 V3 迁移补齐。迁移未部署时
 * 这些语句必然抛 {@link BadSqlGrammarException}，此时写路径<b>响亮失败</b>（见
 * {@link #requireVectorSchema}），<b>绝不回落到不带租户条件的旧语句</b>。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
public class PgVectorStoreService implements VectorStoreService {

    /** 批量写入：列顺序即绑定顺序，{@code tenant_id} 是第 2 个参数。 */
    private static final String INSERT_SQL =
            "INSERT INTO t_knowledge_vector (id, tenant_id, collection_name, content, metadata, embedding) "
                    + "VALUES (?, ?, ?, ?, ?::jsonb, ?::vector)";

    /**
     * 单 chunk upsert：冲突目标必须是 {@code (tenant_id, id)} 复合唯一键。
     *
     * <p>历史缺陷（已修）：冲突目标曾是 {@code (id)}。{@code id} 只是全局主键，不是租户边界——
     * 同 id 的另一个租户行会被这次 upsert 直接覆盖，等于跨租户改写别人的向量。
     */
    private static final String UPSERT_SQL =
            "INSERT INTO t_knowledge_vector (id, tenant_id, collection_name, content, metadata, embedding) "
                    + "VALUES (?, ?, ?, ?, ?::jsonb, ?::vector) "
                    + "ON CONFLICT (tenant_id, id) DO UPDATE SET "
                    + "collection_name = EXCLUDED.collection_name, content = EXCLUDED.content, "
                    + "metadata = EXCLUDED.metadata, embedding = EXCLUDED.embedding";

    /** 文档级删除：租户 + 逻辑库 + 元数据里的 doc_id，三个条件缺一不可。 */
    private static final String DELETE_DOCUMENT_SQL =
            "DELETE FROM t_knowledge_vector WHERE tenant_id = ? AND collection_name = ? AND metadata->>'doc_id' = ?";

    /** 单 chunk 删除：id 全局唯一只说明"能定位到行"，不说明"有权删这行"。 */
    private static final String DELETE_CHUNK_SQL =
            "DELETE FROM t_knowledge_vector WHERE tenant_id = ? AND id = ?";

    /** 批量删除前缀：{@code tenant_id} 是第 1 个绑定参数，chunkId 占位符紧随其后。 */
    private static final String DELETE_CHUNKS_SQL_PREFIX =
            "DELETE FROM t_knowledge_vector WHERE tenant_id = ? AND id IN (";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void indexDocumentChunks(String tenantId, String collectionName, String docId, List<EmbeddedChunk> chunks) {
        VectorStoreService.requireTenant(tenantId);
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        requireVectorSchema("indexDocumentChunks", () ->
                // noinspection SqlDialectInspection,SqlNoDataSourceInspection
                jdbcTemplate.batchUpdate(
                        INSERT_SQL,
                        chunks, chunks.size(), (ps, chunk) -> {
                            ps.setString(1, chunk.chunkId());
                            ps.setString(2, tenantId);
                            ps.setString(3, collectionName);
                            ps.setString(4, chunk.content());
                            ps.setString(5, buildMetadataJson(docId, chunk));
                            ps.setString(6, toVectorLiteral(chunk.embedding()));
                        }));

        log.info("批量写入向量到 PostgreSQL，tenantId={}, collectionName={}, docId={}, count={}",
                tenantId, collectionName, docId, chunks.size());
    }

    @Override
    public void deleteDocumentVectors(String tenantId, String collectionName, String docId) {
        VectorStoreService.requireTenant(tenantId);
        int deleted = requireVectorSchema("deleteDocumentVectors", () ->
                // noinspection SqlDialectInspection,SqlNoDataSourceInspection
                jdbcTemplate.update(DELETE_DOCUMENT_SQL, tenantId, collectionName, docId));
        log.info("删除文档向量，tenantId={}, collectionName={}, docId={}, deleted={}",
                tenantId, collectionName, docId, deleted);
    }

    @Override
    public void deleteChunkById(String tenantId, String collectionName, String chunkId) {
        VectorStoreService.requireTenant(tenantId);
        requireVectorSchema("deleteChunkById", () ->
                // noinspection SqlDialectInspection,SqlNoDataSourceInspection
                jdbcTemplate.update(DELETE_CHUNK_SQL, tenantId, chunkId));
    }

    @Override
    public void deleteChunksByIds(String tenantId, String collectionName, List<String> chunkIds) {
        VectorStoreService.requireTenant(tenantId);
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        String placeholders = chunkIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(", "));

        // 绑定顺序必须与 SQL 文本里 ? 的出现顺序一致：首先是 tenant，随后才是 chunkId 列表
        Object[] args = new Object[chunkIds.size() + 1];
        args[0] = tenantId;
        for (int i = 0; i < chunkIds.size(); i++) {
            args[i + 1] = chunkIds.get(i);
        }

        int deleted = requireVectorSchema("deleteChunksByIds", () ->
                // noinspection SqlDialectInspection,SqlNoDataSourceInspection
                jdbcTemplate.update(DELETE_CHUNKS_SQL_PREFIX + placeholders + ")", args));
        log.info("批量删除 chunk 向量，tenantId={}, collectionName={}, count={}, deleted={}",
                tenantId, collectionName, chunkIds.size(), deleted);
    }

    @Override
    public void updateChunk(String tenantId, String collectionName, String docId, EmbeddedChunk chunk) {
        VectorStoreService.requireTenant(tenantId);
        requireVectorSchema("updateChunk", () ->
                // noinspection SqlDialectInspection,SqlNoDataSourceInspection
                jdbcTemplate.update(UPSERT_SQL,
                        chunk.chunkId(),
                        tenantId,
                        collectionName,
                        chunk.content(),
                        buildMetadataJson(docId, chunk),
                        toVectorLiteral(chunk.embedding())));
    }

    /**
     * 在"V3 租户列已就位"的前提下执行一条向量写语句。
     *
     * <p>{@code tenant_id} 列与 {@code (tenant_id, id)} 唯一键由 C6 门控的 V3 迁移补齐，迁移未部署时
     * 这些语句必然抛 {@link BadSqlGrammarException}。此时唯一的正确行为是让写路径<b>响亮失败</b>：
     * 记 ERROR 后抛 {@link IllegalStateException} 并写明操作名，<b>绝不回落到不带租户条件的旧语句</b>——
     * 那种"降级"会把共享物理表上的跨租户覆盖 / 跨租户删除缺陷原样放回来，正是本次改动要消除的东西。
     *
     * <p>日志刻意只记操作名、异常类型与 SQLState：SQL 文本、连接凭证与业务数据都不进日志。
     */
    private <T> T requireVectorSchema(String operation, Supplier<T> statement) {
        try {
            return statement.get();
        } catch (BadSqlGrammarException e) {
            SQLException sqlException = e.getSQLException();
            String sqlState = sqlException == null || sqlException.getSQLState() == null
                    ? "-" : sqlException.getSQLState();
            log.error("向量写操作被拒绝：t_knowledge_vector 缺少 C6 门控的 V3 租户列与 (tenant_id, id) 唯一键，"
                            + "operation={}, exception={}, sqlState={}；本实现不回落到不带租户条件的旧语句",
                    operation, e.getClass().getSimpleName(), sqlState);
            throw new IllegalStateException("向量写操作失败（" + operation
                    + "）：t_knowledge_vector 缺少 C6 门控的 V3 租户列 tenant_id 与 (tenant_id, id) 唯一键，"
                    + "请先执行 V3 迁移；本实现不会回落到不带租户条件的旧语句", e);
        }
    }

    private String buildMetadataJson(String docId, EmbeddedChunk chunk) {
        // 结构化元数据统一走唯一序列化点：新增字段自动出现在所有索引后端，不必逐个后端补写
        Map<String, Object> meta = new LinkedHashMap<>(chunk.metadata().toMap());
        meta.put("doc_id", docId);
        meta.put("chunk_index", chunk.index());
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            throw new RuntimeException("元数据序列化失败", e);
        }
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
