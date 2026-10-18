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

package com.nageoffer.ai.ragent.agent.config;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.confirm.AgentConfirmDenialMiddleware;
import com.nageoffer.ai.ragent.agent.memory.AgentContextCompactionMiddleware;
import com.nageoffer.ai.ragent.agent.memory.AgentUserMemoryMiddleware;
import com.nageoffer.ai.ragent.agent.skill.AgentSkillMaskingMiddleware;
import com.nageoffer.ai.ragent.agent.state.PgAgentStateStore;
import com.nageoffer.ai.ragent.agent.tool.AgentToolBatchMiddleware;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.ResolvedCatalog;
import com.nageoffer.ai.ragent.agent.trace.AgentTraceEnrichmentMiddleware;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.rag.binding.AgentProfileBindingService;
import com.nageoffer.ai.ragent.rag.binding.AgentProfileBindingService.RuntimeBindings;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptSlot;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主 Agent 供给器：单例复用，AGENT_MAIN 人设或工具目录变化时懒重建
 * 会话状态在 PgAgentStateStore 中按次加载，重建不丢历史
 *
 * <p><b>F09:3-C：绑定 MODEL / ORCHESTRATION 的运行消费（验收口径写死）。</b>
 * <ul>
 *   <li><b>MODEL</b>：请求线程读生效档案绑定；有 MODEL ⇒ 经 {@link AgentModelFactory}
 *       解析该模型并放进本 Agent 的 ReAct 主链。同 provider 内切换是保底能力；跨 provider
 *       若费率表不支持，预占阶段 denied 属既有预算边界（不得为此改 spend 信封）。</li>
 *   <li><b>ORCHESTRATION</b>：{@code PLAN_ACT} 映射为 {@code enableTaskList(true)} 的
 *       「ReAct＋持久任务清单」（可观测：{@code todo_write} 工具），<b>不是</b>独立
 *       Plan-and-Execute 控制环；{@code REACT} 与现状逐字节一致。</li>
 *   <li><b>fail-closed</b>：绑定 MODEL 解析不到（工厂缺席/抛错）⇒ 抛
 *       {@link AgentModelFactory.AgentModelUnavailableException}，<b>不回落</b>全局模型。</li>
 *   <li><b>无绑定</b>：不读绑定、不调工厂，全局槽行为与现状一致。</li>
 *   <li><b>隔离与有界</b>：绑定槽键含 tenant/model/mode，上限 {@value #MAX_BOUND_SLOTS}，
 *       超限清空并 WARN；每槽仍按 persona ＋ 目录指纹重建；{@code evictStateCache} 遍历全部槽。</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class ReActAgentProvider {

    private static final String AGENT_NAME = "RagentAI";

    /** 编排闭集（与 AgentProfileBindingService 的保存校验同源）。 */
    private static final String ORCHESTRATION_REACT = "REACT";
    private static final String ORCHESTRATION_PLAN_ACT = "PLAN_ACT";

    /** 未绑定 MODEL 但绑定 ORCHESTRATION 时，绑定槽使用的全局模型标记（缓存键不含凭据）。 */
    private static final String GLOBAL_MODEL_SLOT = "@global";

    /** 绑定槽上限：绑定集合来自管理面，仍按有界缓存处理，超限清空重建。 */
    private static final int MAX_BOUND_SLOTS = 16;

    private volatile CachedAgent cached;

    /** F09:3-C：绑定槽（键 tenant + '\0' + modelId + '\0' + mode；无绑定不进此表）。 */
    private final ConcurrentHashMap<String, CachedAgent> boundSlots = new ConcurrentHashMap<>();

    /**
     * F09:3-C：绑定读取端口（缺席 = 无绑定，保持既有行为）。
     * 字段注入同时服务组件扫描形态；内嵌装配经 {@link #bindModelOrchestration} 显式传入。
     */
    @Autowired(required = false)
    ObjectProvider<AgentProfileBindingService> bindingService;

    /** F09:3-C：绑定模型工厂（有 MODEL 绑定时必需；缺席 ⇒ fail-closed）。 */
    @Autowired(required = false)
    ObjectProvider<AgentModelFactory> modelFactory;

    private final AgentPromptResolver agentPromptResolver;
    private final AgentToolCatalog toolCatalog;
    private final OpenAIChatModel agentChatModel;
    private final PgAgentStateStore agentStateStore;
    private final AgentProperties agentProperties;
    private final AgentUserMemoryMiddleware userMemoryMiddleware;
    private final AgentContextCompactionMiddleware contextCompactionMiddleware;
    private final AgentConfirmDenialMiddleware confirmDenialMiddleware;
    private final AgentSkillMaskingMiddleware skillMaskingMiddleware;
    private final AgentToolBatchMiddleware toolBatchMiddleware;
    private final ObjectProvider<OtelTracingMiddleware> otelTracingMiddleware;
    private final ObjectProvider<AgentTraceEnrichmentMiddleware> traceEnrichmentMiddleware;

    /**
     * F09:3-C 装配入口：内嵌 {@code @Bean} 在保留既有 12 参构造的前提下显式传入两端口。
     * 直接组件扫描形态由 {@code @Autowired(required=false)} 字段注入覆盖。
     */
    public void bindModelOrchestration(ObjectProvider<AgentProfileBindingService> bindingService,
                                       ObjectProvider<AgentModelFactory> modelFactory) {
        this.bindingService = bindingService;
        this.modelFactory = modelFactory;
    }

    /**
     * 以人设内容和工具目录签名判断重建时机：控制台修改后无需重启，下一次会话生效
     *
     * <p>F09:3-C：绑定解析也发生在请求线程；无绑定 ⇒ 全局槽（现状路径）。
     */
    public ActiveAgent getAgent() {
        Map<String, String> prompts = agentPromptResolver.resolveAll();
        String persona = resolvePersona(prompts);
        ResolvedCatalog catalog = toolCatalog.resolve(prompts);

        BoundSelection selection = resolveBoundSelection();
        if (selection == null) {
            return globalAgent(persona, catalog);
        }
        return boundAgent(selection, persona, catalog);
    }

    /**
     * 驱逐单个会话的内存状态：只作用于已构建的实例，清理动作不该顺手把 Agent 建起来
     * 仅本节点有效，多节点各持一份缓存，需要时经 Redis 广播补齐
     *
     * <p>F09:3-C：全局槽与全部绑定槽都必须在清扫范围内（切模型/切模式后的旧槽同样清）。
     */
    @SuppressWarnings("resource")
    public void evictStateCache(String userId, String sessionId) {
        CachedAgent current = cached;
        if (current != null) {
            current.activeAgent().agent().clearStateCache(userId, sessionId);
        }
        for (CachedAgent bound : boundSlots.values()) {
            bound.activeAgent().agent().clearStateCache(userId, sessionId);
        }
    }

    /** 全局槽：与 F09:3-C 之前的单槽行为一致（无绑定、无 plan-act）。 */
    private ActiveAgent globalAgent(String persona, ResolvedCatalog catalog) {
        CachedAgent current = cached;
        if (matches(current, persona, catalog, agentChatModel, false)) {
            return current.activeAgent();
        }
        synchronized (this) {
            current = cached;
            if (matches(current, persona, catalog, agentChatModel, false)) {
                return current.activeAgent();
            }

            // 旧实例不主动 close：在途会话仍在其上流式输出，交由 GC 回收
            ReActAgent agent = buildAgent(persona, catalog, agentChatModel, false);
            ActiveAgent activeAgent = new ActiveAgent(agent, catalog);

            cached = new CachedAgent(persona, activeAgent, agentChatModel, false);
            return activeAgent;
        }
    }

    /** 绑定槽：模型/模式共同决定实例；同槽内仍按 persona ＋ 目录指纹重建。 */
    private ActiveAgent boundAgent(BoundSelection selection, String persona, ResolvedCatalog catalog) {
        CachedAgent current = boundSlots.get(selection.cacheKey());
        if (matches(current, persona, catalog, selection.model(), selection.taskList())) {
            return current.activeAgent();
        }
        synchronized (this) {
            current = boundSlots.get(selection.cacheKey());
            if (matches(current, persona, catalog, selection.model(), selection.taskList())) {
                return current.activeAgent();
            }
            ReActAgent agent = buildAgent(persona, catalog, selection.model(), selection.taskList());
            ActiveAgent activeAgent = new ActiveAgent(agent, catalog);
            storeBound(selection.cacheKey(),
                    new CachedAgent(persona, activeAgent, selection.model(), selection.taskList()));
            return activeAgent;
        }
    }

    /**
     * 请求线程解析生效档案的绑定：
     * <ul>
     *   <li>绑定端口缺席 / 无生效档案 / 无 MODEL 且 REACT ⇒ {@code null}（走全局槽）；</li>
     *   <li>有 MODEL ⇒ 工厂解析（缺席/抛错原样上抛，绝不回落全局模型）；</li>
     *   <li>有 ORCHESTRATION=PLAN_ACT ⇒ 即使无 MODEL 也建绑定槽（用全局模型＋任务清单）。</li>
     * </ul>
     */
    private BoundSelection resolveBoundSelection() {
        AgentProfileBindingService service = bindingService == null ? null : bindingService.getIfAvailable();
        if (service == null) {
            return null;
        }
        Optional<String> profileId = agentPromptResolver.effectiveProfileId();
        if (profileId == null || profileId.isEmpty()) {
            return null;
        }
        RuntimeBindings bindings = service.runtimeBindings(profileId.get());
        Optional<String> modelId = bindings.modelId() == null ? Optional.empty() : bindings.modelId();
        boolean planAct = ORCHESTRATION_PLAN_ACT.equals(bindings.orchestrationMode());
        if (modelId.isEmpty() && !planAct) {
            return null;
        }

        Model model;
        String modelKey;
        if (modelId.isPresent()) {
            AgentModelFactory factory = modelFactory == null ? null : modelFactory.getIfAvailable();
            if (factory == null) {
                throw new AgentModelFactory.AgentModelUnavailableException(
                        "MODEL 绑定无法解析：模型工厂未装配 (modelId=" + modelId.get() + ")");
            }
            model = factory.create(PrincipalContext.require().tenantId(), modelId.get());
            if (model == null) {
                throw new AgentModelFactory.AgentModelUnavailableException(
                        "MODEL 绑定无法解析：模型工厂返回空模型 (modelId=" + modelId.get() + ")");
            }
            modelKey = modelId.get();
        } else {
            model = agentChatModel;
            modelKey = GLOBAL_MODEL_SLOT;
        }
        String mode = planAct ? ORCHESTRATION_PLAN_ACT : ORCHESTRATION_REACT;
        String cacheKey = PrincipalContext.require().tenantId() + '\0' + modelKey + '\0' + mode;
        return new BoundSelection(cacheKey, model, planAct);
    }

    private boolean matches(CachedAgent current, String persona, ResolvedCatalog catalog,
                            Model model, boolean taskList) {
        return current != null
                && current.persona().equals(persona)
                && current.activeAgent().catalog().fingerprint().equals(catalog.fingerprint())
                // 模型实例变化（工厂缓存被清/重建）同样视为过期，避免旧客户端继续被复用
                && current.model() == model
                && current.taskList() == taskList;
    }

    private void storeBound(String key, CachedAgent value) {
        if (boundSlots.size() >= MAX_BOUND_SLOTS && !boundSlots.containsKey(key)) {
            log.warn("Agent 绑定槽位超过上限 {}，清空重建（缓存键不含凭据）", MAX_BOUND_SLOTS);
            boundSlots.clear();
        }
        boundSlots.put(key, value);
    }

    private ReActAgent buildAgent(String persona, ResolvedCatalog catalog, Model model, boolean taskList) {
        ReActAgent.Builder builder = ReActAgent.builder()
                .name(AGENT_NAME)
                .sysPrompt(persona)
                .model(model)
                .toolkit(toolCatalog.buildToolkit(catalog))
                .maxIters(agentProperties.getMaxIters())
                .maxRetries(agentProperties.getMaxRetries())
                .stateStore(agentStateStore);
        // F09:3-C：PLAN_ACT = ReAct＋持久任务清单（enableTaskList 注册 todo_write 与提醒中间件）；
        // REACT 不调用，保持现状。AgentScope 2.0.2 无独立 Plan-and-Execute 控制环。
        if (taskList) {
            builder.enableTaskList(true);
        }
        // 追踪包在最外层：span 要盖住记忆与压缩才量得到耗时
        otelTracingMiddleware.ifAvailable(builder::middleware);
        traceEnrichmentMiddleware.ifAvailable(builder::middleware);
        return builder
                // 外层先执行：记忆块插在人设与会话之间，压缩的 offset 比对自然吸收它
                .middleware(userMemoryMiddleware)
                .middleware(contextCompactionMiddleware)
                // 压缩之后：被压进摘要的拒绝结果已不在列表里，改写自然跳过
                .middleware(confirmDenialMiddleware)
                // 手册被压缩带走后遮蔽跟着复位，工具与手册同进同出
                .middleware(skillMaskingMiddleware)
                // 最内层：批起止贴着真实执行，不随追踪开关变形
                .middleware(toolBatchMiddleware)
                .build();
    }

    private String resolvePersona(Map<String, String> prompts) {
        String persona = prompts.get(AgentPromptSlot.AGENT_MAIN.name());
        if (StrUtil.isBlank(persona)) {
            throw new IllegalStateException("Agent人设内容不允许为空");
        }
        return persona;
    }

    /**
     * 本轮取到的实例与它构建时用的目录快照，成对交出免得调用方各自再取一遍
     */
    public record ActiveAgent(ReActAgent agent, ResolvedCatalog catalog) {
    }

    /**
     * Provider 内部的缓存条目：persona 用于判断缓存是否过期，ActiveAgent 是对外使用的快照
     */
    private record CachedAgent(String persona, ActiveAgent activeAgent, Model model, boolean taskList) {
    }

    /** 绑定槽的解析结果：键含 tenant/model/mode（不含端点或凭据）。 */
    private record BoundSelection(String cacheKey, Model model, boolean taskList) {
    }
}
