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

import cn.dev33.satoken.annotation.SaCheckRole;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.constant.TenantConstants;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 撤权屏障的 <b>admin 处置面</b>（WP-031 / V20）。
 *
 * <p><b>为什么必须有这个面。</b>屏障原设计是"fail-closed 但不宣告成功"（05 §4.3），
 * 但 T4 在真库上证明它是**fail-closed 且不可恢复**：一次 AI 节点不可达就留下 PENDING，
 * 此后该租户所有受护写被 `another policy barrier is pending` 永久拒绝，没有任何受控路径退出。
 * 一个把自己锁死且无出口的保护机制不是 fail-closed，而是**不可运维**。
 *
 * <p><b>两条出口，刻意只有两条。</b>
 * <ul>
 *   <li>{@code POST /{tenantId}/retry-drain}（主路径）：要求 PENDING 且**租约已过期**，
 *       重新执行 drain。它不放过任何东西 —— 只有 drain 真闭合、pv 同事务 bump 之后才会成功。</li>
 *   <li>{@code POST /{tenantId}/abandon}（逃生门）：把 PENDING/UNKNOWN 置为
 *       <b>{@code ABANDONED}</b>。语义是"**承认这次撤权未完成并留证**"，
 *       **不是**撤权成功。数据库层另有约束禁止把审计动作写成 CLOSED。</li>
 * </ul>
 *
 * <p><b>为什么没有"清空屏障"这个端点。</b>那等于取消撤权保护本身：被撤权的会话可以在
 * 未排空的情况下继续持有 permit。要恢复可用性，正确的动作是"承认未完成"（ABANDONED）
 * 或"再排一次"（retry-drain），而不是假装屏障不存在。
 *
 * <p><b>授权：仅超级管理员</b>（{@code @SaCheckRole("superadmin")}，与
 * {@code SysTenantController}/{@code SysTenantPackageController} 同一形态）。
 * 刻意**不**新建 {@code ai:barrier:admin} 权限行：那会引入一条"播种前永久 403"的
 * 新权限（T4 已实测过同类缺陷的形态），而本端点影响的是**跨租户的可用性**，
 * 用平台最高等级角色门控比新造一条权限更保守。
 *
 * <p><b>审计不可绕过。</b>operator 取自登录态（{@code LoginHelper.getUserId()}），
 * reason 必填；两者缺失一律拒绝。协调器在**同一事务**内写状态与审计，
 * 审计表另有只追加触发器（UPDATE/DELETE 一律拒绝）。
 */
@RestController
@RequestMapping("/system/ai/barrier")
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
@RequiredArgsConstructor
public class AiBarrierAdminController {

    private final RevocationBarrierCoordinator coordinator;

    /**
     * 审计化放弃：PENDING/UNKNOWN → ABANDONED。
     *
     * <p>返回体刻意写明 {@code revoked=false}：让调用方与日志都无法把这次操作误读成"撤权成功"。
     */
    @SaCheckRole(TenantConstants.SUPER_ADMIN_ROLE_KEY)
    @PostMapping("/{tenantId}/abandon")
    public R<Map<String, Object>> abandon(@PathVariable String tenantId,
                                          @RequestBody AbandonRequest request) {
        String operator = requireOperator();
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        coordinator.abandon(tenantId, request.barrierId(), operator, request.reason());
        return R.ok(Map.of(
                "tenantId", tenantId,
                "barrierId", request.barrierId(),
                "status", "ABANDONED",
                // 反向声明：这次处置**不是**撤权成功，避免调用方把 200 当成"已撤权"
                "revoked", false,
                "operator", operator));
    }

    /**
     * 租约过期后的重试 drain（主恢复路径）。
     *
     * <p>只有 {@code closed=true} 才代表撤权真的完成；其余情况屏障仍在 PENDING，
     * 调用方必须按"未完成"处理（响应里带 {@code closed} 与残留数）。
     */
    @SaCheckRole(TenantConstants.SUPER_ADMIN_ROLE_KEY)
    @PostMapping("/{tenantId}/retry-drain")
    public R<Map<String, Object>> retryDrain(@PathVariable String tenantId,
                                             @RequestBody AbandonRequest request) {
        String operator = requireOperator();
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        RevocationBarrierCoordinator.DrainResult result =
                coordinator.retryDrain(tenantId, request.barrierId(), operator, request.reason());
        return R.ok(Map.of(
                "tenantId", tenantId,
                "barrierId", request.barrierId(),
                "closed", result.closed(),
                "newVersion", result.newVersion(),
                "remaining", result.remaining(),
                "detail", result.detail()));
    }

    /**
     * 操作者身份：取不到就拒绝。
     *
     * <p>不用 "system"/"unknown" 之类的占位符兜底 —— 那会让审计变成形式主义，
     * 而"谁动的"正是这个端点存在的全部理由。
     */
    private String requireOperator() {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            throw new IllegalStateException("operator identity unavailable; audited action refused");
        }
        return String.valueOf(userId);
    }

    /** 处置请求体：barrierId + reason 都必填（审计四要素里的两个）。 */
    public record AbandonRequest(String barrierId, String reason) {
    }
}
