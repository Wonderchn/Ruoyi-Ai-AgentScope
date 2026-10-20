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
    }
}
