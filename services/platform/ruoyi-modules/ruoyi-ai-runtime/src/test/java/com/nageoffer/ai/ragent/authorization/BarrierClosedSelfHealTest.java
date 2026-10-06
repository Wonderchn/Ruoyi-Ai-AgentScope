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

package com.nageoffer.ai.ragent.authorization;

import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard.PermitRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W3-T0-23 ②：屏障 CLOSED 滞留自愈（kill 演练 run2 A4b 实录驱动的收敛修复）。
 *
 * <p>缺陷链（2026-10-06 实测）：进程被杀 ⇒ 孤儿 permit（ACTIVE 且永不 release）⇒
 * {@code DefaultRevocationGuard.setBarrierState(OPEN)} 的解除守卫
 * {@code activePermitCount}（旧口径：只看 status='ACTIVE'，不看 expires_at）永久失败 ⇒
 * 屏障滞留 CLOSED ⇒ acquire 门对 CLOSED 直接拒绝 ⇒ 全租户 AI 读写楔死。
 *
 * <p>修复两半，每半一条<b>变异对照锚点</b>（摘掉修复必须变红）：
 * <ol>
 *   <li>{@code activePermitCount} 收敛 expires_at 口径（{@link #m2_activePermitCountSqlCarriesExpiryFilter}）；</li>
 *   <li>acquire 的自愈 CAS 把"解除被阻断而滞留的 CLOSED"纳入自愈对象
 *       （{@link #m1_selfHealSqlKeepsClosedTarget} + {@link #b1_acquireReclaimsStuckClosedBarrier}）。</li>
 * </ol>
 * 红线保持：CAS 只写 OPEN 绝不写 CLOSED；reconciler 缺席/CAS 不成立 ⇒ fail-closed 拒绝；
 * UNKNOWN 无判据基座，仍直接拒（BarrierLeaseSelfHealTest.unknownIsRefusedWithoutSelfHealAttempt）。
 */
@Tag("dev")
class BarrierClosedSelfHealTest {

    private static final String TENANT = "T1";
    private static final String MEMBER = "platform:T1:2101";

    @AfterEach
    void clearPrincipal() {
        com.nageoffer.ai.ragent.framework.context.PrincipalContext.clear();
    }

    // ------------------------------------------------------------------ M1：CAS SQL 变异对照（CLOSED 收敛）

    @Test
    @DisplayName("变异对照①：自愈 CAS 的 status 判据必须含 CLOSED 滞留行（回退 PENDING-only 即红）")
    void m1_selfHealSqlKeepsClosedTarget() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        TenantBarrierReconciler reconciler = new TenantBarrierReconciler(jdbc, noOpTransactionManager());
        when(jdbc.update(anyString(), anyMap())).thenReturn(1);

        assertThat(reconciler.selfHealIfLeaseExpired(TENANT))
                .isEqualTo(TenantBarrierReconciler.LeaseSelfHeal.SELF_HEALED);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), anyMap());
        String cas = sql.getValue();
        // 变异对照：把 status IN ('PENDING','CLOSED') 摘回 status='PENDING' ⇒ 本断言红，
        // CLOSED 滞留屏障回到永久楔死（A4b 实录形态）。
        assertThat(cas).as("自愈对象必须含解除被阻断而滞留的 CLOSED 行（W3-T0-23 ②）")
                .contains("status IN ('PENDING','CLOSED')");
        // 红线不随语义进化回退：只写 OPEN，绝不写 CLOSED。
        assertThat(cas).contains("SET status='OPEN'").doesNotContain("SET status='CLOSED'");
    }

    // ------------------------------------------------------------------ M2：activePermitCount 口径变异对照

    @Test
    @DisplayName("变异对照②：setBarrierState(OPEN) 的解除守卫必须带 expires_at 口径（回退无过滤即红）")
    void m2_activePermitCountSqlCarriesExpiryFilter() {
        JdbcTemplate jdbc = jdbcWithEpoch(3);
        when(jdbc.queryForObject(contains("ai_execution_permit"), any(Class.class), any(Object[].class)))
                .thenReturn(0L);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of("status", "CLOSED", "barrier_id", "b-1")));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        DefaultRevocationGuard guard = new DefaultRevocationGuard(jdbc);

        guard.setBarrierState(TENANT, RevocationGuard.BarrierState.OPEN, "b-1", null, "released");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sql.capture(), any(Class.class), any(Object[].class));
        // 变异对照：把 AND expires_at > CURRENT_TIMESTAMP 摘掉 ⇒ 本断言红，
        // kill 孤儿 permit（ACTIVE 永不 release）重新永久阻断屏障解除（A4b 实录形态）。
        assertThat(sql.getValue()).as("解除守卫的'活跃'口径必须收敛过期语义（W3-T0-23 ①）")
                .contains("expires_at > CURRENT_TIMESTAMP");
    }

    // ------------------------------------------------------------------ B1：CLOSED 滞留自愈放行（行为主例）

    @Test
    @DisplayName("行为主例：CLOSED 滞留 + CAS SELF_HEALED + 复读 OPEN ⇒ 放行登记 permit")
    void b1_acquireReclaimsStuckClosedBarrier() {
        JdbcTemplate jdbc = jdbcWithBarrierStates(List.of("CLOSED"), List.of("OPEN"));
        when(jdbc.update(contains("INSERT INTO ai_execution_permit"), any(Object[].class))).thenReturn(1);
        TenantBarrierReconciler reconciler = mock(TenantBarrierReconciler.class);
        when(reconciler.selfHealIfLeaseExpired(TENANT))
                .thenReturn(TenantBarrierReconciler.LeaseSelfHeal.SELF_HEALED);
        DefaultRevocationGuard guard = new DefaultRevocationGuard(jdbc);
        guard.configureBarrierReconciler(reconciler);

        RevocationGuard.PermitGrant grant = guard.acquire(request());

        // 变异对照：acquire 对 CLOSED 回退为直接拒绝（旧行为）⇒ 本断言红（抛异常）。
        assertThat(grant.permitId()).isNotBlank();
        verify(reconciler).selfHealIfLeaseExpired(TENANT);
        verify(jdbc).update(contains("INSERT INTO ai_execution_permit"), any(Object[].class));
    }

    // ------------------------------------------------------------------ B2/B3：fail-closed 红线保持

    @Test
    @DisplayName("红线保持：CLOSED + reconciler 缺席 ⇒ fail-closed 拒绝（自愈是加路径，不是替换拒绝）")
    void b2_closedStillRefusesWhenReconcilerMissing() {
        JdbcTemplate jdbc = jdbcWithBarrierStates(List.of("CLOSED"), List.of("CLOSED"));
        DefaultRevocationGuard guard = new DefaultRevocationGuard(jdbc);

        assertThatThrownBy(() -> guard.acquire(request()))
                .isInstanceOf(ServiceException.class)
                .hasMessage("租户屏障 CLOSED，拒绝新 permit");
        verify(jdbc, never()).update(contains("INSERT INTO ai_execution_permit"), any(Object[].class));
    }

    @Test
    @DisplayName("红线保持：CLOSED + CAS 不成立（STILL_ACTIVE）⇒ 拒绝且零 INSERT")
    void b3_closedStillRefusesWhenSelfHealDoesNotConverge() {
        JdbcTemplate jdbc = jdbcWithBarrierStates(List.of("CLOSED"), List.of("CLOSED"));
        TenantBarrierReconciler reconciler = mock(TenantBarrierReconciler.class);
        when(reconciler.selfHealIfLeaseExpired(TENANT))
                .thenReturn(TenantBarrierReconciler.LeaseSelfHeal.STILL_ACTIVE);
        DefaultRevocationGuard guard = new DefaultRevocationGuard(jdbc);
        guard.configureBarrierReconciler(reconciler);

        assertThatThrownBy(() -> guard.acquire(request()))
                .isInstanceOf(ServiceException.class)
                .hasMessage("租户屏障 CLOSED，拒绝新 permit");
        verify(jdbc, never()).update(contains("INSERT INTO ai_execution_permit"), any(Object[].class));
    }

    // ------------------------------------------------------------------ 装配辅助

    private static PermitRequest request() {
        return new PermitRequest(TENANT, MEMBER, "kb.write", 7, 3,
                "refshash-1", "op-" + System.nanoTime());
    }

    private static JdbcTemplate jdbcWithEpoch(int version) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(contains("ai_acl_epoch"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(version));
        return jdbc;
    }

    private static JdbcTemplate jdbcWithBarrierStates(List<String> first, List<String> second) {
        JdbcTemplate jdbc = jdbcWithEpoch(3);
        when(jdbc.query(contains("ai_tenant_barrier"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(first, second);
        return jdbc;
    }

    private static org.springframework.transaction.PlatformTransactionManager noOpTransactionManager() {
        return new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction,
                    org.springframework.transaction.TransactionDefinition definition) { }
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) { }
            @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) { }
        };
    }
}
