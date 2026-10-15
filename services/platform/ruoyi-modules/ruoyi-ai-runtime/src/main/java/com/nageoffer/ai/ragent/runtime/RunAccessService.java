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

import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService;
import com.nageoffer.ai.ragent.runtime.dto.AdmissionRequest;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import com.nageoffer.ai.ragent.runtime.usage.PlatformFactsClient;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import org.ruoyi.ai.api.runtime.DocumentPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.util.*;

/** Current authorization for formal run provenance and private owner visibility. */
@Service
public class RunAccessService {
    private final ObjectProvider<AiResourceAuthorizationService> resources;
    private final ObjectProvider<PlatformFactsClient> facts;
    private final ObjectProvider<RunLedgerDao> ledger;
    private final ObjectProvider<DocumentPort> documents;
    public RunAccessService(ObjectProvider<AiResourceAuthorizationService> resources,ObjectProvider<PlatformFactsClient> facts,
                            ObjectProvider<RunLedgerDao> ledger,ObjectProvider<DocumentPort> documents) {
        this.resources=resources;this.facts=facts;this.ledger=ledger;this.documents=documents;
    }
    public AiResourceAuthorizationService resources() {
        var value=resources.getIfAvailable();
        if(value==null) throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE);
        return value;
    }
    public String capture(ExecutionPrincipal principal,List<AdmissionRequest.ResourceRef> refs) {
        List<Map<String,Object>> proof=new ArrayList<>();
        if(refs!=null) for(var input:refs) {
            String action,prefix;
            if("knowledge_base".equals(input.type())){action="kb.read";prefix="kb:";}
            else if("document".equals(input.type())){action="document.read";prefix="doc:";}
            else throw new RunApiException(RunErrorCode.BAD_REQUEST,"unsupported source type");
            if(input.id()==null || !input.id().matches("[A-Za-z0-9_-]{1,64}")) throw new RunApiException(RunErrorCode.BAD_REQUEST);
            String ref=prefix+input.id();
            var scoped=scoped(principal,Set.of(action));
            var service=resources();
            if(service.check(scoped,action,ref)!=ResourceAuthorizationService.Verdict.GRANT) throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
            var fact=service.facts(principal.tenantId(),List.of(ref)).get(ref);
            if(fact==null || fact.isTombstoned()) throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
            proof.add(Map.of("ref",ref,"version",fact.resourceVersion()));
        }
        try{return CanonicalJson.strictMapper().writeValueAsString(proof);}catch(Exception e){throw new RunApiException(RunErrorCode.INTERNAL_ERROR);}
    }
    public void visible(ExecutionPrincipal principal,RunRecord run) {
        ownerAndVersions(principal,run);
        String refs=run.resourceRefsJson();
        if(refs==null) throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        if(!"[]".equals(refs.replace(" ","")) && !resources().sourcesCurrent(principal,refs,run.policyVersion(),run.aclVersion())) throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        citationsCurrent(principal,run);
    }
    public void visibleWithCaptured(ExecutionPrincipal principal,RunRecord run,String captured) {
        ownerAndVersions(principal,run);
        try {
            if(!CanonicalJson.strictMapper().readTree(captured).equals(CanonicalJson.strictMapper().readTree(run.resourceRefsJson())))
                visible(principal,run);
            else citationsCurrent(principal,run);
        } catch(RunApiException e){throw e;}catch(Exception e){throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);}
    }
    /**
     * 引用 provenance 复核（F16 op4 语义区分）。
     *
     * <p><b>三态契约（对外语义必须分开，不能一律吞成 404）：</b>
     * <ul>
     *   <li><b>确实不可见 → 404</b>：findDocument 空、tombstone、版本不符、明确 DENY/STALE；
     *       空/畸形 provenance 同样保持拒绝（不得当"正常空历史"放行）；</li>
     *   <li><b>依赖不可用 → 503</b>：findDocument / 授权判定抛普通异常，或判定返回
     *       {@code UNKNOWN}（enum 既有语义：无法判定，对外 503 不放行）。</li>
     * </ul>
     * 此前所有异常都被兜底成 404，依赖故障与"来源变化"不可区分——会话历史加载器按
     * "404 跳过该轮"的契约会把故障误当空历史（红证：
     * {@code RunAccessServiceConversationDependencyTest.dependencyFailureIsVisibleAndNeverMasqueradesAsMissingHistory}）。
     */
    private void citationsCurrent(ExecutionPrincipal principal,RunRecord run) {
        if(!Set.of("rag.chat","agent.run").contains(run.action())) return;
        var dao=ledger.getIfAvailable();
        if(dao==null) throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE);
        var steps="rag.chat".equals(run.action())?dao.latestCompletedStep(run.tenantId(),run.runId(),"retrieve").stream().toList():dao.listSteps(run.tenantId(),run.runId()).stream().filter(step->"COMPLETED".equals(step.state()) && step.stepId().startsWith("agent-retrieve-")).toList();
        if(steps.isEmpty()) return;
        for(var step:steps) {
            var chunks=citationChunks(step);
            var docs=documents.getIfAvailable();
            if(docs==null) throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE);
            Set<String> checked=new HashSet<>();
            for(var chunk:chunks) {
                String docId=chunk.path("docId").asText(""),versionId=chunk.path("versionId").asText("");
                if(!docId.matches("[A-Za-z0-9_-]{1,64}") || !versionId.matches("[A-Za-z0-9_-]{1,64}")) throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
                if(!checked.add(docId+":"+versionId)) continue;
                var doc=findCitationDocument(docs,principal.tenantId(),docId);
                if(doc==null || doc.tombstoned() || !versionId.equals(doc.publishedVersionId())
                        || !documentReadGranted(principal,docId))
                    throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
            }
        }
    }
    /** 解析引用 provenance：空/畸形保持拒绝（404）——不把缺 provenance 当正常可见。 */
    private static com.fasterxml.jackson.databind.JsonNode citationChunks(RunLedgerDao.StepRow step) {
        try {
            var chunks=CanonicalJson.strictMapper().readTree(step.refJson()).get("chunks");
            if(chunks==null || !chunks.isArray()) throw new IllegalArgumentException("invalid provenance");
            return chunks;
        } catch(RunApiException e){throw e;}
        catch(Exception e){throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);}
    }
    /** 引用文档事实读取：指针更在却读不到（依赖异常）→ 503；确实没有 → null（调用方按 404）。 */
    private static DocumentPort.DocumentRow findCitationDocument(DocumentPort docs,String tenantId,String docId) {
        try {
            return docs.findDocument(tenantId,docId).orElse(null);
        } catch(RunApiException e){throw e;}
        catch(RuntimeException e){throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE,"citation document lookup unavailable");}
    }
    /** 引用文档当前 document.read：GRANT 放行；DENY/STALE → false（404 语义）；UNKNOWN/异常 → 503。 */
    private boolean documentReadGranted(ExecutionPrincipal principal,String docId) {
        try {
            var verdict=resources().check(scoped(principal,Set.of("document.read")),"document.read","doc:"+docId);
            if(verdict==ResourceAuthorizationService.Verdict.UNKNOWN)
                throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE,"citation authorization unresolved");
            return verdict==ResourceAuthorizationService.Verdict.GRANT;
        } catch(RunApiException e){throw e;}
        catch(RuntimeException e){throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE,"citation authorization check failed");}
    }
    private void ownerAndVersions(ExecutionPrincipal principal,RunRecord run) {
        if(!principal.tenantId().equals(run.tenantId()) || !principal.membershipId().equals(run.memberId())
                || principal.policyVersion()!=run.policyVersion() || principal.aclVersion()!=run.aclVersion()) throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
    }
    public ExecutionPrincipal current(RunRecord run,Set<String> scopes) {
        var client=facts.getIfAvailable();
        if(client==null) throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE);
        var current=client.currentFacts(run.tenantId(),run.subject(),run.memberId());
        if(!current.enabled() || current.policyVersion()<1) throw new RunApiException(RunErrorCode.FORBIDDEN);
        long now=java.time.Instant.now().getEpochSecond();
        var principal=new ExecutionPrincipal(run.tenantId(),run.subject(),run.memberId(),current.policyVersion(),resources().currentAclVersion(run.tenantId()),scopes,"async-"+run.runId(),"platform",now,now+300);
        visible(principal,run);
        for(String action:scopes) resources().requireFunction(principal,action,"run:"+run.runId());
        return principal;
    }
    public static ExecutionPrincipal scoped(ExecutionPrincipal p,Set<String> actions) {
        return new ExecutionPrincipal(p.tenantId(),p.userId(),p.membershipId(),p.policyVersion(),p.aclVersion(),actions,p.jti(),p.issuer(),p.issuedAtEpochSecond(),p.expiresAtEpochSecond());
    }
}
