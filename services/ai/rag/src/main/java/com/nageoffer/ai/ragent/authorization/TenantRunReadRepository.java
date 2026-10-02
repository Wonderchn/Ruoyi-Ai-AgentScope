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

package com.nageoffer.ai.ragent.authorization;

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

/**
 * 正式 run 的租户作用域只读仓储（P1.3d）。
 *
 * <p>run 是正式命名结构（V5 建立，PK=(tenant_id, run_id)）：读取按
 * {@code (tenant_id, run_id)} 复合定位，跨租户 runId 与不存在同外显。
 * <b>只读</b>——P1 不开放 run 提交/Worker/SSE/恢复，本仓储不提供任何写方法。
 * 默认不装配（{@code ai.integration.enabled=true}）。
 */
@Slf4j
@Repository
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class TenantRunReadRepository {

    private final JdbcTemplate jdbc;

    public TenantRunReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按 (tenant, run) 复合定位；tenant 不匹配与不存在同样 empty（404 语义）。 */
    public Optional<RunRow> findRun(String tenantId, String runId) {
        if (tenantId == null || tenantId.isBlank() || runId == null || runId.isBlank()) {
            throw new ClientException("tenantId/runId 均不能为空");
        }
        String sql = "SELECT run_id, member_id, action, status, policy_version, acl_version, created_at"
                + " FROM ai_run WHERE tenant_id = ? AND run_id = ?";
        return jdbc.query(sql, (rs, rowNum) -> new RunRow(
                        rs.getString("run_id"),
                        rs.getString("member_id"),
                        rs.getString("action"),
                        rs.getString("status"),
                        rs.getInt("policy_version"),
                        rs.getInt("acl_version"),
                        rs.getTimestamp("created_at")),
                tenantId, runId).stream().findFirst();
    }

    public record RunRow(String runId, String memberId, String action, String status,
                         int policyVersion, int aclVersion, Timestamp createdAt) {
    }
}
