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

package com.nageoffer.ai.ragent.runtime.exec;

import com.nageoffer.ai.ragent.runtime.P2RuntimeProperties;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import com.nageoffer.ai.ragent.runtime.model.RunStatus;

import java.util.Map;

/**
 * 单次执行的 fence 受控视图：executor 只能通过本对象提交事件/检查点/终态。
 *
 * <p>所有提交都携带 (workerId, fence)；旧 Worker 复活后的任何提交被拒绝
 * （VERSION_CONFLICT），不出现旁路状态更新。
 */
public class RunExecutionGuard {

    private final RunLedgerDao dao;
    private final com.nageoffer.ai.ragent.runtime.RunEventAppender events;
    private final P2RuntimeProperties properties;
    private final String tenantId;
    private final String runId;
    private final int attempt;
    private final long fence;
    private final String workerId;

    public RunExecutionGuard(RunLedgerDao dao, com.nageoffer.ai.ragent.runtime.RunEventAppender events,
                             P2RuntimeProperties properties, String tenantId, String runId,
                             int attempt, long fence, String workerId) {
        this.dao = dao;
        this.events = events;
        this.properties = properties;
        this.tenantId = tenantId;
        this.runId = runId;
        this.attempt = attempt;
        this.fence = fence;
        this.workerId = workerId;
    }

    public String tenantId() {
        return tenantId;
    }

    public String runId() {
        return runId;
    }

    public int attempt() {
        return attempt;
    }

    public long fence() {
        return fence;
    }

    public String workerId() {
        return workerId;
    }

    public P2RuntimeProperties properties() {
        return properties;
    }

    public long appendEvent(String type, Object payload) {
        return events.appendFenced(tenantId, runId, workerId, fence, type, payload);
    }

    /** 幂等检查点提交；重复提交返回既有行（已完成步骤不重复执行/计量）。 */
    public RunLedgerDao.StepRow commitStep(String stepId, String stepName, String refJson, String refHash,
                                           String usageJson) {
        return dao.commitStep(tenantId, runId, stepId, attempt, stepName, refJson, refHash, usageJson, fence);
    }

    public java.util.Optional<RunLedgerDao.StepRow> findStep(String stepId) {
        return dao.findStep(tenantId, runId, stepId, attempt);
    }

    /** 取消信号（持久状态；浏览器断开不改变它）。 */
    public boolean isCancelRequested() {
        return dao.findRun(tenantId, runId)
                .map(run -> RunStatus.CANCEL_REQUESTED.equals(run.status()) || RunStatus.CANCELLED.equals(run.status()))
                .orElse(true);
    }

    /** 是否仍持有租约与 fence。 */
    public boolean stillOwned() {
        return dao.findRun(tenantId, runId)
                .map(run -> run.fence() == fence && workerId.equals(run.leaseOwner())
                        && !RunStatus.isTerminal(run.status()))
                .orElse(false);
    }

    public RunRecord currentRun() {
        return dao.findRun(tenantId, runId)
                .orElseThrow(() -> new com.nageoffer.ai.ragent.runtime.RunApiException(
                        com.nageoffer.ai.ragent.runtime.RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
    }

    public void terminal(String status, Object terminalResult, String errorCode) {
        events.terminal(tenantId, runId, workerId, fence, status, terminalResult, errorCode);
    }

    public Map<String, Object> ref(String type, String id, String hash) {
        Map<String, Object> ref = new java.util.LinkedHashMap<>();
        ref.put("type", type);
        ref.put("id", id);
        if (hash != null) {
            ref.put("hash", hash);
        }
        return ref;
    }
}
