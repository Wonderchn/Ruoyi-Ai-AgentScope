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

/**
 * ai_resource_acl（资源 ACL）DAO：同租户主体的显式持久授权行。
 *
 * <p>表契约（V3，DDL 是唯一权威）：复合外键指向 ai_resource；
 * {@code subject_type} 取值 MEMBER / DEPT / ROLE / TENANT_ALL，其中 TENANT_ALL 是
 * 显式持久 grant 且 {@code subject_id} 必空，其余主体 subject_id 必填；
 * {@code uk_ai_resource_acl} 保证同资源同主体同动作只有一行。
 *
 * <p>过期语义：<b>时间判定，不等定时清理</b>。本 DAO 返回资源上的全部规则行，
 * 是否过期由服务层按当前 {@code Clock} 逐行判定（{@code expires_at <= now} 即失效），
 * 这样"过期"不依赖任何清理任务，也便于用固定时钟单测。
 */
@Repository
public class AiResourceAclMapper {

    private final NamedParameterJdbcTemplate jdbc;

    public AiResourceAclMapper(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 一行 ACL 规则。subjectId 为 null 当且仅当 subjectType 为 TENANT_ALL。 */
    public record AclRow(String id,
                         String tenantId,
                         String resourceType,
                         String resourceId,
                         String subjectType,
                         String subjectId,
                         String action,
                         Long expiresAtEpochSecond,
                         String grantedBy) {
    }

    /** 读某资源上的全部 ACL 规则（过期判定交给服务层的时钟，不在这里过滤）。 */
    public List<AclRow> findByResource(String tenantId, String resourceType, String resourceId) {
        String sql = "SELECT id, tenant_id, resource_type, resource_id, subject_type, subject_id,"
                + " action, expires_at, granted_by FROM ai_resource_acl"
                + " WHERE tenant_id = :tenantId AND resource_type = :resourceType AND resource_id = :resourceId";
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("resourceType", resourceType);
        params.put("resourceId", resourceId);
        return jdbc.query(sql, params, (rs, rowNum) -> new AclRow(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("resource_type"),
                rs.getString("resource_id"),
                rs.getString("subject_type"),
                rs.getString("subject_id"),
                rs.getString("action"),
                toEpochSecond(rs.getTimestamp("expires_at")),
                rs.getString("granted_by")));
    }

    public List<AclRow> findByIds(String tenantId, Map<String, ? extends java.util.Collection<String>> idsByType) {
        var batch = ResourceBatchQuery.of(tenantId, idsByType);
        if ("FALSE".equals(batch.predicate())) { return List.of(); }
        return jdbc.query("SELECT id, tenant_id, resource_type, resource_id, subject_type, subject_id,"
                        + " action, expires_at, granted_by FROM ai_resource_acl WHERE tenant_id = :tenantId AND " + batch.predicate(),
                batch.parameters(), (rs, rowNum) -> new AclRow(rs.getString("id"), rs.getString("tenant_id"),
                        rs.getString("resource_type"), rs.getString("resource_id"), rs.getString("subject_type"),
                        rs.getString("subject_id"), rs.getString("action"),
                        toEpochSecond(rs.getTimestamp("expires_at")), rs.getString("granted_by")));
    }

    /**
     * 读该租户全部 ACL 行（<b>含已过期行</b>，是否存活由服务层按 Clock 时间判定）。
     *
     * <p>撤权即删行：主体不再出现在本查询结果里，授权交集立刻收窄——
     * 这是本地实现的"主体存活"语义；platform 组织候选匹配在后续单元接线。
     * 过期行也返回：过期是时间判定而非清理任务的结果，服务层必须能看到
     * 行上的 expires_at 才能按当前时钟做出"已失效"结论。
     */
    public List<AclRow> listTenantRows(String tenantId) {
        String sql = "SELECT tenant_id, subject_type, subject_id, action, expires_at FROM ai_resource_acl"
                + " WHERE tenant_id = :tenantId";
        return jdbc.query(sql, Map.of("tenantId", tenantId), (rs, rowNum) -> new AclRow(
                null,
                rs.getString("tenant_id"),
                null,
                null,
                rs.getString("subject_type"),
                rs.getString("subject_id"),
                rs.getString("action"),
                toEpochSecond(rs.getTimestamp("expires_at")),
                null));
    }

    /** 新增一条授权（id 由服务层生成；expiresAt 为空表示长期有效）。 */
    public int insert(AclRow row) {
        String sql = "INSERT INTO ai_resource_acl (id, tenant_id, resource_type, resource_id,"
                + " subject_type, subject_id, action, expires_at, granted_by)"
                + " VALUES (:id, :tenantId, :resourceType, :resourceId, :subjectType, :subjectId,"
                + " :action, :expiresAt, :grantedBy)";
        Map<String, Object> params = new HashMap<>();
        params.put("id", row.id());
        params.put("tenantId", row.tenantId());
        params.put("resourceType", row.resourceType());
        params.put("resourceId", row.resourceId());
        params.put("subjectType", row.subjectType());
        params.put("subjectId", row.subjectId());
        params.put("action", row.action());
        params.put("expiresAt", toTimestamp(row.expiresAtEpochSecond()));
        params.put("grantedBy", row.grantedBy());
        return jdbc.update(sql, params);
    }

    /**
     * 撤销一条授权（撤权即删行 + 同事务 epoch bump）。
     *
     * <p>{@code IS NOT DISTINCT FROM} 让 TENANT_ALL（subject_id 为 NULL）的撤销
     * 也能精确命中，而不是被 {@code subject_id = NULL} 静默漏删。
     */
    public int deleteRule(String tenantId, String resourceType, String resourceId,
                          String subjectType, String subjectId, String action) {
        String sql = "DELETE FROM ai_resource_acl"
                + " WHERE tenant_id = :tenantId AND resource_type = :resourceType AND resource_id = :resourceId"
                + " AND subject_type = :subjectType AND subject_id IS NOT DISTINCT FROM :subjectId"
                + " AND action = :action";
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("resourceType", resourceType);
        params.put("resourceId", resourceId);
        params.put("subjectType", subjectType);
        params.put("subjectId", subjectId);
        params.put("action", action);
        return jdbc.update(sql, params);
    }

    private static Long toEpochSecond(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().getEpochSecond();
    }

    private static Timestamp toTimestamp(Long epochSecond) {
        return epochSecond == null ? null : Timestamp.from(java.time.Instant.ofEpochSecond(epochSecond));
    }
}
