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

package org.ruoyi.system.aiidentity;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.constant.SystemConstants;
import org.ruoyi.common.core.utils.StreamUtils;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.tenant.helper.TenantHelper;
import org.ruoyi.system.domain.SysDept;
import org.ruoyi.system.domain.SysMenu;
import org.ruoyi.system.domain.SysRole;
import org.ruoyi.system.domain.SysRoleMenu;
import org.ruoyi.system.domain.SysTenant;
import org.ruoyi.system.domain.SysTenantPackage;
import org.ruoyi.system.domain.SysUser;
import org.ruoyi.system.domain.SysUserRole;
import org.ruoyi.system.mapper.SysDeptMapper;
import org.ruoyi.system.mapper.SysMenuMapper;
import org.ruoyi.system.mapper.SysRoleMapper;
import org.ruoyi.system.mapper.SysRoleMenuMapper;
import org.ruoyi.system.mapper.SysTenantMapper;
import org.ruoyi.system.mapper.SysTenantPackageMapper;
import org.ruoyi.system.mapper.SysUserMapper;
import org.ruoyi.system.mapper.SysUserRoleMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 当前成员事实服务：按 (tenantId, userId) 从<b>真实表</b>回答成员身份事实。
 *
 * <p>口径（U04/P1.2b）：全部事实即时查库——租户启用与期限（sys_tenant）、
 * 用户启用/删除/归属租户（sys_user 的 status/del_flag/tenant_id）、当前启用角色
 * （sys_user_role → sys_role，status='0'；sys_role 在本库无过期列，期限由
 * sys_tenant.expire_time 承担）、菜单功能权限（启用角色 → sys_role_menu →
 * sys_menu(status='0') 的 perms 集合）、套餐启用与菜单集合（sys_tenant_package）、
 * 以及经 {@link AiPolicyRevisionService} 读取的 policyVersion。
 *
 * <p>刻意<b>不读 LoginUser 缓存/权限快照</b>：本服务是 AI 授权事实的权威出处，
 * 登录态快照滞后于身份写操作。查询统一在 {@code TenantHelper.ignore} 中执行
 * （目标租户以参数显式给定，可能与当前登录租户不同）。
 *
 * @author AI-Integration
 */
@RequiredArgsConstructor
@Service
public class CurrentAiMembershipService {

    private final SysTenantMapper tenantMapper;
    private final SysTenantPackageMapper tenantPackageMapper;
    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysMenuMapper menuMapper;
    private final AiPolicyRevisionService policyRevisionService;
    private final SysDeptMapper deptMapper;

    /**
     * 成员事实。
     *
     * @param tenantId       租户编号
     * @param userId         用户 ID
     * @param tenantEnabled  租户存在、启用（status='0'）且未过期限
     * @param userEnabled    用户存在、归属该租户、未删除且启用（status='0'）
     * @param enabledRoleIds 当前启用角色 ID（status='0' 且归属该租户）
     * @param menuPerms      启用角色经角色-菜单关联到 sys_menu(status='0') 的 perms 集合
     * @param packageEnabled 租户套餐存在且启用
     * @param packageMenuIds 套餐声明的菜单 ID 集合（逗号串解析）
     * @param policyVersion  当前策略版本；{@code null} 表示租户无版本行（绝不默认为 1）
     */
    public record CurrentAiMembership(String tenantId, Long userId, boolean tenantEnabled, boolean userEnabled,
                                      Set<Long> enabledRoleIds, Set<String> menuPerms,
                                      boolean packageEnabled, Set<Long> packageMenuIds, Integer policyVersion) {
    }

