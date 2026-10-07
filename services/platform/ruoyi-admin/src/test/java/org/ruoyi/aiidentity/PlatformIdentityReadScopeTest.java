package org.ruoyi.aiidentity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.ruoyi.system.aiidentity.CurrentAiMembershipService;
import org.ruoyi.system.domain.SysTenant;
import org.ruoyi.system.mapper.SysDeptMapper;
import org.ruoyi.system.mapper.SysTenantMapper;
import org.ruoyi.system.mapper.SysUserMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class PlatformIdentityReadScopeTest {
    private final CurrentAiMembershipService membership = mock(CurrentAiMembershipService.class);
    private final SysTenantMapper tenants = mock(SysTenantMapper.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final RuoYiPlatformIdentitySource source = new RuoYiPlatformIdentitySource(membership, tenants,
        mock(SysUserMapper.class), mock(SysDeptMapper.class));

    PlatformIdentityReadScopeTest() throws Exception {
        source.configurePermits(jdbc, mock(TransactionTemplate.class));
        when(membership.describe("T1", 42L)).thenReturn(new CurrentAiMembershipService.CurrentAiMembership(
            "T1", 42L, true, true, Set.of(9L), Set.of("ai:kb:read", "ai:kb:list"), true, Set.of(11L), 3));
        when(membership.describeOrgFacts("T1", 42L)).thenReturn(Optional.empty());
        SysTenant tenant = new SysTenant();
        tenant.setStatus("0");
        when(tenants.selectOne(any())).thenReturn(tenant);
        when(jdbc.query(anyString(), any(RowMapper.class), eq("T1"), eq(42L), anyString())).thenAnswer(call -> {
            ResultSet row = mock(ResultSet.class);
            when(row.getLong("role_id")).thenReturn(9L);
            when(row.getLong("menu_id")).thenReturn(11L);
            when(row.getString("data_scope")).thenReturn("2");
            return List.of(((RowMapper<?>) call.getArgument(1)).mapRow(row, 0));
        });
        when(jdbc.query(anyString(), any(RowMapper.class), eq("T1"), eq(7L))).thenReturn(List.of("0,1"));
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("T1"), eq(9L), eq(7L))).thenReturn(true);
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
    void fifteenResourcesReuseTenantActionAndDepartmentReads() {
        request("GET");
        for (int i = 0; i < 15; i++) {
            assertThat(source.tenantState("T1")).isEqualTo(PlatformIdentitySource.TenantState.ENABLED);
            assertThat(source.withinDataScope("T1", "42", "kb.read", null, "7")).isTrue();
        }
        verify(tenants, times(1)).selectOne(any());
        verify(jdbc, times(1)).query(anyString(), any(RowMapper.class), eq("T1"), eq(42L), eq("ai:kb:read"));
        verify(jdbc, times(1)).query(anyString(), any(RowMapper.class), eq("T1"), eq(7L));
        verify(jdbc, times(1)).queryForObject(anyString(), eq(Boolean.class), eq("T1"), eq(9L), eq(7L));
    }

    @Test
    void actionAndTenantKeysCannotSharePermissionResults() {
        request("GET");
        source.withinDataScope("T1", "42", "kb.read", null, "7");
        source.withinDataScope("T1", "42", "kb.list", null, "7");
        assertThat(source.withinDataScope("T2", "42", "kb.read", null, "7")).isFalse();
        verify(jdbc).query(anyString(), any(RowMapper.class), eq("T1"), eq(42L), eq("ai:kb:read"));
        verify(jdbc).query(anyString(), any(RowMapper.class), eq("T1"), eq(42L), eq("ai:kb:list"));
    }

    @Test
    void departmentRevocationIsFreshInTransactionsAndTheNextRequest() {
        request("GET");
        assertThat(source.withinDataScope("T1", "42", "kb.read", null, "7")).isTrue();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("T1"), eq(9L), eq(7L))).thenReturn(false);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThat(source.withinDataScope("T1", "42", "kb.read", null, "7")).isFalse();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThat(source.withinDataScope("T1", "42", "kb.read", null, "7")).isFalse();
        request("GET");
        assertThat(source.withinDataScope("T1", "42", "kb.read", null, "7")).isFalse();
        verify(jdbc, times(4)).queryForObject(anyString(), eq(Boolean.class), eq("T1"), eq(9L), eq(7L));
    }

    @Test
    void postRequestsNeverMemoizeAuthorizationFacts() {
        request("POST");
        source.withinDataScope("T1", "42", "kb.read", null, "7");
        source.withinDataScope("T1", "42", "kb.read", null, "7");
        verify(jdbc, times(2)).query(anyString(), any(RowMapper.class), eq("T1"), eq(42L), eq("ai:kb:read"));
    }
}
