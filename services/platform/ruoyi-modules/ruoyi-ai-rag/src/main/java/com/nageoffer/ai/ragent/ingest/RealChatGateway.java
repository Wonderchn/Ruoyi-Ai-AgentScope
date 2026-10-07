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

package com.nageoffer.ai.ragent.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.runtime.config.ConfigAuthorityUnavailable;
import com.nageoffer.ai.ragent.runtime.config.ProviderConnectionPort;
import com.nageoffer.ai.ragent.runtime.config.RunConfigBinding;
import com.nageoffer.ai.ragent.runtime.usage.EgressPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/**
 * 真实提供方网关（D02）。
 *
 * <p><b>三处硬编码已归零</b>：原 provider 字面量、原 model 字面量、原提供方端点 URL
 * 都**不再出现在本文件里**（判据 `RealChatGatewayAuthorityTest` 直接扫本文件，
 * 并带"确实读到了这个文件"的锚点）。
 * 现在的来源分两层，**都不接受默认值**：
 * <ul>
 *   <li><b>用哪份配置</b> —— {@link RunConfigBinding}，由调用方按 {@code (tenantId, runId)}
 *       经 {@code RunConfigBindingPort} 解析后**显式传入**（C1.2：新受理的 run 绑新版本，
 *       已受理/恢复的 run 固定原版本）。本类不查库、不持有端口，因此也不存在
 *       "自己偷偷取当前最新版本"的窗口。</li>
 *   <li><b>那个提供方在哪里、用什么凭据</b> —— {@link ProviderConnectionPort}（**连接引导**，
 *       C1.1 用途② / D16）。端点必须来自引导项，**没有任何默认端点**；查不到即拒绝。</li>
 * </ul>
 *
 * <p><b>无 run 身份面一律抛异常</b>（D02 约束 2）：{@link #provider()}、{@link #model()}、
 * {@link #stream(List,int,Consumer)} 都抛 {@link ConfigAuthorityUnavailable}。
 * 这保证"调用方忘了带 run 身份"是一个**响亮的失败**，而不是悄悄用一个写死的模型跑起来。
 *
 * <p><b>凭据来源。</b>引导项里的 {@code api-key} 存在时优先；否则用装配注入的
 * {@code key}（现有 {@code @Value} 引导值，单提供方部署形态）。二者都是**部署侧注入**，
 * 都不落库；{@code RunConfigBinding.credentialRef} 是库里的**引用/掩码**，两侧都声明引用时
 * 由 {@code ProviderConnections} 校验一致性（不一致即拒绝，不挑一个信）。
 */
@Component
@ConditionalOnProperty(name="p2.chat.mode",havingValue="real",matchIfMissing=true)
public class RealChatGateway implements ChatGateway {
    /** W4-10/task-24：本类原先**没有 logger**；为让"同码不同因"的瞬时失败可判而引入（只加日志、不改行为）。 */
    private static final Logger log = LoggerFactory.getLogger(RealChatGateway.class);
    private final String bootstrapKey;
    private final EgressPolicy egress;
    /** 测试专用的显式 loopback 端点；**不是**生产端点来源（生产走连接引导）。 */
    private final URI explicitEndpoint;
    private final boolean providerEndpoint;
    private ProviderConnectionPort connections;
    private com.nageoffer.ai.ragent.runtime.usage.ProviderSpendEnvelope spend;
    private final ObjectMapper json=new ObjectMapper();

    @Autowired
    public void configureSpend(com.nageoffer.ai.ragent.runtime.usage.ProviderSpendEnvelope spend) {this.spend=spend;}

    /**
     * 连接引导端口。required 注入 ⇒ 引导缺失时**启动期**失败，而不是运行期静默没有端点
     * （与 F-3/G-39 同族的教训：装配期问题不要留到运行期）。
     */
    @Autowired
    public void configureConnections(ProviderConnectionPort connections) {this.connections=connections;}

    @Autowired
    public RealChatGateway(@Value("${p2.providers.deepseek.api-key:${DEEPSEEK_API_KEY:}}") String key,EgressPolicy egress) {
        this(key,egress,null,true);
    }

