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

package com.nageoffer.ai.ragent.agent.tool;

import com.nageoffer.ai.ragent.agent.identity.AgentRunScope;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryOutcome;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryPipeline;
import com.nageoffer.ai.ragent.agent.memory.MemoryEgressSubjectFacts;
import com.nageoffer.ai.ragent.agent.trace.AgentTraceContextKeys;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import com.nageoffer.ai.ragent.infra.chat.ChatEgressDeniedException;
import com.nageoffer.ai.ragent.infra.chat.ChatEgressPort;
import com.nageoffer.ai.ragent.runtime.usage.EgressPolicy;
import com.nageoffer.ai.ragent.runtime.usage.EnvelopeChatEgressPort;
import com.nageoffer.ai.ragent.runtime.usage.ProviderSpendEnvelope;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-AC6-7 · flush 工具体身份/租户显式化判据（红样本＝真机 19:19:14/19:30:52 档，
 * `r17/p1win/runs/AC6/16-APPROVE-REV11-VERDICT.md`）。
 *
 * <p>修复形态：flush 全链含 LLM 出站（judge/consolidator）——工具体必须以受理期载体
 * 走<b>在线重建式</b>（{@code callWithRebuiltEgress}，声明集恰为 {@code memory.read}）；
 * capture 形会在出站门被拒（E5 同栈）。载体缺席＝原形态直跑（fail-closed，不凭空补身份）。
 * 跨租户负例：T2 载体对 T1-only 在线事实 → 重建 null → 退化 capture → 出站拒绝而 DB 按
 * T2 通过——DAO 键只见 T2 身份。
 */
@Tag("dev")
class MemoryFlushToolIdentityBindingTest {

    private static final String CONVERSATION_ID = "3001";
    private static final String REQUEST_ID = "4001";

    private final java.util.concurrent.atomic.AtomicReference<String> gateTenant =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** 落库点双门（与既有 binding 测试同形）：require() ＋「当前租户仅在 binder 内非空」。 */
    private String observeAtWritePoint() {
        try {
            ExecutionPrincipal principal = PrincipalContext.require();
            String tenant = gateTenant.get();
            if (tenant == null || tenant.isBlank()) {
                throw new ClientException("tenant.context.missing");
            }
            return "persisted:" + tenant + ":" + principal.tenantId() + ":" + principal.membershipId();
        } catch (Exception e) {
            return "failed:" + e.getMessage();
        }
    }

    private TenantScopeBinder binderOf() {
        return new TenantScopeBinder() {
            @Override
            public void runWithTenant(String tenantId, Runnable handle) {
                String previous = gateTenant.get();
                gateTenant.set(tenantId);
                try {
                    handle.run();
                } finally {
                    if (previous == null) {
                        gateTenant.set(null);
                    } else {
                        gateTenant.set(previous);
                    }
                }
            }
        };
    }

    private void setPrincipal(String tenantId, String userId) {
        PrincipalContext.set(new ExecutionPrincipal(
                tenantId, userId, "platform:" + tenantId + ":" + userId,
                1, 1, Set.of(), "fac67-tool-test", "test", 1, Long.MAX_VALUE));
    }

