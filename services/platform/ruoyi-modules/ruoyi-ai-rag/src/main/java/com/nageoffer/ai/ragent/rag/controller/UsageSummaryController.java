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

package com.nageoffer.ai.ragent.rag.controller;

import com.nageoffer.ai.ragent.authorization.AiDeliveryPermitConfiguration;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.runtime.P2RuntimeProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 租户级 AI 用量摘要（只读）。
 *
 * <p>{@code ai_provider_spend} 没有 {@code tenant_id}，且它的 {@code call_id} 是
 * 花费信封自己的 id（{@code spend-…}），不能与 {@code ai_model_call.call_id} 直接 join。
 * 因此本端点只用带租户归属的 {@code ai_model_call} 统计“按模型 / 按天”的调用次数与
 * token；花费信封（部署级）仍由 {@code /runtime-config/spend} 单独返回。</p>
 *
 * <p><b>租户运行配额</b>来自 {@code ai_tenant_budget} / {@code ai_budget_reservation}：
 * 这是准入层真实执行的合成单位额度（{@code RunAdmissionService#lockTenantBudget}），
 * 与部署级人民币花费信封是两套口径。{@code settledUnits} 为已结算消耗，
 * {@code heldUnits} 为在途预占，{@code remainingUnits} 为剩余可用；未配置行的租户
 * 返回默认上限（{@code p2.budget.default-tenant-units}）。</p>
 *
 * <p>授权：租户上下文来自 {@code PrincipalContext}（委托令牌），并要求 {@code run.get}
 * scope；不接受客户端传 tenantId。窗口上限 90 天，防止无界扫描。</p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/ai/v1")
@ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
public class UsageSummaryController {

    private static final int MAX_DAYS = 90;

    private static final AtomicBoolean PERMIT_HOLDER_MISSING = new AtomicBoolean();

    private final JdbcTemplate jdbc;
    private final P2RuntimeProperties properties;

    private RevocationGuard revocations;
    private AiDeliveryPermitConfiguration.DeliveryPermitHolder deliveryPermits;

    @Autowired(required = false)
    public void setRevocations(RevocationGuard revocations) {
        this.revocations = revocations;
    }

    @Autowired(required = false)
    public void setDeliveryPermits(AiDeliveryPermitConfiguration.DeliveryPermitHolder deliveryPermits) {
        this.deliveryPermits = deliveryPermits;
    }

    private <T> ResponseEntity<ApiEnvelope<T>> reply(String action, String ref, T data) {
        if (revocations == null) {
            throw new ServiceException("delivery permit unavailable");
        }
        var operation = revocations.enter(PrincipalContext.require(), action, ref);
        if (deliveryPermits == null) {
            PERMIT_HOLDER_MISSING.compareAndSet(false, true);
        }
        else {
            deliveryPermits.register(operation, operation.permitId(), operation.operationId());
        }
        try {
            return ResponseEntity.ok().header("Cache-Control", "no-store")
                    .header("X-AI-Delivery-Permit", operation.permitId())
                    .header("X-AI-Delivery-Operation", operation.operationId())
                    .body(ApiEnvelope.ok(data));
        }
        catch (RuntimeException e) {
            operation.close();
            throw e;
        }
    }

    @GetMapping("/usage/summary")
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> summary(
            @RequestParam(required = false, defaultValue = "7") int days) {
        var principal = PrincipalContext.require();
        if (!principal.hasScope("run.get")) {
            throw new P04AiException(P04AiErrorCode.FORBIDDEN);
        }
        int windowDays = Math.max(1, Math.min(MAX_DAYS, days));
        String tenantId = principal.tenantId();

        List<Map<String, Object>> byModel = jdbc.queryForList(
                "SELECT mc.provider AS provider, mc.model AS model, "
                        + "count(*) AS calls, coalesce(sum("
                        + "CASE WHEN mc.usage_raw->>'total_tokens' ~ '^[0-9]+$' "
                        + "THEN (mc.usage_raw->>'total_tokens')::int ELSE 0 END), 0) AS tokens "
                        + "FROM ai_model_call mc "
                        + "WHERE mc.tenant_id = ? AND mc.created_at >= now() - (? * interval '1 day') "
                        + "GROUP BY mc.provider, mc.model "
                        + "ORDER BY tokens DESC, calls DESC",
                tenantId, windowDays);

        List<Map<String, Object>> byDay = jdbc.queryForList(
                "SELECT to_char(date_trunc('day', mc.created_at), 'YYYY-MM-DD') AS day, "
                        + "count(*) AS calls, coalesce(sum("
                        + "CASE WHEN mc.usage_raw->>'total_tokens' ~ '^[0-9]+$' "
                        + "THEN (mc.usage_raw->>'total_tokens')::int ELSE 0 END), 0) AS tokens "
                        + "FROM ai_model_call mc "
                        + "WHERE mc.tenant_id = ? AND mc.created_at >= now() - (? * interval '1 day') "
                        + "GROUP BY 1 ORDER BY 1",
                tenantId, windowDays);

        Map<String, Object> totals = jdbc.queryForMap(
                "SELECT count(*) AS calls, coalesce(sum("
                        + "CASE WHEN mc.usage_raw->>'total_tokens' ~ '^[0-9]+$' "
                        + "THEN (mc.usage_raw->>'total_tokens')::int ELSE 0 END), 0) AS tokens "
                        + "FROM ai_model_call mc "
                        + "WHERE mc.tenant_id = ? AND mc.created_at >= now() - (? * interval '1 day')",
                tenantId, windowDays);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowDays", windowDays);
        out.put("tenantId", tenantId);
        out.put("totals", totals);
        out.put("byModel", byModel);
        out.put("byDay", byDay);
        out.put("budget", tenantBudget(tenantId));
        out.put("amountKind", "CALLS_AND_TOKENS");
        return reply("run.get", "usage:summary:" + tenantId, out);
    }

    /**
     * 租户运行配额（合成单位）。未配置 {@code ai_tenant_budget} 行时按准入层同一默认值返回，
     * 并标记 {@code configured=false}，避免页面把默认值说成租户自选额度。
     */
    private Map<String, Object> tenantBudget(String tenantId) {
        List<Long> configured = jdbc.query(
                "SELECT limit_units FROM ai_tenant_budget WHERE tenant_id = ?",
                (rs, rowNum) -> rs.getLong(1), tenantId);
        boolean hasRow = !configured.isEmpty();
        long limit = hasRow ? configured.get(0) : properties.getBudget().getDefaultTenantUnits();

        Long settled = jdbc.queryForObject(
                "SELECT coalesce(sum(units), 0) FROM ai_budget_reservation "
                        + "WHERE tenant_id = ? AND state = 'SETTLED'",
                Long.class, tenantId);
        Long held = jdbc.queryForObject(
                "SELECT coalesce(sum(units), 0) FROM ai_budget_reservation "
                        + "WHERE tenant_id = ? AND state = 'RESERVED'",
                Long.class, tenantId);
        Integer activeHolds = jdbc.queryForObject(
                "SELECT count(*) FROM ai_budget_reservation "
                        + "WHERE tenant_id = ? AND state = 'RESERVED'",
                Integer.class, tenantId);

        long settledUnits = settled == null ? 0 : settled;
        long heldUnits = held == null ? 0 : held;
        long remaining = Math.max(0, limit - settledUnits - heldUnits);
        double utilization = limit > 0
                ? Math.min(100.0, (settledUnits + heldUnits) * 100.0 / limit)
                : 0.0;

        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("unit", "合成单位");
        budget.put("configured", hasRow);
        budget.put("limitUnits", limit);
        budget.put("settledUnits", settledUnits);
        budget.put("heldUnits", heldUnits);
        budget.put("remainingUnits", remaining);
        budget.put("activeHolds", activeHolds == null ? 0 : activeHolds);
        budget.put("utilizationPct", Math.round(utilization * 10.0) / 10.0);
        return budget;
    }
}
