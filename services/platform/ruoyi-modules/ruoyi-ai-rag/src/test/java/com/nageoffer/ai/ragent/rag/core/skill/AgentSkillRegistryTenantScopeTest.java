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

package com.nageoffer.ai.ragent.rag.core.skill;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentSkillDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentSkillMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

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
 * F09:3-D2 运行期缺陷（真机 2026-10-18，新栈 `app-6044-f09-3-d1.log`）：D1 把绑定读取包进
 * 租户作用域后，冷缓存下的 {@code AgentSkillRegistry.listEnabled()} 仍直接在 agent 引擎线程
 * 查 {@code ai_agent_skill}——该线程没有 Sa-Token 登录主体/动态租户，租户行拦截器在 SQL 下发前
 * 拒绝（{@code tenant.context.missing}），遮蔽中间件兜底集为 0，fail-closed 但不生效。
 *
 * <p>判据（红先行）：
 * <ul>
 *   <li>mapper 的 {@code selectList} 只在 {@link TenantScopeBinder#runWithTenant} 作用域内才放行
 *       （模拟拦截器）；</li>
 *   <li>冷缓存 {@code listEnabled()} 返回技能、binder 恰调用 1 次、租户取自 {@code PrincipalContext}；</li>
 *   <li>缓存命中不进入租户作用域；查询失败向上抛（不吞）；</li>
 *   <li>binder 缺席（独立应用无拦截器）⇒ 按原样直跑（3 参兼容构造）。</li>
 * </ul>
 */
@Tag("dev")
class AgentSkillRegistryTenantScopeTest {

    private static final String TENANT = "T1";

    private final AgentSkillMapper agentSkillMapper = mock(AgentSkillMapper.class);
    private final AgentSkillCacheManager cacheManager = mock(AgentSkillCacheManager.class);

    /**
     * 模拟平台 MyBatis 租户行拦截器：没有可信租户作用域就在任何数据库操作之前拒绝。
     *
     * <p>真机异常类型是 {@code org.ruoyi.common.tenant.exception.TenantException}，但它不在
     * ruoyi-ai-rag 的依赖闭包内（仅 ruoyi-system/ruoyi-workflow 依赖 ruoyi-common-tenant），
     * 本卡不允许改 POM ⇒ 用同消息的本地异常模拟同一判定点。
     */
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
        when(cacheManager.getFromCache()).thenReturn(null);
        // 拦截器语义：只有处在租户作用域内才放行查询
        when(agentSkillMapper.selectList(any())).thenAnswer(invocation -> {
            if (!tenantScopeActive.get()) {
                throw new TenantContextMissingException();
            }
            return List.of(skillRow("skill-a", 0), skillRow("skill-b", 1));
        });
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    @DisplayName("listEnabled 冷缓存 DB 查询在租户作用域内：binder 恰一次、tenant 来自 PrincipalContext")
    void listEnabledRunsInsideTenantScope() {
        List<AgentSkill> skills = registry(binder).listEnabled();

        assertThat(skills).extracting(AgentSkill::skillCode).containsExactly("skill-a", "skill-b");
        assertThat(binderCalls.get())
                .as("冷缓存查询必须且只能在一个租户作用域内执行")
                .isEqualTo(1);
        assertThat(boundTenant.get())
                .as("租户只认当前登录主体的权威事实")
                .isEqualTo(TENANT);
        assertThat(tenantScopeActive.get())
                .as("退出作用域必须清理线程绑定")
                .isFalse();
    }

    @Test
    @DisplayName("缓存命中不查库、不进入租户作用域")
    void cacheHitDoesNotEnterTenantScope() {
        AgentSkill cached = new AgentSkill("skill-c", "缓存技能", "说明", "正文", List.of());
        doReturn(List.of(cached)).when(cacheManager).getFromCache();

        assertThat(registry(binder).listEnabled()).containsExactly(cached);
        assertThat(binderCalls.get()).isZero();
    }

    @Test
    @DisplayName("租户作用域内查询失败仍向上抛（服务内不吞异常）")
    void queryFailurePropagates() {
        doThrow(new TenantContextMissingException()).when(agentSkillMapper).selectList(any());

        assertThatThrownBy(() -> registry(binder).listEnabled())
                .isInstanceOf(TenantContextMissingException.class)
                .hasMessage("tenant.context.missing");
        assertThat(binderCalls.get()).isEqualTo(1);
        assertThat(tenantScopeActive.get()).isFalse();
    }

    @Test
    @DisplayName("binder 缺席（独立应用无租户拦截器 / 3 参装配）：按原样直跑")
    void withoutBinderRunsDirectly() {
        doReturn(List.of(skillRow("skill-a", 0))).when(agentSkillMapper).selectList(any());

        AgentSkillRegistry registry = new AgentSkillRegistry(agentSkillMapper, cacheManager, null);

        assertThat(registry.listEnabled()).extracting(AgentSkill::skillCode).containsExactly("skill-a");
    }

    /**
     * embedded 生产装配（{@code AiEmbeddedAgentEngineClosureConfiguration#agentSkillRegistry}）用
     * 3 参构造创建本对象——本用例复刻同一形态：@Bean 返回的实例由 Spring 在实例化后完成
     * {@code @Autowired} 字段注入，端口因此必须可用（否则真机上仍会 tenant.context.missing）。
     */
    @Test
    @DisplayName("3 参 @Bean 形态：Spring 字段注入回填端口，冷缓存查询同样在租户作用域内")
    void beanCreatedRegistryReceivesTenantScopeBinderByFieldInjection() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AgentSkillMapper.class, () -> agentSkillMapper);
            context.registerBean(AgentSkillCacheManager.class, () -> cacheManager);
            context.registerBean(TenantScopeBinder.class, () -> binder);
            context.registerBean("agentSkillRegistry", AgentSkillRegistry.class,
                    () -> new AgentSkillRegistry(agentSkillMapper, cacheManager, null));
            context.refresh();

            List<AgentSkill> skills = context.getBean(AgentSkillRegistry.class).listEnabled();

            assertThat(skills).extracting(AgentSkill::skillCode).containsExactly("skill-a", "skill-b");
            assertThat(binderCalls.get()).isEqualTo(1);
            assertThat(boundTenant.get()).isEqualTo(TENANT);
        }
    }

    // ------------------------------------------------------------------ 夹具

    private AgentSkillRegistry registry(TenantScopeBinder tenantScopeBinder) {
        return new AgentSkillRegistry(agentSkillMapper, cacheManager, null, tenantProvider(tenantScopeBinder));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<TenantScopeBinder> tenantProvider(TenantScopeBinder tenantScopeBinder) {
        ObjectProvider<TenantScopeBinder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(tenantScopeBinder);
        return provider;
    }

    private static AgentSkillDO skillRow(String skillCode, int sortOrder) {
        return AgentSkillDO.builder()
                .id("id-" + skillCode)
                .skillCode(skillCode)
                .name("技能 " + skillCode)
                .description("说明")
                .content("正文")
                .toolIds(List.of())
                .sortOrder(sortOrder)
                .enabled(1)
                .deleted(0)
                .build();
    }
}
