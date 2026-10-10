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

import com.nageoffer.ai.ragent.mcp.config.McpLoginTokenScopes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.domain.model.LoginUser;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.system.aiidentity.CurrentAiMembershipService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * {@link McpLoginTokenScopes} 的生产实现（F12-A1）：登录令牌 → 真实表身份事实 → {@code ai:*} scope。
 *
 * <p><b>为什么用令牌显式解析而不是当前登录上下文。</b>{@code /mcp} 是 servlet 映射、
 * 绕过 DispatcherServlet，Sa-Token 的线程上下文在该 servlet 链上不可用；而
 * {@link LoginHelper#getLoginUser(String)} 走 token session（仓库既有用法，
 * 见 {@code SysRoleServiceImpl}），与请求上下文无关。
 *
 * <p><b>事实口径与内嵌身份桥同源</b>（{@link LocalAiIdentityPort} /
 * {@link RuoYiPlatformIdentitySource}）：{@link CurrentAiMembershipService#describe}
 * 从真实表回答（sys_tenant/sys_user/sys_user_role/sys_role/sys_role_menu/sys_menu/
 * sys_tenant_package），只取 {@code ai:} 前缀的启用角色菜单 perms，<b>精确字符串、
 * 无 {@code *:*:*} 豁免</b>（与 {@code AiCanonicalAction} 口径一致）。
 *
 * <p><b>fail-closed。</b>用户名下无此令牌会话、成员不存在/停用/删除、租户或套餐不可用、
 * 无 {@code ai:*} 权限 —— 一律返回空集，由 {@code McpToolAuthzFilter} 按"空授权为空"
 * 拒绝。基础设施异常（如令牌存储不可用）不在此吞掉：向上抛出，由过滤器映射为
 * 503 {@code AUTHORIZATION_UNAVAILABLE}（与运行面既有语义一致），不静默降级。
 */
@Slf4j
@RequiredArgsConstructor
@Component
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class RuoYiMcpLoginTokenScopes implements McpLoginTokenScopes {

    /** AI 功能权限前缀：只有该前缀的菜单 perms 才进入身份 scope（与 RuoYiPlatformIdentitySource 同口径）。 */
    private static final String AI_PERMS_PREFIX = "ai:";

    private final CurrentAiMembershipService membershipService;

    @Override
    public Set<String> scopesOf(String token) {
        if (token == null || token.isBlank()) {
            return Set.of();
        }
        return scopesOf(LoginHelper.getLoginUser(token));
    }

    /**
     * 已解析登录用户的 scope 集合（token session 反序列化后的形态；单元判据直接覆盖本方法）。
     *
     * @return {@code ai:*} 权限集合；身份事实不可得或停用 → 空集
     */
    Set<String> scopesOf(LoginUser loginUser) {
        if (loginUser == null || loginUser.getUserId() == null
                || StringUtils.isBlank(loginUser.getTenantId())) {
            return Set.of();
        }
        CurrentAiMembershipService.CurrentAiMembership facts =
                membershipService.describe(loginUser.getTenantId(), loginUser.getUserId());
        if (facts == null || !facts.tenantEnabled() || !facts.userEnabled() || !facts.packageEnabled()) {
            // 成员不存在/停用、租户或套餐不可用：无授权（不回落默认租户、不放行空 scope）
            return Set.of();
        }
        Set<String> scopes = new LinkedHashSet<>();
        for (String perm : facts.menuPerms()) {
            if (perm != null && perm.startsWith(AI_PERMS_PREFIX)) {
                scopes.add(perm);
            }
        }
        return Set.copyOf(scopes);
    }
}
