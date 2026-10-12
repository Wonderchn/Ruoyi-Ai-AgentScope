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
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import com.nageoffer.ai.ragent.framework.trace.RagStreamTraceSupport;
import com.nageoffer.ai.ragent.infra.chat.ChatEgressDeniedException;
import com.nageoffer.ai.ragent.infra.chat.ChatEgressPort;
import com.nageoffer.ai.ragent.infra.chat.DeepSeekChatClient;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.usage.EgressPolicy;
import com.nageoffer.ai.ragent.runtime.usage.EnvelopeChatEgressPort;
import com.nageoffer.ai.ragent.runtime.usage.ProviderSpendEnvelope;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F16 出站（s2-f16egress-a1）· 后台抽取出站身份的在线重建判据（红先行）。
 *
 * <p>背景（裁决⑤/设计 F16EGRESS-SCOUT §5）：抽取任务体在共享池线程上没有任何可信主体
 * （捕获引用 scopes 空、无同步起源），出站门 origin 闸（{@code EnvelopeChatEgressPort:55}）
 * 可见拒绝——真机 C2 同形。修复＝任务体内按在线事实重建出站主体（配对 set/restore）。
 *
 * <p>本文件用<b>真出站门</b>（{@code EnvelopeChatEgressPort}＋{@code EgressPolicy}）＋
 * <b>与内嵌 verifier 同形的在线重判</b>＋可翻转事实 mock：出站瞬间主体 tenant/user/membership
 * 回等、scopes 精确等于声明集（不得全量）、pv/av 取在线事实（不是捕获引用旧值）、exp 在预算窗内；
 * 重建不满足任一项即拒绝重建（退回捕获形态：无起源、出站 fail-closed、零预占），
 * 任务退出后线程原主体原样恢复、租户解绑。
 */
@Tag("dev")
class AsyncMemoryEgressRebuildTest {

    private static final String TENANT = "T-F16E";
    private static final String USER = "730101";
    private static final String MEMBERSHIP = "platform:T-F16E:730101";
    /** 捕获引用里的旧版本：重建必须换在线事实值（在线 pv/av 见 {@link #factsReady()}）。 */
    private static final int REF_PV = 3;
    private static final int REF_AV = 4;
    private static final int ONLINE_PV = 7;
    private static final int ONLINE_AV = 9;
    private static final Set<String> DECLARED = Set.of("memory.read");
    private static final String SYNC_RESPONSE =
            "{\"id\":\"provider-sync\",\"choices\":[{\"message\":{\"content\":\"ok\"}}],"
                    + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":2,\"total_tokens\":9}}";

    private final MemoryEgressSubjectFacts facts = mock(MemoryEgressSubjectFacts.class);
    private final ProviderSpendEnvelope spend = mock(ProviderSpendEnvelope.class);
    private final EgressPolicy policy = new EgressPolicy();
    private final AtomicReference<EnvelopeChatEgressPort.SubjectReference> subject = new AtomicReference<>();
    private final TenantGate gate = new TenantGate();

    @BeforeEach
    void setUp() {
        policy.setEnabled(true);
        policy.setAllowedProviders("deepseek,dashscope");
        when(spend.reserve(anyString(), anyString(), anyLong(), anyInt())).thenReturn("call-1");
        factsReady();
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
        gate.setCurrentTenant(null);
    }

    // ==================== 正例：在线重建 → 出站放行一次 ====================

