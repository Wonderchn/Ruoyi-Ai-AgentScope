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

import com.nageoffer.ai.ragent.runtime.P2FaultInjector;
import com.nageoffer.ai.ragent.runtime.RunEventAppender;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 明确假执行器（仅 {@code p2.executor.synthetic.enabled=true} 的专属测试模式）。
 *
 * <p>用于 U01–U05 与故障契约验收（认领/接管/fence/取消/检查点/终止）。
 * 其结果不得当作真实 RAG/MinerU/模型签收。
 */
@Component
@ConditionalOnProperty(name = "p2.executor.mode", havingValue = "synthetic")
public class SyntheticRunExecutor implements RunExecutor {

    /** 合成模式对所有 action 生效（显式替代，不与真实执行器混用）。 */
    public static final String ACTION = "*";

    private final ObjectProvider<P2FaultInjector> faultInjector;

    public SyntheticRunExecutor(ObjectProvider<P2FaultInjector> faultInjector) {
        this.faultInjector = faultInjector;
    }

    @Override
    public String action() {
        return ACTION;
    }

    @Override
    public Outcome execute(RunExecution execution) throws Exception {
        RunExecutionGuard guard = execution.guard();
        P2FaultInjector faults = faultInjector.getIfAvailable();
        String[] steps = {"synthetic.parse", "synthetic.chunk", "synthetic.embed", "synthetic.publish"};
        for (int i = 0; i < steps.length; i++) {
            if (guard.isCancelRequested()) {
                return new Outcome("CANCELLED", Map.of("cancelledAt", steps[i]), null);
            }
            String stepId = steps[i];
            RunLedgerDao.StepRow existing = guard.findStep(stepId).orElse(null);
            if (existing != null && "COMPLETED".equals(existing.state())) {
                // 已完成步骤复用产物，不重复执行
                continue;
            }
            guard.appendEvent(RunEventAppender.EVENT_STEP_STARTED, Map.of(
                    "stepId", stepId, "stepName", stepId, "attemptId", "a-" + guard.attempt()));
            sleep(guard);
            if (faults != null) {
                faults.checkpoint(P2FaultInjector.WORKER_BEFORE_STEP_COMMIT);
            }
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("type", "synthetic-artifact");
            ref.put("id", stepId + "-" + guard.runId());
            ref.put("hash", com.nageoffer.ai.ragent.runtime.CanonicalJson.sha256(stepId + guard.runId()));
            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("syntheticUnits", 1);
            guard.commitStep(stepId, stepId, toJson(ref), (String) ref.get("hash"), toJson(usage));
            if (faults != null) {
                faults.checkpoint(P2FaultInjector.WORKER_AFTER_STEP_COMMIT);
                faults.checkpoint(P2FaultInjector.WORKER_ABANDON_RUN);
            }
            guard.appendEvent(RunEventAppender.EVENT_STEP_COMPLETED, Map.of(
                    "stepId", stepId, "state", "COMPLETED", "ref", ref, "usage", usage,
                    "attemptId", "a-" + guard.attempt()));
        }
        if (guard.isCancelRequested()) {
            return new Outcome("CANCELLED", Map.of(), null);
        }
        if (faults != null) {
            faults.checkpoint(P2FaultInjector.WORKER_BEFORE_TERMINAL);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "synthetic");
        result.put("steps", steps.length);
        result.put("note", "explicit test double; not a real RAG/model acceptance");
        return Outcome.succeeded(result);
    }

    private void sleep(RunExecutionGuard guard) throws InterruptedException {
        long delay = guard.properties().getExecutor().getSyntheticStepDelayMs();
        if (delay > 0) {
            Thread.sleep(delay);
        }
    }

    private String toJson(Object value) {
        return com.nageoffer.ai.ragent.runtime.CanonicalJson.strictMapper().valueToTree(value).toString();
    }
}
