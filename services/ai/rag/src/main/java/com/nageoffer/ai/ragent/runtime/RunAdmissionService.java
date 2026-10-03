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

package com.nageoffer.ai.ragent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.dto.AdmissionRequest;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 原子受理（P0.3 §2.2；P2 计划 U01）。
 *
 * <p>顺序即契约：调用方（委托过滤器 + 控制器）已完成身份与当前授权判定，本服务在
 * <b>当前鉴权之后</b>查幂等；同事务写入 run + accepted(seq=1) + 引用 eventId 的
 * outbox + 一笔真实 budget reserve；提交成功才返回 202。
 *
 * <p>幂等唯一约束 {@code uk_ai_run_idempotency} 裁决并发；同键同体返回原 runId
 * （replayed=true），异体 409。查找/插入都不依赖"先查后插"的乐观假设。
 */
@Service
public class RunAdmissionService {

    /** P2 首期开放动作；agent.run 由 P3 激活后加入。 */
    private static final Set<String> P2_ACTIONS = Set.of("rag.chat", "document.ingest");

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[\\x21-\\x7E]{1,128}");

    private final RunLedgerDao dao;
    private final RunEventAppender events;
    private final P2RuntimeProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final org.springframework.beans.factory.ObjectProvider<P2FaultInjector> faultInjector;

    public RunAdmissionService(RunLedgerDao dao, RunEventAppender events, P2RuntimeProperties properties,
                               PlatformTransactionManager transactionManager,
                               org.springframework.beans.factory.ObjectProvider<P2FaultInjector> faultInjector) {
        this.dao = dao;
        this.events = events;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.faultInjector = faultInjector;
    }

    private void fault(String hook) {
        P2FaultInjector injector = faultInjector.getIfAvailable();
        if (injector != null) {
            injector.checkpoint(hook);
        }
    }

    public record AdmissionResult(String runId, String status, Instant createdAt, boolean replayed) {
    }

    public AdmissionResult admit(ExecutionPrincipal principal, String idempotencyKey, AdmissionRequest request) {
        validate(principal, idempotencyKey, request);
        String requestHash = CanonicalJson.requestHash(request);
        Optional<RunRecord> fastPath = dao.findByIdempotency(
                principal.tenantId(), principal.membershipId(), request.action(), idempotencyKey);
        if (fastPath.isPresent()) {
            return replayOrConflict(fastPath.get(), requestHash);
        }
        try {
            return transactionTemplate.execute(status -> doAdmit(principal, idempotencyKey, request, requestHash));
        } catch (DuplicateKeyException e) {
            Optional<RunRecord> existing = dao.findByIdempotency(
                    principal.tenantId(), principal.membershipId(), request.action(), idempotencyKey);
            if (existing.isEmpty()) {
                throw new RunApiException(RunErrorCode.INTERNAL_ERROR, "admission conflict without persisted run");
            }
            return replayOrConflict(existing.get(), requestHash);
        }
    }

    private AdmissionResult replayOrConflict(RunRecord existing, String requestHash) {
        if (!requestHash.equals(existing.requestHash())) {
            throw new RunApiException(RunErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return new AdmissionResult(existing.runId(), existing.status(), existing.createdAt(), true);
    }

    private AdmissionResult doAdmit(ExecutionPrincipal principal, String idempotencyKey,
                                    AdmissionRequest request, String requestHash) {
        String tenantId = principal.tenantId();
        // 1) 预算：锁租户行 → 叠加预占不超上限（并发失败方 BUDGET_EXCEEDED）
        long limit = dao.lockTenantBudget(tenantId, properties.getBudget().getDefaultTenantUnits());
        long units = unitsOf(request.budget());
        if (dao.sumReserved(tenantId) + units > limit) {
            throw new RunApiException(RunErrorCode.BUDGET_EXCEEDED);
        }
        // 2) run
        String runId = RunEventAppender.newRunId();
        dao.insertRun(tenantId, runId, principal.membershipId(), principal.userId(), request.action(),
                idempotencyKey, requestHash, toJson(request.input()), toJson(request.budget()),
                "p2-v1", principal.policyVersion(), principal.aclVersion(), toJson(request.resourceRefs()),
                request.retryOf());
        fault(P2FaultInjector.ADMISSION_AFTER_RUN);
        // 3) 受理事件 seq=1 + outbox（引用已持久化 eventId）
        Map<String, Object> accepted = new LinkedHashMap<>();
        accepted.put("status", "QUEUED");
        accepted.put("action", request.action());
        accepted.put("budget", request.budget());
        accepted.put("policyVersion", principal.policyVersion());
        accepted.put("aclVersion", principal.aclVersion());
        events.appendWithinAdmission(tenantId, runId, RunEventAppender.EVENT_ACCEPTED, accepted, true);
        fault(P2FaultInjector.ADMISSION_AFTER_EVENT);
        // 4) 真实预占
        dao.insertReservation(tenantId, "res-" + UUID.randomUUID().toString().replace("-", ""),
                runId, principal.membershipId(), units);
        fault(P2FaultInjector.ADMISSION_AFTER_RESERVE);
        fault(P2FaultInjector.ADMISSION_AFTER_OUTBOX);
        fault(P2FaultInjector.ADMISSION_BEFORE_COMMIT);
        RunRecord created = dao.findRun(tenantId, runId)
                .orElseThrow(() -> new RunApiException(RunErrorCode.INTERNAL_ERROR, "run readback failed"));
        return new AdmissionResult(runId, created.status(), created.createdAt(), false);
    }

    private long unitsOf(JsonNode budget) {
        if (budget != null && budget.hasNonNull("maxTokens")) {
            long tokens = Math.max(0, budget.path("maxTokens").asLong());
            long k = Math.max(1, (tokens + 999) / 1000);
            return k * Math.max(1, properties.getBudget().getUnitsPerKTok());
        }
        return Math.max(1, properties.getBudget().getDefaultRunUnits());
    }

    private void validate(ExecutionPrincipal principal, String idempotencyKey, AdmissionRequest request) {
        if (principal == null) {
            throw new RunApiException(RunErrorCode.AUTH_REQUIRED);
        }
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "Idempotency-Key is required (1..128 visible ASCII)");
        }
        if (request == null || request.schemaVersion() == null || request.schemaVersion() != 1) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "schemaVersion 1 is required");
        }
        if (request.action() == null || !P2_ACTIONS.contains(request.action())) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "action is not enabled in P2 core");
        }
        if (request.resourceRefs() != null && request.resourceRefs().size() > 32) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "too many resource refs");
        }
    }

    private String toJson(Object value) {
        return value == null ? null : events.toJson(value);
    }
}
