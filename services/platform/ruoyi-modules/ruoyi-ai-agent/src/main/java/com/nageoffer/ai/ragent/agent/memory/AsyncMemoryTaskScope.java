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

import com.nageoffer.ai.ragent.framework.context.AsyncPrincipalReference;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 异步记忆任务的身份/租户作用域（F16 op1 管线修复，s2-f10p3-a1，2026-10-12）。
 *
 * <p><b>为什么需要。</b>长期记忆的「注入」与「抽取」两条链都把阻塞 JDBC 调度到共享
 * {@code Schedulers.boundedElastic()} 上跑（不占推理/释放线程）。跳变之后：
 * <ul>
 *   <li>落库链的第一道门是 {@link PrincipalContext#require()}——池线程上的主体是
 *       创建期继承的<b>残留拷贝</b>（TTL/InheritableThreadLocal 语义），可能属于别的用户：</li>
 *   <li>第二道门是平台 MyBatis 租户行拦截器——动态租户是普通 ThreadLocal、不跨线程，
 *       池线程上必然 {@code tenant.context.missing}、在任何 SQL 之前 fail-closed。</li>
 * </ul>
 * 真机实证（r17/f10p1-win/app-6044.log）：注入侧静默降级（读失败退空快照）、
 * 抽取侧整批失败。修复＝受理期（请求线程）捕获身份引用，任务体内重绑后再执行。
 *
 * <p><b>形状（同 P2 {@code StreamTaskManager} 先例）。</b>
 * <ul>
 *   <li>身份用<b>非配对</b> {@link PrincipalContext#restore(ExecutionPrincipal)} 重绑：
 *       只授 holder、不授同步起源（勿用 {@code set()}——共享池线程上会临时打开 chat egress
 *       的 origin 闸，等于给「残留身份发起模型出站」开口）；任务体一律<b>不读环境身份</b>，
 *       归属事实只认受理期捕获的那一份；</li>
 *   <li>租户经 {@link TenantScopeBinder} 端口在身份作用域<b>内侧</b>绑定（先身份、后租户），
 *       端口缺席（独立应用无租户拦截器）或无捕获租户时按原形态直跑；</li>
 *   <li>外层 {@code finally} 无条件恢复线程原主体（共享池线程，泄漏＝跨租户污染；
 *       恢复而非 clear——线程既有残留是既成事实，抹掉同样在改变本不属于本任务的线程状态）；</li>
 *   <li>无捕获（无主体形态）直跑、不新增默认身份。</li>
 * </ul>
 *
 * <p><b>边界。</b>恢复出的主体 scopes 恒为空集、无同步起源——任何 scope/出站判定照旧
 * fail-closed（「后台抽取是否允许出站」属 P3 未决裁决，本类不做授权，也不给 origin）。
 */
public final class AsyncMemoryTaskScope {

    private AsyncMemoryTaskScope() {
    }

    /**
     * 在受理期捕获的身份（与同源派生租户）作用域内执行 {@code body}，退出时无条件恢复线程原主体。
     *
     * @param principalRef       受理期捕获的身份引用；{@code null}＝无主体形态，直跑
     * @param tenantId           从同一捕获派生的租户；{@code null}＝不绑租户
     * @param tenantScopeBinder  租户绑定端口（可缺席；缺席按直跑）
     * @return {@code body} 的返回值；{@code body} 抛出时异常原样上抛
     */
    public static <T> T callWithCapture(AsyncPrincipalReference principalRef, String tenantId,
                                        ObjectProvider<TenantScopeBinder> tenantScopeBinder, Supplier<T> body) {
        if (principalRef == null) {
            return runInTenant(tenantId, tenantScopeBinder, body);
        }
        ExecutionPrincipal previous = PrincipalContext.get();
        PrincipalContext.restore(principalRef.toExecutionPrincipal());
        try {
            return runInTenant(tenantId, tenantScopeBinder, body);
        } finally {
            PrincipalContext.restore(previous);
        }
    }

    /**
     * 租户绑定端口只吃 {@link Runnable}，返回值经持有位转一手；绑定端口的清理由其自身 finally 保证。
     */
    private static <T> T runInTenant(String tenantId, ObjectProvider<TenantScopeBinder> tenantScopeBinder,
                                     Supplier<T> body) {
        TenantScopeBinder binder = tenantScopeBinder == null ? null : tenantScopeBinder.getIfAvailable();
        if (binder == null || tenantId == null) {
            return body.get();
        }
        AtomicReference<T> result = new AtomicReference<>();
        binder.runWithTenant(tenantId, () -> result.set(body.get()));
        return result.get();
    }

    /**
     * 已解析端口入口（s2-tenant-cmp-a1，2026-10-12；纯增，既有链语义不动）：完成链三处
     * （轮末状态保存/终答落库/知识检索工具）的消费方手里已经是解析好的
     * {@code AgentRunScope#tenantScopeBinder()} 实例，不再经 ObjectProvider 取一次。
     * 语义与 {@link #callWithCapture(AsyncPrincipalReference, String, ObjectProvider, Supplier)}
     * 完全一致：非配对 restore（只授 holder、不授同步起源）＋租户绑定内层＋finally 复原。
     * 刻意不叫 callWithCapture：重载会让既有调用点传 null 端口时的重载决议歧义
     * （P3 判据 AsyncMemoryTaskScopeTest 即传 null，不许改动）。
     */
    public static <T> T callWithResolvedCapture(AsyncPrincipalReference principalRef, String tenantId,
                                                TenantScopeBinder tenantScopeBinder, Supplier<T> body) {
        if (principalRef == null) {
            return runInTenant(tenantId, tenantScopeBinder, body);
        }
        ExecutionPrincipal previous = PrincipalContext.get();
        PrincipalContext.restore(principalRef.toExecutionPrincipal());
        try {
            return runInTenant(tenantId, tenantScopeBinder, body);
        } finally {
            PrincipalContext.restore(previous);
        }
    }

    /**
     * 端口实例版租户绑定：与 ObjectProvider 版同语义，只吃 {@link Runnable}，返回值经持有位转一手。
     */
    private static <T> T runInTenant(String tenantId, TenantScopeBinder binder, Supplier<T> body) {
        if (binder == null || tenantId == null) {
            return body.get();
        }
        AtomicReference<T> result = new AtomicReference<>();
        binder.runWithTenant(tenantId, () -> result.set(body.get()));
        return result.get();
    }
}
