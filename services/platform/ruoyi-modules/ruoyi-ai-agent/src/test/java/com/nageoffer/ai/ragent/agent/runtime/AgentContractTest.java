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

import com.nageoffer.ai.ragent.runtime.*;
import com.nageoffer.ai.ragent.runtime.dto.AdmissionRequest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Tag;

@Tag("dev")
class AgentContractTest {
    private AdmissionRequest request(String input,String version,String refs,String retry) throws Exception {
        return CanonicalJson.strictMapper().readValue("{\"schemaVersion\":1,\"action\":\"agent.run\",\"agentVersion\":\""+version+"\",\"input\":"+input+",\"resourceRefs\":"+refs+(retry==null?"":",\"retryOf\":\""+retry+"\"")+"}",AdmissionRequest.class);
    }
    @Test void validReadAndSandboxHaveFrozenPurposeAndResources() throws Exception {
        AgentContract.validateShape(request("{\"text\":\"question\",\"mode\":\"read\"}","core-v1","[{\"type\":\"knowledge_base\",\"id\":\"kb1\"}]",null));
        AgentContract.validateShape(request("{\"text\":\"question\",\"mode\":\"sandbox\",\"ticket\":{\"title\":\"synthetic\",\"details\":\"test\"}}","core-v1","[{\"type\":\"knowledge_base\",\"id\":\"kb1\"}]",null));
    }
    @Test void rejectsEndpointCredentialUnknownVersionAndEmptyScope() throws Exception {
        for(String field:new String[]{"url","apiKey","tenantId","operationKey","model","tool"}) {
            var request=request("{\"text\":\"question\",\"mode\":\"read\",\""+field+"\":\"untrusted\"}","core-v1","[{\"type\":\"knowledge_base\",\"id\":\"kb1\"}]",null);
            assertThrows(RunApiException.class,()->AgentContract.validateShape(request));
        }
        var empty=request("{\"text\":\"question\",\"mode\":\"read\"}","core-v1","[]",null);
        assertThrows(RunApiException.class,()->AgentContract.validateShape(empty));
        var version=request("{\"text\":\"question\",\"mode\":\"read\"}","arbitrary","[{\"type\":\"knowledge_base\",\"id\":\"kb1\"}]",null);
        assertThrows(RunApiException.class,()->AgentContract.validateShape(version));
    }
    @Test void inheritanceRequiresSandboxRetryAndExactTicketShape() throws Exception {
        var missingParent=request("{\"text\":\"question\",\"mode\":\"sandbox\",\"ticket\":{\"title\":\"t\",\"details\":\"d\"},\"inheritActionId\":\"act-00000000000000000000000000000000\"}","core-v1","[{\"type\":\"knowledge_base\",\"id\":\"kb1\"}]",null);
        assertThrows(RunApiException.class,()->AgentContract.validateShape(missingParent));
        assertThrows(RunApiException.class,()->AgentContract.ticket(AgentModelAdapter.parse("{\"title\":\"t\",\"details\":\"d\",\"url\":\"untrusted\"}")));
    }
    @Test void modelProtocolRejectsDuplicateTrailingAndUnknownTools() {
        assertThrows(RunApiException.class,()->AgentModelAdapter.parse("{\"kind\":\"final\",\"kind\":\"tool\"}"));
        assertThrows(RunApiException.class,()->AgentModelAdapter.parse("{} {}"));
        assertThrows(RunApiException.class,()->AgentModelAdapter.validateReply(AgentModelAdapter.parse("{\"kind\":\"tool\",\"tool\":\"shell\",\"args\":{}}")));
        assertThrows(RunApiException.class,()->AgentModelAdapter.validateReply(AgentModelAdapter.parse("{\"kind\":\"final\",\"answer\":\"ok\",\"reasoning\":\"private\"}")));
    }
    @Test void productionPolicyAndExtensionsAreClosed() {
        var properties=new P3Properties();assertFalse(properties.isEnabled());assertFalse(properties.isSyntheticModel());
        assertFalse(properties.getSandbox().isEnabled());assertFalse(properties.getApproval().isInitiatorEnabled());
    }
}
