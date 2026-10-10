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

package com.nageoffer.ai.ragent.runtime.dao;

import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 正式运行账本 DAO（V5/V8 表；唯一运行事实）。
 *
 * <p>所有状态/步骤/事件写入都通过本类；executor 只能在通过 {@code fence} 校验的
 * 路径上推进 run。JSON 以字符串传入并显式 {@code ::jsonb} 转型。
 */
@Repository
public class RunLedgerDao {

    private final JdbcTemplate jdbc;

    public RunLedgerDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- run row

    private static final String RUN_COLUMNS = "tenant_id, run_id, member_id, subject, action, status, "
            + "request_hash, idempotency_key, input::text AS input_json, budget::text AS budget_json, "
            + "execution_version, policy_version, acl_version, resource_refs::text AS resource_refs_json, "
            + "version, next_seq, attempt, fence, lease_owner, lease_until, terminal_result::text AS terminal_result_json, "
            + "error_code, retry_of, cancel_requested_at, created_at, started_at, finished_at";

    private static RunRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new RunRecord(
                rs.getString("tenant_id"),
                rs.getString("run_id"),
                rs.getString("member_id"),
                rs.getString("subject"),
                rs.getString("action"),
                rs.getString("status"),
                rs.getString("request_hash"),
                rs.getString("idempotency_key"),
                rs.getString("input_json"),
                rs.getString("budget_json"),
                rs.getString("execution_version"),
                rs.getInt("policy_version"),
                rs.getInt("acl_version"),
                rs.getString("resource_refs_json"),
                rs.getLong("version"),
                rs.getLong("next_seq"),
                rs.getInt("attempt"),
                rs.getLong("fence"),
                rs.getString("lease_owner"),
                toInstant(rs.getTimestamp("lease_until")),
                rs.getString("terminal_result_json"),
                rs.getString("error_code"),
                rs.getString("retry_of"),
                toInstant(rs.getTimestamp("cancel_requested_at")),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("finished_at")));
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    public Optional<RunRecord> findRun(String tenantId, String runId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + RUN_COLUMNS + " FROM ai_run WHERE tenant_id=? AND run_id=?",
                    RunLedgerDao::map, tenantId, runId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<RunRecord> findByIdempotency(String tenantId, String subject, String action, String key) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + RUN_COLUMNS + " FROM ai_run WHERE tenant_id=? AND subject=? AND action=? AND idempotency_key=?",
                    RunLedgerDao::map, tenantId, subject, action, key));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 行锁读取；必须在事务内调用。 */
    public Optional<RunRecord> lockRun(String tenantId, String runId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + RUN_COLUMNS + " FROM ai_run WHERE tenant_id=? AND run_id=? FOR UPDATE",
                    RunLedgerDao::map, tenantId, runId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void insertRun(String tenantId, String runId, String memberId, String subject, String action,
                          String idempotencyKey, String requestHash, String inputJson, String budgetJson,
                          String executionVersion, int policyVersion, int aclVersion, String resourceRefsJson,
                          String retryOf) {
        insertRun(tenantId, runId, memberId, subject, action, idempotencyKey, requestHash, inputJson, budgetJson,
                executionVersion, policyVersion, aclVersion, resourceRefsJson, retryOf, null);
    }

    /**
     * 受理时**同时绑定该 run 受理那一刻的发布版本**（C1.2 第 1 行 / C1.3）。
     *
     * <p><b>为什么必须在这里写。</b>执行期用 {@code RunConfigBindingPort} 解析的是
     * {@code ai_run.config_revision_id}；这一列在此之前**全仓没有任何写入点** ——
     * 于是"新受理的 run 绑新版本"这条契约从受理侧就是空的，任何执行期解析都会以
     * "run has no bound config revision" 拒绝。受理时刻是唯一能固定版本的时刻：
     * 之后再发布新版本不得改动已受理 run 的解释（C1.2 第 2 行）。
     *
     * @param binding 受理时解析出的**新**发布版本事实；{@code null} 表示不绑定 ——
     *                刻意保留给"权威建立之前的历史写路径/测试"。**运行期受理路径
     *                必须传事实**（{@code RunAdmissionService} 在缺权威时拒绝受理，而不是写 null）。
     */
    public void insertRun(String tenantId, String runId, String memberId, String subject, String action,
                          String idempotencyKey, String requestHash, String inputJson, String budgetJson,
                          String executionVersion, int policyVersion, int aclVersion, String resourceRefsJson,
                          String retryOf, com.nageoffer.ai.ragent.runtime.config.RunConfigBinding binding) {
        jdbc.update("INSERT INTO ai_run (tenant_id, run_id, member_id, subject, action, status, idempotency_key, "
                        + "request_hash, input, budget, execution_version, policy_version, acl_version, resource_refs, "
                        + "version, next_seq, attempt, fence, retry_of, "
                        + "config_revision_id, catalog_version, params_hash, provider_id, model_id, config_operator, config_published_at) "
                        + "VALUES (?,?,?,?,?,'QUEUED',?,?,?::jsonb,?::jsonb,?,?,?,?::jsonb,0,1,0,0,?, ?,?,?,?,?,?,?)",
                tenantId, runId, memberId, subject, action, idempotencyKey, requestHash, inputJson, budgetJson,
                executionVersion, policyVersion, aclVersion, resourceRefsJson, retryOf,
                binding == null ? null : binding.revisionId(),
                binding == null ? null : binding.catalogVersion(),
                binding == null ? null : binding.paramsHash(),
                binding == null ? null : binding.providerId(),
                binding == null ? null : binding.modelId(),
                binding == null ? null : binding.operatorId(),
                binding == null || binding.publishedAt() == null ? null
                        : java.sql.Timestamp.from(binding.publishedAt()));
    }

    // ---------------------------------------------------------------- events
    public void registerRun(String tenant,String run,String member,String department) {
        jdbc.update("INSERT INTO ai_resource(tenant_id,resource_type,resource_id,owner_member_id,owner_dept_id,status,resource_version,created_by_member) "
                +"VALUES (?,'RUN',?,?,?,'ACTIVE',1,?)",tenant,run,member,department,member);
    }

    public void insertEvent(String tenantId, String runId, long seq, String eventId, String type, String payloadJson) {
        jdbc.update("INSERT INTO ai_run_event (tenant_id, run_id, seq, event_id, event_type, payload, schema_version) "
                        + "VALUES (?,?,?,?,?,?::jsonb,1)",
                tenantId, runId, seq, eventId, type, payloadJson);
    }

    /** 行锁下递增 next_seq 并返回本事件 seq；回滚不消耗可见 seq。必须在事务内调用。 */
    public long allocateSeq(String tenantId, String runId) {
        Long seq = jdbc.queryForObject(
                "UPDATE ai_run SET next_seq = next_seq + 1, version = version + 1, updated_at = now() "
                        + "WHERE tenant_id=? AND run_id=? RETURNING next_seq - 1",
                Long.class, tenantId, runId);
        if (seq == null) {
            throw new com.nageoffer.ai.ragent.runtime.RunApiException(
                    com.nageoffer.ai.ragent.runtime.RunErrorCode.INTERNAL_ERROR, "run seq allocation failed");
        }
        return seq;
    }

    public void insertOutbox(String tenantId, String eventId, String runId, String eventType, long seq, String payloadJson) {
        jdbc.update("INSERT INTO outbox_event (tenant_id, event_id, run_id, event_type, payload, state, seq, schema_version) "
                        + "VALUES (?,?,?,?,?::jsonb,'PENDING',?,1)",
                tenantId, eventId, runId, eventType, payloadJson, seq);
    }

    public record EventRow(long seq, String eventId, String type, String payloadJson, Instant createdAt) {
    }

    public List<EventRow> listEvents(String tenantId, String runId, long afterSeq, int limit) {
        return jdbc.query("SELECT seq, event_id, event_type, payload::text AS payload_json, created_at "
                        + "FROM ai_run_event WHERE tenant_id=? AND run_id=? AND seq>? ORDER BY seq LIMIT ?",
                (rs, rowNum) -> new EventRow(rs.getLong("seq"), rs.getString("event_id"),
                        rs.getString("event_type"), rs.getString("payload_json"),
                        toInstant(rs.getTimestamp("created_at"))),
                tenantId, runId, afterSeq, limit);
    }

    public long maxSeq(String tenantId, String runId) {
        Long max = jdbc.queryForObject(
                "SELECT coalesce(max(seq),0) FROM ai_run_event WHERE tenant_id=? AND run_id=?",
                Long.class, tenantId, runId);
        return max == null ? 0 : max;
    }

    public long minSeq(String tenantId, String runId) {
        Long min = jdbc.queryForObject(
                "SELECT coalesce(min(seq),0) FROM ai_run_event WHERE tenant_id=? AND run_id=?",
                Long.class, tenantId, runId);
        return min == null ? 0 : min;
    }

    /**
     * 保留期判定（P0.3 §3.3）：游标之后首个待回放事件是否已早于保留窗口。
     *
     * <p>返回 true = 该回放请求涉及保留期外历史，调用方以 410 + 已持久化快照收口，不得静默回放。
     * 时间窗比较全部在 SQL 内以 DB {@code now()} 完成——app/VM 时钟与 DB 差 +8h（D4），
     * 不得用应用时钟折算 DB 落库的 {@code created_at}；游标已追平（无待回放事件）不算超期。
     */
    public boolean retentionExpired(String tenantId, String runId, long afterSeq, int retentionHours) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT coalesce(min(created_at) <= now() - (? * interval '1 hour'), false) "
                        + "FROM ai_run_event WHERE tenant_id=? AND run_id=? AND seq>?",
                Boolean.class, retentionHours, tenantId, runId, afterSeq));
    }

    // ---------------------------------------------------------------- status transitions

    /** 带 fence/owner 校验的状态更新；返回 false = 旧 Worker/租约冲突。 */
    public boolean updateStatusFenced(String tenantId, String runId, String expectedOwner, long expectedFence,
                                      String status, String errorCode) {
        return jdbc.update("UPDATE ai_run SET status=?, error_code=?, version=version+1, updated_at=now() "
                        + "WHERE tenant_id=? AND run_id=? AND lease_owner=? AND fence=? AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED')",
                status, errorCode, tenantId, runId, expectedOwner, expectedFence) == 1;
    }

    public boolean updateTerminalFenced(String tenantId, String runId, String expectedOwner, long expectedFence,
                                        String status, String terminalResultJson, String errorCode) {
        return jdbc.update("UPDATE ai_run SET status=?, terminal_result=?::jsonb, error_code=?, finished_at=now(), "
                        + "version=version+1, updated_at=now() WHERE tenant_id=? AND run_id=? AND lease_owner=? AND fence=? "
                        + "AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED')",
                status, terminalResultJson, errorCode, tenantId, runId, expectedOwner, expectedFence) == 1;
    }

    public boolean updateTerminalUnfenced(String tenantId, String runId, String status,
                                          String terminalResultJson, String errorCode) {
        return jdbc.update("UPDATE ai_run SET status=?, terminal_result=?::jsonb, error_code=?, finished_at=now(), "
                        + "lease_until=now(), version=version+1, updated_at=now() "
                        + "WHERE tenant_id=? AND run_id=? AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED')",
                status, terminalResultJson, errorCode, tenantId, runId) == 1;
    }

    /** 取消：非终态 → CANCEL_REQUESTED（幂等）。返回是否发生状态变化。 */
    public boolean requestCancel(String tenantId, String runId) {
        return jdbc.update("UPDATE ai_run SET status='CANCEL_REQUESTED', cancel_requested_at=now(), "
                        + "version=version+1, updated_at=now() "
                        + "WHERE tenant_id=? AND run_id=? AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED','CANCEL_REQUESTED')",
                tenantId, runId) == 1;
    }

    /** resume：CAS + 非终态白名单 + 重新排队。 */
    public boolean resumeToQueued(String tenantId, String runId, long expectedVersion) {
        return jdbc.update("UPDATE ai_run SET status='QUEUED', lease_owner=NULL, lease_until=NULL, error_code=NULL, "
                        + "cancel_requested_at=NULL, version=version+1, updated_at=now() "
                        + "WHERE tenant_id=? AND run_id=? AND version=? "
                        + "AND status IN ('RECOVERING','RETRY_WAIT','CANCEL_REQUESTED','NEEDS_RECONCILIATION')",
                tenantId, runId, expectedVersion) == 1;
    }

    // ---------------------------------------------------------------- claim / lease

    public record ClaimedRun(RunRecord run, int newAttempt, long newFence) {
    }

    /** 短事务认领：SKIP LOCKED 选取、递增 attempt/fence、写入租约。 */
    public Optional<ClaimedRun> claimNext(String workerId, int leaseSeconds) {
        List<ClaimedRun> claimed = jdbc.query(
                "WITH candidate AS ("
                        + " SELECT tenant_id, run_id FROM ai_run "
                        + " WHERE idempotency_key IS NOT NULL AND status IN ('QUEUED','RETRY_WAIT','RECOVERING') AND (lease_until IS NULL OR lease_until < now()) "
                        + " ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1) "
                        + "UPDATE ai_run r SET status='RUNNING', lease_owner=?, lease_until=now() + (? * interval '1 second'), "
                        + "attempt=r.attempt+1, fence=r.fence+1, version=r.version+1, "
                        + "started_at=coalesce(r.started_at, now()), updated_at=now() "
                        + "FROM candidate c WHERE r.tenant_id=c.tenant_id AND r.run_id=c.run_id "
                        + "RETURNING " + RUN_COLUMNS_PREFIXED,
                (rs, rowNum) -> new ClaimedRun(map(rs, rowNum), rs.getInt("attempt"), rs.getLong("fence")),
                workerId, leaseSeconds);
        return claimed.isEmpty() ? Optional.empty() : Optional.of(claimed.get(0));
    }

    private static final String RUN_COLUMNS_PREFIXED =
            "r.tenant_id, r.run_id, r.member_id, r.subject, r.action, r.status, r.request_hash, r.idempotency_key, "
                    + "r.input::text AS input_json, r.budget::text AS budget_json, r.execution_version, r.policy_version, "
                    + "r.acl_version, r.resource_refs::text AS resource_refs_json, r.version, r.next_seq, r.attempt, r.fence, "
                    + "r.lease_owner, r.lease_until, r.terminal_result::text AS terminal_result_json, r.error_code, r.retry_of, "
                    + "r.cancel_requested_at, r.created_at, r.started_at, r.finished_at";

    /** 续租：owner+fence 校验；失败 = 已被接管。 */
    public boolean renewLease(String tenantId, String runId, String workerId, long fence, int leaseSeconds) {
        return jdbc.update("UPDATE ai_run SET lease_until=now() + (? * interval '1 second'), updated_at=now() "
                        + "WHERE tenant_id=? AND run_id=? AND lease_owner=? AND fence=? AND lease_until>now() AND status='RUNNING'",
                leaseSeconds, tenantId, runId, workerId, fence) == 1;
    }

    /** 完成后释放租约（保留 owner/fence 供审计），不改状态。 */
    public void releaseLease(String tenantId, String runId, String workerId, long fence) {
        jdbc.update("UPDATE ai_run SET lease_until=now(), updated_at=now() "
                        + "WHERE tenant_id=? AND run_id=? AND lease_owner=? AND fence=?",
                tenantId, runId, workerId, fence);
    }

    /** 进程退出/取消后重新排队的非终态恢复（Worker 内使用）。 */
    public boolean requeueForRetry(String tenantId, String runId, String workerId, long fence, String errorCode) {
        return jdbc.update("UPDATE ai_run SET status='RETRY_WAIT', error_code=?, lease_until=now(), version=version+1, updated_at=now() "
                        + "WHERE tenant_id=? AND run_id=? AND lease_owner=? AND fence=? AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED')",
                errorCode, tenantId, runId, workerId, fence) == 1;
    }

    public boolean recoverInterrupted(String tenantId, String runId, String workerId, long fence, String errorCode) {
        return jdbc.update("UPDATE ai_run SET status='RECOVERING', error_code=?, lease_until=now(), version=version+1, updated_at=now() "
                        + "WHERE tenant_id=? AND run_id=? AND lease_owner=? AND fence=? AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED')",
                errorCode, tenantId, runId, workerId, fence) == 1;
    }

    /** 只把超期未续租的 RUNNING 归入 RECOVERING（扫描补偿；不伪造排空）。 */
    public int sweepExpiredLeases() {
        return jdbc.update("UPDATE ai_run SET status='RECOVERING', error_code='LEASE_EXPIRED', version=version+1, updated_at=now() "
                + "WHERE status='RUNNING' AND lease_until IS NOT NULL AND lease_until < now()");
    }

    /** 无活动租约的 CANCEL_REQUESTED run（供 Worker 幂等封口）。 */
    public List<String[]> listCancelPending(int limit) {
        return jdbc.query("SELECT tenant_id, run_id FROM ai_run WHERE status='CANCEL_REQUESTED' "
                        + "AND (lease_until IS NULL OR lease_until < now()) ORDER BY created_at LIMIT ?",
                (rs, rowNum) -> new String[]{rs.getString(1), rs.getString(2)}, limit);
    }

    // ---------------------------------------------------------------- steps

    public record StepRow(String stepId, int attempt, int checkpointVersion, String stepName, String state,
                          String refJson, String refHash, String usageJson, long fence, Instant createdAt, Instant updatedAt) {
    }

    /** fence 校验下幂等插入检查点；已存在时返回既有行。 */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public StepRow commitStep(String tenantId, String runId, String stepId, int attempt, String stepName,
                              String refJson, String refHash, String usageJson, long fence, String owner) {
        lockRun(tenantId, runId).orElseThrow(() -> new com.nageoffer.ai.ragent.runtime.RunApiException(
                com.nageoffer.ai.ragent.runtime.RunErrorCode.VERSION_CONFLICT));
        if (!ownsLiveLease(tenantId, runId, owner, fence)) {
            throw new com.nageoffer.ai.ragent.runtime.RunApiException(
                    com.nageoffer.ai.ragent.runtime.RunErrorCode.VERSION_CONFLICT);
        }
        int inserted = jdbc.update("INSERT INTO ai_run_step (tenant_id, run_id, step_id, attempt, checkpoint_version, "
                        + "step_name, state, ref, ref_hash, usage, fence) "
                        + "SELECT ?,?,?,?,1,?,'COMPLETED',?::jsonb,?,?::jsonb,? "
                        + "FROM ai_run WHERE tenant_id=? AND run_id=? AND fence=? "
                        + "ON CONFLICT (tenant_id, run_id, step_id, attempt, checkpoint_version) DO NOTHING",
                tenantId, runId, stepId, attempt, stepName, refJson, refHash, usageJson, fence,
                tenantId, runId, fence);
        if (inserted == 0) {
            Optional<StepRow> existing = findStep(tenantId, runId, stepId, attempt);
            if (existing.isPresent()) {
                return existing.get();
            }
            throw new com.nageoffer.ai.ragent.runtime.RunApiException(
                    com.nageoffer.ai.ragent.runtime.RunErrorCode.VERSION_CONFLICT, "step commit rejected by fence");
        }
        return new StepRow(stepId, attempt, 1, stepName, "COMPLETED", refJson, refHash, usageJson, fence,
                Instant.now(), Instant.now());
    }

    public boolean ownsLiveLease(String tenantId, String runId, String owner, long fence) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ai_run WHERE tenant_id=? "
                + "AND run_id=? AND lease_owner=? AND fence=? AND lease_until>now() "
                + "AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED'))", Boolean.class, tenantId, runId, owner, fence));
    }

    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public <T> T withLiveFence(String tenantId,String runId,String owner,long fence,java.util.function.Supplier<T> commit) {
        lockRun(tenantId,runId).orElseThrow(() -> new com.nageoffer.ai.ragent.runtime.RunApiException(
                com.nageoffer.ai.ragent.runtime.RunErrorCode.VERSION_CONFLICT));
        if(!ownsLiveLease(tenantId,runId,owner,fence)) throw new com.nageoffer.ai.ragent.runtime.RunApiException(
                com.nageoffer.ai.ragent.runtime.RunErrorCode.VERSION_CONFLICT);
        return commit.get();
    }

    public boolean suspend(String tenantId,String runId,String owner,long fence,String status,String error) {
        if(!java.util.Set.of("WAITING_APPROVAL","NEEDS_RECONCILIATION").contains(status)) throw new IllegalArgumentException("invalid suspension");
        return jdbc.update("UPDATE ai_run SET status=?,error_code=?,lease_until=now(),version=version+1,updated_at=now() "
                +"WHERE tenant_id=? AND run_id=? AND lease_owner=? AND fence=? AND lease_until>now() AND status='RUNNING'",
                status,error,tenantId,runId,owner,fence)==1;
    }

    public Optional<StepRow> latestCompletedStep(String tenantId, String runId, String stepId) {
        return listSteps(tenantId, runId).stream().filter(step -> stepId.equals(step.stepId())
                && "COMPLETED".equals(step.state())).max(java.util.Comparator.comparingInt(StepRow::attempt));
    }

    public Optional<StepRow> findStep(String tenantId, String runId, String stepId, int attempt) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT step_id, attempt, checkpoint_version, step_name, state, ref::text AS ref_json, ref_hash, "
                            + "usage::text AS usage_json, fence, created_at, updated_at "
                            + "FROM ai_run_step WHERE tenant_id=? AND run_id=? AND step_id=? AND attempt=? "
                            + "ORDER BY checkpoint_version DESC LIMIT 1",
                    (rs, rowNum) -> new StepRow(rs.getString("step_id"), rs.getInt("attempt"),
                            rs.getInt("checkpoint_version"), rs.getString("step_name"), rs.getString("state"),
                            rs.getString("ref_json"), rs.getString("ref_hash"), rs.getString("usage_json"),
                            rs.getLong("fence"), toInstant(rs.getTimestamp("created_at")),
                            toInstant(rs.getTimestamp("updated_at"))),
                    tenantId, runId, stepId, attempt));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<StepRow> listSteps(String tenantId, String runId) {
        return jdbc.query("SELECT step_id, attempt, checkpoint_version, step_name, state, ref::text AS ref_json, ref_hash, "
                        + "usage::text AS usage_json, fence, created_at, updated_at FROM ai_run_step "
                        + "WHERE tenant_id=? AND run_id=? ORDER BY created_at, step_id",
                (rs, rowNum) -> new StepRow(rs.getString("step_id"), rs.getInt("attempt"),
                        rs.getInt("checkpoint_version"), rs.getString("step_name"), rs.getString("state"),
                        rs.getString("ref_json"), rs.getString("ref_hash"), rs.getString("usage_json"),
                        rs.getLong("fence"), toInstant(rs.getTimestamp("created_at")),
                        toInstant(rs.getTimestamp("updated_at"))),
                tenantId, runId);
    }

    // ---------------------------------------------------------------- budget

    /** 锁定（或建立默认）租户预算行；必须在事务内。 */
    public long lockTenantBudget(String tenantId, long defaultLimit) {
        jdbc.update("INSERT INTO ai_tenant_budget (tenant_id, limit_units) VALUES (?,?) ON CONFLICT (tenant_id) DO NOTHING",
                tenantId, defaultLimit);
        Long limit = jdbc.queryForObject(
                "SELECT limit_units FROM ai_tenant_budget WHERE tenant_id=? FOR UPDATE", Long.class, tenantId);
        return limit == null ? defaultLimit : limit;
    }

    public long sumReserved(String tenantId) {
        Long sum = jdbc.queryForObject(
                "SELECT coalesce(sum(units),0) FROM ai_budget_reservation WHERE tenant_id=? AND state='RESERVED'",
                Long.class, tenantId);
        return sum == null ? 0 : sum;
    }

    public void insertReservation(String tenantId, String reservationId, String runId, String memberId, long units) {
        jdbc.update("INSERT INTO ai_budget_reservation (tenant_id, reservation_id, run_id, member_id, units, state) "
                        + "VALUES (?,?,?,?,?,'RESERVED')",
                tenantId, reservationId, runId, memberId, units);
    }

    public void settleReservation(String tenantId, String runId, long settledUnits) {
        jdbc.update("UPDATE ai_budget_reservation SET units=?, state='SETTLED', updated_at=now() "
                        + "WHERE tenant_id=? AND run_id=? AND state='RESERVED'",
                settledUnits, tenantId, runId);
    }

    public void releaseReservation(String tenantId, String runId) {
        jdbc.update("UPDATE ai_budget_reservation SET state='RELEASED', updated_at=now() "
                        + "WHERE tenant_id=? AND run_id=? AND state='RESERVED'",
                tenantId, runId);
    }
}
