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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP-031 / V20：屏障**可恢复性**的判据（T0 派单要点逐条落地）。
 *
 * <p>被替代的旧行为是"fail-closed 且不可恢复"：一次 AI 节点不可达留下 PENDING，
 * 此后该租户所有受护写被 `another policy barrier is pending` 永久拒绝，无出口。
 *
 * <p>本类里**负例比正例多**，且刻意包含两条容易被写漏的：
 * <ul>
 *   <li><b>反例</b>：无审计上下文（缺 operator / 缺 reason）的处置必须失败；</li>
 *   <li><b>反向断言</b>：恢复路径产出的 SQL 里**不得**出现 {@code 'CLOSED'} ——
 *       一旦出现，就意味着一次未闭合的撤权被写成了"撤权成功"。</li>
 * </ul>
 */
@Tag("dev")
class RevocationBarrierRecoveryTest {

    private static final String TENANT = "000000";
    private static final String BARRIER = "barrier-1";
    private static final String OPERATOR = "1";

    private JdbcTemplate jdbc;
    private TransactionTemplate transactionTemplate;
    private RevocationBarrierCoordinator coordinator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        transactionTemplate = mock(TransactionTemplate.class);
        // 事务模板替身：直接把回调执行掉（本类判据关心的是"写了什么 SQL"，
        // 而不是 Spring 的事务传播；后者由 DB 层的原子性保证）。
        doAnswer(invocation -> {
            Consumer<TransactionStatus> body = invocation.getArgument(0);
            body.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        when(jdbc.queryForObject(contains("sys_ai_policy_revision"), eq(Integer.class), anyString()))
                .thenReturn(1);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        coordinator = new RevocationBarrierCoordinator(jdbc,
                mock(org.ruoyi.system.aiidentity.AiPolicyRevisionService.class),
                // AiBarrierPort 有三个方法（close/activePermitCount/open），**不是**函数式接口 ——
                // 第一版我写成 lambda，编译器直接拒（"不是函数式接口"）。这里显式实现，
                // 并让节点侧"可达且活跃数为 0"，使 retryDrain 的用例只受"租约/状态"两个前置约束影响。
                new RevocationBarrierCoordinator.AiBarrierPort() {
                    @Override
                    public java.util.Optional<Long> close(String tenantId, String barrierId, String reason) {
                        return java.util.Optional.of(0L);
                    }

                    @Override
                    public java.util.Optional<Long> activePermitCount(String tenantId) {
                        return java.util.Optional.of(0L);
                    }

                    @Override
                    public void open(String tenantId, String barrierId) {
                        // 本类判据不覆盖 open 路径（由 drainAndBump 的既有用例负责）
                    }
                },
                transactionTemplate);
    }

    /** 让屏障当前状态可读。同包，可直接用包内可见的 {@code BarrierState}。 */
    @SuppressWarnings("unchecked")
    private void givenBarrier(String status, Instant leaseExpiresAt) {
        when(jdbc.query(contains("FROM sys_ai_tenant_barrier"), any(RowMapper.class), eq(TENANT)))
                .thenReturn(List.of(new RevocationBarrierCoordinator.BarrierState(status,
                        leaseExpiresAt == null ? null : Timestamp.from(leaseExpiresAt), 1)));
    }

    private List<String> capturedUpdateSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.atLeastOnce()).update(sql.capture(), any(Object[].class));
        return sql.getAllValues();
    }

    @Test
    @DisplayName("abandon：PENDING → ABANDONED，并写一条 to_status=ABANDONED 的审计（同一次处置）")
    void abandonMovesPendingToAbandonedAndAudits() {
        givenBarrier("PENDING", Instant.now().plusSeconds(300));

        coordinator.abandon(TENANT, BARRIER, OPERATOR, "AI node unreachable for 40 minutes");

        List<String> statements = capturedUpdateSql();
        assertThat(statements)
                .as("必须真的把状态置为 ABANDONED")
                .anyMatch(sql -> sql.contains("status='ABANDONED'"));
        assertThat(statements)
                .as("必须写审计（只追加表），且记录处置动作")
                .anyMatch(sql -> sql.contains("sys_ai_barrier_admin_audit"));
    }

    @Test
    @DisplayName("反向断言：恢复路径**绝不**出现 'CLOSED' —— 未闭合的撤权不得写成撤权成功")
    void recoveryNeverWritesClosed() {
        givenBarrier("PENDING", Instant.now().minusSeconds(60));

        coordinator.abandon(TENANT, BARRIER, OPERATOR, "recovering");

        assertThat(capturedUpdateSql())
                .as("CLOSED 的唯一合法来源是 drain 真闭合 + pv 同事务 bump；"
                        + "恢复路径一旦写出 CLOSED，就等于伪造撤权成功")
                .noneMatch(sql -> sql.contains("'CLOSED'"));
    }

    @Test
    @DisplayName("反例：缺 operator 或缺 reason 的处置必须失败（无审计上下文不得清空屏障）")
    void abandonWithoutAuditContextIsRejected() {
        givenBarrier("PENDING", Instant.now().plusSeconds(300));

        assertThatThrownBy(() -> coordinator.abandon(TENANT, BARRIER, null, "why"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operator");
        assertThatThrownBy(() -> coordinator.abandon(TENANT, BARRIER, "  ", "why"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> coordinator.abandon(TENANT, BARRIER, OPERATOR, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
        assertThatThrownBy(() -> coordinator.abandon(TENANT, BARRIER, OPERATOR, "   "))
                .isInstanceOf(IllegalArgumentException.class);

        verify(jdbc, never()).update(contains("status='ABANDONED'"), any(Object[].class));
    }

    @Test
    @DisplayName("CLOSED 不能被 abandon（那是把成功改写成未完成，与伪造成功同样有害）")
    void closedCannotBeAbandoned() {
        givenBarrier("CLOSED", Instant.now().minusSeconds(60));

        assertThatThrownBy(() -> coordinator.abandon(TENANT, BARRIER, OPERATOR, "oops"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING/UNKNOWN");
        // 用 never() 而不是 capturedUpdateSql()：这条路径**本来就不该产生任何 update**，
        // 而 capturedUpdateSql() 内含 atLeastOnce()，在没有 update 时会因验证失败而误报。
        verify(jdbc, never()).update(contains("status='ABANDONED'"), any(Object[].class));
    }

    @Test
    @DisplayName("retryDrain 只解锁重试：租约未过期时拒绝（保护不沿时间轴自己消失）")
    void retryRequiresAnExpiredLease() {
        givenBarrier("PENDING", Instant.now().plusSeconds(300));

        assertThatThrownBy(() -> coordinator.retryDrain(TENANT, BARRIER, OPERATOR, "retry"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired lease");
    }

    @Test
    @DisplayName("retryDrain 只接受 PENDING（OPEN/CLOSED/ABANDONED 都不是重试对象）")
    void retryRequiresPending() {
        givenBarrier("OPEN", Instant.now().minusSeconds(60));

        assertThatThrownBy(() -> coordinator.retryDrain(TENANT, BARRIER, OPERATOR, "retry"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires PENDING");
    }

    @Test
    @DisplayName("屏障行缺失 ⇒ 拒绝恢复，不用『读不到就当没有屏障』继续")
    void missingBarrierRowRefusesRecovery() {
        when(jdbc.query(contains("FROM sys_ai_tenant_barrier"), any(RowMapper.class), eq(TENANT)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> coordinator.abandon(TENANT, BARRIER, OPERATOR, "recovering"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");
    }

    @Test
    @DisplayName("缺 tenantId/barrierId ⇒ 参数拒绝（不做无租户限定的处置）")
    void missingIdentityIsRejected() {
        assertThatThrownBy(() -> coordinator.abandon(null, BARRIER, OPERATOR, "r"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> coordinator.abandon(TENANT, " ", OPERATOR, "r"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> coordinator.retryDrain(TENANT, BARRIER, null, "r"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operator");
    }
}
