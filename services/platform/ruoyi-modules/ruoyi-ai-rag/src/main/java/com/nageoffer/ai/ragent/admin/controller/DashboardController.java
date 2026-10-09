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

import com.nageoffer.ai.ragent.admin.controller.vo.DashboardOverviewVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardPerformance;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardTrendsVO;
import com.nageoffer.ai.ragent.admin.service.DashboardService;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营 Dashboard（F18 op0）。
 *
 * <p><b>信封（RW-23）</b>：经 AI 网关的内层 handler 必须返回整数 code 信封
 * （{@code LocalAiGatewayClient.requireSingleJsonObject} 要求 {@code code} 为整数且与 HTTP
 * 状态一致；AI 旧 {@code Result} 的 code 是字符串 {@code "0"}，经网关必然 503）。
 * 因此从 {@code Result} 改为 {@link ApiEnvelope}；三个 VO 的字段形状不变。</p>
 *
 * <p>RW-23-R1/R2 已分别限定 workflow 查询与 agent SQL/缓存的租户范围。
 * 三个读入口分别复核规范主体与 {@code run.get}，由嵌入配置显式选择统计实现。</p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/ai/v1/dashboard")
@ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/overview")
    public ResponseEntity<ApiEnvelope<DashboardOverviewVO>> overview(@RequestParam(required = false) String window) {
        requireRead();
        return reply(dashboardService.loadOverview(window));
    }

    @GetMapping("/performance")
    public ResponseEntity<ApiEnvelope<DashboardPerformance>> performance(@RequestParam(required = false) String window) {
        requireRead();
        return reply(dashboardService.loadPerformance(window));
    }

    @GetMapping("/trends")
    public ResponseEntity<ApiEnvelope<DashboardTrendsVO>> trends(@RequestParam String metric,
                                                                 @RequestParam(required = false) String window,
                                                                 @RequestParam(required = false) String granularity) {
        requireRead();
        return reply(dashboardService.loadTrends(metric, window, granularity));
    }

    private static void requireRead() {
        if (!PrincipalContext.require().hasScope("run.get")) {
            throw new P04AiException(P04AiErrorCode.FORBIDDEN);
        }
    }

    private static <T> ResponseEntity<ApiEnvelope<T>> reply(T data) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiEnvelope.ok(data));
    }
}
