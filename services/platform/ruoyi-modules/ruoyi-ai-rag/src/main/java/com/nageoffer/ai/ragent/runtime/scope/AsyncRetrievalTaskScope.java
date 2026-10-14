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

package com.nageoffer.ai.ragent.runtime.scope;

import com.nageoffer.ai.ragent.framework.context.AsyncPrincipalReference;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 异步检索/意图任务的身份/租户作用域（BD-1，2026-10-14）。
 *
 * <p><b>为什么需要。</b>eval 检索链与意图分类链（{@code GET /internal/ai/v1/rag/eval} →
 * {@code IntentResolver.resolve}/{@code RetrievalEngine.retrieve}）都在受理线程
 * {@code supplyAsync} 到 TTL 线程池（{@code intent_classify_executor_*} /
 * {@code rag_context_executor_*}）。跳变之后：动态租户是普通 ThreadLocal、不跨线程，
 * 池线程上平台 MyBatis 租户行拦截器对 {@code ai_intent_node}/{@code ai_knowledge_base}
 * fail-closed（真机 r17/p2win logs/sys-console.log 02:55:39 起，
 * {@code tenant.context.missing}）——意图分类恒降级空意图、检索空转 hasKb=false。
 * 修复＝受理线程捕获身份引用，任务体内重绑后再执行。
 *
 * <p><b>形状（与 agent 模块 {@code AsyncMemoryTaskScope} 同族；模块边界内 rag 不可见
 * 该类，故在本模块按同形实现，机件逐条对应）。</b>
 * <ul>
 *   <li>捕获纪律（同 {@code AgentRunScope.capture}）：只在受理线程、且
 *       {@link PrincipalContext#isSynchronousOrigin()} 为真时成捕获——池化残留与非配对
 *       {@code restore} 拷贝不是可信在场主体，一律返回 {@code null}＝任务体按原形态
 *       直跑，既不新增默认身份，也绝不错绑他人身份；</li>
 *   <li>身份用<b>非配对</b> {@link PrincipalContext#restore(ExecutionPrincipal)} 重绑：
 *       只授 holder、不授同步起源（勿用 {@code set()}——共享池线程上会临时打开 chat
 *       egress 的 origin 闸）；租户经 {@link TenantScopeBinder} 端口在身份作用域<b>内侧</b>
 *       绑定（先身份、后租户），端口缺席（独立应用无租户拦截器）或无捕获租户时按原形态
 *       直跑；</li>
 *   <li>外层 {@code finally} 无条件恢复线程原主体（共享池线程，泄漏＝跨租户污染；
 *       恢复而非 clear——线程既有残留是既成事实）；</li>
 *   <li>意图分类链任务体含 LLM 出站：该链用 {@link #callWithRebuiltEgress}（后台记忆抽取
 *       flush 同形、维护者裁决⑤同口径——仅在线重建成真实当前主体后才以配对
 *       {@code set()/restore()} 授予同步起源）；检索链纯读（无 LLM 出站）用
 *       {@link #callWithResolvedCapture}（非配对 restore，C2 fail-closed 语义保持）。</li>
 * </ul>
 *
 * <p><b>与 agent 模块变体的两点差异（均在边界内）。</b>①声明集参数直接吃<b>权限串</b>
 * （如 {@code ai:kb:retrieve}）而非动作名——动作名→权限映射在 ruoyi-ai-api
 * （{@code AiCanonicalAction}），rag 模块不可见，映射由声明方（调用点常量）以注释锚定；
 * ②捕获以 {@link Capture} record 承载（引用＋同源派生租户一起流转），防两处来源漂移。
 */
public final class AsyncRetrievalTaskScope {

    private AsyncRetrievalTaskScope() {
    }

    /**
     * 受理期捕获（请求线程、同步起源）：身份引用＋同源派生租户。
     * 非同步起源或无主体返回 {@code null}＝无捕获形态，任务体按原形态直跑。
     */
    public record Capture(AsyncPrincipalReference principalRef, String tenantId) {

        public static Capture capture() {
            if (!PrincipalContext.isSynchronousOrigin()) {
                return null;
            }
            AsyncPrincipalReference principalRef = AsyncPrincipalReference.from(PrincipalContext.require());
            return new Capture(principalRef, principalRef.tenantId());
        }
    }

    /**
     * 在受理期捕获的身份（与同源派生租户）作用域内执行 {@code body}，退出时无条件恢复线程原主体。
     * 纯读链（RAG 检索）形状：非配对 restore——只授 holder、不授同步起源。
     *
     * @param capture           受理期捕获；{@code null}＝无捕获形态，直跑
     * @param tenantScopeBinder 租户绑定端口（可缺席；缺席按直跑）
     * @return {@code body} 的返回值；{@code body} 抛出时异常原样上抛
     */
    public static <T> T callWithResolvedCapture(Capture capture,
                                                ObjectProvider<TenantScopeBinder> tenantScopeBinder,
                                                Supplier<T> body) {
        if (capture == null) {
            return body.get();
        }
        ExecutionPrincipal previous = PrincipalContext.get();
        PrincipalContext.restore(capture.principalRef().toExecutionPrincipal());
        try {
            return runInTenant(capture.tenantId(), tenantScopeBinder, body);
        } finally {
            PrincipalContext.restore(previous);
        }
    }

    /**
     * 意图分类链的「出站身份重建」变体（flush 同形、裁决⑤同口径）：先按在线事实重建出站
     * 主体，重建成功则以配对 {@code set()/restore()} 授予同步起源；退出时无条件恢复线程原主体。
     *
     * <p><b>重建序列（仅在此任务体、只按捕获引用定位；不读环境身份、不复用捕获 scopes）。</b>
     * <ol>
     *   <li>facts 端口缺席 → 拒绝重建；</li>
     *   <li>租户必须在线事实 ENABLED；</li>
     *   <li>成员事实必须存在且启用，tenant/user/membership 三键回等；</li>
     *   <li>pv 取 {@code subject.policyVersion()≥1}；av 取在线 {@code currentAclVersion}（≥1；
     *       缺行/读错即拒绝，不默认）；</li>
     *   <li>声明的权限集逐个属于成员当前在线 scopes，否则拒绝（早于出站、零 provider 请求；
     *       出站 verifier 仍最终权威）；</li>
     *   <li>构造 {@code ExecutionPrincipal(..., scopes=声明权限集, jti="retrieval-egress:"+issuedAt,
     *       issuer="platform", issuedAt=now, expiresAt=now+300s)}（覆盖 STANDARD 档预算）。</li>
     * </ol>
     *
     * <p><b>拒绝重建（任一项不满足或事实不可用）＝退化为捕获形态</b>：非配对 {@code restore}
     * 只授 holder、不授起源，出站照旧 fail-closed；绝不回落环境身份、捕获 scopes 或默认版本。
     * 无捕获（{@code capture == null}）按原形态直跑、不新增默认身份。
     *
     * @param capture            受理期捕获；{@code null}＝无捕获形态，直跑
     * @param tenantScopeBinder  租户绑定端口（可缺席；缺席按直跑）
     * @param egressFacts        只读出站事实端口（可缺席；缺席＝拒绝重建）
     * @param declaredPermissions 本次出站声明的权限集（重建后即为主体 scopes；不得全量或加宽）
     * @return {@code body} 的返回值；{@code body} 抛出时异常原样上抛
     */
    public static <T> T callWithRebuiltEgress(Capture capture,
                                              ObjectProvider<TenantScopeBinder> tenantScopeBinder,
                                              ObjectProvider<RetrievalEgressSubjectFacts> egressFacts,
                                              Set<String> declaredPermissions, Supplier<T> body) {
        if (capture == null) {
            return body.get();
        }
        ExecutionPrincipal rebuilt = rebuildEgressPrincipal(capture.principalRef(), egressFacts, declaredPermissions);
        ExecutionPrincipal previous = PrincipalContext.get();
        if (rebuilt == null) {
            // 拒绝重建：退化为捕获形态（非配对 restore——只授 holder、不授同步起源）
            PrincipalContext.restore(capture.principalRef().toExecutionPrincipal());
        } else {
            // 裁决⑤具名例外同口径：仅本任务体、仅在线重建成真实当前主体后 set()（配对恢复见 finally）
            PrincipalContext.set(rebuilt);
        }
        try {
            return runInTenant(capture.tenantId(), tenantScopeBinder, body);
        } finally {
            PrincipalContext.restore(previous);
        }
    }

    /** 出站主体有效期（秒）：覆盖 STANDARD 档 120s 调用预算并留余量；与 flush 变体同为 UTC 时刻语义。 */
    private static final long EGRESS_SUBJECT_TTL_SECONDS = 300;

    /**
     * 按在线事实重建出站主体；任一项不满足或事实不可用返回 {@code null}（调用方拒绝重建）。
     * 刻意不抛异常：事实不可用是 fail-closed 的常态路径，不是错误传播路径；
     * 只按捕获引用定位（不读环境身份）、只声明权限集（不复制成员全量权限）。
     */
    private static ExecutionPrincipal rebuildEgressPrincipal(AsyncPrincipalReference principalRef,
                                                             ObjectProvider<RetrievalEgressSubjectFacts> egressFacts,
                                                             Set<String> declaredPermissions) {
        RetrievalEgressSubjectFacts facts = egressFacts == null ? null : egressFacts.getIfAvailable();
        if (facts == null || declaredPermissions == null || declaredPermissions.isEmpty()) {
            return null;
        }
        try {
            if (facts.tenantState(principalRef.tenantId()) != RetrievalEgressSubjectFacts.TenantState.ENABLED) {
                return null;
            }
            RetrievalEgressSubjectFacts.CurrentSubject subject = facts.currentSubject(
                    principalRef.tenantId(), principalRef.userId(), principalRef.membershipId());
            if (subject == null || !subject.enabled()
                    || !principalRef.tenantId().equals(subject.tenantId())
                    || !principalRef.userId().equals(subject.subject())
                    || !principalRef.membershipId().equals(subject.membershipId())
                    || subject.policyVersion() < 1) {
                return null;
            }
            if (subject.scopes() == null || !subject.scopes().containsAll(declaredPermissions)) {
                return null;
            }
            int aclVersion = facts.currentAclVersion(principalRef.tenantId());
            if (aclVersion < 1) {
                return null;
            }
            long issuedAt = Instant.now().getEpochSecond();
            return new ExecutionPrincipal(principalRef.tenantId(), principalRef.userId(),
                    principalRef.membershipId(), subject.policyVersion(), aclVersion,
                    Set.copyOf(declaredPermissions), "retrieval-egress:" + issuedAt, "platform",
                    issuedAt, issuedAt + EGRESS_SUBJECT_TTL_SECONDS);
        } catch (RuntimeException unavailableFacts) {
            // 事实源不可用（含 ACL 缺行/读错/身份源缺席）：拒绝重建，不新增默认身份
            return null;
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
}
