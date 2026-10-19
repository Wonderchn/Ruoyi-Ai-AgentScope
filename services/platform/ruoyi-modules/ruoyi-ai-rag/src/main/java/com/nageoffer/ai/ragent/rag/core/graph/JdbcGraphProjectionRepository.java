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

package com.nageoffer.ai.ragent.rag.core.graph;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 投影仓储的 PostgreSQL 实现（platform 副本，D7：不镜像回 services/ai）。
 *
 * <p>每条语句都带 {@code tenant_id} 谓词；读侧 join 主库文档/知识库并要求
 * {@code deleted = 0}，因此软删文档/库的残留投影不会回到调用方。
 *
 * <p>SQL 以包内常量暴露，供 {@code GraphProjectionRepositorySqlTest} 做静态护栏
 * （无真库时的租户谓词证据）。
 */
public class JdbcGraphProjectionRepository implements GraphProjectionRepository {

    static final String SQL_FIND_KB = """
            SELECT id, name, collection_name
            FROM platform.ai_knowledge_base
            WHERE tenant_id = :tenant AND id = :kbId AND deleted = 0
            """;

    static final String SQL_FIND_KB_BY_COLLECTION = """
            SELECT id, name, collection_name
            FROM platform.ai_knowledge_base
            WHERE tenant_id = :tenant AND collection_name = :collectionName AND deleted = 0
            """;

    static final String SQL_FIND_DOCUMENT = """
            SELECT d.id, d.kb_id, d.doc_name, d.status, d.chunk_count, k.collection_name
            FROM platform.ai_knowledge_document d
            JOIN platform.ai_knowledge_base k
              ON k.tenant_id = d.tenant_id AND k.id = d.kb_id AND k.deleted = 0
            WHERE d.tenant_id = :tenant AND d.id = :docId AND d.deleted = 0
            """;

    static final String SQL_FIND_REBUILD_DOCUMENTS = """
            SELECT d.id, d.kb_id, d.doc_name, d.status, d.chunk_count, k.collection_name
            FROM platform.ai_knowledge_document d
            JOIN platform.ai_knowledge_base k
              ON k.tenant_id = d.tenant_id AND k.id = d.kb_id AND k.deleted = 0
            WHERE d.tenant_id = :tenant AND d.kb_id = :kbId AND d.deleted = 0
              AND d.status = 'success' AND COALESCE(d.chunk_count, 0) > 0
            ORDER BY d.create_time ASC, d.id ASC
            """;

    static final String SQL_LOAD_TEXT = """
            SELECT string_agg(content, E'\\n\\n' ORDER BY chunk_index, id) AS body
            FROM platform.ai_knowledge_chunk
            WHERE tenant_id = :tenant AND doc_id = :docId AND deleted = 0 AND enabled = 1
            """;

    static final String SQL_FIND_ENTITIES = """
            SELECT e.node_id, e.entity_key, e.name, e.entity_type, e.description,
                   e.doc_id, e.kb_id, d.doc_name, k.collection_name
            FROM platform.ai_graph_entity e
            JOIN platform.ai_knowledge_document d
              ON d.tenant_id = e.tenant_id AND d.id = e.doc_id AND d.kb_id = e.kb_id AND d.deleted = 0
            JOIN platform.ai_knowledge_base k
              ON k.tenant_id = e.tenant_id AND k.id = e.kb_id AND k.deleted = 0
            WHERE e.tenant_id = :tenant AND e.deleted = 0
              AND e.kb_id IN (:kbs)
              AND (CAST(:docId AS VARCHAR) IS NULL OR e.doc_id = :docId)
            ORDER BY e.node_id ASC, e.doc_id ASC
            """;

    static final String SQL_FIND_RELATIONS = """
            SELECT r.source_node_id, r.target_node_id, r.source_key, r.target_key,
                   r.relation_label, r.description, r.source_file_path,
                   r.doc_id, r.kb_id, d.doc_name, k.collection_name
            FROM platform.ai_graph_relation r
            JOIN platform.ai_knowledge_document d
              ON d.tenant_id = r.tenant_id AND d.id = r.doc_id AND d.kb_id = r.kb_id AND d.deleted = 0
            JOIN platform.ai_knowledge_base k
              ON k.tenant_id = r.tenant_id AND k.id = r.kb_id AND k.deleted = 0
            WHERE r.tenant_id = :tenant AND r.deleted = 0
              AND r.kb_id IN (:kbs)
              AND (CAST(:docId AS VARCHAR) IS NULL OR r.doc_id = :docId)
            ORDER BY r.source_node_id ASC, r.target_node_id ASC, r.relation_label ASC, r.doc_id ASC
            """;

