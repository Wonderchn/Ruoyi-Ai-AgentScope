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

import com.fasterxml.jackson.databind.JsonNode;
import com.nageoffer.ai.ragent.framework.context.*;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.runtime.*;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.ResponseEntity;
import java.util.*;

@RestController
@RequestMapping("/internal/ai/v1/runs")
@ConditionalOnProperty(name="p3.enabled",havingValue="true")
public class AgentActionController {
    private final AgentLedger ledger;
    private final RunLedgerDao runs;
    private final RunAccessService access;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final P3Properties properties;
    private final RunEventAppender events;
    private final SandboxTicketClient sandbox;
    private final RevocationGuard revocation;
    private final DeliveryPermits deliveries;
    public AgentActionController(AgentLedger ledger,RunLedgerDao runs,RunAccessService access,JdbcTemplate jdbc,
        org.springframework.transaction.PlatformTransactionManager tx,P3Properties properties,RunEventAppender events,
        SandboxTicketClient sandbox,RevocationGuard revocation,DeliveryPermits deliveries) {
        this.ledger=ledger;this.runs=runs;this.access=access;this.jdbc=jdbc;this.transactions=new TransactionTemplate(tx);
        this.properties=properties;this.events=events;this.sandbox=sandbox;this.revocation=revocation;this.deliveries=deliveries;
    }
    private ExecutionPrincipal principal(){if(!PrincipalContext.hasPrincipal()) throw new RunApiException(RunErrorCode.AUTH_REQUIRED);return PrincipalContext.require();}
    private RunRecord visible(String id,String action) {
        var principal=principal();
        var run=runs.findRun(principal.tenantId(),id).orElseThrow(()->new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        if(!"agent.run".equals(run.action())) throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        access.visible(principal,run);access.resources().requireFunction(principal,action,"run:"+id);return run;
    }
    private ResponseEntity<?> response(String id,String action,java.util.function.Supplier<Object> operation) {
        String request="req-"+UUID.randomUUID().toString().replace("-","");
        DeliveryPermits.Permit permit=null;
        try {
            visible(id,action);permit=deliveries.enter(principal(),action,"run:"+id);
            Object result=operation.get();visible(id,action);
            return ResponseEntity.ok().header("X-AI-Delivery-Permit",permit.permitId()).header("X-AI-Delivery-Operation",permit.operationId()).body(Map.of("code",200,"msg","success","data",result,"requestId",request));
        } catch(RuntimeException e) {
            if(permit!=null) permit.close();
            if(e instanceof RunApiException api) return com.nageoffer.ai.ragent.runtime.web.RunApiResponses.fail(api.errorCode(),api.getMessage(),request);
            throw e;
        }
    }
    private Map<String,Object> view(AgentLedger.Action a) {
        var data=new LinkedHashMap<String,Object>();
        data.put("actionId",a.id());data.put("tool",a.tool());data.put("toolVersion",a.toolVersion());data.put("args",CanonicalJson.strictMapper().convertValue(AgentModelAdapter.parse(a.args()),Object.class));
        data.put("argsHash",a.argsHash());data.put("target",a.target());data.put("approvalVersion",a.approvalVersion());data.put("state",a.state());
        data.put("externalId",a.externalId());data.put("version",a.version());return data;
    }
    @GetMapping("/{id}/actions") public ResponseEntity<?> actions(@PathVariable String id){
        // Gateway recognizes the delivery headers; the result shape stays the ordinary data list.
        String request="req-"+UUID.randomUUID().toString().replace("-","");
        DeliveryPermits.Permit permit=null;
        try {var run=visible(id,"run.get");permit=deliveries.enter(principal(),"run.get","run:"+id);return ResponseEntity.ok().header("X-Ai-Delivery-Permit",permit.permitId()).header("X-Ai-Delivery-Operation",permit.operationId()).body(Map.of("code",200,"msg","success","data",ledger.actions(run).stream().map(this::view).toList(),"requestId",request));}
        catch(RuntimeException e){if(permit!=null) permit.close();if(e instanceof RunApiException api) return com.nageoffer.ai.ragent.runtime.web.RunApiResponses.fail(api.errorCode(),api.getMessage(),request);throw e;}
    }
    @PostMapping("/{id}/approvals") public ResponseEntity<?> approve(@PathVariable String id,@RequestBody String body){return response(id,"run.approve",()->{
        var input=AgentModelAdapter.parse(body);AgentContract.fields(input,Set.of("actionId","argsHash","toolVersion","target","approvalVersion","decision"));
        if(input.size()!=6 || !input.path("approvalVersion").isIntegralNumber() || !Set.of("ALLOW","DENY").contains(input.path("decision").asText())) throw new RunApiException(RunErrorCode.BAD_REQUEST);
        if(!properties.getApproval().isInitiatorEnabled()) throw new RunApiException(RunErrorCode.FORBIDDEN,"APPROVER_POLICY_CLOSED");
        var initial=visible(id,"run.approve");
        return transactions.execute(status->{
            var run=runs.lockRun(initial.tenantId(),id).orElseThrow();visible(id,"run.approve");
            var a=ledger.action(run.tenantId(),input.path("actionId").asText()).orElseThrow(()->new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
            if(!id.equals(a.runId()) || !principal().membershipId().equals(a.member())) throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
            if(!a.argsHash().equals(input.path("argsHash").asText()) || !a.toolVersion().equals(input.path("toolVersion").asText()) || !a.target().equals(input.path("target").asText()) || a.approvalVersion()!=input.path("approvalVersion").intValue() || !"sandbox_ticket".equals(a.tool())) throw new RunApiException(RunErrorCode.VERSION_CONFLICT);
            if(Set.of("CANCELLED","CANCEL_REQUESTED","FAILED").contains(run.status())) throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT);
            var prior=jdbc.queryForList("SELECT decision,expires_at>now() AS valid FROM ai_action_approval WHERE tenant_id=? AND action_id=? AND approval_version=?",run.tenantId(),a.id(),a.approvalVersion());
            String decision=input.path("decision").asText();
            if(!prior.isEmpty()) {if(!decision.equals(prior.get(0).get("decision")) || !Boolean.TRUE.equals(prior.get(0).get("valid"))) throw new RunApiException(RunErrorCode.VERSION_CONFLICT);return view(a);}
            if(!"WAITING_APPROVAL".equals(run.status()) || !"PROPOSED".equals(a.state()) || !Boolean.TRUE.equals(jdbc.queryForObject("SELECT created_at>now()-interval '15 minutes' FROM ai_tool_call WHERE tenant_id=? AND action_id=?",Boolean.class,run.tenantId(),a.id()))) throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT);
            jdbc.update("INSERT INTO ai_action_approval(tenant_id,action_id,approval_version,args_hash,tool_version,target,initiator_member,decision,expires_at,policy_version,acl_version) VALUES(?,?,?,?,?,?,?,?,now()+interval '15 minutes',?,?)",run.tenantId(),a.id(),a.approvalVersion(),a.argsHash(),a.toolVersion(),a.target(),principal().membershipId(),decision,run.policyVersion(),run.aclVersion());
            jdbc.update("UPDATE ai_tool_call SET state=?,version=version+1,updated_at=now() WHERE tenant_id=? AND action_id=? AND state='PROPOSED'","ALLOW".equals(decision)?"APPROVED":"REJECTED",run.tenantId(),a.id());
            jdbc.update("UPDATE ai_run SET status='QUEUED',lease_owner=NULL,lease_until=NULL,version=version+1,updated_at=now() WHERE tenant_id=? AND run_id=? AND status='WAITING_APPROVAL'",run.tenantId(),id);
            events.append(run.tenantId(),id,"tool.approval",Map.of("actionId",a.id(),"decision",decision,"approvalVersion",a.approvalVersion()));
            events.appendStatusEvent(run.tenantId(),id,"QUEUED","sandbox confirmation recorded");
            return view(ledger.action(run.tenantId(),a.id()).orElseThrow());
        });
    });}
    @GetMapping("/{id}/reconciliations/{actionId}") public ResponseEntity<?> evidence(@PathVariable String id,@PathVariable String actionId){return response(id,"run.reconcile",()->{
        var run=visible(id,"run.reconcile");var a=owned(run,actionId);
        return Map.of("action",view(a),"evidence",jdbc.queryForList("SELECT seq,actor_member,evidence_hash,external_id,finality,created_at FROM ai_action_reconciliation WHERE tenant_id=? AND action_id=? ORDER BY seq",run.tenantId(),a.id()));
    });}
    private AgentLedger.Action owned(RunRecord run,String id){var a=ledger.action(run.tenantId(),id).orElseThrow(()->new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));if(!run.runId().equals(a.runId()) || !run.memberId().equals(a.member()) || !"sandbox_ticket".equals(a.tool())) throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);return a;}
    @PostMapping("/{id}/reconciliations/{actionId}/query") public ResponseEntity<?> query(@PathVariable String id,@PathVariable String actionId,@RequestBody String body){return response(id,"run.reconcile",()->{
        if(!AgentModelAdapter.parse(body).isObject() || !AgentModelAdapter.parse(body).isEmpty()) throw new RunApiException(RunErrorCode.BAD_REQUEST);
        var run=visible(id,"run.reconcile");var a=owned(run,actionId);
        if(!Set.of("STARTED","UNKNOWN","SUCCEEDED").contains(a.state())) throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT);
        JsonNode result;boolean found;
        try {result=sandbox.query(a.operationKey());if("FOUND".equals(result.path("state").asText())) {sandbox.verifyFound(result,a.operationKey(),a.argsHash());found=true;}else {if(!"ABSENT".equals(result.path("state").asText()) || result.path("final").asBoolean(true)) throw new SandboxTicketClient.UnknownOutcome();found=false;}}
        catch(SandboxTicketClient.UnknownOutcome ex){result=CanonicalJson.strictMapper().valueToTree(Map.of("state","UNKNOWN","final",false));found=false;}
        JsonNode proof=result;boolean finalFound=found;
        transactions.executeWithoutResult(status->{
            runs.lockRun(run.tenantId(),id).orElseThrow();visible(id,"run.reconcile");var current=owned(run,a.id());
            int seq=jdbc.queryForObject("SELECT coalesce(max(seq),0)+1 FROM ai_action_reconciliation WHERE tenant_id=? AND action_id=?",Integer.class,run.tenantId(),a.id());
            jdbc.update("INSERT INTO ai_action_reconciliation(tenant_id,action_id,seq,actor_member,evidence,evidence_hash,external_id,finality,policy_version,acl_version) VALUES(?,?,?,?,?::jsonb,?,?,?,?,?)",run.tenantId(),a.id(),seq,principal().membershipId(),AgentLedger.json(proof),AgentLedger.hashArgs(proof),finalFound?proof.path("externalId").asText():null,finalFound?"FOUND":"UNKNOWN",run.policyVersion(),run.aclVersion());
            if(finalFound) {
                jdbc.update("UPDATE ai_tool_call SET state='SUCCEEDED',result=?::jsonb,external_id=?,version=version+1,updated_at=now() WHERE tenant_id=? AND action_id=? AND state IN ('STARTED','UNKNOWN')",AgentLedger.json(proof),proof.path("externalId").asText(),run.tenantId(),a.id());
                var permit=jdbc.queryForList("SELECT permit_id,permit_operation,sender_stopped FROM ai_tool_call WHERE tenant_id=? AND action_id=?",run.tenantId(),a.id()).get(0);
                if(Boolean.TRUE.equals(permit.get("sender_stopped")) && permit.get("permit_id")!=null) new RevocationGuard.Operation(revocation,(String)permit.get("permit_id"),(String)permit.get("permit_operation")).close();
            }
            // Audit is retained independently. A terminal run is never rewritten or given a second terminal event.
        });
        return Map.of("action",view(ledger.action(run.tenantId(),a.id()).orElseThrow()),"finality",found?"FOUND":"UNKNOWN");
    });}
}
