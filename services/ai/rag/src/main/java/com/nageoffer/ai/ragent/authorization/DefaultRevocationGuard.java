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

    private final JdbcTemplate jdbc;

    public DefaultRevocationGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
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
            throw new ServiceException("aclVersion 已变化（" + request.aclVersion() + " -> "
                    + currentVersion + "），拒绝登记");
        }

        // 2) 屏障状态：PENDING/CLOSED/UNKNOWN 一律拒绝新 permit。
        BarrierState state = readBarrierState(request.tenantId());
        if (state == BarrierState.PENDING || state == BarrierState.CLOSED || state == BarrierState.UNKNOWN) {
            throw new ServiceException("租户屏障 " + state + "，拒绝新 permit");
        }

        // 3) 登记 ACTIVE permit（跨节点共享）。
        String permitId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ai_execution_permit (permit_id, tenant_id, member_id, action,"
                        + " policy_version, acl_version, resource_refs_hash, operation_id, status,"
                        + " acquired_at, expires_at) VALUES (?,?,?,?,?,?,?,?, 'ACTIVE', now(), ?)",
                permitId, request.tenantId(), request.memberId(), request.action(),
                request.policyVersion(), request.aclVersion(), request.resourceRefsHash(),
                request.operationId(), Timestamp.from(Instant.now().plusSeconds(LEASE_SECONDS)));
        log.info("permit 登记, tenantId={}, operationId={}, aclVersion={}",
                request.tenantId(), request.operationId(), currentVersion);
        return new PermitGrant(permitId, currentVersion);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
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
    }

    @Override
    public BarrierState barrierState(String tenantId) {
        return readBarrierState(tenantId);
    }

    @Override
    public long activePermitCount(String tenantId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM ai_execution_permit"
                + " WHERE tenant_id = ? AND status = 'ACTIVE' AND expires_at > now()", Long.class, tenantId);
        return count == null ? 0L : count;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void setBarrierState(String tenantId, BarrierState state, String barrierId,
                                Integer targetAclVersion, String reason) {
        if (tenantId == null || tenantId.isBlank() || state == null || state == BarrierState.NO_ROW) {
            throw new ClientException("tenantId/state 非法（NO_ROW 不是可写入状态）");
        }
        // upsert：无行插入，有行原位更新；状态迁移不做静默降级（UNKNOWN 只能显式写 OPEN 解除）
        jdbc.update("INSERT INTO ai_tenant_barrier (tenant_id, status, barrier_id, target_acl_version, reason, updated_at)"
                        + " VALUES (?,?,?,?,?, now())"
                        + " ON CONFLICT (tenant_id) DO UPDATE SET status = EXCLUDED.status,"
                        + " barrier_id = EXCLUDED.barrier_id, target_acl_version = EXCLUDED.target_acl_version,"
                        + " reason = EXCLUDED.reason, updated_at = now()",
                tenantId, state.name(), barrierId, targetAclVersion, reason);
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
