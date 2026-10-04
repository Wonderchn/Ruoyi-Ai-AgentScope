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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.*;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Tag;

@Tag("dev")
class AgentEngineContractTest {
    @Test void actualSdkDispatchesFrozenToolAndReturnsFinalText() {
        AtomicInteger calls=new AtomicInteger(),writes=new AtomicInteger();
        Toolkit toolkit=new Toolkit();
        toolkit.registerAgentTool(new io.agentscope.core.tool.AgentTool(){
            public String getName(){return "kb_search";}
            public String getDescription(){return "synthetic read";}
            public java.util.Map<String,Object> getParameters(){return java.util.Map.of("type","object","properties",java.util.Map.of("query",java.util.Map.of("type","string")),"required",List.of("query"),"additionalProperties",false);}
            public reactor.core.publisher.Mono<io.agentscope.core.message.ToolResultBlock> callAsync(io.agentscope.core.tool.ToolCallParam param){assertEquals("synthetic",param.getInput().get("query"));writes.incrementAndGet();return reactor.core.publisher.Mono.just(io.agentscope.core.message.ToolResultBlock.of(param.getToolUseBlock().getId(),"kb_search",io.agentscope.core.message.TextBlock.builder().text("synthetic tool result").build()));}
        });
        Model model=new Model(){
            public String getModelName(){return "synthetic-contract";}
            public Flux<ChatResponse> stream(List<Msg> messages,List<ToolSchema> tools,GenerateOptions options){int n=calls.getAndIncrement();return Flux.just(n==0?new ChatResponse("agent-model-0",List.of(new io.agentscope.core.message.ToolUseBlock("agent-model-0","kb_search",java.util.Map.of("query","synthetic"),"{\"query\":\"synthetic\"}",java.util.Map.of())),new ChatUsage(0,0,0),java.util.Map.of(),"tool_calls"):new ChatResponse("agent-model-1",List.of(io.agentscope.core.message.TextBlock.builder().text("synthetic final").build()),new ChatUsage(0,0,0),java.util.Map.of(),"stop"));}
        };
        var agent=ReActAgent.builder().name("Contract").sysPrompt("synthetic fixture").model(model).toolkit(toolkit).maxIters(6).maxRetries(1).build();
        var result=agent.call("synthetic",RuntimeContext.builder().userId("synthetic").sessionId("synthetic-run").build()).block();
        assertNotNull(result);assertEquals(1,writes.get());assertEquals("synthetic final",result.getTextContent());
    }
    @Test void hardEngineCeilingAllowsSecondToolBeforeApplicationSummaryBudgetRefusal() {
        AtomicInteger calls=new AtomicInteger(),reads=new AtomicInteger(),writes=new AtomicInteger();
        Toolkit toolkit=new Toolkit();
        for(String name:List.of("read_probe","write_probe")) toolkit.registerAgentTool(new io.agentscope.core.tool.AgentTool(){
            public String getName(){return name;}
            public String getDescription(){return "synthetic bounded tool";}
            public java.util.Map<String,Object> getParameters(){return java.util.Map.of("type","object","properties",java.util.Map.of(),"additionalProperties",false);}
            public reactor.core.publisher.Mono<io.agentscope.core.message.ToolResultBlock> callAsync(io.agentscope.core.tool.ToolCallParam param){
                (name.equals("read_probe")?reads:writes).incrementAndGet();
                return reactor.core.publisher.Mono.just(io.agentscope.core.message.ToolResultBlock.of(param.getToolUseBlock().getId(),name,io.agentscope.core.message.TextBlock.builder().text("known tool receipt").build()));
            }
        });
        Model model=new Model(){
            public String getModelName(){return "synthetic-contract";}
            public Flux<ChatResponse> stream(List<Msg> messages,List<ToolSchema> tools,GenerateOptions options){return Flux.defer(()->{
                int ordinal=calls.getAndIncrement();
                if(ordinal>=2) return Flux.error(new IllegalStateException("application step budget reached before summary"));
                String name=ordinal==0?"read_probe":"write_probe";
                return Flux.just(new ChatResponse("step-"+ordinal,List.of(new io.agentscope.core.message.ToolUseBlock("step-"+ordinal,name,java.util.Map.of(),"{}",java.util.Map.of())),new ChatUsage(0,0,0),java.util.Map.of(),"tool_calls"));
            });}
        };
        var agent=ReActAgent.builder().name("Contract").sysPrompt("synthetic fixture").model(model).toolkit(toolkit).maxIters(12).maxRetries(1).build();
        assertThrows(RuntimeException.class,()->agent.call("synthetic",RuntimeContext.builder().userId("synthetic").sessionId("summary-budget-run").build()).block());
        assertEquals(1,reads.get());assertEquals(1,writes.get());assertEquals(3,calls.get());
    }
    @Test void actualSdkMaxRetriesOneMeansOneModelAttempt() {
        AtomicInteger calls=new AtomicInteger();
        Model model=new Model(){
            public String getModelName(){return "synthetic-contract";}
            public Flux<ChatResponse> stream(List<Msg> messages,List<ToolSchema> tools,GenerateOptions options){return Flux.defer(()->{calls.incrementAndGet();return Flux.error(new IllegalStateException("synthetic unknown response"));});}
        };
        var agent=ReActAgent.builder().name("Contract").sysPrompt("synthetic fixture").model(model).toolkit(new Toolkit()).maxIters(2).maxRetries(1).build();
        var context=RuntimeContext.builder().userId("synthetic").sessionId("synthetic-run").build();
        assertThrows(RuntimeException.class,()->agent.call("synthetic",context).block());
        assertEquals(1,calls.get());
    }
}
