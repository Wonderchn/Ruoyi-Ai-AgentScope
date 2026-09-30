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

package com.nageoffer.ai.ragent.rag.runtime;

import com.nageoffer.ai.ragent.framework.security.AiRequestIdFilter;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 受理端点：{@code POST /internal/ai/v1/runs}。
 *
 * <p>成功口径（Spec §8.1 的 C7）：HTTP <b>202</b>，body {@code code=200}，{@code data} 带
 * {@code runId}/{@code requestId}/{@code replayed}。失败由 {@code P04AiExceptionHandler} 映射成
 * {@code HTTP status == body.code}。
 *
 * <p>请求体以原始字符串接收：需要先按需检测伪造身份字段（判 403）再绑定 DTO，
 * 直接用 DTO 绑定会把"携带身份字段"错报成 400。
 */
@RestController
@RequestMapping("/internal/ai/v1")
public class RunAcceptanceController {

    /** 伪造身份头：出现即 403，绝不回落到默认租户。 */
    private static final String[] FORGED_IDENTITY_HEADERS = {"X-Tenant-Id", "X-User-Id", "X-Mid"};

    private final RunAcceptanceService service;

    public RunAcceptanceController(RunAcceptanceService service) {
        this.service = service;
    }

    @PostMapping("/runs")
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> accept(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Tenant-Id", required = false) String forgedTenant,
            @RequestHeader(value = "X-User-Id", required = false) String forgedUser,
            @RequestHeader(value = "X-Mid", required = false) String forgedMid,
            @RequestBody(required = false) String rawBody) {

        if (present(forgedTenant) || present(forgedUser) || present(forgedMid)) {
            throw new P04AiException(P04AiErrorCode.TENANT_CONTEXT_MISSING);
        }

        RunAcceptanceService.Outcome outcome = service.accept(bearer(authorization), idempotencyKey, rawBody);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", outcome.runId());
        data.put("requestId", outcome.requestId());
        data.put("replayed", outcome.replayed());

        return ResponseEntity.status(202)
                .header(AiRequestIdFilter.HEADER, outcome.requestId())
                .body(ApiEnvelope.ok(data));
    }

    /** 伪造身份头清单（供隔离/审计断言引用）。 */
    public static String[] forgedIdentityHeaders() {
        return FORGED_IDENTITY_HEADERS.clone();
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String bearer(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        String value = authorization.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return value.substring(7).trim();
        }
        return value;
    }
}
