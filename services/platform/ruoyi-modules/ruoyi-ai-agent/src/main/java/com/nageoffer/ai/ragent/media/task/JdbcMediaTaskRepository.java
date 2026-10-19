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

package com.nageoffer.ai.ragent.media.task;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * {@link MediaTaskRepository} 的 JDBC 实现（{@code platform.ai_media_task}，V44）。
 *
 * <p>所有写路径都带状态谓词（CAS 风格），重复轮询/重复查询不会把终态改写；
 * 所有读路径都带 {@code tenant_id + owner_member_id}，跨租户不可见。
 */
public class JdbcMediaTaskRepository implements MediaTaskRepository {

    private static final String COLUMNS = "tenant_id, task_id, owner_member_id, owner_user_id, media_type,"
            + " provider, model, request_id, previous_task_id, attempt, status, provider_task_id, prompt,"
            + " size, seed, result_ref, result_mime, result_sha256, result_size, error_code, error_message,"
            + " usage_raw::text AS usage_json, units, reserved_cny, spend_call_id, created_at, updated_at";

    private static final RowMapper<MediaTaskRecord> MAPPER = JdbcMediaTaskRepository::map;

    private final JdbcTemplate jdbc;

    public JdbcMediaTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<MediaTaskRecord> findByRequestId(String tenantId, String ownerMemberId, String requestId) {
        return query("SELECT " + COLUMNS + " FROM platform.ai_media_task"
                        + " WHERE tenant_id=? AND owner_member_id=? AND request_id=?",
                tenantId, ownerMemberId, requestId);
    }

    @Override
    public Optional<MediaTaskRecord> findOwned(String tenantId, String ownerMemberId, String taskId) {
        return query("SELECT " + COLUMNS + " FROM platform.ai_media_task"
                        + " WHERE tenant_id=? AND owner_member_id=? AND task_id=?",
                tenantId, ownerMemberId, taskId);
    }

    @Override
    public List<MediaTaskRecord> listOwned(String tenantId, String ownerMemberId, int limit) {
        int bounded = Math.max(1, Math.min(100, limit));
        return jdbc.query("SELECT " + COLUMNS + " FROM platform.ai_media_task"
                        + " WHERE tenant_id=? AND owner_member_id=? ORDER BY created_at DESC, task_id DESC LIMIT ?",
                MAPPER, tenantId, ownerMemberId, bounded);
    }

    @Override
    public void insert(MediaTaskRecord task) {
        jdbc.update("INSERT INTO platform.ai_media_task(tenant_id, task_id, owner_member_id, owner_user_id,"
                        + " media_type, provider, model, request_id, previous_task_id, attempt, status,"
                        + " provider_task_id, prompt, size, seed, result_ref, result_mime, result_sha256,"
                        + " result_size, error_code, error_message, usage_raw, units, reserved_cny,"
                        + " spend_call_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?)",
                task.tenantId(), task.taskId(), task.ownerMemberId(), task.ownerUserId(), task.mediaType(),
                task.provider(), task.model(), task.requestId(), task.previousTaskId(), task.attempt(),
                task.status().name(), task.providerTaskId(), task.prompt(), task.size(), task.seed(),
                task.resultRef(), task.resultMime(), task.resultSha256(), task.resultSize(),
                task.errorCode(), task.errorMessage(), task.usageJson(), task.units(), task.reservedCny(),
                task.spendCallId());
    }

    @Override
    public boolean markRunning(String tenantId, String taskId, String providerTaskId) {
        return jdbc.update("UPDATE platform.ai_media_task SET status='RUNNING', provider_task_id=?,"
                        + " updated_at=now() WHERE tenant_id=? AND task_id=? AND status='QUEUED'",
                providerTaskId, tenantId, taskId) == 1;
    }

    @Override
    public boolean markSucceeded(String tenantId, String taskId, String resultRef, String resultMime,
                                 String resultSha256, long resultSize, String usageJson, long units,
                                 BigDecimal reservedCny) {
        return jdbc.update("UPDATE platform.ai_media_task SET status='SUCCEEDED', result_ref=?,"
                        + " result_mime=?, result_sha256=?, result_size=?, usage_raw=?::jsonb, units=?,"
                        + " reserved_cny=?, error_code=NULL, error_message=NULL, updated_at=now()"
                        + " WHERE tenant_id=? AND task_id=? AND status='RUNNING'",
                resultRef, resultMime, resultSha256, resultSize, usageJson, units, reservedCny,
                tenantId, taskId) == 1;
    }

    @Override
    public boolean markFailed(String tenantId, String taskId, String errorCode, String errorMessage) {
        return jdbc.update("UPDATE platform.ai_media_task SET status='FAILED', error_code=?, error_message=?,"
                        + " updated_at=now() WHERE tenant_id=? AND task_id=? AND status IN ('QUEUED','RUNNING')",
                errorCode, errorMessage, tenantId, taskId) == 1;
    }

    @Override
    public boolean markUnknown(String tenantId, String taskId, String errorCode, String errorMessage) {
        return jdbc.update("UPDATE platform.ai_media_task SET status='UNKNOWN', error_code=?, error_message=?,"
                        + " updated_at=now() WHERE tenant_id=? AND task_id=? AND status IN ('QUEUED','RUNNING')",
                errorCode, errorMessage, tenantId, taskId) == 1;
    }

    @Override
    public boolean attachSpendCall(String tenantId, String taskId, String spendCallId, BigDecimal reservedCny) {
        return jdbc.update("UPDATE platform.ai_media_task SET spend_call_id=?, reserved_cny=?, updated_at=now()"
                        + " WHERE tenant_id=? AND task_id=? AND status='QUEUED'",
                spendCallId, reservedCny, tenantId, taskId) == 1;
    }

    private Optional<MediaTaskRecord> query(String sql, Object... args) {
        List<MediaTaskRecord> rows = jdbc.query(sql, MAPPER, args);
        return rows.stream().findFirst();
    }

    private static MediaTaskRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new MediaTaskRecord(
                rs.getString("tenant_id"),
                rs.getString("task_id"),
                rs.getString("owner_member_id"),
                rs.getString("owner_user_id"),
                rs.getString("media_type"),
                rs.getString("provider"),
                rs.getString("model"),
                rs.getString("request_id"),
                rs.getString("previous_task_id"),
                rs.getInt("attempt"),
                MediaTaskStatus.fromDb(rs.getString("status")),
                rs.getString("provider_task_id"),
                rs.getString("prompt"),
                rs.getString("size"),
                (Integer) rs.getObject("seed"),
                rs.getString("result_ref"),
                rs.getString("result_mime"),
                rs.getString("result_sha256"),
                rs.getObject("result_size", Long.class),
                rs.getString("error_code"),
                rs.getString("error_message"),
                rs.getString("usage_json"),
                rs.getObject("units", Long.class),
                rs.getBigDecimal("reserved_cny"),
                rs.getString("spend_call_id"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
