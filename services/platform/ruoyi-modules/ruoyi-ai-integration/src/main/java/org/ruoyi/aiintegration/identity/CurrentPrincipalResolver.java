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

package org.ruoyi.aiintegration.identity;

import java.util.Optional;

/**
 * 当前登录账号 → canonical 成员身份的解析 SPI（U06/P1.2c 引入）。
 *
 * <p>轻量接口留在 integration 模块，由 admin 的真实实现
 * （{@code org.ruoyi.aiidentity.RuoYiCurrentPrincipalResolver}）提供——
 * 它从真实 SaToken 登录会话与 platform 当前成员事实解析，不读 body/header、
 * 不读 LoginUser 权限快照、不接受 dynamic 伪造。integration 模块因此不需要
 * 反向依赖 system/admin。
 */
public interface CurrentPrincipalResolver {

    /**
     * @return 当前登录账号的 canonical 成员身份；未登录、无租户上下文或
     *         账号不属于该租户时返回 empty（由调用方决定 401/403），不抛异常。
     */
    Optional<CurrentMember> resolveCurrentMember();

    /**
     * 当前调用方是否为<b>平台管理身份</b>（维护者裁决 A2-ter）。
     *
     * <p>本方法只<b>声明问题</b>，不引入新的依赖：与 {@link #resolveCurrentMember()}
     * 一样，答案由平台侧实现给出（那里本来就读 SaToken 会话）。integration 模块
     * 依旧不读 {@code LoginUser} 权限快照、不反向依赖 system/admin。
     *
     * <p><b>默认 {@code false}</b>（fail-closed）：未覆写该方法的实现，会让
     * "平台管理身份专属动作"被<b>拒绝</b>而不是放行 —— 漏实现只会更严，不会更松。
     *
     * <p>判定口径由平台侧决定，且<b>不是</b>租户内角色、也<b>不是</b> scope：
     * 租户管理员与租户成员一律为 {@code false}。本仓库既有词汇为内置超级管理员
     * （{@code SystemConstants.SUPER_ADMIN_ID}）与角色标识
     * {@code TenantConstants.SUPER_ADMIN_ROLE_KEY="superadmin"}。
     *
     * @return true 仅当当前登录主体是平台管理身份
     */
    default boolean isPlatformAdmin() {
        return false;
    }

    /**
     * canonical 成员身份。
     *
     * @param tenantId      平台租户（1..64 字符，不含冒号）
     * @param userId        平台用户 ID 的十进制字符串（≤20 位）
     * @param membershipId  精确等于 {@code platform:<tenantId>:<userId>}
     */
    record CurrentMember(String tenantId, String userId, String membershipId) {
    }
}
