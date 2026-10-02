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
public class RuoYiPlatformIdentitySource implements PlatformIdentitySource, OrganizationMatchController.SubjectMatchSource,
        org.ruoyi.aiintegration.identity.ProductionAuthorizationProvider {

    private org.springframework.jdbc.core.JdbcTemplate permitJdbc;
    private org.springframework.transaction.support.TransactionTemplate permitTransactions;

    @org.springframework.beans.factory.annotation.Autowired
    public void configurePermits(org.springframework.jdbc.core.JdbcTemplate jdbc,
            org.springframework.transaction.support.TransactionTemplate transactions) {
        this.permitJdbc = jdbc;
        this.permitTransactions = transactions;
    }

    @Override
    public PermitGrant acquire(PermitRequest request) {
        if (request == null || request.tenantId() == null || request.subject() == null
                || request.membershipId() == null || request.operationId() == null
                || request.operationId().isBlank() || request.operationId().length() > 64
                || request.resourceRefsHash() == null || !request.resourceRefsHash().matches("[0-9a-f]{64}")
                || request.resourceRef() == null || request.resourceRef().isBlank() || request.resourceRef().length() > 512
                || request.aclVersion() < 1 || request.policyVersion() < 1
                || request.tenantId().isBlank() || request.tenantId().length() > 64 || request.tenantId().contains(":")
                || !request.subject().matches("[0-9]{1,20}")
                || !request.membershipId().equals("platform:" + request.tenantId() + ":" + request.subject())
                || !request.resourceRefsHash().equals(hashRef(request.resourceRef()))) {
            throw new org.ruoyi.aiintegration.web.P04Exception(org.ruoyi.aiintegration.web.P04ErrorCode.BAD_REQUEST);
        }
        return permitTransactions.execute(status -> {
            Integer version = permitJdbc.query("SELECT version FROM sys_ai_policy_revision WHERE tenant_id = ? FOR UPDATE",
                    (rs, n) -> rs.getInt(1), request.tenantId()).stream().findFirst().orElse(null);
            if (version == null) { throw permitUnavailable(); }
            if (version != request.policyVersion()) {
                throw new org.ruoyi.aiintegration.web.P04Exception(org.ruoyi.aiintegration.web.P04ErrorCode.POLICY_VERSION_STALE);
            }
            var barriers = permitJdbc.query("SELECT status FROM sys_ai_tenant_barrier WHERE tenant_id = ?",
                    (rs, n) -> rs.getString(1), request.tenantId());
            if (barriers.stream().anyMatch(s -> !"OPEN".equals(s))) { throw permitUnavailable(); }
            PlatformIdentity identity = membership(request.tenantId(), request.subject(), request.membershipId());
            if (tenantState(request.tenantId()) != TenantState.ENABLED || identity == null || !identity.enabled()) {
                throw new org.ruoyi.aiintegration.web.P04Exception(org.ruoyi.aiintegration.web.P04ErrorCode.MEMBERSHIP_INVALID);
            }
            String permission = org.ruoyi.aiintegration.authorization.AiActionRegistry.requirePermission(request.action());
            if (!identity.scopes().contains(permission)) {
                throw new org.ruoyi.aiintegration.web.P04Exception(org.ruoyi.aiintegration.web.P04ErrorCode.FORBIDDEN);
            }
            String permitId = java.util.UUID.randomUUID().toString();
            int inserted = permitJdbc.update("INSERT INTO sys_ai_execution_permit (permit_id,tenant_id,member_id,action,policy_version,"
                    + "resource_refs_hash,operation_id,status,expires_at) VALUES (?,?,?,?,?,?,?,'ACTIVE',now()+interval '5 minutes')",
                    permitId, request.tenantId(), request.membershipId(), request.action(), version,
                    request.resourceRefsHash(), request.operationId());
            if (inserted != 1) { throw permitUnavailable(); }
            return new PermitGrant(permitId, version, request.operationId());
        });
    }

    @Override
    public void release(PermitRelease request) {
        if (request == null || request.permitId() == null || request.operationId() == null
                || request.tenantId() == null || request.membershipId() == null) {
            throw new org.ruoyi.aiintegration.web.P04Exception(org.ruoyi.aiintegration.web.P04ErrorCode.BAD_REQUEST);
        }
        permitTransactions.executeWithoutResult(status -> {
            var rows = permitJdbc.query("SELECT status FROM sys_ai_execution_permit WHERE permit_id = ?"
                    + " AND tenant_id = ? AND member_id = ? AND operation_id = ? FOR UPDATE",
                    (rs, n) -> rs.getString(1), request.permitId(), request.tenantId(), request.membershipId(), request.operationId());
            if (rows.isEmpty()) {
                Integer owned = permitJdbc.queryForObject("SELECT count(*) FROM sys_ai_execution_permit"
                        + " WHERE permit_id=? AND tenant_id=? AND member_id=?", Integer.class,
                        request.permitId(), request.tenantId(), request.membershipId());
                if (owned != null && owned > 0) {
                    throw new org.ruoyi.aiintegration.web.P04Exception(org.ruoyi.aiintegration.web.P04ErrorCode.IDEMPOTENCY_KEY_REUSED);
                }
                throw new org.ruoyi.aiintegration.web.P04Exception(org.ruoyi.aiintegration.web.P04ErrorCode.FORBIDDEN);
            }
            if ("ACTIVE".equals(rows.get(0))) {
                permitJdbc.update("UPDATE sys_ai_execution_permit SET status='RELEASED',released_at=now() WHERE permit_id=?",
                        request.permitId());
            }
        });
    }

    private static org.ruoyi.aiintegration.web.P04Exception permitUnavailable() {
        return new org.ruoyi.aiintegration.web.P04Exception(org.ruoyi.aiintegration.web.P04ErrorCode.AUTHORIZATION_UNAVAILABLE);
    }

    private static String hashRef(String ref) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(ref.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

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
        boolean enabled = facts.tenantEnabled() && facts.userEnabled() && facts.packageEnabled();
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

    @Override
    public Set<String> currentSubjects(String tenantId, String subject) {
        Long userId = parseUserId(subject);
        if (userId == null) { return Set.of(); }
        var facts = membershipService.describe(tenantId, userId);
        if (facts == null || !facts.userEnabled() || !facts.tenantEnabled()) { return Set.of(); }
        Set<String> refs = new HashSet<>();
        refs.add("member:" + canonicalMembershipId(tenantId, userId));
        refs.add("tenant_all:" + tenantId);
        facts.enabledRoleIds().forEach(id -> refs.add("role:" + id));
        membershipService.describeOrgFacts(tenantId, userId).ifPresent(org -> {
            refs.add("department:" + org.deptId());
            org.ancestorDeptIds().forEach(id -> refs.add("department:" + id));
        });
        return Set.copyOf(refs);
    }

    private record ActionRole(long id,String dataScope) { }
    @Override
    public boolean subjectExists(String tenant,String ref){
        if(ref==null){return false;}
        if(ref.equals("tenant_all:"+tenant)){return tenantState(tenant)==TenantState.ENABLED;}
        String member="member:platform:"+tenant+":";
        if(ref.startsWith(member)){
            var facts=membershipService.describe(tenant,parseUserId(ref.substring(member.length())));
            return facts!=null && facts.userEnabled() && facts.tenantEnabled();
        }
        String table,column,value;
        if(ref.startsWith("role:")){table="sys_role";column="role_id";value=ref.substring(5);}
        else if(ref.startsWith("department:")){table="sys_dept";column="dept_id";value=ref.substring(11);}
        else{return false;}
        Long id=parseUserId(value);if(id==null){return false;}
        if(permitJdbc==null){throw permitUnavailable();}
        return Boolean.TRUE.equals(permitJdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM "+table+" WHERE tenant_id=? AND "+column
                +"=? AND status='0' AND del_flag='0')",Boolean.class,tenant,id));
    }

    private List<ActionRole> actionRoles(String tenant,String subject,String action) {
        if(permitJdbc==null){throw permitUnavailable();}
        Long user=parseUserId(subject);
        var facts=user==null?null:membershipService.describe(tenant,user);
        if(facts==null || !facts.tenantEnabled() || !facts.userEnabled() || !facts.packageEnabled()){return List.of();}
        String permission=org.ruoyi.aiintegration.authorization.AiActionRegistry.requirePermission(action);
        return permitJdbc.query("SELECT DISTINCT r.role_id,r.data_scope,m.menu_id FROM sys_user u"
                +" JOIN sys_user_role ur ON ur.user_id=u.user_id JOIN sys_role r ON r.role_id=ur.role_id AND r.tenant_id=u.tenant_id"
                +" JOIN sys_role_menu rm ON rm.role_id=r.role_id JOIN sys_menu m ON m.menu_id=rm.menu_id"
                +" WHERE u.tenant_id=? AND u.user_id=? AND u.status='0' AND u.del_flag='0'"
                +" AND r.status='0' AND r.del_flag='0' AND m.status='0' AND m.perms=?",
                (rs,n)->facts.packageMenuIds().contains(rs.getLong("menu_id")) && facts.enabledRoleIds().contains(rs.getLong("role_id"))
                        ?new ActionRole(rs.getLong("role_id"),rs.getString("data_scope")):null,tenant,user,permission)
                .stream().filter(java.util.Objects::nonNull).distinct().toList();
    }

    @Override
    public Set<String> currentSubjects(String tenant,String subject,String action) {
        var roles=actionRoles(tenant,subject,action);
        if(roles.isEmpty()){return Set.of();}
        Set<String> refs=new HashSet<>(currentSubjects(tenant,subject));
        refs.removeIf(ref->ref.startsWith("role:"));
        roles.forEach(role->refs.add("role:"+role.id()));
        return Set.copyOf(refs);
    }

    @Override
    public boolean withinDataScope(String tenant,String subject,String action,String ownerMember,String ownerDept) {
        var roles=actionRoles(tenant,subject,action);
        if(roles.isEmpty()){return false;}
        var org=orgFacts(tenant,subject).orElse(null);
        boolean self=("platform:"+tenant+":"+subject).equals(ownerMember);
        // Validate owner membership against live tenant facts even for ALL scope.
        if(ownerMember!=null){
            String prefix="platform:"+tenant+":";
            if(!ownerMember.startsWith(prefix)){return false;}
            var owner=membershipService.describe(tenant,parseUserId(ownerMember.substring(prefix.length())));
            if(owner==null || !owner.userEnabled()){return false;}
        }
        Long dept=ownerDept==null?null:parseUserId(ownerDept);
        List<String> ancestors=dept==null?List.of():permitJdbc.query("SELECT ancestors FROM sys_dept WHERE tenant_id=?"
                +" AND dept_id=? AND status='0' AND del_flag='0'",(rs,n)->rs.getString(1),tenant,dept);
        if(ownerDept!=null && ancestors.isEmpty()){return false;}
        boolean same=org!=null && dept!=null && dept.equals(org.deptId());
        boolean subtree=same || org!=null && !ancestors.isEmpty()
                && java.util.Arrays.asList(ancestors.get(0).split(",")).contains(String.valueOf(org.deptId()));
        for(var role:roles){
            switch(role.dataScope()){
                case "1": return true;
                case "2":
                    if(dept!=null && Boolean.TRUE.equals(permitJdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM sys_role_dept rd"
                            +" JOIN sys_role r ON r.role_id=rd.role_id WHERE r.tenant_id=? AND rd.role_id=? AND rd.dept_id=?)",
                            Boolean.class,tenant,role.id(),dept))){return true;}break;
                case "3": if(same){return true;}break;
                case "4": if(subtree){return true;}break;
                case "5": if(self){return true;}break;
                case "6": if(self || subtree){return true;}break;
                default: break;
            }
        }
        return false;
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
