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

import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.trace.RagStreamTraceSupport;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import okhttp3.*;
import okio.Buffer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@Tag("dev")
class AbstractOpenAIStyleChatClientEnvelopeTest {
    private static final String SYNC = "{\"id\":\"provider-sync\",\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":2,\"total_tokens\":9}}";
    private static final String STREAM = "data: {\"id\":\"provider-stream\",\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: {\"id\":\"provider-stream\",\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":2,\"total_tokens\":9}}\n\ndata: [DONE]\n\n";

    @Test void missingPortDeniesAllFiveClientsBeforeSyncOrStreamSend() {
        for (AbstractOpenAIStyleChatClient client : List.of(new DeepSeekChatClient(),new BaiLianChatClient(),new SiliconFlowChatClient(),new AIHubMixChatClient(),new OllamaChatClient())) {
            Harness h=new Harness(client,SYNC,Runnable::run);
            assertThatThrownBy(()->client.chat(request(null),target(client.provider()))).isInstanceOf(ChatEgressDeniedException.class);
            assertThatThrownBy(()->client.streamChat(request(null),h.callback,target(client.provider()))).isInstanceOf(ChatEgressDeniedException.class);
            assertThat(h.sends).hasValue(0);
        }
    }

    @Test void deniedLeaseNeverExecutesAndDoesNotSettle() {
        Harness h=new Harness(new DeepSeekChatClient(),SYNC,Runnable::run);
        h.port(call->{throw new ChatEgressDeniedException("denied");});
        assertThatThrownBy(()->h.client.chat(request(512),target("deepseek"))).isInstanceOf(ChatEgressDeniedException.class);
        assertThatThrownBy(()->h.client.streamChat(request(512),h.callback,target("deepseek"))).isInstanceOf(ChatEgressDeniedException.class);
        assertThat(h.sends).hasValue(0);
    }

