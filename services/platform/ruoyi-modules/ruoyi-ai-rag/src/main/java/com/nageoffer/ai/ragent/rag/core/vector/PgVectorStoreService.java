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
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
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
 * <p>{@code ai_knowledge_vector} 是所有租户共用的物理表，因此每一条写语句都必须带 {@code tenant_id}：
 * 删除按 {@code (tenant_id, id)} 收窄，upsert 的冲突目标必须是 {@code (tenant_id, id)} 复合键。
 * 冲突目标只剩 {@code (id)} 时，后写的租户会直接覆盖先写租户的同一行。
 *
 * <p>{@code tenant_id} 列与 {@code (tenant_id, id)} 唯一键由 C6 门控的 V3 迁移补齐。迁移未部署时
 * 这些语句必然抛 {@link BadSqlGrammarException}，此时写路径<b>响亮失败</b>（见
 * {@link #requireVectorSchema}），<b>绝不回落到不带租户条件的旧语句</b>。
 *
 * <p><b>四列归属契约（D-VEC-1）。</b>向量行的归属是 {@code tenant_id/deleted/document_id/doc_version}
 * 四列合取（V7 冻结迁移；{@code document_id}/{@code doc_version} 为 {@code NOT NULL} 且无默认值）。
 * 写语句因此必须携带 {@code document_id}（调用实参 docId）与 {@code doc_version}
 * （在树权威 {@code ai_knowledge_document.version}，见 {@link #requireDocumentVersion}）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
public class PgVectorStoreService implements VectorStoreService {

    /**
     * 批量写入：列顺序即绑定顺序，{@code tenant_id} 是第 2 个参数。
     *
     * <p><b>D-VEC-1 四列契约。</b>部署表 {@code ai_knowledge_vector} 的 {@code document_id}
     * 与 {@code doc_version} 两列由 V7 冻结迁移（{@code V7__unified_ai_domain.sql:1848/1991-1992}）
     * 定为 {@code NOT NULL} 且无默认值：向量行的归属是四列合取
     * （{@code tenant_id/deleted/document_id/doc_version}，任一无值即拒绝）。历史列集
     * （id/tenant_id/collection_name/content/metadata/embedding）必然触发
     * {@code null value in column "document_id" ... violates not-null constraint}，故这两列必须写入。
     */
    private static final String INSERT_SQL =
            "INSERT INTO ai_knowledge_vector (id, tenant_id, collection_name, document_id, doc_version, content, metadata, embedding) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::vector)";

    /**
     * 单 chunk upsert：冲突目标必须是 {@code (tenant_id, id)} 复合唯一键。
     *
     * <p>历史缺陷（已修）：冲突目标曾是 {@code (id)}。{@code id} 只是全局主键，不是租户边界——
     * 同 id 的另一个租户行会被这次 upsert 直接覆盖，等于跨租户改写别人的向量。
     *
     * <p>冲突更新同样刷新 {@code document_id}/{@code doc_version}：这两列是行的归属身份，
     * 不能停在插入时的旧值（同一 chunkId 被重新绑定到别处时会留下错误的归属）。
     */
    private static final String UPSERT_SQL =
            "INSERT INTO ai_knowledge_vector (id, tenant_id, collection_name, document_id, doc_version, content, metadata, embedding) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::vector) "
                    + "ON CONFLICT (tenant_id, id) DO UPDATE SET "
                    + "collection_name = EXCLUDED.collection_name, document_id = EXCLUDED.document_id, "
                    + "doc_version = EXCLUDED.doc_version, content = EXCLUDED.content, "
                    + "metadata = EXCLUDED.metadata, embedding = EXCLUDED.embedding";

    /**
     * 文档级删除：租户 + 逻辑库 + 归属列 {@code document_id}，三个条件缺一不可。
     *
     * <p>按 V7 四列契约改用 {@code document_id} 列定位归属（不再走 {@code metadata->>'doc_id'}）：
     * 该列由写入侧保证非空，谓词与列契约同源；租户条件保留在最前，绝不放宽。
     */
    private static final String DELETE_DOCUMENT_SQL =
            "DELETE FROM ai_knowledge_vector WHERE tenant_id = ? AND collection_name = ? AND document_id = ?";

    /**
     * {@code doc_version} 值源：在树权威 {@code ai_knowledge_document.version}
     * （F05:2 版本面，V29 乐观锁计数器）。租户条件是必需项——跨租户读版本等同越权。
     */
    private static final String DOC_VERSION_SQL =
            "SELECT version FROM ai_knowledge_document WHERE tenant_id = ? AND id = ? AND deleted = 0";

    /** 单 chunk 删除：id 全局唯一只说明"能定位到行"，不说明"有权删这行"。 */
    private static final String DELETE_CHUNK_SQL =
            "DELETE FROM ai_knowledge_vector WHERE tenant_id = ? AND id = ?";

    /** 批量删除前缀：{@code tenant_id} 是第 1 个绑定参数，chunkId 占位符紧随其后。 */
    private static final String DELETE_CHUNKS_SQL_PREFIX =
            "DELETE FROM ai_knowledge_vector WHERE tenant_id = ? AND id IN (";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void indexDocumentChunks(String tenantId, String collectionName, String docId, List<EmbeddedChunk> chunks) {
        VectorStoreService.requireTenant(tenantId);
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        long docVersion = requireDocumentVersion(tenantId, docId);

        requireVectorSchema("indexDocumentChunks", () ->
                // noinspection SqlDialectInspection,SqlNoDataSourceInspection
                jdbcTemplate.batchUpdate(
                        INSERT_SQL,
                        chunks, chunks.size(), (ps, chunk) -> {
                            ps.setString(1, chunk.chunkId());
                            ps.setString(2, tenantId);
                            ps.setString(3, collectionName);
                            ps.setString(4, docId);
                            ps.setLong(5, docVersion);
                            ps.setString(6, chunk.content());
                            ps.setString(7, buildMetadataJson(docId, chunk));
                            ps.setString(8, toVectorLiteral(chunk.embedding()));
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
        long docVersion = requireDocumentVersion(tenantId, docId);
        requireVectorSchema("updateChunk", () ->
                // noinspection SqlDialectInspection,SqlNoDataSourceInspection
                jdbcTemplate.update(UPSERT_SQL,
                        chunk.chunkId(),
                        tenantId,
                        collectionName,
                        docId,
                        docVersion,
                        chunk.content(),
                        buildMetadataJson(docId, chunk),
                        toVectorLiteral(chunk.embedding())));
    }

    /**
     * 解析向量行的文档归属版本 {@code doc_version}：以租户为条件读在树权威
     * {@code ai_knowledge_document.version}（F05:2 版本面）。
     *
     * <p>为什么在这里读而不是由调用方传入：{@link VectorStoreService} 的方法签名（任务单允许面）
     * 不携带版本，而四列契约要求每条向量行都带非空版本；权威只在知识文档行上，写侧就地取，
     * 与"某条向量属于哪个文档的哪个版本"同一处决定。
     *
     * <p>文档缺失 / 不属于当前租户 / 无版本（列不存在或为 NULL）→ <b>响亮失败</b>，绝不猜一个版本值：
     * 猜出来的版本会让检索侧的四列合取失配，把这条向量静默变成不可见或错误归属。
     */
    private long requireDocumentVersion(String tenantId, String docId) {
        if (docId == null || docId.isBlank()) {
            throw new ClientException("向量写入缺少文档归属：document_id 不能为空");
        }
        Long version;
        try {
            version = jdbcTemplate.queryForObject(DOC_VERSION_SQL, Long.class, tenantId, docId);
        } catch (EmptyResultDataAccessException noDocument) {
            version = null;
        }
        if (version == null) {
            throw new ClientException("文档不存在或不属于当前租户，无法确定向量 doc_version：docId=" + docId);
        }
        return version;
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
            log.error("向量写操作被拒绝：ai_knowledge_vector 缺少 C6 门控的 V3 租户列与 (tenant_id, id) 唯一键，"
                            + "operation={}, exception={}, sqlState={}；本实现不回落到不带租户条件的旧语句",
                    operation, e.getClass().getSimpleName(), sqlState);
            throw new IllegalStateException("向量写操作失败（" + operation
                    + "）：ai_knowledge_vector 缺少 C6 门控的 V3 租户列 tenant_id 与 (tenant_id, id) 唯一键，"
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
