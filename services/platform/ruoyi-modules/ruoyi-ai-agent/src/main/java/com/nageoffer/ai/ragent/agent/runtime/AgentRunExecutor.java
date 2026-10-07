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
import com.nageoffer.ai.ragent.runtime.exec.*;
import com.nageoffer.ai.ragent.runtime.usage.UsageLedgerService;
import com.nageoffer.ai.ragent.ingest.ChatGateway;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.*;
import io.agentscope.core.tool.*;
import reactor.core.publisher.Mono;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Component
@ConditionalOnProperty(name="p3.enabled",havingValue="true")
public class AgentRunExecutor implements RunExecutor {
    private final AgentLedger ledger;
    private final RunAccessService access;
    private final AgentToolService tools;
    private final ChatGateway gateway;
    private final UsageLedgerService usage;
    private final P3Properties properties;
    private final JdbcTemplate jdbc;
    private final AgentProviderBoundary boundary;
    /** run 级配置绑定端口（D02/C1.2）：由已注入的 JdbcTemplate 组装，避免为改构造签名而改宿主 wiring。 */
    private final com.nageoffer.ai.ragent.runtime.config.RunConfigBindingPort bindingPort;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<P2FaultInjector> faults;
    public AgentRunExecutor(AgentLedger ledger,RunAccessService access,AgentToolService tools,ChatGateway gateway,UsageLedgerService usage,P3Properties properties,JdbcTemplate jdbc,AgentProviderBoundary boundary){this.ledger=ledger;this.access=access;this.tools=tools;this.gateway=gateway;this.usage=usage;this.properties=properties;this.jdbc=jdbc;this.boundary=boundary;this.bindingPort=new com.nageoffer.ai.ragent.runtime.config.JdbcRunConfigBindingPort(jdbc);}