    /**
     * 重建成功后：任务体内是同步起源、出站门放行一次；主体 scopes <b>精确等于</b>声明集
     * （成员即使另持 ai:kb:read/ai:agent:execute 也不得被带出）、pv/av 取在线事实、exp 在 300s 窗内；
     * 退出后线程原主体原样恢复、起源关闭、租户解绑。
     */
    @Test
    void rebuiltEgressSubjectIsOnlineLeastScopedAndAuthorizedOnce() {
        // 在线成员另持两个权限：重建只放声明集，且不得全量复制成员权限
        when(facts.currentSubject(TENANT, USER, MEMBERSHIP)).thenReturn(new MemoryEgressSubjectFacts.CurrentSubject(
                TENANT, USER, MEMBERSHIP, true,
                Set.of("ai:memory:read", "ai:kb:read", "ai:agent:execute"), ONLINE_PV));
        ExecutionPrincipal residue = residue();
        PrincipalContext.restore(residue);
        AtomicBoolean originInBody = new AtomicBoolean();

        long before = Instant.now().getEpochSecond();
        String outcome = AsyncMemoryTaskScope.callWithRebuiltEgress(ref(), TENANT,
                providerOf(gate.binder()), providerOf(facts), DECLARED, () -> {
                    originInBody.set(PrincipalContext.isSynchronousOrigin());
                    return egressProbe(portAt(Clock.systemUTC()));
                });
        long after = Instant.now().getEpochSecond();

        assertThat(outcome).as("重建成功时出站必须放行（现状红：origin 门拒绝）").isEqualTo("authorized");
        assertThat(originInBody.get()).as("任务体内必须是同步起源（裁决⑤配对 set 授予）").isTrue();
        EnvelopeChatEgressPort.SubjectReference reference = subject.get();
        assertThat(reference.tenantId()).isEqualTo(TENANT);
        assertThat(reference.userId()).isEqualTo(USER);
        assertThat(reference.membershipId()).isEqualTo(MEMBERSHIP);
        assertThat(reference.scopes()).as("scopes 精确等于声明集（最小化，不得放大）")
                .containsExactly("memory.read");
        assertThat(reference.policyVersion()).as("pv 必须取在线事实而不是捕获引用旧值").isEqualTo(ONLINE_PV);
        assertThat(reference.aclVersion()).as("av 必须取在线事实而不是捕获引用旧值").isEqualTo(ONLINE_AV);
        assertThat(reference.expiresAtEpochSecond()).isBetween(before + 1, after + 300);
        verify(spend).reserve("deepseek", "model", 4096L, 512);
        verify(spend).received(eq("call-1"), eq("provider-id"), anyMap());
        assertThat(PrincipalContext.get()).as("退出时线程原主体原样恢复（配对恢复）").isSameAs(residue);
        assertThat(PrincipalContext.isSynchronousOrigin()).isFalse();
        assertThat(gate.currentTenant()).as("租户绑定内层、退出解绑").isNull();
        assertThat(gate.boundTenants()).containsExactly(TENANT);
    }

    // ==================== 负例矩阵：任一在线事实不满足 → 拒绝重建 ====================

    /**
     * 拒绝重建＝退化为捕获形态：无同步起源（出站 origin 门可见拒绝）、holder 是 identity 引用
     * （scopes 空、issuer=async-principal-reference）、零预占、退出后原主体恢复。
     */
    @Test
    void rebuildIsRefusedWhenAnyOnlineFactIsMissingOrMismatched() {
        List<String> scenarios = List.of(
                "tenant-disabled", "tenant-unknown", "member-missing", "member-disabled",
                "echo-tenant-mismatch", "echo-user-mismatch", "echo-membership-mismatch",
                "policy-version-below-one", "acl-epoch-missing", "acl-version-below-one",
                "permission-not-held", "declared-action-not-canonical");
        for (String scenario : scenarios) {
            factsReady();
            switch (scenario) {
                case "tenant-disabled" -> when(facts.tenantState(TENANT))
                        .thenReturn(MemoryEgressSubjectFacts.TenantState.DISABLED);
                case "tenant-unknown" -> when(facts.tenantState(TENANT))
                        .thenReturn(MemoryEgressSubjectFacts.TenantState.UNKNOWN);
                case "member-missing" -> when(facts.currentSubject(TENANT, USER, MEMBERSHIP)).thenReturn(null);
                case "member-disabled" -> when(facts.currentSubject(TENANT, USER, MEMBERSHIP))
                        .thenReturn(member(false, Set.of("ai:memory:read"), ONLINE_PV));
                case "echo-tenant-mismatch" -> when(facts.currentSubject(TENANT, USER, MEMBERSHIP))
                        .thenReturn(new MemoryEgressSubjectFacts.CurrentSubject("T-OTHER", USER, MEMBERSHIP,
                                true, Set.of("ai:memory:read"), ONLINE_PV));
                case "echo-user-mismatch" -> when(facts.currentSubject(TENANT, USER, MEMBERSHIP))
                        .thenReturn(new MemoryEgressSubjectFacts.CurrentSubject(TENANT, "999999", MEMBERSHIP,
                                true, Set.of("ai:memory:read"), ONLINE_PV));
                case "echo-membership-mismatch" -> when(facts.currentSubject(TENANT, USER, MEMBERSHIP))
                        .thenReturn(new MemoryEgressSubjectFacts.CurrentSubject(TENANT, USER,
                                "platform:T-F16E:999999", true, Set.of("ai:memory:read"), ONLINE_PV));
                case "policy-version-below-one" -> when(facts.currentSubject(TENANT, USER, MEMBERSHIP))
                        .thenReturn(member(true, Set.of("ai:memory:read"), 0));
                case "acl-epoch-missing" -> when(facts.currentAclVersion(TENANT))
                        .thenThrow(new ServiceException("acl epoch is missing for tenant " + TENANT));
                case "acl-version-below-one" -> when(facts.currentAclVersion(TENANT)).thenReturn(0);
                case "permission-not-held" -> when(facts.currentSubject(TENANT, USER, MEMBERSHIP))
                        .thenReturn(member(true, Set.of("ai:kb:read"), ONLINE_PV));
                default -> { }
            }
            Set<String> declared = "declared-action-not-canonical".equals(scenario)
                    ? Set.of("memory.not-an-action") : DECLARED;
            assertRebuildRefused(scenario, declared);
        }
        verifyNoInteractions(spend);
    }

