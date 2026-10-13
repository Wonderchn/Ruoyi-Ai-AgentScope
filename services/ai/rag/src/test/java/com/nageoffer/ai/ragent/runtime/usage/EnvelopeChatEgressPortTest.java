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
import com.nageoffer.ai.ragent.infra.chat.ChatEgressPort;
import com.nageoffer.ai.ragent.infra.chat.ChatEgressDeniedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EnvelopeChatEgressPortTest {
    final ProviderSpendEnvelope spend=mock(ProviderSpendEnvelope.class);
    final EgressPolicy egress=new EgressPolicy();
    final AtomicInteger checks=new AtomicInteger();
    boolean rejectFacts;
    final AtomicReference<EnvelopeChatEgressPort.SubjectReference> subject=new AtomicReference<>();
    final Clock clock=Clock.fixed(Instant.ofEpochSecond(100),ZoneOffset.UTC);
    final EnvelopeChatEgressPort bridge=new EnvelopeChatEgressPort(egress,spend,ref->{checks.incrementAndGet();subject.set(ref);if(rejectFacts)throw new IllegalStateException("current facts unavailable");},clock);
    @AfterEach void cleanup(){PrincipalContext.clear();}
    void enabled(){egress.setEnabled(true);egress.setAllowedProviders("deepseek,dashscope");when(spend.reserve(anyString(),anyString(),anyLong(),anyInt())).thenReturn("call-1");PrincipalContext.set(principal(200,Set.of("run.submit")));}
    @Test void missingAndExpiredAndEmptyScopesAreDeniedWithoutReserve(){
        assertThatThrownBy(()->bridge.authorize(call("deepseek","https://api.deepseek.com/chat/completions"))).isInstanceOf(ChatEgressDeniedException.class);
        PrincipalContext.set(principal(100,Set.of("run.submit")));assertThatThrownBy(()->bridge.authorize(call("deepseek","https://api.deepseek.com/chat/completions"))).isInstanceOf(ChatEgressDeniedException.class);
        PrincipalContext.set(principal(200,Set.of()));assertThatThrownBy(()->bridge.authorize(call("deepseek","https://api.deepseek.com/chat/completions"))).isInstanceOf(ChatEgressDeniedException.class);verifyNoInteractions(spend);
    }
    @Test void unknownCurrentFactsAndDisabledEgressDenyBeforeReserve(){
        enabled();rejectFacts=true;assertThatThrownBy(()->bridge.authorize(call("deepseek","https://api.deepseek.com/chat/completions"))).isInstanceOf(ChatEgressDeniedException.class);
        rejectFacts=false;egress.setEnabled(false);assertThatThrownBy(()->bridge.authorize(call("deepseek","https://api.deepseek.com/chat/completions"))).isInstanceOf(ChatEgressDeniedException.class);verify(spend,never()).reserve(anyString(),anyString(),anyLong(),anyInt());
    }
    @Test void unpairedLegacyHolderIsDeniedEvenWhenCurrentFactsWouldAllow(){
        enabled();ExecutionPrincipal p=PrincipalContext.get();PrincipalContext.clear();PrincipalContext.restore(p);
        assertThatThrownBy(()->bridge.authorize(call("deepseek","https://api.deepseek.com/chat/completions"))).isInstanceOf(ChatEgressDeniedException.class);verify(spend,never()).reserve(anyString(),anyString(),anyLong(),anyInt());assertThat(checks).hasValue(0);
    }
    @Test void unsupportedOrSpoofedEndpointsCannotBorrowProviderBudget(){
        enabled();for(ChatEgressPort.CallSpec spec:java.util.List.of(call("siliconflow","https://api.deepseek.com/chat/completions"),call("aihubmix","https://api.deepseek.com/chat/completions"),call("ollama","http://localhost:11434/api/chat"),call("bailian","https://evil.test/compatible-mode/v1/chat/completions"),call("deepseek","https://api.deepseek.com.evil.test/chat/completions"),call("deepseek","https://user@api.deepseek.com/chat/completions"),call("deepseek","https://api.deepseek.com/chat/completions?route=evil"),call("deepseek","https://api.deepseek.com:444/chat/completions"))){assertThatThrownBy(()->bridge.authorize(spec)).isInstanceOf(ChatEgressDeniedException.class);}verify(spend,never()).reserve(anyString(),anyString(),anyLong(),anyInt());
    }
    @Test void canonicalBailianUsesDashscopeAndFinalBodyLimitsOnly(){
        enabled();ChatEgressPort.Lease lease=bridge.authorize(call("bailian","https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"));
        verify(spend).reserve("dashscope","model",4096L,512);assertThat(subject.get().tenantId()).isEqualTo("tenant-a");assertThat(subject.get().membershipId()).isEqualTo("platform:tenant-a:42");assertThat(subject.get().scopes()).containsExactly("run.submit");assertThatThrownBy(()->subject.get().scopes().add("skill.write")).isInstanceOf(UnsupportedOperationException.class);
        lease.beforeSend();lease.received("provider-id",Map.of("total_tokens",9));lease.received("provider-id",Map.of("total_tokens",9));verify(spend,times(1)).received("call-1","provider-id",Map.of("total_tokens",9));assertThat(checks).hasValue(2);
    }
    @Test void leaseRevalidatesFactsWithoutHolderAndNeverReplaysSend(){
        enabled();ChatEgressPort.Lease lease=bridge.authorize(call("deepseek","https://api.deepseek.com/chat/completions"));PrincipalContext.clear();lease.beforeSend();assertThatThrownBy(lease::beforeSend).isInstanceOf(ChatEgressDeniedException.class);
        enabled();ChatEgressPort.Lease denied=bridge.authorize(call("deepseek","https://api.deepseek.com/chat/completions"));rejectFacts=true;assertThatThrownBy(denied::beforeSend).isInstanceOf(ChatEgressDeniedException.class);denied.received("id",Map.of("total_tokens",9));verify(spend,never()).received(anyString(),anyString(),anyMap());
    }
    @Test void budgetFailureIsTerminalAndUnknownReceiptRetainsReservation(){
        enabled();when(spend.reserve(anyString(),anyString(),anyLong(),anyInt())).thenThrow(new IllegalStateException("budget unavailable"));assertThatThrownBy(()->bridge.authorize(call("deepseek","https://api.deepseek.com/chat/completions"))).isInstanceOf(ChatEgressDeniedException.class);verify(spend,never()).received(anyString(),anyString(),anyMap());
    }
    @Test void absentDependenciesDenyRatherThanRestoringBareChat(){
        enabled();EnvelopeChatEgressPort absent=new EnvelopeChatEgressPort(egress,null,null,clock);assertThatThrownBy(()->absent.authorize(call("deepseek","https://api.deepseek.com/chat/completions"))).isInstanceOf(ChatEgressDeniedException.class);verify(spend,never()).reserve(anyString(),anyString(),anyLong(),anyInt());
    }
    static ChatEgressPort.CallSpec call(String provider,String url){return new ChatEgressPort.CallSpec(provider,"model",URI.create(url),4096,512);}
    static ExecutionPrincipal principal(long expiry,Set<String> scopes){return new ExecutionPrincipal("tenant-a","42","platform:tenant-a:42",3,4,scopes,"test","platform",1,expiry);}
}
