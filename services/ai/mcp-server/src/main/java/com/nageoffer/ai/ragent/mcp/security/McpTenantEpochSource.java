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

package com.nageoffer.ai.ragent.mcp.security;

import com.nageoffer.ai.ragent.framework.security.DelegatedPrincipalFilter;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 9099 独立进程的租户 ACL epoch 事实源（SPEC-9099 §7，T0 2026-10-11）。
 *
 * <p><b>语义边界（登记）。</b>内嵌链的 av 是 rag 的 AI 资源 ACL epoch（与 AI 运行数据
 * 共库）；独立进程不与平台/rag 共库，本实现把 av 定义为<b>本资源服务器的租户开通版本</b>：
 * 表 {@code mcp_tenant_epoch} 有该租户一行（epoch ≥ 1）才认为"该租户在本服务器已开通"，
 * 授权基线才成立；无行返回 0 → 委托验签过滤器按 503 拒绝（与内嵌链
 * "没有版本就当有效被禁止"同一口径，绝不默认初始化到 1）。
 *
 * <p>表行由部署面 SQL 维护（开通即插入；授权基线变化时提升 epoch）。
 * 查询异常原样上抛 → 过滤器映射 503（fail-closed，不降级）。
 */
public class McpTenantEpochSource implements DelegatedPrincipalFilter.AclVersionSource {

    /** 开通版本查询：无行 = 未开通（返回 0，由过滤器拒绝）。 */
    static final String EPOCH_QUERY = "SELECT acl_epoch FROM mcp_tenant_epoch WHERE tenant_id = ?";

    private final JdbcTemplate jdbc;

    public McpTenantEpochSource(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int currentVersion(String tenantId) {
        try {
            Integer epoch = jdbc.queryForObject(EPOCH_QUERY, Integer.class, tenantId);
            return epoch == null ? 0 : epoch;
        } catch (EmptyResultDataAccessException noRow) {
            // 无 epoch 行（租户未开通/数据不完整）：返回 0，调用方必须拒绝
            return 0;
        }
    }
}
