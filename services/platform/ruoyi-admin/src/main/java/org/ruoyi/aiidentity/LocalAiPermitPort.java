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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ai.api.AiExecutionFacts;
import org.ruoyi.ai.api.action.AiCanonicalAction;
import org.ruoyi.ai.api.authz.AiPermitPort;
import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link AiPermitPort} 的本地实现（E3/C2）：platform 层执行许可在进程内授予，
 * 取代原 {@code POST /internal/platform/v1/authorization/permits/acquire|release}
 * 的 HTTP 调用。判定链与原平台端点（{@code RuoYiPlatformIdentitySource.acquire}）一致：
 * <ol>
 *   <li>动作可映射（{@link AiCanonicalAction}，未知动作拒绝）且事实 scopes 含对应权限；</li>
 *   <li>同事务行锁读 {@code sys_ai_policy_revision}：无版本行拒绝、版本落后拒绝；</li>
 *   <li>{@code sys_ai_tenant_barrier} 出现任何非 OPEN 行拒绝（无行 = 未设防，允许）；</li>
 *   <li>成员事实仍然有效（{@link PlatformIdentitySource#membership}）；</li>
 *   <li>写 ACTIVE permit（5 分钟租约，跨节点共享活跃集）。</li>
 * </ol>
 *
 * <p>与原 HTTP 层的两处契约差异（记录于 E3 spec，运行时模块搬迁接线时复核）：
 * <ul>
 *   <li>{@code Permit.fence} 恒为 0——运行时 fence 由 AI 运行时层
 *       （{@code ai_acl_epoch} 的 attempt/fence）产生，platform 层许可不携带；
 *       调用方不得用本端口的 fence 做状态库围栏；</li>
 *   <li>{@code resource_refs_hash} 记录空引用哈希——资源级绑定保留在运行时层
 *       {@code ai_execution_permit}，platform 层只记动作级许可。</li>
 * </ul>
 *
 * <p>许可失效只说明"与持有者失联"，不代表执行成功；UNKNOWN 一律按拒绝处理。
 *
 * @author AI-Identity
 */
@Slf4j
@RequiredArgsConstructor
@Component
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class LocalAiPermitPort implements AiPermitPort {

    /** 平台层 permit 租约，与原平台端点一致（5 分钟）。 */
    static final Duration LEASE = Duration.ofMinutes(5);

    /** 空资源引用哈希：platform 层许可不绑定资源（见类注释）。 */
    private static final String NO_RESOURCE_REFS_HASH =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final PlatformIdentitySource identitySource;
    private final JdbcTemplate permitJdbc;
    private final TransactionTemplate permitTransactions;

    @Override
    public Optional<Permit> acquire(AiExecutionFacts facts, String reason) {
        String permission = AiCanonicalAction.permissionOf(reason).orElse(null);
        if (permission == null) {
            // 未知动作拒绝，不默认允许
            return Optional.empty();
        }
        if (!facts.hasScope(permission)) {
            return Optional.empty();
        }
        return Optional.ofNullable(permitTransactions.execute(status -> acquireLocked(facts, reason, permission)));
    }

    private Permit acquireLocked(AiExecutionFacts facts, String action, String permission) {
        Integer version = permitJdbc.query(
                "SELECT version FROM sys_ai_policy_revision WHERE tenant_id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getInt(1), facts.tenantId()).stream().findFirst().orElse(null);
        if (version == null || version != facts.policyVersion()) {
            // 无版本行或版本落后：拒绝，不默认 1
            return null;
        }
        var barriers = permitJdbc.query("SELECT status FROM sys_ai_tenant_barrier WHERE tenant_id = ?",
                (rs, rowNum) -> rs.getString(1), facts.tenantId());
        if (barriers.stream().anyMatch(state -> !"OPEN".equals(state))) {
            // PENDING/CLOSED/UNKNOWN 一律拒绝
            return null;
        }
        PlatformIdentitySource.PlatformIdentity identity = identitySource.membership(
                facts.tenantId(), facts.userId(), facts.membershipId());
        if (identity == null || !identity.enabled()) {
            return null;
        }
        String permitId = UUID.randomUUID().toString();
        int inserted = permitJdbc.update("INSERT INTO sys_ai_execution_permit (permit_id,tenant_id,member_id,action,"
                        + "policy_version,resource_refs_hash,operation_id,status,expires_at) VALUES (?,?,?,?,?,?,?,'ACTIVE',now()+interval '5 minutes')",
                permitId, facts.tenantId(), facts.membershipId(), action, version,
                NO_RESOURCE_REFS_HASH, UUID.randomUUID().toString());
        if (inserted != 1) {
            log.error("platform permit insert unconfirmed, tenant={}, action={}", facts.tenantId(), action);
            return null;
        }
        return new Permit(permitId, facts.tenantId(), facts.membershipId(), 0, LEASE);
    }

    @Override
    public void release(String permitId) {
        if (permitId == null || permitId.isBlank()) {
            return;
        }
        // 释放幂等：未知/已过期的许可不报错
        permitJdbc.update("UPDATE sys_ai_execution_permit SET status = 'RELEASED', released_at = now()"
                + " WHERE permit_id = ? AND status = 'ACTIVE'", permitId);
    }

    @Override
    public BarrierState barrierState(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return BarrierState.UNKNOWN;
        }
        var rows = permitJdbc.query("SELECT status FROM sys_ai_tenant_barrier WHERE tenant_id = ?",
                (rs, rowNum) -> rs.getString(1), tenantId);
        if (rows.isEmpty()) {
            // 无行 = 从未设防
            return BarrierState.OPEN;
        }
        try {
            return BarrierState.valueOf(rows.get(0));
        } catch (IllegalArgumentException corrupted) {
            return BarrierState.UNKNOWN;
        }
    }

    @Override
    public boolean stillAuthorized(AiExecutionFacts facts) {
        try {
            Integer version = permitJdbc.query("SELECT version FROM sys_ai_policy_revision WHERE tenant_id = ?",
                            (rs, rowNum) -> rs.getInt(1), facts.tenantId())
                    .stream().findFirst().orElse(null);
            if (version == null || version != facts.policyVersion()) {
                return false;
            }
            var barriers = permitJdbc.query("SELECT status FROM sys_ai_tenant_barrier WHERE tenant_id = ?",
                    (rs, rowNum) -> rs.getString(1), facts.tenantId());
            if (barriers.stream().anyMatch(state -> !"OPEN".equals(state))) {
                return false;
            }
            PlatformIdentitySource.PlatformIdentity identity = identitySource.membership(
                    facts.tenantId(), facts.userId(), facts.membershipId());
            return identity != null && identity.enabled();
        } catch (RuntimeException unavailable) {
            // 状态不可得按拒绝处理，不降级放行
            log.warn("stillAuthorized check unavailable, tenant={}, treating as revoked", facts.tenantId());
            return false;
        }
    }
}
