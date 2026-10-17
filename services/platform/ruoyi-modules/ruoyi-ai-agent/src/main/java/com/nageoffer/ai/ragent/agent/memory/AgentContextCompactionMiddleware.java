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

package com.nageoffer.ai.ragent.agent.memory;

import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.identity.AgentRunScope;
import com.nageoffer.ai.ragent.agent.identity.AgentRunScopeRegistry;
import com.nageoffer.ai.ragent.agent.memory.AgentContextTrimmer.TrimResult;
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 记忆接线点：推理前裁剪/压缩上下文并同步上行列表
 * <p>
 * 两层按水位分工：50% 裁工具结果，80% 压缩摘要；实例被单例 Agent 共享，不持有 per-call 字段
 * <p>
 * F16 压缩链出站（F-AC6-7/BD-1 同族第五站，2026-10-14）：压缩跳 boundedElastic 后无同步起源，
 * 摘要模型调用撞出站 origin 闸；命中受理期登记作用域时以在线重建式包裹（裁决⑤形状），
 * 作用域未命中按原形态直跑（fail-closed 语义不变）
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
public class AgentContextCompactionMiddleware implements MiddlewareBase {

    /**
     * 压缩摘要出站的声明动作集（F16 压缩出站，最小集合）：canonical 动作
     * {@code agent.execute→ai:agent:execute}（{@code AiCanonicalAction:55}，菜单 7120，受理主体持有），
     * 语义＝agent 执行内的运行期模型调用。刻意只声明这一条——不得用全量 canonicalActions
     * 或追加动作制造放行；重建序列逐动作在线预检、出站 verifier 仍逐动作重判。
     */
    public static final Set<String> COMPACTION_EGRESS_ACTIONS = Set.of("agent.execute");

    private final AgentContextTrimmer trimmer;
    private final AgentContextCompactor compactor;
    private final AgentMemoryProperties memoryProperties;
    /**
     * 轮次身份作用域登记表（与完成链同一受理期登记，装配形状照 MemoryFlushTool）：压缩切到
     * boundedElastic 后任务体手里没有可信受理期身份，按 (userId, sessionId) 取本轮登记；
     * 载体缺席＝原样直跑（不读环境身份、不新增默认身份）。
     */
    private final ObjectProvider<AgentRunScopeRegistry> runScopeRegistry;
    /**
     * 出站身份在线事实端口（裁决⑤）：任务体内按捕获引用在线重建出站主体；端口缺席＝
     * 拒绝重建、退回捕获形态（出站照旧被可见拒绝，压缩如实放弃）。
     */
    private final ObjectProvider<MemoryEgressSubjectFacts> egressFacts;
    /**
     * 租户绑定端口（装配形状照 MemoryFlushTool/AgentChatServiceImpl）：池线程没有动态租户，
     * 摘要在任务体内绑回受理期租户；端口缺席（独立应用）按原形态直跑。
     */
    private final ObjectProvider<TenantScopeBinder> tenantScopeBinder;

    @Autowired
    public AgentContextCompactionMiddleware(AgentContextTrimmer trimmer, AgentContextCompactor compactor,
                                            AgentMemoryProperties memoryProperties,
                                            ObjectProvider<AgentRunScopeRegistry> runScopeRegistry,
                                            ObjectProvider<MemoryEgressSubjectFacts> egressFacts,
                                            ObjectProvider<TenantScopeBinder> tenantScopeBinder) {
        this.trimmer = trimmer;
        this.compactor = compactor;
        this.memoryProperties = memoryProperties;
        this.runScopeRegistry = runScopeRegistry;
        this.egressFacts = egressFacts;
        this.tenantScopeBinder = tenantScopeBinder;
    }

