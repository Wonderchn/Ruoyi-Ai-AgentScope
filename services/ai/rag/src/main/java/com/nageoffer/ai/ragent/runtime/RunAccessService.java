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
import com.nageoffer.ai.ragent.ingest.DocumentDao;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.util.*;

/** Current authorization for formal run provenance and private owner visibility. */
@Service
public class RunAccessService {
    private final ObjectProvider<AiResourceAuthorizationService> resources;
    private final ObjectProvider<PlatformFactsClient> facts;
    private final ObjectProvider<RunLedgerDao> ledger;
    private final ObjectProvider<DocumentDao> documents;
    public RunAccessService(ObjectProvider<AiResourceAuthorizationService> resources,ObjectProvider<PlatformFactsClient> facts,
                            ObjectProvider<RunLedgerDao> ledger,ObjectProvider<DocumentDao> documents) {
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
    private void citationsCurrent(ExecutionPrincipal principal,RunRecord run) {
        if(!"rag.chat".equals(run.action())) return;
        var dao=ledger.getIfAvailable();
        if(dao==null) throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE);
        var step=dao.latestCompletedStep(run.tenantId(),run.runId(),"retrieve");
        if(step.isEmpty()) return;
        try {
            var chunks=CanonicalJson.strictMapper().readTree(step.get().refJson()).get("chunks");
            if(chunks==null || !chunks.isArray()) throw new IllegalArgumentException("invalid provenance");
            var docs=documents.getIfAvailable();
            if(docs==null) throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE);
            Set<String> checked=new HashSet<>();
            for(var chunk:chunks) {
                String docId=chunk.path("docId").asText(""),versionId=chunk.path("versionId").asText("");
                if(!docId.matches("[A-Za-z0-9_-]{1,64}") || !versionId.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("invalid provenance");
                if(!checked.add(docId+":"+versionId)) continue;
                var doc=docs.findDocument(principal.tenantId(),docId).orElseThrow();
                if(doc.tombstoned() || !versionId.equals(doc.publishedVersionId())
                        || resources().check(scoped(principal,Set.of("document.read")),"document.read","doc:"+docId)!=ResourceAuthorizationService.Verdict.GRANT)
                    throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
            }
        } catch(RunApiException e){throw e;}
        catch(Exception e){throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);}
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