    /**
     * 该 run **受理时固定**的发布版本（D02/C1.2）。
     *
     * <p>synthetic 模式用**标明的合成绑定**（合成的 run 不绑 V15 发布版本，走端口只会拒绝自己人；
     * 与 D11 {@code LocalKnowledge} 同口径，不是第二权威）。真实模式走端口、失败即拒。
     */
    private com.nageoffer.ai.ragent.runtime.config.RunConfigBinding bound(RunExecution execution) {
        if(properties.isSyntheticModel()) return com.nageoffer.ai.ragent.ingest.SyntheticChatGateway.syntheticBinding(execution.tenantId(),execution.runId());
        return bindingPort.requireBoundRevision(execution.tenantId(),execution.runId());
    }
    public String action(){return "agent.run";}
    public Outcome execute(RunExecution execution) {
        var run=execution.run();var guard=execution.guard();
        // ① 执行契约版本：**先于任何配置权威/DB 读取**判定（旧 checkpoint 不得因为读不到权威而
        //    变成另一种错误码）。不匹配即显式拒绝，不做任何"尽力兼容"。
        if(!"p3-core-v1".equals(run.executionVersion())) return Outcome.failed("AGENT_CHECKPOINT_INCOMPATIBLE");
        // ② 绑定行的五个兼容条件（agent/engine/catalog/model/checkpoint_version）。
        //
        //    这里与 ① 必须给出**同一个**终态码：此前 ① 返回 Outcome.failed("AGENT_CHECKPOINT_INCOMPATIBLE")，
        //    而 ledger.compatible(...) 抛 RUN_STATE_CONFLICT 被 Worker 的 safeFail 收成
        //    error_code=RUN_STATE_CONFLICT —— 同一类"旧 checkpoint"在客户端是两个不同事实。
        //    现在两者都收敛为 AGENT_CHECKPOINT_INCOMPATIBLE，且**授权失败不被吞**：
        //    ledger.compatible(...) 的 access.current 仍原样上抛（见 ③ 的注释）。
        String boundModel = gateway.model(bound(execution));
        AgentLedger.Binding binding = ledger.binding(run);
        if(!AgentLedger.isCompatible(binding,boundModel)) return Outcome.failed("AGENT_CHECKPOINT_INCOMPATIBLE");
        // ③ 授权门：兼容性通过后才走 access.current。**这一句不能并进 ② 的判定**，
        //    否则"授权不可用"会被误报成"checkpoint 不兼容"，把 fail-closed 的原因说错。
        ledger.compatible(binding,run,boundModel);
        // Wall budget is measured from first start, including approval wait and subsequent attempts.
        var budget=AgentModelAdapter.parse(run.budgetJson()==null?"{}":run.budgetJson());
        if(run.startedAt()!=null && java.time.Instant.now().isAfter(run.startedAt().plusSeconds(budget.path("maxWallClockSeconds").asInt(600)))) return Outcome.failed("BUDGET_EXCEEDED");
        var completed=guard.findStep("agent-final");
        if(completed.isPresent()) return Outcome.succeeded(AgentModelAdapter.parse(completed.get().refJson()));
        AtomicReference<RuntimeException> stop=new AtomicReference<>();
        Toolkit toolkit=new Toolkit();
        for(String name:List.of("kb_search","sandbox_ticket")) toolkit.registerAgentTool(new AgentTool(){
            public String getName(){return name;}
            public String getDescription(){return name.equals("kb_search")?"Search current admitted knowledge bases for the exact question":"Create the fixed admitted test ticket after explicit confirmation";}
            public Map<String,Object> getParameters(){return name.equals("kb_search")?Map.of("type","object","properties",Map.of("query",Map.of("type","string")),"required",List.of("query"),"additionalProperties",false):Map.of("type","object","properties",Map.of("title",Map.of("type","string"),"details",Map.of("type","string")),"required",List.of("title","details"),"additionalProperties",false);}
            public Mono<ToolResultBlock> callAsync(ToolCallParam param){return Mono.defer(()->{
                try {var result=tools.invoke(execution,name,CanonicalJson.strictMapper().valueToTree(param.getInput()));var call=param.getToolUseBlock();return Mono.just(ToolResultBlock.of(call.getId(),name,TextBlock.builder().text(AgentLedger.json(result)).build()));}
                catch(RuntimeException ex){stop.set(ex);return Mono.error(ex);}
            });}
        });
        ReActAgent agent=ReActAgent.builder().name("CoreAgent").sysPrompt("Use only the frozen tools and report retrieved evidence and resolved test-ticket results.")
            .model(new AgentModelAdapter(execution,ledger,access,gateway,usage,properties.isSyntheticModel(),stop,boundary,faults.getIfAvailable(),bindingPort))
            // The SDK reserves its final iteration for summarization. Application model-step
            // budgets are enforced by AgentModelAdapter; do not suppress an already admitted tool.
            .toolkit(toolkit).maxIters(12).maxRetries(1)
            // 绑定模型复用 ② 读出的那一份：同一 run 的绑定版本在受理时刻已固定（C1.2），
            // 执行期再读一次不会得到不同事实，只会多一次配置权威查询。
            .stateStore(new FencedAgentStateStore(jdbc,ledger,run,guard,boundModel)).build();
        try {
            var context=RuntimeContext.builder().userId(run.subject()).sessionId(run.runId()).build();
            var reply=agent.call(AgentModelAdapter.parse(run.inputJson()).path("text").asText(),context).block();
            if(stop.get()!=null) throw stop.get();
            if(faults.getIfAvailable()!=null) faults.getIfAvailable().checkpoint("agent.afterEngineSave");
            if(reply==null) return Outcome.failed("AGENT_EMPTY_REPLY");
            String answer=reply.getContentBlocks(TextBlock.class).stream().map(TextBlock::getText).collect(java.util.stream.Collectors.joining());
            if(answer.isBlank()) return Outcome.failed("AGENT_EMPTY_REPLY");
            access.current(run,Set.of("agent.execute","kb.read"));
            var refs=ledger.actions(run).stream().filter(a->"kb_search".equals(a.tool()) && "SUCCEEDED".equals(a.state())).flatMap(a->{var chunks=AgentModelAdapter.parse(a.result()).path("chunks");List<Object> list=new ArrayList<>();chunks.forEach(c->{var citation=new LinkedHashMap<String,Object>();citation.put("docId",c.path("docId").asText());citation.put("versionId",c.path("versionId").asText());citation.put("chunkKey",c.path("chunkKey").asText());citation.put("pageFrom",c.get("pageFrom"));citation.put("pageTo",c.get("pageTo"));list.add(citation);});return list.stream();}).toList();
            var result=new LinkedHashMap<String,Object>();
            result.put("answer",answer);result.put("citations",refs);result.put("engineVersion",AgentLedger.ENGINE);result.put("agentVersion",AgentLedger.AGENT);
            if(guard.findStep("agent-inherited").isPresent()) ledger.inherited(run).ifPresent(source->{
                access.visible(access.current(run,Set.of("agent.execute","kb.read")),ledger.source(source));
                if(!"SUCCEEDED".equals(source.state()) || source.externalId()==null) throw new IllegalStateException("inherited receipt no longer verified");
                result.put("inheritedFrom",Map.of("runId",source.runId(),"actionId",source.id(),"externalId",source.externalId()));
            });
            guard.commitStep("agent-final","agent.final",AgentLedger.json(result),CanonicalJson.sha256(answer),"{}");
            guard.appendEvent(RunEventAppender.EVENT_OUTPUT_DELTA,Map.of("text",answer));
            return Outcome.succeeded(result);
        } catch(RuntimeException ex) {
            RuntimeException signal=stop.get()==null?ex:stop.get();
            if(signal instanceof AgentSuspension suspended) return new Outcome(suspended.status,Map.of(),suspended.status.equals("NEEDS_RECONCILIATION")?"EXTERNAL_OUTCOME_UNKNOWN":null);
            throw signal;
        }
    }
}
