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

package com.nageoffer.ai.ragent.ingestion.task;

import com.nageoffer.ai.ragent.runtime.CanonicalJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * F06:1/2 canonical 摄取任务面仓储（原生命令，复用 V7 既有 {@code ai_ingestion_task} /
 * {@code ai_ingestion_task_node} 两表）。
 *
 * <p><b>为什么不走既有 DO/Mapper。</b>两表 DO 缺少 V7 ALTER 增补的 {@code tenant_id}
 * （NOT NULL），MyBatis-Plus 插入会漏列而失败；且 DO 属冻结读取面。本仓储是本切片允许的
 * **新增**文件，用显式 SQL 写租户列。
 *
 * <p><b>任务行主键 = runId</b>（{@code "r-"+32hex}，34 字符），故 {@code ai_ingestion_task.id}
 * 与 {@code ai_ingestion_task_node.task_id} 需在 V37 由 T0 放宽到 VARCHAR(64)（见 Stage A §5）。
 * 节点行主键为 (runId,nodeId) 的 20 字符稳定 hash，落在既有 VARCHAR(20) 内。
 */
@Repository
public class IngestionTaskRepository {

    private final JdbcTemplate jdbc;

    public IngestionTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record TaskRow(String id, String pipelineId, String sourceType, String sourceLocation,
                          String sourceFileName, String status, int chunkCount, String errorMessage,
                          String logsJson, String metadataJson, Instant startedAt, Instant completedAt,
                          String createdBy, Instant createTime, Instant updateTime) {
    }

    public record NodeRow(String id, String taskId, String pipelineId, String nodeId, String nodeType,
                          int nodeOrder, String status, long durationMs, String message, String errorMessage,
                          String outputJson, Instant createTime, Instant updateTime) {
    }

    /** (runId,nodeId) → 20 字符稳定 id（防重、可纯计算）。 */
    public static String nodeRowId(String runId, String nodeId) {
        return "n" + CanonicalJson.sha256(runId + "\u0000" + nodeId).substring(0, 19);
    }

    public void createTask(String tenantId, String runId, String pipelineId, String sourceType,
                           String sourceLocation, String sourceFileName, String status,
                           String metadataJson, String createdBy) {
        jdbc.update("INSERT INTO ai_ingestion_task (tenant_id, id, pipeline_id, source_type, source_location, "
                        + "source_file_name, status, chunk_count, metadata_json, created_by, deleted) "
                        + "VALUES (?,?,?,?,?,?,?,0,?::jsonb,?,0) ON CONFLICT (tenant_id, id) DO NOTHING",
                tenantId, runId, pipelineId, sourceType, sourceLocation, sourceFileName, status,
                metadataJson, trim20(createdBy));
    }

