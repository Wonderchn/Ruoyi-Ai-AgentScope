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

import com.nageoffer.ai.ragent.authorization.dao.AiAclEpochMapper;
import com.nageoffer.ai.ragent.authorization.dao.AiResourceAclMapper;
import com.nageoffer.ai.ragent.authorization.dao.AiResourceAclMapper.AclRow;
import com.nageoffer.ai.ragent.authorization.dao.AiResourceMapper;
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
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W3-1 屏障租约自愈（L3-T2R-AUTHZ 任务①）的行为验收。
 *
 * <p>缺陷形态（V23 背景）：进程被杀后无人跑 finally 对账，{@code ai_tenant_barrier} 永久
 * PENDING ⇒ 该租户全部写 503。修复分两半：
 * <ol>
 *   <li><b>唯一自愈入口</b>：{@code DefaultRevocationGuard.acquire} 的屏障门 —— PENDING 且
 *       reconciler 的"租约过期 + 无未过期 ACTIVE permit"CAS 成立 ⇒ 收回 OPEN 并放行；
 *       其余一切结果 ⇒ 维持拒绝（负例语义**进化而不消失**：租约未过 / 组件缺席时仍然拒绝）；</li>
 *   <li><b>PENDING 必带租约</b>：两个 PENDING 写入方（AI 侧 prepare、platform CLOSE 通道的
 *       {@code setBarrierState}）都 stamp {@code lease_expires_at}；CLOSE 同时清租约与 reconcile 留痕。</li>
 * </ol>
 *
 * <p>红线（每条都有能失败的锚点）：<b>租约未过 ⇒ 绝不回收</b>；<b>仍有未过期 ACTIVE permit
 * ⇒ 绝不回收</b>；<b>CAS 只写 OPEN，绝不写 CLOSED</b>；<b>自愈对象是租户行</b>（卡死行的
 * barrier_id 属于已死进程，新操作无从知道）。
 */
@Tag("dev")
class BarrierLeaseSelfHealTest {

    private static final String TENANT = "T1";
    private static final long LEASE = 300L;

    @AfterEach
    void clearPrincipal() {
        com.nageoffer.ai.ragent.framework.context.PrincipalContext.clear();
    }

    // ------------------------------------------------------------------ 1. reconciler 自愈 CAS（真 reconciler + mock JDBC）

    @Test
    @DisplayName("CAS 成立（1 行）：SELF_HEALED，且 SQL 按 tenant_id 定位（不按已死进程的 barrier_id）")
    void selfHealCasReclaimsByTenantRowWhenLeaseExpired() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        TenantBarrierReconciler reconciler = new TenantBarrierReconciler(jdbc, noOpTransactionManager());
        when(jdbc.update(anyString(), anyMap())).thenReturn(1);

        TenantBarrierReconciler.LeaseSelfHeal outcome = reconciler.selfHealIfLeaseExpired(TENANT);

