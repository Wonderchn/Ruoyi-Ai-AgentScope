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

package com.nageoffer.ai.ragent.runtime.usage;

import com.nageoffer.ai.ragent.runtime.CanonicalJson;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 调用账本（{@code ai_model_call}）与预算结算。
 *
 * <p>契约（P0.3 §6）：reserve → settle → release 幂等；重复 settle 只生效一次；
 * 供应商用量未知 → PENDING_RECONCILIATION，不得按零消耗入账；取消不假定费用为 0。
 */
@Service
public class UsageLedgerService {

    public static final String KIND_CHAT = "CHAT";
    public static final String KIND_EMBEDDING = "EMBEDDING";
    public static final String STATE_STARTED = "STARTED";
    public static final String STATE_SETTLED = "SETTLED";
    public static final String STATE_FAILED = "FAILED";
    public static final String STATE_PENDING_RECONCILIATION = "PENDING_RECONCILIATION";
    public static final String STATE_RELEASED = "RELEASED";

    private final JdbcTemplate jdbc;

    public UsageLedgerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 调用前落账（内部 callId 在缺 providerRequestId 时仍能定位同一次调用）。 */
    public String startCall(String tenantId, String runId, int attempt, String stepId, String kind,
                            String provider, String model, String requestHash) {
        String callId = "call-" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO ai_model_call (tenant_id, call_id, run_id, attempt, step_id, kind, provider, "
                        + "model, request_hash, state) VALUES (?,?,?,?,?,?,?,?,?,?)",
                tenantId, callId, runId, attempt, stepId, kind, provider, model, requestHash, STATE_STARTED);
        return callId;
    }

    /**
     * 幂等结算：仅 STARTED → SETTLED 一次；providerRequestId 唯一约束防重复计量。
     * usage 未知时调用方传 {@code usageRaw=null} → 保持 PENDING_RECONCILIATION。
     */
    public void settle(String tenantId, String callId, String providerRequestId, Map<String, Object> usageRaw) {
        String usageJson = usageRaw == null ? null : CanonicalJson.strictMapper().valueToTree(usageRaw).toString();
        String state = usageRaw == null ? STATE_PENDING_RECONCILIATION : STATE_SETTLED;
        try {
            jdbc.update("UPDATE ai_model_call SET state=?, provider_request_id=?, usage_raw=?::jsonb, updated_at=now() "
                            + "WHERE tenant_id=? AND call_id=? AND state=?",
                    state, providerRequestId, usageJson, tenantId, callId, STATE_STARTED);
        } catch (DuplicateKeyException e) {
            // 同一 providerRequestId 已结算：本调用标记为重复，不二次计量
            jdbc.update("UPDATE ai_model_call SET state='RELEASED', updated_at=now() "
                    + "WHERE tenant_id=? AND call_id=? AND state=?", tenantId, callId, STATE_STARTED);
        }
    }

    public void markFailed(String tenantId, String callId, String errorCode) {
        jdbc.update("UPDATE ai_model_call SET state='FAILED', error_code=?, updated_at=now() "
                + "WHERE tenant_id=? AND call_id=? AND state=?", errorCode, tenantId, callId, STATE_STARTED);
    }

    /** 已确认用量（合成单位）与未决（待核对）调用数。 */
    public record RunUsage(long settledCalls, long pendingReconciliation, long failedCalls) {
    }

    public RunUsage usageOf(String tenantId, String runId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT state, count(*) AS n FROM ai_model_call WHERE tenant_id=? AND run_id=? GROUP BY state",
                tenantId, runId);
        long settled = 0;
        long pending = 0;
        long failed = 0;
        for (Map<String, Object> row : rows) {
            String state = String.valueOf(row.get("state"));
            long count = ((Number) row.get("n")).longValue();
            switch (state) {
                case STATE_SETTLED -> settled += count;
                case STATE_PENDING_RECONCILIATION -> pending += count;
                case STATE_FAILED -> failed += count;
                default -> {
                }
            }
        }
        return new RunUsage(settled, pending, failed);
    }

    /**
     * run 终态时的预算处理：有待核对调用 → 保持 RESERVED（未知不按 0 结算）；
     * 否则按已确认调用数结算（合成单位 = 调用数）。
     */
    public void finalizeReservation(String tenantId, String runId) {
        RunUsage usage = usageOf(tenantId, runId);
        if (usage.pendingReconciliation() > 0) {
            return;
        }
        long units = Math.max(1, usage.settledCalls());
        jdbc.update("UPDATE ai_budget_reservation SET units=?, state='SETTLED', updated_at=now() "
                + "WHERE tenant_id=? AND run_id=? AND state='RESERVED'", units, tenantId, runId);
    }

    public void releaseReservation(String tenantId, String runId) {
        jdbc.update("UPDATE ai_budget_reservation SET state='RELEASED', updated_at=now() "
                + "WHERE tenant_id=? AND run_id=? AND state='RESERVED'", tenantId, runId);
    }
}
