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

package com.nageoffer.ai.ragent.runtime.web;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.runtime.*;
import com.nageoffer.ai.ragent.runtime.dto.AdmissionRequest;
import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RunControllerHttpTest {
    private RunAdmissionService admission;
    private MockMvc mvc;
    @BeforeEach void init() {
        admission=mock(RunAdmissionService.class);
        var authorization=mock(AiResourceAuthorizationService.class);
        @SuppressWarnings("unchecked") ObjectProvider<AiResourceAuthorizationService> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(authorization);
        mvc=MockMvcBuilders.standaloneSetup(new RunController(admission,mock(RunLifecycleService.class),new P2RuntimeProperties(),provider))
                .setControllerAdvice(new RunApiExceptionHandler()).build();
        PrincipalContext.set(new ExecutionPrincipal("t1","1001","platform:t1:1001",1,1,Set.of("run.submit"),"jti","platform",1,9999999999L));
    }
    @AfterEach void clear() { PrincipalContext.clear(); }
    @Test void defaultMvcConvertersAcceptNestedJackson2Request() throws Exception {
        when(admission.admit(any(),eq("same-key"),any())).thenReturn(new RunAdmissionService.AdmissionResult("run-test","QUEUED",Instant.EPOCH,false));
        mvc.perform(post("/internal/ai/v1/runs").header("Idempotency-Key","same-key").contentType("application/json")
                .content("{\"schemaVersion\":1,\"action\":\"rag.chat\",\"input\":{\"text\":\"测试\"},\"budget\":{\"maxTokens\":2000}}"))
                .andExpect(status().isAccepted());
        var argument=ArgumentCaptor.forClass(AdmissionRequest.class);
        verify(admission).admit(any(),eq("same-key"),argument.capture());
        assertEquals("测试",argument.getValue().input().path("text").asText());
        assertEquals(2000,argument.getValue().budget().path("maxTokens").asInt());
    }
    @Test void invalidJsonNeverAdmits() throws Exception {
        for(String body:new String[]{"{", "{\"action\":\"rag.chat\",\"action\":\"agent.run\"}","{} {}", "{\"tenantId\":\"forged\"}"}) {
            mvc.perform(post("/internal/ai/v1/runs").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(admission);
    }
    @Test void missingPrincipalNeverAdmits() throws Exception {
        PrincipalContext.clear();
        mvc.perform(post("/internal/ai/v1/runs").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(admission);
    }
}
