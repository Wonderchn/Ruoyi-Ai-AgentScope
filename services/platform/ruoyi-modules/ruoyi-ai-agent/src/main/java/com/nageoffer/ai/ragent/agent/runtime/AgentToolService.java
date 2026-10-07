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
import com.nageoffer.ai.ragent.runtime.*;
import com.nageoffer.ai.ragent.runtime.exec.*;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import com.nageoffer.ai.ragent.runtime.usage.*;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.ingest.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.*;

/** The catalog has two names and no user-selected endpoint or credential. */
@Service
@ConditionalOnProperty(name="p3.enabled",havingValue="true")
public class AgentToolService {
    private final JdbcTemplate jdbc;
    private final AgentLedger ledger;
    private final RunAccessService access;
    private final DocumentDao documents;
    private final EmbeddingGateway embedding;
    private final UsageLedgerService usage;
    private final SandboxTicketClient sandbox;
    private final RevocationGuard revocation;
    private final P2FaultInjector faults;
    private final AgentProviderBoundary boundary;
    public AgentToolService(JdbcTemplate jdbc,AgentLedger ledger,RunAccessService access,DocumentDao documents,
        EmbeddingGateway embedding,UsageLedgerService usage,SandboxTicketClient sandbox,RevocationGuard revocation,
        org.springframework.beans.factory.ObjectProvider<P2FaultInjector> faults,AgentProviderBoundary boundary) {
        this.jdbc=jdbc;this.ledger=ledger;this.access=access;this.documents=documents;this.embedding=embedding;
        this.usage=usage;this.sandbox=sandbox;this.revocation=revocation;this.faults=faults.getIfAvailable();this.boundary=boundary;
    }
    private static JsonNode parse(String text){try{return CanonicalJson.strictMapper().readTree(text);}catch(Exception e){throw new RunApiException(RunErrorCode.BAD_REQUEST);}}
    public JsonNode invoke(RunExecution execution,String name,JsonNode args) {
        var run=execution.run();var guard=execution.guard();
        ledger.compatible(run,ledger.binding(run).model());
        if(guard.isCancelRequested()) throw new AgentSuspension("CANCELLED");
        JsonNode input=parse(run.inputJson());
        if("kb_search".equals(name)) {
            AgentContract.fields(args,Set.of("query"));
            if(!args.path("query").isTextual() || !args.path("query").asText().equals(input.path("text").asText())) throw new RunApiException(RunErrorCode.BAD_REQUEST,"query must match the admitted purpose");
            return search(execution,args);
        }
        if(!"sandbox_ticket".equals(name) || !"sandbox".equals(input.path("mode").asText()) || !args.equals(input.path("ticket"))) throw new RunApiException(RunErrorCode.BAD_REQUEST,"tool outside frozen catalog or arguments");
        AgentContract.ticket(args);
        return ticket(execution,args);
    }
    private AgentLedger.Action proposal(RunExecution e,String name,JsonNode args,String target,String state) {
        String hash=AgentLedger.hashArgs(args);
        return e.guard().commitAtomic(()->{
            var old=ledger.actions(e.run()).stream().filter(a->name.equals(a.tool()) && hash.equals(a.argsHash())).findFirst();
            if(old.isPresent()) return old.get();
            ledger.spendStep(e.guard(),true);
            String id="act-"+UUID.randomUUID().toString().replace("-","");
            jdbc.update("INSERT INTO ai_tool_call(tenant_id,action_id,member_id,run_id,tool_name,tool_version,args,args_hash,target,operation_key,approval_version,state,source_refs,attempt,fence) VALUES(?,?,?,?,?,'v1',?::jsonb,?,?,?,1,?,?::jsonb,?,?)",
                e.tenantId(),id,e.run().memberId(),e.runId(),name,AgentLedger.json(args),hash,target,"op-"+UUID.randomUUID().toString().replace("-",""),state,e.run().resourceRefsJson(),e.attempt(),e.fence());
            e.guard().appendEvent("tool.proposed",Map.of("actionId",id,"tool",name,"argsHash",hash));
            return ledger.action(e.tenantId(),id).orElseThrow();
        });
    }
    private JsonNode search(RunExecution e,JsonNode args) {
        var a=proposal(e,"kb_search",args,"pg:published","STARTED");
        access.current(e.run(),Set.of("agent.execute","kb.read"));
        if("SUCCEEDED".equals(a.state())) return parse(a.result());
        String step="agent-retrieve-"+a.id();
        if(usage.hasCall(e.tenantId(),e.runId(),step)) throw new AgentSuspension("NEEDS_RECONCILIATION");
        List<String> kbs=new ArrayList<>();
        for(var ref:parse(e.run().resourceRefsJson())) {String value=ref.path("ref").asText();if(value.startsWith("kb:")) kbs.add(value.substring(3));}
        if(kbs.isEmpty() || !documents.matchesEmbeddingModel(e.tenantId(),kbs,embedding.model())) throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT,"MODEL_CONFIG_CHANGED");
        if(faults!=null) faults.checkpointPause("agent.beforeReadEmbedding");
        var operation=boundary.enter(e,embedding.provider());
        String call;
        try {call=e.guard().commitAtomic(()->usage.startCall(e.tenantId(),e.runId(),e.attempt(),step,UsageLedgerService.KIND_EMBEDDING,embedding.provider(),embedding.model(),a.argsHash()));} catch(RuntimeException ex){operation.close();throw ex;}
        EmbeddingGateway.EmbeddingResult result;
        try {result=embedding.embedBatchWithUsage(List.of(args.path("query").asText()));}
        catch(RuntimeException ex){e.guard().commitAtomic(()->{usage.markUnknown(e.tenantId(),call);return null;});throw new AgentSuspension("NEEDS_RECONCILIATION");}
        operation.close();
        e.guard().commitAtomic(()->{usage.settle(e.tenantId(),call,result.providerRequestId(),result.usageRaw());return null;});
        if(usage.unresolved(e.tenantId(),e.runId(),step)) throw new AgentSuspension("NEEDS_RECONCILIATION");
        var principal=access.current(e.run(),Set.of("agent.execute","kb.read"));
        var chunks=documents.searchPublished(e.tenantId(),kbs,result.vectors().get(0),10,0.2);
        List<Map<String,Object>> refs=new ArrayList<>();
        for(var chunk:chunks) {
            var doc=documents.findDocument(e.tenantId(),chunk.docId()).orElseThrow();
            access.resources().requireFunction(RunAccessService.scoped(principal,Set.of("document.read")),"document.read","doc:"+doc.docId());
            if(doc.tombstoned() || !chunk.versionId().equals(doc.publishedVersionId()) || access.resources().check(RunAccessService.scoped(principal,Set.of("document.read")),"document.read","doc:"+doc.docId())!=com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService.Verdict.GRANT) throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
            var ref=new LinkedHashMap<String,Object>();
            ref.put("docId",chunk.docId());ref.put("versionId",chunk.versionId());ref.put("chunkKey",chunk.chunkKey());
            ref.put("content",chunk.content());ref.put("pageFrom",chunk.pageFrom());ref.put("pageTo",chunk.pageTo());
            refs.add(ref);
        }
        JsonNode payload=CanonicalJson.strictMapper().valueToTree(Map.of("chunks",refs,"embeddingModel",embedding.model()));
        e.guard().commitAtomic(()->{
            e.guard().commitStep(step,"agent.retrieve",AgentLedger.json(payload),AgentLedger.hashArgs(payload),"{}");
            jdbc.update("UPDATE ai_tool_call SET state='SUCCEEDED',result=?::jsonb,attempt=?,fence=?,version=version+1,updated_at=now() WHERE tenant_id=? AND action_id=? AND state='STARTED'",AgentLedger.json(payload),e.attempt(),e.fence(),e.tenantId(),a.id());
            e.guard().appendEvent("tool.completed",Map.of("actionId",a.id(),"tool",a.tool()));return null;
        });
        access.current(e.run(),Set.of("agent.execute","kb.read"));
        return payload;
    }
    private JsonNode ticket(RunExecution e,JsonNode args) {
        var inherited=ledger.inherited(e.run());
        if(inherited.isPresent()) {
            var source=inherited.get();access.visible(access.current(e.run(),Set.of("agent.execute","kb.read")),ledger.source(source));
            if(!source.argsHash().equals(AgentLedger.hashArgs(args))) throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
            if(!"SUCCEEDED".equals(source.state())) throw new AgentSuspension("NEEDS_RECONCILIATION");
            if(e.guard().findStep("agent-inherited").isEmpty()) e.guard().commitAtomic(()->{e.guard().commitStep("agent-inherited","agent.inherited",source.result(),source.argsHash(),"{}");e.guard().appendEvent("tool.inherited",Map.of("sourceActionId",source.id()));return null;});
            return parse(source.result());
        }
        var a=proposal(e,"sandbox_ticket",args,SandboxTicketClient.TARGET,"PROPOSED");
        if("SUCCEEDED".equals(a.state())) return parse(a.result());
        if(Set.of("STARTED","UNKNOWN").contains(a.state())) throw new AgentSuspension("NEEDS_RECONCILIATION");
        if("REJECTED".equals(a.state())) return CanonicalJson.strictMapper().valueToTree(Map.of("state","REJECTED"));
        if("PROPOSED".equals(a.state())) throw new AgentSuspension("WAITING_APPROVAL");
        if(faults!=null) faults.checkpointPause("agent.beforeSandboxWrite");
        var principal=access.current(e.run(),Set.of("agent.execute","kb.read","tool.sandbox.write"));
        var operation=revocation.enter(principal,"tool.sandbox.write","run:"+e.runId());
        try {
            e.guard().commitAtomic(()->{
                if(jdbc.update("UPDATE ai_tool_call SET state='STARTED',permit_id=?,permit_operation=?,sender_stopped=false,attempt=?,fence=?,version=version+1,updated_at=now() WHERE tenant_id=? AND action_id=? AND state='APPROVED' AND EXISTS(SELECT 1 FROM ai_action_approval WHERE tenant_id=? AND action_id=? AND approval_version=? AND decision='ALLOW' AND expires_at>now())",operation.permitId(),operation.operationId(),e.attempt(),e.fence(),e.tenantId(),a.id(),e.tenantId(),a.id(),a.approvalVersion())!=1) throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT,"approval expired or changed");
                return null;
            });
        } catch(RuntimeException ex){operation.close();throw ex;}
        JsonNode result;
        try { result=sandbox.verifyFound(sandbox.create(a.operationKey(),a.argsHash(),args),a.operationKey(),a.argsHash()); }
        catch(SandboxTicketClient.UnknownOutcome ex){
            e.guard().commitAtomic(()->{jdbc.update("UPDATE ai_tool_call SET state='UNKNOWN',sender_stopped=true,version=version+1,updated_at=now() WHERE tenant_id=? AND action_id=? AND state='STARTED'",e.tenantId(),a.id());return null;});
            throw new AgentSuspension("NEEDS_RECONCILIATION");
        }
        if(faults!=null) faults.checkpoint("agent.afterExternalWrite");
        e.guard().commitAtomic(()->{
            jdbc.update("UPDATE ai_tool_call SET state='SUCCEEDED',result=?::jsonb,external_id=?,sender_stopped=true,version=version+1,updated_at=now() WHERE tenant_id=? AND action_id=? AND state='STARTED'",AgentLedger.json(result),result.path("externalId").asText(),e.tenantId(),a.id());
            e.guard().commitStep("agent-ticket-"+a.id(),"agent.ticket",AgentLedger.json(result),a.argsHash(),"{}");
            e.guard().appendEvent("tool.completed",Map.of("actionId",a.id(),"externalId",result.path("externalId").asText()));return null;
        });
        operation.close();
        if(faults!=null) faults.checkpoint("agent.afterToolCheckpoint");
        return result;
    }
}
