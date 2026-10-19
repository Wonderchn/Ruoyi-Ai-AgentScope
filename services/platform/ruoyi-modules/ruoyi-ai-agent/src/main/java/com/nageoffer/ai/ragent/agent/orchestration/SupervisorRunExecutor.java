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

package com.nageoffer.ai.ragent.agent.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.nageoffer.ai.ragent.agent.runtime.P3Properties;
import com.nageoffer.ai.ragent.runtime.CanonicalJson;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.RunEventAppender;
import com.nageoffer.ai.ragent.runtime.config.RunConfigBinding;
import com.nageoffer.ai.ragent.runtime.exec.RunDeadline;
import com.nageoffer.ai.ragent.runtime.exec.RunExecution;
import com.nageoffer.ai.ragent.runtime.exec.RunExecutionGuard;
import com.nageoffer.ai.ragent.runtime.exec.RunExecutor;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F14:0/1/3 的 Supervisor 执行器：**显式委派环**（D1=A：父决策 → 子执行 → 父收敛）。
 *
 * <p>不引入第二编排模式、不使用对等广播/并行图；模型调用与绑定由
 * {@link SupervisorModelCall} 统一完成，本类只决定"谁、什么时候、以什么 step_id 写账"。
 *
 * <p><b>账本形状（§2.2，同一父 run）。</b>
 * <ul>
 *   <li>{@code route:<n>} / {@code supervisor.route}：父路由 n（1..4）；</li>
 *   <li>{@code child:<alias>:<n>} / {@code supervisor.child}：第 n 次委派的子回答；</li>
 *   <li>{@code supervisor-final} / {@code supervisor.final}：父最终收敛。</li>
 * </ul>
 * route 与 child 两行在子回答返回后**同一 fence 事务**提交：既保证"route:1→child"的
 * 顺序可见，也避免"route 已提交而 child 未提交"时丢失模型生成的 task（route ref 按
 * §2.2 只保存 taskHash）。所有 step/event 只经 {@link RunExecutionGuard}。
 *
 * <p><b>失败（D6）。</b>子失败 fail-fast，不写 FAILED step 行（{@code commitStep} 只写
 * COMPLETED），只写 {@code run.error {stepId,agent,errorCode}}；不自动重试，重试走新 run。
 */
public class SupervisorRunExecutor implements RunExecutor {

    private final SupervisorLedger ledger;
    private final SupervisorModelCall modelCall;
    private final P3Properties properties;

    public SupervisorRunExecutor(SupervisorLedger ledger, SupervisorModelCall modelCall, P3Properties properties) {
        this.ledger = ledger;
        this.modelCall = modelCall;
        this.properties = properties;
    }

    @Override
    public String action() {
        return SupervisorProtocol.ACTION;
    }