    /**
     * 回答一个 (tenantId, userId) 的成员事实。
     *
     * @param tenantId 租户编号
     * @param userId   用户 ID
     * @return 成员事实；用户不存在、已删除或<b>不归属该租户</b>时返回 {@code null}
     *         （调用方据此判定「成员无效/不属于该租户」）
     */
    public CurrentAiMembership describe(String tenantId, Long userId) {
        if (StringUtils.isBlank(tenantId) || ObjectUtil.isNull(userId)) {
            return null;
        }
        return TenantHelper.ignore(() -> {
            SysTenant tenant = tenantMapper.selectOne(new LambdaQueryWrapper<SysTenant>()
                .eq(SysTenant::getTenantId, tenantId));
            if (ObjectUtil.isNull(tenant)) {
                return null;
            }
            SysUser user = userMapper.selectById(userId);
            // 归属校验：用户不存在/已删除（@TableLogic 过滤）或 tenant_id 不符 → 不是该租户成员
            if (ObjectUtil.isNull(user) || !tenantId.equals(user.getTenantId())) {
                return null;
            }

            boolean tenantEnabled = SystemConstants.NORMAL.equals(tenant.getStatus())
                && (ObjectUtil.isNull(tenant.getExpireTime()) || tenant.getExpireTime().after(new Date()));
            boolean userEnabled = SystemConstants.NORMAL.equals(user.getStatus());

            Set<Long> enabledRoleIds = resolveEnabledRoleIds(tenantId, userId);
            Set<String> menuPerms = resolveMenuPerms(enabledRoleIds);

            boolean packageEnabled = false;
            Set<Long> packageMenuIds = new HashSet<>();
            if (ObjectUtil.isNotNull(tenant.getPackageId())) {
                SysTenantPackage tenantPackage = tenantPackageMapper.selectById(tenant.getPackageId());
                packageEnabled = ObjectUtil.isNotNull(tenantPackage)
                    && SystemConstants.NORMAL.equals(tenantPackage.getStatus());
                if (ObjectUtil.isNotNull(tenantPackage)) {
                    packageMenuIds.addAll(StringUtils.splitTo(tenantPackage.getMenuIds(), Convert::toLong));
                }
            }

            Integer policyVersion = policyRevisionService.currentVersion(tenantId).orElse(null);
            return new CurrentAiMembership(tenantId, userId, tenantEnabled, userEnabled,
                enabledRoleIds, menuPerms, packageEnabled, packageMenuIds, policyVersion);
        });
    }

    /**
     * 当前启用角色：sys_user_role → sys_role(status='0')，且角色归属该租户。
     */
    private Set<Long> resolveEnabledRoleIds(String tenantId, Long userId) {
        List<SysUserRole> userRoles = userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
            .eq(SysUserRole::getUserId, userId));
        if (CollUtil.isEmpty(userRoles)) {
            return Set.of();
        }
        List<Long> roleIds = StreamUtils.toList(userRoles, SysUserRole::getRoleId);
        Set<Long> enabledRoleIds = new HashSet<>();
        for (SysRole role : roleMapper.selectByIds(roleIds)) {
            if (SystemConstants.NORMAL.equals(role.getStatus()) && tenantId.equals(role.getTenantId())) {
                enabledRoleIds.add(role.getRoleId());
            }
        }
        return enabledRoleIds;
    }

    /**
     * 菜单功能权限：启用角色 → sys_role_menu → sys_menu(status='0') 的 perms 集合。
     */
    private Set<String> resolveMenuPerms(Set<Long> enabledRoleIds) {
        if (CollUtil.isEmpty(enabledRoleIds)) {
            return Set.of();
        }
        List<SysRoleMenu> roleMenus = roleMenuMapper.selectList(new LambdaQueryWrapper<SysRoleMenu>()
            .in(SysRoleMenu::getRoleId, enabledRoleIds));
        if (CollUtil.isEmpty(roleMenus)) {
            return Set.of();
        }
        List<Long> menuIds = StreamUtils.toList(roleMenus, SysRoleMenu::getMenuId);
        Set<String> menuPerms = new HashSet<>();
        for (SysMenu menu : menuMapper.selectByIds(menuIds)) {
            if (SystemConstants.NORMAL.equals(menu.getStatus()) && StringUtils.isNotBlank(menu.getPerms())) {
                menuPerms.add(menu.getPerms().trim());
            }
        }
        return menuPerms;
    }

    /**
     * 主体（用户）的部门归属事实：所在部门与其祖先链（含自身），
     * 供组织匹配端点做 ownerDeptId 判定；用户未知或无部门时返回 empty。
     */
    public Optional<SubjectOrgFacts> describeOrgFacts(String tenantId, Long userId) {
        if (StringUtils.isBlank(tenantId) || ObjectUtil.isNull(userId)) {
            return Optional.empty();
        }
        return TenantHelper.ignore(() -> {
            SysUser user = userMapper.selectById(userId);
            if (ObjectUtil.isNull(user) || !tenantId.equals(user.getTenantId()) || ObjectUtil.isNull(user.getDeptId())) {
                return Optional.<SubjectOrgFacts>empty();
            }
            SysDept dept = deptMapper.selectById(user.getDeptId());
            if (ObjectUtil.isNull(dept)) {
                return Optional.<SubjectOrgFacts>empty();
            }
            List<Long> ancestors = new ArrayList<>();
            if (StringUtils.isNotBlank(dept.getAncestors())) {
                ancestors.addAll(StringUtils.splitTo(dept.getAncestors(), Convert::toLong));
            }
            return Optional.of(new SubjectOrgFacts(user.getDeptId(), ancestors));
        });
    }

    /**
     * 主体部门归属事实。
     *
     * @param deptId          主体所在部门
     * @param ancestorDeptIds 该部门的祖先部门链（sys_dept.ancestors 解析，不含自身）
     */
    public record SubjectOrgFacts(Long deptId, List<Long> ancestorDeptIds) {
    }

}
