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

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 受保护模式的两张安全支撑表（SPEC-9099 §7，T0 2026-10-11）：幂等 DDL，仅
 * {@code ai.integration.security.enabled=true} 时装配执行。
 *
 * <p>刻意不写进 {@code db/bit-schema.sql}：那份脚本有 {@code t_schema_version}
 * 版本闸（对不上直接拒绝启动），把安全表混进去会连带业务结构升版；本类独立、
 * 非破坏性（{@code CREATE TABLE IF NOT EXISTS}），可反复启动。
 */
public class McpSecuritySchemaInitializer {

    /** 委托 jti 重放账本（口径镜像内嵌 {@code ai_delegation_replay}）。 */
    static final String REPLAY_TABLE_DDL = """
            CREATE TABLE IF NOT EXISTS mcp_delegation_replay (
                issuer      VARCHAR(64)  NOT NULL,
                jti         VARCHAR(128) NOT NULL,
                tenant_id   VARCHAR(64),
                consumed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
                expires_at  TIMESTAMPTZ  NOT NULL,
                PRIMARY KEY (issuer, jti)
            )""";

    /** 租户开通版本（av 事实源；无行 = 未开通，过滤器按 503 拒绝）。 */
    static final String EPOCH_TABLE_DDL = """
            CREATE TABLE IF NOT EXISTS mcp_tenant_epoch (
                tenant_id  VARCHAR(64) PRIMARY KEY,
                acl_epoch  INT         NOT NULL CHECK (acl_epoch >= 1),
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
            )""";

    private final JdbcTemplate jdbc;

    public McpSecuritySchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 启动期执行（由 {@link McpSecurityConfiguration} 在 bean 方法里触发，失败即启动失败）。 */
    public void initialize() {
        jdbc.execute(REPLAY_TABLE_DDL);
        jdbc.execute(EPOCH_TABLE_DDL);
    }
}
