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
 * 因此本类**唯一持有**五段 SQL：
 * <ul>
 *   <li>{@link #SQL_ACTIVE_PERMITS_OUTSIDE}：可回收判据（<b>drain 循环两处也引用本常量</b>——
 *       W3-1 收敛后全工程不再存在第二份"活跃 permit"SQL 文本）；</li>
 *   <li>{@link #SQL_BARRIER_STATUS}：屏障行的实际状态（按 barrier_id，区分"真卡在 PENDING"
 *       与"本来就不在 PENDING"）；</li>
 *   <li>{@link #SQL_BARRIER_STATUS_BY_TENANT}：同上但按租户（自愈 CAS 的 0 行归因用）；</li>
 *   <li>{@link #SQL_REOPEN_IF_PENDING}：回收语句，**只写 OPEN**；</li>
 *   <li>{@link #SQL_SELF_HEAL_IF_LEASE_EXPIRED}：W3-1 跨进程自愈 CAS——进程被杀后无人跑
 *       finally 对账，屏障永久 PENDING ⇒ 全租户写 503。自愈入口是
 *       {@code DefaultRevocationGuard.acquire} 的屏障门：PENDING（W3-T0-23 ② 起含解除被
 *       孤儿 permit 阻断而滞留的 CLOSED 行）+ 租约过期/无租约 + 无未过期 ACTIVE permit
 *       ⇒ 先收回 OPEN 再登记新 permit。租约条件**必须进 CAS 的 WHERE**
 *       （不能只做 Java 侧预判），否则与并发 prepare 刷租约竞争时会收回一个仍在推进的屏障。</li>
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
     * <p><b>W3-1 drain 收敛</b>：{@code AiResourceWriteService} 的 drain 轮询与提交前复核
     * 曾经内联着与本常量逐字相同的文本 —— "同一张表、两套口径"的 G-55b 同族隐患
     * （改一处漏一处 = 两处对"还有没有活跃 permit"给出不同答案）。收敛后 drain 两侧
     * 直接引用本常量，本类是唯一权威。
     *
     * <p><b>反向证明</b>（{@code BarrierSqlConvergenceTest}）：把本常量里的
     * {@code expires_at > CURRENT_TIMESTAMP} 子句摘掉，drain 两侧与 reconciler 必须
     * <b>一起</b>变红 —— 只单点变红 = 没收敛。
     *
     * <p>{@code permit_id<>:permit} 排除本次操作自己的 permit（它此刻必然是 ACTIVE，
     * 不排除就永远排不空）。
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

    /**
     * 屏障行的实际状态（按租户，自愈 CAS 的 0 行归因用）。
     * 自愈 CAS 不能按 {@code barrier_id} 匹配——卡死行的 barrier_id 属于**已死进程**，
     * 新操作的 operationId 与它必然不同；租户行本身（PK tenant_id）才是自愈对象。
     */
    static final String SQL_BARRIER_STATUS_BY_TENANT =
            "SELECT status FROM ai_tenant_barrier WHERE tenant_id=:tenant";

    /** 回收语句（**唯一一份**）：只把 PENDING 收回 OPEN。 */
    static final String SQL_REOPEN_IF_PENDING =
            "UPDATE ai_tenant_barrier SET status='OPEN', updated_at=now()"
                    + " WHERE tenant_id=:tenant AND barrier_id=:barrier AND status='PENDING'";

    /**
     * W3-1 跨进程自愈 CAS（**唯一一份**）：PENDING（或解除被阻断而滞留的 CLOSED，W3-T0-23 ②）
     * + 屏障租约已过/无租约 + 无未过期 ACTIVE permit ⇒ 收回 OPEN。租约未过或仍有未过期
     * ACTIVE permit 时 WHERE 不成立、影响 0 行 ⇒ <b>绝不回收</b>（红线：租约未过绝不放行；
     * 只写 OPEN，绝不写 CLOSED）。
     *
     * <p>三个判据全部在 SQL 里（不是 Java 预判）：CAS 的原子性依赖 WHERE 一次性裁决，
     * 否则"读到过期 → 判断 → 写回"之间并发 prepare 刷了租约，就会收回一个仍在推进的屏障。
     * <b>刻意不按 {@code barrier_id} 匹配</b>：卡死行的 barrier_id 属于已死进程，新操作无从知道；
     * 自愈对象是"该租户当前这张卡死的行"（PK tenant_id 唯一）。
     * {@code NOT EXISTS} 刻意<b>没有</b> {@code permit_id<>} 排除项：本 CAS 的调用时刻
     * 新 permit 尚未登记，全量计数才保守（调用点被 {@code ai_acl_epoch} 行锁串行化保护）。
     * {@code lease_expires_at IS NULL} 一支兜底两类行——V23 之后 PENDING 必带租约
     * （{@code DefaultRevocationGuard.setBarrierState} 与 {@code AiResourceWriteService} 的
     * prepare 都 stamp）；<b>CLOSED 行提交闭合时清租约</b>（{@code lease_expires_at=NULL}），
     * 落进同一分支。
     *
     * <p><b>为什么 CLOSED 也是自愈对象（W3-T0-23 ②，2026-10-06 kill 演练 run2 A4b 实录）：</b>
     * CLOSED 本应只存活于"业务提交 → setBarrierState(OPEN) 解除"的瞬态窗；但解除守卫
     * （{@code activePermitCount}）在旧口径下会被进程孤儿 permit（ACTIVE 且永不 release）
     * 永久打失败 ⇒ CLOSED 滞留 ⇒ acquire 门直接拒绝 ⇒ 全租户 AI 读写楔死。把 CLOSED 纳入
     * 同一条 CAS（活跃集判据不变）即可在"无未过期活跃 permit"时收回 OPEN——CLOSED 本就是
     * 业务已提交的安全态，收回只写 OPEN、绝不写 CLOSED 的红线不变。
     *
     * <p>写入 {@code reconciled_at/reconciled_by}：kill 演练判据的取证列（V23 约定）。
     */
    static final String SQL_SELF_HEAL_IF_LEASE_EXPIRED =
            "UPDATE ai_tenant_barrier SET status='OPEN', updated_at=now(),"
                    + " reconciled_at=now(), reconciled_by='lease-reconciler'"
                    + " WHERE tenant_id=:tenant AND status IN ('PENDING','CLOSED')"
                    + " AND (lease_expires_at IS NULL OR lease_expires_at <= now())"
                    + " AND NOT EXISTS(SELECT 1 FROM ai_execution_permit p WHERE p.tenant_id=:tenant"
                    + " AND p.status='ACTIVE' AND p.expires_at>CURRENT_TIMESTAMP)";

    /** {@code ai_tenant_barrier.status} 的自愈对象取值：PENDING（卡死）与 CLOSED（解除被
     *  孤儿 permit 阻断而滞留，W3-T0-23 ②）。OPEN/UNKNOWN 不由本类触碰。 */
    private static final String BARRIER_STATUS_PENDING = "PENDING";
    private static final String BARRIER_STATUS_CLOSED = "CLOSED";

    /**
     * 屏障 PENDING 租约时长（秒）：写 PENDING 的一方（prepare / {@code setBarrierState}）
     * 必须 stamp {@code lease_expires_at = now() + 本常量}。
     *
     * <p>W3-1 裁定（T0 批准）：取 {@link DefaultRevocationGuard#LEASE_SECONDS}（300）——
     * 平台侧 {@code RevocationBarrierCoordinator} 与 AI 侧 permit 已是同一常量，
     * 本表不再引入第三个自定义时长。租约只用于"失联判定"（跨进程自愈的**解锁**条件），
     * 绝不用于宣告成功；自愈能否真正收回由 SQL_SELF_HEAL_IF_LEASE_EXPIRED 的
     * 活跃集判据独立裁决。
     */
    static final long BARRIER_LEASE_SECONDS = DefaultRevocationGuard.LEASE_SECONDS;

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
     * 跨进程自愈 CAS 的三态（W3-1）。与 {@link #reclaimIfNoActivePermit} 的区别：
     * 本方法从不读屏障状态、从不记 error —— 调用场景是 {@code acquire} 的屏障门，
     * 门本身的拒绝语义由调用方表达；这里只回答"CAS 是否成立"。
     */
    public enum LeaseSelfHeal {
        /** CAS 成立：PENDING/CLOSED 滞留 + 租约已过/无租约 + 无未过期 ACTIVE permit ⇒ 行已收回 OPEN。 */
        SELF_HEALED,
        /** 租约未过 ⇒ 绝不回收（红线，仅 PENDING 行可能）。 */
        LEASE_NOT_EXPIRED,
        /** 租约已过/无租约但仍有未过期 ACTIVE permit ⇒ 绝不回收（CLOSED 滞留行恒报此值，保守）。 */
        STILL_ACTIVE,
        /** 该行当前不是 PENDING/CLOSED（已被收回或行不存在）—— CAS 无对象。 */
        NOT_PENDING
    }

    /**
     * 跨进程自愈 CAS：屏障租约已过且无未过期活跃 permit 时，把该租户卡死的 PENDING 行收回 OPEN。
     *
     * <p>全部判据在 {@link #SQL_SELF_HEAL_IF_LEASE_EXPIRED} 的 WHERE 里一次性裁决。
     * 影响行数是唯一事实：1 = 已收回；0 = 三种原因之一（租约未过 / 仍活跃 / 已不是 PENDING），
     * 0 行时用 {@link #SQL_BARRIER_STATUS_BY_TENANT} 复核状态、用 {@link #SQL_ACTIVE_PERMITS_OUTSIDE}
     * 复核活跃集来区分，绝不猜测。
     *
     * <p><b>调用方纪律</b>：本方法应在已持有 {@code ai_acl_epoch} 租户行锁的事务里调用
     * （{@code DefaultRevocationGuard.acquire} 正是如此）——自愈与并发 prepare/permit 登记的
     * 竞争由该锁串行化，CAS 判据在锁内是稳定的。缺席该锁的调用是弱化形态，允许存在但语义降级为
     * "尽力而为"（0 行的细分结果可能过时）。
     *
     * @param tenantId 租户（空即 {@code NOT_PENDING}，不猜）
     */
    public LeaseSelfHeal selfHealIfLeaseExpired(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return LeaseSelfHeal.NOT_PENDING;
        }
        Map<String, Object> parameters = Map.of("tenant", tenantId);
        int healed = jdbc.update(SQL_SELF_HEAL_IF_LEASE_EXPIRED, parameters);
        if (healed == 1) {
            log.warn("屏障租约过期自愈：PENDING 收回 OPEN, tenant={}, leaseSeconds={}",
                    tenantId, BARRIER_LEASE_SECONDS);
            return LeaseSelfHeal.SELF_HEALED;
        }
        // 0 行：区分原因（租约未过 / 仍活跃 / 已不是自愈对象）——不猜，逐项复核。
        // CLOSED 滞留行（W3-T0-23 ②）：提交闭合即清租约（无租约判据可失败），CAS 失败的
        // 唯一可能原因是活跃集 ⇒ 即便复核计数为 0（并发释放的瞬态）也保守报 STILL_ACTIVE，
        // 下一次 acquire 的 CAS 自然成立。
        List<String> rows = jdbc.query(SQL_BARRIER_STATUS_BY_TENANT, parameters, (rs, rowNum) -> rs.getString(1));
        if (rows.isEmpty()) {
            return LeaseSelfHeal.NOT_PENDING;
        }
        String status = rows.get(0);
        if (!BARRIER_STATUS_PENDING.equals(status) && !BARRIER_STATUS_CLOSED.equals(status)) {
            return LeaseSelfHeal.NOT_PENDING;
        }
        Long active = jdbc.queryForObject(SQL_ACTIVE_PERMITS_OUTSIDE,
                Map.of("tenant", tenantId,
                        // 本操作自己的 permit 尚未登记；占位值使语义退化为"全量未过期 ACTIVE 计数"。
                        "permit", ""),
                Long.class);
        long remaining = active == null ? -1L : active;
        if (remaining > 0) {
            return LeaseSelfHeal.STILL_ACTIVE;
        }
        return BARRIER_STATUS_PENDING.equals(status)
                ? LeaseSelfHeal.LEASE_NOT_EXPIRED
                : LeaseSelfHeal.STILL_ACTIVE;
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
