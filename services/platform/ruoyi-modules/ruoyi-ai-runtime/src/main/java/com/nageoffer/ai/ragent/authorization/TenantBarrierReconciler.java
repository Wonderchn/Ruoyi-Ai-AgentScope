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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/**
 * 租户屏障（{@code ai_tenant_barrier}）的**唯一**对账口径（G-55c）。
 *
 * <p><b>为什么必须集中成一个组件。</b>G-55b 的根因就是"同一张表、两套口径"：
 * 读侧 {@code findActive} 过滤 {@code expires_at}，而 drain 不过滤 —— 同一个事实有两种解释，
 * 于是"还有没有活跃 permit"这句话在两处给出不同答案。若把"PENDING 能否收回 OPEN"的判据
 * 在 {@code writeInternal} 的 finally 与租约回收里各写一份，两处迟早分叉，会再得到一族同源缺陷。
 * 因此本类**唯一持有**三段 SQL：
 * <ul>
 *   <li>{@link #SQL_ACTIVE_PERMITS_OUTSIDE}：可回收判据（<b>与 drain 循环当前口径逐字一致</b>）；</li>
 *   <li>{@link #SQL_BARRIER_STATUS}：屏障行的实际状态（用来区分"真卡在 PENDING"与"本来就不在 PENDING"）；</li>
 *   <li>{@link #SQL_REOPEN_IF_PENDING}：回收语句，**只写 OPEN**。</li>
 * </ul>
 *
 * <p><b>红线：本类没有任何写 {@code CLOSED} 的方法。</b>{@code CLOSED} 只能由业务写事务内的那条
 * UPDATE 写出（{@code AiResourceWriteService.writeInternal} 内），因为 {@code CLOSED} 的含义是
 * "业务写已提交" —— 在 finally/租约回收里补写 {@code CLOSED} 会把**已回滚的业务写记成成功**，
 * 那是把安全机制变成假话（与 V20 坚持的 {@code ABANDONED ≠ CLOSED} 同一纪律）。
 *
 * <p><b>为什么整体走 {@code REQUIRES_NEW}。</b>本方法的主要调用场景是"业务事务即将回滚"的
 * finally：它必须在一个**独立事务**里提交，外层回滚回滚不掉它；反过来，它也绝不能加入外层
 * 事务，否则它自己也会被回滚掉，等于没做。
 *
 * <p><b>失败不静默。</b>仍有未过期活跃 permit 时记 {@code error}（屏障保持 PENDING 是有后果的
 * 状态，必须留日志）；成功收回记 {@code warn}（这是一次真实的屏障状态变更）。
 */
public class TenantBarrierReconciler {

    private static final Logger log = LoggerFactory.getLogger(TenantBarrierReconciler.class);

    /**
     * 可回收判据（**唯一一份**）：本租户除自己之外是否还有**未过期**的 ACTIVE permit。
     *
     * <p>{@code expires_at > CURRENT_TIMESTAMP} 这一条与 drain 循环
     * （{@code AiResourceWriteService} 内两处 count）逐字一致；{@code permit_id<>:permit}
     * 排除本次操作自己的 permit（它此刻必然是 ACTIVE，不排除就永远排不空）。
     */
    static final String SQL_ACTIVE_PERMITS_OUTSIDE =
            "SELECT count(*) FROM ai_execution_permit WHERE tenant_id=:tenant"
                    + " AND status='ACTIVE' AND permit_id<>:permit"
                    + " AND expires_at > CURRENT_TIMESTAMP";

    /**
     * 屏障行的实际状态。<b>为什么需要它</b>：{@code status} 才是"这张屏障卡在 PENDING"的唯一判据；
     * 只凭"排不空活跃 permit"就宣告 PENDING，会把<b>已经正常闭合的屏障</b>也说成卡住
     * （每一次成功写的 finally 都会撞上这个形态：并发读还握着 permit，而本操作的屏障早已 CLOSED/OPEN）。
     * 这条假 error 会淹掉运维判断真卡障的唯一信号，所以状态必须先于告警确立。
     */
    static final String SQL_BARRIER_STATUS =
            "SELECT status FROM ai_tenant_barrier WHERE tenant_id=:tenant AND barrier_id=:barrier";

    /** 回收语句（**唯一一份**）：只把 PENDING 收回 OPEN。 */
    static final String SQL_REOPEN_IF_PENDING =
            "UPDATE ai_tenant_barrier SET status='OPEN', updated_at=now()"
                    + " WHERE tenant_id=:tenant AND barrier_id=:barrier AND status='PENDING'";

