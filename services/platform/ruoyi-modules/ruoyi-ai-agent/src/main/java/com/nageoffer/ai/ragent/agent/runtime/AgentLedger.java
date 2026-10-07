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

package com.nageoffer.ai.ragent.agent.runtime;

import com.nageoffer.ai.ragent.runtime.*;
import com.nageoffer.ai.ragent.runtime.exec.RunExecutionGuard;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.*;

@Service
@ConditionalOnProperty(name="p3.enabled",havingValue="true")
public class AgentLedger {
    public static final String ENGINE="2.0.2", AGENT="core-v1", CATALOG="p3-tools-v1";
    private final JdbcTemplate jdbc;
    private final RunLedgerDao runs;
    private final RunAccessService access;
    public AgentLedger(JdbcTemplate jdbc,RunLedgerDao runs,RunAccessService access){this.jdbc=jdbc;this.runs=runs;this.access=access;}
    public record Binding(String member,String agent,String engine,String model,String catalog,int maxSteps,int maxTools,int maxTokens,int tokensUsed,int checkpointVersion) {}
    public Binding binding(RunRecord run) {
        var rows=jdbc.query("SELECT member_id,agent_version,engine_version,model,tool_catalog,max_steps,max_tool_calls,max_tokens,tokens_used,checkpoint_version FROM ai_agent_run WHERE tenant_id=? AND run_id=?",
            (rs,n)->new Binding(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getInt(6),rs.getInt(7),rs.getInt(8),rs.getInt(9),rs.getInt(10)),run.tenantId(),run.runId());
        if(rows.size()!=1 || !rows.get(0).member().equals(run.memberId())) throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        return rows.get(0);
    }
    public void compatible(RunRecord run,String model) {
        compatible(binding(run),run,model);
    }