    static final String SQL_FIND_LABELS = """
            SELECT e.name AS label
            FROM platform.ai_graph_entity e
            JOIN platform.ai_knowledge_document d
              ON d.tenant_id = e.tenant_id AND d.id = e.doc_id AND d.kb_id = e.kb_id AND d.deleted = 0
            JOIN platform.ai_knowledge_base k
              ON k.tenant_id = e.tenant_id AND k.id = e.kb_id AND k.deleted = 0
            WHERE e.tenant_id = :tenant AND e.deleted = 0
              AND e.kb_id IN (:kbs)
              AND (CAST(:docId AS VARCHAR) IS NULL OR e.doc_id = :docId)
              AND (CAST(:keywordPattern AS VARCHAR) IS NULL
                   OR e.name ILIKE :keywordPattern OR e.entity_key ILIKE :keywordPattern)
            GROUP BY e.name
            ORDER BY COUNT(*) DESC, e.name ASC
            LIMIT :limitValue
            """;

    static final String SQL_COUNT_UNATTRIBUTED_ENTITIES = """
            SELECT COUNT(*)
            FROM platform.ai_graph_entity e
            LEFT JOIN platform.ai_knowledge_document d
              ON d.tenant_id = e.tenant_id AND d.id = e.doc_id AND d.kb_id = e.kb_id AND d.deleted = 0
            LEFT JOIN platform.ai_knowledge_base k
              ON k.tenant_id = e.tenant_id AND k.id = e.kb_id AND k.deleted = 0
            WHERE e.tenant_id = :tenant AND e.deleted = 0
              AND e.kb_id IN (:kbs)
              AND (CAST(:docId AS VARCHAR) IS NULL OR e.doc_id = :docId)
              AND (d.id IS NULL OR k.id IS NULL)
            """;

    static final String SQL_COUNT_UNATTRIBUTED_RELATIONS = """
            SELECT COUNT(*)
            FROM platform.ai_graph_relation r
            LEFT JOIN platform.ai_knowledge_document d
              ON d.tenant_id = r.tenant_id AND d.id = r.doc_id AND d.kb_id = r.kb_id AND d.deleted = 0
            LEFT JOIN platform.ai_knowledge_base k
              ON k.tenant_id = r.tenant_id AND k.id = r.kb_id AND k.deleted = 0
            WHERE r.tenant_id = :tenant AND r.deleted = 0
              AND r.kb_id IN (:kbs)
              AND (CAST(:docId AS VARCHAR) IS NULL OR r.doc_id = :docId)
              AND (d.id IS NULL OR k.id IS NULL)
            """;

    static final String SQL_STATS_ENTITIES = """
            SELECT COUNT(*) FROM platform.ai_graph_entity
            WHERE tenant_id = :tenant AND deleted = 0
            """;

    static final String SQL_STATS_RELATIONS = """
            SELECT COUNT(*) FROM platform.ai_graph_relation
            WHERE tenant_id = :tenant AND deleted = 0
            """;

    static final String SQL_STATS_DOCS = """
            SELECT COUNT(DISTINCT e.doc_id)
            FROM platform.ai_graph_entity e
            JOIN platform.ai_knowledge_document d
              ON d.tenant_id = e.tenant_id AND d.id = e.doc_id AND d.kb_id = e.kb_id AND d.deleted = 0
            JOIN platform.ai_knowledge_base k
              ON k.tenant_id = e.tenant_id AND k.id = e.kb_id AND k.deleted = 0
            WHERE e.tenant_id = :tenant AND e.deleted = 0
            """;

    static final String SQL_STATS_LAST_SYNCED = """
            SELECT MAX(sync_time) AS last_synced_at
            FROM (
              SELECT update_time AS sync_time FROM platform.ai_graph_entity
              WHERE tenant_id = :tenant AND deleted = 0
              UNION ALL
              SELECT update_time AS sync_time FROM platform.ai_graph_relation
              WHERE tenant_id = :tenant AND deleted = 0
            ) sync_rows
            """;

    static final String SQL_DELETE_ENTITY = """
            DELETE FROM platform.ai_graph_entity
            WHERE tenant_id = :tenant AND doc_id = :docId
            """;

    static final String SQL_DELETE_RELATION = """
            DELETE FROM platform.ai_graph_relation
            WHERE tenant_id = :tenant AND doc_id = :docId
            """;

    static final String SQL_DELETE_KB_ENTITY = """
            DELETE FROM platform.ai_graph_entity
            WHERE tenant_id = :tenant AND kb_id = :kbId
            """;

    static final String SQL_DELETE_KB_RELATION = """
            DELETE FROM platform.ai_graph_relation
            WHERE tenant_id = :tenant AND kb_id = :kbId
            """;