    @Override
    public Outcome execute(RunExecution execution) {
        var run = execution.run();
        var guard = execution.guard();
        // ① 执行契约版本先于任何配置权威/DB 读取判定（旧 checkpoint 不得变成另一种错误码）。
        if (!SupervisorProtocol.VERSION.equals(run.executionVersion())) {
            return Outcome.failed("AGENT_CHECKPOINT_INCOMPATIBLE");
        }
        try {
            var input = SupervisorProtocol.input(run.inputJson());
            if (wallExceeded(run)) {
                return Outcome.failed("BUDGET_EXCEEDED");
            }
            // ② 受理时固定的发布绑定；父/子共用同一模型（C1.2）。
            RunConfigBinding binding = modelCall.bound(execution);
            String model = modelCall.model(binding);
            if (!SupervisorLedger.isCompatible(ledger.binding(run), model)) {
                return Outcome.failed("AGENT_CHECKPOINT_INCOMPATIBLE");
            }
            // ③ 已完成终态 step 复用 ref，不重呼任何模型（R12）。
            var completed = guard.findStep(SupervisorProtocol.FINAL_STEP);
            if (completed.isPresent()) {
                return Outcome.succeeded(toMap(completed.get().refJson()));
            }
            boolean synthetic = properties.isSyntheticModel();
            List<Map<String, Object>> delegations = new ArrayList<>();
            List<String[]> transcript = new ArrayList<>();

            // ④ 显式委派环：最多 4 次委派；父在任一轮可直接 final。
            for (int n = 1; n <= SupervisorProtocol.MAX_DELEGATIONS; n++) {
                String routeStep = SupervisorProtocol.ROUTE_STEP_PREFIX + n;
                var route = guard.findStep(routeStep);
                if (route.isPresent()) {
                    // 已完成委派：route 与 child 是同一事务写下的，直接复用 ref。
                    var routeRef = parse(route.get().refJson());
                    String alias = routeRef.path("agent").asText();
                    String childStep = SupervisorProtocol.CHILD_STEP_PREFIX + alias + ":" + n;
                    var child = guard.findStep(childStep).orElseThrow(() -> new RunApiException(
                            RunErrorCode.RUN_STATE_CONFLICT, "supervisor checkpoint is incomplete"));
                    var childRef = parse(child.refJson());
                    String answer = childRef.path("answer").asText();
                    transcript.add(new String[]{alias, answer});
                    delegations.add(SupervisorProtocol.delegation(alias, childStep,
                            childRef.path("callId").asText()));
                    continue;
                }
                String parentPrompt = SupervisorProtocol.parentUserPrompt(input.text(), transcript);
                SupervisorModelCall.RawReply parent;
                try {
                    parent = modelCall.call(execution, routeStep,
                            SupervisorProtocol.parentSystemPrompt(input.roster()), parentPrompt,
                            SupervisorProtocol.MAX_PARENT_ANSWER, SupervisorProtocol.INVALID_ROUTE,
                            () -> syntheticParent(input, transcript));
                } catch (RuntimeException failure) {
                    // F-1：非 stop/协议违例与模型调用异常在调用点收敛为稳定码 + run.error，
                    // 不让它逃逸成 RunWorker 的 EXECUTION_FAILED（VERSION_CONFLICT 仍上抛）。
                    return callFailure(guard, routeStep, null, failure);
                }
                SupervisorProtocol.ParentDecision decision;
                try {
                    decision = SupervisorProtocol.parseParentReply(parent.content(), input.roster());
                } catch (SupervisorProtocol.Violation violation) {
                    error(guard, routeStep, null, violation.errorCode());
                    return Outcome.failed(violation.errorCode());
                }
                if ("delegate".equals(decision.kind())) {
                    if (delegations.size() >= SupervisorProtocol.MAX_DELEGATIONS) {
                        error(guard, routeStep, decision.agent(), SupervisorProtocol.DELEGATION_LIMIT);
                        return Outcome.failed(SupervisorProtocol.DELEGATION_LIMIT);
                    }
                    String childStep = SupervisorProtocol.CHILD_STEP_PREFIX + decision.agent() + ":" + n;
                    SupervisorModelCall.RawReply child;
                    try {
                        child = modelCall.call(execution, childStep,
                                SupervisorProtocol.childSystemPrompt(input.roster().agent(decision.agent())),
                                decision.task(), SupervisorProtocol.MAX_CHILD_ANSWER,
                                SupervisorProtocol.CHILD_INVALID,
                                () -> syntheticChild(decision.agent(), decision.task()));
                    } catch (RuntimeException failure) {
                        return callFailure(guard, childStep, decision.agent(), failure);
                    }
                    SupervisorProtocol.ChildAnswer answer;
                    try {
                        answer = SupervisorProtocol.parseChildReply(child.content());
                    } catch (SupervisorProtocol.Violation violation) {
                        // D6：子失败不写 step 行，只写 run.error（stepId/agent/errorCode）。
                        error(guard, childStep, decision.agent(), violation.errorCode());
                        return Outcome.failed(violation.errorCode());
                    }
                    var routeRef = SupervisorProtocol.routeRef(decision.agent(), decision.task(),
                            decision.reason(), parent.callId(), parent.model());
                    var childRef = SupervisorProtocol.childRef(answer.answer(), child.callId(),
                            child.provider(), child.model());
                    guard.appendEvent(RunEventAppender.EVENT_STEP_STARTED, Map.of("stepId", routeStep));
                    guard.appendEvent(RunEventAppender.EVENT_STEP_STARTED, Map.of("stepId", childStep));
                    guard.commitAtomic(() -> {
                        guard.commitStep(routeStep, "supervisor.route", json(routeRef),
                                SupervisorProtocol.hash(decision.task()), "{}");
                        guard.commitStep(childStep, "supervisor.child", json(childRef),
                                SupervisorProtocol.hash(answer.answer()), child.usageJson());
                        return null;
                    });
                    guard.appendEvent(RunEventAppender.EVENT_STEP_COMPLETED, Map.of("stepId", routeStep));
                    guard.appendEvent(RunEventAppender.EVENT_STEP_COMPLETED, Map.of("stepId", childStep));
                    delegations.add(SupervisorProtocol.delegation(decision.agent(), childStep, child.callId()));
                    transcript.add(new String[]{decision.agent(), answer.answer()});
                    if (SupervisorProtocol.transcriptLength(input.text(), transcript)
                            > SupervisorProtocol.MAX_TRANSCRIPT) {
                        return Outcome.failed("BUDGET_EXCEEDED");
                    }
                } else {
                    return finish(guard, decision.answer(), delegations, synthetic);
                }
            }

            // ⑤ 委派次数用尽：父必须收敛；再要委派即 DELEGATION_LIMIT。
            if (guard.isCancelRequested()) {
                return new Outcome("CANCELLED", Map.of(), null);
            }
            String finalPrompt = SupervisorProtocol.parentUserPrompt(input.text(), transcript);
            SupervisorModelCall.RawReply parent;
            try {
                parent = modelCall.call(execution, SupervisorProtocol.FINAL_STEP,
                        SupervisorProtocol.parentSystemPrompt(input.roster()), finalPrompt,
                        SupervisorProtocol.MAX_PARENT_ANSWER, SupervisorProtocol.INVALID_ROUTE,
                        () -> syntheticFinal(input, transcript));
            } catch (RuntimeException failure) {
                return callFailure(guard, SupervisorProtocol.FINAL_STEP, null, failure);
            }
            SupervisorProtocol.ParentDecision decision;
            try {
                decision = SupervisorProtocol.parseParentReply(parent.content(), input.roster());
            } catch (SupervisorProtocol.Violation violation) {
                error(guard, SupervisorProtocol.FINAL_STEP, null, violation.errorCode());
                return Outcome.failed(violation.errorCode());
            }
            if ("delegate".equals(decision.kind())) {
                error(guard, SupervisorProtocol.FINAL_STEP, decision.agent(),
                        SupervisorProtocol.DELEGATION_LIMIT);
                return Outcome.failed(SupervisorProtocol.DELEGATION_LIMIT);
            }
            return finish(guard, decision.answer(), delegations, synthetic);
        } catch (SupervisorProtocol.Suspension suspension) {
            return new Outcome(suspension.status(), Map.of(),
                    "NEEDS_RECONCILIATION".equals(suspension.status()) ? "EXTERNAL_OUTCOME_UNKNOWN" : null);
        }
    }

