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

import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService;
import com.nageoffer.ai.ragent.ingest.DocumentDao;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Tag;

@Tag("dev")
class RunCitationVisibilityTest {
    @SuppressWarnings("unchecked") private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(value);return provider;
    }
    @Test void historicalAnswerRequiresCurrentDocumentVersionAndAcl() {
        var resources=mock(AiResourceAuthorizationService.class);
        var ledger=mock(RunLedgerDao.class);var documents=mock(DocumentDao.class);
        var service=new RunAccessService(provider(resources),provider(null),provider(ledger),provider(documents));
        var principal=new ExecutionPrincipal("t1","1001","platform:t1:1001",1,1,Set.of("run.read"),"j","platform",1,9999999999L);
        var run=new RunRecord("t1","r1","platform:t1:1001","1001","rag.chat","SUCCEEDED",null,"key",null,null,"p2-v1",1,1,"[]",1,1,1,1,null,null,null,null,null,null,null,null,null);
        when(ledger.latestCompletedStep("t1","r1","retrieve")).thenReturn(Optional.of(new RunLedgerDao.StepRow("retrieve",1,1,"retrieve","COMPLETED","{\"chunks\":[{\"docId\":\"doc1\",\"versionId\":\"ver1\"}]}",null,null,1,null,null)));
        when(documents.findDocument("t1","doc1")).thenReturn(Optional.of(new DocumentDao.DocumentRow("doc1","kb1","name","platform:t1:1001","ver1",null,null)));
        when(resources.check(any(),eq("document.read"),eq("doc:doc1"))).thenReturn(ResourceAuthorizationService.Verdict.GRANT);
        assertDoesNotThrow(()->service.visible(principal,run));
        when(documents.findDocument("t1","doc1")).thenReturn(Optional.of(new DocumentDao.DocumentRow("doc1","kb1","name","platform:t1:1001","ver2",null,null)));
        assertThrows(RunApiException.class,()->service.visible(principal,run));
        when(documents.findDocument("t1","doc1")).thenReturn(Optional.empty());
        assertThrows(RunApiException.class,()->service.visible(principal,run));
        when(documents.findDocument("t1","doc1")).thenReturn(Optional.of(new DocumentDao.DocumentRow("doc1","kb1","name","platform:t1:1001","ver1",null,null)));
        when(resources.check(any(),eq("document.read"),eq("doc:doc1"))).thenReturn(ResourceAuthorizationService.Verdict.DENY);
        assertThrows(RunApiException.class,()->service.visibleWithCaptured(principal,run,"[]"));
    }
}
