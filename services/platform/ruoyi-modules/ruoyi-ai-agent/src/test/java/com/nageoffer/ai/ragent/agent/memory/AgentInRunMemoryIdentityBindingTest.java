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

import com.nageoffer.ai.ragent.agent.identity.AgentRunScope;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.message.Msg;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F-AC6-7/F-AC6-6 · in-run 内存链身份/租户显式化判据（轮首 load 站，红先行）。
 *
 * <p>真机形态（r17/p1win runs/AC6，19:19:14 等）：{@code onReasoning} 信号到达在
 * <b>非请求线程</b>上，{@code isSynchronousOrigin()} 恒 false——受理期挂进
 * RuntimeContext 的 {@link AgentRunScope} 才是本轮身份的唯一可信来源。修复前的
 * 实现在信号线程上自抓（拿到 null）→ 整轮退空快照（每轮「长期记忆读取失败」）。
 *
 * <p>门 fake 与既有 binding 测试同形：{@code require()} ＋「当前租户仅在 binder 内非空」，
 * 断言串 {@code persisted:<tenant>:<principalTenant>:<membershipId>}。
 * 本测试在修复前必须红（载体未被读）、修复后绿（载体重绑＋线程复原）。
 */
@Tag("dev")
class AgentInRunMemoryIdentityBindingTest {

    private final TenantGate gate = new TenantGate();

    private AgentMemoryRepository repository;
    private AgentUserMemoryMiddleware middleware;

    @BeforeEach
    void setUp() {
        repository = mock(AgentMemoryRepository.class);
        middleware = new AgentUserMemoryMiddleware(repository, providerOf(gate.binder()));
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
        UserContext.clear();
        gate.setCurrentTenant(null);
    }

    /**
     * 信号线程形态：线程上无同步起源（清空），身份只来自 RuntimeContext 里的受理期捕获。
     * 修复前：实现自抓 → null → 直跑 → 双门失败 ⇒ 红。
     * 修复后：载体重绑 → 读库见捕获身份/租户 ⇒ 绿；且 load 为纯读路径——调用线程
     * 不被授予同步起源（C2 fail-closed 语义保持）。
     */
    @Test
    void memoryLoadMustBindCapturedCarrierOnSignalThreadShape() throws Exception {
        CountDownLatch observed = new CountDownLatch(1);
        AtomicReference<String> persisted = new AtomicReference<>();
        when(repository.loadSnapshot(anyString())).thenAnswer(invocation -> {
            persisted.set(observeAtWritePoint());
            observed.countDown();
            return AgentMemorySnapshot.empty();
        });

        setPrincipal("T-FAC67A", "910101");
        com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder binder = gate.binder();
        AgentRunScope scope = AgentRunScope.capture(providerOf(binder));
        // 信号线程形态：清掉请求线程环境，身份只留载体
        PrincipalContext.clear();
        gate.setCurrentTenant(null);

        RuntimeContext runtimeContext = RuntimeContext.builder().userId("910101").sessionId("c-fac67").build();
        runtimeContext.put(AgentRunScope.RUNTIME_CONTEXT_KEY, scope);
        middleware.onReasoning(null, runtimeContext, emptyReasoningInput(), reasoningInput -> Flux.empty())
                .blockLast();

        assertThat(observed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(persisted.get())
                .as("load 必须使用受理期捕获载体重绑（双门），否则真机每轮退空快照")
                .isEqualTo("persisted:T-FAC67A:T-FAC67A:platform:T-FAC67A:910101");
        assertThat(PrincipalContext.isSynchronousOrigin())
                .as("load 是纯读路径：调用线程不得被授予同步起源（C2 fail-closed 语义保持）")
                .isFalse();
    }

    /**
     * 载体缺席（独立应用/直调）＋线程无同步起源：原形态直跑停在门内——
     * 修复不得凭空补身份（负例，守 fail-closed）。
     */
    @Test
    void memoryLoadWithoutCarrierStaysFailClosed() throws Exception {
        CountDownLatch observed = new CountDownLatch(1);
        AtomicReference<String> persisted = new AtomicReference<>();
        when(repository.loadSnapshot(anyString())).thenAnswer(invocation -> {
            persisted.set(observeAtWritePoint());
            observed.countDown();
            return AgentMemorySnapshot.empty();
        });

        PrincipalContext.clear();
        gate.setCurrentTenant(null);
        RuntimeContext runtimeContext = RuntimeContext.builder().userId("910101").sessionId("c-fac67").build();
        middleware.onReasoning(null, runtimeContext, emptyReasoningInput(), reasoningInput -> Flux.empty())
                .blockLast();

        assertThat(observed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(persisted.get())
                .as("载体缺席且无同步起源：直跑停门内，不新增默认身份")
                .startsWith("failed:");
    }

    /**
     * 池化残留拷贝（非配对 restore）不得成为本轮身份——载体缺席时即便线程上有
     * 身份 holder 也必须停门内（负例，守「宁可退化也不错绑」）。
     */
    @Test
    void memoryLoadResidueCopyMustNotBecomeIdentity() throws Exception {
        CountDownLatch observed = new CountDownLatch(1);
        AtomicReference<String> persisted = new AtomicReference<>();
        when(repository.loadSnapshot(anyString())).thenAnswer(invocation -> {
            persisted.set(observeAtWritePoint());
            observed.countDown();
            return AgentMemorySnapshot.empty();
        });

        PrincipalContext.clear();
        PrincipalContext.restore(principal("T-RESIDUE", "920001"));
        gate.setCurrentTenant(null);
        RuntimeContext runtimeContext = RuntimeContext.builder().userId("910101").sessionId("c-fac67").build();
        middleware.onReasoning(null, runtimeContext, emptyReasoningInput(), reasoningInput -> Flux.empty())
                .blockLast();

        PrincipalContext.clear();
        assertThat(observed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(persisted.get())
                .as("非同步起源的持有者是残留拷贝：直跑停门内，绝不绑成本轮身份")
                .startsWith("failed:");
    }

    // ==================== 夹具 ====================

    private ReasoningInput emptyReasoningInput() {
        return new ReasoningInput(List.of(), List.of(), null);
    }

    private String observeAtWritePoint() {
        try {
            ExecutionPrincipal principal = PrincipalContext.require();
            String tenant = gate.currentTenant();
            if (tenant == null || tenant.isBlank()) {
                throw new ClientException("tenant.context.missing");
            }
            return "persisted:" + tenant + ":" + principal.tenantId() + ":" + principal.membershipId();
        } catch (Exception e) {
            return "failed:" + e.getMessage();
        }
    }

    private static void setPrincipal(String tenantId, String userId) {
        PrincipalContext.set(principal(tenantId, userId));
    }

    private static ExecutionPrincipal principal(String tenantId, String userId) {
        return new ExecutionPrincipal(tenantId, userId, "platform:" + tenantId + ":" + userId,
                1, 1, Set.of(), "fac67-chain-test", "test", 1, Long.MAX_VALUE);
    }

    private static <T> ObjectProvider<T> providerOf(T value) {
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

    /**
     * 租户门 fake（TenantHelper.dynamic 的等价镜像），与既有 binding 测试同形。
     */
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

        private TenantScopeBinder binder() {
            return new TenantScopeBinder() {
                @Override
                public void runWithTenant(String tenantId, Runnable handle) {
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
            };
        }
    }
}
