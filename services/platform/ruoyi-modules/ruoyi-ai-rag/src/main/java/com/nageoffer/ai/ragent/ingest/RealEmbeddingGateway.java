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
import com.nageoffer.ai.ragent.runtime.usage.EgressPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Mainland DashScope text-embedding-v4, explicit physical dimension 1536. */
@Component
@ConditionalOnProperty(name="p2.embedding.mode",havingValue="real",matchIfMissing=true)
public class RealEmbeddingGateway implements EmbeddingGateway {
    private final String key;
    private final URI endpoint;
    private final EgressPolicy egress;
    private com.nageoffer.ai.ragent.runtime.usage.ProviderSpendEnvelope spend;
    private boolean providerEndpoint;
    @Autowired
    public void configureSpend(com.nageoffer.ai.ragent.runtime.usage.ProviderSpendEnvelope spend) {this.spend=spend;}
    private final ObjectMapper json=new ObjectMapper();
    @Autowired
    public RealEmbeddingGateway(@Value("${p2.providers.dashscope.api-key:${DASHSCOPE_API_KEY:}}") String key,EgressPolicy egress) {
        this(key,egress,URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings"),true);
    }
    RealEmbeddingGateway(String key,EgressPolicy egress,URI endpoint) {
        this(key,egress,endpoint,false);
        if(!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())) throw new IllegalArgumentException("test endpoint must be loopback");
    }
    private RealEmbeddingGateway(String key,EgressPolicy egress,URI endpoint,boolean providerEndpoint) {this.key=key;this.egress=egress;this.endpoint=endpoint;this.providerEndpoint=providerEndpoint;}
    private String reserve(String body,int maxTokens) {
        if(key==null || key.isBlank()) throw ProviderHttp.unavailable();
        if(!providerEndpoint) return null;
        if(spend==null) throw ProviderHttp.unavailable();
        return spend.reserve(provider(),model(),body.getBytes(StandardCharsets.UTF_8).length,maxTokens);
    }
    public String provider(){return "dashscope";}
    public String model(){return "text-embedding-v4";}
    public int dimension(){return 1536;}
    public List<Float> embed(String text){return embedBatch(List.of(text)).get(0);}
    public List<List<Float>> embedBatch(List<String> texts){return embedBatchWithUsage(texts).vectors();}
    public EmbeddingResult embedBatchWithUsage(List<String> texts) {
        egress.requireAllowed(provider());
        if(texts==null || texts.isEmpty() || texts.size()>10 || texts.stream().anyMatch(t->t==null||t.length()>8192)) throw ProviderHttp.unavailable();
        HttpURLConnection connection=null;
        try {
            String body=json.writeValueAsString(Map.of("model",model(),"input",texts,"dimensions",dimension(),"encoding_format","float"));
            String spendId=reserve(body,0);
            connection=ProviderHttp.open(endpoint,key,body);
            int status=connection.getResponseCode();
            if(status!=200) {
                if(spendId!=null) spend.received(spendId,null,Map.of("httpStatus",status));
                throw ProviderHttp.unavailable();
            }
            byte[] bytes;
            try(var input=connection.getInputStream()){bytes=input.readNBytes(2097153);}
            if(bytes.length>2097152) throw ProviderHttp.unavailable();
            var root=json.readTree(new String(bytes,StandardCharsets.UTF_8));
            if(!root.path("data").isArray() || root.path("data").size()!=texts.size()) throw ProviderHttp.unavailable();
            List<List<Float>> vectors=new ArrayList<>(Collections.nCopies(texts.size(),null));
            for(var item:root.path("data")) {
                var position=item.path("index");
                if(!position.isIntegralNumber() || !position.canConvertToInt() || !item.path("embedding").isArray()) throw ProviderHttp.unavailable();
                int index=position.intValue();
                if(index<0||index>=texts.size()||vectors.get(index)!=null) throw ProviderHttp.unavailable();
                List<Float> vector=new ArrayList<>();
                for(var value:item.path("embedding")){if(!value.isNumber())throw ProviderHttp.unavailable(); vector.add(value.floatValue());}
                validateVector(vector,dimension());vectors.set(index,vector);
            }
            String requestId=connection.getHeaderField("x-request-id");
            if(requestId==null) requestId=root.path("request_id").asText(null);
            Map<String,Object> usage=root.path("usage").isObject()?json.convertValue(root.path("usage"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}):null;
            if(spendId!=null) spend.received(spendId,requestId,usage);
            return new EmbeddingResult(vectors,requestId,usage);
        }catch(IOException e){throw ProviderHttp.unavailable();}
        finally {if(connection!=null)connection.disconnect();}
    }
    static void validateVector(List<Float> vector,int dimension) {
        if(vector==null || vector.size()!=dimension || vector.stream().anyMatch(v->v==null||!Float.isFinite(v))) throw ProviderHttp.unavailable();
    }
}
