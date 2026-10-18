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

import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.dto.AdmissionRequest;
import com.nageoffer.ai.ragent.runtime.exec.RunExecutionGuard;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * F14:0/1/3 的统一账本绑定：**同一父 run 的 {@code ai_agent_run} 行**。
 *
 * <p><b>零第二账本。</b>{@link SupervisorRunContract#onAdmitted} 在受理事务里写唯一一行
 * {@code ai_agent_run}；执行期的 route/child/final 只作为 {@code ai_run_step} 行
 * （经 {@link RunExecutionGuard#commitStep}）落账。本类不建投影表、不写
 * {@code ai_tool_call}/{@code ai_agent_checkpoint}、不追加第二套 run。
 *
 * <p><b>为什么复制单 Agent 的 SQL 而不复用 {@code AgentLedger} 的兼容判据。</b>冻结类的
 * 兼容判据（{@code agent_version=core-v1}、{@code tool_catalog=p3-tools-v1}）对 Supervisor
 * 是错的；复用其 {@code isCompatible} 会把每一个 Supervisor run 判成旧检查点。这里保留
 * 同样的列形状与预算更新语句，只改冻结身份常量。
 */
public class SupervisorLedger {

    /** 绑定行的 agent_version（D15）。 */
    public static final String AGENT = SupervisorProtocol.VERSION;
    /** 绑定行的 engine_version（与单 Agent 先例同一冻结值）。 */
    public static final String ENGINE = SupervisorProtocol.ENGINE;
    /** 绑定行的 tool_catalog（子工具空集）。 */
    public static final String CATALOG = SupervisorProtocol.CATALOG;

    /** 预算默认（D11）：maxTokens 3000 / maxSteps 8 / maxToolCalls 1。 */
    public static final int DEFAULT_MAX_TOKENS = 3000;
    public static final int DEFAULT_MAX_STEPS = 8;
    public static final int DEFAULT_MAX_TOOL_CALLS = 1;

    private final JdbcTemplate jdbc;

    public SupervisorLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Binding(String member, String agent, String engine, String model, String catalog,
                          int maxSteps, int maxTools, int maxTokens, int tokensUsed, int checkpointVersion) {
    }

    /** 读受理时写下的唯一绑定行；不存在/不唯一/成员不符一律 404（不猜）。 */
    public Binding binding(RunRecord run) {
        List<Binding> rows = jdbc.query(
                "SELECT member_id,agent_version,engine_version,model,tool_catalog,max_steps,max_tool_calls,"
                        + "max_tokens,tokens_used,checkpoint_version FROM ai_agent_run WHERE tenant_id=? AND run_id=?",
                (rs, n) -> new Binding(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getInt(10)),
                run.tenantId(), run.runId());
        if (rows.size() != 1 || !rows.get(0).member().equals(run.memberId())) {
            throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        }
        return rows.get(0);
    }

    /**
     * 旧/异样检查点判定（纯函数）：五个条件必须同时成立。
     *
     * <p>与 {@code AgentLedger.isCompatible} 同构，只把 agent_version/catalog 换成
     * Supervisor 的冻结身份；{@code model} 为 null 一律判为不兼容。
     */
    public static boolean isCompatible(Binding binding, String model) {
        return binding != null && AGENT.equals(binding.agent()) && ENGINE.equals(binding.engine())
                && CATALOG.equals(binding.catalog()) && model != null && model.equals(binding.model())
                && binding.checkpointVersion() == 1;
    }

    /**
     * 受理事务内写**唯一**绑定行（恰 1 行 {@code ai_agent_run}）。
     *
     * <p>预算缺省取 D11 默认值；显式 budget 已在受理侧通过白名单/上限校验。
     * 本方法不写继承行：Supervisor 不支持 {@code inheritActionId}。
     */
    public void admitted(RunRecord run, AdmissionRequest request, String model) {
        var budget = request.budget();
        int steps = budget != null && budget.isObject() && budget.hasNonNull("maxSteps")
                ? budget.path("maxSteps").asInt(DEFAULT_MAX_STEPS) : DEFAULT_MAX_STEPS;
        int tools = budget != null && budget.isObject() && budget.hasNonNull("maxToolCalls")
                ? budget.path("maxToolCalls").asInt(DEFAULT_MAX_TOOL_CALLS) : DEFAULT_MAX_TOOL_CALLS;
        int tokens = budget != null && budget.isObject() && budget.hasNonNull("maxTokens")
                ? budget.path("maxTokens").asInt(DEFAULT_MAX_TOKENS) : DEFAULT_MAX_TOKENS;
        jdbc.update("INSERT INTO ai_agent_run(tenant_id,run_id,member_id,agent_version,engine_version,model,"
                        + "tool_catalog,max_steps,max_tool_calls,max_tokens,source_refs) VALUES(?,?,?,?,?,?,?,?,?,?,?::jsonb)",
                run.tenantId(), run.runId(), run.memberId(), AGENT, ENGINE, model, CATALOG,
                steps, tools, tokens, run.resourceRefsJson());
    }

    /** 每次模型调用前占一步；超出 max_steps → BUDGET_EXCEEDED（fence 受控）。 */
    public void spendStep(RunExecutionGuard guard) {
        guard.commitAtomic(() -> {
            if (jdbc.update("UPDATE ai_agent_run SET steps_used=steps_used+1 "
                            + "WHERE tenant_id=? AND run_id=? AND steps_used<max_steps",
                    guard.tenantId(), guard.runId()) != 1) {
                throw new RunApiException(RunErrorCode.BUDGET_EXCEEDED);
            }
            return null;
        });
    }

    /** 结算后累加 token；超 max_tokens → BUDGET_EXCEEDED。 */
    public void addTokens(RunExecutionGuard guard, int tokens) {
        if (tokens < 0) {
            throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT, "MODEL_USAGE_UNKNOWN");
        }
        if (jdbc.update("UPDATE ai_agent_run SET tokens_used=tokens_used+? "
                        + "WHERE tenant_id=? AND run_id=? AND tokens_used+?<=max_tokens",
                tokens, guard.tenantId(), guard.runId(), tokens) != 1) {
            throw new RunApiException(RunErrorCode.BUDGET_EXCEEDED);
        }
    }
}
