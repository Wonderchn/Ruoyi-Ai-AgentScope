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
import com.nageoffer.ai.ragent.runtime.usage.EgressPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** Human-selected DeepSeek model; provider uncertainty never triggers fallback or retry. */
@Component
@ConditionalOnProperty(name="p2.chat.mode",havingValue="real",matchIfMissing=true)
public class RealChatGateway implements ChatGateway {
    private final String key;
    private final URI endpoint;
    private final EgressPolicy egress;
    private com.nageoffer.ai.ragent.runtime.usage.ProviderSpendEnvelope spend;
    private boolean providerEndpoint;
    @Autowired
    public void configureSpend(com.nageoffer.ai.ragent.runtime.usage.ProviderSpendEnvelope spend) {this.spend=spend;}
    private final ObjectMapper json=new ObjectMapper();
    @Autowired
    public RealChatGateway(@Value("${p2.providers.deepseek.api-key:${DEEPSEEK_API_KEY:}}") String key,EgressPolicy egress) {
        this(key,egress,URI.create("https://api.deepseek.com/chat/completions"),true);
    }
    RealChatGateway(String key,EgressPolicy egress,URI endpoint) {
        this(key,egress,endpoint,false);
        if(!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())) throw new IllegalArgumentException("test endpoint must be loopback");
    }
    private RealChatGateway(String key,EgressPolicy egress,URI endpoint,boolean providerEndpoint) {this.key=key;this.egress=egress;this.endpoint=endpoint;this.providerEndpoint=providerEndpoint;}
    private String reserve(String body,int maxTokens) {
        if(key==null || key.isBlank()) throw ProviderHttp.unavailable();
        if(!providerEndpoint) return null;
        if(spend==null) throw ProviderHttp.unavailable();
        return spend.reserve(provider(),model(),body.getBytes(StandardCharsets.UTF_8).length,maxTokens);
    }
    public String provider() {return "deepseek";}
    public String model() {return "deepseek-flash";}
    public ChatResult stream(List<ChatMessage> messages,int maxTokens,Consumer<String> onDelta) {
        egress.requireAllowed(provider());
        var serialized=messages.stream().map(m->Map.of("role",m.getRole().name().toLowerCase(Locale.ROOT),"content",m.getContent())).toList();
        if(maxTokens<1 || maxTokens>8192 || serialized.stream().mapToInt(m->m.get("content").length()).sum()>65536) throw ProviderHttp.unavailable();
        HttpURLConnection connection=null;
        try {
            String body=json.writeValueAsString(Map.of("model",model(),"messages",serialized,"max_tokens",maxTokens,"thinking",Map.of("type","disabled"),"stream",true,"stream_options",Map.of("include_usage",true)));
            String spendId=reserve(body,maxTokens);
            connection=ProviderHttp.open(endpoint,key,body);
            int status=connection.getResponseCode();
            if(status!=200) {
                if(spendId!=null) spend.received(spendId,null,Map.of("httpStatus",status));
                throw ProviderHttp.unavailable();
            }
            String id=null,finish=null; Map<String,Object> usage=null;
            StringBuilder content=new StringBuilder(); boolean done=false;
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(120);
            int consumed=0;
            try(var reader=new BufferedReader(new InputStreamReader(connection.getInputStream(),StandardCharsets.UTF_8))) {
                for(String line;(line=ProviderHttp.line(reader,65536))!=null;) {
                    if((consumed+=line.length())>2097152 || System.nanoTime()>deadline || Thread.currentThread().isInterrupted()) throw ProviderHttp.unavailable();
                    if(!line.startsWith("data:")) continue;
                    String value=line.substring(5).trim();
                    if("[DONE]".equals(value)){done=true;break;}
                    var chunk=json.readTree(value);
                    if(chunk.hasNonNull("id")) {String next=chunk.path("id").asText(); if(id!=null&&!id.equals(next))throw ProviderHttp.unavailable();id=next;}
                    if(chunk.path("usage").isObject()) usage=json.convertValue(chunk.path("usage"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
                    for(var choice:chunk.path("choices")) {
                        String delta=choice.path("delta").path("content").asText("");
                        if(!delta.isEmpty()){content.append(delta);onDelta.accept(delta);}
                        if(choice.hasNonNull("finish_reason")) finish=choice.path("finish_reason").asText();
                    }
                }
            }
            if(!done || finish==null || id==null) throw ProviderHttp.unavailable();
            if(spendId!=null) spend.received(spendId,id,usage);
            return new ChatResult(content.toString(),id,usage,finish);
        } catch(IOException e) {throw ProviderHttp.unavailable();}
        finally {if(connection!=null) connection.disconnect();}
    }
}
