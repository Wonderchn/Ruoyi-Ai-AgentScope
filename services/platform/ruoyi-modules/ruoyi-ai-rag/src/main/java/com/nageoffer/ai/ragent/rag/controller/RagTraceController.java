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

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.rag.controller.request.RagTraceRunPageRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.RagTraceDetailVO;
import com.nageoffer.ai.ragent.rag.controller.vo.RagTraceNodeVO;
import com.nageoffer.ai.ragent.rag.controller.vo.RagTraceRunVO;
import com.nageoffer.ai.ragent.rag.service.RagTraceQueryService;
import com.nageoffer.ai.ragent.rag.trace.RagTraceReadScope;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

/**
 * RAG Trace 查询接口（运维/管理面）。
 *
 * <p><b>信封（RW-23）</b>：经 AI 网关的内层 handler 必须返回<b>整数 code</b> 的信封
 * （{@code LocalAiGatewayClient.requireSingleJsonObject} 要求 {@code code} 是整数且与 HTTP
 * 状态一致；AI 旧 {@code Result} 的 code 是字符串 {@code "0"}，经网关必然 503）。
 * 因此本控制器从 {@code Result} 改为 {@link ApiEnvelope}：HTTP 200 + {@code code=200}。
 * 载荷类型（各 VO / {@code IPage}）保持不变。</p>
 *
 * <p><b>限域（RW-23）</b>：这是运维面读路径，显式用
 * {@link RagTraceReadScope#tenantWideCurrent()}（tenant 恒等过滤，跨租户不可见）；
 * 用户面用 {@link RagTraceReadScope#memberOnlyCurrent()}，由调用点决定，服务层不猜。
 * 缺执行主体时构造限域即抛 {@code ClientException}（经 {@code AiInternalExceptionResolver}
 * 映射为 403/整数码信封），不会降级成全量查询。</p>
 */
@RestController
@RequestMapping("/internal/ai/v1")
@RequiredArgsConstructor
public class RagTraceController {

    private final RagTraceQueryService ragTraceQueryService;

    /**
     * 分页查询链路运行记录
     */
    @GetMapping("/rag/traces/runs")
    public ResponseEntity<ApiEnvelope<IPage<RagTraceRunVO>>> pageRuns(RagTraceRunPageRequest request) {
        return reply(ragTraceQueryService.pageRuns(request, RagTraceReadScope.tenantWideCurrent()));
    }

    /**
     * 查询链路详情（包含节点）；跨租户 traceId 与不存在同外显（data=null）
     */
    @GetMapping("/rag/traces/runs/{traceId}")
    public ResponseEntity<ApiEnvelope<RagTraceDetailVO>> detail(@PathVariable String traceId) {
        return reply(ragTraceQueryService.detail(traceId, RagTraceReadScope.tenantWideCurrent()));
    }

    /**
     * 仅查询链路节点；不在限域内返回空列表（与不存在同外显）
     */
    @GetMapping("/rag/traces/runs/{traceId}/nodes")
    public ResponseEntity<ApiEnvelope<List<RagTraceNodeVO>>> nodes(@PathVariable String traceId) {
        return reply(ragTraceQueryService.listNodes(traceId, RagTraceReadScope.tenantWideCurrent()));
    }

    private static <T> ResponseEntity<ApiEnvelope<T>> reply(T data) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiEnvelope.ok(data));
    }
}
