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

import com.nageoffer.ai.ragent.framework.security.ProductionReplayGuard;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 9099 独立进程的委托 jti 重放防护（SPEC-9099 §7，T0 2026-10-11）：
 * 内嵌链的 {@code JdbcDelegationReplayStore} 依赖平台库 {@code ai_delegation_replay}，
 * 独立进程不与平台共库，因此按同口径在本资源服务器的库（bit 库）自建对应表。
 *
 * <p>表契约（{@link McpSecuritySchemaInitializer} 幂等 DDL）：主键 {@code (issuer, jti)}，
 * 原子 unique 约束承载"首次消费"语义——单条 {@code INSERT ... ON CONFLICT DO NOTHING}：
 * 影响行数 = 1 → 首次消费；= 0 → 重复 jti。并发插入由主键冲突兜底，无"先查后插"竞态窗口。
 *
 * <p>存储异常<b>必须向上抛</b>：调用方（委托验签过滤器）把消费失败按 503 拒绝，
 * 绝不允许"存储坏了当作未消费放行"。
 */
public class McpJdbcDelegationReplayStore implements ProductionReplayGuard {

    /** 消费语句：列形状与 {@link McpSecuritySchemaInitializer} 的 DDL 一致。 */
    static final String CONSUME_SQL = "INSERT INTO mcp_delegation_replay "
            + "(issuer, jti, tenant_id, consumed_at, expires_at) VALUES (?, ?, ?, ?, ?) "
            + "ON CONFLICT DO NOTHING";

    private final JdbcTemplate jdbc;

    public McpJdbcDelegationReplayStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 原子消费 jti。
     *
     * @param issuer    签发方（主键第一列；不同签发方的 jti 空间互不干扰）
     * @param jti       一次性标识
     * @param tenantId  租户（审计用，不参与唯一性）
     * @param expiresAt 令牌过期时间（过期行清理口径用）
     * @return true = 首次消费；false = 重复 jti
     * @throws org.springframework.dao.DataAccessException 存储不可用时原样向上抛
     */
    @Override
    public boolean consume(String issuer, String jti, String tenantId, Instant expiresAt) {
        int inserted = jdbc.update(CONSUME_SQL,
                issuer,
                jti,
                tenantId,
                Timestamp.from(Instant.now()),
                Timestamp.from(expiresAt));
        return inserted == 1;
    }
}
