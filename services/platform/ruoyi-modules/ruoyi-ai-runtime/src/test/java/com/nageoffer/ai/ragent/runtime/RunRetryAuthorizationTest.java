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

package com.nageoffer.ai.ragent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.dto.AdmissionRequest;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Tag;

@Tag("dev")
class RunRetryAuthorizationTest {
    private final RunLedgerDao dao=mock(RunLedgerDao.class);
    private final RunAccessService access=mock(RunAccessService.class);
    private final PlatformTransactionManager transactions=mock(PlatformTransactionManager.class);
    private final ExecutionPrincipal principal=new ExecutionPrincipal("t1","1001","platform:t1:1001",1,1,
            Set.of("run.submit"),"fresh-jti","platform",1,9999999999L);
    private RunAdmissionService admission;
    private AdmissionRequest request;

    @SuppressWarnings("unchecked") @BeforeEach void setup() throws Exception {
        admission=new RunAdmissionService(dao,mock(RunEventAppender.class),new P2RuntimeProperties(),transactions,mock(ObjectProvider.class));
        ReflectionTestUtils.setField(admission,"access",access);
        // D02/C1.2：受理必须在写入前取到发布权威（否则拒绝新受理）。这里给固定事实，
        // 使本类继续只测"重试授权"这一件事，不把权威读库混进判据。
        admission.configureModelAuthority(action -> new com.nageoffer.ai.ragent.runtime.config.EngineModelAuthority.PublishedModel(
                "t1","rev-1",1L,"deepseek","bound-model-v1","cat-v1","params-hash","cred-ref","op-1",Instant.EPOCH));
        request=new AdmissionRequest(1,"rag.chat",null,null,new ObjectMapper().readTree("{\"text\":\"synthetic\"}"),
                List.of(new AdmissionRequest.ResourceRef("knowledge_base","kb1")),null,null,"source");
    }
    private RunRecord parent(String status,String action) {
        return new RunRecord("t1","source","platform:t1:1001","1001",action,status,
                CanonicalJson.requestHash(request),"same-key","{}","{}","p2-v1",1,1,"[]",1,1,1,1,
                null,null,null,null,null,null,Instant.now(),null,null);
    }
    private void noAdmissionSideEffects() {
        verify(dao,never()).findByIdempotency(anyString(),anyString(),anyString(),anyString());
        verify(dao,never()).lockTenantBudget(anyString(),anyLong());
        verifyNoInteractions(transactions);
    }
    @Test void absentOrOtherTenantSourceCannotReachIdempotencyOrReserve() {
        when(dao.findRun("t1","source")).thenReturn(Optional.empty());
        assertThrows(RunApiException.class,()->admission.admit(principal,"same-key",request));
        noAdmissionSideEffects();verifyNoInteractions(access);
    }
    @Test void currentSourceDenialIsCheckedBeforeKnownIdempotencyKey() {
        var source=parent("SUCCEEDED","rag.chat");when(dao.findRun("t1","source")).thenReturn(Optional.of(source));
        doThrow(new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN)).when(access).visible(principal,source);
        assertThrows(RunApiException.class,()->admission.admit(principal,"same-key",request));
        noAdmissionSideEffects();
    }
    @Test void ordinaryRetryRequiresTerminalSourceAndMatchingAction() {
        for(var source:List.of(parent("NEEDS_RECONCILIATION","rag.chat"),parent("SUCCEEDED","document.ingest"))) {
            when(dao.findRun("t1","source")).thenReturn(Optional.of(source));
            assertThrows(RunApiException.class,()->admission.admit(principal,"same-key",request));
            verify(access).visible(principal,source);
        }
        noAdmissionSideEffects();
    }
    @Test void authorizedTerminalSourceCanReplayAfterCurrentCaptureAndVisibility() {
        var source=parent("SUCCEEDED","rag.chat");when(dao.findRun("t1","source")).thenReturn(Optional.of(source));
        when(access.capture(principal,request.resourceRefs())).thenReturn("[]");
        when(dao.findByIdempotency("t1","1001","rag.chat","same-key")).thenReturn(Optional.of(source));
        assertTrue(admission.admit(principal,"same-key",request).replayed());
        var order=inOrder(access,dao);
        order.verify(dao).findRun("t1","source");order.verify(access).visible(principal,source);
        order.verify(access).capture(principal,request.resourceRefs());
        order.verify(dao).findByIdempotency("t1","1001","rag.chat","same-key");
        order.verify(access).visibleWithCaptured(principal,source,"[]");
        verifyNoInteractions(transactions);
    }
}
