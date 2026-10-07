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

import lombok.RequiredArgsConstructor;
import org.ruoyi.aiintegration.identity.CurrentPrincipalResolver;
import org.ruoyi.common.core.constant.TenantConstants;
import org.ruoyi.common.core.domain.model.LoginUser;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.system.aiidentity.CurrentAiMembershipService;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * 当前登录账号 → canonical 成员身份的生产实现（U06/P1.2c SPI 的 U04/P1.2b 接线）。
 *
 * <p>从 SaToken 当前登录会话取 userId/tenantId（仓库既有 {@link LoginHelper} 用法，
 * 读 token 扩展字段而非 LoginUser 权限快照），并用 {@link CurrentAiMembershipService}
 * 按真实表校验该账号确实归属该租户——不属于（或账号不存在/已删除）→ empty。
 * membershipId 精确构造为 {@code platform:<tenantId>:<userId>}。
 *
 * <p>不读 body/header、不接受 dynamic 伪造；未登录/无租户上下文返回 empty，
 * 由调用方决定 401/403。
 *
 * @author AI-Integration
 */
@RequiredArgsConstructor
@Component
public class RuoYiCurrentPrincipalResolver implements CurrentPrincipalResolver {

    private final CurrentAiMembershipService membershipService;

    @Override
    public Optional<CurrentMember> resolveCurrentMember() {
        Long userId = LoginHelper.getUserId();
        String tenantId = LoginHelper.getTenantId();
        if (userId == null || StringUtils.isBlank(tenantId)) {
            // 未登录或无租户上下文：交由调用方决定 401/403
            return Optional.empty();
        }
        CurrentAiMembershipService.CurrentAiMembership facts = membershipService.describe(tenantId, userId);
        if (facts == null) {
            // 用户不存在、已删除或不归属当前会话租户 → 拒绝构造成员身份
            return Optional.empty();
        }
        String userIdStr = String.valueOf(userId);
        String membershipId = "platform:" + tenantId + ":" + userIdStr;
        return Optional.of(new CurrentMember(tenantId, userIdStr, membershipId));
    }

    /**
     * 平台管理身份判定（维护者裁决 A2-ter）。
     *
     * <p>口径取平台既有词汇，三者之一成立即算平台管理身份：
     * <ul>
     *   <li>内置超级管理员：{@code LoginHelper.isSuperAdmin()}（{@code userId == 1}）；</li>
     *   <li>持有超级管理员角色标识：登录会话的 {@code rolePermission} 含
     *       {@code TenantConstants.SUPER_ADMIN_ROLE_KEY}（{@code "superadmin"}）——
     *       与仓库既有 {@code @SaCheckRole(TenantConstants.SUPER_ADMIN_ROLE_KEY)}
     *       同源；</li>
     *   <li><b>专用平台目录管理角色</b> {@link #PLATFORM_CATALOG_ADMIN_ROLE_KEY}
     *       —— 维护者裁决要求的"仅授予专用平台目录管理角色"：只发这一个角色、
     *       不给整套超管权限，即可管理平台级 Agent 目录。</li>
     * </ul>
     *
     * <p><b>租户管理员（{@code "admin"}）与普通租户成员一律为 false</b>：它们拿到
     * 再多 scope 也不能通过"平台管理身份专属动作"的第二道门。会话不存在或读不到
     * 角色时返回 false（fail-closed）。
     */
    @Override
    public boolean isPlatformAdmin() {
        if (LoginHelper.isSuperAdmin()) {
            return true;
        }
        LoginUser loginUser = LoginHelper.getLoginUser();
        if (loginUser == null) {
            return false;
        }
        Set<String> rolePermission = loginUser.getRolePermission();
        if (rolePermission == null) {
            return false;
        }
        return rolePermission.contains(TenantConstants.SUPER_ADMIN_ROLE_KEY)
                || rolePermission.contains(PLATFORM_CATALOG_ADMIN_ROLE_KEY);
    }

    /**
     * 专用平台目录管理角色标识（A2-ter）。
     *
     * <p>刻意<b>不</b>复用 {@code superadmin}：按维护者裁决，平台级 Agent 目录只应
     * 授予这一个<b>专用</b>角色，而不是把整套超管权限发出去。部署方在
     * {@code sys_role} 建同名 {@code role_key} 并绑定到平台管理账号即可。
     */
    public static final String PLATFORM_CATALOG_ADMIN_ROLE_KEY = "ai_catalog_admin";

}
