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
    private String platformBaseUrl;
    private String platformCredential;
    private final java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1)
            .connectTimeout(java.time.Duration.ofSeconds(2)).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @org.springframework.beans.factory.annotation.Autowired
    public void configurePlatform(@org.springframework.beans.factory.annotation.Value("${ai.integration.platform-base-url:}") String url,
            @org.springframework.beans.factory.annotation.Value("${ai.integration.platform-service-credential:}") String credential) {
        if (url.isBlank() || credential.isBlank()) {
            throw new IllegalStateException("production permit platform URL/credential required");
        }
        platformBaseUrl = url;
        platformCredential = credential;
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
        BarrierState state = readBarrierState(request.tenantId());
        if (state == BarrierState.PENDING || state == BarrierState.CLOSED || state == BarrierState.UNKNOWN) {
            throw new ServiceException("租户屏障 " + state + "，拒绝新 permit");
        }

        // 3) 登记 ACTIVE permit（跨节点共享）。
        String permitId = UUID.randomUUID().toString();
        if (platformBaseUrl != null) {
            String subject = request.memberId().substring(request.memberId().lastIndexOf(':') + 1);
            var result = platformCall("acquire", java.util.Map.of("tenantId", request.tenantId(),
                    "subject", subject, "membershipId", request.memberId(), "policyVersion", request.policyVersion(),
                    "aclVersion", request.aclVersion(), "action", request.action(), "resourceRef", request.resourceRef(),
                    "resourceRefsHash", request.resourceRefsHash(), "operationId", request.operationId()));
            var data = result.path("data");
            if (!data.path("permitId").isTextual() || data.path("permitId").textValue().isBlank()
                    || !data.path("policyVersion").isIntegralNumber()
                    || data.path("policyVersion").intValue() != request.policyVersion()
                    || !request.operationId().equals(data.path("operationId").textValue())) {
                throw new ServiceException("平台 permit 回执非法");
            }
            permitId = data.path("permitId").textValue();
            String registeredId = permitId;
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override public void afterCompletion(int status) {
                                if (status != STATUS_COMMITTED) {
                                    try {
                                        platformCall("release", java.util.Map.of("tenantId", request.tenantId(),
                                                "membershipId", request.memberId(), "permitId", registeredId,
                                                "operationId", request.operationId()));
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
                        + " acquired_at, expires_at) VALUES (?,?,?,?,?,?,?,?, 'ACTIVE', now(), ?)",
                permitId, request.tenantId(), request.memberId(), request.action(),
                request.policyVersion(), request.aclVersion(), request.resourceRefsHash(),
                request.operationId(), Timestamp.from(Instant.now().plusSeconds(LEASE_SECONDS)));
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
        if (platformBaseUrl != null) {
            var rows = jdbc.query("SELECT tenant_id,member_id FROM ai_execution_permit WHERE permit_id=? AND operation_id=?",
                    (rs, n) -> java.util.Map.of("tenantId", rs.getString(1), "membershipId", rs.getString(2),
                            "permitId", permitId, "operationId", operationId), permitId, operationId);
            if (rows.isEmpty()) { throw new ServiceException("permit 释放事实缺失"); }
            platformCall("release", rows.get(0));
        }
    }

    private com.fasterxml.jackson.databind.JsonNode platformCall(String action, Object body) {
        try {
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(platformBaseUrl
                            + "/internal/platform/v1/authorization/permits/" + action))
                    .timeout(java.time.Duration.ofSeconds(2)).header("Content-Type", "application/json")
                    .header("X-P04-Service-Credential", platformCredential)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            var response = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (action.equals("release") && response.statusCode() == 204 && response.body().isBlank()) {
                return json.createObjectNode();
            }
            var node = json.readTree(response.body());
            if (node == null || !node.isObject() || !node.path("code").isIntegralNumber()
                    || node.path("code").intValue() != response.statusCode()) { throw new ServiceException("平台 permit 响应非法"); }
            if (response.statusCode() == 409) { throw new com.nageoffer.ai.ragent.framework.security.StaleVersionException("policyVersion changed"); }
            if (response.statusCode() != 200) { throw new ServiceException("平台 permit 拒绝"); }
            return node;
        } catch (com.nageoffer.ai.ragent.framework.security.StaleVersionException | ServiceException e) { throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("平台 permit 中断");
        } catch (Exception e) { throw new ServiceException("平台 permit 不可用"); }
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
        int updated = jdbc.update("INSERT INTO ai_tenant_barrier (tenant_id, status, barrier_id, target_acl_version, reason, updated_at)"
                        + " VALUES (?,?,?,?,?, now())"
                        + " ON CONFLICT (tenant_id) DO UPDATE SET status = EXCLUDED.status,"
                        + " barrier_id = EXCLUDED.barrier_id, target_acl_version = EXCLUDED.target_acl_version,"
                        + " reason = EXCLUDED.reason, updated_at = now()",
                tenantId, state.name(), barrierId, targetAclVersion, reason);
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
