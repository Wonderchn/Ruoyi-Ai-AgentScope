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

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;

/**
 * ai_acl_epoch（ACL epoch）DAO：租户内授权版本，撤权/授权同事务递增。
 *
 * <p>表契约（V3，DDL 是唯一权威）：按租户一行，<b>不预置行</b>。
 * 读语义必须维持"行不存在返回 empty，调用方拒绝"——<b>绝不默认初始化到 1</b>：
 * 一个没有 epoch 行的租户要么尚未开通，要么数据不完整，两种情况都不允许
 * 用默认版本把授权判定"修"成可放行。
 */
@Repository
public class AiAclEpochMapper {

    private final NamedParameterJdbcTemplate jdbc;

    public AiAclEpochMapper(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 读当前版本；行不存在返回 {@code empty}（不得默认 1）。
     */
    public Optional<Integer> findVersion(String tenantId) {
        String sql = "SELECT version FROM ai_acl_epoch WHERE tenant_id = :tenantId";
        try {
            Integer version = jdbc.queryForObject(sql, Map.of("tenantId", tenantId), Integer.class);
            return Optional.ofNullable(version);
        } catch (EmptyResultDataAccessException e) {
            // 无 epoch 行：这是必须显式暴露的拒绝语义，不是"取默认值"的机会
            return Optional.empty();
        }
    }

    /**
     * 事务内递增版本并返回新值；行不存在返回 {@code empty}（写路径同样拒绝默认化）。
     *
     * <p>{@code RETURNING} 保证"递增 + 读回"是单语句原子操作，
     * 不存在"先读后写"的竞态窗口。
     */
    public Optional<Integer> bump(String tenantId) {
        String sql = "UPDATE ai_acl_epoch SET version = version + 1, update_time = CURRENT_TIMESTAMP"
                + " WHERE tenant_id = :tenantId RETURNING version";
        try {
            Integer version = jdbc.queryForObject(sql, Map.of("tenantId", tenantId), Integer.class);
            return Optional.ofNullable(version);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
