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

package com.nageoffer.ai.ragent.infra.chat;

import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.model.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class RoutingLLMServiceEnvelopeDeniedTest {
    @Test void startDenialIsTerminalRatherThanTryingSecondProvider(){denied(false);}
    @Test void workerDenialIsTerminalRatherThanTryingSecondProvider(){denied(true);}
    private void denied(boolean async){
        ModelSelector selector=mock(ModelSelector.class);ModelHealthStore health=mock(ModelHealthStore.class);
        ModelTarget first=target("first","bailian"),second=target("second","deepseek");
        when(selector.selectChatCandidates(false)).thenReturn(List.of(first,second));
        ModelHealthStore.CallPermit permit=new ModelHealthStore.CallPermit(first.id(),0L);
        when(health.allowCall(first.id())).thenReturn(permit);
        when(health.allowCall(second.id())).thenReturn(new ModelHealthStore.CallPermit(second.id(),0L));
        AtomicInteger fallback=new AtomicInteger();
        ChatClient primary=new ChatClient(){public String provider(){return "bailian";}public String chat(ChatRequest request,ModelTarget target){throw new UnsupportedOperationException();}public StreamCancellationHandle streamChat(ChatRequest request,StreamCallback callback,ModelTarget target){RuntimeException denied=new IllegalStateException("wrapped",new ChatEgressDeniedException("denied"));if(async){callback.onError(denied);return StreamCancellationHandles.noop();}throw denied;}};
        ChatClient alternate=new ChatClient(){public String provider(){return "deepseek";}public String chat(ChatRequest request,ModelTarget target){throw new UnsupportedOperationException();}public StreamCancellationHandle streamChat(ChatRequest request,StreamCallback callback,ModelTarget target){fallback.incrementAndGet();callback.onContent("fallback");return StreamCancellationHandles.noop();}};
        RoutingLLMService routing=new RoutingLLMService(selector,health,new ModelRoutingExecutor(health),new LlmFirstPacketProbe(),List.of(primary,alternate));
        StreamCallback callback=new StreamCallback(){public void onContent(String s){}public void onComplete(){}public void onError(Throwable t){}};
        assertThatThrownBy(()->routing.streamChat(ChatRequest.builder().build(),callback)).isInstanceOf(ChatEgressDeniedException.class);
        assertThat(fallback).hasValue(0);verify(health,never()).markFailure(first.id());verify(health,never()).allowCall(second.id());verify(health).releaseHalfOpenPermit(permit);
    }
    static ModelTarget target(String id,String provider){AIModelProperties.ModelCandidate c=new AIModelProperties.ModelCandidate();c.setProvider(provider);return new ModelTarget(id,c,new AIModelProperties.ProviderConfig(),50L);}
}
