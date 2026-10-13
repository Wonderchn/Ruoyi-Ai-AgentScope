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
import org.ruoyi.ai.api.action.AiCanonicalAction;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Set;
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
 * <p><b>边界。</b>除 {@link #callWithRebuiltEgress} 外，本类恢复出的主体 scopes 恒为空集、
 * 无同步起源——任何 scope/出站判定照旧 fail-closed，不回落环境身份或捕获 scopes。
 * 唯一例外是 {@link #callWithRebuiltEgress}：维护者裁决⑤具名授权——仅后台抽取任务作用域、
 * 仅在线重建成真实当前主体后才以配对 {@code set()/restore()} 授予同步起源（契约见该方法）。
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
     * 后台记忆抽取的「出站身份重建」变体（F16 出站，s2-f16egress-a1，2026-10-12）。
     *
     * <p>在受理期捕获的引用作用域内执行 {@code body}：先按在线事实重建出站主体（裁决⑤），
     * 重建成功则以配对 {@code set()/restore()} 授予同步起源；退出时无条件恢复线程原主体。
     *
     * <p><b>重建序列（仅在此任务体、只按捕获引用定位；不读环境身份、不复用捕获 scopes）。</b>
     * <ol>
     *   <li>facts 端口缺席 → 拒绝重建；</li>
     *   <li>租户必须在线事实 ENABLED；</li>
     *   <li>成员事实必须存在且启用，tenant/user/membership 三键回等；</li>
     *   <li>pv 取 {@code identity.policyVersion()≥1}；av 取在线 {@code currentAclVersion}（≥1；
     *       缺行/读错即拒绝，不默认）；</li>
     *   <li>对声明的动作集逐个 {@code AiCanonicalAction.permissionOf} 可解且 identity.scopes 包含，
     *       否则拒绝（早于出站、零 provider 请求；出站 verifier 仍最终权威）；</li>
     *   <li>构造 {@code ExecutionPrincipal(..., scopes=声明动作集, jti="memory-egress:"+issuedAt,
     *       issuer="platform", issuedAt=now, expiresAt=now+300s)}（覆盖 STANDARD 档预算）。</li>
     * </ol>
     *
     * <p><b>拒绝重建（任一项不满足或事实不可用）＝退化为捕获形态</b>：非配对 {@code restore}
     * 只授 holder、不授起源，出站照旧 fail-closed；绝不回落环境身份、捕获 scopes 或默认版本。
     * 无捕获（{@code principalRef == null}）按原形态直跑、不新增默认身份。
     *
     * <p><b>裁决⑤具名例外边界。</b>配对 {@code set()/restore()} 仅限本后台抽取任务作用域、
     * 仅在线重建成真实当前主体后才开、且配对恢复；其余异步链（完成链三处、注入链）维持
     * 非配对 restore 纪律不变。
     *
     * @param principalRef      受理期捕获的身份引用；{@code null}＝无主体形态，直跑
     * @param tenantId          从同一捕获派生的租户；{@code null}＝不绑租户
     * @param tenantScopeBinder 租户绑定端口（可缺席；缺席按直跑）
     * @param egressFacts       只读出站事实端口（可缺席；缺席＝拒绝重建）
     * @param declaredActions   本次出站声明的动作集（重建后即为主体 scopes；不得全量或加宽）
     * @return {@code body} 的返回值；{@code body} 抛出时异常原样上抛
     */
    public static <T> T callWithRebuiltEgress(AsyncPrincipalReference principalRef, String tenantId,
                                              ObjectProvider<TenantScopeBinder> tenantScopeBinder,
                                              ObjectProvider<MemoryEgressSubjectFacts> egressFacts,
                                              Set<String> declaredActions, Supplier<T> body) {
        if (principalRef == null) {
            return runInTenant(tenantId, tenantScopeBinder, body);
        }
        ExecutionPrincipal rebuilt = rebuildEgressPrincipal(principalRef, egressFacts, declaredActions);
        ExecutionPrincipal previous = PrincipalContext.get();
        if (rebuilt == null) {
            // 拒绝重建：退化为捕获形态（非配对 restore——只授 holder、不授同步起源）
            PrincipalContext.restore(principalRef.toExecutionPrincipal());
        } else {
            // 裁决⑤具名例外：仅本后台抽取任务、仅在线重建成真实当前主体后 set()（配对恢复见 finally）
            PrincipalContext.set(rebuilt);
        }
        try {
            return runInTenant(tenantId, tenantScopeBinder, body);
        } finally {
            PrincipalContext.restore(previous);
        }
    }

    /** 出站主体有效期（秒）：覆盖 STANDARD 档 120s 调用预算并留余量；与出站端口时钟同为 UTC 时刻语义。 */
    private static final long EGRESS_SUBJECT_TTL_SECONDS = 300;

    /**
     * 按在线事实重建出站主体；任一项不满足或事实不可用返回 {@code null}（调用方拒绝重建）。
     * 刻意不抛异常：事实不可用是 fail-closed 的常态路径，不是错误传播路径；
     * 只按捕获引用定位（不读环境身份）、只声明动作集（不复制成员全量权限）。
     */
    private static ExecutionPrincipal rebuildEgressPrincipal(AsyncPrincipalReference principalRef,
                                                             ObjectProvider<MemoryEgressSubjectFacts> egressFacts,
                                                             Set<String> declaredActions) {
        MemoryEgressSubjectFacts facts = egressFacts == null ? null : egressFacts.getIfAvailable();
        if (facts == null || declaredActions == null || declaredActions.isEmpty()) {
            return null;
        }
        try {
            if (facts.tenantState(principalRef.tenantId()) != MemoryEgressSubjectFacts.TenantState.ENABLED) {
                return null;
            }
            MemoryEgressSubjectFacts.CurrentSubject subject = facts.currentSubject(
                    principalRef.tenantId(), principalRef.userId(), principalRef.membershipId());
            if (subject == null || !subject.enabled()
                    || !principalRef.tenantId().equals(subject.tenantId())
                    || !principalRef.userId().equals(subject.subject())
                    || !principalRef.membershipId().equals(subject.membershipId())
                    || subject.policyVersion() < 1) {
                return null;
            }
            for (String action : declaredActions) {
                String permission = AiCanonicalAction.permissionOf(action).orElse(null);
                if (permission == null || subject.scopes() == null || !subject.scopes().contains(permission)) {
                    return null;
                }
            }
            int aclVersion = facts.currentAclVersion(principalRef.tenantId());
            if (aclVersion < 1) {
                return null;
            }
            long issuedAt = Instant.now().getEpochSecond();
            return new ExecutionPrincipal(principalRef.tenantId(), principalRef.userId(),
                    principalRef.membershipId(), subject.policyVersion(), aclVersion,
                    Set.copyOf(declaredActions), "memory-egress:" + issuedAt, "platform",
                    issuedAt, issuedAt + EGRESS_SUBJECT_TTL_SECONDS);
        } catch (RuntimeException unavailableFacts) {
            // 事实源不可用（含 ACL 缺行/读错/身份源缺席）：拒绝重建，不新增默认身份
            return null;
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
