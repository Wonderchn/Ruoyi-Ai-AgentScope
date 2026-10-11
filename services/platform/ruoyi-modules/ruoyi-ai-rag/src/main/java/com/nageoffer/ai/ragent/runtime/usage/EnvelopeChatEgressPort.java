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

package com.nageoffer.ai.ragent.runtime.usage;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.infra.chat.ChatEgressDeniedException;
import com.nageoffer.ai.ragent.infra.chat.ChatEgressPort;
import java.net.URI;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Chat-only adapter; no tenant budget ledger or business run is synthesized here. */
public final class EnvelopeChatEgressPort implements ChatEgressPort {
    /** A reference and source versions, never bearer credentials or a transmitted holder. */
    public record SubjectReference(String tenantId, String userId, String membershipId,
            int policyVersion, int aclVersion, Set<String> scopes, long expiresAtEpochSecond) {
        public SubjectReference { scopes = Set.copyOf(scopes); }
    }
    @FunctionalInterface public interface CurrentSubjectVerifier {
        /** Check current tenant, exact membership, policy/ACL versions and every original scope. */
        void requireCurrent(SubjectReference subject);
    }

    private final EgressPolicy egress;
    private final ProviderSpendEnvelope spend;
    private final CurrentSubjectVerifier subjects;
    private final Clock clock;

    public EnvelopeChatEgressPort(EgressPolicy egress, ProviderSpendEnvelope spend,
            CurrentSubjectVerifier subjects, Clock clock) {
        this.egress=egress;this.spend=spend;this.subjects=subjects;this.clock=clock;
    }

    @Override public Lease authorize(CallSpec call) {
        try {
            if (egress==null || spend==null || subjects==null || clock==null
                    || !PrincipalContext.isSynchronousOrigin()) { throw denied(); }
            ExecutionPrincipal principal=PrincipalContext.require();
            SubjectReference subject=new SubjectReference(principal.tenantId(),principal.userId(),
                    principal.membershipId(),principal.policyVersion(),principal.aclVersion(),
                    principal.scopes(),principal.expiresAtEpochSecond());
            requireCurrent(subject);
            String provider=canonicalProvider(call);
            if(call.model()==null || call.model().isBlank() || call.model().length()>256
                    || call.inputBytes()<0 || call.inputBytes()>1048576
                    || call.maxOutputTokens()<1 || call.maxOutputTokens()>8192) { throw denied(); }
            egress.requireAllowed(provider);
            String callId=spend.reserve(provider,call.model(),call.inputBytes(),call.maxOutputTokens());
            return new Lease() {
                private final AtomicBoolean sent=new AtomicBoolean();
                private final AtomicBoolean settled=new AtomicBoolean();
                @Override public void beforeSend() {
                    try {
                        requireCurrent(subject);
                        egress.requireAllowed(provider);
                        if(!sent.compareAndSet(false,true)) { throw denied(); }
                    } catch(RuntimeException failure) { throw wrap(failure); }
                }
                @Override public void received(String providerId,Map<String,Object> usage) {
                    if(!sent.get() || providerId==null || providerId.isBlank() || providerId.length()>256
                            || usage==null || usage.isEmpty()) { return; }
                    if(settled.compareAndSet(false,true)) {
                        try { spend.received(callId,providerId,usage); }
                        catch(RuntimeException failure) { throw wrap(failure); }
                    }
                }
            };
        } catch(RuntimeException failure) { throw wrap(failure); }
    }

    private void requireCurrent(SubjectReference subject) {
        if(subject.scopes().isEmpty() || subject.expiresAtEpochSecond()<=clock.instant().getEpochSecond()) { throw denied(); }
        subjects.requireCurrent(subject);
    }

    private static String canonicalProvider(CallSpec call) {
        if(call==null) { throw denied(); }
        URI uri=call.endpoint();
        if(uri==null || !"https".equals(uri.getScheme()) || uri.getRawUserInfo()!=null
                || uri.getRawQuery()!=null || uri.getRawFragment()!=null
                || (uri.getPort()!=-1 && uri.getPort()!=443)) { throw denied(); }
        String host=uri.getHost(),path=uri.getRawPath();
        if("deepseek".equals(call.provider()) && "api.deepseek.com".equals(host)
                && ("/chat/completions".equals(path) || "/v1/chat/completions".equals(path))) { return "deepseek"; }
        if("bailian".equals(call.provider()) && "dashscope.aliyuncs.com".equals(host)
                && "/compatible-mode/v1/chat/completions".equals(path)) { return "dashscope"; }
        throw denied();
    }
    private static ChatEgressDeniedException denied() { return new ChatEgressDeniedException("chat boundary denied"); }
    private static ChatEgressDeniedException wrap(RuntimeException failure) {
        if(failure instanceof ChatEgressDeniedException denied) { return denied; }
        return new ChatEgressDeniedException("chat boundary denied",failure);
    }
}