    // ------------------------------------------------------------ 终态

    private static Outcome finish(RunExecutionGuard guard, String answer,
                                  List<Map<String, Object>> delegations, boolean synthetic) {
        var ref = SupervisorProtocol.finalRef(answer, delegations, synthetic);
        guard.appendEvent(RunEventAppender.EVENT_STEP_STARTED, Map.of("stepId", SupervisorProtocol.FINAL_STEP));
        guard.commitAtomic(() -> {
            guard.commitStep(SupervisorProtocol.FINAL_STEP, "supervisor.final", json(ref),
                    SupervisorProtocol.hash(answer), "{}");
            return null;
        });
        guard.appendEvent(RunEventAppender.EVENT_STEP_COMPLETED, Map.of("stepId", SupervisorProtocol.FINAL_STEP));
        // F-2：fresh final 与复用 final 共用同一形状（finalRef 已含 engineVersion/supervisorVersion）。
        return Outcome.succeeded(ref);
    }

    private static void error(RunExecutionGuard guard, String stepId, String agent, String errorCode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stepId", stepId);
        if (agent != null) {
            payload.put("agent", agent);
        }
        payload.put("errorCode", errorCode);
        guard.appendEvent(RunEventAppender.EVENT_ERROR, payload);
    }

    /**
     * 模型调用失败的统一收敛（F-1）。
     *
     * <p>协议 {@link SupervisorProtocol.Violation}（非 stop、非法 JSON、子工具字段等）与
     * {@link RunApiException}（预算/外发/依赖等）都在**调用点**写
     * {@code run.error {stepId,agent,errorCode}} 并返回稳定的 FAILED 终态；否则它们会逃逸到
     * {@code RunWorker} 的统一 catch，被记成 {@code EXECUTION_FAILED}，丢失稳定码且没有
     * 可归因事件。
     *
     * <p>两个例外：{@link RunErrorCode#VERSION_CONFLICT}（失 fence）必须原样上抛（R14，
     * 旧 Worker 不得写终态）；{@link SupervisorProtocol.Suspension} 不是失败，交由外层映射
     * 为 CANCELLED/NEEDS_RECONCILIATION。
     */
    private static Outcome callFailure(RunExecutionGuard guard, String stepId, String agent,
                                       RuntimeException failure) {
        if (failure instanceof SupervisorProtocol.Violation violation) {
            error(guard, stepId, agent, violation.errorCode());
            return Outcome.failed(violation.errorCode());
        }
        if (failure instanceof RunApiException api) {
            if (api.errorCode() == RunErrorCode.VERSION_CONFLICT) {
                throw api;
            }
            error(guard, stepId, agent, api.errorCode().name());
            return Outcome.failed(api.errorCode().name());
        }
        throw failure;
    }