    /** facts 端口缺席（独立/旧形态）：拒绝重建、退回捕获形态——出站照旧 fail-closed、零预占。 */
    @Test
    void factsAbsentFallsBackToCaptureAndStaysFailClosed() {
        assertRebuildRefused("facts-absent", DECLARED, providerOf((MemoryEgressSubjectFacts) null));
    }

    /** 无捕获（无主体形态）：直跑、不新增默认身份；线程原主体不被触碰。 */
    @Test
    void nullCaptureRunsDirectlyWithoutInventingIdentity() {
        ExecutionPrincipal residue = residue();
        PrincipalContext.restore(residue);
        AtomicReference<ExecutionPrincipal> holderInBody = new AtomicReference<>();
        AtomicBoolean originInBody = new AtomicBoolean(true);

        String outcome = AsyncMemoryTaskScope.callWithRebuiltEgress(null, TENANT,
                providerOf(gate.binder()), providerOf(facts), DECLARED, () -> {
                    holderInBody.set(PrincipalContext.get());
                    originInBody.set(PrincipalContext.isSynchronousOrigin());
                    return egressProbe(portAt(Clock.systemUTC()));
                });

        assertThat(outcome).as("无捕获不得凭空补身份：出站仍 fail-closed").isEqualTo("denied");
        assertThat(originInBody.get()).isFalse();
        assertThat(holderInBody.get()).as("线程环境主体原样保留（直跑不借贷）").isSameAs(residue);
        assertThat(PrincipalContext.get()).isSameAs(residue);
        assertThat(gate.boundTenants()).as("无捕获形态仍按捕获租户直跑").containsExactly(TENANT);
        verifyNoInteractions(spend);
    }

    // ==================== 出站门语义：发送前重判 / 过期 / 预算 ====================

    /**
     * authorize 通过后、实际发送前事实发生变化（停用/PV 递增/AV 递增）：
     * beforeSend 在线重判必须拒绝、不得收到结算（未知结局保留预占）。
     */
    @Test
    void onlineFactsAreRevalidatedBeforeSendSoStaleSubjectsCannotSend() {
        for (String flip : List.of("member-disabled", "policy-version-advanced", "acl-version-advanced")) {
            factsReady();
            ExecutionPrincipal residue = residue();
            PrincipalContext.restore(residue);
            AtomicReference<String> outcome = new AtomicReference<>();

            AsyncMemoryTaskScope.callWithRebuiltEgress(ref(), TENANT, providerOf(gate.binder()),
                    providerOf(facts), DECLARED, () -> {
                        try {
                            ChatEgressPort.Lease lease = portAt(Clock.systemUTC()).authorize(call());
                            switch (flip) {
                                case "member-disabled" -> when(facts.currentSubject(TENANT, USER, MEMBERSHIP))
                                        .thenReturn(member(false, Set.of("ai:memory:read"), ONLINE_PV));
                                case "policy-version-advanced" -> when(facts.currentSubject(TENANT, USER, MEMBERSHIP))
                                        .thenReturn(member(true, Set.of("ai:memory:read"), ONLINE_PV + 1));
                                default -> when(facts.currentAclVersion(TENANT)).thenReturn(ONLINE_AV + 1);
                            }
                            lease.beforeSend();
                            outcome.set("sent");
                        } catch (ChatEgressDeniedException denied) {
                            outcome.set("denied");
                        }
                        return null;
                    });

            assertThat(outcome.get()).as(flip + "：发送前重判必须拒绝").isEqualTo("denied");
            PrincipalContext.clear();
        }
        // 三次 authorize 各预占一次；重判拒绝发生在发送前，不得有任何结算回执
        verify(spend, times(3)).reserve(anyString(), anyString(), anyLong(), anyInt());
        verify(spend, never()).received(anyString(), anyString(), anyMap());
    }