    public Optional<TaskRow> findTask(String tenantId, String runId) {
        List<TaskRow> rows = jdbc.query("SELECT id, pipeline_id, source_type, source_location, source_file_name, "
                        + "status, chunk_count, error_message, logs_json::text AS logs_json, "
                        + "metadata_json::text AS metadata_json, started_at, completed_at, created_by, "
                        + "create_time, update_time FROM ai_ingestion_task "
                        + "WHERE tenant_id=? AND id=? AND deleted=0",
                (rs, rowNum) -> new TaskRow(rs.getString("id"), rs.getString("pipeline_id"),
                        rs.getString("source_type"), rs.getString("source_location"), rs.getString("source_file_name"),
                        rs.getString("status"), rs.getInt("chunk_count"), rs.getString("error_message"),
                        rs.getString("logs_json"), rs.getString("metadata_json"),
                        toInstant(rs.getTimestamp("started_at")), toInstant(rs.getTimestamp("completed_at")),
                        rs.getString("created_by"), toInstant(rs.getTimestamp("create_time")),
                        toInstant(rs.getTimestamp("update_time"))),
                tenantId, runId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<TaskRow> listTasks(String tenantId, String memberId, String status, int limit, int offset) {
        String filter = (status == null || status.isBlank()) ? "" : " AND t.status=?";
        String sql = "SELECT t.id, t.pipeline_id, t.source_type, t.source_location, t.source_file_name, t.status, "
                + "t.chunk_count, t.error_message, t.logs_json::text AS logs_json, "
                + "t.metadata_json::text AS metadata_json, t.started_at, t.completed_at, t.created_by, "
                + "t.create_time, t.update_time FROM ai_ingestion_task t "
                + "JOIN ai_run r ON r.tenant_id=t.tenant_id AND r.run_id=t.id "
                + "WHERE t.tenant_id=? AND r.member_id=? AND r.action='ingestion.run' AND t.deleted=0"
                + filter + " ORDER BY t.create_time DESC LIMIT ? OFFSET ?";
        Object[] args = (filter.isEmpty())
                ? new Object[]{tenantId, memberId, limit, offset}
                : new Object[]{tenantId, memberId, status, limit, offset};
        return jdbc.query(sql, (rs, rowNum) -> new TaskRow(rs.getString("id"), rs.getString("pipeline_id"),
                rs.getString("source_type"), rs.getString("source_location"), rs.getString("source_file_name"),
                rs.getString("status"), rs.getInt("chunk_count"), rs.getString("error_message"),
                rs.getString("logs_json"), rs.getString("metadata_json"),
                toInstant(rs.getTimestamp("started_at")), toInstant(rs.getTimestamp("completed_at")),
                rs.getString("created_by"), toInstant(rs.getTimestamp("create_time")),
                toInstant(rs.getTimestamp("update_time"))), args);
    }

    public long countTasks(String tenantId, String memberId, String status) {
        String filter = (status == null || status.isBlank()) ? "" : " AND t.status=?";
        String sql = "SELECT count(*) FROM ai_ingestion_task t "
                + "JOIN ai_run r ON r.tenant_id=t.tenant_id AND r.run_id=t.id "
                + "WHERE t.tenant_id=? AND r.member_id=? AND r.action='ingestion.run' AND t.deleted=0" + filter;
        Object[] args = filter.isEmpty() ? new Object[]{tenantId, memberId} : new Object[]{tenantId, memberId, status};
        Long total = jdbc.queryForObject(sql, Long.class, args);
        return total == null ? 0 : total;
    }

    public void markRunning(String tenantId, String runId) {
        jdbc.update("UPDATE ai_ingestion_task SET status='running', started_at=coalesce(started_at, now()), "
                + "update_time=now() WHERE tenant_id=? AND id=? AND deleted=0", tenantId, runId);
    }

    public void markTerminal(String tenantId, String runId, String status, Integer chunkCount, String errorMessage) {
        jdbc.update("UPDATE ai_ingestion_task SET status=?, chunk_count=coalesce(?, chunk_count), error_message=?, "
                + "completed_at=now(), update_time=now() WHERE tenant_id=? AND id=? AND deleted=0",
                status, chunkCount, errorMessage, tenantId, runId);
    }

    public void updateMetadata(String tenantId, String runId, String metadataJson) {
        jdbc.update("UPDATE ai_ingestion_task SET metadata_json=?::jsonb, update_time=now() "
                + "WHERE tenant_id=? AND id=? AND deleted=0", metadataJson, tenantId, runId);
    }

    public void upsertNode(String tenantId, String runId, String pipelineId, String nodeId, String nodeType,
                           int nodeOrder, String status, long durationMs, String message, String errorMessage,
                           String outputJson) {
        jdbc.update("INSERT INTO ai_ingestion_task_node (tenant_id, id, task_id, pipeline_id, node_id, node_type, "
                        + "node_order, status, duration_ms, message, error_message, output_json, deleted) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0) "
                        + "ON CONFLICT (id) DO UPDATE SET status=EXCLUDED.status, duration_ms=EXCLUDED.duration_ms, "
                        + "message=EXCLUDED.message, error_message=EXCLUDED.error_message, "
                        + "output_json=EXCLUDED.output_json, update_time=now()",
                tenantId, nodeRowId(runId, nodeId), runId, pipelineId, nodeId, nodeType, nodeOrder, status,
                durationMs, truncate(message, 4096), truncate(errorMessage, 4096), truncate(outputJson, 4096));
    }

    public List<NodeRow> listNodes(String tenantId, String runId) {
        return jdbc.query("SELECT id, task_id, pipeline_id, node_id, node_type, node_order, status, duration_ms, "
                        + "message, error_message, output_json, create_time, update_time "
                        + "FROM ai_ingestion_task_node WHERE tenant_id=? AND task_id=? AND deleted=0 "
                        + "ORDER BY node_order",
                (rs, rowNum) -> new NodeRow(rs.getString("id"), rs.getString("task_id"), rs.getString("pipeline_id"),
                        rs.getString("node_id"), rs.getString("node_type"), rs.getInt("node_order"),
                        rs.getString("status"), rs.getLong("duration_ms"), rs.getString("message"),
                        rs.getString("error_message"), rs.getString("output_json"),
                        toInstant(rs.getTimestamp("create_time")), toInstant(rs.getTimestamp("update_time"))),
                tenantId, runId);
    }

    public Optional<NodeRow> findNode(String tenantId, String runId, String nodeId) {
        List<NodeRow> rows = jdbc.query("SELECT id, task_id, pipeline_id, node_id, node_type, node_order, status, "
                        + "duration_ms, message, error_message, output_json, create_time, update_time "
                        + "FROM ai_ingestion_task_node WHERE tenant_id=? AND task_id=? AND node_id=? AND deleted=0",
                (rs, rowNum) -> new NodeRow(rs.getString("id"), rs.getString("task_id"), rs.getString("pipeline_id"),
                        rs.getString("node_id"), rs.getString("node_type"), rs.getInt("node_order"),
                        rs.getString("status"), rs.getLong("duration_ms"), rs.getString("message"),
                        rs.getString("error_message"), rs.getString("output_json"),
                        toInstant(rs.getTimestamp("create_time")), toInstant(rs.getTimestamp("update_time"))),
                tenantId, runId, nodeId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String trim20(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 20 ? value : value.substring(0, 20);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