    @Test void finalBodyOutputAndBytesAreTheReservationInputsWithoutMutatingRequest() {
        for(Integer max : java.util.Arrays.asList(null,1,512,8192)) {
            Harness h=new Harness(new DeepSeekChatClient(),SYNC,Runnable::run);
            RecordingPort port=new RecordingPort();h.port(port);
            ChatRequest req=request(max);
            assertThat(h.client.chat(req,target("deepseek"))).isEqualTo("ok");
            assertThat(port.call).isNotNull();
            assertThat(port.call.maxOutputTokens()).isEqualTo(max==null?2048:max);
            assertThat(JsonParser.parseString(h.body).getAsJsonObject().get("max_tokens").getAsInt()).isEqualTo(port.call.maxOutputTokens());
            assertThat(port.call.inputBytes()).isEqualTo(h.body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            assertThat(req.getMaxTokens()).isEqualTo(max);
            assertThat(port.before).hasValue(1);assertThat(port.receipts).hasValue(1);
            assertThat(port.id).isEqualTo("provider-sync");assertThat(port.usage.get("total_tokens").toString()).isEqualTo("9");
        }
    }

    @Test void invalidOutputLimitsDenyBeforeAuthorizationOrSend() {
        for(int max : new int[]{0,-1,8193,Integer.MAX_VALUE}) {
            Harness h=new Harness(new DeepSeekChatClient(),SYNC,Runnable::run);
            AtomicInteger auth=new AtomicInteger();h.port(call->{auth.incrementAndGet();throw new AssertionError("must reject first");});
            assertThatThrownBy(()->h.client.chat(request(max),target("deepseek"))).isInstanceOf(ChatEgressDeniedException.class);
            assertThatThrownBy(()->h.client.streamChat(request(max),h.callback,target("deepseek"))).isInstanceOf(ChatEgressDeniedException.class);
            assertThat(auth).hasValue(0);assertThat(h.sends).hasValue(0);
        }
    }

    @Test void finishReasonDoesNotDiscardUsageOnlyTailAndReceiptHappensOnce() {
        Harness h=new Harness(new DeepSeekChatClient(),STREAM,Runnable::run);
        RecordingPort port=new RecordingPort();h.port(port);
        h.client.streamChat(request(512),h.callback,target("deepseek"));
        assertThat(h.sends).hasValue(1);assertThat(port.before).hasValue(1);assertThat(port.receipts).hasValue(1);
        assertThat(port.id).isEqualTo("provider-stream");assertThat(port.usage.get("total_tokens").toString()).isEqualTo("9");
        assertThat(h.completed).hasValue(1);assertThat(h.errors).isEmpty();
        assertThat(JsonParser.parseString(h.body).getAsJsonObject().getAsJsonObject("stream_options").get("include_usage").getAsBoolean()).isTrue();
    }

    @Test void workerDenialAndCancelBeforeSendHaveZeroNetworkAndNoReceipt() {
        List<Runnable> jobs=new ArrayList<>();Harness h=new Harness(new DeepSeekChatClient(),STREAM,jobs::add);
        RecordingPort port=new RecordingPort();h.port(port);port.denySend=true;
        h.client.streamChat(request(512),h.callback,target("deepseek"));jobs.remove(0).run();
        assertThat(h.sends).hasValue(0);assertThat(port.receipts).hasValue(0);assertThat(h.errors).hasSize(1);
        port.denySend=false;h.errors.clear();
        StreamCancellationHandle handle=h.client.streamChat(request(512),h.callback,target("deepseek"));handle.cancel();jobs.remove(0).run();
        assertThat(h.sends).hasValue(0);assertThat(port.receipts).hasValue(0);
    }

    @Test void interruptedOrUsageMissingStreamDoesNotFabricateReceipt() {
        for(String response : List.of("data: {\"id\":\"x\",\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n", "data: {\"id\":\"x\",\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n")) {
            Harness h=new Harness(new DeepSeekChatClient(),response,Runnable::run);RecordingPort port=new RecordingPort();h.port(port);
            h.client.streamChat(request(512),h.callback,target("deepseek"));assertThat(port.receipts).hasValue(0);
        }
    }

    @Test void callbackFailureCannotBeSwallowedAndSettledAsSuccess() {
        Harness h=new Harness(new DeepSeekChatClient(),STREAM,Runnable::run);RecordingPort port=new RecordingPort();h.port(port);
        StreamCallback bad=new StreamCallback(){public void onContent(String s){throw new IllegalStateException("callback failed");}public void onComplete(){h.completed.incrementAndGet();}public void onError(Throwable t){h.errors.add(t);}};
        h.client.streamChat(request(512),bad,target("deepseek"));assertThat(port.receipts).hasValue(0);assertThat(h.completed).hasValue(0);assertThat(h.errors).hasSize(1);
    }

    static ChatRequest request(Integer max){return ChatRequest.builder().maxTokens(max).build();}
    static ModelTarget target(String id){AIModelProperties.ModelCandidate model=new AIModelProperties.ModelCandidate();model.setProvider(id);model.setModel("approved-model");AIModelProperties.ProviderConfig cfg=new AIModelProperties.ProviderConfig();cfg.setApiKey("test-only");cfg.setUrl("https://api.deepseek.com");cfg.setEndpoints(Map.of("chat","/chat/completions"));return new ModelTarget("test",model,cfg,null);}
    static final class RecordingPort implements ChatEgressPort {
        CallSpec call;AtomicInteger before=new AtomicInteger(),receipts=new AtomicInteger();String id;Map<String,Object> usage;boolean denySend;
        public Lease authorize(CallSpec spec){call=spec;return new Lease(){public void beforeSend(){before.incrementAndGet();if(denySend)throw new ChatEgressDeniedException("changed");}public void received(String providerId,Map<String,Object> raw){receipts.incrementAndGet();id=providerId;usage=raw;}};}
    }
    static final class Harness {
        final AbstractOpenAIStyleChatClient client;final AtomicInteger sends=new AtomicInteger(),completed=new AtomicInteger();final List<Throwable> errors=new ArrayList<>();String body;
        final StreamCallback callback=new StreamCallback(){public void onContent(String s){}public void onComplete(){completed.incrementAndGet();}public void onError(Throwable t){errors.add(t);}};
        Harness(AbstractOpenAIStyleChatClient client,String response,Executor executor){this.client=client;OkHttpClient http=new OkHttpClient.Builder().addInterceptor(chain->{sends.incrementAndGet();Buffer b=new Buffer();chain.request().body().writeTo(b);body=b.readUtf8();return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(ResponseBody.create(response,MediaType.get("application/json"))).build();}).build();ReflectionTestUtils.setField(client,"syncHttpClient",http);ReflectionTestUtils.setField(client,"streamingHttpClient",http);ReflectionTestUtils.setField(client,"modelStreamExecutor",executor);ReflectionTestUtils.setField(client,"streamTraceSupport",(RagStreamTraceSupport)(a,b)->RagStreamTraceSupport.NOOP_SPAN);}
        void port(ChatEgressPort p){try{ReflectionTestUtils.setField(client,"chatEgressPort",p);}catch(IllegalArgumentException missingOnBaseline){/* Baseline behavior red must run rather than fail to compile. */}}
    }
}
