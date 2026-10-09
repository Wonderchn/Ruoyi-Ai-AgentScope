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

package org.ruoyi.common.tenant.handle;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.common.tenant.exception.TenantException;
import org.ruoyi.common.tenant.helper.TenantHelper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R-2 验收（一）：{@link PlusTenantLineHandler} 的 fail-closed 收口。
 *
 * <p>四类情形各一条可红判据，外加两组"没有误伤"的正例：
 * <ol>
 *   <li><b>缺租户</b>：租户启用 + 无登录主体 + 无动态租户 ⇒ 租户业务表被拒绝；</li>
 *   <li><b>租户伪造</b>：已登录主体（租户 000000，非超管）被塞入别的租户号 ⇒ 拒绝；
 *       同一情形对平台超管放行（这是"明确、可审计的跨租户执行上下文"）；</li>
 *   <li><b>合法系统任务</b>：全局共享表 / 显式允许的系统表在<b>同样缺上下文</b>时照常可用
 *       （证明收口不是一刀切）；动态租户 = 自身租户时正常限域；</li>
 *   <li><b>租户功能未启用</b>：行为不变（不过滤），证明收口只在租户模式生效。</li>
 * </ol>
 *
 * <p>真实性说明：{@code LoginHelper} 用 Mockito 静态桩替换（本模块没有 Sa-Token web 上下文），
 * 被替换的只有"是否登录 / 登录租户 / 是否超管"三个答案，被验证的判定逻辑是生产代码本身。
 */
@Tag("dev")
class R2TenantContextFailClosedTest {

    private static final String OWN_TENANT = "000000";
    private static final String OTHER_TENANT = "154726";

    private PlusTenantLineHandler handler;

    @BeforeEach
    void setUp() {
        R2TenantTestContext.install(true);
        handler = R2TenantTestContext.handler();
        R2TenantTestContext.clearTenantContext();
    }

    @AfterEach
    void tearDown() {
        // isLogin 未被桩住时 clearDynamic 只清 ThreadLocal，不会碰 Redis/SaHolder
        R2TenantTestContext.clearTenantContext();
        R2TenantTestContext.setTenantEnabled(true);
    }

    // ------------------------------------------------------------------ 1) 缺租户