    private static <T> ObjectProvider<T> providerOf(Class<T> type, T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }
        };
    }

    /** 出站门（真类，与既有 binding 测试同构造）；verifier 记录放行时的 scopes。 */
    private EnvelopeChatEgressPort egressPort(MemoryEgressSubjectFacts facts,
                                              AtomicReference<Set<String>> seenScopes,
                                              AtomicReference<String> outcome) {
        ProviderSpendEnvelope spend = mock(ProviderSpendEnvelope.class);
        when(spend.reserve(anyString(), anyString(), anyLong(), anyInt())).thenReturn("call-fac67");
        EgressPolicy policy = new EgressPolicy();
        policy.setEnabled(true);
        policy.setAllowedProviders("deepseek,dashscope");
        return new EnvelopeChatEgressPort(policy, spend, reference -> {
            seenScopes.set(reference.scopes());
            if (facts.tenantState(reference.tenantId()) != MemoryEgressSubjectFacts.TenantState.ENABLED) {
                throw new ChatEgressDeniedException("chat subject unavailable");
            }
            MemoryEgressSubjectFacts.CurrentSubject member = facts.currentSubject(
                    reference.tenantId(), reference.userId(), reference.membershipId());
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
            outcome.set("authorized");
        }, java.time.Clock.systemUTC());
    }

    private MemoryEgressSubjectFacts factsFor(String tenant, String userId, String membership) {
        MemoryEgressSubjectFacts facts = mock(MemoryEgressSubjectFacts.class);
        when(facts.tenantState(tenant)).thenReturn(MemoryEgressSubjectFacts.TenantState.ENABLED);
        when(facts.currentSubject(tenant, userId, membership)).thenReturn(
                new MemoryEgressSubjectFacts.CurrentSubject(
                        tenant, userId, membership, true, Set.of("ai:memory:read"), 7));
        when(facts.currentAclVersion(tenant)).thenReturn(9);
        return facts;
    }

    private ToolCallParam paramWith(RuntimeContext runtimeContext) {
        return ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder()
                        .id("call-fac67")
                        .name(MemoryFlushTool.TOOL_NAME)
                        .input(Map.of())
                        .build())
                .input(Map.of())
                .runtimeContext(runtimeContext)
                .build();
    }

    private String textOf(ToolResultBlock block) {
        if (block == null || block.getOutput() == null) {
            return "";
        }
        return block.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(b -> ((TextBlock) b).getText())
                .collect(java.util.stream.Collectors.joining());
    }

    /**
     * 载体在：flush 的 DB 双门必须见捕获身份/租户；LLM 出站经在线重建恰一次放行、
     * scopes 精确等于声明集；出站瞬间同步起源成立、返回后线程复原（不残留起源）。
     */
    @Test
    void flushMustUseRebuiltEgressAndBindCapturedIdentity() throws Exception {
        java.util.concurrent.CountDownLatch observed = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<String> persisted = new AtomicReference<>();
        AtomicReference<Boolean> originInBody = new AtomicReference<>();
        AtomicReference<String> egressOutcome = new AtomicReference<>();
        AtomicReference<Set<String>> seenScopes = new AtomicReference<>();

        MemoryEgressSubjectFacts facts = factsFor("T-FAC67", "910101", "platform:T-FAC67:910101");
        EnvelopeChatEgressPort egressPort = egressPort(facts, seenScopes, egressOutcome);

        AgentMemoryPipeline memoryPipeline = mock(AgentMemoryPipeline.class);
        when(memoryPipeline.flush(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            persisted.set(observeAtWritePoint());
            originInBody.set(PrincipalContext.isSynchronousOrigin());
            try {
                ChatEgressPort.Lease lease = egressPort.authorize(new ChatEgressPort.CallSpec(
                        "deepseek", "model", URI.create("https://api.deepseek.com/chat/completions"), 4096, 512));
                lease.beforeSend();
                lease.received("provider-id", Map.of("total_tokens", 9));
            } catch (Exception e) {
                // 已在 verifier 内记 outcome
            }
            observed.countDown();
            return new AgentMemoryOutcome(AgentMemoryOutcome.Status.SETTLED_EMPTY, 0, 1, false);
        });
        TenantScopeBinder binder = binderOf();
        MemoryFlushTool tool = new MemoryFlushTool("记忆整理工具描述", memoryPipeline,
                providerOf(TenantScopeBinder.class, binder),
                providerOf(MemoryEgressSubjectFacts.class, facts));

        setPrincipal("T-FAC67", "910101");
        AgentRunScope scope = AgentRunScope.capture(providerOf(TenantScopeBinder.class, binder));
        PrincipalContext.clear();
        RuntimeContext runtimeContext = RuntimeContext.builder().userId("910101").sessionId(CONVERSATION_ID).build();
        runtimeContext.put(AgentRunScope.RUNTIME_CONTEXT_KEY, scope);
        runtimeContext.put(AgentTraceContextKeys.REPLY_TO_MESSAGE_ID, REQUEST_ID);

        ToolResultBlock result = tool.callAsync(paramWith(runtimeContext)).block(java.time.Duration.ofSeconds(10));

        assertThat(observed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(persisted.get())
                .as("flush 的 DB 双门必须见受理期捕获身份/租户")
                .isEqualTo("persisted:T-FAC67:T-FAC67:platform:T-FAC67:910101");
        assertThat(egressOutcome.get()).as("LLM 出站必须在线重建后放行恰一次").isEqualTo("authorized");
        assertThat(originInBody.get()).as("重建形态在任务体内授受限起源").isTrue();
        assertThat(seenScopes.get()).as("出站 scopes 精确等于声明集（动作口径，verifier 映射权限）").containsExactly("memory.read");
        assertThat(PrincipalContext.isSynchronousOrigin()).as("返回后线程复原，不残留起源").isFalse();
        assertThat(textOf(result)).contains("整理完");
    }

    /**
     * 载体缺席：原形态直跑停在双门内（fail-closed）、flush 如实报失败——修复不得凭空补身份。
     */
    @Test
    void flushWithoutCarrierStaysFailClosed() throws Exception {
        java.util.concurrent.CountDownLatch observed = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<String> persisted = new AtomicReference<>();
        AgentMemoryPipeline memoryPipeline = mock(AgentMemoryPipeline.class);
        when(memoryPipeline.flush(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            persisted.set(observeAtWritePoint());
            observed.countDown();
            // 真链形态：门拒绝时管线抛 tenant.context.missing，工具体 catch 后如实报失败
            throw new com.nageoffer.ai.ragent.framework.exception.ClientException("tenant.context.missing");
        });
        MemoryFlushTool tool = new MemoryFlushTool("记忆整理工具描述", memoryPipeline,
                providerOf(TenantScopeBinder.class, null),
                providerOf(MemoryEgressSubjectFacts.class, null));

        PrincipalContext.clear();
        RuntimeContext runtimeContext = RuntimeContext.builder().userId("910101").sessionId(CONVERSATION_ID).build();
        runtimeContext.put(AgentTraceContextKeys.REPLY_TO_MESSAGE_ID, REQUEST_ID);

        ToolResultBlock result = tool.callAsync(paramWith(runtimeContext)).block(java.time.Duration.ofSeconds(10));

        assertThat(observed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(persisted.get()).as("载体缺席＝直跑停门内").startsWith("failed:");
        assertThat(textOf(result)).contains("未能写入");
    }

    /**
     * 跨租户负例：T2 载体对 T1-only 在线事实 → 重建 null → 退化 capture（出站拒绝）
     * 而 DB 门按 T2 通过——DAO 键只见 T2 身份，绝不混入 T1/A 侧。
     */
    @Test
    void flushCrossTenantFactsMismatchDegradesAndDeniesEgress() throws Exception {
        java.util.concurrent.CountDownLatch observed = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<String> persisted = new AtomicReference<>();
        AtomicReference<Boolean> originInBody = new AtomicReference<>();
        AtomicReference<String> egressOutcome = new AtomicReference<>();
        AtomicReference<Set<String>> seenScopes = new AtomicReference<>();

        // 在线事实只配 T1/A 侧——T2/B run 的重建三元组查不到 → 重建 null → 退化 capture
        MemoryEgressSubjectFacts facts = factsFor("T-FAC67A", "910101", "platform:T-FAC67A:910101");
        EnvelopeChatEgressPort egressPort = egressPort(facts, seenScopes, egressOutcome);

        AgentMemoryPipeline memoryPipeline = mock(AgentMemoryPipeline.class);
        when(memoryPipeline.flush(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            persisted.set(observeAtWritePoint());
            originInBody.set(PrincipalContext.isSynchronousOrigin());
            try {
                ChatEgressPort.Lease lease = egressPort.authorize(new ChatEgressPort.CallSpec(
                        "deepseek", "model", URI.create("https://api.deepseek.com/chat/completions"), 4096, 512));
                lease.beforeSend();
                egressOutcome.set("authorized");
            } catch (Exception e) {
                egressOutcome.set("denied:" + e.getClass().getSimpleName());
            }
            observed.countDown();
            // 真链形态：门拒绝时管线抛 tenant.context.missing，工具体 catch 后如实报失败
            throw new com.nageoffer.ai.ragent.framework.exception.ClientException("tenant.context.missing");
        });
        TenantScopeBinder binder = binderOf();
        MemoryFlushTool tool = new MemoryFlushTool("记忆整理工具描述", memoryPipeline,
                providerOf(TenantScopeBinder.class, binder),
                providerOf(MemoryEgressSubjectFacts.class, facts));

        // T2 run：受理期捕获的是 T2/B 的身份
        setPrincipal("T-FAC67B", "910104");
        AgentRunScope scope = AgentRunScope.capture(providerOf(TenantScopeBinder.class, binder));
        PrincipalContext.clear();
        RuntimeContext runtimeContext = RuntimeContext.builder().userId("910104").sessionId(CONVERSATION_ID).build();
        runtimeContext.put(AgentRunScope.RUNTIME_CONTEXT_KEY, scope);
        runtimeContext.put(AgentTraceContextKeys.REPLY_TO_MESSAGE_ID, REQUEST_ID);

        ToolResultBlock result = tool.callAsync(paramWith(runtimeContext)).block(java.time.Duration.ofSeconds(10));

        assertThat(observed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(persisted.get())
                .as("跨租户 run 的 DB 键必须是 T2/B 自己的，绝不混入 T1/A")
                .isEqualTo("persisted:T-FAC67B:T-FAC67B:platform:T-FAC67B:910104")
                .doesNotContain("T-FAC67A");
        assertThat(originInBody.get()).as("退化 capture 形不授同步起源").isFalse();
        assertThat(egressOutcome.get()).as("事实不匹配 ⇒ 出站拒绝").startsWith("denied:");
        assertThat(textOf(result)).contains("未能写入");
    }
}
