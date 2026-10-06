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

package com.nageoffer.ai.ragent.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.usage.EgressPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Tag;

@Tag("dev")
class ProviderGatewayHttpTest {
    @Test void malformedEmbeddingResponsesRejectWholeBatchWithoutRetry() throws Exception {
        var payload=new java.util.concurrent.atomic.AtomicReference<String>();
        var count=new AtomicInteger();var json=new ObjectMapper();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",x->{count.incrementAndGet();x.getRequestBody().readAllBytes();byte[] body=payload.get().getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(200,body.length);x.getResponseBody().write(body);x.close();});server.start();
        try {
            var gateway=new RealEmbeddingGateway("test-only",allowed(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"));
            var valid=Collections.nCopies(1536,0.1);
            var replies=List.of(
                json.writeValueAsString(Map.of("data",List.of())),
                json.writeValueAsString(Map.of("data",List.of(Map.of("index",0,"embedding",Collections.nCopies(1535,0.1))))),
                json.writeValueAsString(Map.of("data",List.of(Map.of("index",0.5,"embedding",valid)))),
                json.writeValueAsString(Map.of("data",List.of(Map.of("index",1,"embedding",valid)))),
                json.writeValueAsString(Map.of("data",List.of(Map.of("index",0,"embedding",Map.of("value",0.1))))),
                json.writeValueAsString(Map.of("data",List.of(Map.of("index",0,"embedding",Collections.nCopies(1536,"NaN"))))),
                json.writeValueAsString(Map.of("data",List.of(Map.of("index",0,"embedding",valid)))).replace("0.1","1e999"));
            for(String reply:replies) {
                int before=count.get();payload.set(reply);
                assertThrows(RuntimeException.class,()->gateway.embedBatchWithUsage(List.of("synthetic")));
                assertEquals(before+1,count.get());
            }
            payload.set(json.writeValueAsString(Map.of("data",List.of(Map.of("index",0,"embedding",valid),Map.of("index",0,"embedding",valid)))));
            int before=count.get();assertThrows(RuntimeException.class,()->gateway.embedBatchWithUsage(List.of("a","b")));assertEquals(before+1,count.get());
        } finally {server.stop(0);}
    }
    private EgressPolicy allowed(){var p=new EgressPolicy();p.setEnabled(true);p.setAllowedProviders("deepseek,dashscope");return p;}

    /**
     * 绑定事实（D02）：provider/model **只能**来自这里。
     * 断言请求体里的 `model` 等于**这个**值（而不是某个写死的默认模型），
     * 才真正证明"模型权威来自 run 绑定"。
     */
    static final String BOUND_MODEL="bound-chat-model-v1";
    static com.nageoffer.ai.ragent.runtime.config.RunConfigBinding binding(String providerId){
        return new com.nageoffer.ai.ragent.runtime.config.RunConfigBinding("t1","run-1","rag.chat",
                "rev-1",1L,providerId,BOUND_MODEL,"cat-v1","params-hash","cred-ref","op-1",java.time.Instant.EPOCH);
    }
    @Test void deniedOrMissingKeyMakesNoRequest() throws Exception {
        var count=new AtomicInteger(); var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",x->{count.incrementAndGet();x.sendResponseHeaders(500,-1);x.close();});server.start();
        try {
            var uri=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/");
            assertThrows(RunApiException.class,()->new RealChatGateway("test-only",new EgressPolicy(),uri).stream(binding("deepseek"),List.of(ChatMessage.user("test")),10,s->{}));
            assertThrows(RunApiException.class,()->new RealEmbeddingGateway("",allowed(),uri).embed("test"));
            assertEquals(0,count.get());
        } finally {server.stop(0);}
    }
    @Test void streamUsesExactModelAndPreservesFinalUsage() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var request=new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/",x->{request.set(new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] reply=("data: {\"id\":\"req-1\",\"choices\":[{\"delta\":{\"content\":\"测试\"},\"finish_reason\":null}]}\n\n"
                    +"data: {\"id\":\"req-1\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":2,\"total_tokens\":14}}\n\n"
                    +"data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200,reply.length);x.getResponseBody().write(reply);x.close();});server.start();
        try {
            StringBuilder delta=new StringBuilder();
            var result=new RealChatGateway("test-only",allowed(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/")).stream(binding("deepseek"),List.of(ChatMessage.user("synthetic")),32,delta::append);
            assertEquals("测试",delta.toString());assertEquals("req-1",result.providerRequestId());assertEquals(14,result.usageRaw().get("total_tokens"));
            assertEquals(BOUND_MODEL,new ObjectMapper().readTree(request.get()).path("model").asText());
        } finally {server.stop(0);}
    }
    @Test void interruptedResponseIsUnknownAndNeverAutomaticallyRetried() throws Exception {
        var count=new AtomicInteger(); var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",x->{count.incrementAndGet();byte[] reply="data: {\"id\":\"req-1\",\"choices\":[]}\n".getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(200,reply.length);x.getResponseBody().write(reply);x.close();});server.start();
        try {
            var gateway=new RealChatGateway("test-only",allowed(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"));
            assertThrows(RunApiException.class,()->gateway.stream(binding("deepseek"),List.of(ChatMessage.user("synthetic")),32,s->{}));assertEquals(1,count.get());
        } finally {server.stop(0);}
    }
    @Test void embeddingOrderDimensionsAndUsageAreValidated() throws Exception {
        var request=new java.util.concurrent.atomic.AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",x->{request.set(new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] reply=new ObjectMapper().writeValueAsBytes(Map.of("data",List.of(Map.of("index",1,"embedding",Collections.nCopies(1536,0.2)),Map.of("index",0,"embedding",Collections.nCopies(1536,0.1))),"usage",Map.of("total_tokens",20)));
            x.getResponseHeaders().set("x-request-id","embed-1");x.sendResponseHeaders(200,reply.length);x.getResponseBody().write(reply);x.close();});server.start();
        try {
            var result=new RealEmbeddingGateway("test-only",allowed(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/")).embedBatchWithUsage(List.of("a","b"));
            assertEquals(0.1f,result.vectors().get(0).get(0));assertEquals("embed-1",result.providerRequestId());assertEquals(20,result.usageRaw().get("total_tokens"));
            var body=new ObjectMapper().readTree(request.get());assertEquals("text-embedding-v4",body.path("model").asText());assertEquals(1536,body.path("dimensions").asInt());
        } finally {server.stop(0);}
    }
}