    static final String SQL_INSERT_ENTITY = """
            INSERT INTO platform.ai_graph_entity
              (id, tenant_id, kb_id, doc_id, node_id, entity_key, name, entity_type,
               description, source_file_path)
            VALUES
              (:id, :tenant, :kbId, :docId, :nodeId, :entityKey, :name, :entityType,
               :description, :sourceFilePath)
            """;

    static final String SQL_INSERT_RELATION = """
            INSERT INTO platform.ai_graph_relation
              (id, tenant_id, kb_id, doc_id, source_node_id, target_node_id, source_key,
               target_key, relation_label, description, source_file_path)
            VALUES
              (:id, :tenant, :kbId, :docId, :sourceNodeId, :targetNodeId, :sourceKey,
               :targetKey, :relationLabel, :description, :sourceFilePath)
            """;

    private static final RowMapper<KbRow> KB_MAPPER = (rs, rowNum) -> new KbRow(
            rs.getString("id"), rs.getString("name"), rs.getString("collection_name"));

    private static final RowMapper<DocRow> DOC_MAPPER = (rs, rowNum) -> new DocRow(
            rs.getString("id"), rs.getString("kb_id"), rs.getString("doc_name"),
            rs.getString("collection_name"), rs.getString("status"),
            rs.getObject("chunk_count", Integer.class));

    private static final RowMapper<EntityRow> ENTITY_MAPPER = (rs, rowNum) -> new EntityRow(
            rs.getString("node_id"), rs.getString("entity_key"), rs.getString("name"),
            rs.getString("entity_type"), rs.getString("description"), rs.getString("doc_id"),
            rs.getString("kb_id"), rs.getString("doc_name"), rs.getString("collection_name"));

    private static final RowMapper<RelationRow> RELATION_MAPPER = (rs, rowNum) -> new RelationRow(
            rs.getString("source_node_id"), rs.getString("target_node_id"),
            rs.getString("source_key"), rs.getString("target_key"), rs.getString("relation_label"),
            rs.getString("description"), rs.getString("source_file_path"), rs.getString("doc_id"),
            rs.getString("kb_id"), rs.getString("doc_name"), rs.getString("collection_name"));

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcGraphProjectionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<KbRow> findKnowledgeBase(String tenantId, String kbId) {
        List<KbRow> rows = jdbc.query(SQL_FIND_KB,
                new MapSqlParameterSource("tenant", tenantId).addValue("kbId", kbId), KB_MAPPER);
        return rows.stream().findFirst();
    }

    @Override
    public List<KbRow> findKnowledgeBasesByCollection(String tenantId, String collectionName) {
        return jdbc.query(SQL_FIND_KB_BY_COLLECTION,
                new MapSqlParameterSource("tenant", tenantId).addValue("collectionName", collectionName),
                KB_MAPPER);
    }

    @Override
    public Optional<DocRow> findDocument(String tenantId, String docId) {
        List<DocRow> rows = jdbc.query(SQL_FIND_DOCUMENT,
                new MapSqlParameterSource("tenant", tenantId).addValue("docId", docId), DOC_MAPPER);
        return rows.stream().findFirst();
    }

    @Override
    public List<DocRow> findRebuildDocuments(String tenantId, String kbId) {
        return jdbc.query(SQL_FIND_REBUILD_DOCUMENTS,
                new MapSqlParameterSource("tenant", tenantId).addValue("kbId", kbId), DOC_MAPPER);
    }

    @Override
    public String loadDocumentText(String tenantId, String docId) {
        List<String> bodies = jdbc.queryForList(SQL_LOAD_TEXT,
                new MapSqlParameterSource("tenant", tenantId).addValue("docId", docId), String.class);
        return bodies.isEmpty() ? null : bodies.get(0);
    }

    @Override
    public List<EntityRow> findEntities(String tenantId, Collection<String> kbIds, String docId) {
        if (empty(kbIds)) {
            return List.of();
        }
        return jdbc.query(SQL_FIND_ENTITIES, scopeParams(tenantId, kbIds, docId), ENTITY_MAPPER);
    }

    @Override
    public List<RelationRow> findRelations(String tenantId, Collection<String> kbIds, String docId) {
        if (empty(kbIds)) {
            return List.of();
        }
        return jdbc.query(SQL_FIND_RELATIONS, scopeParams(tenantId, kbIds, docId), RELATION_MAPPER);
    }

