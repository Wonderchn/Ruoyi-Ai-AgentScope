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

package com.nageoffer.ai.ragent.audit.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.audit.controller.request.BizChangeLogPageRequest;
import com.nageoffer.ai.ragent.audit.controller.vo.BizChangeLogVO;
import com.nageoffer.ai.ragent.audit.service.BizChangeLogService;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogReadScope;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 业务变更日志查询（F18 op2）。
 *
 * <p><b>信封（RW-23）</b>：经 AI 网关的内层 handler 必须返回整数 code 信封
 * （{@code LocalAiGatewayClient.requireSingleJsonObject} 要求 {@code code} 为整数且与 HTTP
 * 状态一致；AI 旧 {@code Result} 的 code 是字符串 {@code "0"}，经网关必然 503）。
 * 因此从 {@code Result} 改为 {@link ApiEnvelope}，载荷不变。</p>
 *
 * <p><b>限域（RW-23）</b>：运维/管理面读路径，显式
 * {@link BizChangeLogReadScope#tenantWideCurrent()}（tenant 恒等过滤，跨租户不可见）；
 * 用户面用 {@link BizChangeLogReadScope#memberOnlyCurrent()}。缺主体即拒绝。</p>
 */
@RestController
@RequestMapping("/internal/ai/v1")
@RequiredArgsConstructor
public class BizChangeLogController {

    private final BizChangeLogService bizChangeLogService;

    @GetMapping("/biz-change-logs")
    public ResponseEntity<ApiEnvelope<IPage<BizChangeLogVO>>> page(BizChangeLogPageRequest requestParam) {
        return reply(bizChangeLogService.page(requestParam, BizChangeLogReadScope.tenantWideCurrent()));
    }

    /**
     * 详情；跨租户 id 与不存在同外显（抛同一句"变更审计日志不存在"）
     */
    @GetMapping("/biz-change-logs/{id}")
    public ResponseEntity<ApiEnvelope<BizChangeLogVO>> get(@PathVariable String id) {
        return reply(bizChangeLogService.get(id, BizChangeLogReadScope.tenantWideCurrent()));
    }

    private static <T> ResponseEntity<ApiEnvelope<T>> reply(T data) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiEnvelope.ok(data));
    }
}
