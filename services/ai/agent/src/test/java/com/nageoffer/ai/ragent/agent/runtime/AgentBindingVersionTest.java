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
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentBindingVersionTest {
    private RunRecord run(){return new RunRecord("t1","r1","platform:t1:1001","1001","agent.run","RUNNING",null,"key",null,null,"p3-core-v1",1,1,"[]",1,1,1,1,null,null,null,null,null,null,null,null,null);}
    @Test void incompatibleFrozenBindingRejectsBeforeCurrentExecutionWork() {
        var access=mock(RunAccessService.class);
        var run=run();
        for(var binding:List.of(
            new AgentLedger.Binding("member","core-v1","2.0.2","model","p3-tools-v1",6,6,4000,0,2),
            new AgentLedger.Binding("member","core-v1","old-engine","model","p3-tools-v1",6,6,4000,0,1),
            new AgentLedger.Binding("member","core-v1","2.0.2","model","untrusted-catalog",6,6,4000,0,1),
            new AgentLedger.Binding("member","core-v1","2.0.2","other-model","p3-tools-v1",6,6,4000,0,1))) {
            var ledger=new AgentLedger(null,null,access) {
                @Override public Binding binding(RunRecord ignored){return binding;}
            };
            assertThrows(RunApiException.class,()->ledger.compatible(run,"model"));
        }
        verifyNoInteractions(access);
    }
    @Test void compatibleVersionRequiresCurrentAuthority() {
        var access=mock(RunAccessService.class);var run=run();
        var ledger=new AgentLedger(null,null,access) {
            @Override public Binding binding(RunRecord ignored){return new Binding("member","core-v1","2.0.2","model","p3-tools-v1",6,6,4000,0,1);}
        };
        ledger.compatible(run,"model");
        verify(access).current(eq(run),anySet());
    }
}
