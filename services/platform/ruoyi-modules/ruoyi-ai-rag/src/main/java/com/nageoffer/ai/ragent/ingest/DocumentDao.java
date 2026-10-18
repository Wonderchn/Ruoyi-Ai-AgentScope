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

package com.nageoffer.ai.ragent.ingest;

import com.nageoffer.ai.ragent.rag.core.graph.GraphFileSource;
import com.nageoffer.ai.ragent.rag.core.graph.LightRagClient;
import com.nageoffer.ai.ragent.rag.core.keyword.EsKeywordIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** P2 文档/上传/版本/分块 DAO（V8 表；只读当前 published 版本）。 */
@Slf4j
@Repository
public class DocumentDao implements org.ruoyi.ai.api.runtime.DocumentPort {

    private final JdbcTemplate jdbc;

    /**
     * P2 发布双写（F05:4-A）：{@code rag.keyword.type=es} 时该 bean 才存在；
     * 缺席即"零 ES 外呼"，不是"尽量写"。
     */
    @Autowired(required = false)
    private EsKeywordIndexService keywordIndexService;

    /**
     * P2 发布双写（F05:4-A）：{@code rag.graph.type=lightrag} 时该 bean 才存在；
     * 缺席即零图谱外呼。
     */
    @Autowired(required = false)
    private LightRagClient lightRagClient;

