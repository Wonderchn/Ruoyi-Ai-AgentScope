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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ai_resource（资源注册表）DAO：P1.3a 的资源归属与版本权威读取/写入。
 *
 * <p>表契约（V3__tenant_acl_expand.sql，DDL 是本 DAO 的唯一权威）：
 * 复合主键 {@code (tenant_id, resource_type, resource_id)}，status 取值 ACTIVE / TOMBSTONED。
 * 因此每条语句按主键<b>自然携带租户条件</b>：不存在"不带 tenant 的注册表读写"。
 *
 * <p>刻意不用 MyBatis-Plus：该表没有 DO 基类体系，归属列必须显式出现在
 * 每条 SQL 里，而不是靠插件隐式注入——原生 SQL 让"租户条件恒在"可以被
 * {@code P1ResourceSqlIsolationTest} 逐条文本断言。
 */
@Repository
public class AiResourceMapper {

    /** 资源类型：知识库（V3 注释：当前取值 KB / DOCUMENT，不开放任意取值）。 */
    public static final String TYPE_KB = "KB";

    /** 资源类型：文档。 */
    public static final String TYPE_DOCUMENT = "DOCUMENT";

    /** 有效状态：正常可见。 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** tombstone 状态：已删除（逻辑删，不物理删）。 */
    public static final String STATUS_TOMBSTONED = "TOMBSTONED";

    private static final String SELECT_COLUMNS =
            "tenant_id, resource_type, resource_id, owner_member_id, owner_dept_id, "
                    + "parent_type, parent_id, status, resource_version";

    private final NamedParameterJdbcTemplate jdbc;

    public AiResourceMapper(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 一行注册表事实。resource_version 随归属/ACL/删除变化递增。 */
    public record AiResourceRow(String tenantId,
                                String resourceType,
                                String resourceId,
                                String ownerMemberId,
                                String ownerDeptId,
                                String parentType,
                                String parentId,
                                String status,
                                long resourceVersion) {
    }

    /** 按复合主键读单行；不存在或不属于该租户返回 empty（调用方一律按"资源不存在"处理）。 */
    public Optional<AiResourceRow> findByPk(String tenantId, String resourceType, String resourceId) {
        String sql = "SELECT " + SELECT_COLUMNS + " FROM ai_resource"
                + " WHERE tenant_id = :tenantId AND resource_type = :resourceType AND resource_id = :resourceId";
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("resourceType", resourceType);
        params.put("resourceId", resourceId);
        List<AiResourceRow> rows = jdbc.query(sql, params, (rs, rowNum) -> new AiResourceRow(
                rs.getString("tenant_id"),
                rs.getString("resource_type"),
                rs.getString("resource_id"),
                rs.getString("owner_member_id"),
                rs.getString("owner_dept_id"),
                rs.getString("parent_type"),
                rs.getString("parent_id"),
                rs.getString("status"),
                rs.getLong("resource_version")));
        return rows.stream().findFirst();
    }

    public List<AiResourceRow> findByIds(String tenantId, Map<String, ? extends java.util.Collection<String>> idsByType) {
        var batch = ResourceBatchQuery.of(tenantId, idsByType);
        if ("FALSE".equals(batch.predicate())) { return List.of(); }
        return jdbc.query("SELECT " + SELECT_COLUMNS + " FROM ai_resource WHERE tenant_id = :tenantId AND " + batch.predicate(),
                batch.parameters(), (rs, rowNum) -> new AiResourceRow(rs.getString("tenant_id"),
                        rs.getString("resource_type"), rs.getString("resource_id"), rs.getString("owner_member_id"),
                        rs.getString("owner_dept_id"), rs.getString("parent_type"), rs.getString("parent_id"),
                        rs.getString("status"), rs.getLong("resource_version")));
    }

    /** 列出该租户全部 ACTIVE 注册行（resolveScope 空候选时的"全部已授权资源"候选来源）。 */
    public List<AiResourceRow> listActive(String tenantId) {
        String sql = "SELECT " + SELECT_COLUMNS + " FROM ai_resource"
                + " WHERE tenant_id = :tenantId AND status = 'ACTIVE'";
        return jdbc.query(sql, Map.of("tenantId", tenantId), (rs, rowNum) -> new AiResourceRow(
                rs.getString("tenant_id"),
                rs.getString("resource_type"),
                rs.getString("resource_id"),
                rs.getString("owner_member_id"),
                rs.getString("owner_dept_id"),
                rs.getString("parent_type"),
                rs.getString("parent_id"),
                rs.getString("status"),
                rs.getLong("resource_version")));
    }

    /** 新增注册行（归属列显式提供，不设默认值；parent 对 KB 为空）。 */
    public int insert(AiResourceRow row, String createdByMember) {
        String sql = "INSERT INTO ai_resource (tenant_id, resource_type, resource_id, owner_member_id,"
                + " owner_dept_id, parent_type, parent_id, status, resource_version, created_by_member)"
                + " VALUES (:tenantId, :resourceType, :resourceId, :ownerMemberId, :ownerDeptId,"
                + " :parentType, :parentId, :status, :resourceVersion, :createdByMember)";
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", row.tenantId());
        params.put("resourceType", row.resourceType());
        params.put("resourceId", row.resourceId());
        params.put("ownerMemberId", row.ownerMemberId());
        params.put("ownerDeptId", row.ownerDeptId());
        params.put("parentType", row.parentType());
        params.put("parentId", row.parentId());
        params.put("status", row.status());
        params.put("resourceVersion", row.resourceVersion());
        params.put("createdByMember", createdByMember);
        return jdbc.update(sql, params);
    }

    /**
     * tombstone：同一条语句内完成 status 置换与版本递增（resource_version + 1）。
     *
     * <p>只允许 ACTIVE → TOMBSTONED：重复删除返回 0 行，由调用方按"不存在"拒绝；
     * 不物理删，行保留供审计与 tombstone 优先判定。
     */
    public int tombstone(String tenantId, String resourceType, String resourceId) {
        String sql = "UPDATE ai_resource SET status = 'TOMBSTONED', resource_version = resource_version + 1,"
                + " update_time = CURRENT_TIMESTAMP"
                + " WHERE tenant_id = :tenantId AND resource_type = :resourceType"
                + " AND resource_id = :resourceId AND status = 'ACTIVE'";
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("resourceType", resourceType);
        params.put("resourceId", resourceId);
        return jdbc.update(sql, params);
    }
}
