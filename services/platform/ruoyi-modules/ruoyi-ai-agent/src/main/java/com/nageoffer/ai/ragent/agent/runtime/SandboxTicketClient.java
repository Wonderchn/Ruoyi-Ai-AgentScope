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
import com.nageoffer.ai.ragent.runtime.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.Map;

@Component
@ConditionalOnProperty(name="p3.enabled",havingValue="true")
public class SandboxTicketClient {
    public static final String TARGET="http://127.0.0.1:19520/tickets";
    private final P3Properties properties;
    private final URI endpoint;
    @org.springframework.beans.factory.annotation.Autowired
    public SandboxTicketClient(P3Properties properties){this.properties=properties;this.endpoint=URI.create(TARGET);}
    SandboxTicketClient(P3Properties properties,URI endpoint){
        if(!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost()) || !"/tickets".equals(endpoint.getPath()) || endpoint.getUserInfo()!=null || endpoint.getQuery()!=null || endpoint.getFragment()!=null) throw new IllegalArgumentException("test endpoint must be bounded loopback");
        this.properties=properties;this.endpoint=endpoint;
    }
    public JsonNode create(String key,String hash,JsonNode args){return request("POST","",AgentLedger.json(Map.of("operationKey",key,"argsHash",hash,"args",args)));}
    public JsonNode query(String key){return request("GET","/"+safe(key),null);}
    private static String safe(String key){if(key==null || !key.matches("[A-Za-z0-9_-]{1,128}")) throw new RunApiException(RunErrorCode.BAD_REQUEST);return key;}
    private JsonNode request(String method,String path,String body) {
        var config=properties.getSandbox();
        if(!config.isEnabled() || !config.getNamespace().matches("[A-Za-z0-9_-]{1,64}") || config.getCredential().isBlank()) throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT,"SANDBOX_CLOSED");
        HttpURLConnection connection=null;
        try {
            connection=(HttpURLConnection)URI.create(endpoint.toString()+path).toURL().openConnection();
            connection.setRequestMethod(method);connection.setConnectTimeout(2000);connection.setReadTimeout(2000);connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Authorization","Bearer "+config.getCredential());connection.setRequestProperty("X-Sandbox-Namespace",config.getNamespace());
            if(body!=null){connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");byte[] bytes=body.getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(bytes.length);try(var output=connection.getOutputStream()){output.write(bytes);}}
            int status=connection.getResponseCode();
            if(status!=200 && status!=201) throw new UnknownOutcome();
            byte[] bytes;try(var input=connection.getInputStream()){bytes=input.readNBytes(65537);}
            if(bytes.length>65536) throw new UnknownOutcome();
            var node=new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
            if(node==null || !node.isObject()) throw new UnknownOutcome();
            return node;
        } catch(IOException e){throw new UnknownOutcome();}
        finally {if(connection!=null) connection.disconnect();}
    }
    public static void verifyFound(JsonNode result,AgentLedger.Action action) {
        if(!"FOUND".equals(result.path("state").asText()) || !result.path("final").isBoolean() || !result.path("final").asBoolean() || !action.operationKey().equals(result.path("operationKey").asText()) || !action.argsHash().equals(result.path("argsHash").asText()) || !result.path("externalId").asText().matches("ticket-[a-f0-9]{32}")) throw new UnknownOutcome();
    }
    public JsonNode verifyFound(JsonNode result,String key,String hash) {
        if(!"FOUND".equals(result.path("state").asText()) || !result.path("final").isBoolean() || !result.path("final").asBoolean() || !key.equals(result.path("operationKey").asText()) || !hash.equals(result.path("argsHash").asText()) || !result.path("externalId").asText().matches("ticket-[a-f0-9]{32}")) throw new UnknownOutcome();
        return result;
    }
    public static final class UnknownOutcome extends RuntimeException {public UnknownOutcome(){super("SANDBOX_OUTCOME_UNKNOWN");}}
}