    // ------------------------------------------------------------ 预算

    private static boolean wallExceeded(RunRecord run) {
        int wall = 300;
        try {
            var budget = CanonicalJson.strictMapper().readTree(run.budgetJson() == null ? "{}" : run.budgetJson());
            if (budget.hasNonNull("maxWallClockSeconds")) {
                wall = budget.path("maxWallClockSeconds").asInt(300);
            }
        } catch (Exception e) {
            wall = 1;
        }
        return RunDeadline.remainingSeconds(run.startedAt(), Instant.now(), wall) == 0;
    }

    // ------------------------------------------------------------ 合成夹具（D10，不作真实证据）

    private static String syntheticParent(SupervisorProtocol.Input input, List<String[]> transcript) {
        Set<String> used = new HashSet<>();
        for (String[] entry : transcript) {
            used.add(entry[0]);
        }
        for (SupervisorProtocol.Agent agent : input.roster().agents()) {
            if (!used.contains(agent.name())) {
                return json(Map.of("kind", "delegate", "agent", agent.name(),
                        "task", truncate(input.text(), SupervisorProtocol.MAX_TASK),
                        "reason", "synthetic deterministic fixture"));
            }
        }
        return syntheticFinal(input, transcript);
    }

    private static String syntheticFinal(SupervisorProtocol.Input input, List<String[]> transcript) {
        StringBuilder answer = new StringBuilder("Synthetic supervisor:");
        for (String[] entry : transcript) {
            answer.append(" [").append(entry[0]).append("] ").append(entry[1]);
        }
        if (transcript.isEmpty()) {
            answer.append(" no delegation");
        }
        return json(Map.of("kind", "final",
                "answer", truncate(answer.toString(), SupervisorProtocol.MAX_PARENT_ANSWER)));
    }

    private static String syntheticChild(String alias, String task) {
        String answer = "[synthetic-child " + alias + "] " + truncate(task, 512);
        return json(Map.of("kind", "final",
                "answer", truncate(answer, SupervisorProtocol.MAX_CHILD_ANSWER)));
    }

    // ------------------------------------------------------------ helpers

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String json(Object value) {
        try {
            return CanonicalJson.strictMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new RunApiException(RunErrorCode.INTERNAL_ERROR, "supervisor step serialization failed");
        }
    }

    private static JsonNode parse(String json) {
        try {
            return CanonicalJson.strictMapper().readTree(json == null ? "{}" : json);
        } catch (Exception e) {
            throw new RunApiException(RunErrorCode.INTERNAL_ERROR, "supervisor step ref is invalid");
        }
    }

    private static Map<String, Object> toMap(String json) {
        return CanonicalJson.strictMapper().convertValue(parse(json),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
    }
}
