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

package com.nageoffer.ai.ragent.rag.runtime;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * PG 实现的最小受理账本（Spec §7.2「最小原子受理」）。
 *
 * <p>{@link #accept} 在<b>同一事务</b>写入 {@code ai_run}、{@code ai_run_event(seq=1)}、
 * {@code outbox_event} 与一笔 {@code ai_usage_ledger(reserve)}；任一步失败整体回滚，
 * 提交成功才允许返回 202。
 *
 * <p>实现细节：outbox 只引用已持久化的 {@code eventId}（不做第二事实源），本实验不投递；
 * 幂等靠 {@code uk_run_idempotency} 唯一约束裁决，而不是"先查后插"。
 */
@Repository
@ConditionalOnProperty(name = "p04.enabled", havingValue = "true")
public class JdbcRunStore implements RunStore {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<AcceptanceFaultHook> faultHook;

    public JdbcRunStore(JdbcTemplate jdbcTemplate, ObjectProvider<AcceptanceFaultHook> faultHook) {
        this.jdbcTemplate = jdbcTemplate;
        this.faultHook = faultHook;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StoredRun accept(NewRun run) {
        try {
            jdbcTemplate.update("INSERT INTO ai_run (id, tenant_id, membership_id, subject, action, "
                            + "idempotency_key, request_hash, status, policy_version, acl_version) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    run.runId(), run.tenantId(), run.membershipId(), run.subject(), run.action(),
                    run.idempotencyKey(), run.requestHash(), "QUEUED", run.policyVersion(), run.aclVersion());
            faultHook.ifAvailable(hook -> hook.afterWrite("run", run.runId()));

            jdbcTemplate.update("INSERT INTO ai_run_event (id, run_id, seq, type, payload) VALUES (?,?,?,?,?)",
                    run.eventId(), run.runId(), 1, RunAcceptanceService.EVENT_RUN_ACCEPTED, null);
            faultHook.ifAvailable(hook -> hook.afterWrite("event", run.runId()));

            // 合成额度：每个新 run 预占 1 个实验单位；真实竞争/结算属 P2
            jdbcTemplate.update("INSERT INTO ai_usage_ledger (id, run_id, tenant_id, units, state) VALUES (?,?,?,?,?)",
                    run.ledgerId(), run.runId(), run.tenantId(), 1, "RESERVED");
            faultHook.ifAvailable(hook -> hook.afterWrite("reserve", run.runId()));

            jdbcTemplate.update("INSERT INTO outbox_event (id, run_id, event_id, topic, payload) VALUES (?,?,?,?,?)",
                    run.outboxId(), run.runId(), run.eventId(), "ai.run.accepted", null);
            faultHook.ifAvailable(hook -> hook.afterWrite("outbox", run.runId()));

            // F01：四类记录已写入、事务尚未提交；此处抛出会整体回滚
            faultHook.ifAvailable(hook -> hook.beforeCommit(run.runId()));

            return new StoredRun(run.runId(), run.requestHash());
        } catch (DuplicateKeyException e) {
            throw new IdempotencyConflict("duplicate idempotency key or run id", e);
        }
    }

    @Override
    public Optional<StoredRun> findByIdempotencyKey(String tenantId, String membershipId, String action,
                                                    String idempotencyKey) {
        try {
            StoredRun stored = jdbcTemplate.queryForObject(
                    "SELECT id, request_hash FROM ai_run WHERE tenant_id=? AND membership_id=? AND action=? "
                            + "AND idempotency_key=?",
                    (rs, rowNum) -> new StoredRun(rs.getString("id"), rs.getString("request_hash")),
                    tenantId, membershipId, action, idempotencyKey);
            return Optional.ofNullable(stored);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean recordJti(String issuer, String jti) {
        try {
            jdbcTemplate.update("INSERT INTO p04_replay_guard (issuer, jti) VALUES (?,?)", issuer, jti);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