        assertThat(outcome).isEqualTo(TenantBarrierReconciler.LeaseSelfHeal.SELF_HEALED);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), anyMap());
        String cas = sql.getValue();
        // 锚点（缺一即恒真）：CAS 必须真的携带全部判据。
        // W3-T0-23 ②：自愈对象从 PENDING 扩到"解除被孤儿 permit 阻断而滞留的 CLOSED"，
        // 故 status 判据为 IN ('PENDING','CLOSED')（语义进化锚点，变异对照见
        // BarrierClosedSelfHealTest.m1_selfHealSqlKeepsClosedTarget）。
        assertThat(cas).contains("status IN ('PENDING','CLOSED')")
                .contains("lease_expires_at <= now()")
                .contains("NOT EXISTS(SELECT 1 FROM ai_execution_permit")
                .contains("p.status='ACTIVE'")
                .contains("p.expires_at>CURRENT_TIMESTAMP")
                .contains("reconciled_by='lease-reconciler'");
        // kill 场景关键锚点：自愈对象是租户行 —— SQL 里不得出现 barrier_id 匹配
        assertThat(cas).as("自愈 CAS 不得按 barrier_id 匹配（卡死行的 barrier_id 属于已死进程）")
                .doesNotContain("barrier_id");
        // 红线：CAS 只写 OPEN，绝不写 CLOSED（SET 目标单一；WHERE 里的 CLOSED 是选择自愈对象）
        assertThat(cas).as("自愈 SET 目标必须是 OPEN")
                .contains("SET status='OPEN'");
        assertThat(cas).as("自愈绝不写 CLOSED（CLOSED 只能由业务写事务写出）")
                .doesNotContain("SET status='CLOSED'");
    }

    @Test
    @DisplayName("CAS 0 行 + 屏障仍 PENDING + 无活跃 permit ⇒ LEASE_NOT_EXPIRED（租约未过绝不回收）")
    void leaseNotExpiredIsDistinguishedFromStillActive() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        TenantBarrierReconciler reconciler = new TenantBarrierReconciler(jdbc, noOpTransactionManager());
        when(jdbc.update(anyString(), anyMap())).thenReturn(0);
        when(jdbc.query(contains("FROM ai_tenant_barrier"), anyMap(), any(RowMapper.class)))
                .thenReturn(List.of("PENDING"));
        when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(0L);

        assertThat(reconciler.selfHealIfLeaseExpired(TENANT))
                .isEqualTo(TenantBarrierReconciler.LeaseSelfHeal.LEASE_NOT_EXPIRED);
    }

    @Test
    @DisplayName("CAS 0 行 + 屏障 PENDING + 仍有未过期 ACTIVE permit ⇒ STILL_ACTIVE（绝不回收）")
    void stillActivePermitsBlockSelfHeal() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        TenantBarrierReconciler reconciler = new TenantBarrierReconciler(jdbc, noOpTransactionManager());
        when(jdbc.update(anyString(), anyMap())).thenReturn(0);
        when(jdbc.query(contains("FROM ai_tenant_barrier"), anyMap(), any(RowMapper.class)))
                .thenReturn(List.of("PENDING"));
        when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(2L);

        assertThat(reconciler.selfHealIfLeaseExpired(TENANT))
                .isEqualTo(TenantBarrierReconciler.LeaseSelfHeal.STILL_ACTIVE);
    }

    @Test
    @DisplayName("CAS 0 行 + 行 CLOSED（解除滞留）⇒ STILL_ACTIVE（无租约可判，保守不回收）——W3-T0-23 ② 语义进化")
    void closedRowAttributionIsStillActive() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        TenantBarrierReconciler reconciler = new TenantBarrierReconciler(jdbc, noOpTransactionManager());
        when(jdbc.update(anyString(), anyMap())).thenReturn(0);
        when(jdbc.query(contains("FROM ai_tenant_barrier"), anyMap(), any(RowMapper.class)))
                .thenReturn(List.of("CLOSED"));
        when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(0L);

        assertThat(reconciler.selfHealIfLeaseExpired(TENANT))
                .isEqualTo(TenantBarrierReconciler.LeaseSelfHeal.STILL_ACTIVE);
    }

    @Test
    @DisplayName("租户为空：短路，绝不碰数据库")
    void blankTenantShortCircuits() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        TenantBarrierReconciler reconciler = new TenantBarrierReconciler(jdbc, noOpTransactionManager());

        assertThat(reconciler.selfHealIfLeaseExpired(" "))
                .isEqualTo(TenantBarrierReconciler.LeaseSelfHeal.NOT_PENDING);
        verify(jdbc, never()).update(anyString(), anyMap());
    }

    // ------------------------------------------------------------------ 2. acquire 屏障门（自愈唯一入口）

    @Test
    @DisplayName("门自愈正例：PENDING + CAS SELF_HEALED + 复读 OPEN ⇒ 放行登记 permit")
    void acquireProceedsAfterSuccessfulSelfHeal() {
        JdbcTemplate jdbc = jdbcWithBarrierStates(List.of("PENDING"), List.of("OPEN"));
        when(jdbc.update(contains("INSERT INTO ai_execution_permit"), any(Object[].class))).thenReturn(1);
        TenantBarrierReconciler reconciler = mock(TenantBarrierReconciler.class);
        when(reconciler.selfHealIfLeaseExpired(TENANT))
                .thenReturn(TenantBarrierReconciler.LeaseSelfHeal.SELF_HEALED);
        DefaultRevocationGuard guard = new DefaultRevocationGuard(jdbc);
        guard.configureBarrierReconciler(reconciler);

        RevocationGuard.PermitGrant grant = guard.acquire(request());

        assertThat(grant.permitId()).isNotBlank();
        verify(reconciler).selfHealIfLeaseExpired(TENANT);
        verify(jdbc).update(contains("INSERT INTO ai_execution_permit"), any(Object[].class));
    }

    @Test
    @DisplayName("门负例（进化后的旧锚点）：PENDING + 租约未过 ⇒ 拒绝且零 INSERT（负例语义不消失）")
    void acquireStillRefusesWhenLeaseNotExpired() {
        JdbcTemplate jdbc = jdbcWithBarrierStates(List.of("PENDING"), List.of("PENDING"));
        TenantBarrierReconciler reconciler = mock(TenantBarrierReconciler.class);
        when(reconciler.selfHealIfLeaseExpired(TENANT))
                .thenReturn(TenantBarrierReconciler.LeaseSelfHeal.LEASE_NOT_EXPIRED);
        DefaultRevocationGuard guard = new DefaultRevocationGuard(jdbc);
        guard.configureBarrierReconciler(reconciler);

        assertThatThrownBy(() -> guard.acquire(request())).isInstanceOf(ServiceException.class);
        verify(jdbc, never()).update(contains("INSERT INTO ai_execution_permit"), any(Object[].class));
    }

    @Test
    @DisplayName("门负例：reconciler 缺席 ⇒ fail-closed 拒绝（自愈是加路径，不是替换拒绝）")
    void acquireRefusesWhenReconcilerMissing() {
        JdbcTemplate jdbc = jdbcWithBarrierStates(List.of("PENDING"), List.of("PENDING"));
        DefaultRevocationGuard guard = new DefaultRevocationGuard(jdbc);

        assertThatThrownBy(() -> guard.acquire(request())).isInstanceOf(ServiceException.class);
        verify(jdbc, never()).update(contains("INSERT INTO ai_execution_permit"), any(Object[].class));
    }

    @Test
    @DisplayName("门负例：CAS 声称成功但复读仍非 OPEN ⇒ 拒绝（不复读等于信任组件间竞态）")
    void acquireRefusesWhenReReadIsStillNotOpen() {
        JdbcTemplate jdbc = jdbcWithBarrierStates(List.of("PENDING"), List.of("PENDING"));
        TenantBarrierReconciler reconciler = mock(TenantBarrierReconciler.class);
        when(reconciler.selfHealIfLeaseExpired(TENANT))
                .thenReturn(TenantBarrierReconciler.LeaseSelfHeal.SELF_HEALED);
        DefaultRevocationGuard guard = new DefaultRevocationGuard(jdbc);
        guard.configureBarrierReconciler(reconciler);

        assertThatThrownBy(() -> guard.acquire(request())).isInstanceOf(ServiceException.class);
        verify(jdbc, never()).update(contains("INSERT INTO ai_execution_permit"), any(Object[].class));
    }

    @Test
    @DisplayName("门负例：UNKNOWN 直接拒绝且不进自愈（无判据基座）；CLOSED 的自愈语义见 BarrierClosedSelfHealTest")
    void unknownIsRefusedWithoutSelfHealAttempt() {
        JdbcTemplate jdbc = jdbcWithBarrierStates(List.of("UNKNOWN"), List.of("UNKNOWN"));
        TenantBarrierReconciler reconciler = mock(TenantBarrierReconciler.class);
        DefaultRevocationGuard guard = new DefaultRevocationGuard(jdbc);
        guard.configureBarrierReconciler(reconciler);

        assertThatThrownBy(() -> guard.acquire(request()))
                .as("屏障 UNKNOWN 必须直接拒绝").isInstanceOf(ServiceException.class);
        verify(reconciler, never()).selfHealIfLeaseExpired(anyString());
        verify(jdbc, never()).update(contains("INSERT INTO ai_execution_permit"), any(Object[].class));
    }

    // ------------------------------------------------------------------ 3. PENDING 必带租约（两个写入方）

    @Test
    @DisplayName("setBarrierState(PENDING)：stamp lease_expires_at=now()+300s（platform CLOSE 通道）")
    void setBarrierStatePendingStampsLease() {
        JdbcTemplate jdbc = jdbcWithEpoch(3);
        // setBarrierState 会读现有屏障行（queryForList）；不 stub 则 mock 返回 null ⇒ NPE。
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of("status", "PENDING", "barrier_id", "b-1")));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        DefaultRevocationGuard guard = new DefaultRevocationGuard(jdbc);

        guard.setBarrierState(TENANT, RevocationGuard.BarrierState.PENDING, "b-1", null, "policy mutation");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        assertThat(sql.getValue()).as("屏障 upsert 必须 stamp lease_expires_at").contains("lease_expires_at");
        Object[] a = args.getValue();
        assertThat(a).hasSize(7);
        assertThat(a[1]).isEqualTo("PENDING");
        assertThat(a[5]).as("PENDING 分支判定参数").isEqualTo("PENDING");
        assertThat(((Number) a[6]).longValue())
                .as("租约时长必须与 permit 侧同源（不引入第三个自定义时长）")
                .isEqualTo(LEASE)
                .isEqualTo(DefaultRevocationGuard.BARRIER_LEASE_SECONDS);
    }

    @Test
    @DisplayName("setBarrierState(OPEN)：不 stamp 租约（CASE WHEN 非 PENDING 分支）")
    void setBarrierStateOpenDoesNotStampLease() {
        JdbcTemplate jdbc = jdbcWithEpoch(3);
        when(jdbc.queryForObject(contains("ai_execution_permit"), any(Class.class), any(Object[].class)))
                .thenReturn(0L);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of("status", "PENDING", "barrier_id", "b-1")));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        DefaultRevocationGuard guard = new DefaultRevocationGuard(jdbc);

        guard.setBarrierState(TENANT, RevocationGuard.BarrierState.OPEN, "b-1", null, "released");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        assertThat(sql.getValue()).contains("CASE WHEN");
        Object[] a = args.getValue();
        assertThat(a[1]).isEqualTo("OPEN");
        assertThat(a[5]).as("OPEN 走非 PENDING 分支，不 stamp 租约").isEqualTo("OPEN");
    }

    @Test
    @DisplayName("AI 侧 prepare：PENDING 带 lease_expires_at 与 attempt_count+1；覆盖条件不被改动（红线锚点）")
    void prepareStampsLeaseAndKeepsOriginalConflictCondition() {
        com.nageoffer.ai.ragent.framework.context.PrincipalContext.set(principal());
        when(resourceMapper.findByPk(TENANT, "KB", "kb-1"))
                .thenReturn(Optional.of(new AiResourceMapper.AiResourceRow(TENANT, "KB", "kb-1", MEMBER,
                        null, null, null, "ACTIVE", 1L)));
        AiResourceWriteService writeService = writeService();

        writeService.grantAclRule("kb-1",
                new AiResourceWriteService.AclGrant("member", MEMBER, "kb.read", null));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(namedJdbc, org.mockito.Mockito.times(2)).update(sql.capture(), anyMap());
        List<String> statements = sql.getAllValues();
        String prepareSql = statements.stream().filter(s -> s.startsWith("INSERT INTO ai_tenant_barrier"))
                .findFirst().orElseThrow(() -> new AssertionError("prepare upsert 未发出"));
        String closeSql = statements.stream().filter(s -> s.startsWith("UPDATE ai_tenant_barrier"))
                .findFirst().orElseThrow(() -> new AssertionError("barrier close 未发出"));

        assertThat(prepareSql).as("PENDING 必带租约（AI 侧写入方）")
                .contains("lease_expires_at")
                .contains("now() + (:barrierLease * interval '1 second')")
                .as("attempt_count 递增（V23 约定 D 点）")
                .contains("attempt_count=ai_tenant_barrier.attempt_count+1")
                .as("新一轮 PENDING 清掉上一轮 reconcile 留痕")
                .contains("reconciled_at=NULL");
        assertThat(prepareSql).as("红线：prepare 覆盖条件必须保持原样（自愈唯一入口在 acquire 门）")
                .contains("WHERE ai_tenant_barrier.status='OPEN' OR ai_tenant_barrier.barrier_id=EXCLUDED.barrier_id");
        assertThat(closeSql).as("提交闭合清租约：非 PENDING 行不留租约")
                .contains("status='CLOSED'")
                .contains("lease_expires_at=NULL");
    }

    // ------------------------------------------------------------------ 装配辅助

    private static final String MEMBER = "platform:T1:2101";

    private final AiResourceMapper resourceMapper = mock(AiResourceMapper.class);
    private final AiResourceAclMapper aclMapper = mock(AiResourceAclMapper.class);
    private final AiAclEpochMapper epochMapper = mock(AiAclEpochMapper.class);
    private NamedParameterJdbcTemplate namedJdbc;

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

    /**
     * 屏障状态读序列：acquire 门至少读两次（CAS 前 / CAS 后复读），
     * 用 consecutive stub 表达"收回前 PENDING、收回后 OPEN"。
     */
    private static JdbcTemplate jdbcWithBarrierStates(List<String> first, List<String> second) {
        JdbcTemplate jdbc = jdbcWithEpoch(3);
        when(jdbc.query(contains("ai_tenant_barrier"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(first, second);
        return jdbc;
    }

    /** writeInternal 驱动（同 P1PersistentAclTest 形态）：mock 事务模板同步执行回调。 */
    private AiResourceWriteService writeService() {
        namedJdbc = mock(NamedParameterJdbcTemplate.class);
        TransactionOperations txOps = mock(TransactionOperations.class);
        // 块 lambda + 显式 return：Answer.answer 声明返回 Object，
        // 表达式 lambda 里塞 void 的 Consumer.accept() 会编译失败（T0 边界①实测 exit 1）。
        org.mockito.Mockito.doAnswer(inv -> {
            ((Consumer<?>) inv.getArgument(0)).accept(null);
            return null;
        }).when(txOps).executeWithoutResult(any());
        org.mockito.Mockito.doAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0))
                .doInTransaction(mock(TransactionStatus.class))).when(txOps).execute(any(TransactionCallback.class));
        when(namedJdbc.update(anyString(), anyMap())).thenReturn(1);
        when(namedJdbc.query(contains("FOR UPDATE"), anyMap(), any(RowMapper.class))).thenReturn(List.of(3));
        when(namedJdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(0L);
        when(epochMapper.bump(anyString())).thenReturn(Optional.of(4));
        when(aclMapper.insert(any(AclRow.class))).thenReturn(1);

        var guard = mock(RevocationGuard.class);
        when(guard.enter(any(), anyString(), anyString()))
                .thenReturn(new RevocationGuard.Operation(guard, "permit", "op"));
        var resources = mock(com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService.class);
        when(resources.check(any(), anyString(), anyString()))
                .thenReturn(com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService.Verdict.GRANT);
        var platform = mock(com.nageoffer.ai.ragent.framework.security.AuthorizationChecker.class);
        when(platform.check(any(), anyString(), anyString()))
                .thenReturn(new com.nageoffer.ai.ragent.framework.security.AuthorizationChecker.AuthorizeResult(true, 7));

        AiResourceWriteService service = new AiResourceWriteService(
                namedJdbc, resourceMapper, aclMapper, epochMapper, txOps);
        service.configureExecution(guard, resources, platform);
        service.setHighRiskEnabled(true);
        return service;
    }

    private static com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal principal() {
        return new com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal(TENANT, "2101", MEMBER, 7, 3,
                Set.of("kb.acl.manage", "kb.write", "kb.delete"), "jti-heal", "platform", 0, Long.MAX_VALUE);
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
