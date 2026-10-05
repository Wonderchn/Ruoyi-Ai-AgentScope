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

package com.nageoffer.ai.ragent.authorization.dao;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ai_execution_permit（执行 permit）DAO：高风险段的强撤权屏障登记。
 *
 * <p>表契约（V3，DDL 是唯一权威）：permit 唯一、operation 幂等
 * （{@code uk_ai_execution_permit_operation}）；<b>不存 bearer</b>。
 * 本单元只提供基础行为（insert / active 查询 / ACTIVE→RELEASED/REVOKED 状态迁移）；
 * 跨节点的屏障协调（获取、续期、冲突仲裁）属 U10，不在这里实现。
 *
 * <p>时间语义与 ACL 一致：<b>过期是时间判定</b>——active 查询要求
 * {@code expires_at > CURRENT_TIMESTAMP}，过期 permit 不需要等任何清理任务
 * 就查不到，也不允许被再次迁移状态。
 */
@Repository
public class AiExecutionPermitMapper {

    private final NamedParameterJdbcTemplate jdbc;

    public AiExecutionPermitMapper(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 一行 permit 登记。 */
    public record PermitRow(String permitId,
                            String tenantId,
                            String memberId,
                            String action,
                            int policyVersion,
                            int aclVersion,
                            String resourceRefsHash,
                            String operationId,
                            String status,
                            long acquiredAtEpochSecond,
                            long expiresAtEpochSecond,
                            Long releasedAtEpochSecond) {
    }

    /** 登记一条新 permit（status 固定 ACTIVE；调用方负责同事务写 run 侧引用）。 */
    public int insert(PermitRow row) {
        String sql = "INSERT INTO ai_execution_permit (permit_id, tenant_id, member_id, action,"
                + " policy_version, acl_version, resource_refs_hash, operation_id, status,"
                + " acquired_at, expires_at)"
                + " VALUES (:permitId, :tenantId, :memberId, :action, :policyVersion, :aclVersion,"
                + " :resourceRefsHash, :operationId, 'ACTIVE', :acquiredAt, :expiresAt)";
        Map<String, Object> params = new HashMap<>();
        params.put("permitId", row.permitId());
        params.put("tenantId", row.tenantId());
        params.put("memberId", row.memberId());
        params.put("action", row.action());
        params.put("policyVersion", row.policyVersion());
        params.put("aclVersion", row.aclVersion());
        params.put("resourceRefsHash", row.resourceRefsHash());
        params.put("operationId", row.operationId());
        params.put("acquiredAt", toTimestamp(row.acquiredAtEpochSecond()));
        params.put("expiresAt", toTimestamp(row.expiresAtEpochSecond()));
        return jdbc.update(sql, params);
    }

    /** 读一条仍活跃（ACTIVE 且未过期）的 permit；不存在/已迁移/已过期都返回 empty。 */
    public Optional<PermitRow> findActive(String tenantId, String permitId) {
        String sql = "SELECT permit_id, tenant_id, member_id, action, policy_version, acl_version,"
                + " resource_refs_hash, operation_id, status, acquired_at, expires_at, released_at"
                + " FROM ai_execution_permit"
                + " WHERE tenant_id = :tenantId AND permit_id = :permitId"
                + " AND status = 'ACTIVE' AND expires_at > CURRENT_TIMESTAMP";
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("permitId", permitId);
        List<PermitRow> rows = jdbc.query(sql, params, (rs, rowNum) -> new PermitRow(
                rs.getString("permit_id"),
                rs.getString("tenant_id"),
                rs.getString("member_id"),
                rs.getString("action"),
                rs.getInt("policy_version"),
                rs.getInt("acl_version"),
                rs.getString("resource_refs_hash"),
                rs.getString("operation_id"),
                rs.getString("status"),
                toEpochSecond(rs.getTimestamp("acquired_at")),
                toEpochSecond(rs.getTimestamp("expires_at")),
                toEpochSecond(rs.getTimestamp("released_at"))));
        return rows.stream().findFirst();
    }

    /** ACTIVE → RELEASED（正常释放）。只允许从 ACTIVE 迁移，返回受影响行数。 */
    public int release(String tenantId, String permitId) {
        String sql = "UPDATE ai_execution_permit SET status = 'RELEASED', released_at = CURRENT_TIMESTAMP"
                + " WHERE tenant_id = :tenantId AND permit_id = :permitId AND status = 'ACTIVE'";
        return jdbc.update(sql, Map.of("tenantId", tenantId, "permitId", permitId));
    }

    /** ACTIVE → REVOKED（强撤权屏障触发）。只允许从 ACTIVE 迁移，返回受影响行数。 */
    public int revoke(String tenantId, String permitId) {
        String sql = "UPDATE ai_execution_permit SET status = 'REVOKED', released_at = CURRENT_TIMESTAMP"
                + " WHERE tenant_id = :tenantId AND permit_id = :permitId AND status = 'ACTIVE'";
        return jdbc.update(sql, Map.of("tenantId", tenantId, "permitId", permitId));
    }

    private static Timestamp toTimestamp(long epochSecond) {
        return Timestamp.from(java.time.Instant.ofEpochSecond(epochSecond));
    }

    private static Long toEpochSecond(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().getEpochSecond();
    }
}
