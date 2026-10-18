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

package com.nageoffer.ai.ragent.flow.runtime;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * F13:2 AIFlow 运行投影仓储（V9 冻结表 {@code ai_flow_runtime} / {@code ai_flow_runtime_node}）。
 *
 * <p><b>为什么是 JdbcTemplate 而不是既有 DO/Mapper。</b>两张运行表在 V9 已是冻结 DDL；
 * 本切片不新增表、不改 DDL，也不把运行面的写路径绑到定义面的 MyBatis-Plus 实体上。
 * 显式 SQL 让每个谓词（tenant / owner / is_deleted）都成为可审阅的事实。
 *
 * <p><b>uuid 映射（坑 1）。</b>{@code ai_flow_runtime.uuid} 是 {@code varchar(32)}
 * （V9:529），而执行账本的 runId 是 {@code r-}+32hex（34 字符）。
 * 唯一映射：{@code runtime.uuid = runId.substring(2)}；反向 {@code runId = "r-"+uuid}。
 * {@link #runtimeUuidOf(String)} / {@link #runIdOf(String)} 是这两个方向的唯一入口。
 *
 * <p><b>不物理删。</b>清理与单条删除都写 {@code is_deleted=1}；所有读路径恒带
 * {@code is_deleted=0}。
 */
@Repository
public class FlowRuntimeRepository {

    private final JdbcTemplate jdbc;

    public FlowRuntimeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ graph read

    /** 定义面一行节点（内部 id 用于 runtime_node.node_id）。 */
    public record NodeSource(long id, String uuid, String title, long componentId,
                             String inputConfigJson, String nodeConfigJson) {
    }

    public record EdgeSource(String source, String target) {
    }

    /** 可执行图（谓词命中后的节点/边/组件名快照源）。 */
    public record GraphSource(long workflowId, String workflowUuid, List<NodeSource> nodes,
                              List<EdgeSource> edges, Map<Long, String> componentNames) {
    }

    public record ParentGraph(long workflowId, String inputJson) {
    }

    /**
     * §3.1(5) 可执行谓词：{@code tenant=当前 AND is_deleted=0 AND is_enable=1 AND
     * (user_id=当前 OR is_public=1)}。0 行返回 empty（调用方映射为 404，不区分原因）。
     */
    public Optional<GraphSource> findExecutableGraph(String tenantId, String workflowUuid, long userId) {
        List<Long> ids = jdbc.query(
                "SELECT id FROM ai_flow_workflow WHERE tenant_id=? AND uuid=? AND is_deleted=0 "
                        + "AND is_enable=1 AND (user_id=? OR is_public=1)",
                (rs, rowNum) -> rs.getLong(1), tenantId, workflowUuid, userId);
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        long workflowId = ids.get(0);
        List<NodeSource> nodes = jdbc.query(
                "SELECT id, uuid, title, workflow_component_id, input_config::text AS input_config, "
                        + "node_config::text AS node_config FROM ai_flow_node "
                        + "WHERE tenant_id=? AND workflow_id=? AND is_deleted=0 ORDER BY uuid",
                (rs, rowNum) -> new NodeSource(rs.getLong("id"), rs.getString("uuid"),
                        rs.getString("title"), rs.getLong("workflow_component_id"),
                        rs.getString("input_config"), rs.getString("node_config")),
                tenantId, workflowId);
        List<EdgeSource> edges = jdbc.query(
                "SELECT source_node_uuid, target_node_uuid FROM ai_flow_edge "
                        + "WHERE tenant_id=? AND workflow_id=? AND is_deleted=0 "
                        + "ORDER BY source_node_uuid, target_node_uuid",
                (rs, rowNum) -> new EdgeSource(rs.getString(1), rs.getString(2)), tenantId, workflowId);
        Map<Long, String> componentNames = new LinkedHashMap<>();
        for (NodeSource node : nodes) {
            long componentId = node.componentId();
            if (componentId <= 0 || componentNames.containsKey(componentId)) {
                continue;
            }
            List<String> names = jdbc.query(
                    "SELECT name FROM ai_flow_component WHERE tenant_id=? AND id=? AND is_deleted=0",
                    (rs, rowNum) -> rs.getString(1), tenantId, componentId);
            if (!names.isEmpty()) {
                componentNames.put(componentId, names.get(0));
            }
        }
        return Optional.of(new GraphSource(workflowId, workflowUuid, nodes, edges, componentNames));
    }

    /** D7：父 run 的 runtime 行（含 input 快照与 workflow_id）；仅未删行。 */
    public Optional<ParentGraph> findParentGraph(String tenantId, String runtimeUuid) {
        List<ParentGraph> rows = jdbc.query(
                "SELECT workflow_id, input::text AS input_json FROM ai_flow_runtime "
                        + "WHERE tenant_id=? AND uuid=? AND is_deleted=0",
                (rs, rowNum) -> new ParentGraph(rs.getLong("workflow_id"), rs.getString("input_json")),
                tenantId, runtimeUuid);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** retry 复用父快照时需要把 snapshot node uuid 映射回定义行内部 id。 */
    public OptionalLong findNodeId(String tenantId, long workflowId, String nodeUuid) {
        List<Long> ids = jdbc.query(
                "SELECT id FROM ai_flow_node WHERE tenant_id=? AND workflow_id=? AND uuid=? "
                        + "ORDER BY id LIMIT 1",
                (rs, rowNum) -> rs.getLong(1), tenantId, workflowId, nodeUuid);
        return ids.isEmpty() ? OptionalLong.empty() : OptionalLong.of(ids.get(0));
    }

    // ------------------------------------------------------------------ admission writes

    public long insertRuntime(String tenantId, String runtimeUuid, long userId, long workflowId, String inputJson) {
        Long id = jdbc.queryForObject(
                "INSERT INTO ai_flow_runtime (uuid, user_id, workflow_id, input, output, status, "
                        + "status_remark, is_deleted, tenant_id) VALUES (?,?,?,?::jsonb,NULL,1,'',0,?) "
                        + "RETURNING id",
                Long.class, runtimeUuid, userId, workflowId, inputJson, tenantId);
        if (id == null) {
            throw new RunApiException(RunErrorCode.INTERNAL_ERROR, "flow runtime insert readback failed");
        }
        return id;
    }

    /** §3.3：每节点 1 行，status=1，remark=PENDING。 */
    public void insertRuntimeNode(String tenantId, long runtimeId, long nodeId, long userId) {
        jdbc.update(
                "INSERT INTO ai_flow_runtime_node (uuid, user_id, workflow_runtime_id, node_id, input, "
                        + "output, status, status_remark, is_deleted, tenant_id) "
                        + "VALUES (?,?,?,?,NULL,NULL,1,'PENDING',0,?)",
                UUID.randomUUID().toString().replace("-", ""), userId, runtimeId, nodeId, tenantId);
    }

    // ------------------------------------------------------------------ runtime row

    public record RuntimeRow(long id, String uuid, long userId, long workflowId, String inputJson,
                             String outputJson, int status, String statusRemark, boolean deleted) {
    }

    public Optional<RuntimeRow> findRuntime(String tenantId, String runtimeUuid) {
        List<RuntimeRow> rows = jdbc.query(
                "SELECT id, uuid, user_id, workflow_id, input::text AS input_json, "
                        + "output::text AS output_json, status, status_remark, is_deleted "
                        + "FROM ai_flow_runtime WHERE tenant_id=? AND uuid=? AND is_deleted=0",
                (rs, rowNum) -> new RuntimeRow(rs.getLong("id"), rs.getString("uuid"),
                        rs.getLong("user_id"), rs.getLong("workflow_id"), rs.getString("input_json"),
                        rs.getString("output_json"), rs.getInt("status"),
                        rs.getString("status_remark"), rs.getInt("is_deleted") == 1),
                tenantId, runtimeUuid);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    // ------------------------------------------------------------------ projection writes

    public void markRuntimeRunning(String tenantId, String runtimeUuid) {
        jdbc.update("UPDATE ai_flow_runtime SET status=2, update_time=now() "
                + "WHERE tenant_id=? AND uuid=? AND is_deleted=0", tenantId, runtimeUuid);
    }

    public void markNodeRunning(String tenantId, String runtimeUuid, long nodeId) {
        updateNode(tenantId, runtimeUuid, nodeId, 1, "RUNNING", null);
    }

    public void markNodeSucceeded(String tenantId, String runtimeUuid, long nodeId, String outputJson) {
        updateNode(tenantId, runtimeUuid, nodeId, 3, "", outputJson);
    }

    public void markNodeFailed(String tenantId, String runtimeUuid, long nodeId, String remark) {
        updateNode(tenantId, runtimeUuid, nodeId, 2, truncate(remark, 250), null);
    }

    private void updateNode(String tenantId, String runtimeUuid, long nodeId, int status, String remark,
                            String outputJson) {
        jdbc.update("UPDATE ai_flow_runtime_node SET status=?, status_remark=?, output=?::jsonb, "
                        + "update_time=now() WHERE tenant_id=? AND workflow_runtime_id="
                        + "(SELECT id FROM ai_flow_runtime WHERE tenant_id=? AND uuid=?) AND node_id=?",
                status, remark, outputJson, tenantId, tenantId, runtimeUuid, nodeId);
    }

    public void markRuntimeSucceeded(String tenantId, String runtimeUuid, String outputJson) {
        jdbc.update("UPDATE ai_flow_runtime SET status=3, status_remark='', output=?::jsonb, "
                + "update_time=now() WHERE tenant_id=? AND uuid=? AND is_deleted=0",
                outputJson, tenantId, runtimeUuid);
    }

    public void markRuntimeFailed(String tenantId, String runtimeUuid, String remark) {
        jdbc.update("UPDATE ai_flow_runtime SET status=4, status_remark=?, update_time=now() "
                        + "WHERE tenant_id=? AND uuid=? AND is_deleted=0",
                truncate(remark, 250), tenantId, runtimeUuid);
    }

    public void markRuntimeCancelled(String tenantId, String runtimeUuid) {
        jdbc.update("UPDATE ai_flow_runtime SET status=4, status_remark='CANCELLED', update_time=now() "
                + "WHERE tenant_id=? AND uuid=? AND is_deleted=0", tenantId, runtimeUuid);
    }

    // ------------------------------------------------------------------ runtime views

    public record RuntimeView(String uuid, String workflowUuid, int status, String statusRemark,
                              String outputJson, Instant createTime, Instant updateTime,
                              String runStatus, String errorCode) {
    }

    public record RuntimeNodeView(long nodeId, String nodeUuid, int status, String statusRemark,
                                  String inputJson, String outputJson, String stepState,
                                  String stepRefJson, Instant createTime, Instant updateTime) {
    }

    public List<RuntimeView> listRuntimes(String tenantId, long userId, String workflowUuid,
                                          int limit, int offset) {
        boolean filtered = workflowUuid != null && !workflowUuid.isBlank();
        String filter = filtered ? " AND w.uuid=?" : "";
        String sql = "SELECT r.uuid, w.uuid AS workflow_uuid, r.status, r.status_remark, "
                + "r.output::text AS output_json, r.create_time, r.update_time, "
                + "NULL AS run_status, NULL AS error_code "
                + "FROM ai_flow_runtime r JOIN ai_flow_workflow w "
                + "ON w.id=r.workflow_id AND w.tenant_id=r.tenant_id "
                + "WHERE r.tenant_id=? AND r.user_id=? AND r.is_deleted=0" + filter
                + " ORDER BY r.update_time DESC, r.id DESC LIMIT ? OFFSET ?";
        Object[] args = filtered
                ? new Object[]{tenantId, userId, workflowUuid, limit, offset}
                : new Object[]{tenantId, userId, limit, offset};
        return jdbc.query(sql, FlowRuntimeRepository::mapRuntimeView, args);
    }

    public long countRuntimes(String tenantId, long userId, String workflowUuid) {
        boolean filtered = workflowUuid != null && !workflowUuid.isBlank();
        String filter = filtered ? " AND w.uuid=?" : "";
        String sql = "SELECT count(*) FROM ai_flow_runtime r JOIN ai_flow_workflow w "
                + "ON w.id=r.workflow_id AND w.tenant_id=r.tenant_id "
                + "WHERE r.tenant_id=? AND r.user_id=? AND r.is_deleted=0" + filter;
        Long total = filtered
                ? jdbc.queryForObject(sql, Long.class, tenantId, userId, workflowUuid)
                : jdbc.queryForObject(sql, Long.class, tenantId, userId);
        return total == null ? 0L : total;
    }

    public Optional<RuntimeView> findRuntimeView(String tenantId, long userId, String runtimeUuid) {
        List<RuntimeView> rows = jdbc.query(
                "SELECT r.uuid, w.uuid AS workflow_uuid, r.status, r.status_remark, "
                        + "r.output::text AS output_json, r.create_time, r.update_time, "
                        + "run.status AS run_status, run.error_code "
                        + "FROM ai_flow_runtime r JOIN ai_flow_workflow w "
                        + "ON w.id=r.workflow_id AND w.tenant_id=r.tenant_id "
                        + "LEFT JOIN ai_run run ON run.tenant_id=r.tenant_id "
                        + "AND run.run_id=('r-'||r.uuid) "
                        + "WHERE r.tenant_id=? AND r.user_id=? AND r.uuid=? AND r.is_deleted=0",
                FlowRuntimeRepository::mapRuntimeView, tenantId, userId, runtimeUuid);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<RuntimeNodeView> listRuntimeNodes(String tenantId, long userId, String runtimeUuid) {
        return jdbc.query(
                "SELECT rn.node_id, n.uuid AS node_uuid, rn.status, rn.status_remark, "
                        + "rn.input::text AS input_json, rn.output::text AS output_json, "
                        + "s.state AS step_state, s.ref_json AS step_ref_json, rn.create_time, rn.update_time "
                        + "FROM ai_flow_runtime_node rn "
                        + "JOIN ai_flow_runtime r ON r.id=rn.workflow_runtime_id AND r.tenant_id=rn.tenant_id "
                        + "LEFT JOIN ai_flow_node n ON n.id=rn.node_id AND n.tenant_id=rn.tenant_id "
                        + "LEFT JOIN LATERAL (SELECT state, ref::text AS ref_json FROM ai_run_step "
                        + "WHERE tenant_id=r.tenant_id AND run_id=('r-'||r.uuid) "
                        + "AND step_id=('node:'||n.uuid) ORDER BY attempt DESC, checkpoint_version DESC "
                        + "LIMIT 1) s ON true "
                        + "WHERE r.tenant_id=? AND r.user_id=? AND r.uuid=? AND r.is_deleted=0 "
                        + "AND rn.is_deleted=0 ORDER BY rn.id",
                (rs, rowNum) -> new RuntimeNodeView(rs.getLong("node_id"), rs.getString("node_uuid"),
                        rs.getInt("status"), rs.getString("status_remark"), rs.getString("input_json"),
                        rs.getString("output_json"), rs.getString("step_state"),
                        rs.getString("step_ref_json"), toInstant(rs.getTimestamp("create_time")),
                        toInstant(rs.getTimestamp("update_time"))),
                tenantId, userId, runtimeUuid);
    }

    // ------------------------------------------------------------------ soft delete

    /** 清理：按工作流软删（空 wfUuid = 本人全部）；0 行是幂等成功。 */
    public int softDeleteByWorkflow(String tenantId, long userId, String workflowUuid) {
        if (workflowUuid == null || workflowUuid.isBlank()) {
            return jdbc.update("UPDATE ai_flow_runtime SET is_deleted=1, update_time=now() "
                    + "WHERE tenant_id=? AND user_id=? AND is_deleted=0", tenantId, userId);
        }
        return jdbc.update("UPDATE ai_flow_runtime SET is_deleted=1, update_time=now() "
                        + "WHERE tenant_id=? AND user_id=? AND is_deleted=0 AND workflow_id="
                        + "(SELECT id FROM ai_flow_workflow WHERE tenant_id=? AND uuid=? AND user_id=? "
                        + "AND is_deleted=0)",
                tenantId, userId, tenantId, workflowUuid, userId);
    }

    public boolean softDeleteRuntime(String tenantId, long userId, String runtimeUuid) {
        return jdbc.update("UPDATE ai_flow_runtime SET is_deleted=1, update_time=now() "
                + "WHERE tenant_id=? AND user_id=? AND uuid=? AND is_deleted=0",
                tenantId, userId, runtimeUuid) == 1;
    }

    // ------------------------------------------------------------------ mapping helpers

    private static RuntimeView mapRuntimeView(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new RuntimeView(rs.getString("uuid"), rs.getString("workflow_uuid"), rs.getInt("status"),
                rs.getString("status_remark"), rs.getString("output_json"),
                toInstant(rs.getTimestamp("create_time")), toInstant(rs.getTimestamp("update_time")),
                rs.getString("run_status"), rs.getString("error_code"));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    // ------------------------------------------------------------------ uuid mapping

    /** {@code ai_flow_runtime.uuid} = runId.substring(2)；形状不符即拒绝。 */
    public static String runtimeUuidOf(String runId) {
        if (runId == null || !runId.startsWith("r-") || runId.length() != 34
                || !runId.substring(2).matches("[0-9a-f]{32}")) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST,
                    "runtime uuid requires r- + 32 lowercase hex");
        }
        return runId.substring(2);
    }

    /** {@code runId = "r-" + runtime.uuid}。 */
    public static String runIdOf(String runtimeUuid) {
        if (runtimeUuid == null || !runtimeUuid.matches("[0-9a-f]{32}")) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST,
                    "runtime uuid requires 32 lowercase hex");
        }
        return "r-" + runtimeUuid;
    }

    /** 主体 userId（bigint 列）；解析失败按 404 处理（不落到 user_id=0 的意外行）。 */
    public static long ownerUserId(ExecutionPrincipal principal) {
        try {
            return Long.parseLong(principal.userId());
        } catch (NumberFormatException e) {
            throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        }
    }
}
