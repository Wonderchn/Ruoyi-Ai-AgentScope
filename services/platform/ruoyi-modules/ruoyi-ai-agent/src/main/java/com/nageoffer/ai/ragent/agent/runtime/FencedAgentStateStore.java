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

import io.agentscope.core.state.*;
import io.agentscope.core.util.JsonUtils;
import com.nageoffer.ai.ragent.runtime.*;
import com.nageoffer.ai.ragent.runtime.exec.RunExecutionGuard;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

/** One immutable run binding, real AgentScope SPI, all writes through the P2 live fence. */
public final class FencedAgentStateStore implements AgentStateStore {
    private final JdbcTemplate jdbc;
    private final AgentLedger ledger;
    private final RunRecord run;
    private final RunExecutionGuard guard;
    private final String model;
    private boolean recordedLoad;
    public FencedAgentStateStore(JdbcTemplate jdbc,AgentLedger ledger,RunRecord run,RunExecutionGuard guard,String model){this.jdbc=jdbc;this.ledger=ledger;this.run=run;this.guard=guard;this.model=model;}
    private void scope(String user,String session,String key) {
        if(!run.subject().equals(user) || !run.runId().equals(session) || key==null || !key.matches("[A-Za-z0-9_.:-]{1,128}")) throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        ledger.compatible(run,model);
    }
    public void save(String user,String session,String key,State value){save(user,session,key,List.of(value));}
    public void save(String user,String session,String key,List<? extends State> values) {
        scope(user,session,key);
        String payload;
        try {payload=CanonicalJson.canonicalize(CanonicalJson.strictMapper().readTree(JsonUtils.getJsonCodec().toJson(values)));}
        catch(java.io.IOException e){throw new RunApiException(RunErrorCode.BAD_REQUEST);}
        if(payload.length()>262144) throw new RunApiException(RunErrorCode.BUDGET_EXCEEDED);
        guard.commitAtomic(()->{
            jdbc.update("INSERT INTO ai_agent_checkpoint(tenant_id,run_id,member_id,state_key,payload,payload_hash,attempt,fence,engine_version,agent_version,model,tool_catalog,policy_version,acl_version,checkpoint_version,source_refs) VALUES(?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?,?,1,?::jsonb) ON CONFLICT(tenant_id,run_id,state_key) DO UPDATE SET payload=excluded.payload,payload_hash=excluded.payload_hash,attempt=excluded.attempt,fence=excluded.fence,updated_at=now()",
                run.tenantId(),run.runId(),run.memberId(),key,payload,CanonicalJson.sha256(payload),guard.attempt(),guard.fence(),AgentLedger.ENGINE,AgentLedger.AGENT,model,AgentLedger.CATALOG,run.policyVersion(),run.aclVersion(),run.resourceRefsJson());
            return null;
        });
    }
    private String payload(String user,String session,String key) {
        scope(user,session,key);
        var rows=jdbc.queryForList("SELECT payload::text,payload_hash,member_id,engine_version,agent_version,model,tool_catalog,checkpoint_version,policy_version,acl_version FROM ai_agent_checkpoint WHERE tenant_id=? AND run_id=? AND state_key=?",run.tenantId(),run.runId(),key);
        if(rows.isEmpty()) return null;
        var row=rows.get(0);
        // PG jsonb formatting is canonicalized before hash comparison; semantic hash is checked below.
        if(!run.memberId().equals(row.get("member_id")) || !AgentLedger.ENGINE.equals(row.get("engine_version")) || !AgentLedger.AGENT.equals(row.get("agent_version")) || !model.equals(row.get("model")) || !AgentLedger.CATALOG.equals(row.get("tool_catalog")) || ((Number)row.get("checkpoint_version")).intValue()!=1 || ((Number)row.get("policy_version")).intValue()!=run.policyVersion() || ((Number)row.get("acl_version")).intValue()!=run.aclVersion()) throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT,"AGENT_CHECKPOINT_INCOMPATIBLE");
        String payload=(String)row.get("payload");
        try {
            String hash=CanonicalJson.sha256(CanonicalJson.canonicalize(CanonicalJson.strictMapper().readTree(payload)));
            if(!hash.equals(row.get("payload_hash"))) throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT,"AGENT_STATE_HASH_MISMATCH");
        } catch(java.io.IOException e){throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT);}
        return payload;
    }
    public <T extends State> Optional<T> get(String user,String session,String key,Class<T> type){var values=getList(user,session,key,type);return values.stream().findFirst();}
    public <T extends State> List<T> getList(String user,String session,String key,Class<T> type) {
        String payload=payload(user,session,key);if(payload==null) return List.of();
        List<?> values=JsonUtils.getJsonCodec().fromJson(payload,List.class);
        List<T> decoded=values.stream().map(value->JsonUtils.getJsonCodec().convertValue(value,type)).toList();
        if(!recordedLoad) {
            guard.appendEvent("agent.state_loaded",Map.of("stateKey",key,"payloadHash",CanonicalJson.sha256(payload),"attempt",guard.attempt()));
            recordedLoad=true;
        }
        return decoded;
    }
    public boolean exists(String user,String session){scope(user,session,"exists");return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ai_agent_checkpoint WHERE tenant_id=? AND run_id=?)",Boolean.class,run.tenantId(),run.runId()));}
    public void delete(String user,String session){throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT,"retained checkpoints cannot be deleted by the engine");}
    public Set<String> listSessionIds(String user){if(!run.subject().equals(user)) throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);ledger.compatible(run,model);return Set.of(run.runId());}
}