    /**
     * 直构兼容（既有集成测试装配）：三件载体缺席＝压缩按原形态直跑，
     * 出站闸照旧 fail-closed；Spring 装配一律走六参主构造（{@code @Autowired}）。
     */
    public AgentContextCompactionMiddleware(AgentContextTrimmer trimmer, AgentContextCompactor compactor,
                                            AgentMemoryProperties memoryProperties) {
        this(trimmer, compactor, memoryProperties, null, null, null);
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext runtimeContext, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> dispatch(agent, runtimeContext, input, next));
    }

    private Flux<AgentEvent> dispatch(Agent agent, RuntimeContext runtimeContext, ReasoningInput input,
                                      Function<ReasoningInput, Flux<AgentEvent>> next) {
        List<Msg> context;
        try {
            AgentState state = RuntimeContext.resolveAgentState(runtimeContext, agent);
            context = state == null ? null : state.contextMutable();
        } catch (Exception e) {
            log.warn("会话状态取不到, 本轮按原列表推理, sessionId: {}", sessionId(runtimeContext), e);
            return next.apply(input);
        }
        if (context == null) {
            return next.apply(input);
        }
        if (!memoryProperties.isSummaryEnabled() || !shouldCompact(context)) {
            return next.apply(trim(context, input, runtimeContext));
        }
        // 先验上行列表与 context 的引用关系，再动手压缩
        List<Msg> prefix = resolvePrefix(input.messages(), context);
        if (prefix == null) {
            log.warn("上行列表与上下文对不上, 本轮不压缩, 上行: {}, 上下文: {}", input.messages().size(), context.size());
            return next.apply(trim(context, input, runtimeContext));
        }
        return compact(context, input, prefix, runtimeContext).flatMapMany(next::apply);
    }

    /**
     * 末条是用户消息才压缩，保证工具循环已闭合
     */
    private boolean shouldCompact(List<Msg> context) {
        if (context.isEmpty() || context.get(context.size() - 1).getRole() != MsgRole.USER) {
            return false;
        }
        return AgentContextChars.total(context) > memoryProperties.resolveCompactTriggerChars();
    }

    /**
     * 压缩含同步模型调用，切到 boundedElastic 避免占推理线程
     *
     * <p>F16 压缩链出站：池线程上无同步起源，摘要模型调用被出站 origin 闸拒绝
     * （真机 7×{@code ChatEgressDeniedException}，「摘要生成失败, 放弃本次压缩」）。命中受理期
     * 登记作用域时以在线重建式包裹（裁决⑤形状）：任务体内按在线事实重建、声明动作集最小化
     * （{@link #COMPACTION_EGRESS_ACTIONS}），重建成功才配对 set/restore 授予同步起源；
     * 重建被拒＝退回捕获形态、出站照旧 fail-closed。作用域未命中/载体缺席＝原样直跑。
     */
    private Mono<ReasoningInput> compact(List<Msg> context, ReasoningInput input, List<Msg> prefix,
                                         RuntimeContext runtimeContext) {
        String userId = userId(runtimeContext);
        String sessionId = sessionId(runtimeContext);
        return Mono.fromCallable(() -> {
                    AgentRunScope runScope = resolveRunScope(runtimeContext);
                    if (runScope == null) {
                        return compactDirectly(context, input, prefix, runtimeContext, userId, sessionId);
                    }
                    return AsyncMemoryTaskScope.callWithRebuiltEgress(runScope.principal(), runScope.tenantId(),
                            tenantScopeBinder, egressFacts, COMPACTION_EGRESS_ACTIONS,
                            () -> compactDirectly(context, input, prefix, runtimeContext, userId, sessionId));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("上下文压缩异常, 本轮退回工具结果裁剪, sessionId: {}", sessionId(runtimeContext), e);
                    return Mono.fromCallable(() -> trim(context, input, runtimeContext));
                });
    }

    /**
     * 压缩本体（原直跑语义逐字保留）：压缩器改写则整段重建上行列表，放弃/异常退回裁剪
     */
    private ReasoningInput compactDirectly(List<Msg> context, ReasoningInput input, List<Msg> prefix,
                                           RuntimeContext runtimeContext, String userId, String sessionId) {
        return compactor.compactInPlace(context, userId, sessionId)
                ? rebuild(input, context, prefix)
                : trim(context, input, runtimeContext);
    }

    /**
     * 取受理期登记的本轮作用域；载体缺席或未登记返回 {@code null}＝原样直跑
     * （fail-closed 语义不变：不读环境身份、不新增默认身份）
     */
    private AgentRunScope resolveRunScope(RuntimeContext runtimeContext) {
        AgentRunScopeRegistry registry = runScopeRegistry == null ? null : runScopeRegistry.getIfAvailable();
        return registry == null ? null : registry.resolve(userId(runtimeContext), sessionId(runtimeContext));
    }

    /**
     * 裁剪失败走原列表
     */
    private ReasoningInput trim(List<Msg> context, ReasoningInput input, RuntimeContext runtimeContext) {
        try {
            TrimResult result = trimmer.trimInPlace(context);
            if (!result.changed()) {
                return input;
            }
            List<Msg> messages = input.messages().stream()
                    .map(msg -> result.replacements().getOrDefault(msg, msg))
                    .toList();
            return new ReasoningInput(messages, input.tools(), input.options());
        } catch (Exception e) {
            log.warn("上下文裁剪异常, 本轮按原列表推理, sessionId: {}", sessionId(runtimeContext), e);
            return input;
        }
    }

    /**
     * 按引用逐条比对，取出上行列表头部的框架前缀；失配返回 null
     */
    private List<Msg> resolvePrefix(List<Msg> messages, List<Msg> context) {
        int offset = messages.size() - context.size();
        if (offset < 0) {
            return null;
        }
        for (int i = 0; i < context.size(); i++) {
            if (messages.get(offset + i) != context.get(i)) {
                return null;
            }
        }
        return List.copyOf(messages.subList(0, offset));
    }

    /**
     * 压缩改了消息条数，需整段重建上行列表
     */
    private ReasoningInput rebuild(ReasoningInput input, List<Msg> context, List<Msg> prefix) {
        List<Msg> rebuilt = new ArrayList<>(prefix.size() + context.size());
        rebuilt.addAll(prefix);
        rebuilt.addAll(context);
        return new ReasoningInput(rebuilt, input.tools(), input.options());
    }

    private String sessionId(RuntimeContext runtimeContext) {
        return runtimeContext == null ? null : runtimeContext.getSessionId();
    }

    private String userId(RuntimeContext runtimeContext) {
        return runtimeContext == null ? null : runtimeContext.getUserId();
    }
}