    @Override
    public List<String> findLabels(String tenantId, Collection<String> kbIds, String docId,
                                   String keyword, int limit) {
        if (empty(kbIds)) {
            return List.of();
        }
        MapSqlParameterSource params = scopeParams(tenantId, kbIds, docId)
                .addValue("keywordPattern", likePattern(keyword))
                .addValue("limitValue", limit);
        return jdbc.queryForList(SQL_FIND_LABELS, params, String.class);
    }

    @Override
    public int countUnattributed(String tenantId, Collection<String> kbIds, String docId) {
        if (empty(kbIds)) {
            return 0;
        }
        MapSqlParameterSource params = scopeParams(tenantId, kbIds, docId);
        Integer entities = jdbc.queryForObject(SQL_COUNT_UNATTRIBUTED_ENTITIES, params, Integer.class);
        Integer relations = jdbc.queryForObject(SQL_COUNT_UNATTRIBUTED_RELATIONS, params, Integer.class);
        return (entities == null ? 0 : entities) + (relations == null ? 0 : relations);
    }

    @Override
    public ProjectionStats stats(String tenantId) {
        MapSqlParameterSource params = new MapSqlParameterSource("tenant", tenantId);
        Long entities = jdbc.queryForObject(SQL_STATS_ENTITIES, params, Long.class);
        Long relations = jdbc.queryForObject(SQL_STATS_RELATIONS, params, Long.class);
        Long docs = jdbc.queryForObject(SQL_STATS_DOCS, params, Long.class);
        Timestamp lastSynced = jdbc.queryForObject(SQL_STATS_LAST_SYNCED, params, Timestamp.class);
        return new ProjectionStats(entities == null ? 0 : entities,
                relations == null ? 0 : relations,
                docs == null ? 0 : docs,
                lastSynced == null ? null : lastSynced.getTime());
    }

    @Override
    public void replaceDocumentProjection(String tenantId, String kbId, String docId,
                                          List<EntityWriteRow> entities, List<RelationWriteRow> relations) {
        MapSqlParameterSource scope = new MapSqlParameterSource("tenant", tenantId).addValue("docId", docId);
        jdbc.update(SQL_DELETE_ENTITY, scope);
        jdbc.update(SQL_DELETE_RELATION, scope);
        if (entities != null && !entities.isEmpty()) {
            jdbc.batchUpdate(SQL_INSERT_ENTITY, entities.stream().map(row -> {
                MapSqlParameterSource params = new MapSqlParameterSource()
                        .addValue("id", IdUtil.getSnowflakeNextIdStr())
                        .addValue("tenant", tenantId)
                        .addValue("kbId", kbId)
                        .addValue("docId", docId)
                        .addValue("nodeId", row.nodeId())
                        .addValue("entityKey", row.entityKey())
                        .addValue("name", row.name())
                        .addValue("entityType", row.entityType())
                        .addValue("description", row.description())
                        .addValue("sourceFilePath", row.sourceFilePath());
                return params;
            }).toArray(SqlParameterSource[]::new));
        }
        if (relations != null && !relations.isEmpty()) {
            jdbc.batchUpdate(SQL_INSERT_RELATION, relations.stream().map(row -> new MapSqlParameterSource()
                    .addValue("id", IdUtil.getSnowflakeNextIdStr())
                    .addValue("tenant", tenantId)
                    .addValue("kbId", kbId)
                    .addValue("docId", docId)
                    .addValue("sourceNodeId", row.sourceNodeId())
                    .addValue("targetNodeId", row.targetNodeId())
                    .addValue("sourceKey", row.sourceKey())
                    .addValue("targetKey", row.targetKey())
                    .addValue("relationLabel", row.relationLabel())
                    .addValue("description", row.description())
                    .addValue("sourceFilePath", row.sourceFilePath()))
                    .toArray(SqlParameterSource[]::new));
        }
    }

    @Override
    public void deleteKnowledgeBaseProjection(String tenantId, String kbId) {
        MapSqlParameterSource params = new MapSqlParameterSource("tenant", tenantId).addValue("kbId", kbId);
        jdbc.update(SQL_DELETE_KB_ENTITY, params);
        jdbc.update(SQL_DELETE_KB_RELATION, params);
    }

    private static MapSqlParameterSource scopeParams(String tenantId, Collection<String> kbIds, String docId) {
        return new MapSqlParameterSource("tenant", tenantId)
                .addValue("kbs", List.copyOf(kbIds))
                .addValue("docId", StrUtil.isBlank(docId) ? null : docId);
    }

    private static boolean empty(Collection<String> values) {
        return values == null || values.isEmpty();
    }

    /** ILIKE 模式转义：% _ \ 按字面匹配，避免用户输入扩大扫描面。 */
    static String likePattern(String keyword) {
        if (StrUtil.isBlank(keyword)) {
            return null;
        }
        String escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