    /** {@code ai_tenant_barrier.status} 的"卡住"取值（其余 OPEN/CLOSED/UNKNOWN 都不由本类收回）。 */
    private static final String BARRIER_STATUS_PENDING = "PENDING";

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate requiresNew;

    public TenantBarrierReconciler(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 三态。{@code NOT_PENDING} 必须有：否则"本来就不在 PENDING"会被误记成"回收失败"。 */
    public enum Outcome {
        /** 屏障曾为 PENDING，且已确认无未过期活跃 permit ⇒ 已收回 OPEN。 */
        RECLAIMED,
        /** 仍有未过期活跃 permit ⇒ 保持 PENDING（并已记 error）。 */
        STILL_ACTIVE,
        /** 该 barrier_id 当前不是 PENDING（已被正常提交路径 CLOSED，或已被别的操作收回）。 */
        NOT_PENDING
    }

    /**
     * {@code activePermits} 是本次观察到的"其它未过期活跃 permit 数"：{@code RECLAIMED} 恒为 0，
     * {@code STILL_ACTIVE} 与"因不是 PENDING 而 {@code NOT_PENDING}"都带真实计数（后者可用于排查
     * "同一个租户为什么长期排不空"），标识缺失的短路返回 -1。
     */
    public record ReclaimResult(Outcome outcome, long activePermits) {
    }

    /**
     * 若本租户已无未过期活跃 permit，则把该 barrier 从 PENDING 收回 OPEN。
     *
     * <p><b>只有这一个出口能写 OPEN。</b>正常提交路径不调本方法（它由业务事务内写 CLOSED）。
     *
     * @param tenantId  租户（空即返回 {@code NOT_PENDING}，不猜）
     * @param barrierId 屏障标识（= {@code RevocationGuard.Operation.operationId()}）
     * @param permitId  本次操作自己的 permit（从计数中排除）
     */
    public ReclaimResult reclaimIfNoActivePermit(String tenantId, String barrierId, String permitId) {
        if (tenantId == null || tenantId.isBlank() || barrierId == null || barrierId.isBlank()) {
            return new ReclaimResult(Outcome.NOT_PENDING, -1L);
        }
        Map<String, Object> parameters = Map.of(
                "tenant", tenantId,
                "barrier", barrierId,
                // Map.of 不接受 null：permitId 缺失时用一个不可能等于任何 permit_id 的值，
                // 语义退化为"统计全部未过期 ACTIVE permit" —— 保守方向正确（宁可留 PENDING）。
                "permit", permitId == null ? "" : permitId);
        return requiresNew.execute(status -> {
            Long active = jdbc.queryForObject(SQL_ACTIVE_PERMITS_OUTSIDE, parameters, Long.class);
            long remaining = active == null ? -1L : active;
            if (remaining == 0L) {
                int reopened = jdbc.update(SQL_REOPEN_IF_PENDING, parameters);
                if (reopened == 1) {
                    log.warn("屏障由 PENDING 收回 OPEN：无未过期活跃 permit, tenant={}, barrier={}",
                            tenantId, barrierId);
                    return new ReclaimResult(Outcome.RECLAIMED, 0L);
                }
                // 0 行 = 该 barrier 已不是 PENDING（正常提交已 CLOSED / 已被收回）——**不是失败**。
                return new ReclaimResult(Outcome.NOT_PENDING, 0L);
            }
            // 稀有分支：只有"看起来排不空"时才多读一次屏障状态，用它区分"真卡住"和"本来就不在 PENDING"。
            // 放在这一侧，是为了不给每一次成功的写都插一条额外查询（本方法在 finally 里必执行）。
            if (!BARRIER_STATUS_PENDING.equals(barrierStatus(parameters))) {
                return new ReclaimResult(Outcome.NOT_PENDING, remaining);
            }
            log.error("屏障保持 PENDING：仍有未过期活跃 permit, tenant={}, barrier={}, remaining={}",
                    tenantId, barrierId, remaining);
            return new ReclaimResult(Outcome.STILL_ACTIVE, remaining);
        });
    }

    /** 读屏障行状态；行不存在返回 null（按"不是 PENDING"处理——没有本操作准备过的屏障就无从回收）。 */
    private String barrierStatus(Map<String, Object> parameters) {
        List<String> rows = jdbc.query(SQL_BARRIER_STATUS, parameters, (rs, rowNum) -> rs.getString(1));
        return rows.isEmpty() ? null : rows.get(0);
    }
}
