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

package com.nageoffer.ai.ragent.rag.binding;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import com.nageoffer.ai.ragent.rag.binding.AgentProfileBindingService.BindingReference;
import com.nageoffer.ai.ragent.rag.binding.AgentProfileBindingService.RefLookup;
import com.nageoffer.ai.ragent.rag.binding.AgentProfileBindingService.RefType;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentProfileBindingDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentProfileBindingMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F09:3/F11:3 运行期缺陷（真机 2026-10-18）：agent 引擎线程没有 Sa-Token 登录主体/动态租户，
 * 平台侧 MyBatis 租户行拦截器（{@code tenant.enable=true}）在 SQL 下发前拒绝
 * （{@code tenant.context.missing}），{@code bindingsOf} 因此抛错并被上层 fail-open。
 *
 * <p>判据（红先行）：
 * <ul>
 *   <li>mapper 的 {@code selectList} 只在 {@link TenantScopeBinder#runWithTenant} 作用域内才放行
 *       （模拟拦截器）；</li>
 *   <li>{@code bindingsOf} 必须返回绑定、binder 恰调用 1 次、租户取自 {@code PrincipalContext}；</li>
 *   <li>查询失败向上抛（服务内不吞异常，由调用方决定 fail-closed）；
 *       binder 退出时清理线程作用域；</li>
 *   <li>binder 缺席（独立应用无拦截器）⇒ 按原样直跑（兼容构造）。</li>
 * </ul>
 */
@Tag("dev")
class AgentProfileBindingServiceTenantScopeTest {

    private static final String TENANT = "T1";
    private static final String AGENT = "agent-1";

    private final AgentProfileBindingMapper bindingMapper = mock(AgentProfileBindingMapper.class);
    private final RefLookup refLookup = mock(RefLookup.class);

    /** 模拟平台 MyBatis 租户行拦截器：没有可信租户作用域就在任何数据库操作之前拒绝。 */
    static final class TenantContextMissingException extends RuntimeException {
        TenantContextMissingException() {
            super("tenant.context.missing");
        }
    }

    private final AtomicBoolean tenantScopeActive = new AtomicBoolean();
    private final AtomicInteger binderCalls = new AtomicInteger();
    private final AtomicReference<String> boundTenant = new AtomicReference<>();

    private final TenantScopeBinder binder = (tenantId, handle) -> {
        binderCalls.incrementAndGet();
        boundTenant.set(tenantId);
        tenantScopeActive.set(true);
        try {
            handle.run();
        } finally {
            tenantScopeActive.set(false);
        }
    };

    @BeforeEach
    void setUp() {
        PrincipalContext.set(new ExecutionPrincipal(TENANT, "2101", "platform:" + TENANT + ":2101",
                1, 1, Set.of("agent.read"), "jti", "platform", 0, Long.MAX_VALUE));
        // 拦截器语义：只有处在租户作用域内才放行查询
        when(bindingMapper.selectList(any())).thenAnswer(invocation -> {
            if (!tenantScopeActive.get()) {
                throw new TenantContextMissingException();
            }
            return List.of(row(RefType.SKILL, "skill-a", 0), row(RefType.MCP, "mcp-x", 1));
        });
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    @DisplayName("bindingsOf 在租户作用域内查询：binder 恰一次、租户来自 PrincipalContext")
    void bindingsOfRunsInsideTenantScope() {
        List<BindingReference> bindings = service(binder).bindingsOf(AGENT);

        assertThat(bindings).extracting(ref -> ref.type() + ":" + ref.refId())
                .containsExactly("SKILL:skill-a", "MCP:mcp-x");
        assertThat(binderCalls.get())
                .as("绑定查询必须且只能在一个租户作用域内执行")
                .isEqualTo(1);
        assertThat(boundTenant.get())
                .as("租户只认当前登录主体的权威事实")
                .isEqualTo(TENANT);
        assertThat(tenantScopeActive.get())
                .as("退出作用域必须清理线程绑定")
                .isFalse();
    }

    @Test
    @DisplayName("租户作用域内查询失败仍向上抛（服务内不吞异常）")
    void bindingReadFailurePropagates() {
        // doThrow 覆写既有 stub：when(...) 会先执行上一版 thenAnswer（那里正抛拦截器异常）
        doThrow(new TenantContextMissingException()).when(bindingMapper).selectList(any());

        assertThatThrownBy(() -> service(binder).bindingsOf(AGENT))
                .isInstanceOf(TenantContextMissingException.class)
                .hasMessage("tenant.context.missing");
        assertThat(binderCalls.get()).isEqualTo(1);
        assertThat(tenantScopeActive.get()).isFalse();
    }

    @Test
    @DisplayName("binder 缺席（独立应用无租户拦截器）：按原样直跑")
    void withoutBinderRunsDirectly() {
        doReturn(List.of(row(RefType.SKILL, "skill-a", 0))).when(bindingMapper).selectList(any());

        AgentProfileBindingService service = new AgentProfileBindingService(bindingMapper, provider(refLookup));

        assertThat(service.boundSkillCodes(AGENT)).containsExactly("skill-a");
    }

    // ------------------------------------------------------------------ 夹具

    private AgentProfileBindingService service(TenantScopeBinder tenantScopeBinder) {
        return new AgentProfileBindingService(bindingMapper, provider(refLookup), tenantProvider(tenantScopeBinder));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RefLookup> provider(RefLookup lookup) {
        ObjectProvider<RefLookup> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(lookup);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<TenantScopeBinder> tenantProvider(TenantScopeBinder tenantScopeBinder) {
        ObjectProvider<TenantScopeBinder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(tenantScopeBinder);
        return provider;
    }

    private static AgentProfileBindingDO row(RefType type, String refId, int sortOrder) {
        return AgentProfileBindingDO.builder()
                .id(type.name() + ":" + refId)
                .tenantId(TENANT)
                .agentProfileId(AGENT)
                .refType(type.name())
                .refId(refId)
                .enabled(1)
                .sortOrder(sortOrder)
                .deleted(0)
                .build();
    }
}
