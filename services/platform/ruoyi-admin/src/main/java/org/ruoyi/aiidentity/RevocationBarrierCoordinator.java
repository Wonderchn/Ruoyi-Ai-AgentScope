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
 * <p>默认不装配（{@code ai.integration.enabled=true}）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class RevocationBarrierCoordinator {

    /** 单次 drain 的等待上限（P1 不承诺 5s SLA，只承诺"不虚假成功"）。 */
    static final long DRAIN_TIMEOUT_MILLIS = 30_000;
    static final long POLL_INTERVAL_MILLIS = 250;

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

    /**
     * 执行"禁止新授权 → 等待退出 → 提交新版本"的完整序列。
     *
     * @param tenantId 受影响租户
     * @param barrierId 本次屏障标识（幂等/审计）
     * @param reason   原因（脱敏）
     */
    public DrainResult drainAndBump(String tenantId, String barrierId, String reason) {
        if (tenantId == null || tenantId.isBlank() || barrierId == null || barrierId.isBlank()) {
            throw new IllegalArgumentException("tenantId/barrierId is required");
        }
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("drain must run before the business transaction");
        }
        // 1) prepare：本地屏障 PENDING（独立短事务提交，先于等待）
        transactionTemplate.execute(status -> {
            lockRevision(tenantId);
            int prepared = jdbc.update("INSERT INTO sys_ai_tenant_barrier (tenant_id, status, barrier_id, reason, updated_at)"
                + " VALUES (?, 'PENDING', ?, ?, now())"
                + " ON CONFLICT (tenant_id) DO UPDATE SET status = 'PENDING',"
                + " barrier_id = EXCLUDED.barrier_id, reason = EXCLUDED.reason, updated_at = now()"
                + " WHERE sys_ai_tenant_barrier.status = 'OPEN' OR sys_ai_tenant_barrier.barrier_id = EXCLUDED.barrier_id",
                tenantId, barrierId, reason);
            if (prepared != 1) { throw new IllegalStateException("another barrier is pending"); }
            return null;
        });

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

    private long countActivePermits(String tenantId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM sys_ai_execution_permit"
                + " WHERE tenant_id = ? AND status = 'ACTIVE'", Long.class, tenantId);
        if (count == null || count < 0) { throw new IllegalStateException("permit count unknown"); }
        return count;
    }

    private void lockRevision(String tenantId) {
        Integer version = jdbc.queryForObject("SELECT version FROM sys_ai_policy_revision WHERE tenant_id=? FOR UPDATE",
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
