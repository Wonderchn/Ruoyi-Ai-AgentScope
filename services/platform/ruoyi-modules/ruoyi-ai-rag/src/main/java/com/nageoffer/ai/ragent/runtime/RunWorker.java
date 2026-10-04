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

import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.exec.RunExecution;
import com.nageoffer.ai.ragent.runtime.exec.RunExecutionGuard;
import com.nageoffer.ai.ragent.runtime.exec.RunExecutor;
import com.nageoffer.ai.ragent.runtime.exec.RunExecutorRegistry;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import com.nageoffer.ai.ragent.runtime.model.RunStatus;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 正式 Worker：认领/续租/接管、fence 校验、取消/恢复、终态封口。
 *
 * <p>契约要点：短事务认领（attempt+1/fence+1）；心跳续租校验 owner+fence；
 * 租约过期扫描只把 RUNNING 归入 RECOVERING（不代表旧 Worker 的外部 IO 已停止，
 * 也不释放 P1 ACTIVE permit）；所有状态/步骤/事件提交受 fence 约束。
 */
@Component
@ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
@ConditionalOnProperty(name = "p2.worker.enabled", havingValue = "true")
public class RunWorker {

    private static final Logger log = LoggerFactory.getLogger(RunWorker.class);

    private final RunLedgerDao dao;
    private final RunEventAppender events;
    private final RunExecutorRegistry executors;
    private final P2RuntimeProperties properties;
    private final ObjectProvider<P2FaultInjector> faultInjector;
    private final String workerId = "worker-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final ExecutorService pool;
    private final java.util.concurrent.Semaphore capacity;
    private final ScheduledExecutorService heartbeats = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "p2-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public RunWorker(RunLedgerDao dao, RunEventAppender events, RunExecutorRegistry executors,
                     P2RuntimeProperties properties, ObjectProvider<P2FaultInjector> faultInjector) {
        this.dao = dao;
        this.events = events;
        this.executors = executors;
        this.properties = properties;
        this.faultInjector = faultInjector;
        this.capacity = new java.util.concurrent.Semaphore(Math.max(1, properties.getWorker().getBatch()));
        this.pool = Executors.newFixedThreadPool(Math.max(1, properties.getWorker().getBatch()), runnable -> {
            Thread thread = new Thread(runnable, "p2-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    public String workerId() {
        return workerId;
    }

    @Scheduled(fixedDelayString = "${p2.worker.poll-interval-ms:500}")
    public void tick() {
        try {
            dao.sweepExpiredLeases();
            finalizeCancelledRuns();
            for (int i = 0; i < properties.getWorker().getBatch(); i++) {
                if (!capacity.tryAcquire()) { return; }
                boolean dispatched=false;
                try {
                    Optional<RunLedgerDao.ClaimedRun> claimed = dao.claimNext(workerId,
                            properties.getWorker().getLeaseSeconds());
                    if (claimed.isEmpty()) { return; }
                    RunLedgerDao.ClaimedRun claim=claimed.get();
                    pool.submit(() -> runOne(claim));
                    dispatched=true;
                } finally { if(!dispatched) capacity.release(); }
            }
        } catch (RuntimeException e) {
            log.warn("worker tick failed: {}", e.getClass().getSimpleName());
        }
    }

    /** CANCEL_REQUESTED 且无活动租约的 run 封口为 CANCELLED（幂等）。 */
    private void finalizeCancelledRuns() {
        List<String[]> pending = dao.listCancelPending(8);
        for (String[] row : pending) {
            try {
                events.terminalUnfenced(row[0], row[1], RunStatus.CANCELLED,
                        Map.of("cancelled", true, "note", "cancel requested before/without execution"), null);
            } catch (RunApiException e) {
                // 已被别的节点封口：幂等忽略
            }
        }
    }

    private void runOne(RunLedgerDao.ClaimedRun claim) {
        RunRecord run = claim.run();
        RunExecutionGuard guard = new RunExecutionGuard(dao, events, properties,
                run.tenantId(), run.runId(), claim.newAttempt(), claim.newFence(), workerId);
        P2FaultInjector faults = faultInjector.getIfAvailable();
        ScheduledFuture<?> heartbeat = startHeartbeat(guard);
        int wall=Math.min(600,Math.max(1,properties.getWorker().getMaxWallClockSeconds()));
        try {
            var budget=CanonicalJson.strictMapper().readTree(run.budgetJson()==null?"{}":run.budgetJson());
            if(budget.hasNonNull("maxWallClockSeconds")) wall=Math.min(wall,budget.path("maxWallClockSeconds").asInt(wall));
        } catch(java.io.IOException ignored) {wall=1;}
        long remaining=com.nageoffer.ai.ragent.runtime.exec.RunDeadline.remainingSeconds(run.startedAt(),java.time.Instant.now(),wall);
        Thread executionThread=Thread.currentThread();
        var expired=new java.util.concurrent.atomic.AtomicBoolean();
        ScheduledFuture<?> deadline=heartbeats.schedule(()->{expired.set(true);executionThread.interrupt();},Math.max(1,remaining),TimeUnit.SECONDS);
        try {
            if(remaining==0) {safeFail(guard,"BUDGET_EXCEEDED");return;}
            if (faults != null) {
                faults.checkpoint(P2FaultInjector.WORKER_AFTER_CLAIM);
            }
            guard.appendEvent(RunEventAppender.EVENT_STATUS, Map.of(
                    "status", RunStatus.RUNNING, "attempt", claim.newAttempt(), "fence", claim.newFence(),
                    "worker", workerId));
            RunExecutor executor = executors.resolve(run.action()).orElse(null);
            if (executor == null) {
                guard.terminal(RunStatus.FAILED, Map.of(), "ACTION_NOT_ENABLED");
                return;
            }
            RunExecutor.Outcome outcome = executor.execute(new RunExecution(run, guard));
            if(expired.get()) {safeFail(guard,"BUDGET_EXCEEDED");return;}
            if (!guard.stillOwned()) {
                log.warn("run {} lost lease before terminal; skipping terminal write", run.runId());
                return;
            }
            if (java.util.Set.of(RunStatus.WAITING_APPROVAL,RunStatus.NEEDS_RECONCILIATION).contains(outcome.status())) {
                guard.suspend(outcome.status(),outcome.errorCode());
            } else if (RunStatus.CANCELLED.equals(outcome.status())) {
                guard.terminal(RunStatus.CANCELLED, outcome.terminalResult(), null);
            } else if (RunStatus.FAILED.equals(outcome.status())) {
                guard.terminal(RunStatus.FAILED, outcome.terminalResult(), outcome.errorCode());
            } else {
                guard.terminal(RunStatus.SUCCEEDED, outcome.terminalResult(), null);
            }
        } catch (P2FaultInjector.InjectedFault injected) {
            // 故障注入：模拟进程在窗口内退出——不写终态，租约到期后被接管（分类为 RECOVERING）
            log.warn("run {} abandoned by injected fault {}", run.runId(), injected.getMessage());
        } catch (RunApiException e) {
            if (e.errorCode() == RunErrorCode.VERSION_CONFLICT) {
                log.warn("run {} rejected by fence; another worker owns it", run.runId());
                return;
            }
            safeFail(guard, expired.get()?"BUDGET_EXCEEDED":e.errorCode().name());
        } catch (Exception e) {
            log.warn("run {} failed: {}", run.runId(), e.getClass().getSimpleName());
            safeFail(guard, expired.get()?"BUDGET_EXCEEDED":"EXECUTION_FAILED");
        } finally {
            capacity.release();
            heartbeat.cancel(false);
            deadline.cancel(false);
            Thread.interrupted();
            try {
                if (guard.stillOwned()) {
                    dao.releaseLease(run.tenantId(), run.runId(), workerId, claim.newFence());
                }
            } catch (RuntimeException ignored) {
                // 释放失败由租约过期扫描兜底
            }
        }
    }

    private void safeFail(RunExecutionGuard guard, String errorCode) {
        try {
            if (guard.stillOwned()) {
                guard.terminal(RunStatus.FAILED, Map.of(), errorCode);
            }
        } catch (RuntimeException e) {
            log.warn("terminal write failed for run {}: {}", guard.runId(), e.getClass().getSimpleName());
        }
    }

    private ScheduledFuture<?> startHeartbeat(RunExecutionGuard guard) {
        long period = Math.max(1, properties.getWorker().getHeartbeatSeconds());
        return heartbeats.scheduleAtFixedRate(() -> {
            try {
                boolean renewed = dao.renewLease(guard.tenantId(), guard.runId(), workerId, guard.fence(),
                        properties.getWorker().getLeaseSeconds());
                if (!renewed) {
                    log.warn("heartbeat lost for run {}", guard.runId());
                }
            } catch (RuntimeException e) {
                log.warn("heartbeat error for run {}: {}", guard.runId(), e.getClass().getSimpleName());
            }
        }, period, period, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
        heartbeats.shutdownNow();
    }

    /** 测试/诊断：当前 worker 标识。 */
    public Map<String, Object> describe() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("workerId", workerId);
        info.put("syntheticMode", executors.isSyntheticMode());
        info.put("leaseSeconds", properties.getWorker().getLeaseSeconds());
        return info;
    }
}
