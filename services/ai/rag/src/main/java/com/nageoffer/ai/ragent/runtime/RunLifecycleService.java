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

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import com.nageoffer.ai.ragent.runtime.model.RunStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行生命周期：取消 / 恢复 / 快照（当前授权由调用方在控制器层完成）。
 */
@Service
public class RunLifecycleService {

    private final RunLedgerDao dao;
    private final RunEventAppender events;
    @org.springframework.beans.factory.annotation.Autowired
    private RunAccessService access;

    public RunLifecycleService(RunLedgerDao dao, RunEventAppender events) {
        this.dao = dao;
        this.events = events;
    }

    /** 是否为 P2 正式执行账本管理的 run（有幂等键/执行字段）。 */
    public boolean isManagedRun(String tenantId, String runId) {
        return dao.findRun(tenantId, runId).map(run -> run.idempotencyKey() != null).orElse(false);
    }

    public RunRecord get(ExecutionPrincipal principal, String runId) {
        RunRecord run=dao.findRun(principal.tenantId(), runId)
                .orElseThrow(() -> new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        if(run.idempotencyKey()!=null) access.visible(principal,run);
        return run;
    }

    /** 幂等取消：非终态 → CANCEL_REQUESTED；终态不改写。 */
    @Transactional(rollbackFor = Exception.class)
    public RunRecord cancel(ExecutionPrincipal principal, String runId, Long expectedVersion) {
        get(principal,runId);
        RunRecord run = dao.lockRun(principal.tenantId(), runId)
                .orElseThrow(() -> new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        if (RunStatus.isTerminal(run.status())) {
            return run;
        }
        if (expectedVersion != null && run.version() != expectedVersion) {
            throw new RunApiException(RunErrorCode.VERSION_CONFLICT, "expectedVersion does not match");
        }
        if (RunStatus.CANCEL_REQUESTED.equals(run.status())) {
            return run;
        }
        dao.requestCancel(principal.tenantId(), runId);
        events.appendStatusEvent(principal.tenantId(), runId, RunStatus.CANCEL_REQUESTED, "cancel requested");
        return reread(principal, runId);
    }

    /** 非终态白名单 + expectedVersion CAS；终端拒绝。 */
    @Transactional(rollbackFor = Exception.class)
    public RunRecord resume(ExecutionPrincipal principal, String runId, long expectedVersion) {
        get(principal,runId);
        RunRecord run = dao.lockRun(principal.tenantId(), runId)
                .orElseThrow(() -> new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        if (RunStatus.isTerminal(run.status())) {
            throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT, "terminal runs cannot be resumed");
        }
        if (!RunStatus.RESUMABLE.contains(run.status())) {
            throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT, "run status is not resumable");
        }
        if (run.version() != expectedVersion) {
            throw new RunApiException(RunErrorCode.VERSION_CONFLICT, "expectedVersion does not match");
        }
        if (!dao.resumeToQueued(principal.tenantId(), runId, expectedVersion)) {
            throw new RunApiException(RunErrorCode.VERSION_CONFLICT, "resume CAS rejected");
        }
        events.appendStatusEvent(principal.tenantId(), runId, RunStatus.QUEUED, "resumed");
        return reread(principal, runId);
    }

    private RunRecord reread(ExecutionPrincipal principal, String runId) {
        return dao.findRun(principal.tenantId(), runId)
                .orElseThrow(() -> new RunApiException(RunErrorCode.INTERNAL_ERROR, "run readback failed"));
    }

    public List<RunLedgerDao.StepRow> steps(ExecutionPrincipal principal, String runId) {
        get(principal,runId);
        return dao.listSteps(principal.tenantId(), runId);
    }

    public Map<String, Object> snapshot(ExecutionPrincipal principal, String runId) {
        RunRecord run = get(principal, runId);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("runId", run.runId());
        snapshot.put("action", run.action());
        snapshot.put("status", run.status());
        snapshot.put("attempt", run.attempt());
        snapshot.put("fence", run.fence());
        snapshot.put("version", run.version());
        snapshot.put("nextSeq", run.nextSeq());
        snapshot.put("createdAt", run.createdAt());
        snapshot.put("startedAt", run.startedAt());
        snapshot.put("finishedAt", run.finishedAt());
        snapshot.put("cancelRequestedAt", run.cancelRequestedAt());
        snapshot.put("errorCode", run.errorCode());
        snapshot.put("retryOf", run.retryOf());
        snapshot.put("terminalResult", RawJson.of(run.terminalResultJson()));
        List<Map<String, Object>> steps = new ArrayList<>();
        for (RunLedgerDao.StepRow step : dao.listSteps(principal.tenantId(), runId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stepId", step.stepId());
            item.put("stepName", step.stepName());
            item.put("state", step.state());
            item.put("attempt", step.attempt());
            item.put("checkpointVersion", step.checkpointVersion());
            item.put("ref", RawJson.of(step.refJson()));
            item.put("usage", RawJson.of(step.usageJson()));
            item.put("at", step.updatedAt());
            steps.add(item);
        }
        snapshot.put("steps", steps);
        List<String> allowed = new ArrayList<>();
        if (!RunStatus.isTerminal(run.status())) {
            allowed.add("cancel");
        }
        if (RunStatus.RESUMABLE.contains(run.status())) {
            allowed.add("resume");
        }
        snapshot.put("allowedActions", allowed);
        return snapshot;
    }

    /** 保持 JSON 原样（不二次转义）的包装，供 Jackson 以 RawValue 输出。 */
    public record RawJson(String json) implements com.fasterxml.jackson.databind.JsonSerializable {
        public static Object of(String json) {
            if(json==null || json.isBlank()) return null;
            try{return CanonicalJson.strictMapper().readValue(json,Object.class);}
            catch(java.io.IOException e){throw new RunApiException(RunErrorCode.INTERNAL_ERROR);}
        }

        @Override
        public void serialize(com.fasterxml.jackson.core.JsonGenerator gen,
                              com.fasterxml.jackson.databind.SerializerProvider serializers) throws java.io.IOException {
            if (json == null || json.isBlank()) {
                gen.writeNull();
            } else {
                gen.writeRawValue(json);
            }
        }

        @Override
        public void serializeWithType(com.fasterxml.jackson.core.JsonGenerator gen,
                                      com.fasterxml.jackson.databind.SerializerProvider serializers,
                                      com.fasterxml.jackson.databind.jsontype.TypeSerializer typeSer) throws java.io.IOException {
            serialize(gen, serializers);
        }
    }
}