    /** 测试专用构造：显式 loopback 端点（保留以继续覆盖 HTTP/SSE 解析，不构成生产端点来源）。 */
    RealChatGateway(String key,EgressPolicy egress,URI endpoint) {
        this(key,egress,endpoint,false);
        if(!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())) throw new IllegalArgumentException("test endpoint must be loopback");
    }
    private RealChatGateway(String key,EgressPolicy egress,URI endpoint,boolean providerEndpoint) {
        this.bootstrapKey=key;this.egress=egress;this.explicitEndpoint=endpoint;this.providerEndpoint=providerEndpoint;
    }

    // ------------------------------------------------------------ run 作用域面（唯一可用面）

    @Override
    public String provider(RunConfigBinding binding) {return requireBinding(binding).providerId();}

    @Override
    public String model(RunConfigBinding binding) {return requireBinding(binding).modelId();}

    @Override
    public ChatResult stream(RunConfigBinding binding,List<ChatMessage> messages,int maxTokens,Consumer<String> onDelta) {
        RunConfigBinding bound=requireBinding(binding);
        ProviderConnectionPort.ProviderConnection connection=connection(bound);
        egress.requireAllowed(bound.providerId());
        var serialized=messages.stream().map(m->Map.of("role",m.getRole().name().toLowerCase(Locale.ROOT),"content",m.getContent())).toList();
        if(maxTokens<1 || maxTokens>8192 || serialized.stream().mapToInt(m->m.get("content").length()).sum()>65536) throw ProviderHttp.unavailable();
        HttpURLConnection http=null;
        try {
            String body=json.writeValueAsString(Map.of("model",bound.modelId(),"messages",serialized,"max_tokens",maxTokens,"thinking",Map.of("type","disabled"),"stream",true,"stream_options",Map.of("include_usage",true)));
            String spendId=reserve(bound,connection,body,maxTokens);
            http=ProviderHttp.open(connection.endpoint(),credential(connection),body);
            int status=http.getResponseCode();
            if(status!=200) {
                if(spendId!=null) spend.received(spendId,null,Map.of("httpStatus",status));
                throw ProviderHttp.unavailable();
            }
            String id=null,finish=null; Map<String,Object> usage=null;
            StringBuilder content=new StringBuilder(); boolean done=false;
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(120);
            int consumed=0;
            try(var reader=new BufferedReader(new InputStreamReader(http.getInputStream(),StandardCharsets.UTF_8))) {
                for(String line;(line=ProviderHttp.line(reader,65536))!=null;) {
                    if((consumed+=line.length())>2097152 || System.nanoTime()>deadline || Thread.currentThread().isInterrupted()) throw ProviderHttp.unavailable();
                    if(!line.startsWith("data:")) continue;
                    String value=line.substring(5).trim();
                    if("[DONE]".equals(value)){done=true;break;}
                    var chunk=json.readTree(value);
                    if(chunk.hasNonNull("id")) {String next=chunk.path("id").asText(); if(id!=null&&!id.equals(next))throw ProviderHttp.unavailable();id=next;}
                    if(chunk.path("usage").isObject()) usage=json.convertValue(chunk.path("usage"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
                    for(var choice:chunk.path("choices")) {
                        String delta=choice.path("delta").path("content").asText("");
                        if(!delta.isEmpty()){content.append(delta);onDelta.accept(delta);}
                        if(choice.hasNonNull("finish_reason")) finish=choice.path("finish_reason").asText();
                    }
                }
            }
            if(!done || finish==null || id==null) throw ProviderHttp.unavailable();
            if(spendId!=null) spend.received(spendId,id,usage);
            return new ChatResult(content.toString(),id,usage,finish);
        } catch(IOException e) {throw ProviderHttp.unavailable();}
        finally {if(http!=null) http.disconnect();}
    }

    // ------------------------------------------------------------ 无 run 身份面：显式拒绝（D02 约束 2）

    /** 无 run 身份 ⇒ 拒绝。**不得**回退任何默认模型。 */
    @Override
    public String provider() {
        throw new ConfigAuthorityUnavailable("run identity required: use provider(RunConfigBinding)");
    }

    /** 无 run 身份 ⇒ 拒绝。**不得**回退任何默认模型。 */
    @Override
    public String model() {
        throw new ConfigAuthorityUnavailable("run identity required: use model(RunConfigBinding)");
    }

    /** 无 run 身份 ⇒ 拒绝。**不得**回退任何默认模型。 */
    @Override
    public ChatResult stream(List<ChatMessage> messages,int maxTokens,Consumer<String> onDelta) {
        throw new ConfigAuthorityUnavailable("run identity required: use stream(RunConfigBinding, messages, maxTokens, onDelta)");
    }

    // ------------------------------------------------------------ 内部

    /** provider 与 model 必须同时来自 run 的绑定事实；缺失即拒绝（无默认值）。 */
    private static RunConfigBinding requireBinding(RunConfigBinding binding) {
        if(binding==null || binding.providerId()==null || binding.providerId().isBlank()
                || binding.modelId()==null || binding.modelId().isBlank()) {
            throw new ConfigAuthorityUnavailable("provider and model must come from the run's bound published revision");
        }
        return binding;
    }

    /** 端点解析：生产只走连接引导（无默认端点）；测试走显式 loopback 端点。 */
    private ProviderConnectionPort.ProviderConnection connection(RunConfigBinding bound) {
        if(explicitEndpoint!=null) {
            return new ProviderConnectionPort.ProviderConnection(bound.providerId(),explicitEndpoint,bound.credentialRef(),bootstrapKey);
        }
        ProviderConnectionPort port=connections;
        if(port==null) throw new ConfigAuthorityUnavailable("provider connection bootstrap is not wired");
        return port.requireConnection(bound.providerId(),bound.credentialRef());
    }

    /** 引导项声明的密钥优先；否则用装配注入的引导值。两者都是部署侧注入，都不落库。 */
    private String credential(ProviderConnectionPort.ProviderConnection connection) {
        String declared=connection.apiKey();
        return declared==null || declared.isBlank() ? bootstrapKey : declared;
    }

    private String reserve(RunConfigBinding bound,ProviderConnectionPort.ProviderConnection connection,String body,int maxTokens) {
        // W4-10/task-24 探针（T0 落点）：本轮 `DEPENDENCY_UNAVAILABLE` 只剩两处 message 相同的"瞬时"抛点，
        // 而 :111 已被实测排除、:197 与"env key 长度 35 非空"冲突 ⇒ 需要**可判事实**而不是继续排除法。
        // **只打布尔与长度，绝不打值**（K3：凭据内容不出日志），且**不改变任何行为**。
        String cred=credential(connection);
        log.warn("chat credential check provider={} ref={} present={} len={} bootstrapPresent={}",
                bound.providerId(), connection.credentialRef(),
                cred!=null && !cred.isBlank(), cred==null?-1:cred.length(),
                bootstrapKey!=null && !bootstrapKey.isBlank());
        if(cred==null || cred.isBlank()) throw ProviderHttp.unavailable();
        if(!providerEndpoint) return null;
        if(spend==null) throw ProviderHttp.unavailable();
        return spend.reserve(bound.providerId(),bound.modelId(),body.getBytes(StandardCharsets.UTF_8).length,maxTokens);
    }
}
