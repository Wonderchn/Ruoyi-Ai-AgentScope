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
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F16 op1 管线修复 · 任务边界纪律（P3 §3/§5）：共享池任务的身份/租户重绑形状。
 *
 * <p>双门 fake 与真链同形：落库点先吃 {@code PrincipalContext.require()}（第一道门），
 * 再吃"当前租户仅在 binder 内非空"的租户门（第二道门，MyBatis 行拦截器语义），
 * 断言串 {@code persisted:<tenant>:<principalTenant>:<membershipId>}——
 * 只绑身份／只绑租户都红，双门才绿。
 *
 * <p>执行线程一律预置"别人的主体"（池化残留的等价形态）：正确实现必须用受理期
 * 捕获的引用覆盖它、用捕获的租户覆盖环境租户，并在退出时把线程原主体
 * <b>恢复</b>（不是 clear、不是把捕获身份留给下一个任务）。
 */
@Tag("dev")
class AsyncMemoryTaskScopeTest {

    private final TenantGate gate = new TenantGate();

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    void capturedIdentityAndTenantOverrideWorkerResidueAndRestoreItOnExit() {
        // 执行线程预置"别的用户"的残留主体（真机形态：boundedElastic 池线程带创建期继承拷贝）
        ExecutionPrincipal residue = principal("T-RESIDUE", "920001");
        PrincipalContext.set(residue);

        AtomicReference<String> observed = new AtomicReference<>();
        String returned = AsyncMemoryTaskScope.callWithCapture(
                ref("T-F10P3A", "910101"), "T-F10P3A", providerOf(gate.binder()),
                () -> {
                    observed.set(observeWritePoint());
                    return "done";
                });

        assertThat(returned).isEqualTo("done");
        assertThat(observed.get())
                .as("任务体必须看到捕获的身份与租户（双门），不是线程环境/残留")
                .isEqualTo("persisted:T-F10P3A:T-F10P3A:platform:T-F10P3A:910101");
        assertThat(PrincipalContext.get())
                .as("退出时线程原主体必须原样恢复（不是清空、也不是留下捕获身份）")
                .isSameAs(residue);
        assertThat(gate.boundTenants()).containsExactly("T-F10P3A");
    }

    @Test
    void capturedIdentityMustOverrideWorkerResidueEvenWhenBodyThrows() {
        ExecutionPrincipal residue = principal("T-RESIDUE", "920001");
        PrincipalContext.set(residue);

        assertThatThrownBy(() -> AsyncMemoryTaskScope.callWithCapture(
                ref("T-F10P3A", "910101"), "T-F10P3A", providerOf(gate.binder()),
                () -> {
                    throw new IllegalStateException("落库失败");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(PrincipalContext.get())
                .as("抛错路径也必须恢复线程原主体（共享池线程，泄漏＝跨用户串身份）")
                .isSameAs(residue);
    }

    @Test
    void tenantIsBoundInsideIdentityScopeFromTheSameCapture() {
        // 双门合测：租户从同一捕获派生；若实现改去独立二次读环境租户（读环境身份/租户），
        // 下面这个"环境预置他租户"的场景就会把它顶出来
        PrincipalContext.set(principal("T-RESIDUE", "920001"));
        gate.setCurrentTenant("T-STALE");
        AtomicReference<String> observed = new AtomicReference<>();

        AsyncMemoryTaskScope.callWithCapture(ref("T-F10P3A", "910101"), "T-F10P3A", providerOf(gate.binder()),
                () -> {
                    observed.set(observeWritePoint());
                    return null;
                });

        assertThat(observed.get())
                .as("环境租户不得顶掉捕获租户")
                .isEqualTo("persisted:T-F10P3A:T-F10P3A:platform:T-F10P3A:910101");
    }

    @Test
    void identityOnlyCaptureStillFailsTheTenantGate() {
        // “只绑身份”红：端口缺席时身份恢复成功、租户门仍 fail-closed（缺一不可）
        PrincipalContext.clear();
        AtomicReference<String> observed = new AtomicReference<>();

        AsyncMemoryTaskScope.callWithCapture(ref("T-F10P3A", "910101"), "T-F10P3A", null,
                () -> {
                    observed.set(observeWritePoint());
                    return null;
                });

        assertThat(observed.get())
                .as("没有租户绑定端口时，租户门必须仍然拒绝")
                .isEqualTo("failed:tenant.context.missing");
    }

    @Test
    void tenantOnlyBindingStillFailsThePrincipalGate() {
        // “只绑租户”红：身份门先拒（无主体形态不允许用环境身份补位）
        PrincipalContext.clear();
        AtomicReference<String> observed = new AtomicReference<>();

        AsyncMemoryTaskScope.callWithCapture(null, "T-F10P3A", providerOf(gate.binder()),
                () -> {
                    observed.set(observeWritePoint());
                    return null;
                });

        assertThat(observed.get())
                .as("无捕获主体就必须停在身份门，不得用环境身份直跑")
                .isEqualTo("failed:no execution principal in current context");
        assertThat(PrincipalContext.hasPrincipal())
                .as("无主体形态直跑不得新增默认身份")
                .isFalse();
    }

    @Test
    void interleavedTasksKeepEachCapturedIdentity() {
        // 两个任务、两个主体在同一执行线程上交错：捕获跟任务走，不许静态共享（后者胜）
        PrincipalContext.set(principal("T-RESIDUE", "920001"));
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();

        AsyncMemoryTaskScope.callWithCapture(ref("T-F10P3A", "910101"), "T-F10P3A", providerOf(gate.binder()),
                () -> {
                    first.set(observeWritePoint());
                    return null;
                });
        AsyncMemoryTaskScope.callWithCapture(ref("T-F10P3B", "910102"), "T-F10P3B", providerOf(gate.binder()),
                () -> {
                    second.set(observeWritePoint());
                    return null;
                });

        assertThat(first.get()).isEqualTo("persisted:T-F10P3A:T-F10P3A:platform:T-F10P3A:910101");
        assertThat(second.get()).isEqualTo("persisted:T-F10P3B:T-F10P3B:platform:T-F10P3B:910102");
    }

    /**
     * 落库点双门（与真链同形）：第一道 {@code require()}，第二道"当前租户仅在 binder 内非空"。
     */
    private String observeWritePoint() {
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

    private static ExecutionPrincipal principal(String tenantId, String userId) {
        return new ExecutionPrincipal(tenantId, userId, "platform:" + tenantId + ":" + userId,
                1, 1, Set.of(), "f10p3-scope-test", "test", 1, Long.MAX_VALUE);
    }

    private static AsyncPrincipalReference ref(String tenantId, String userId) {
        return AsyncPrincipalReference.of(principal(tenantId, userId), 1_700_000_000_000L);
    }

    /**
     * 租户门 fake（TenantHelper.dynamic 的等价镜像）：动态租户按线程保存，仅 binder 内非空。
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

    private static ObjectProvider<TenantScopeBinder> providerOf(TenantScopeBinder binder) {
        return new ObjectProvider<>() {
            @Override
            public TenantScopeBinder getObject() {
                if (binder == null) {
                    throw new IllegalStateException("no binder");
                }
                return binder;
            }

            @Override
            public TenantScopeBinder getObject(Object... args) {
                return getObject();
            }

            @Override
            public TenantScopeBinder getIfAvailable() {
                return binder;
            }

            @Override
            public TenantScopeBinder getIfUnique() {
                return binder;
            }
        };
    }
}
