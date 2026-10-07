package org.ruoyi.aiidentity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.system.aiidentity.AiPolicyRevisionService;
import org.ruoyi.system.aiidentity.CurrentAiMembershipService;
import org.ruoyi.system.domain.SysDept;
import org.ruoyi.system.domain.SysTenant;
import org.ruoyi.system.domain.SysTenantPackage;
import org.ruoyi.system.domain.SysUser;
import org.ruoyi.system.mapper.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("dev")
class CurrentAiMembershipRequestScopeTest {
    private final SysTenantMapper tenants = mock(SysTenantMapper.class);
    private final SysTenantPackageMapper packages = mock(SysTenantPackageMapper.class);
    private final SysUserMapper users = mock(SysUserMapper.class);
    private final SysUserRoleMapper userRoles = mock(SysUserRoleMapper.class);
    private final SysRoleMapper roles = mock(SysRoleMapper.class);
    private final SysRoleMenuMapper roleMenus = mock(SysRoleMenuMapper.class);
    private final SysMenuMapper menus = mock(SysMenuMapper.class);
    private final AiPolicyRevisionService policy = mock(AiPolicyRevisionService.class);
    private final SysDeptMapper departments = mock(SysDeptMapper.class);
    private final CurrentAiMembershipService service = newService();
    private final SysUser user;

    CurrentAiMembershipRequestScopeTest() {
        SysTenant tenant = new SysTenant();
        tenant.setTenantId("T1");
        tenant.setStatus("0");
        tenant.setPackageId(1L);
        when(tenants.selectOne(any())).thenReturn(tenant);
        SysTenantPackage tenantPackage = new SysTenantPackage();
        tenantPackage.setStatus("0");
        tenantPackage.setMenuIds("11");
        when(packages.selectById(1L)).thenReturn(tenantPackage);
        user = new SysUser();
        user.setUserId(42L);
        user.setTenantId("T1");
        user.setStatus("0");
        user.setDeptId(7L);
        when(users.selectById(42L)).thenReturn(user);
        when(userRoles.selectList(any())).thenReturn(List.of());
        when(policy.currentVersion("T1")).thenReturn(Optional.of(3));
        SysDept department = new SysDept();
        department.setDeptId(7L);
        department.setTenantId("T1");
        department.setStatus("0");
        department.setAncestors("0,1");
        when(departments.selectById(7L)).thenReturn(department);
    }

    private CurrentAiMembershipService newService() {
        return new CurrentAiMembershipService(tenants, packages, users, userRoles, roles,
            roleMenus, menus, policy, departments);
    }

    private void request(String method) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest(method, "/api/ai/v1/knowledge-bases")));
    }

    @AfterEach
    void cleanup() {
        RequestContextHolder.resetRequestAttributes();
        TransactionSynchronizationManager.clear();
    }

    @Test
    void fifteenRowsReuseMemberAndOrganizationFacts() {
        request("GET");
        for (int row = 0; row < 15; row++) {
            assertThat(service.describe("T1", 42L).userEnabled()).isTrue();
            assertThat(service.describeOrgFacts("T1", 42L)).isPresent();
        }
        verify(tenants, times(1)).selectOne(any());
        verify(users, times(2)).selectById(42L); // one membership read, one organization read
        verify(policy, times(1)).currentVersion("T1");
        verify(departments, times(1)).selectById(7L);
    }

    @Test
    void revokedUserIsReadFreshOnTheNextRequestEvenOnTheSameThread() {
        request("GET");
        assertThat(service.describe("T1", 42L).userEnabled()).isTrue();
        user.setStatus("1");
        request("GET");
        assertThat(service.describe("T1", 42L).userEnabled()).isFalse();
        verify(users, times(2)).selectById(42L);
    }

    @Test
    void cacheKeysSeparateTenantsAndUsers() {
        request("GET");
        assertThat(service.describe("T1", 42L).userEnabled()).isTrue();
        assertThat(service.describe("T2", 42L)).isNull();
        assertThat(service.describe("T1", 43L)).isNull();
    }

    @Test
    void missingMembershipDoesNotBecomeAPersistentDenial() {
        request("GET");
        assertThat(service.describe("T1", 43L)).isNull();
        assertThat(service.describe("T1", 43L)).isNull();
        verify(users, times(1)).selectById(43L);
        SysUser added = new SysUser();
        added.setTenantId("T1");
        added.setStatus("0");
        when(users.selectById(43L)).thenReturn(added);
        request("GET");
        assertThat(service.describe("T1", 43L).userEnabled()).isTrue();
    }

    @Test
    void transactionsRecheckAndInvalidateTheEarlierReadSnapshot() {
        request("GET");
        assertThat(service.describe("T1", 42L).userEnabled()).isTrue();
        user.setStatus("1");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThat(service.describe("T1", 42L).userEnabled()).isFalse();
        assertThat(service.describe("T1", 42L).userEnabled()).isFalse();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThat(service.describe("T1", 42L).userEnabled()).isFalse();
        verify(users, times(4)).selectById(42L);
    }

    @Test
    void writesAndWorkersNeverReuseARequestSnapshot() {
        request("POST");
        assertThat(service.describe("T1", 42L).userEnabled()).isTrue();
        user.setStatus("1");
        assertThat(service.describe("T1", 42L).userEnabled()).isFalse();
        RequestContextHolder.resetRequestAttributes();
        service.describe("T1", 42L);
        service.describe("T1", 42L);
        verify(users, times(4)).selectById(42L);
    }

    @Test
    void failedFactReadsAreNotCachedAsSuccessOrDenial() {
        request("GET");
        when(users.selectById(42L)).thenThrow(new IllegalStateException("unavailable")).thenReturn(user);
        assertThatThrownBy(() -> service.describe("T1", 42L)).isInstanceOf(IllegalStateException.class);
        assertThat(service.describe("T1", 42L).userEnabled()).isTrue();
    }

    @Test
    void distinctServiceInstancesDoNotShareCachedValues() {
        request("GET");
        assertThat(service.describe("T1", 42L).userEnabled()).isTrue();
        user.setStatus("1");
        assertThat(newService().describe("T1", 42L).userEnabled()).isFalse();
    }
}
