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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import com.nageoffer.ai.ragent.runtime.model.RunStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 事件追加：一行锁内分配连续 seq，事件与 outbox 通知同事务。
 *
 * <p>契约（P0.3 §3）：先合批后分号、一事件一 seq、回滚不占号、终态与
 * {@code run.terminal} 同事务封口；outbox 只引用已持久化 eventId。
 */
@Service
public class RunEventAppender {

    /** Worker 执行推进使用；fence 不匹配时拒绝旧 Worker 的任何提交。 */
    public static final String EVENT_STATUS = "run.status";
    public static final String EVENT_ACCEPTED = "run.accepted";
    public static final String EVENT_STEP_STARTED = "run.step_started";
    public static final String EVENT_STEP_COMPLETED = "run.step_completed";
    public static final String EVENT_OUTPUT_DELTA = "run.output_delta";
    public static final String EVENT_APPROVAL_REQUIRED = "run.approval_required";
    public static final String EVENT_APPROVAL_RESOLVED = "run.approval_resolved";
    public static final String EVENT_USAGE = "run.usage";
    public static final String EVENT_RECONCILIATION_REQUIRED = "run.reconciliation_required";
    public static final String EVENT_ERROR = "run.error";
    public static final String EVENT_TERMINAL = "run.terminal";

    private final RunLedgerDao dao;
    private final ObjectMapper objectMapper;

    public RunEventAppender(RunLedgerDao dao, ObjectMapper objectMapper) {
        this.dao = dao;
        this.objectMapper = objectMapper;
    }

    public static String newEventId() {
        return "e-" + UUID.randomUUID().toString().replace("-", "");
    }

    public static String newRunId() {
        return "r-" + UUID.randomUUID().toString().replace("-", "");
    }

    /** 受理事务内使用：run 行已在本事务写入，不重复锁。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendWithinAdmission(String tenantId, String runId, String type, Object payload, boolean notify) {
        long seq = dao.allocateSeq(tenantId, runId);
        String eventId = newEventId();
        String payloadJson = toJson(payload);
        dao.insertEvent(tenantId, runId, seq, eventId, type, payloadJson);
        if (notify) {
            dao.insertOutbox(tenantId, eventId, runId, type, seq, payloadJson);
        }
    }

    /** 独立事务追加（状态事件/步骤事件等）。 */
    @Transactional(rollbackFor = Exception.class)
    public long append(String tenantId, String runId, String type, Object payload) {
        RunRecord run = dao.lockRun(tenantId, runId)
                .orElseThrow(() -> new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        if (RunStatus.isTerminal(run.status())) {
            throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT, "run already terminal");
        }
        long seq = dao.allocateSeq(tenantId, runId);
        String eventId = newEventId();
        String payloadJson = toJson(payload);
        dao.insertEvent(tenantId, runId, seq, eventId, type, payloadJson);
        dao.insertOutbox(tenantId, eventId, runId, type, seq, payloadJson);
        return seq;
    }

    /** Worker 路径：fence 校验通过才追加；否则 VERSION_CONFLICT。 */
    @Transactional(rollbackFor = Exception.class)
    public long appendFenced(String tenantId, String runId, String workerId, long fence,
                             String type, Object payload) {
        RunRecord run = dao.lockRun(tenantId, runId)
                .orElseThrow(() -> new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        if (run.fence() != fence || !workerId.equals(run.leaseOwner())) {
            throw new RunApiException(RunErrorCode.VERSION_CONFLICT, "worker fence is stale");
        }
        if (RunStatus.isTerminal(run.status())) {
            throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT, "run already terminal");
        }
        long seq = dao.allocateSeq(tenantId, runId);
        String eventId = newEventId();
        String payloadJson = toJson(payload);
        dao.insertEvent(tenantId, runId, seq, eventId, type, payloadJson);
        dao.insertOutbox(tenantId, eventId, runId, type, seq, payloadJson);
        return seq;
    }

    /** 状态变更事件（cancel/resume/recovering 等），非终态。 */
    @Transactional(rollbackFor = Exception.class)
    public void appendStatusEvent(String tenantId, String runId, String status, String detail) {
        RunRecord run = dao.lockRun(tenantId, runId)
                .orElseThrow(() -> new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put("attempt", run.attempt());
        payload.put("fence", run.fence());
        if (detail != null) {
            payload.put("detail", detail);
        }
        appendWithinLocked(tenantId, runId, EVENT_STATUS, payload);
    }

    /** 终态封口：状态 + terminal_result + run.terminal + outbox 同事务。 */
    @Transactional(rollbackFor = Exception.class)
    public long terminal(String tenantId, String runId, String workerId, long fence, String status,
                         Object terminalResult, String errorCode) {
        RunRecord run = dao.lockRun(tenantId, runId)
                .orElseThrow(() -> new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        if (RunStatus.isTerminal(run.status())) {
            throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT, "run already terminal");
        }
        if (workerId != null && (run.fence() != fence || !workerId.equals(run.leaseOwner()))) {
            throw new RunApiException(RunErrorCode.VERSION_CONFLICT, "worker fence is stale");
        }
        String resultJson = toJson(terminalResult == null ? Map.of() : terminalResult);
        long seq = dao.allocateSeq(tenantId, runId);
        String eventId = newEventId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put("resultRef", resultJson);
        payload.put("attempt", run.attempt());
        payload.put("fence", run.fence());
        if (errorCode != null) {
            payload.put("errorCode", errorCode);
        }
        String payloadJson = toJson(payload);
        dao.insertEvent(tenantId, runId, seq, eventId, EVENT_TERMINAL, payloadJson);
        dao.insertOutbox(tenantId, eventId, runId, EVENT_TERMINAL, seq, payloadJson);
        boolean updated = workerId == null
                ? dao.updateTerminalUnfenced(tenantId, runId, status, resultJson, errorCode)
                : dao.updateTerminalFenced(tenantId, runId, workerId, fence, status, resultJson, errorCode);
        if (!updated) {
            throw new RunApiException(RunErrorCode.VERSION_CONFLICT, "terminal transition rejected");
        }
        return seq;
    }

    @Transactional(rollbackFor = Exception.class)
    public long terminalUnfenced(String tenantId, String runId, String status, Object terminalResult, String errorCode) {
        return terminal(tenantId, runId, null, 0L, status, terminalResult, errorCode);
    }

    /** 已持有 run 行锁时追加（内部使用）。 */
    private void appendWithinLocked(String tenantId, String runId, String type, Object payload) {
        long seq = dao.allocateSeq(tenantId, runId);
        String eventId = newEventId();
        String payloadJson = toJson(payload);
        dao.insertEvent(tenantId, runId, seq, eventId, type, payloadJson);
        dao.insertOutbox(tenantId, eventId, runId, type, seq, payloadJson);
    }

    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RunApiException(RunErrorCode.INTERNAL_ERROR, "event payload serialization failed");
        }
    }

    public Optional<RunRecord> findRun(String tenantId, String runId) {
        return dao.findRun(tenantId, runId);
    }
}
