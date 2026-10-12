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

package com.nageoffer.ai.ragent.agent.identity;

import com.nageoffer.ai.ragent.framework.context.AsyncPrincipalReference;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import io.agentscope.core.agent.RuntimeContext;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 完成链的受理期身份/租户作用域（s2-tenant-cmp-a1，2026-10-12）。
 *
 * <p><b>为什么需要。</b>一个 agent 轮次走到「完成」时，三条既有链在受理线程之外执行：
 * 轮末状态保存（框架 {@code ReActAgent.saveStateToSession} 在
 * {@code subscribeOn(boundedElastic)} 上回调 {@code AgentStateStore}，闭包只剩
 * {@code (userId, sessionId)} 一对键）、终答落库（流终止/池线程）、知识检索工具
 * （工具自身 {@code subscribeOn(boundedElastic)}）。跳变之后池线程的主体是创建期继承
 * 的<b>残留拷贝</b>（可能属于别的用户），动态租户是普通 ThreadLocal、不跨线程——
 * 平台 MyBatis 租户行拦截器对 {@code ai_agent_state}/{@code ai_agent_message}/
 * {@code ai_query_term_mapping} fail-closed（真机 r17/f10p3-win：
 * {@code tenant.context.missing}，3/3 完成轮必挂）。
 *
 * <p><b>捕获纪律（同 P2/P3 先例）。</b>只在受理期、请求线程、且
 * {@link PrincipalContext#isSynchronousOrigin()} 为真时成 scope；池化残留与非配对
 * {@code restore} 拷贝不是可信在场主体，一律返回 {@code null}——消费方按原形态直跑，
 * 既不新增默认身份，也绝不错绑他人身份。
 *
 * <p><b>两个消费载体。</b>工具执行上下文走 {@link #RUNTIME_CONTEXT_KEY}（受理期填入
 * RuntimeContext）；轮末状态保存链没有 RuntimeContext 可达，改由
 * {@link AgentRunScopeRegistry} 按 {@code (userId, sessionId)} 键登记。
 * 重绑一律走 {@code AsyncMemoryTaskScope} 形状：非配对 restore（只授 holder、不授
 * 同步起源）＋租户绑定端口内层＋finally 复原线程原主体。
 */
public record AgentRunScope(AsyncPrincipalReference principal, TenantScopeBinder tenantScopeBinder) {

    /** RuntimeContext 载体键；工具执行上下文（如 search_knowledge）读它重绑。 */
    public static final String RUNTIME_CONTEXT_KEY = "ragent_run_scope";

    public AgentRunScope {
        if (principal == null) {
            throw new ClientException("run scope principal is required");
        }
    }

    /** 租户从同一捕获派生，两处来源不许各自读一遍（漂移＝跨租户写归属）。 */
    public String tenantId() {
        return principal.tenantId();
    }

    /**
     * 受理期捕获（请求线程、同步起源）：非同步起源即返回 {@code null}＝无捕获形态直跑。
     * binder 端口缺席（独立应用无租户拦截器）合法，重绑时按直跑处理。
     */
    public static AgentRunScope capture(ObjectProvider<TenantScopeBinder> tenantScopeBinder) {
        if (!PrincipalContext.isSynchronousOrigin()) {
            return null;
        }
        AsyncPrincipalReference principal = AsyncPrincipalReference.from(PrincipalContext.require());
        return new AgentRunScope(principal,
                tenantScopeBinder == null ? null : tenantScopeBinder.getIfAvailable());
    }

    /** 从 RuntimeContext 读载体；缺席或类型不符返回 {@code null}（直跑形状）。 */
    public static AgentRunScope from(RuntimeContext runtimeContext) {
        Object scope = runtimeContext == null ? null : runtimeContext.get(RUNTIME_CONTEXT_KEY);
        return scope instanceof AgentRunScope value ? value : null;
    }
}
