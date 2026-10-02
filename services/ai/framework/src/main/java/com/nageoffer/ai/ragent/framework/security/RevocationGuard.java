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

package com.nageoffer.ai.ragent.framework.security;

/**
 * 强撤权屏障的执行侧端口（U10/P1.4）。
 *
 * <p>契约（05 §4.3）：
 * <ul>
 *   <li><b>登记与验证同锁</b>：{@link #acquire} 在同一租户 epoch 锁内完成
 *       "读屏障状态 + 读当前 aclVersion + 写 ACTIVE permit"，因此两个节点的
 *       登记互不覆盖、与撤权动作互斥；</li>
 *   <li><b>跨节点共享活跃集</b>：permit 落在持久表（ai_execution_permit），
 *       不依赖单 JVM 计数——另一节点能看见本节点的活跃段；</li>
 *   <li><b>屏障状态</b>：PENDING/CLOSED/UNKNOWN 一律拒绝新 acquire；
 *       UNKNOWN 只能由可证实安全的处置解除，<b>绝不凭租约过期放行</b>；</li>
 *   <li><b>释放幂等</b>：同 operationId 重复 release 无新业务写。</li>
 * </ul>
 */
public interface RevocationGuard {

    /** 登记先于 I/O；调用方必须在输出停止或事务结束之后关闭。 */
    default Operation enter(com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal principal,
                            String action, String resourceRef) {
        return enterUsing(this, principal, action, resourceRef);
    }

    static Operation enterUsing(RevocationGuard guard,
            com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal principal,
            String action, String resourceRef) {
        String operationId = java.util.UUID.randomUUID().toString();
        String hash;
        try {
            hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(resourceRef.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        PermitGrant grant = guard.acquire(new PermitRequest(principal.tenantId(), principal.membershipId(), action,
                principal.policyVersion(), principal.aclVersion(), hash, operationId, resourceRef));
        return new Operation(guard, grant.permitId(), operationId);
    }

    final class Operation implements AutoCloseable {
        private final RevocationGuard guard;
        private final String permitId;
        private final String operationId;
        private boolean closed;

        public Operation(RevocationGuard guard, String permitId, String operationId) {
            this.guard = guard;
            this.permitId = permitId;
            this.operationId = operationId;
        }

        public String permitId() { return permitId; }
        public String operationId() { return operationId; }

        @Override
        public synchronized void close() {
            if (closed) { return; }
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override public void afterCompletion(int status) {
                                guard.release(permitId, operationId);
                            }
                        });
            } else {
                guard.release(permitId, operationId);
            }
            closed = true;
        }
    }

    /** Delivery acknowledgement is bound to the original tenant/member and opaque operation. */
    default void releaseDelivery(String tenantId, String memberId, String permitId, String operationId) {
        throw new IllegalStateException("delivery release not configured");
    }

    /** 高风险段进入事实。不存 bearer。 */
    record PermitRequest(String tenantId, String memberId, String action,
                         int policyVersion, int aclVersion,
                         String resourceRefsHash, String operationId, String resourceRef) {
        public PermitRequest(String tenantId, String memberId, String action, int policyVersion, int aclVersion,
                             String resourceRefsHash, String operationId) {
            this(tenantId, memberId, action, policyVersion, aclVersion, resourceRefsHash, operationId, resourceRefsHash);
        }
    }

    /** 登记结果：permitId 与登记时刻的 aclVersion（释放在 AI 提交之后）。 */
    record PermitGrant(String permitId, int aclVersion) {
    }

    /** 屏障状态。NO_ROW = 从未设防（新 permit 允许，但仍受 epoch 校验）。 */
    enum BarrierState {
        NO_ROW, OPEN, PENDING, CLOSED, UNKNOWN
    }

    /**
     * 登记一个 ACTIVE permit。
     *
     * @throws com.nageoffer.ai.ragent.framework.exception.ServiceException 屏障非 OPEN/NO_ROW、
     *         epoch 缺失或事实源不可用——调用方按 503 拒绝，不得降级放行
     * @throws com.nageoffer.ai.ragent.framework.exception.ClientException 主体/operationId 形状非法
     */
    PermitGrant acquire(PermitRequest request);

    /** 释放 permit；同 operationId 幂等。 */
    void release(String permitId, String operationId);

    /** 当前屏障状态（读 ai_tenant_barrier；无行 = NO_ROW）。 */
    BarrierState barrierState(String tenantId);

    /** 租户当前活跃 permit 数（跨节点共享事实）。 */
    long activePermitCount(String tenantId);

    /** 写入屏障状态（platform 的 CLOSE/OPEN/UNKNOWN 经 service 身份端点到达本方法）。 */
    void setBarrierState(String tenantId, BarrierState state, String barrierId,
                         Integer targetAclVersion, String reason);
}
