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

package org.ruoyi.system.controller.system;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.constant.TenantConstants;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.tenant.helper.TenantHelper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台管理员视角的“租户 AI 工作区总览”（只读）。
 *
 * <p>AI 目录/运行数据按 {@code tenant_id} 行级隔离，普通接口只看当前租户；
 * 本端点显式使用 {@link TenantHelper#ignore} 做平台级汇总，且只返回计数/状态，
 * 不返回任何租户的业务内容或凭据。访问要求超管角色 + {@code system:tenant:list}。</p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/system/tenant")
public class TenantAiOverviewController {

    private final JdbcTemplate jdbc;

    public record TenantAiOverviewRow(
            String tenantId,
            String companyName,
            String status,
            Integer aclVersion,
            Integer revisionNo,
            String runtimeState,
            String providerId,
            String modelId,
            Long agents,
            Long skills,
            Long mcpTools,
            Long knowledgeBases,
            Long calls7d,
            Long tokens7d,
            Long defaultAgents) {
    }

    @SaCheckRole(TenantConstants.SUPER_ADMIN_ROLE_KEY)
    @SaCheckPermission("system:tenant:list")
    @GetMapping("/ai-overview")
    public R<List<TenantAiOverviewRow>> overview() {
        List<TenantAiOverviewRow> rows = TenantHelper.ignore(() -> jdbc.query("""
                SELECT t.tenant_id,
                       t.company_name,
                       t.status,
                       e.version AS acl_version,
                       r.revision_no,
                       r.state AS runtime_state,
                       r.provider_id,
                       r.model_id,
                       (SELECT count(*) FROM ai_agent_profile a
                         WHERE a.tenant_id = t.tenant_id AND a.deleted = 0) AS agents,
                       (SELECT count(*) FROM ai_agent_skill s
                         WHERE s.tenant_id = t.tenant_id AND s.deleted = 0) AS skills,
                       (SELECT count(*) FROM ai_mcp_tool m
                         WHERE m.tenant_id = t.tenant_id) AS mcp_tools,
                       (SELECT count(*) FROM ai_knowledge_base k
                         WHERE k.tenant_id = t.tenant_id AND k.deleted = 0) AS knowledge_bases,
                       (SELECT count(*) FROM ai_model_call c
                         WHERE c.tenant_id = t.tenant_id
                           AND c.created_at >= now() - interval '7 days') AS calls_7d,
                       (SELECT coalesce(sum(CASE WHEN c.usage_raw->>'total_tokens' ~ '^[0-9]+$'
                                                 THEN (c.usage_raw->>'total_tokens')::bigint ELSE 0 END), 0)
                          FROM ai_model_call c
                         WHERE c.tenant_id = t.tenant_id
                           AND c.created_at >= now() - interval '7 days') AS tokens_7d,
                       (SELECT count(*) FROM ai_agent_profile d
                         WHERE d.tenant_id = t.tenant_id AND d.name = '默认助手' AND d.deleted = 0) AS default_agents
                  FROM sys_tenant t
                  LEFT JOIN ai_acl_epoch e ON e.tenant_id = t.tenant_id
                  LEFT JOIN LATERAL (
                       SELECT revision_no, state, provider_id, model_id
                         FROM ai_runtime_config_revision r
                        WHERE r.tenant_id = t.tenant_id AND r.state = 'PUBLISHED'
                        ORDER BY revision_no DESC
                        LIMIT 1
                  ) r ON true
                 ORDER BY t.tenant_id
                """, (rs, rowNum) -> new TenantAiOverviewRow(
                rs.getString("tenant_id"),
                rs.getString("company_name"),
                rs.getString("status"),
                rs.getObject("acl_version") == null ? null : rs.getInt("acl_version"),
                rs.getObject("revision_no") == null ? null : rs.getInt("revision_no"),
                rs.getString("runtime_state"),
                rs.getString("provider_id"),
                rs.getString("model_id"),
                rs.getLong("agents"),
                rs.getLong("skills"),
                rs.getLong("mcp_tools"),
                rs.getLong("knowledge_bases"),
                rs.getLong("calls_7d"),
                rs.getLong("tokens_7d"),
                rs.getLong("default_agents"))));
        return R.ok(rows);
    }
}
