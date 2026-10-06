package com.nageoffer.ai.ragent.authorization;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link TenantBarrierReconciler} 的行为测试（G-55c 此前**零覆盖**：引用该类的两个测试只验 bean 存在）。
 *
 * <p>核心是 D-1 的回归锚点 {@link #settledBarrierWithActivePermitsDoesNotClaimBarrierIsPending()}：
 * 屏障已闭合（OPEN/CLOSED）且同租户仍有其它活跃 permit 时，必须得到 {@code NOT_PENDING} 且
 * **不得**打出"屏障保持 PENDING"。修复前该场景走的是"先数 permit、非 0 就 error"的顺序，
 * 会返回 {@code STILL_ACTIVE} 并记 error —— 而这条 error 是运维判断"屏障真卡住"的唯一信号，
 * 成功路径上刷出来的假 error 会把信号废掉。
 *
 * <p>同时钉住安全不变量：**回收语句只能在活跃 permit 计数为 0 时执行**。
 * 任何"为了少一次查询而把 reopen 提前"的改法都必须让本类变红。
 *
 * <p>{@code @Tag("dev")} 不是装饰：父 pom 把 surefire 配成 {@code <groups>${profiles.active}</groups>}，
 * CI 跑 {@code -Pdev} ⇒ **没有这个 tag 的测试类一行都不会执行**（表现为 BUILD SUCCESS + Tests run: 0）。
 */
@Tag("dev")
class TenantBarrierReconcilerTest {

    private static final String TENANT = "000000";
    private static final String BARRIER = "barrier-1";
    private static final String PERMIT = "permit-self";
    private static final String PENDING_TOKEN = "屏障保持 PENDING";

    private NamedParameterJdbcTemplate jdbc;
    private TenantBarrierReconciler reconciler;
    private Logger logbackLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        reconciler = new TenantBarrierReconciler(jdbc, noOpTransactionManager());
        logbackLogger = (Logger) LoggerFactory.getLogger(TenantBarrierReconciler.class);
        appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logbackLogger.detachAppender(appender);
        appender.stop();
    }

    /** 回收成功：PENDING + 无其它活跃 permit ⇒ RECLAIMED，且不记 error。 */
    @Test
    void pendingBarrierWithoutOtherActivePermitsIsReclaimed() {
        givenBarrierStatus("PENDING");
        givenActivePermits(0L);
        when(jdbc.update(eq(TenantBarrierReconciler.SQL_REOPEN_IF_PENDING), anyMap())).thenReturn(1);

        TenantBarrierReconciler.ReclaimResult result =
                reconciler.reclaimIfNoActivePermit(TENANT, BARRIER, PERMIT);

        assertEquals(TenantBarrierReconciler.Outcome.RECLAIMED, result.outcome());
        assertEquals(0L, result.activePermits());
        assertFalse(hasMessageContaining(PENDING_TOKEN), "回收成功却记了 PENDING 告警");
    }

    /** 真卡住：PENDING + 仍有未过期活跃 permit ⇒ STILL_ACTIVE，必须记 error，且**绝不**回收。 */
    @Test
    void pendingBarrierWithActivePermitsStaysPendingAndIsReported() {
        givenBarrierStatus("PENDING");
        givenActivePermits(2L);

        TenantBarrierReconciler.ReclaimResult result =
                reconciler.reclaimIfNoActivePermit(TENANT, BARRIER, PERMIT);

        assertEquals(TenantBarrierReconciler.Outcome.STILL_ACTIVE, result.outcome());
        assertEquals(2L, result.activePermits());
        assertTrue(hasMessageContaining(PENDING_TOKEN), "屏障真的卡住时必须留下 error 证据");
        verify(jdbc, never()).update(anyString(), anyMap());
    }

    /**
     * **D-1 锚点**。屏障早已闭合（OPEN / CLOSED 两种收尾态各测一次），但同租户此刻仍有其它活跃 permit
     * —— 这是**每一次成功写的 finally 都会遇到的形状**（permit 由并发读持有，与本操作无关）。
     * 期望：{@code NOT_PENDING}、**不打 PENDING error**、不动屏障行。
     */
    @Test
    void settledBarrierWithActivePermitsDoesNotClaimBarrierIsPending() {
        givenBarrierStatus("OPEN");
        givenActivePermits(1L);

        TenantBarrierReconciler.ReclaimResult openCase =
                reconciler.reclaimIfNoActivePermit(TENANT, BARRIER, PERMIT);

        assertEquals(TenantBarrierReconciler.Outcome.NOT_PENDING, openCase.outcome(),
                "屏障已是 OPEN，却被判成'保持 PENDING'");
        assertFalse(hasMessageContaining(PENDING_TOKEN),
                "成功路径上刷出假 error：屏障并非 PENDING 却记录" + PENDING_TOKEN);
        verify(jdbc, never()).update(anyString(), anyMap());

        givenBarrierStatus("CLOSED");
        givenActivePermits(3L);

        assertEquals(TenantBarrierReconciler.Outcome.NOT_PENDING,
                reconciler.reclaimIfNoActivePermit(TENANT, BARRIER, PERMIT).outcome());
        assertFalse(hasMessageContaining(PENDING_TOKEN), "CLOSED 收尾态同样不得报 PENDING");
    }

    /** 屏障行不存在（该操作从未准备过屏障）：按 NOT_PENDING 处理，保守方向是不写库。 */
    @Test
    void missingBarrierRowIsTreatedAsNotPending() {
        when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of());
        givenActivePermits(1L);

        assertEquals(TenantBarrierReconciler.Outcome.NOT_PENDING,
                reconciler.reclaimIfNoActivePermit(TENANT, BARRIER, PERMIT).outcome());
        assertFalse(hasMessageContaining(PENDING_TOKEN));
        verify(jdbc, never()).update(anyString(), anyMap());
    }

    /** 计数未知（queryForObject 返回 null）时**不回收**：宁可留 PENDING，也不把安全机制放开。 */
    @Test
    void unknownActiveCountNeverReclaims() {
        givenBarrierStatus("PENDING");
        when(jdbc.queryForObject(eq(TenantBarrierReconciler.SQL_ACTIVE_PERMITS_OUTSIDE), anyMap(), eq(Long.class)))
                .thenReturn(null);

        assertEquals(TenantBarrierReconciler.Outcome.STILL_ACTIVE,
                reconciler.reclaimIfNoActivePermit(TENANT, BARRIER, PERMIT).outcome());
        verify(jdbc, never()).update(anyString(), anyMap());
    }

    /** 标识缺失即短路，绝不猜租户、也绝不碰数据库。 */
    @Test
    void blankIdentityShortCircuitsWithoutTouchingDatabase() {
        assertEquals(TenantBarrierReconciler.Outcome.NOT_PENDING,
                reconciler.reclaimIfNoActivePermit(" ", BARRIER, PERMIT).outcome());
        assertEquals(TenantBarrierReconciler.Outcome.NOT_PENDING,
                reconciler.reclaimIfNoActivePermit(TENANT, null, PERMIT).outcome());
        verifyNoInteractions(jdbc);
    }

    // ------------------------------------------------------------------ 装配辅助

    private void givenBarrierStatus(String status) {
        when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of(status));
    }

    private void givenActivePermits(long active) {
        when(jdbc.queryForObject(eq(TenantBarrierReconciler.SQL_ACTIVE_PERMITS_OUTSIDE), anyMap(), eq(Long.class)))
                .thenReturn(active);
    }

    private boolean hasMessageContaining(String token) {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains(token));
    }

    /** 复用本仓先例（{@code P1RevocationRaceTest}）：no-op 事务管理器，只为让 REQUIRES_NEW 真的执行回调。 */
    private static AbstractPlatformTransactionManager noOpTransactionManager() {
        return new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
            @Override protected void doCommit(DefaultTransactionStatus status) { }
            @Override protected void doRollback(DefaultTransactionStatus status) { }
        };
    }
}
