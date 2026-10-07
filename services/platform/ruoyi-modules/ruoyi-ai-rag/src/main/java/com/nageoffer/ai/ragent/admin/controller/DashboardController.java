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

package com.nageoffer.ai.ragent.admin.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardOverviewVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardPerformance;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardTrendsVO;
import com.nageoffer.ai.ragent.admin.service.DashboardService;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营 Dashboard（F18 op1）。
 *
 * <p><b>信封（RW-23）</b>：经 AI 网关的内层 handler 必须返回整数 code 信封
 * （{@code LocalAiGatewayClient.requireSingleJsonObject} 要求 {@code code} 为整数且与 HTTP
 * 状态一致；AI 旧 {@code Result} 的 code 是字符串 {@code "0"}，经网关必然 503）。
 * 因此从 {@code Result} 改为 {@link ApiEnvelope}；三个 VO 的字段形状不变。</p>
 *
 * <p><b>授权范围（RW-23，未完成部分见报告）</b>：本控制器已 fail-closed 要求执行主体
 * （{@link PrincipalContext#require()}，缺主体 403/整数码信封）；但
 * {@code DashboardServiceImpl} / {@code AgentDashboardReader} 的<b>统计查询尚未按 tenant 过滤</b>
 * （现状是全局计数），因此在补齐租户限域之前<b>不得装配本控制器</b>——报告 §Dashboard 给了
 * 精确补丁规格与证据。</p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/dashboard")
@SaCheckRole("admin")
@ConditionalOnProperty(prefix = "ragent.engine", name = "type", havingValue = "workflow", matchIfMissing = true)
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/overview")
    public ResponseEntity<ApiEnvelope<DashboardOverviewVO>> overview(@RequestParam(required = false) String window) {
        PrincipalContext.require();
        return reply(dashboardService.loadOverview(window));
    }

    @GetMapping("/performance")
    public ResponseEntity<ApiEnvelope<DashboardPerformance>> performance(@RequestParam(required = false) String window) {
        PrincipalContext.require();
        return reply(dashboardService.loadPerformance(window));
    }

    @GetMapping("/trends")
    public ResponseEntity<ApiEnvelope<DashboardTrendsVO>> trends(@RequestParam String metric,
                                                                 @RequestParam(required = false) String window,
                                                                 @RequestParam(required = false) String granularity) {
        PrincipalContext.require();
        return reply(dashboardService.loadTrends(metric, window, granularity));
    }

    private static <T> ResponseEntity<ApiEnvelope<T>> reply(T data) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiEnvelope.ok(data));
    }
}