    /**
     * 接受**已读出的**绑定行的兼容性判定（RW-20）。
     *
     * <p>抽出这个重载是为了让"绑定行的读取次数"与"判定点"解耦：调用方若已经为了别的目的读过
     * {@link #binding(RunRecord)}，不必为了过这道门再读一次（同一行两次 SELECT 不是错误，
     * 但会让"这次执行读了几次绑定"变成不可断言的事实）。
     *
     * <p>语义与 {@link #compatible(RunRecord, String)} **完全一致**：不兼容即
     * {@link RunErrorCode#RUN_STATE_CONFLICT} 抛出，兼容时仍执行同一条授权门
     * （{@code access.current}）。**授权失败与不兼容是两件事**：前者必须原样冒出去，
     * 不能被任何调用方收敛成"checkpoint 不兼容"。
     */
    public void compatible(Binding binding,RunRecord run,String model) {
        if(!isCompatible(binding,model)) throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT,"AGENT_CHECKPOINT_INCOMPATIBLE");
        access.current(run,Set.of("agent.execute","kb.read"));
    }

    /**
     * 冻结的 checkpoint 兼容性判定（纯函数，RW-20）。
     *
     * <p>五个条件必须<b>同时</b>成立，缺一即"旧/异样 checkpoint"：
     * {@code agent_version=core-v1}、{@code engine_version=2.0.2}、{@code tool_catalog=p3-tools-v1}、
     * 绑定的模型与当前绑定模型一致、{@code checkpoint_version=1}。
     *
     * <p><b>为什么必须是纯函数。</b>它是"旧 checkpoint 兼容或显式拒绝"这条判据的核心：
     * 抛异常与返回 FAILED 是两种不同的外显形态，把它们混在一条语句里，
     * 同一种不兼容就会随判定点不同而给出不同的 {@code error_code}（RW-20 实测：
     * 执行器入口给 {@code AGENT_CHECKPOINT_INCOMPATIBLE}，而本类抛出的路径被 Worker 收成
     * {@code RUN_STATE_CONFLICT}）。拆出来之后，调用方可以**显式选择**外显形态，
     * 并让"哪一种不兼容"对客户端是一个稳定事实。
     *
     * <p>{@code model} 为 null 一律判为不兼容（不得因为"两边都取不到模型"就放行）。
     */
    public static boolean isCompatible(Binding binding,String model) {
        return binding!=null && AGENT.equals(binding.agent()) && ENGINE.equals(binding.engine())
                && CATALOG.equals(binding.catalog()) && model!=null && model.equals(binding.model())
                && binding.checkpointVersion()==1;
    }
    public void admitted(RunRecord run,com.nageoffer.ai.ragent.runtime.dto.AdmissionRequest request,String model) {
        var budget=request.budget();
        int steps=budget==null?6:budget.path("maxSteps").asInt(6),tools=budget==null?6:budget.path("maxToolCalls").asInt(6),tokens=budget==null?2000:budget.path("maxTokens").asInt(2000);
        jdbc.update("INSERT INTO ai_agent_run(tenant_id,run_id,member_id,agent_version,engine_version,model,tool_catalog,max_steps,max_tool_calls,max_tokens,source_refs) VALUES(?,?,?,?,?,?,?,?,?,?,?::jsonb)",run.tenantId(),run.runId(),run.memberId(),AGENT,ENGINE,model,CATALOG,steps,tools,tokens,run.resourceRefsJson());
        String source=request.input().path("inheritActionId").asText("");
        if(!source.isEmpty()) {
            var action=action(run.tenantId(),source).orElseThrow(()->new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
            jdbc.update("INSERT INTO ai_action_inheritance(tenant_id,new_run_id,source_run_id,source_action_id,member_id,args_hash) VALUES(?,?,?,?,?,?)",run.tenantId(),run.runId(),action.runId(),source,run.memberId(),action.argsHash());
        }
    }
    public void spendStep(RunExecutionGuard guard,boolean tool) {
        guard.commitAtomic(()->{
            String field=tool?"tools_used":"steps_used",limit=tool?"max_tool_calls":"max_steps";
            if(jdbc.update("UPDATE ai_agent_run SET "+field+"="+field+"+1 WHERE tenant_id=? AND run_id=? AND "+field+"<"+limit,guard.tenantId(),guard.runId())!=1) throw new RunApiException(RunErrorCode.BUDGET_EXCEEDED);
            return null;
        });
    }
    public void addTokens(RunExecutionGuard guard,int tokens) {
        if(tokens<0) throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT,"MODEL_USAGE_UNKNOWN");
        if(jdbc.update("UPDATE ai_agent_run SET tokens_used=tokens_used+? WHERE tenant_id=? AND run_id=? AND tokens_used+?<=max_tokens",tokens,guard.tenantId(),guard.runId(),tokens)!=1) throw new RunApiException(RunErrorCode.BUDGET_EXCEEDED);
    }
    public record Action(String tenant,String id,String member,String runId,String tool,String toolVersion,String args,String argsHash,String target,String operationKey,int approvalVersion,String state,String result,String externalId,long version) {}
    public Optional<Action> action(String tenant,String id) {
        return jdbc.query("SELECT tenant_id,action_id,member_id,run_id,tool_name,tool_version,args::text,args_hash,target,operation_key,approval_version,state,result::text,external_id,version FROM ai_tool_call WHERE tenant_id=? AND action_id=?",
            (rs,n)->new Action(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getString(10),rs.getInt(11),rs.getString(12),rs.getString(13),rs.getString(14),rs.getLong(15)),tenant,id).stream().findFirst();
    }
    public List<Action> actions(RunRecord run) {
        return jdbc.queryForList("SELECT action_id FROM ai_tool_call WHERE tenant_id=? AND run_id=? ORDER BY created_at",String.class,run.tenantId(),run.runId()).stream().map(id->action(run.tenantId(),id).orElseThrow()).toList();
    }
    public Optional<Action> inherited(RunRecord run) {
        return jdbc.queryForList("SELECT source_action_id FROM ai_action_inheritance WHERE tenant_id=? AND new_run_id=?",String.class,run.tenantId(),run.runId()).stream().findFirst().flatMap(id->action(run.tenantId(),id));
    }
    public RunRecord source(Action action){return runs.findRun(action.tenant(),action.runId()).orElseThrow(()->new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));}
    public static String json(Object value) {try{return CanonicalJson.strictMapper().writeValueAsString(value);}catch(Exception e){throw new RunApiException(RunErrorCode.BAD_REQUEST);}}
    public static String hashArgs(com.fasterxml.jackson.databind.JsonNode args){return CanonicalJson.sha256(CanonicalJson.canonicalize(args));}
}