    public DocumentDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 测试入口：显式注入可选后端，避免为可测性把生产构造改成多参。 */
    DocumentDao(JdbcTemplate jdbc, EsKeywordIndexService keywordIndexService, LightRagClient lightRagClient) {
        this.jdbc = jdbc;
        this.keywordIndexService = keywordIndexService;
        this.lightRagClient = lightRagClient;
    }









    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    public boolean matchesEmbeddingModel(String tenantId, java.util.Collection<String> kbIds, String model) {
        if(kbIds==null || kbIds.isEmpty() || model==null) return false;
        for(String kbId:kbIds) {
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.ai_knowledge_base WHERE tenant_id=? AND id=? AND deleted=0 AND embedding_model=?)",Boolean.class,tenantId,kbId,model))) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ documents

    public void insertDocument(String tenantId, String docId, String kbId, String name, String memberId) {
        jdbc.update("INSERT INTO ai_document (tenant_id, doc_id, kb_id, name, member_id) VALUES (?,?,?,?,?)",
                tenantId, docId, kbId, name, memberId);
        if(jdbc.update("INSERT INTO ai_resource(tenant_id,resource_type,resource_id,owner_member_id,owner_dept_id,parent_type,parent_id,status,resource_version,created_by_member) "
                +"SELECT ?,'DOCUMENT',?,?,owner_dept_id,'KB',resource_id,'ACTIVE',1,? FROM ai_resource WHERE tenant_id=? AND resource_type='KB' AND resource_id=? AND status='ACTIVE'",
                tenantId,docId,memberId,memberId,tenantId,kbId)!=1) throw new com.nageoffer.ai.ragent.runtime.RunApiException(com.nageoffer.ai.ragent.runtime.RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
    }

    public Optional<DocumentRow> findDocument(String tenantId, String docId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT doc_id, kb_id, name, member_id, published_version_id, tombstoned_at, created_at "
                            + "FROM ai_document WHERE tenant_id=? AND doc_id=?",
                    DocumentDao::mapDocument, tenantId, docId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private static DocumentRow mapDocument(ResultSet rs, int rowNum) throws SQLException {
        return new DocumentRow(rs.getString("doc_id"), rs.getString("kb_id"), rs.getString("name"),
                rs.getString("member_id"), rs.getString("published_version_id"),
                instant(rs.getTimestamp("tombstoned_at")), instant(rs.getTimestamp("created_at")));
    }

    public List<DocumentRow> listDocuments(String tenantId, String kbId) {
        return jdbc.query("SELECT doc_id, kb_id, name, member_id, published_version_id, tombstoned_at, created_at "
                        + "FROM ai_document WHERE tenant_id=? AND kb_id=? ORDER BY created_at",
                DocumentDao::mapDocument, tenantId, kbId);
    }

    public int tombstoneDocument(String tenantId, String docId) {
        // 先解析 collection：tombstone 之后文档仍可读，但这里保持"清理基于删除前视图"的确定性。
        // 两个可选后端都缺席时保持默认部署的既有行为：不额外查库、不外呼。
        MirrorTarget mirrorTarget = mirrorEnabled() ? findMirrorTarget(tenantId, docId) : null;
        jdbc.update("UPDATE ai_resource SET status='TOMBSTONED',resource_version=resource_version+1,update_time=now() WHERE tenant_id=? AND resource_type='DOCUMENT' AND resource_id=? AND status='ACTIVE'",tenantId,docId);
        int rows = jdbc.update("UPDATE ai_document SET tombstoned_at=now(), updated_at=now() "
                + "WHERE tenant_id=? AND doc_id=? AND tombstoned_at IS NULL", tenantId, docId);
        if (rows > 0) {
            // best-effort：ES / LightRAG 的残留由授权集合与 publishedDocRefs 双重拦截，清理失败不回滚删除
            mirrorDocumentCleanup(tenantId, docId, mirrorTarget);
        }
        return rows;
    }

    public int tombstoneVersions(String tenantId, String docId) {
        return jdbc.update("UPDATE ai_document_version SET state='TOMBSTONED', updated_at=now() "
                + "WHERE tenant_id=? AND doc_id=? AND state NOT IN ('TOMBSTONED')", tenantId, docId);
    }

    public int deleteChunksOfVersion(String tenantId, String versionId) {
        return jdbc.update("DELETE FROM ai_document_chunk WHERE tenant_id=? AND version_id=?", tenantId, versionId);
    }

    // ------------------------------------------------------------------ uploads

    public void insertUpload(String tenantId, String uploadId, String docId, String kbId, String memberId,
                             String filename, String mimeType, long sizeBytes, String sha256, String objectKey) {
        jdbc.update("INSERT INTO ai_document_upload (tenant_id, upload_id, doc_id, kb_id, member_id, filename, "
                        + "mime_type, size_bytes, sha256, object_key, state) VALUES (?,?,?,?,?,?,?,?,?,?,'STORED')",
                tenantId, uploadId, docId, kbId, memberId, filename, mimeType, sizeBytes, sha256, objectKey);
    }

    public Optional<UploadRow> findUpload(String tenantId, String uploadId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT upload_id, doc_id, kb_id, member_id, filename, mime_type, size_bytes, sha256, object_key, "
                            + "state, created_at FROM ai_document_upload WHERE tenant_id=? AND upload_id=?",
                    (rs, rowNum) -> new UploadRow(rs.getString("upload_id"), rs.getString("doc_id"),
                            rs.getString("kb_id"), rs.getString("member_id"), rs.getString("filename"),
                            rs.getString("mime_type"), rs.getLong("size_bytes"), rs.getString("sha256"),
                            rs.getString("object_key"), rs.getString("state"), instant(rs.getTimestamp("created_at"))),
                    tenantId, uploadId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void markUploadState(String tenantId, String uploadId, String state) {
        jdbc.update("UPDATE ai_document_upload SET state=?, updated_at=now() WHERE tenant_id=? AND upload_id=?",
                state, tenantId, uploadId);
    }

    // ------------------------------------------------------------------ versions

    public void insertVersion(String tenantId, String versionId, String docId, String uploadId, String runId) {
        Long order=jdbc.queryForObject("UPDATE ai_document SET version_counter=version_counter+1 WHERE tenant_id=? AND doc_id=? AND tombstoned_at IS NULL RETURNING version_counter",Long.class,tenantId,docId);
        if(order==null) throw new com.nageoffer.ai.ragent.runtime.RunApiException(com.nageoffer.ai.ragent.runtime.RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        jdbc.update("INSERT INTO ai_document_version (tenant_id, version_id, doc_id, upload_id, run_id, state,version_order) "
                + "VALUES (?,?,?,?,?,'STAGING',?)", tenantId, versionId, docId, uploadId, runId,order);
    }

    public Optional<VersionRow> findVersion(String tenantId, String versionId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT version_id, doc_id, upload_id, run_id, state, parse_ref::text AS parse_ref_json, "
                            + "chunk_count, embedding_model, embedding_dimension, published_at "
                            + "FROM ai_document_version WHERE tenant_id=? AND version_id=?",
                    DocumentDao::mapVersion, tenantId, versionId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<VersionRow> findVersionByUpload(String tenantId, String docId, String uploadId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT version_id, doc_id, upload_id, run_id, state, parse_ref::text AS parse_ref_json, "
                            + "chunk_count, embedding_model, embedding_dimension, published_at "
                            + "FROM ai_document_version WHERE tenant_id=? AND doc_id=? AND upload_id=?",
                    DocumentDao::mapVersion, tenantId, docId, uploadId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private static VersionRow mapVersion(ResultSet rs, int rowNum) throws SQLException {
        int dimension = rs.getInt("embedding_dimension");
        return new VersionRow(rs.getString("version_id"), rs.getString("doc_id"), rs.getString("upload_id"),
                rs.getString("run_id"), rs.getString("state"), rs.getString("parse_ref_json"),
                rs.getInt("chunk_count"), rs.getString("embedding_model"),
                rs.wasNull() ? null : dimension, instant(rs.getTimestamp("published_at")));
    }

    /** fence 受控的版本状态推进；返回 false = fence 过期（旧 Worker 提交被拒绝）。 */
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public boolean updateVersionStateFenced(String tenantId, String versionId, String runId, long fence,
                                            String state, String parseRefJson, Integer chunkCount,
                                            String embeddingModel, Integer embeddingDimension) {
        var owned=jdbc.queryForList("SELECT fence FROM ai_run WHERE tenant_id=? AND run_id=? AND fence=? AND lease_until>now() AND status='RUNNING' FOR UPDATE",tenantId,runId,fence);
        if(owned.isEmpty()) return false;
        return jdbc.update("UPDATE ai_document_version v SET state=?, "
                        + "parse_ref=coalesce(?::jsonb, v.parse_ref), "
                        + "chunk_count=coalesce(?, v.chunk_count), "
                        + "embedding_model=coalesce(?, v.embedding_model), "
                        + "embedding_dimension=coalesce(?, v.embedding_dimension), updated_at=now() "
                        + "WHERE v.tenant_id=? AND v.version_id=? AND v.state NOT IN ('TOMBSTONED','PUBLISHED') "
                        + "AND EXISTS (SELECT 1 FROM ai_run r WHERE r.tenant_id=v.tenant_id AND r.run_id=? AND r.fence=? AND r.lease_until>now() AND r.status='RUNNING')",
                state, parseRefJson, chunkCount, embeddingModel, embeddingDimension,
                tenantId, versionId, runId, fence) == 1;
    }

    /** 发布：版本置 PUBLISHED 并原子切换文档发布指针（短事务，由调用方保证）。 */
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public boolean publishVersionFenced(String tenantId, String docId, String versionId, String runId, long fence) {
        List<String> current=jdbc.query("SELECT published_version_id FROM ai_document WHERE tenant_id=? AND doc_id=? AND tombstoned_at IS NULL FOR UPDATE",
                (rs,n)->rs.getString(1),tenantId,docId);
        if(current.isEmpty()) return false;
        if(versionId.equals(current.get(0))) {
            // 同版本重放：DB 已是发布态，重放双写保证"上次外呼失败"可由重试修复；
            // 同 _id 覆盖与先删后写使这一步幂等。
            mirrorAfterCommit(tenantId, docId, versionId);
            return true;
        }
        if(current.get(0)!=null && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT newer.version_order>older.version_order FROM ai_document_version newer,ai_document_version older WHERE newer.tenant_id=? AND newer.version_id=? AND older.tenant_id=newer.tenant_id AND older.version_id=?",Boolean.class,tenantId,versionId,current.get(0)))) return false;
        int updated = jdbc.update("UPDATE ai_document_version v SET state='PUBLISHED', published_at=now(), updated_at=now() "
                        + "WHERE v.tenant_id=? AND v.version_id=? AND v.doc_id=? AND v.state='READY_TO_PUBLISH' "
                        + "AND EXISTS (SELECT 1 FROM ai_run r WHERE r.tenant_id=v.tenant_id AND r.run_id=? AND r.fence=? AND r.lease_until>now() AND r.status='RUNNING') "
                        + "AND EXISTS (SELECT 1 FROM ai_document d WHERE d.tenant_id=v.tenant_id AND d.doc_id=v.doc_id "
                        + "  AND d.tombstoned_at IS NULL)",
                tenantId, versionId, docId, runId, fence);
        if (updated == 0) {
            return false;
        }
        int chunks = jdbc.update("UPDATE ai_document_chunk SET state='PUBLISHED' "
                + "WHERE tenant_id=? AND version_id=? AND state='STAGING'", tenantId, versionId);
        if (chunks == 0) {
            throw new IllegalStateException("publish produced no visible chunks");
        }
        jdbc.update("UPDATE ai_document SET published_version_id=?, updated_at=now() "
                + "WHERE tenant_id=? AND doc_id=? AND tombstoned_at IS NULL", versionId, tenantId, docId);
        jdbc.update("UPDATE ai_document_version SET state='SUPERSEDED', updated_at=now() "
                        + "WHERE tenant_id=? AND doc_id=? AND version_id<>? AND state='PUBLISHED'",
                tenantId, docId, versionId);
        // 发布成功之后才双写可选后端；失败只 WARN，不回滚发布。
        mirrorAfterCommit(tenantId, docId, versionId);
        return true;
    }

    // ------------------------------------------------------------------ P2 发布双写（F05:4-A）

    private record MirrorTarget(String kbId, String collectionName) {
    }

    private record MirrorChunk(String chunkKey, int chunkIndex, String content,
                               String kbId, String collectionName) {
    }

    /**
     * 发布完成后的可选后端双写：ES 关键词 + LightRAG 图谱，均为 best-effort。
     *
     * <p>后端 bean 缺席（{@code rag.keyword.type != es} / {@code rag.graph.type != lightrag}）
     * 时该方法不发起任何外呼。写入前按 docId 清理旧版本，保证 supersede 后共享索引/图谱里
     * 只留当前发布版本；重复发布由「同 _id 覆盖」保持幂等。
     */
    /**
     * 事务内只登记提交后动作：外呼 ES/LightRAG 不得占着 {@code ai_document} 的行锁；
     * 只有真正 COMMITTED 的发布才允许双写（回滚不得在外部后端留下幻影版本）。
     * 无 Spring 事务（单测/直接调用）时同步执行。
     */
    private void mirrorAfterCommit(String tenantId, String docId, String versionId) {
        if (!mirrorEnabled()) {
            // 默认部署（pg/none/无 graph）：零额外查询、零外呼，与发布前逐行为等价
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            mirrorPublishedVersion(tenantId, docId, versionId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    mirrorPublishedVersion(tenantId, docId, versionId);
                }
            }
        });
    }

    private void mirrorPublishedVersion(String tenantId, String docId, String versionId) {
        try {
            List<MirrorChunk> rows = jdbc.query(
                    "SELECT c.chunk_key, c.chunk_index, c.content, c.kb_id, kb.collection_name "
                            + "FROM ai_document_chunk c "
                            + "JOIN ai_document d ON d.tenant_id=c.tenant_id AND d.doc_id=c.doc_id "
                            + "JOIN platform.ai_knowledge_base kb ON kb.tenant_id=d.tenant_id AND kb.id=d.kb_id "
                            + "WHERE c.tenant_id=? AND c.version_id=? AND c.state='PUBLISHED' "
                            + "AND d.tombstoned_at IS NULL AND kb.deleted=0 ORDER BY c.chunk_index",
                    (rs, rowNum) -> new MirrorChunk(rs.getString("chunk_key"), rs.getInt("chunk_index"),
                            rs.getString("content"), rs.getString("kb_id"), rs.getString("collection_name")),
                    tenantId, versionId);
            if (rows.isEmpty()) {
                log.warn("P2 发布双写跳过：没有可投影的已发布 chunk, tenant={}, docId={}, versionId={}",
                        tenantId, docId, versionId);
                return;
            }
            MirrorTarget target = new MirrorTarget(rows.get(0).kbId(), rows.get(0).collectionName());
            mirrorKeywords(target, tenantId, docId, versionId, rows);
            mirrorGraph(target, docId, rows);
        } catch (Exception e) {
            log.warn("P2 发布双写读取投影失败（best-effort，不影响发布） tenant={}, docId={}, versionId={}, reason={}",
                    tenantId, docId, versionId, e.getMessage());
        }
    }

    private void mirrorKeywords(MirrorTarget target, String tenantId, String docId, String versionId,
                                List<MirrorChunk> rows) {
        if (keywordIndexService == null) {
            return;
        }
        try {
            // 旧版本/旧文档残留先按 docId 清理，再写当前版本，避免共享索引里叠加多个版本
            keywordIndexService.deleteDocumentIndex(tenantId, target.collectionName(), docId);
        } catch (Exception e) {
            log.warn("P2 发布双写 ES 旧版本清理失败（已跳过） tenant={}, docId={}, reason={}",
                    tenantId, docId, e.getMessage());
        }
        try {
            List<EsKeywordIndexService.PublishedChunk> chunks = rows.stream()
                    .map(row -> new EsKeywordIndexService.PublishedChunk(
                            row.chunkKey(), row.chunkIndex(), row.content()))
                    .collect(Collectors.toList());
            keywordIndexService.indexPublishedChunks(tenantId, target.collectionName(), target.kbId(),
                    docId, versionId, chunks);
        } catch (Exception e) {
            log.warn("P2 发布双写 ES 失败（best-effort，不影响发布） tenant={}, docId={}, versionId={}, reason={}",
                    tenantId, docId, versionId, e.getMessage());
        }
    }

    private void mirrorGraph(MirrorTarget target, String docId, List<MirrorChunk> rows) {
        if (lightRagClient == null) {
            return;
        }
        try {
            // 图谱是文档级：先删旧文档再插入新版本全文，与 legacy 装饰器「先删后建」同序
            lightRagClient.deleteByDoc(docId);
        } catch (Exception e) {
            log.warn("P2 发布双写 LightRAG 旧文档清理失败（已跳过） docId={}, reason={}",
                    docId, e.getMessage());
        }
        try {
            String text = rows.stream()
                    .map(MirrorChunk::content)
                    .filter(content -> content != null && !content.isBlank())
                    .collect(Collectors.joining("\n\n"));
            lightRagClient.insertText(text, GraphFileSource.encode(target.collectionName(), docId));
        } catch (Exception e) {
            log.warn("P2 发布双写 LightRAG 失败（best-effort，不影响发布） docId={}, reason={}",
                    docId, e.getMessage());
        }
    }

    private void mirrorDocumentCleanup(String tenantId, String docId, MirrorTarget target) {
        if (target == null) {
            return;
        }
        if (keywordIndexService != null) {
            try {
                keywordIndexService.deleteDocumentIndex(tenantId, target.collectionName(), docId);
            } catch (Exception e) {
                log.warn("P2 tombstone 清理 ES 失败（best-effort） tenant={}, docId={}, reason={}",
                        tenantId, docId, e.getMessage());
            }
        }
        if (lightRagClient != null) {
            try {
                lightRagClient.deleteByDoc(docId);
            } catch (Exception e) {
                log.warn("P2 tombstone 清理 LightRAG 失败（best-effort） docId={}, reason={}",
                        docId, e.getMessage());
            }
        }
    }

    private boolean mirrorEnabled() {
        return keywordIndexService != null || lightRagClient != null;
    }

    private MirrorTarget findMirrorTarget(String tenantId, String docId) {
        try {
            return jdbc.queryForObject(
                    "SELECT d.kb_id, kb.collection_name FROM ai_document d "
                            + "JOIN platform.ai_knowledge_base kb ON kb.tenant_id=d.tenant_id AND kb.id=d.kb_id "
                            + "WHERE d.tenant_id=? AND d.doc_id=? AND kb.deleted=0",
                    (rs, rowNum) -> new MirrorTarget(rs.getString("kb_id"),
                            rs.getString("collection_name")),
                    tenantId, docId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        } catch (Exception e) {
            log.warn("P2 tombstone 解析 collection 失败（已跳过可选后端清理） tenant={}, docId={}, reason={}",
                    tenantId, docId, e.getMessage());
            return null;
        }
    }

    public long countChunks(String tenantId, String versionId, String state) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM ai_document_chunk WHERE tenant_id=? AND version_id=? AND state=?",
                Long.class, tenantId, versionId, state);
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------------ chunks

    public void insertStagingChunk(String tenantId, String versionId, String chunkKey, int chunkIndex,
                                   String docId, String kbId, String content, String contentHash, int charCount,
                                   Integer pageFrom, Integer pageTo, List<Float> embedding, String embeddingModel) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","p2-chunk-capacity:"+tenantId);
        boolean present=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ai_document_chunk WHERE tenant_id=? AND version_id=? AND chunk_key=?)",Boolean.class,tenantId,versionId,chunkKey));
        if(!present && jdbc.queryForObject("SELECT count(*) FROM ai_document_chunk c JOIN ai_document d ON d.tenant_id=c.tenant_id AND d.doc_id=c.doc_id JOIN ai_document_version v ON v.tenant_id=c.tenant_id AND v.version_id=c.version_id WHERE c.tenant_id=? AND d.tombstoned_at IS NULL AND v.state NOT IN ('TOMBSTONED','SUPERSEDED') AND c.state IN ('STAGING','PUBLISHED')",Long.class,tenantId)>=50000)
            throw new com.nageoffer.ai.ragent.runtime.RunApiException(com.nageoffer.ai.ragent.runtime.RunErrorCode.BUDGET_EXCEEDED,"tenant chunk capacity exceeded");
        // tombstone 优先：已删除版本/文档不接受晚到 chunk 写入
        jdbc.update("INSERT INTO ai_document_chunk (tenant_id, version_id, chunk_key, chunk_index, doc_id, kb_id, "
                        + "content, content_hash, char_count, page_from, page_to, state, embedding, embedding_model) "
                        + "SELECT ?,?,?,?,?,?,?,?,?,?,?,'STAGING',?::vector,? "
                        + "WHERE EXISTS (SELECT 1 FROM ai_document_version v JOIN ai_document d "
                        + "  ON d.tenant_id=v.tenant_id AND d.doc_id=v.doc_id "
                        + "  WHERE v.tenant_id=? AND v.version_id=? AND v.state NOT IN ('TOMBSTONED') "
                        + "  AND d.tombstoned_at IS NULL) "
                        + "ON CONFLICT (tenant_id, version_id, chunk_key) DO NOTHING",
                tenantId, versionId, chunkKey, chunkIndex, docId, kbId, content, contentHash, charCount,
                pageFrom, pageTo, toVectorLiteral(embedding), embeddingModel, tenantId, versionId);
    }

    private static String toVectorLiteral(List<Float> embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(embedding.get(i));
        }
        return sb.append(']').toString();
    }

    /** 检索：只读当前 published 版本 + 指定 KB + 未删除 + 已发布 chunk。 */
    public List<RetrievedChunk> searchPublished(String tenantId, List<String> kbIds, List<Float> queryVector,
                                                int topK, double minScore) {
        if (kbIds == null || kbIds.isEmpty()) {
            return List.of();
        }
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < kbIds.size(); i++) {
            placeholders.append(i == 0 ? "?" : ",?");
        }
        String sql = "SELECT c.doc_id, c.version_id, c.chunk_key, c.chunk_index, c.content, c.page_from, c.page_to, "
                + "(1 - (c.embedding <=> ?::vector)) AS score "
                + "FROM ai_document_chunk c JOIN ai_document d ON d.tenant_id=c.tenant_id AND d.doc_id=c.doc_id "
                + "WHERE c.tenant_id=? AND c.state='PUBLISHED' AND c.version_id=d.published_version_id "
                + "AND d.tombstoned_at IS NULL AND c.kb_id IN (" + placeholders + ") "
                + "AND c.embedding IS NOT NULL "
                + "ORDER BY c.embedding <=> ?::vector LIMIT ?";
        List<Object> args = new java.util.ArrayList<>();
        args.add(toVectorLiteral(queryVector));
        args.add(tenantId);
        args.addAll(kbIds);
        args.add(toVectorLiteral(queryVector));
        args.add(Math.max(1, topK));
        List<RetrievedChunk> rows = jdbc.query(sql, (rs, rowNum) -> new RetrievedChunk(
                rs.getString("doc_id"), rs.getString("version_id"), rs.getString("chunk_key"),
                rs.getInt("chunk_index"), rs.getString("content"),
                (Integer) rs.getObject("page_from"), (Integer) rs.getObject("page_to"),
                rs.getDouble("score")), args.toArray());
        List<RetrievedChunk> filtered = new java.util.ArrayList<>();
        for (RetrievedChunk row : rows) {
            if (row.score() >= minScore) {
                filtered.add(row);
            }
        }
        return filtered;
    }
}
