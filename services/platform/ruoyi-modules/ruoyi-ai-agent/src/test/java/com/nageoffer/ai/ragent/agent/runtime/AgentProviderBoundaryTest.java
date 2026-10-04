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

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.runtime.*;
import com.nageoffer.ai.ragent.runtime.exec.*;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import com.nageoffer.ai.ragent.runtime.usage.EgressPolicy;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Tag;

@Tag("dev")
class AgentProviderBoundaryTest {
    private final RunRecord run=new RunRecord("t1","r1","platform:t1:1001","1001","agent.run","RUNNING",null,"key","{}","{}","p3-core-v1",1,1,"[]",1,1,1,1,null,null,null,null,null,null,null,null,null);
    private final RunExecutionGuard guard=mock(RunExecutionGuard.class);
    private final RunAccessService access=mock(RunAccessService.class);
    private final RevocationGuard revocation=mock(RevocationGuard.class);
    private final EgressPolicy policy=new EgressPolicy();
    private final RunExecution execution=new RunExecution(run,guard);
    private final ExecutionPrincipal principal=new ExecutionPrincipal("t1","1001","platform:t1:1001",1,1,Set.of("agent.execute","kb.read"),"new-jti","platform",1,9999999999L);
    private AgentProviderBoundary boundary(){when(access.current(run,Set.of("agent.execute","kb.read"))).thenReturn(principal);return new AgentProviderBoundary(access,revocation,policy);}
    @Test void disabledEmptyAndUnlistedProvidersCannotAcquirePermit() {
        var boundary=boundary();
        assertThrows(RunApiException.class,()->boundary.enter(execution,"deepseek"));
        policy.setEnabled(true);
        assertThrows(RunApiException.class,()->boundary.enter(execution,"deepseek"));
        policy.setAllowedProviders("deepseek");
        assertThrows(RunApiException.class,()->boundary.enter(execution,"dashscope"));
        assertThrows(RunApiException.class,()->boundary.enter(execution,"synthetic"));
        verifyNoInteractions(revocation);
        verify(access,times(4)).current(run,Set.of("agent.execute","kb.read"));
    }
    @Test void exactServerModelAndEmbeddingProvidersUseCurrentPermit() {
        policy.setEnabled(true);policy.setAllowedProviders("deepseek,dashscope,synthetic");
        var boundary=boundary();var operation=new RevocationGuard.Operation(revocation,"permit","operation");
        when(revocation.enter(principal,"agent.execute","run:r1")).thenReturn(operation);
        for(var provider:Set.of("deepseek","dashscope","synthetic"))assertSame(operation,boundary.enter(execution,provider));
        verify(guard,times(3)).commitAtomic(any());verify(access,times(3)).current(run,Set.of("agent.execute","kb.read"));
        verify(revocation,times(3)).enter(principal,"agent.execute","run:r1");
    }
    @Test void currentWithdrawalRejectsBeforeAllowedProviderPermit() {
        policy.setEnabled(true);policy.setAllowedProviders("deepseek");var boundary=boundary();
        doThrow(new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN)).when(access).current(run,Set.of("agent.execute","kb.read"));
        assertThrows(RunApiException.class,()->boundary.enter(execution,"deepseek"));verifyNoInteractions(revocation);
    }
}
