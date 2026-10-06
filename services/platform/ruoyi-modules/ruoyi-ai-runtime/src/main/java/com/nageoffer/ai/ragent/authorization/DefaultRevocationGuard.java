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

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 强撤权屏障的默认实现（U10/P1.4）：持久表 + 租户 epoch 行锁。
 *
 * <p>跨节点一致性来自"事实在共享库、锁在共享库"：
 * <ul>
 *   <li>{@link #acquire} 在单事务内 {@code SELECT ... FOR UPDATE} 锁 ai_acl_epoch
 *       租户行（不存在即拒绝，不默认 1）→ 读 ai_tenant_barrier → 写 ACTIVE permit。
 *       另一节点的撤权（bump epoch / 置 PENDING）同样要拿这把锁，因此
 *       "检查通过后执行"与"撤权生效"不可能交错——这正是"AI 不得只做一次
 *       check 后执行"的落地方式；</li>
 *   <li>{@link #activePermitCount} 读共享表：另一节点的活跃段对撤权方可见；</li>
 *   <li>permit 带租约时间只用于<b>判定失联</b>（UNKNOWN 保持关闭），
 *       绝不用于"过期即视为成功"。</li>
 * </ul>
 *
 * <p>默认不装配（{@code ai.integration.enabled=true}）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class DefaultRevocationGuard implements RevocationGuard {

    /** permit 租约时长：只用于识别失联节点，不用于宣告撤权成功。 */
    static final long LEASE_SECONDS = 300;

    /**
     * 屏障 PENDING 租约时长（秒）：本类写 PENDING 时 stamp
     * {@code lease_expires_at = now() + 本常量}（W3-1 裁定：不引入第三个自定义时长，
     * 与 permit 侧、平台侧 sys 屏障同源）。它同时经 {@link TenantBarrierReconciler#BARRIER_LEASE_SECONDS}
     * 被自愈 CAS 口径引用——"谁写租约、多长"只有一个事实来源。
     */
    static final long BARRIER_LEASE_SECONDS = LEASE_SECONDS;

    private final JdbcTemplate jdbc;
    private com.nageoffer.ai.ragent.framework.security.PlatformPermitPort platformPermits;

    /**
     * W3-1 屏障自愈（L3-T2R-AUTHZ）：{@code required=false} + 缺席时 fail-closed ——
     * acquire 的屏障门在 PENDING 时若 reconciler 缺席，维持"拒绝新 permit"的既有行为，
     * 绝不因组件缺席而放宽（自愈是加路径，不是替换拒绝）。
     */
    private TenantBarrierReconciler barrierReconciler;

    /** 屏障门自愈的来源标识：CAS 成功后 reconcile 行里留痕用。 */
    static final String LEASE_RECONCILER = "lease-reconciler";

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void configureBarrierReconciler(TenantBarrierReconciler barrierReconciler) {
        this.barrierReconciler = barrierReconciler;
    }

    /**
     * platform 层许可镜像端口。内嵌装配提供本地实现（同进程调用 platform 许可提供者，
     * 不经 localhost HTTP）；端口缺席时保持"仅本层 permit"的形态
     * （与迁移前 {@code ai.integration.platform-base-url} 未配置时一致）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void configurePlatformPermits(org.springframework.beans.factory.ObjectProvider<
            com.nageoffer.ai.ragent.framework.security.PlatformPermitPort> permits) {
        this.platformPermits = permits == null ? null : permits.getIfAvailable();
    }

    public DefaultRevocationGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private org.springframework.beans.factory.ObjectProvider<DefaultRevocationGuard> self;

    @org.springframework.beans.factory.annotation.Autowired
    public void configureSelf(org.springframework.beans.factory.ObjectProvider<DefaultRevocationGuard> self) {
        this.self = self;
    }

    @Override
    public Operation enter(com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal principal,
            String action, String resourceRef) {
        // Invoke acquire/release through the Spring proxy: interface-default self calls bypass transactions.
        return RevocationGuard.enterUsing(self == null ? this : self.getObject(), principal, action, resourceRef);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public PermitGrant acquire(PermitRequest request) {
        requireShape(request);

        // 1) 租户行锁（同撤权动作的互斥点）。无 epoch 行 = 从未初始化 → 拒绝，不默认 1。
        Integer currentVersion = jdbc.query(
                "SELECT version FROM ai_acl_epoch WHERE tenant_id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getInt("version"), request.tenantId())
                .stream().findFirst().orElse(null);
        if (currentVersion == null || currentVersion < 1) {
            throw new ServiceException("租户无 ACL epoch，拒绝登记 permit（不默认初始化）");
        }
        if (request.aclVersion() != currentVersion) {
            // 请求方检查过的版本已过期：让它按并发撤权重取
            throw new com.nageoffer.ai.ragent.framework.security.StaleVersionException("aclVersion 已变化（" + request.aclVersion() + " -> "
                    + currentVersion + "），拒绝登记");
        }

        // 2) 屏障状态：PENDING/CLOSED/UNKNOWN 一律拒绝新 permit。
        //    W3-1 自愈唯一入口（L3-T2R-AUTHZ）：PENDING 且 reconciler 在位 ⇒ 先尝试
        //    "租约过期 + 无未过期 ACTIVE permit"的 CAS（reconciler 口径，全判据在 UPDATE 的
        //    WHERE 里）。SELF_HEALED ⇒ 屏障已收回 OPEN，本次 acquire 继续往下走；
        //    其余一切结果（LEASE_NOT_EXPIRED / STILL_ACTIVE / NOT_PENDING）与 reconciler
        //    缺席 ⇒ 维持拒绝。语义精确化：今天"PENDING 一律拒"，改为"PENDING 且不可自愈才拒"
        //    ——旧锚点测试（P1RevocationRaceTest.nonOpenBarrierRefuses）负例语义不消失：
        //    它们的 mock 场景里 CAS 影响 0 行（未配置 reconciler ⇒ 自愈不存在），
        //    拒绝行为保持不变、测试保持绿。
        //    本方法此刻正持有 ai_acl_epoch 租户行锁（上面第 1 步 FOR UPDATE），CAS 的
        //    活跃集判据在该锁内是稳定的；CAS 是 UPDATE 一次性裁决，不需要 Java 侧二次判断。
        BarrierState state = readBarrierState(request.tenantId());
        if (state == BarrierState.PENDING) {
            if (barrierReconciler == null
                    || barrierReconciler.selfHealIfLeaseExpired(request.tenantId())
                            != TenantBarrierReconciler.LeaseSelfHeal.SELF_HEALED
                    || readBarrierState(request.tenantId()) != BarrierState.OPEN) {
                log.warn("租户屏障 PENDING 且不可自愈，拒绝新 permit, tenantId={}, operationId={}",
                        request.tenantId(), request.operationId());
                throw new ServiceException("租户屏障 PENDING，拒绝新 permit");
            }
            log.warn("屏障租约过期自愈放行, tenantId={}, operationId={}",
                    request.tenantId(), request.operationId());
        } else if (state == BarrierState.CLOSED || state == BarrierState.UNKNOWN) {
            throw new ServiceException("租户屏障 " + state + "，拒绝新 permit");
        }

        // 3) 登记 ACTIVE permit（跨节点共享）。
        String permitId = UUID.randomUUID().toString();
        if (platformPermits != null) {
            String subject = request.memberId().substring(request.memberId().lastIndexOf(':') + 1);
            var result = platformPermits.acquire(new com.nageoffer.ai.ragent.framework.security.PlatformPermitPort.AcquireRequest(
                    request.tenantId(), subject, request.memberId(), request.policyVersion(), request.aclVersion(),
                    request.action(), request.resourceRef(), request.resourceRefsHash(), request.operationId()));
            if (result == null || result.permitId() == null || result.permitId().isBlank()
                    || result.policyVersion() != request.policyVersion()
                    || !request.operationId().equals(result.operationId())) {
                throw new ServiceException("平台 permit 回执非法");
            }
            permitId = result.permitId();
            String registeredId = permitId;
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override public void afterCompletion(int status) {
                                if (status != STATUS_COMMITTED) {
                                    try {
                                        platformPermits.release(new com.nageoffer.ai.ragent.framework.security.PlatformPermitPort.ReleaseRequest(
                                                request.tenantId(), request.memberId(), registeredId, request.operationId()));
                                    } catch (RuntimeException e) {
                                        log.error("permit rollback release unconfirmed; keep platform ACTIVE, operationId={}", request.operationId());
                                    }
                                }
                            }
                        });
            }
        }
        int inserted = jdbc.update("INSERT INTO ai_execution_permit (permit_id, tenant_id, member_id, action,"
                        + " policy_version, acl_version, resource_refs_hash, operation_id, status,"
                        + " acquired_at, expires_at) VALUES (?,?,?,?,?,?,?,?, 'ACTIVE', now(), now() + (? * interval '1 second'))",
                permitId, request.tenantId(), request.memberId(), request.action(),
                request.policyVersion(), request.aclVersion(), request.resourceRefsHash(),
                request.operationId(), LEASE_SECONDS);
        if (inserted != 1) { throw new ServiceException("permit 登记未确认"); }
        log.info("permit 登记, tenantId={}, operationId={}, aclVersion={}",
                request.tenantId(), request.operationId(), currentVersion);
        return new PermitGrant(permitId, currentVersion);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void release(String permitId, String operationId) {
        if (permitId == null || permitId.isBlank() || operationId == null || operationId.isBlank()) {
            throw new ClientException("permitId/operationId 均不能为空");
        }
        int updated = jdbc.update("UPDATE ai_execution_permit SET status = 'RELEASED', released_at = now()"
                + " WHERE permit_id = ? AND operation_id = ? AND status = 'ACTIVE'", permitId, operationId);
        if (updated == 0) {
            // 幂等：同体重复 release（已是 RELEASED）不算失败；permit 不存在/operation 不匹配必须暴露
            Integer exists = jdbc.query("SELECT count(*) FROM ai_execution_permit"
                            + " WHERE permit_id = ? AND operation_id = ?",
                    (rs, rowNum) -> rs.getInt(1), permitId, operationId).stream().findFirst().orElse(0);
            if (exists == null || exists == 0) {
                throw new ClientException("permit 不存在或 operationId 不匹配");
            }
        }
        if (platformPermits != null) {
            var rows = jdbc.query("SELECT tenant_id,member_id FROM ai_execution_permit WHERE permit_id=? AND operation_id=?",
                    (rs, n) -> java.util.Map.of("tenantId", rs.getString(1), "membershipId", rs.getString(2),
                            "permitId", permitId, "operationId", operationId), permitId, operationId);
            if (rows.isEmpty()) { throw new ServiceException("permit 释放事实缺失"); }
            var row = rows.get(0);
            platformPermits.release(new com.nageoffer.ai.ragent.framework.security.PlatformPermitPort.ReleaseRequest(
                    row.get("tenantId"), row.get("membershipId"), permitId, operationId));
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void releaseDelivery(String tenantId, String memberId, String permitId, String operationId) {
        Long owned=jdbc.queryForObject("SELECT count(*) FROM ai_execution_permit WHERE tenant_id=? AND member_id=?"
                + " AND permit_id=? AND operation_id=? AND action IN ('document.download','document.list','conversation.export','kb.list','kb.read','document.read','conversation.read','memory.read','run.get','run.events','run.stream','kb.retrieve','run.approve','run.reconcile')",
                Long.class,tenantId,memberId,permitId,operationId);
        if(owned==null || owned!=1){throw new ClientException("delivery identity mismatch");}
        release(permitId,operationId);
    }

    @Override
    public BarrierState barrierState(String tenantId) {
        return readBarrierState(tenantId);
    }

    @Override
    public long activePermitCount(String tenantId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM ai_execution_permit"
                + " WHERE tenant_id = ? AND status = 'ACTIVE'", Long.class, tenantId);
        if (count == null || count < 0) { throw new ServiceException("活跃 permit 数未知"); }
        return count;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void setBarrierState(String tenantId, BarrierState state, String barrierId,
                                Integer targetAclVersion, String reason) {
        if (tenantId == null || tenantId.isBlank() || state == null || state == BarrierState.NO_ROW
                || barrierId == null || barrierId.isBlank()) {
            throw new ClientException("tenantId/state 非法（NO_ROW 不是可写入状态）");
        }
        // 与 acquire 使用同一 epoch 行锁：关闭提交后不可能再登记旧 permit。
        Integer version = jdbc.query("SELECT version FROM ai_acl_epoch WHERE tenant_id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getInt(1), tenantId).stream().findFirst().orElse(null);
        if (version == null) {
            throw new ServiceException("租户无 ACL epoch，拒绝修改屏障");
        }
        if (state == BarrierState.OPEN && activePermitCount(tenantId) != 0) {
            throw new ServiceException("存在未释放 permit，屏障不能解除");
        }
        var existing = jdbc.queryForList("SELECT status,barrier_id FROM ai_tenant_barrier WHERE tenant_id=?", tenantId);
        if (existing.isEmpty() && state == BarrierState.OPEN) { throw new ServiceException("屏障不存在，不能解除"); }
        if (!existing.isEmpty() && !barrierId.equals(existing.get(0).get("barrier_id"))
                && (state == BarrierState.OPEN || !"OPEN".equals(existing.get(0).get("status")))) {
            throw new ServiceException("屏障标识不匹配");
        }
        int updated = jdbc.update("INSERT INTO ai_tenant_barrier (tenant_id, status, barrier_id, target_acl_version, reason, updated_at, lease_expires_at)"
                        + " VALUES (?,?,?,?,?, now(),"
                        + " CASE WHEN ? = 'PENDING' THEN now() + (? * interval '1 second') ELSE NULL END)"
                        + " ON CONFLICT (tenant_id) DO UPDATE SET status = EXCLUDED.status,"
                        + " barrier_id = EXCLUDED.barrier_id, target_acl_version = EXCLUDED.target_acl_version,"
                        + " reason = EXCLUDED.reason, updated_at = now(),"
                        + " lease_expires_at = EXCLUDED.lease_expires_at, reconciled_at = NULL, reconciled_by = NULL",
                tenantId, state.name(), barrierId, targetAclVersion, reason, state.name(), BARRIER_LEASE_SECONDS);
        if (updated != 1) { throw new ServiceException("屏障写入未确认"); }
    }

    private BarrierState readBarrierState(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ClientException("tenantId 不能为空");
        }
        List<String> rows = jdbc.query("SELECT status FROM ai_tenant_barrier WHERE tenant_id = ?",
                (rs, rowNum) -> rs.getString("status"), tenantId);
        if (rows.isEmpty()) {
            return BarrierState.NO_ROW;
        }
        return BarrierState.valueOf(rows.get(0));
    }

    private static void requireShape(PermitRequest request) {
        if (request == null || request.tenantId() == null || request.tenantId().isBlank()
                || request.memberId() == null || request.memberId().isBlank()
                || request.action() == null || request.action().isBlank()
                || request.operationId() == null || request.operationId().isBlank()
                || request.policyVersion() < 1 || request.aclVersion() < 1) {
            throw new ClientException("permit 请求字段缺失或版本 < 1");
        }
    }
}
