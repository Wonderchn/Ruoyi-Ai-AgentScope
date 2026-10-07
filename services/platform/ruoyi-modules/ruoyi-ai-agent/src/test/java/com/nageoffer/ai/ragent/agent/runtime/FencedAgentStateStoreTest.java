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
import com.nageoffer.ai.ragent.runtime.exec.RunExecutionGuard;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import io.agentscope.core.message.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Tag;

@Tag("dev")
class FencedAgentStateStoreTest {
    private RunRecord run(){return new RunRecord("t1","r1","platform:t1:1001","1001","agent.run","RUNNING",null,"key","{}","{}","p3-core-v1",1,1,"[]",1,1,1,1,null,null,null,null,null,null,null,null,null);}
    @Test void boundScopeAndLiveFenceRejectCrossRunAndOldWorkerWrites() {
        var jdbc=mock(JdbcTemplate.class);var ledger=mock(AgentLedger.class);var guard=mock(RunExecutionGuard.class);
        var store=new FencedAgentStateStore(jdbc,ledger,run(),guard,"model");
        var state=Msg.builder().name("assistant").role(MsgRole.ASSISTANT).textContent("synthetic").build();
        assertThrows(RunApiException.class,()->store.save("other","r1","messages",state));
        assertThrows(RunApiException.class,()->store.get("1001","other-run","messages",Msg.class));
        when(guard.commitAtomic(any())).thenThrow(new RunApiException(RunErrorCode.VERSION_CONFLICT));
        assertThrows(RunApiException.class,()->store.save("1001","r1","messages",state));
        verifyNoInteractions(jdbc);
        assertThrows(RunApiException.class,()->store.delete("1001","r1"));
    }
    @Test void actualAgentScopeCodecRoundTripsAndRejectsVersionOrHashTampering() {
        var jdbc=mock(JdbcTemplate.class);var ledger=mock(AgentLedger.class);var guard=mock(RunExecutionGuard.class);
        var store=new FencedAgentStateStore(jdbc,ledger,run(),guard,"model");
        var state=Msg.builder().name("assistant").role(MsgRole.ASSISTANT).textContent("synthetic checkpoint").build();
        String payload=io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(List.of(state));
        String hash=AgentLedger.hashArgs(AgentModelAdapter.parse(payload));
        var row=new HashMap<String,Object>();row.put("payload",payload);row.put("payload_hash",hash);row.put("member_id","platform:t1:1001");
        row.put("engine_version",AgentLedger.ENGINE);row.put("agent_version",AgentLedger.AGENT);row.put("model","model");row.put("tool_catalog",AgentLedger.CATALOG);row.put("checkpoint_version",1);row.put("policy_version",1);row.put("acl_version",1);
        when(jdbc.queryForList(anyString(),eq("t1"),eq("r1"),eq("messages"))).thenReturn(List.of(row));
        assertEquals("synthetic checkpoint",store.get("1001","r1","messages",Msg.class).orElseThrow().getTextContent());
        row.put("engine_version","different");assertThrows(RunApiException.class,()->store.get("1001","r1","messages",Msg.class));
        row.put("engine_version",AgentLedger.ENGINE);row.put("payload_hash","0".repeat(64));assertThrows(RunApiException.class,()->store.getList("1001","r1","messages",Msg.class));
    }
}
