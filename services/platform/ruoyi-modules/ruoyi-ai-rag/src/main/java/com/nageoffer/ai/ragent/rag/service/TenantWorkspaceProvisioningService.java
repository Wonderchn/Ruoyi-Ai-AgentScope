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

package com.nageoffer.ai.ragent.rag.service;

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 租户 AI 工作区初始化：把平台模板租户（{@code __public_template__}）的默认 Agent
 * 复制到当前租户。
 *
 * <p>幂等口径：同一租户已有同名（默认助手）行时直接返回既有 id，不重复插入、不改既有行。
 * 只复制 Agent 与 Prompt；绑定（binding）不复制——绑定引用的是租户内资源，平台模板
 * 不应替租户做选择。
 *
 * <p>租户边界：模板查询显式限定 {@code tenant_id='__public_template__'}，写入显式限定
 * 目标 {@code tenant_id}；本类不依赖 MyBatis 租户插件（JdbcTemplate 直写），
 * 因此每个 SQL 都带租户谓词，不能复用为跨租户工具。
 */
@Service
@RequiredArgsConstructor
public class TenantWorkspaceProvisioningService {

    /** 平台模板租户：与 Agent 默认值回落链同源。 */
    static final String TEMPLATE_TENANT = "__public_template__";

    /** 默认 Agent 名称：同租户唯一键 {@code (tenant_id, name)}。 */
    static final String DEFAULT_AGENT_NAME = "默认助手";

    private final JdbcTemplate jdbc;

    public record ImportResult(String agentId, boolean created, int promptCount) {
    }

    /** 20 字符 id：{@code ai_agent_profile.id}/{@code ai_agent_prompt.id} 是 varchar(20)。 */
    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    @Transactional(rollbackFor = Exception.class)
    public ImportResult importDefaultAgent(String tenantId, String operatorId) {
        if (tenantId == null || tenantId.isBlank() || TEMPLATE_TENANT.equals(tenantId)) {
            throw new ClientException("租户上下文无效，拒绝初始化默认 Agent");
        }

        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id FROM ai_agent_profile WHERE tenant_id = ? AND name = ? LIMIT 1",
                tenantId, DEFAULT_AGENT_NAME);
        if (!existing.isEmpty()) {
            return new ImportResult(String.valueOf(existing.get(0).get("id")), false, 0);
        }

        List<Map<String, Object>> templates = jdbc.queryForList(
                "SELECT id, description, avatar, agent_describe, agent_name, agent_show, "
                        + "enable_thinking, knowledge_ids, mcp_tool_ids, model_id, remark, "
                        + "skill_names, status, system_prompt "
                        + "FROM ai_agent_profile "
                        + "WHERE tenant_id = ? AND name = ? AND deleted = 0 "
                        + "ORDER BY create_time LIMIT 1",
                TEMPLATE_TENANT, DEFAULT_AGENT_NAME);
        if (templates.isEmpty()) {
            throw new ClientException("平台默认 Agent 模板缺失（" + TEMPLATE_TENANT + "/" + DEFAULT_AGENT_NAME + "）");
        }
        Map<String, Object> template = templates.get(0);
        String templateId = String.valueOf(template.get("id"));

        Integer activeCount = jdbc.queryForObject(
                "SELECT count(*) FROM ai_agent_profile WHERE tenant_id = ? AND active = 1 AND deleted = 0",
                Integer.class, tenantId);
        int active = (activeCount == null || activeCount == 0) ? 1 : 0;

        String agentId = newId();
        jdbc.update("INSERT INTO ai_agent_profile ("
                        + "id, name, description, avatar, builtin, active, create_by, create_time, update_time, "
                        + "deleted, tenant_id, agent_describe, agent_name, agent_show, create_dept, "
                        + "enable_thinking, knowledge_ids, mcp_tool_ids, model_id, remark, skill_names, "
                        + "status, system_prompt"
                        + ") VALUES (?, ?, ?, ?, 0, ?, ?, now(), now(), 0, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?)",
                agentId, DEFAULT_AGENT_NAME, template.get("description"), template.get("avatar"),
                active, operatorId, tenantId,
                template.get("agent_describe"), template.get("agent_name"), template.get("agent_show"),
                template.get("enable_thinking"), template.get("knowledge_ids"), template.get("mcp_tool_ids"),
                template.get("model_id"), template.get("remark"), template.get("skill_names"),
                template.get("status"), template.get("system_prompt"));

        List<Map<String, Object>> prompts = jdbc.queryForList(
                "SELECT slot_key, content FROM ai_agent_prompt "
                        + "WHERE tenant_id = ? AND agent_id = ? AND deleted = 0",
                TEMPLATE_TENANT, templateId);
        for (Map<String, Object> prompt : prompts) {
            jdbc.update("INSERT INTO ai_agent_prompt "
                            + "(id, agent_id, slot_key, content, create_by, create_time, update_time, deleted, tenant_id) "
                            + "VALUES (?, ?, ?, ?, ?, now(), now(), 0, ?)",
                    newId(),
                    agentId, prompt.get("slot_key"), prompt.get("content"), operatorId, tenantId);
        }
        return new ImportResult(agentId, true, prompts.size());
    }
}
