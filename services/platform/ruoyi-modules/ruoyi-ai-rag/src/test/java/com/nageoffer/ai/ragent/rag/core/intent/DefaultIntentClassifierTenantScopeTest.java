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

package com.nageoffer.ai.ragent.rag.core.intent;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.rag.config.OrchestrationProperties;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.dao.entity.IntentNodeDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
 * F09:3-D3 运行期缺陷（真机 2026-10-18，`app-6044-f09-3-d2.log`）：D2 修好技能注册表后，
 * `AgentSkillMaskingMiddleware.maskedTools` 的 O-2 收窄调用 `AgentToolCatalog.mcpToolIds()`
 * → `DefaultIntentClassifier.listMcpToolNodes()` → 冷缓存 `loadIntentTreeFromDB()` 查
 * `ai_intent_node`，仍在 agent 引擎线程上没有可信租户上下文，租户行拦截器在 SQL 下发前拒绝
 * （`tenant.context.missing`）。
 *
 * <p>判据（红先行）：
 * <ul>
 *   <li>mapper 的 {@code selectList} 只在 {@link TenantScopeBinder#runWithTenant} 作用域内才放行
 *       （模拟拦截器）；</li>
 *   <li>真实 Spring {@code AnnotationConfigApplicationContext} 以生产同形 5 参 {@code @Bean}
 *       创建分类器 + binder bean（复刻 embedded 字段注入），{@code listMcpToolNodes()} /
 *       {@code getNodeById} 返回节点、binder 恰 1 次、租户取自 {@code PrincipalContext}；</li>
 *   <li>缓存命中不进入租户作用域；查询失败向上抛（不吞）；</li>
 *   <li>binder 缺席（独立应用无拦截器 / 直接 5 参构造）⇒ 按原样直跑。</li>
 * </ul>
 */
@Tag("dev")
class DefaultIntentClassifierTenantScopeTest {

    private static final String TENANT = "T1";

    private final LLMService llmService = mock(LLMService.class);
    private final IntentNodeMapper intentNodeMapper = mock(IntentNodeMapper.class);
    private final PromptTemplateLoader promptTemplateLoader = mock(PromptTemplateLoader.class);
    private final IntentTreeCacheManager intentTreeCacheManager = mock(IntentTreeCacheManager.class);
    private final OrchestrationProperties orchestrationProperties = mock(OrchestrationProperties.class);

    /**
     * 模拟平台 MyBatis 租户行拦截器：没有可信租户作用域就在数据库操作之前拒绝。
     * 真机异常类型是 {@code org.ruoyi.common.tenant.exception.TenantException}，但它不在
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
        // 冷缓存 + 缓存回填 no-op（mock 默认）
        when(intentTreeCacheManager.getIntentTreeFromCache()).thenReturn(null);
        // 拦截器语义：只有处在租户作用域内才放行查询
        when(intentNodeMapper.selectList(any())).thenAnswer(invocation -> {
            if (!tenantScopeActive.get()) {
                throw new TenantContextMissingException();
            }
            return List.of(rootRow(), mcpRow());
        });
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    @DisplayName("listMcpToolNodes 冷缓存查库在租户作用域内：binder 恰一次、tenant 来自 PrincipalContext")
    void listMcpToolNodesLoadsInsideTenantScope() {
        try (AnnotationConfigApplicationContext context = context()) {
            List<IntentNode> nodes = context.getBean(DefaultIntentClassifier.class).listMcpToolNodes();

            assertThat(nodes).extracting(IntentNode::getId).containsExactly("mcp-a");
            assertThat(nodes.get(0).getMcpToolId()).isEqualTo("tool-a");
            assertThat(binderCalls.get())
                    .as("冷缓存查库必须且只能在一个租户作用域内执行")
                    .isEqualTo(1);
            assertThat(boundTenant.get())
                    .as("租户只认当前登录主体的权威事实")
                    .isEqualTo(TENANT);
            assertThat(tenantScopeActive.get())
                    .as("退出作用域必须清理线程绑定")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("getNodeById 冷缓存查库同样在租户作用域内")
    void getNodeByIdLoadsInsideTenantScope() {
        try (AnnotationConfigApplicationContext context = context()) {
            IntentNode node = context.getBean(DefaultIntentClassifier.class).getNodeById("mcp-a");

            assertThat(node).isNotNull();
            assertThat(node.getId()).isEqualTo("mcp-a");
            assertThat(binderCalls.get()).isEqualTo(1);
            assertThat(boundTenant.get()).isEqualTo(TENANT);
        }
    }

    @Test
    @DisplayName("缓存命中不查库、不进入租户作用域")
    void cacheHitDoesNotEnterTenantScope() {
        IntentNode cached = IntentNode.builder()
                .id("mcp-cached")
                .name("缓存节点")
                .kind(IntentKind.MCP)
                .mcpToolId("tool-c")
                .build();
        doReturn(List.of(cached)).when(intentTreeCacheManager).getIntentTreeFromCache();

        DefaultIntentClassifier classifier = directClassifier();

        assertThat(classifier.listMcpToolNodes()).extracting(IntentNode::getId).containsExactly("mcp-cached");
        assertThat(binderCalls.get()).isZero();
    }

    @Test
    @DisplayName("租户作用域内查询失败仍向上抛（服务内不吞异常）")
    void queryFailurePropagates() {
        doThrow(new TenantContextMissingException()).when(intentNodeMapper).selectList(any());

        try (AnnotationConfigApplicationContext context = context()) {
            DefaultIntentClassifier classifier = context.getBean(DefaultIntentClassifier.class);

            assertThatThrownBy(() -> classifier.listMcpToolNodes())
                    .isInstanceOf(TenantContextMissingException.class)
                    .hasMessage("tenant.context.missing");
            assertThat(binderCalls.get()).isEqualTo(1);
            assertThat(tenantScopeActive.get()).isFalse();
        }
    }

    @Test
    @DisplayName("binder 缺席（独立应用无拦截器 / 直接 5 参构造）：按原样直跑")
    void withoutBinderRunsDirectly() {
        doReturn(List.of(rootRow(), mcpRow())).when(intentNodeMapper).selectList(any());

        assertThat(directClassifier().listMcpToolNodes())
                .extracting(IntentNode::getId)
                .containsExactly("mcp-a");
    }

    // ------------------------------------------------------------------ 夹具

    /** 生产同形：5 参构造 + binder bean，字段注入由 Spring 在 @Bean 实例化后完成。 */
    private AnnotationConfigApplicationContext context() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(LLMService.class, () -> llmService);
        context.registerBean(IntentNodeMapper.class, () -> intentNodeMapper);
        context.registerBean(PromptTemplateLoader.class, () -> promptTemplateLoader);
        context.registerBean(IntentTreeCacheManager.class, () -> intentTreeCacheManager);
        context.registerBean(OrchestrationProperties.class, () -> orchestrationProperties);
        context.registerBean(TenantScopeBinder.class, () -> binder);
        context.register(ClassifierBeans.class);
        context.refresh();
        return context;
    }

    /** 直接 5 参构造（无 Spring ⇒ 无字段注入，等价端口缺席）。 */
    private DefaultIntentClassifier directClassifier() {
        return new DefaultIntentClassifier(llmService, intentNodeMapper, promptTemplateLoader,
                intentTreeCacheManager, orchestrationProperties);
    }

    @Configuration(proxyBeanMethods = false)
    static class ClassifierBeans {

        @Bean
        DefaultIntentClassifier defaultIntentClassifier(LLMService llmService,
                                                        IntentNodeMapper intentNodeMapper,
                                                        PromptTemplateLoader promptTemplateLoader,
                                                        IntentTreeCacheManager intentTreeCacheManager,
                                                        OrchestrationProperties orchestrationProperties) {
            return new DefaultIntentClassifier(llmService, intentNodeMapper, promptTemplateLoader,
                    intentTreeCacheManager, orchestrationProperties);
        }
    }

    private static IntentNodeDO rootRow() {
        return IntentNodeDO.builder()
                .id("id-group")
                .intentCode("group")
                .name("集团信息化")
                .level(0)
                .kind(0)
                .sortOrder(0)
                .enabled(1)
                .deleted(0)
                .build();
    }

    private static IntentNodeDO mcpRow() {
        return IntentNodeDO.builder()
                .id("id-mcp-a")
                .intentCode("mcp-a")
                .parentCode("group")
                .name("MCP 工具")
                .level(2)
                .kind(2)
                .mcpToolId("tool-a")
                .requireConfirm(0)
                .sortOrder(1)
                .enabled(1)
                .deleted(0)
                .build();
    }
}
