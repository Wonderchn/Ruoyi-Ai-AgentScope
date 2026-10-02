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

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.aiintegration.authorization.OrganizationMatchController;
import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.ruoyi.common.core.constant.SystemConstants;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.tenant.helper.TenantHelper;
import org.ruoyi.system.aiidentity.CurrentAiMembershipService;
import org.ruoyi.system.domain.SysDept;
import org.ruoyi.system.domain.SysTenant;
import org.ruoyi.system.domain.SysUser;
import org.ruoyi.system.mapper.SysDeptMapper;
import org.ruoyi.system.mapper.SysTenantMapper;
import org.ruoyi.system.mapper.SysUserMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * platform 真实身份源（U04/P1.2b）：把
 * {@link org.ruoyi.aiintegration.identity.PlatformIdentitySource} 的身份事实落到
 * platform 真实表——全部事实经 {@link CurrentAiMembershipService}（sys_tenant/sys_user/
 * sys_user_role/sys_role/sys_role_menu/sys_menu/sys_tenant_package + 策略版本）回答。
 *
 * <p>口径：
 * <ul>
 *   <li>scopes = 该用户启用角色集合持有的 {@code ai:*} 菜单 perms（精确字符串，
 *       经 {@code AiActionRegistry} 消费）——<b>无 {@code *:*:*} 豁免</b>：超管的
 *       可产生平台功能允许，但不得产生 AI 资源 ACL 豁免；</li>
 *   <li>policyVersion 精确返回当前值；租户<b>无版本行</b>时 membership 返回 null，
 *       并在日志记录原因（不默认为 1）；</li>
 *   <li>membershipId 精确等于 {@code platform:<tenantId>:<userId>}，不匹配即 null。</li>
 * </ul>
 *
 * <p>本 bean 由 {@code ai.integration.enabled=true} 装配（生产接线开关，默认关）；
 * P0.4 的合成身份源（{@code p04.enabled}）与本实现互斥使用。
 *
 * @author AI-Integration
 */
@Slf4j
@RequiredArgsConstructor
@Component
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class RuoYiPlatformIdentitySource implements PlatformIdentitySource, OrganizationMatchController.SubjectMatchSource {

    /** AI 功能权限前缀：只有该前缀的菜单 perms 才进入身份 scopes。 */
    private static final String AI_PERMS_PREFIX = "ai:";

    private final CurrentAiMembershipService membershipService;
    private final SysTenantMapper tenantMapper;
    private final SysUserMapper userMapper;
    private final SysDeptMapper deptMapper;

    @Override
    public TenantState tenantState(String tenantId) {
        if (StringUtils.isBlank(tenantId)) {
            return TenantState.UNKNOWN;
        }
        SysTenant tenant = TenantHelper.ignore(() -> tenantMapper.selectOne(
            new LambdaQueryWrapper<SysTenant>().eq(SysTenant::getTenantId, tenantId)));
        if (ObjectUtil.isNull(tenant)) {
            return TenantState.UNKNOWN;
        }
        if (!SystemConstants.NORMAL.equals(tenant.getStatus()) || tenantExpired(tenant)) {
            return TenantState.DISABLED;
        }
        return TenantState.ENABLED;
    }

    @Override
    public PlatformIdentity membership(String tenantId, String subject, String membershipId) {
        Long userId = parseUserId(subject);
        if (userId == null || StringUtils.isBlank(tenantId)
                || membershipId == null || !membershipId.equals(canonicalMembershipId(tenantId, userId))) {
            // tid/sub/mid 任一不匹配 → 无此成员身份
            return null;
        }
        CurrentAiMembershipService.CurrentAiMembership facts = membershipService.describe(tenantId, userId);
        if (facts == null) {
            return null;
        }
        if (facts.policyVersion() == null) {
            // 无版本行 → 拒绝（不默认为 1）；日志只记符号事实，不记请求细节
            log.warn("platform identity: tenant {} has no ai policy revision row, membership rejected", tenantId);
            return null;
        }
        Set<String> scopes = new HashSet<>();
        for (String perm : facts.menuPerms()) {
            if (perm.startsWith(AI_PERMS_PREFIX)) {
                scopes.add(perm);
            }
        }
        boolean enabled = facts.tenantEnabled() && facts.userEnabled();
        return new PlatformIdentity(tenantId, subject, membershipId, enabled, scopes, facts.policyVersion());
    }

    @Override
    public Optional<SubjectOrgFacts> orgFacts(String tenantId, String subject) {
        Long userId = parseUserId(subject);
        if (userId == null || StringUtils.isBlank(tenantId)) {
            return Optional.empty();
        }
        return membershipService.describeOrgFacts(tenantId, userId)
            .map(facts -> new SubjectOrgFacts(facts.deptId(), List.copyOf(facts.ancestorDeptIds())));
    }

    /**
     * 租户期限判定：设置了过期时间且已过 → 视为停用（sys_tenant.expire_time，null = 不限）。
     */
    private static boolean tenantExpired(SysTenant tenant) {
        return tenant.getExpireTime() != null && tenant.getExpireTime().before(new Date());
    }

    /**
     * 主体严格解析：十进制数字串（≤20 位）→ 用户 ID；否则 null。
     */
    private static Long parseUserId(String subject) {
        if (subject == null || subject.isBlank() || subject.length() > 20
                || !subject.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Long.parseLong(subject);
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    private static String canonicalMembershipId(String tenantId, Long userId) {
        return "platform:" + tenantId + ":" + userId;
    }

}
