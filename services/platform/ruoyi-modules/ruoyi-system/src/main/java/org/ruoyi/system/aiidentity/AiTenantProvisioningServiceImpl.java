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

package org.ruoyi.system.aiidentity;

import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.constant.TenantConstants;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * {@link AiTenantProvisioningService} 的默认实现。
 *
 * <p>两条写都是幂等的：epoch 用 {@code ON CONFLICT DO NOTHING}；运行配置仅在
 * 该租户尚无任何版本时复制。事务传播固定为 {@code MANDATORY}——本服务自己
 * 开启事务会让“租户建了、AI 初始化失败”变成半成品，正是要消除的形态。
 */
@RequiredArgsConstructor
@Service
public class AiTenantProvisioningServiceImpl implements AiTenantProvisioningService {

    private static final String TEMPLATE_TENANT = "__public_template__";
    private static final String DEFAULT_AGENT_NAME = "默认助手";

    private final JdbcTemplate jdbc;

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void initialize(String tenantId, Long operatorUserId) {
        // 1) ACL epoch：AI 身份事实的租户版本行。行不存在时网关拒绝（不默认 1）。
        jdbc.update("INSERT INTO ai_acl_epoch(tenant_id, version) VALUES (?, 1) "
                + "ON CONFLICT (tenant_id) DO NOTHING", tenantId);

        // 2) 默认运行配置：复制默认租户最新 PUBLISHED 版本为 revision_no=1。
        //    只在该租户还没有任何运行配置版本时执行；默认租户无发布版本时插入 0 行。
        String revisionId = "rev-" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO ai_runtime_config_revision("
                        + "tenant_id, revision_id, revision_no, state, provider_id, model_id, "
                        + "catalog_version, params_hash, params_json, credential_ref, operator_id, "
                        + "published_at, dimension) "
                        + "SELECT ?, ?, 1, 'PUBLISHED', provider_id, model_id, catalog_version, "
                        + "params_hash, params_json, credential_ref, ?, now(), dimension "
                        + "FROM ai_runtime_config_revision "
                        + "WHERE tenant_id = ? AND state = 'PUBLISHED' "
                        + "AND NOT EXISTS (SELECT 1 FROM ai_runtime_config_revision r2 WHERE r2.tenant_id = ?) "
                        + "ORDER BY revision_no DESC LIMIT 1",
                tenantId, revisionId, String.valueOf(operatorUserId),
                TenantConstants.DEFAULT_TENANT_ID, tenantId);

        // 3) 默认 Agent：从平台模板域复制“默认助手”及其 Prompt 槽位。
        //    幂等：同名行已存在则跳过；模板缺失时插入 0 行，不影响租户创建。
        //    与 TenantWorkspaceProvisioningService 的手动导入保持同一模板源/同名口径。
        Integer existing = jdbc.queryForObject(
                "SELECT count(*) FROM ai_agent_profile WHERE tenant_id = ? AND name = ? AND deleted = 0",
                Integer.class, tenantId, DEFAULT_AGENT_NAME);
        if (existing == null || existing == 0) {
            Integer activeCount = jdbc.queryForObject(
                    "SELECT count(*) FROM ai_agent_profile WHERE tenant_id = ? AND active = 1 AND deleted = 0",
                    Integer.class, tenantId);
            int active = (activeCount == null || activeCount == 0) ? 1 : 0;
            String agentId = "ag" + UUID.randomUUID().toString().replace("-", "").substring(0, 18);
            int inserted = jdbc.update(
                    "INSERT INTO ai_agent_profile ("
                            + "id, name, description, avatar, builtin, active, create_by, create_time, update_time, "
                            + "deleted, tenant_id, agent_describe, agent_name, agent_show, create_dept, "
                            + "enable_thinking, knowledge_ids, mcp_tool_ids, model_id, remark, skill_names, "
                            + "status, system_prompt"
                            + ") SELECT ?, ?, description, avatar, 0, ?, ?, now(), now(), 0, ?, "
                            + "agent_describe, agent_name, agent_show, NULL, enable_thinking, knowledge_ids, "
                            + "mcp_tool_ids, model_id, remark, skill_names, status, system_prompt "
                            + "FROM ai_agent_profile WHERE tenant_id = ? AND name = ? AND deleted = 0 "
                            + "ORDER BY create_time LIMIT 1",
                    agentId, DEFAULT_AGENT_NAME, active, String.valueOf(operatorUserId), tenantId,
                    TEMPLATE_TENANT, DEFAULT_AGENT_NAME);
            if (inserted == 1) {
                jdbc.update(
                        "INSERT INTO ai_agent_prompt (id, agent_id, slot_key, content, create_by, "
                                + "create_time, update_time, deleted, tenant_id) "
                                + "SELECT substr(md5(random()::text), 1, 20), ?, slot_key, content, ?, "
                                + "now(), now(), 0, ? FROM ai_agent_prompt WHERE tenant_id = ? "
                                + "AND agent_id = (SELECT id FROM ai_agent_profile WHERE tenant_id = ? "
                                + "AND name = ? AND deleted = 0 ORDER BY create_time LIMIT 1) AND deleted = 0",
                        agentId, String.valueOf(operatorUserId), tenantId, TEMPLATE_TENANT,
                        TEMPLATE_TENANT, DEFAULT_AGENT_NAME);
            }
        }
    }
}
