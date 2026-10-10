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

package org.ruoyi.aiidentity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.core.domain.model.LoginUser;
import org.ruoyi.system.aiidentity.CurrentAiMembershipService;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F12-A1：/mcp 登录令牌 scope 解析的生产实现判据（fail-closed 口径）。
 *
 * <p>钉住与内嵌身份桥同源的既有口径：只取 {@code ai:*} 前缀权限、成员/租户/套餐
 * 不可用一律空集（"空授权为空"）、无身份空集。令牌 → LoginUser 的一段由
 * {@code LoginHelper.getLoginUser(token)}（仓库既有 API）承担，真实 HTTP 全链
 * 由 T0 环境相位复验。
 */
@Tag("dev")
class RuoYiMcpLoginTokenScopesTest {

    private static final String TENANT = "000000";

    private static LoginUser loginUser() {
        LoginUser user = new LoginUser();
        user.setTenantId(TENANT);
        user.setUserId(910101L);
        return user;
    }

    private static CurrentAiMembershipService.CurrentAiMembership facts(
            boolean tenantEnabled, boolean userEnabled, boolean packageEnabled, Set<String> menuPerms) {
        return new CurrentAiMembershipService.CurrentAiMembership(TENANT, 910101L, tenantEnabled, userEnabled,
                Set.of(810101L), menuPerms, packageEnabled, Set.of(7120L), 1);
    }

    @Test
    @DisplayName("只取 ai:* 前缀权限：普通菜单权限不得进入 MCP 授权面")
    void onlyAiPrefixedPermissionsBecomeScopes() {
        CurrentAiMembershipService membership = mock(CurrentAiMembershipService.class);
        when(membership.describe(TENANT, 910101L)).thenReturn(facts(true, true, true,
                Set.of("ai:agent:execute", "ai:tool:sandbox:write", "ai:flow:read", "system:user:list")));

        Set<String> scopes = new RuoYiMcpLoginTokenScopes(membership).scopesOf(loginUser());

        assertThat(scopes).containsExactlyInAnyOrder("ai:agent:execute", "ai:tool:sandbox:write", "ai:flow:read");
        verify(membership).describe(TENANT, 910101L);
    }

    @Test
    @DisplayName("空授权为空：无任何 ai:* 权限 ⇒ 空集（不是通配，不透传普通权限）")
    void noAiPermissionsIsEmpty() {
        CurrentAiMembershipService membership = mock(CurrentAiMembershipService.class);
        when(membership.describe(TENANT, 910101L)).thenReturn(facts(true, true, true, Set.of("system:user:list")));

        assertThat(new RuoYiMcpLoginTokenScopes(membership).scopesOf(loginUser())).isEmpty();
    }

    @Test
    @DisplayName("fail-closed：成员不存在 / 租户停用 / 用户停用 / 套餐停用 ⇒ 一律空集")
    void disabledOrUnknownIdentityIsEmpty() {
        CurrentAiMembershipService membership = mock(CurrentAiMembershipService.class);
        RuoYiMcpLoginTokenScopes scopes = new RuoYiMcpLoginTokenScopes(membership);
        Set<String> granted = Set.of("ai:agent:execute");

        when(membership.describe(TENANT, 910101L)).thenReturn(null);
        assertThat(scopes.scopesOf(loginUser())).isEmpty();

        when(membership.describe(TENANT, 910101L)).thenReturn(facts(false, true, true, granted));
        assertThat(scopes.scopesOf(loginUser())).isEmpty();

        when(membership.describe(TENANT, 910101L)).thenReturn(facts(true, false, true, granted));
        assertThat(scopes.scopesOf(loginUser())).isEmpty();

        when(membership.describe(TENANT, 910101L)).thenReturn(facts(true, true, false, granted));
        assertThat(scopes.scopesOf(loginUser())).isEmpty();
    }

    @Test
    @DisplayName("无身份：loginUser 缺位 / 缺 userId / 缺 tenantId ⇒ 空集且不查事实表")
    void missingIdentityIsEmptyWithoutLookup() {
        CurrentAiMembershipService membership = mock(CurrentAiMembershipService.class);
        RuoYiMcpLoginTokenScopes scopes = new RuoYiMcpLoginTokenScopes(membership);

        assertThat(scopes.scopesOf((LoginUser) null)).isEmpty();
        LoginUser noUser = new LoginUser();
        noUser.setTenantId(TENANT);
        assertThat(scopes.scopesOf(noUser)).isEmpty();
        LoginUser noTenant = new LoginUser();
        noTenant.setUserId(910101L);
        assertThat(scopes.scopesOf(noTenant)).isEmpty();
        assertThat(scopes.scopesOf("   ")).isEmpty();
        assertThat(scopes.scopesOf((String) null)).isEmpty();

        verifyNoInteractions(membership);
    }
}
