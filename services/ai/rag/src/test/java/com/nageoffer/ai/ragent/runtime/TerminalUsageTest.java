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
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import com.nageoffer.ai.ragent.runtime.usage.UsageLedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TerminalUsageTest {
    @Test @SuppressWarnings("unchecked") void terminalFinalizesBudgetOnlyAfterCurrentFenceAccepted() {
        var dao=mock(RunLedgerDao.class);var usage=mock(UsageLedgerService.class);
        ObjectProvider<UsageLedgerService> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(usage);
        var events=new RunEventAppender(dao,new ObjectMapper());events.configureUsage(provider);
        var run=new RunRecord("t1","r1","platform:t1:1001","1001","rag.chat","RUNNING",null,"key",null,null,"p2-v1",1,1,"[]",1,1,1,1,null,null,null,null,null,null,null,null,null);
        when(dao.lockRun("t1","r1")).thenReturn(Optional.of(run));
        when(dao.ownsLiveLease("t1","r1","current",1)).thenReturn(true);
        when(dao.updateTerminalFenced(eq("t1"),eq("r1"),eq("current"),eq(1L),anyString(),anyString(),isNull())).thenReturn(true);
        assertThrows(RunApiException.class,()->events.terminal("t1","r1","old",1,"SUCCEEDED",Map.of(),null));
        verifyNoInteractions(usage);
        events.terminal("t1","r1","current",1,"SUCCEEDED",Map.of(),null);
        verify(usage).finalizeReservation("t1","r1");
    }
    @Test void unknownCallNeverReleasesReservationAndConfirmedNoCallDoes() {
        var jdbc=mock(JdbcTemplate.class);var usage=new UsageLedgerService(jdbc);
        when(jdbc.queryForList(anyString(),eq("t1"),eq("r1"))).thenReturn(List.of(Map.of("state","STARTED","n",1L)));
        usage.finalizeReservation("t1","r1");
        verify(jdbc,never()).update(anyString(),any(Object[].class));
        when(jdbc.queryForList(anyString(),eq("t1"),eq("r1"))).thenReturn(List.of());
        usage.finalizeReservation("t1","r1");
        verify(jdbc).update(contains("state='RELEASED'"),eq("t1"),eq("r1"));
    }
}