    /** exp 过期（端口时钟已越过 TTL）：重建成的主体会被出站门的有效期闸拒绝、零预占。 */
    @Test
    void expiredRebuiltSubjectIsDeniedBeforeReserve() {
        ExecutionPrincipal residue = residue();
        PrincipalContext.restore(residue);
        AtomicBoolean originInBody = new AtomicBoolean();

        String outcome = AsyncMemoryTaskScope.callWithRebuiltEgress(ref(), TENANT,
                providerOf(gate.binder()), providerOf(facts), DECLARED, () -> {
                    originInBody.set(PrincipalContext.isSynchronousOrigin());
                    return egressProbe(portAt(Clock.fixed(Instant.now().plusSeconds(600), ZoneOffset.UTC)));
                });

        assertThat(originInBody.get()).as("重建本身成功（过期判定在出站门，不在重建）").isTrue();
        assertThat(outcome).isEqualTo("denied");
        verify(spend, never()).reserve(anyString(), anyString(), anyLong(), anyInt());
    }

    /** 预算拒绝（reserve 抛 BUDGET_EXCEEDED）：出站终止、零发送、无结算。 */
    @Test
    void budgetFailureAfterSuccessfulRebuildDeniesWithoutReceipt() {
        when(spend.reserve(anyString(), anyString(), anyLong(), anyInt()))
                .thenThrow(new RunApiException(RunErrorCode.BUDGET_EXCEEDED));
        ExecutionPrincipal residue = residue();
        PrincipalContext.restore(residue);
        AtomicBoolean originInBody = new AtomicBoolean();

        String outcome = AsyncMemoryTaskScope.callWithRebuiltEgress(ref(), TENANT,
                providerOf(gate.binder()), providerOf(facts), DECLARED, () -> {
                    originInBody.set(PrincipalContext.isSynchronousOrigin());
                    return egressProbe(portAt(Clock.systemUTC()));
                });

        assertThat(originInBody.get()).isTrue();
        assertThat(outcome).isEqualTo("denied");
        verify(spend, never()).received(anyString(), anyString(), anyMap());
    }

    // ==================== 交错与真实客户端 ====================

    /** 两个成员的任务在同一线程交错：各自重建只认自己的在线事实；退出后原主体恢复。 */
    @Test
    void interleavedExtractionsKeepEachMembersOnlineFacts() {
        when(facts.tenantState("T-F16F")).thenReturn(MemoryEgressSubjectFacts.TenantState.ENABLED);
        when(facts.currentSubject("T-F16F", "730102", "platform:T-F16F:730102")).thenReturn(
                new MemoryEgressSubjectFacts.CurrentSubject("T-F16F", "730102",
                        "platform:T-F16F:730102", true, Set.of("ai:memory:read"), 5));
        when(facts.currentAclVersion("T-F16F")).thenReturn(6);
        ExecutionPrincipal residue = residue();
        PrincipalContext.restore(residue);
        AtomicReference<EnvelopeChatEgressPort.SubjectReference> first = new AtomicReference<>();
        AtomicReference<EnvelopeChatEgressPort.SubjectReference> second = new AtomicReference<>();

        String firstOutcome = AsyncMemoryTaskScope.callWithRebuiltEgress(ref(), TENANT,
                providerOf(gate.binder()), providerOf(facts), DECLARED, () -> {
                    String outcome = egressProbe(portAt(Clock.systemUTC()));
                    first.set(subject.get());
                    return outcome;
                });
        String secondOutcome = AsyncMemoryTaskScope.callWithRebuiltEgress(
                ref("T-F16F", "730102", 1, 1), "T-F16F",
                providerOf(gate.binder()), providerOf(facts), DECLARED, () -> {
                    String outcome = egressProbe(portAt(Clock.systemUTC()));
                    second.set(subject.get());
                    return outcome;
                });

        assertThat(firstOutcome).isEqualTo("authorized");
        assertThat(secondOutcome).isEqualTo("authorized");
        assertThat(first.get().membershipId()).isEqualTo(MEMBERSHIP);
        assertThat(first.get().policyVersion()).isEqualTo(ONLINE_PV);
        assertThat(second.get().membershipId()).isEqualTo("platform:T-F16F:730102");
        assertThat(second.get().policyVersion()).isEqualTo(5);
        assertThat(second.get().aclVersion()).isEqualTo(6);
        assertThat(PrincipalContext.get()).isSameAs(residue);
        assertThat(gate.currentTenant()).isNull();
    }

