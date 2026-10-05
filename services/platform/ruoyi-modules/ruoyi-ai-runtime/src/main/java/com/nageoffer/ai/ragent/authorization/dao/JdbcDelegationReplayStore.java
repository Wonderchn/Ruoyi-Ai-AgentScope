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

import com.nageoffer.ai.ragent.framework.security.ProductionReplayGuard;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 委托 jti 持久消费账（U06/P1.2c）：{@link ProductionReplayGuard} 的 rag 生产实现。
 *
 * <p>表契约（V5 迁移 {@code ai_delegation_replay}，DDL 是唯一权威）：
 * 主键 (issuer, jti)，原子 unique 约束承载"首次消费"语义——单条
 * {@code INSERT ... ON CONFLICT DO NOTHING}：影响行数 = 1 → 首次消费；
 * = 0 → 重复 jti。并发插入由主键冲突兜底，不存在"先查后插"的竞态窗口。
 *
 * <p>装配条件 {@code ai.integration.security.enabled=true}：与生产委托链的
 * AI 侧开关（{@code ProductionSecurityConfig}）同源；开关关闭时本 bean 不存在，
 * 过滤器端表现为 replay 端口缺失 → 503 fail-closed。
 *
 * <p>存储异常<b>必须向上抛</b>：调用方（过滤器）把消费失败按 503 拒绝，
 * 绝不允许"存储坏了当作未消费放行"。
 */
@Repository
@ConditionalOnProperty(name = "ai.integration.security.enabled", havingValue = "true")
public class JdbcDelegationReplayStore implements ProductionReplayGuard {

    /** 消费语句：列形状与 V5 DDL 一致，ON CONFLICT DO NOTHING 保证原子性。 */
    static final String CONSUME_SQL = "INSERT INTO ai_delegation_replay "
            + "(issuer, jti, tenant_id, consumed_at, expires_at) VALUES (?, ?, ?, ?, ?) "
            + "ON CONFLICT DO NOTHING";

    private final JdbcTemplate jdbc;

    public JdbcDelegationReplayStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 原子消费 jti。
     *
     * @param issuer    签发方（主键第一列；不同签发方的 jti 空间互不干扰）
     * @param jti       一次性标识
     * @param tenantId  租户（审计/按租户清理用，不参与唯一性）
     * @param expiresAt 令牌过期时间（过期清理索引使用）
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
