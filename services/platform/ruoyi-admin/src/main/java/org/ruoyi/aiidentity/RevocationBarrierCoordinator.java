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

import lombok.extern.slf4j.Slf4j;
import org.ruoyi.system.aiidentity.AiPolicyRevisionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 政策变更的 drain 协调器（U10/P1.4，platform 侧）。
 *
 * <p>位置说明：放在 admin（可同时依赖 system 的 AiPolicyRevisionService 与
 * integration 的配置/HTTP 栈），integration 模块保持不反向依赖 system
 * （与 U04 的 provider 放 admin 同一规则）。
 *
 * <p>协议（05 §4.3）：
 * <ol>
 *   <li><b>prepare</b>：先在独立短事务把本租户屏障置 PENDING（拒绝新 permit），
 *       再向各 AI 节点（经 {@link AiBarrierPort}）请求 CLOSE；等待发生在政策业务
 *       事务之外，不持租户行锁等另一个需要该锁 release 的操作；</li>
 *   <li><b>drain</b>：等待活跃 permit 归零（本地 {@code sys_ai_execution_permit}
 *       ACTIVE 计数 + AI 节点回报的活跃数，两者都以共享库事实为准）；</li>
 *   <li><b>commit</b>：drain 闭合后在同一事务内 bump policyVersion 并置 CLOSED；</li>
 *   <li><b>不成功就不宣告</b>：超时/节点失联时保持 PENDING（对平台侧等价于
 *       UNKNOWN：新 permit 已被拒绝），返回失败；<b>绝不凭租约过期报告撤权成功</b>。</li>
 * </ol>
 *
 * <p><b>WP-031 补上"可恢复性"（V20）。</b>原实现的第 4 条是 fail-closed，但**不可恢复**：
 * 一次 AI 节点不可达就留下 PENDING，此后该租户所有受护写被
 * {@code prepared != 1}（"another policy barrier is pending"）永久拒绝，
 * 且没有任何受控路径能退出。T4 在真库上留下过 {@code 000000 | PENDING | 25db7f66…}。
 * 现在补两条**受控**退出路径：
 * <ul>
 *   <li>{@link #retryDrain}（主路径，对应"租约/超时"语义）：**要求**屏障处于 PENDING
 *       且租约已过期。超时**只解锁重试**，不静默放行 —— 保护不会沿时间轴自己消失。</li>
 *   <li>{@link #abandon}（逃生门）：由运维显式执行，**必须**带 operator 与 reason，
 *       并把处置写入只追加审计。它把 PENDING/UNKNOWN 置为 <b>{@code ABANDONED}</b>。</li>
 * </ul>
 *
 * <p><b>为什么 {@code ABANDONED} 而不是 {@code CLOSED}（本包最要紧的一处语义）。</b>
 * {@code CLOSED} 的含义是"drain 已闭合且 policyVersion 已在同一事务 bump"——
 * 即**撤权成功**。若让逃生门直接置 CLOSED，一次**从未闭合**的撤权就会在账上变成闭合，
 * 之后任何"以屏障状态为准"的判断都会把它当成真事实（而这正是 05 §4.3 信任它的方式）。
 * 所以 {@code ABANDONED} 表达的是：**承认这次撤权未完成，并且把它作为未完成的证据留下来**；
 * 它不再阻塞新 permit（否则等于没有恢复路径），但它**不被任何成功判据认可**。
 * V20 甚至把这条写进了数据库约束：审计表的 {@code to_status <> 'CLOSED'}。
 *
 * <p>默认不装配（{@code ai.integration.enabled=true}）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class RevocationBarrierCoordinator implements org.ruoyi.system.aiidentity.AiPolicyMutationGuard.BarrierPort {

    /** 单次 drain 的等待上限（P1 不承诺 5s SLA，只承诺"不虚假成功"）。 */
    static final long DRAIN_TIMEOUT_MILLIS = 30_000;
    static final long POLL_INTERVAL_MILLIS = 250;

    /**
     * PENDING 的租约时长。
     *
     * <p>语义边界（与 V4 冻结注释一致）：租约到期**不代表**撤权成功，也不自动改状态；
     * 它只让 {@link #retryDrain} 与 {@link #abandon} 这两个受控动作变得合格。
     * 取 5 分钟：小于它的 PENDING 应当被人当作"正在进行"，大于它的才值得介入。
     */
    static final long LEASE_SECONDS = 300;

    private final JdbcTemplate jdbc;
    private final AiPolicyRevisionService policyRevisionService;
    private final AiBarrierPort aiBarrierPort;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    public RevocationBarrierCoordinator(JdbcTemplate jdbc,
                                        AiPolicyRevisionService policyRevisionService,
                                        AiBarrierPort aiBarrierPort,
                                        org.springframework.transaction.support.TransactionTemplate transactionTemplate) {
        this.jdbc = jdbc;
        this.policyRevisionService = policyRevisionService;
        this.aiBarrierPort = aiBarrierPort;
        this.transactionTemplate = transactionTemplate.getTransactionManager() == null ? transactionTemplate
                : new org.springframework.transaction.support.TransactionTemplate(transactionTemplate.getTransactionManager());
        this.transactionTemplate.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 政策变更的 drain 结果。
     *
     * @param closed       true=drain 闭合且 pv 已在同一事务 bump；false=未完成，屏障保持 PENDING
     * @param newVersion   closed=true 时的新 pv
     * @param remaining    未排空的活跃 permit 数（附带节点的最小值）
     */
    public record DrainResult(boolean closed, int newVersion, long remaining, String detail) {
    }

    /** 屏障的当前事实（恢复路径的判定依据）。 */
    record BarrierState(String status, java.sql.Timestamp leaseExpiresAt, int attemptCount) {
        boolean leaseExpired() {
            return leaseExpiresAt != null
                    && leaseExpiresAt.toInstant().isBefore(java.time.Instant.now());
        }
    }

    @Override
    public void prepareAndDrain(String tenantId, String barrierId) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("prepare/drain must suspend the business transaction");
        }
        transactionTemplate.executeWithoutResult(status -> {
            lockRevision(tenantId);
            int prepared=jdbc.update("INSERT INTO sys_ai_tenant_barrier(tenant_id,status,barrier_id,reason,updated_at,lease_expires_at,attempt_count)"
                    + " VALUES(?,'PENDING',?,'policy mutation',now(),now() + (? * interval '1 second'),1) ON CONFLICT(tenant_id) DO UPDATE"
                    + " SET status='PENDING',barrier_id=EXCLUDED.barrier_id,updated_at=now(),"
                    + " lease_expires_at=EXCLUDED.lease_expires_at,attempt_count=sys_ai_tenant_barrier.attempt_count+1"
                    + " WHERE sys_ai_tenant_barrier.status IN ('OPEN','ABANDONED') OR sys_ai_tenant_barrier.barrier_id=EXCLUDED.barrier_id",
                    tenantId,barrierId,LEASE_SECONDS);
            if(prepared!=1){throw new IllegalStateException("another policy barrier is pending");}
        });
        Optional<Long> closed=aiBarrierPort.close(tenantId,barrierId,"policy mutation");
        if(closed.isEmpty() || closed.get()<0){throw new IllegalStateException("AI close unconfirmed; policy remains PENDING");}
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(DRAIN_TIMEOUT_MILLIS);
        while(true){
            Optional<Long> active=aiBarrierPort.activePermitCount(tenantId);
            if(active.isEmpty() || active.get()<0){throw new IllegalStateException("AI active set unknown; policy remains PENDING");}
            if(Math.max(countActivePermits(tenantId),active.get())==0){return;}
            if(System.nanoTime()>=deadline){throw new IllegalStateException("policy drain timeout; PENDING retained");}
            try{Thread.sleep(POLL_INTERVAL_MILLIS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("policy drain interrupted",e);}
        }
    }

    @Override
    public void reopen(String tenantId, String barrierId) {
        // This callback runs after the facts/version transaction has committed.
        aiBarrierPort.open(tenantId,barrierId);
        transactionTemplate.executeWithoutResult(status -> {
            lockRevision(tenantId);
            int changed=jdbc.update("UPDATE sys_ai_tenant_barrier SET status='OPEN',updated_at=now()"
                    + " WHERE tenant_id=? AND barrier_id=? AND status='CLOSED'",tenantId,barrierId);
            if(changed!=1){throw new IllegalStateException("policy OPEN acknowledgement lost");}
        });
    }

    /**
     * 执行"禁止新授权 → 等待退出 → 提交新版本"的完整序列。
     *
     * @param tenantId 受影响租户
     * @param barrierId 本次屏障标识（幂等/审计）
     * @param reason   原因（脱敏）
     */
    public DrainResult drainAndBump(String tenantId, String barrierId, String reason) {
        requireTenantAndBarrier(tenantId, barrierId);
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("drain must run before the business transaction");
        }
        // 1) prepare：本地屏障 PENDING（独立短事务提交，先于等待）
        transactionTemplate.execute(status -> {
            lockRevision(tenantId);
            int prepared = jdbc.update("INSERT INTO sys_ai_tenant_barrier (tenant_id, status, barrier_id, reason, updated_at,"
                + " lease_expires_at, attempt_count)"
                + " VALUES (?, 'PENDING', ?, ?, now(), now() + (? * interval '1 second'), 1)"
                + " ON CONFLICT (tenant_id) DO UPDATE SET status = 'PENDING',"
                + " barrier_id = EXCLUDED.barrier_id, reason = EXCLUDED.reason, updated_at = now(),"
                + " lease_expires_at = EXCLUDED.lease_expires_at,"
                + " attempt_count = sys_ai_tenant_barrier.attempt_count + 1"
                // WP-031：ABANDONED 是**合法的前态**（否则"承认未完成"之后就再也无法发起新的撤权，
                // 等于把不可恢复从 PENDING 挪到了 ABANDONED）。CLOSED/UNKNOWN/PENDING 仍需
                // 同一 barrier_id 才能续跑，保护不被削弱。
                + " WHERE sys_ai_tenant_barrier.status IN ('OPEN', 'ABANDONED')"
                + " OR sys_ai_tenant_barrier.barrier_id = EXCLUDED.barrier_id",
                tenantId, barrierId, reason, LEASE_SECONDS);
            if (prepared != 1) { throw new IllegalStateException("another barrier is pending"); }
            return null;
        });
        return drainAndCommit(tenantId, barrierId, reason);
    }

    /**
     * 主恢复路径（② 租约/超时语义）：租约过期后**重试** drain。
     *
     * <p><b>超时只解锁重试，不放行。</b>本方法在任何时刻都不会把屏障直接置为 OPEN/CLOSED 之外的
     * "看起来完成"的中间态：只有 drain 真正闭合、且 pv 在同一事务 bump 之后才会 CLOSED。
     * 换句话说，"等得够久"本身不构成成功。
     *
     * <p>operator/reason 必填：重试也是**处置动作**，必须能回答"谁在什么时候为什么又试了一次"。
     *
     * @throws IllegalStateException 屏障不是 PENDING、或租约尚未过期（此时应当继续等待或找运维）
     */
    public DrainResult retryDrain(String tenantId, String barrierId, String operatorId, String reason) {
        requireTenantAndBarrier(tenantId, barrierId);
        if (operatorId == null || operatorId.isBlank()) {
            throw new IllegalArgumentException("retry requires an operator (audit)");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("retry requires a reason (audit)");
        }
        BarrierState state = requireState(tenantId);
        if (!"PENDING".equals(state.status())) {
            throw new IllegalStateException("retry requires PENDING; current=" + state.status());
        }
        if (!state.leaseExpired()) {
            throw new IllegalStateException("retry requires an expired lease; the drain may still be progressing");
        }
        audit(tenantId, barrierId, "RETRY_DRAIN", operatorId, reason,
                state.status(), "PENDING",
                java.util.Map.of("leaseExpired", true, "attemptCount", state.attemptCount()));
        return drainAndCommit(tenantId, barrierId, reason);
    }

    /**
     * 逃生门（① 审计化 admin reopen）：把 PENDING/UNKNOWN 置为 <b>{@code ABANDONED}</b>。
     *
     * <p><b>三项强制。</b>① operator 与 reason 都必须非空（"无审计上下文的清空必须失败"）；
     * ② 只允许从 PENDING/UNKNOWN 进入（CLOSED 不能被放弃 —— 那是"把成功改写成未完成"，
     * 与伪造成功同样有害）；③ 审计与状态变更**同一事务**，要么都留痕要么都不生效。
     *
     * <p><b>刻意不以"租约已过期"为前置。</b>它是运维逃生门：当 AI 节点长期不可达、
     * 租约还会被反复续期时，要求先等过期就等于把不可恢复换个位置。
     * 是否早于租约介入由 {@code detail.leaseExpired} 记录在审计里，供事后审计判断。
     */
    public void abandon(String tenantId, String barrierId, String operatorId, String reason) {
        requireTenantAndBarrier(tenantId, barrierId);
        if (operatorId == null || operatorId.isBlank()) {
            throw new IllegalArgumentException("abandon requires an operator (audit)");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("abandon requires a reason (audit)");
        }
        transactionTemplate.executeWithoutResult(status -> {
            lockRevision(tenantId);
            BarrierState state = requireState(tenantId);
            if (!"PENDING".equals(state.status()) && !"UNKNOWN".equals(state.status())) {
                throw new IllegalStateException("only PENDING/UNKNOWN can be abandoned; current=" + state.status());
            }
            int changed = jdbc.update("UPDATE sys_ai_tenant_barrier SET status='ABANDONED', abandoned_at=now(),"
                    + " abandoned_by=?, abandon_reason=?, updated_at=now()"
                    + " WHERE tenant_id=? AND status IN ('PENDING','UNKNOWN')",
                    operatorId, reason, tenantId);
            if (changed != 1) {
                throw new IllegalStateException("barrier abandon lost a concurrent update");
            }
            audit(tenantId, barrierId, "ABANDON", operatorId, reason,
                    state.status(), "ABANDONED",
                    java.util.Map.of("leaseExpired", state.leaseExpired(),
                            "attemptCount", state.attemptCount()));
        });
        log.warn("屏障被审计化放弃（**非撤权成功**）, tenantId={}, barrierId={}, operator={}",
                tenantId, barrierId, operatorId);
    }

    /**
     * drain 的第 2~4 步（通知节点 → 等待归零 → 同事务 bump+CLOSED → 通知 OPEN）。
     *
     * <p>抽出来是为了让 {@link #drainAndBump}（首次）与 {@link #retryDrain}（租约过期后重试）
     * **共用同一条成功判据**：任何一条路径都不可能用"更宽松"的条件宣告 CLOSED。
     */
    private DrainResult drainAndCommit(String tenantId, String barrierId, String reason) {
        // 2) 通知 AI 节点进入 PENDING（拒绝新 permit）；节点回报当前活跃数
        Optional<Long> nodeActive = aiBarrierPort.close(tenantId, barrierId, reason);
        if (nodeActive.isEmpty() || nodeActive.get() < 0) {
            return new DrainResult(false, 0, -1, "node unreachable; barrier stays PENDING");
        }

        // 3) drain：以共享库事实等待归零；节点回报值作为下界（取最大值更保守）
        long deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MILLIS;
        long remaining;
        while (true) {
            long localActive = countActivePermits(tenantId);
            Optional<Long> observed = aiBarrierPort.activePermitCount(tenantId);
            if (observed.isEmpty() || observed.get() < 0) {
                return new DrainResult(false, 0, -1, "node status unknown; barrier stays PENDING");
            }
            long reported = observed.get();
            remaining = Math.max(localActive, reported);
            if (remaining == 0) {
                break;
            }
            if (System.currentTimeMillis() >= deadline) {
                log.error("drain 超时：保持屏障 PENDING，不宣告撤权成功, tenantId={}, remaining={}",
                        tenantId, remaining);
                return new DrainResult(false, 0, remaining,
                        "drain timeout; barrier stays PENDING; no success claimed");
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new DrainResult(false, 0, remaining, "interrupted; barrier stays PENDING");
            }
        }

        // 4) commit：bump pv 与置 CLOSED 必须在**同一事务**（事实与版本同时提交）；
        //    等待已经在事务之外完成，这里只做短提交。
        Integer committedVersion = transactionTemplate.execute(status -> {
            lockRevision(tenantId);
            if (countActivePermits(tenantId) != 0) { throw new IllegalStateException("permits remain active"); }
            policyRevisionService.bumpAll(java.util.List.of(tenantId));
            int version = policyRevisionService.currentVersion(tenantId)
                    .orElseThrow(() -> new IllegalStateException("bump 后 pv 行必须存在"));
            jdbc.update("UPDATE sys_ai_tenant_barrier SET status = 'CLOSED', target_policy_version = ?,"
                    + " updated_at = now() WHERE tenant_id = ? AND barrier_id = ?", version, tenantId, barrierId);
            return version;
        });
        try { aiBarrierPort.open(tenantId, barrierId); }
        catch (RuntimeException e) {
            return new DrainResult(false, committedVersion, -1, "open not acknowledged; platform stays CLOSED");
        }
        transactionTemplate.execute(status -> {
            lockRevision(tenantId);
            jdbc.update("UPDATE sys_ai_tenant_barrier SET status = 'OPEN', updated_at = now()"
                    + " WHERE tenant_id = ? AND barrier_id = ? AND status = 'CLOSED'", tenantId, barrierId);
            return null;
        });
        log.info("撤权 drain 完成, tenantId={}, newPv={}", tenantId, committedVersion);
        return new DrainResult(true, committedVersion, 0, "drained and bumped");
    }

    // ---------------------------------------------------------- 恢复路径的共享护栏

    private void requireTenantAndBarrier(String tenantId, String barrierId) {
        if (tenantId == null || tenantId.isBlank() || barrierId == null || barrierId.isBlank()) {
            throw new IllegalArgumentException("tenantId/barrierId is required");
        }
    }

    /** 读屏障事实；读不到即异常（不得用"读不到就当没有屏障"继续）。 */
    private BarrierState requireState(String tenantId) {
        java.util.List<BarrierState> rows = jdbc.query(
                "SELECT status, lease_expires_at, attempt_count FROM sys_ai_tenant_barrier WHERE tenant_id = ?",
                (rs, rowNum) -> new BarrierState(rs.getString("status"),
                        rs.getTimestamp("lease_expires_at"), rs.getInt("attempt_count")),
                tenantId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("barrier row is missing; refusing to guess a recovery");
        }
        return rows.get(0);
    }

    /** 只追加审计：与状态变更同事务执行（调用方负责事务）。 */
    private void audit(String tenantId, String barrierId, String action, String operatorId, String reason,
                       String fromStatus, String toStatus, java.util.Map<String, Object> detail) {
        // detail 以 JSON 文本 + `?::jsonb` 写入：不引入额外的 JSON 包装类型，也避免把驱动
        // 专有的 PGobject 泄漏到调用方。detail 只承载诊断上下文（租约到期/尝试次数），不含密钥。
        jdbc.update("INSERT INTO sys_ai_barrier_admin_audit (tenant_id, audit_id, barrier_id, action,"
                        + " operator_id, reason, from_status, to_status, detail)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                tenantId, java.util.UUID.randomUUID().toString(), barrierId, action,
                operatorId, reason, fromStatus, toStatus, detailJson(detail));
    }

    /** 最小 JSON 序列化：只支持本方法实际用到的标量类型（布尔/数字/字符串），避免引入依赖。 */
    private static String detailJson(java.util.Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (java.util.Map.Entry<String, Object> entry : detail.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append('"').append(entry.getKey().replace("\"", "\\\"")).append("\":");
            Object value = entry.getValue();
            if (value instanceof Number || value instanceof Boolean) {
                out.append(value);
            } else {
                out.append('"').append(String.valueOf(value).replace("\"", "\\\"")).append('"');
            }
        }
        return out.append('}').toString();
    }

    private long countActivePermits(String tenantId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM sys_ai_execution_permit"
                + " WHERE tenant_id = ? AND status = 'ACTIVE'", Long.class, tenantId);
        if (count == null || count < 0) { throw new IllegalStateException("permit count unknown"); }
        return count;
    }

    /**
     * 取得租户政策版本行的**行锁**，并在缺失时**原子初始化**该行。
     *
     * <p>WP-039（T4）发现 D 修复：原实现用
     * {@code SELECT version ... FOR UPDATE} + {@code queryForObject}（要求恰好 1 行），
     * 在**全新库/新租户**上必然抛 {@code EmptyResultDataAccessException}（实测
     * "expected 1, actual 0"），把**所有经 {@code AiPolicyMutationGuard} 的变更写**
     * 打成 HTTP 500（用户状态变更即此形态）。
     *
     * <p>而 `V4__ai_policy_revision.sql:25` 的冻结表注释写明"租户一行；政策写事务内锁定并
     * 同事务递增，**不预置行**" ⇒ 0 行是**设计预期状态**，缺陷在"实现假设行已存在"，不在"缺播种"。
     * 因此这里改为原子 upsert：缺失则建行（version=1），已存在则用**等值更新**取得行锁
     * （{@code DO UPDATE} 即使值不变也会锁行），**不在此处递增版本** —— 递增仍由同一事务内的
     * {@code AiPolicyRevisionService.bump/bumpAll} 完成，保持"锁定并同事务递增"的原语义，
     * 避免每次取锁都虚增 pv（{@code prepareAndDrain}/{@code reopen} 也调用本方法）。
     *
     * <p>fail-closed 保持不变：租户为空或 DB 不可达时异常照旧向上抛（调用方事务回滚、
     * 屏障保持 PENDING/CLOSED），不会退化成"宽容通过"；返回值校验仍是 version &ge; 1。
     */
    private void lockRevision(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("policy revision tenant is required");
        }
        Integer version = jdbc.queryForObject(
                "INSERT INTO sys_ai_policy_revision(tenant_id, version, update_time) VALUES(?, 1, now())"
                        + " ON CONFLICT (tenant_id) DO UPDATE SET update_time = sys_ai_policy_revision.update_time"
                        + " RETURNING version",
                Integer.class, tenantId);
        if (version == null || version < 1) { throw new IllegalStateException("policy revision missing"); }
    }

    /**
     * 到 AI 节点的屏障通道端口（默认实现见 {@code HttpAiBarrierClient}）。
     * 端口化使协调逻辑可在无网络时被单测覆盖；生产装配 HTTP 实现。
     */
    public interface AiBarrierPort {
        /** 请求节点置 PENDING；返回节点回报的活跃 permit 数（不可达=empty）。 */
        Optional<Long> close(String tenantId, String barrierId, String reason);

        /** 只读节点活跃数。 */
        Optional<Long> activePermitCount(String tenantId);

        /** drain 闭合后解除（置 OPEN）。 */
        void open(String tenantId, String barrierId);
    }
}
