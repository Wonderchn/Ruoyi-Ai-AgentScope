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

package com.nageoffer.ai.ragent.runtime.web;

import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F03 普通会话批量删除的内部端点（D05）。
 *
 * <p><b>客户端路径</b>：{@code POST /api/ai/v1/conversations/batch-delete}。
 * 本类注册在内层前缀 {@code /internal/ai/v1} 下 —— 与
 * {@code RunController} / {@code RunStreamController} / {@code AiResourceController}
 * 以及 Agent 会话面 {@code ConversationSurface} 同形：网关
 * {@code AiGatewayController} 的转送目标恒为 {@code base + "/internal/ai/v1" + subPath}，
 * 因此客户端见的子路径必须在内层逐字可命中，否则表现为网关必然 404（WP-034 记录过的形态）。
 *
 * <p><b>装配</b>：与其它 AI 控制器一样，内嵌形态<b>不</b>做 {@code com.nageoffer.**} 的组件扫描，
 * 本类必须由 {@code ruoyi-ai-web} 的内嵌装配显式声明为 bean（T0 集成补丁，见 RW-01 报告）。
 * 本类自带 {@link ConditionalOnProperty}，开关缺席时既不注册也不产生任何可达面。
 *
 * <p><b>契约形状</b>：请求体只有一组会话 id（<b>没有</b> tenant/member/user 字段 ——
 * 归属只来自执行主体，客户端提交的身份字段不参与主体选择）；响应是
 * {@link ApiEnvelope}（<b>整数</b> {@code code}）—— 网关对 JSON 路径强制"单 JSON 对象 +
 * 整数 code 等于 HTTP 状态"，字符串 code 会被判成"缺少包络 code"并收敛为 503。
 * 失败响应遵循运行面既有口径：{@code HTTP status == body.code}，符号码放 {@code data.errorCode}，
 * 且 {@code data.retryable} 表达"可否重试"。
 */
@RestController
@RequestMapping("/internal/ai/v1")
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class ConversationBatchDeleteController {

    private final ConversationBatchDeleteService batchDeleteService;

    public ConversationBatchDeleteController(ConversationBatchDeleteService batchDeleteService) {
        this.batchDeleteService = batchDeleteService;
    }

    /**
     * 批量删除请求体（D05）。
     *
     * <p>刻意只有"一组 id"：不接受任何授权/租户/成员字段，上限与去重规则由服务端强制，
     * 客户端传什么都不改变这些判定。字段名与 Agent 会话面的同名端点
     * （{@code POST /agent/v1/conversations/batch-delete} 的 {@code conversationIds}）一致，
     * 使前端两条会话面用同一个请求形状。
     */
    public record BatchDeleteRequest(List<String> conversationIds) {
    }

    @PostMapping("/conversations/batch-delete")
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> batchDelete(
            @RequestBody(required = false) BatchDeleteRequest request) {
        ConversationBatchDeleteService.Outcome outcome =
                batchDeleteService.deleteAll(request == null ? null : request.conversationIds());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deletedCount", outcome.deletedCount());
        data.put("permitCount", outcome.permitCount());
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(ApiEnvelope.ok(data));
    }

    /**
     * 本端点自带的失败映射。
     *
     * <p>控制器内 {@code @ExceptionHandler} 优先于任何 {@code @ControllerAdvice}，
     * 因此本端点的错误契约<b>不依赖</b>调用方是否注册了 {@code RunApiExceptionHandler}：
     * 同一个失败在任何装配形态下都得到同一个 HTTP 状态与同一个符号码。
     * 形状与 {@code RunApiResponses.fail(...)} 逐字一致（status == code，符号码在 data.errorCode）。
     */
    @ExceptionHandler(RunApiException.class)
    public ResponseEntity<Map<String, Object>> handleRunApiException(RunApiException e) {
        String requestId = "req-" + java.util.UUID.randomUUID().toString().replace("-", "");
        return RunApiResponses.fail(e.errorCode(), e.getMessage(), requestId);
    }
}