    /**
     * 真实 chat 客户端直连真出站门：重建主体下出站恰一次（HTTP 计数 1）；facts 拒绝时
     * 出站零次（ChatEgressDeniedException、零预占、零 HTTP）——与真机 C2 拒绝同形。
     */
    @Test
    void realClientEgressSendsExactlyOnceUnderRebuiltSubjectAndZeroWhenRefused() {
        // 正例：重建成功 → HTTP 恰一次
        ClientHarness ok = new ClientHarness(portAt(Clock.systemUTC()));
        ExecutionPrincipal residue = residue();
        PrincipalContext.restore(residue);
        String okOutcome = AsyncMemoryTaskScope.callWithRebuiltEgress(ref(), TENANT,
                providerOf(gate.binder()), providerOf(facts), DECLARED,
                () -> chatProbe(ok));
        assertThat(okOutcome).as("重建后真实客户端出站必须成功").isEqualTo("chat:ok");
        assertThat(ok.sends).as("HTTP 发送恰一次").hasValue(1);
        assertThat(subject.get().scopes()).containsExactly("memory.read");
        // 真实客户端按最终报文预占：model 取目标模型、字节数取实际请求体（此处只钉 provider 与输出上限）
        verify(spend).reserve(eq("deepseek"), eq("approved-model"), anyLong(), eq(512));

        // 负例：撤权（无 ai:memory:read）→ 拒绝重建 → 零 HTTP、零预占
        when(facts.currentSubject(TENANT, USER, MEMBERSHIP)).thenReturn(member(true, Set.of("ai:kb:read"), ONLINE_PV));
        ClientHarness revoked = new ClientHarness(portAt(Clock.systemUTC()));
        PrincipalContext.clear();
        PrincipalContext.restore(residue);
        String revokedOutcome = AsyncMemoryTaskScope.callWithRebuiltEgress(ref(), TENANT,
                providerOf(gate.binder()), providerOf(facts), DECLARED,
                () -> chatProbe(revoked));
        assertThat(revokedOutcome).as("撤权后出站必须可见拒绝（ChatEgressDeniedException）")
                .isEqualTo("denied:ChatEgressDeniedException");
        assertThat(revoked.sends).as("拒绝发生在任何 HTTP 之前").hasValue(0);

        // 负例：facts 端口缺席 → 拒绝重建 → 零 HTTP
        ClientHarness absent = new ClientHarness(portAt(Clock.systemUTC()));
        String absentOutcome = AsyncMemoryTaskScope.callWithRebuiltEgress(ref(), TENANT,
                providerOf(gate.binder()), providerOf((MemoryEgressSubjectFacts) null), DECLARED,
                () -> chatProbe(absent));
        assertThat(absentOutcome).isEqualTo("denied:ChatEgressDeniedException");
        assertThat(absent.sends).hasValue(0);

        verify(spend, times(1)).reserve(anyString(), anyString(), anyLong(), anyInt());
    }

    // ==================== 夹具 ====================

