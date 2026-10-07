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
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.runtime.*;
import com.nageoffer.ai.ragent.runtime.dto.AdmissionRequest;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.ingest.ChatGateway;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.*;

@Component
@ConditionalOnProperty(name="p3.enabled",havingValue="true")
public class AgentContract implements RuntimeActionContract {
    private final P3Properties properties;
    private final AgentLedger ledger;
    private final RunLedgerDao runs;
    private final RunAccessService access;
    private final ChatGateway model;
    public AgentContract(P3Properties properties,AgentLedger ledger,RunLedgerDao runs,RunAccessService access,ChatGateway model){this.properties=properties;this.ledger=ledger;this.runs=runs;this.access=access;this.model=model;}
    public String action(){return "agent.run";}
    public String executionVersion(){return "p3-core-v1";}
    public void validate(ExecutionPrincipal principal,AdmissionRequest request) {
        validateShape(request);
        access.resources().requireFunction(RunAccessService.scoped(principal,Set.of("agent.execute")),"agent.execute","run:new");
        if("sandbox".equals(request.input().path("mode").asText())) {
            if(!properties.getSandbox().isEnabled() || !properties.getApproval().isInitiatorEnabled()) throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT,"SANDBOX_POLICY_CLOSED");
            access.resources().requireFunction(RunAccessService.scoped(principal,Set.of("tool.sandbox.write")),"tool.sandbox.write","run:new");
        }
        String source=request.input().path("inheritActionId").asText("");
        if(!source.isEmpty()) {
            var action=ledger.action(principal.tenantId(),source).orElseThrow(()->new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
            var parent=ledger.source(action);access.visible(principal,parent);
            if(!action.runId().equals(request.retryOf()) || !action.member().equals(principal.membershipId()) || !Set.of("SUCCEEDED","UNKNOWN","STARTED").contains(action.state()) || !action.argsHash().equals(AgentLedger.hashArgs(request.input().path("ticket")))) throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        }
    }
    public static void validateShape(AdmissionRequest request) {
        if(!AgentLedger.AGENT.equals(request.agentVersion())) bad();
        JsonNode input=request.input();fields(input,Set.of("text","mode","ticket","inheritActionId"));
        text(input.get("text"),4096);
        if(!Set.of("read","sandbox").contains(input.path("mode").asText())) bad();
        if(request.resourceRefs()==null || request.resourceRefs().isEmpty() || request.resourceRefs().stream().anyMatch(ref->!"knowledge_base".equals(ref.type()))) bad();
        if("sandbox".equals(input.path("mode").asText())) ticket(input.path("ticket"));
        else if(input.has("ticket") || input.has("inheritActionId")) bad();
        if(input.has("inheritActionId") && (!input.get("inheritActionId").isTextual() || !input.path("inheritActionId").asText().matches("act-[a-f0-9]{32}") || request.retryOf()==null)) bad();
    }
    public static void ticket(JsonNode node){fields(node,Set.of("title","details"));text(node.get("title"),120);text(node.get("details"),1024);}
    public static void fields(JsonNode node,Set<String> allowed){if(node==null || !node.isObject()) bad();node.fieldNames().forEachRemaining(field->{if(!allowed.contains(field)) bad();});}
    private static void text(JsonNode node,int max){if(node==null || !node.isTextual() || node.asText().isBlank() || node.asText().length()>max) bad();}
    private static void bad(){throw new RunApiException(RunErrorCode.BAD_REQUEST,"invalid frozen Agent input");}
    /**
     * 受理时刻的模型名来自**发布权威**（C1.2 第 1 行：新受理的 run 绑新版本）。
     *
     * <p>不能用 {@code ChatGateway.model()}：那是 run 作用域面，而受理时刻**还没有** run 绑定；
     * 用无身份面会（正确地）抛异常，等于把受理打断。也不读 YAML —— 那正是 D02 要取消的第二权威。
     * 读不到权威即拒绝受理（fail-closed）。
     *
     * <p>{@code model}（ChatGateway）字段保留 ctor 形参形态：不改宿主的 wiring 签名；
     * 本类自受理时刻起不再用它取模型名。
     */
    private com.nageoffer.ai.ragent.runtime.config.EngineModelAuthority modelAuthority;

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void configureModelAuthority(com.nageoffer.ai.ragent.runtime.config.EngineModelAuthority authority) {
        this.modelAuthority=authority;
    }

    public void onAdmitted(ExecutionPrincipal principal,String runId,AdmissionRequest request){
        var run=runs.findRun(principal.tenantId(),runId).orElseThrow();
        var authority=modelAuthority;
        if(authority==null) throw new com.nageoffer.ai.ragent.runtime.config.ConfigAuthorityUnavailable("engine model authority is not wired; refusing to record an admitted model");
        ledger.admitted(run,request,authority.requirePublished(action()).modelId());
    }
}