    @Test
    @DisplayName("缺租户：租户业务表在数据库操作前被拒绝，且不带任何默认租户兜底")
    void missingTenantIsRefusedForTenantBusinessTables() {
        // 无动态租户、无登录主体 -> TenantHelper.getTenantId() == null
        try (MockedStatic<LoginHelper> login = Mockito.mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::isLogin).thenReturn(false);
            login.when(LoginHelper::getTenantId).thenReturn(null);

            for (String businessTable : new String[]{"sys_user", "sys_role", "ai_agent_message",
                    "ai_rag_trace_run", "ai_biz_change_log", "ai_knowledge_document"}) {
                assertThatThrownBy(() -> handler.ignoreTable(businessTable))
                        .as("租户业务表 %s 缺上下文必须被拒绝", businessTable)
                        .isInstanceOf(TenantException.class)
                        .hasFieldOrPropertyWithValue("code", "tenant.context.missing");
            }
        }
    }

    @Test
    @DisplayName("缺租户：空白租户号与 null 同等对待（不得当成'无过滤'放行）")
    void blankTenantIsTreatedAsMissingNotAsUnfiltered() {
        for (String blank : new String[]{"", "   ", "\t"}) {
            TenantHelper.setDynamic(blank, false);
            assertThatThrownBy(() -> handler.ignoreTable("sys_user"))
                    .as("空白租户号 [%s] 必须按缺失拒绝", blank)
                    .isInstanceOf(TenantException.class)
                    .hasFieldOrPropertyWithValue("code", "tenant.context.missing");
        }
    }

    // ------------------------------------------------------------------ 2) 租户伪造

    @Test
    @DisplayName("租户伪造：非超管把上下文换成别的租户被拒绝")
    void forgedTenantIsRefusedForNonSuperAdmin() {
        try (MockedStatic<LoginHelper> login = Mockito.mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::isLogin).thenReturn(true);
            login.when(LoginHelper::getTenantId).thenReturn(OWN_TENANT);
            login.when(LoginHelper::isSuperAdmin).thenReturn(false);

            // 动态租户被设成"别人的租户"——这正是 setDynamic 能被滥用的形态
            TenantHelper.setDynamic(OTHER_TENANT, false);
            assertThat(TenantHelper.getTenantId()).isEqualTo(OTHER_TENANT);

            assertThatThrownBy(() -> handler.ignoreTable("sys_user"))
                    .isInstanceOf(TenantException.class)
                    .hasFieldOrPropertyWithValue("code", "tenant.context.forged");
        }
    }

    @Test
    @DisplayName("租户伪造的反面：动态租户 = 自身租户时正常限域（不误伤）")
    void sameAsOwnTenantIsFilteredNormally() {
        try (MockedStatic<LoginHelper> login = Mockito.mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::isLogin).thenReturn(true);
            login.when(LoginHelper::getTenantId).thenReturn(OWN_TENANT);
            login.when(LoginHelper::isSuperAdmin).thenReturn(false);

            TenantHelper.setDynamic(OWN_TENANT, false);
            assertThat(handler.ignoreTable("sys_user")).isFalse();
        }
    }

    @Test
    @DisplayName("F3：已登录但主体没有租户（缺可信 tenant）⇒ 拒绝，不得凭动态租户放行")
    void loggedInPrincipalWithoutTenantIsRefused() {
        try (MockedStatic<LoginHelper> login = Mockito.mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::isLogin).thenReturn(true);
            login.when(LoginHelper::getTenantId).thenReturn(null);
            login.when(LoginHelper::isSuperAdmin).thenReturn(false);

            // 复核者反例的精确形态：塞入任意动态租户后，此前会以该租户限域查询（放行）
            TenantHelper.setDynamic(OTHER_TENANT, false);
            assertThat(TenantHelper.getTenantId()).isEqualTo(OTHER_TENANT);

            assertThatThrownBy(() -> handler.ignoreTable("sys_user"))
                    .as("已登录却没有主体租户 == 缺可信 tenant，必须拒绝")
                    .isInstanceOf(TenantException.class)
                    .hasFieldOrPropertyWithValue("code", "tenant.context.missing");
            assertThatThrownBy(() -> handler.ignoreTable("ai_agent_message"))
                    .isInstanceOf(TenantException.class)
                    .hasFieldOrPropertyWithValue("code", "tenant.context.missing");
        }
    }

    @Test
    @DisplayName("F3：已登录主体租户为空白串时同样拒绝（不是只看 null）")
    void loggedInPrincipalWithBlankTenantIsRefused() {
        for (String blank : new String[]{"", "   "}) {
            try (MockedStatic<LoginHelper> login = Mockito.mockStatic(LoginHelper.class)) {
                login.when(LoginHelper::isLogin).thenReturn(true);
                login.when(LoginHelper::getTenantId).thenReturn(blank);
                login.when(LoginHelper::isSuperAdmin).thenReturn(true);

                TenantHelper.setDynamic(OTHER_TENANT, false);
                assertThatThrownBy(() -> handler.ignoreTable("sys_user"))
                        .as("主体租户为 [%s] 时即使是超管也必须拒绝", blank)
                        .isInstanceOf(TenantException.class)
                        .hasFieldOrPropertyWithValue("code", "tenant.context.missing");
            }
        }
    }

    // ------------------------------------------------------------------ F2：配置不得放宽跳过面

    @Test
    @DisplayName("F2：把租户业务表写进 tenant.excludes ⇒ 首次使用时响亮失败（不再静默跳过）")
    void widenedExcludesAreRefusedAtFirstUse() {
        for (String widened : new String[]{"sys_user", "ai_agent_message", "ai_rag_trace_run"}) {
            List<String> configured = new ArrayList<>(R2TenantTestContext.EXCLUDES);
            configured.add(widened);
            PlusTenantLineHandler widenedHandler =
                    new PlusTenantLineHandler(R2TenantTestContext.properties(configured));

            // 分类本身不再被配置左右：被放宽的表仍然是租户业务表
            assertThat(widenedHandler.classify(widened))
                    .as("被写进 excludes 的 %s 不得变成共享表", widened)
                    .isEqualTo(PlusTenantLineHandler.TableScope.TENANT_BUSINESS);

            // 且任何一次使用都响亮失败，而不是按配置跳过
            try (MockedStatic<LoginHelper> login = Mockito.mockStatic(LoginHelper.class)) {
                login.when(LoginHelper::isLogin).thenReturn(false);
                login.when(LoginHelper::getTenantId).thenReturn(null);
                assertThatThrownBy(() -> widenedHandler.ignoreTable(widened))
                        .as("被放宽的配置必须在首次使用时拒绝")
                        .isInstanceOf(TenantException.class)
                        .hasFieldOrPropertyWithValue("code", "tenant.context.excludes.widened");
                // 连共享表的查询也要失败：放宽是整体配置问题，不是单表问题
                assertThatThrownBy(() -> widenedHandler.ignoreTable("flow_spel"))
                        .isInstanceOf(TenantException.class)
                        .hasFieldOrPropertyWithValue("code", "tenant.context.excludes.widened");
            }
        }
    }

    @Test
    @DisplayName("F2：越界项大小写不敏感，且生产 excludes 本身合法（不误伤）")
    void widenedExcludesAreCaseInsensitiveAndShippedListIsAccepted() {
        List<String> configured = new ArrayList<>(R2TenantTestContext.EXCLUDES);
        configured.add("SYS_USER");
        PlusTenantLineHandler widenedHandler =
                new PlusTenantLineHandler(R2TenantTestContext.properties(configured));
        assertThatThrownBy(() -> widenedHandler.ignoreTable("sys_user"))
                .isInstanceOf(TenantException.class)
                .hasFieldOrPropertyWithValue("code", "tenant.context.excludes.widened");

        // 出厂清单必须被接受：任何表都不会因配置校验被拒
        assertThat(handler.ignoreTable("flow_spel")).isTrue();
        assertThat(handler.ignoreTable("sys_tenant")).isTrue();
    }

    @Test
    @DisplayName("F2：分类不读配置——收窄 excludes 也不会让共享表被过滤（防不存在的列谓词）")
    void narrowingExcludesDoesNotTurnSharedTablesIntoBusinessTables() {
        PlusTenantLineHandler narrowed =
                new PlusTenantLineHandler(R2TenantTestContext.properties(List.of()));
        assertThat(narrowed.classify("flow_spel"))
                .as("flow_spel 没有 tenant_id 列，收窄配置也不得把它变成租户业务表")
                .isEqualTo(PlusTenantLineHandler.TableScope.GLOBAL_SHARED);
        assertThat(narrowed.classify("sys_menu")).isEqualTo(PlusTenantLineHandler.TableScope.GLOBAL_SHARED);
        // 收窄不是放宽：仍然可用，不会被 F2 校验拒绝
        assertThat(narrowed.ignoreTable("flow_spel")).isTrue();
    }

    @Test
    @DisplayName("F2：即使绕过构造，分类也不会把配置里的业务表当成共享表")
    void classificationIgnoresConfiguredExcludesEvenIfValidationWereBypassed() {
        List<String> configured = new ArrayList<>(R2TenantTestContext.EXCLUDES);
        configured.add("sys_user");
        PlusTenantLineHandler widenedHandler =
                new PlusTenantLineHandler(R2TenantTestContext.properties(configured));
        assertThat(widenedHandler.classify("sys_user"))
                .isEqualTo(PlusTenantLineHandler.TableScope.TENANT_BUSINESS);
        assertThat(widenedHandler.classify("ai_agent_message"))
                .isEqualTo(PlusTenantLineHandler.TableScope.TENANT_BUSINESS);
    }

    @Test
    @DisplayName("跨租户管理任务的合法通道：平台超管的租户切换被放行且限域到目标租户")
    void superAdminTenantSwitchIsAllowedAndScoped() {
        try (MockedStatic<LoginHelper> login = Mockito.mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::isLogin).thenReturn(true);
            login.when(LoginHelper::getTenantId).thenReturn(OWN_TENANT);
            login.when(LoginHelper::isSuperAdmin).thenReturn(true);

            TenantHelper.setDynamic(OTHER_TENANT, false);
            assertThat(handler.ignoreTable("sys_user")).isFalse();
            assertThat(handler.getTenantId().toString()).contains(OTHER_TENANT);
        }
    }

    // ------------------------------------------------------------------ 3) 合法系统任务（没有误伤）

    @Test
    @DisplayName("合法系统任务：全局共享表在缺上下文时照常可用")
    void globalSharedTablesStayUsableWithoutTenantContext() {
        try (MockedStatic<LoginHelper> login = Mockito.mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::isLogin).thenReturn(false);
            login.when(LoginHelper::getTenantId).thenReturn(null);

            for (String shared : R2TenantTestContext.EXCLUDES) {
                assertThat(handler.ignoreTable(shared))
                        .as("共享表 %s 不得因缺上下文被拒绝", shared)
                        .isTrue();
            }
            for (String codeGen : new String[]{"gen_table", "gen_table_column"}) {
                assertThat(handler.ignoreTable(codeGen)).isTrue();
            }
        }
    }

    @Test
    @DisplayName("合法系统任务：显式允许的系统表在缺上下文与有上下文两种情形下都不过滤")
    void platformLevelTablesStayUnfilteredInBothContexts() {
        try (MockedStatic<LoginHelper> login = Mockito.mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::isLogin).thenReturn(false);
            login.when(LoginHelper::getTenantId).thenReturn(null);
            for (String table : new String[]{"sys_tenant", "sys_oss_config",
                    "ai_flow_trace_run", "ai_flow_trace_node"}) {
                assertThat(handler.ignoreTable(table))
                        .as("显式允许的系统表 %s 在缺上下文时不得被拒绝", table)
                        .isTrue();
            }

            TenantHelper.setDynamic(OWN_TENANT, false);
            for (String table : new String[]{"sys_tenant", "sys_oss_config",
                    "ai_flow_trace_run", "ai_flow_trace_node"}) {
                assertThat(handler.ignoreTable(table))
                        .as("显式允许的系统表 %s 有上下文时也保持不过滤", table)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("合法系统任务：有租户上下文的租户业务表正常加谓词")
    void tenantBusinessTablesAreFilteredWhenContextPresent() {
        TenantHelper.setDynamic(OWN_TENANT, false);
        assertThat(handler.ignoreTable("sys_user")).isFalse();
        assertThat(handler.ignoreTable("ai_agent_message")).isFalse();
        assertThat(handler.getTenantId().toString()).contains(OWN_TENANT);
    }

    // ------------------------------------------------------------------ 4) 未启用时不改变行为

    @Test
    @DisplayName("租户功能未启用：不做任何过滤，也不抛异常（收口只在租户模式生效）")
    void tenantDisabledKeepsLegacyBehaviour() {
        R2TenantTestContext.setTenantEnabled(false);
        try (MockedStatic<LoginHelper> login = Mockito.mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::isLogin).thenReturn(false);
            login.when(LoginHelper::getTenantId).thenReturn(null);
            assertThat(handler.ignoreTable("sys_user")).isTrue();
            assertThat(handler.ignoreTable("ai_agent_message")).isTrue();
        }
    }
}