    /**
     * 在线事实就绪：租户启用、成员启用且持有 ai:memory:read、pv=7/av=9（与捕获引用 3/4 不同）。
     * 用 {@code doReturn} 重桩：负例矩阵里存在把 currentAclVersion 桩成 thenThrow 的场景，
     * 再以 {@code when(...)} 重桩会实地调用旧桩并抛错（夹具缺陷，非被测行为）。
     */
    private void factsReady() {
        doReturn(MemoryEgressSubjectFacts.TenantState.ENABLED).when(facts).tenantState(TENANT);
        doReturn(member(true, Set.of("ai:memory:read"), ONLINE_PV)).when(facts)
                .currentSubject(TENANT, USER, MEMBERSHIP);
        doReturn(ONLINE_AV).when(facts).currentAclVersion(TENANT);
    }

    private static MemoryEgressSubjectFacts.CurrentSubject member(boolean enabled, Set<String> scopes,
                                                                  int policyVersion) {
        return new MemoryEgressSubjectFacts.CurrentSubject(TENANT, USER, MEMBERSHIP, enabled, scopes, policyVersion);
    }

    /**
     * 拒绝重建的完整断言：出站 origin 门可见拒绝（零预占）、任务体无起源、
     * holder 退回捕获的 identity 引用（scopes 空）、退出后线程原主体恢复。
     */
    private void assertRebuildRefused(String scenario, Set<String> declared) {
        assertRebuildRefused(scenario, declared, providerOf(facts));
    }

    private void assertRebuildRefused(String scenario, Set<String> declared,
                                      ObjectProvider<MemoryEgressSubjectFacts> factsProvider) {
        ExecutionPrincipal residue = residue();
        PrincipalContext.restore(residue);
        AtomicBoolean originInBody = new AtomicBoolean(true);
        AtomicReference<ExecutionPrincipal> holderInBody = new AtomicReference<>();

        String outcome = AsyncMemoryTaskScope.callWithRebuiltEgress(ref(), TENANT,
                providerOf(gate.binder()), factsProvider, declared, () -> {
                    originInBody.set(PrincipalContext.isSynchronousOrigin());
                    holderInBody.set(PrincipalContext.get());
                    return egressProbe(portAt(Clock.systemUTC()));
                });

        assertThat(outcome).as(scenario + "：拒绝重建后出站必须 fail-closed").isEqualTo("denied");
        assertThat(originInBody.get()).as(scenario + "：拒绝重建不得授予同步起源").isFalse();
        assertThat(holderInBody.get()).as(scenario + "：退回捕获形态（identity 引用）")
                .satisfies(holder -> {
                    assertThat(holder.scopes()).isEmpty();
                    assertThat(holder.issuer()).isEqualTo(AsyncPrincipalReference.ASYNC_REF_ISSUER);
                });
        assertThat(PrincipalContext.get()).as(scenario + "：退出后原主体恢复").isSameAs(residue);
        PrincipalContext.clear();
    }

    /** 与内嵌 verifier（AiEmbeddedModelConfiguration.chatEgressPort）同形的单次出站探针。 */
    private String egressProbe(EnvelopeChatEgressPort port) {
        try {
            ChatEgressPort.Lease lease = port.authorize(call());
            lease.beforeSend();
            lease.received("provider-id", Map.of("total_tokens", 9));
            return "authorized";
        } catch (ChatEgressDeniedException denied) {
            return "denied";
        }
    }

    private String chatProbe(ClientHarness harness) {
        try {
            return "chat:" + harness.client.chat(ChatRequest.builder().maxTokens(512).build(), harness.target);
        } catch (Exception e) {
            return "denied:" + e.getClass().getSimpleName();
        }
    }

    /** 与内嵌 verifier 同形的在线重判：租户启用＋成员回等＋pv/av 相等＋逐动作权限精确包含。 */
    private EnvelopeChatEgressPort portAt(Clock clock) {
        return new EnvelopeChatEgressPort(policy, spend, reference -> {
            subject.set(reference);
            if (facts.tenantState(reference.tenantId()) != MemoryEgressSubjectFacts.TenantState.ENABLED) {
                throw new ChatEgressDeniedException("chat subject unavailable");
            }
            MemoryEgressSubjectFacts.CurrentSubject member =
                    facts.currentSubject(reference.tenantId(), reference.userId(), reference.membershipId());
            if (member == null || !member.enabled()
                    || !reference.tenantId().equals(member.tenantId())
                    || !reference.userId().equals(member.subject())
                    || !reference.membershipId().equals(member.membershipId())
                    || member.policyVersion() != reference.policyVersion()
                    || facts.currentAclVersion(reference.tenantId()) != reference.aclVersion()
                    || member.scopes() == null || reference.scopes().isEmpty()) {
                throw new ChatEgressDeniedException("chat subject unavailable");
            }
            for (String action : reference.scopes()) {
                String permission = org.ruoyi.ai.api.action.AiCanonicalAction.permissionOf(action).orElse(null);
                if (permission == null || !member.scopes().contains(permission)) {
                    throw new ChatEgressDeniedException("chat scope unavailable");
                }
            }
        }, clock);
    }

    private static ChatEgressPort.CallSpec call() {
        return new ChatEgressPort.CallSpec("deepseek", "model",
                URI.create("https://api.deepseek.com/chat/completions"), 4096, 512);
    }

    private static ExecutionPrincipal residue() {
        return new ExecutionPrincipal("T-RESIDUE", "920001", "platform:T-RESIDUE:920001",
                1, 1, Set.of(), "f16e-residue", "test", 1, Long.MAX_VALUE);
    }

    private static AsyncPrincipalReference ref() {
        return ref(TENANT, USER, REF_PV, REF_AV);
    }

    private static AsyncPrincipalReference ref(String tenantId, String userId, int policyVersion, int aclVersion) {
        ExecutionPrincipal principal = new ExecutionPrincipal(tenantId, userId,
                "platform:" + tenantId + ":" + userId, policyVersion, aclVersion, Set.of(),
                "f16e-ref", "platform", 1, 1_700_000_000L);
        return AsyncPrincipalReference.of(principal, 1_700_000_000_000L);
    }

    /** 真实 chat 客户端＋拦截 HTTP：零网络发送的计数与内容由拦截器回答。 */
    private static final class ClientHarness {

        private final DeepSeekChatClient client = new DeepSeekChatClient();
        private final AtomicInteger sends = new AtomicInteger();
        private final ModelTarget target;

        private ClientHarness(EnvelopeChatEgressPort port) {
            AIModelProperties.ModelCandidate model = new AIModelProperties.ModelCandidate();
            model.setProvider("deepseek");
            model.setModel("approved-model");
            AIModelProperties.ProviderConfig provider = new AIModelProperties.ProviderConfig();
            provider.setApiKey("test-only");
            provider.setUrl("https://api.deepseek.com");
            provider.setEndpoints(Map.of("chat", "/chat/completions"));
            this.target = new ModelTarget("test", model, provider, null);

            OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
                sends.incrementAndGet();
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK")
                        .body(ResponseBody.create(SYNC_RESPONSE, MediaType.get("application/json")))
                        .build();
            }).build();
            ReflectionTestUtils.setField(client, "syncHttpClient", http);
            ReflectionTestUtils.setField(client, "streamingHttpClient", http);
            ReflectionTestUtils.setField(client, "modelStreamExecutor", (Executor) Runnable::run);
            ReflectionTestUtils.setField(client, "streamTraceSupport",
                    (RagStreamTraceSupport) (name, type) -> RagStreamTraceSupport.NOOP_SPAN);
            ReflectionTestUtils.setField(client, "chatEgressPort", port);
        }
    }

    /** 租户门 fake（TenantHelper.dynamic 的等价镜像）：动态租户按线程保存，仅 binder 内非空。 */
    private static final class TenantGate {

        private final ThreadLocal<String> current = new ThreadLocal<>();
        private final List<String> boundTenants = new CopyOnWriteArrayList<>();

        private String currentTenant() {
            return current.get();
        }

        private void setCurrentTenant(String tenantId) {
            if (tenantId == null) {
                current.remove();
            } else {
                current.set(tenantId);
            }
        }

        private List<String> boundTenants() {
            return boundTenants;
        }

        private void runWithTenant(String tenantId, Runnable handle) {
            boundTenants.add(tenantId);
            String previous = current.get();
            current.set(tenantId);
            try {
                handle.run();
            } finally {
                if (previous == null) {
                    current.remove();
                } else {
                    current.set(previous);
                }
            }
        }

        private TenantScopeBinder binder() {
            return this::runWithTenant;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
