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

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.runtime.P2RuntimeProperties;
import com.nageoffer.ai.ragent.runtime.RunAdmissionService;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.RunLifecycleService;
import com.nageoffer.ai.ragent.runtime.dto.AdmissionRequest;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 正式运行受理/生命周期（内部前缀；身份只来自委托过滤器）。
 *
 * <p>仅当 {@code p2.enabled=true} 且 P0.4 实验关闭时装配；与 P0.4 的
 * {@code RunAcceptanceController} 互斥，避免同一路径两套事实。
 */
@RestController
@RequestMapping("/internal/ai/v1")
@ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
@ConditionalOnProperty(name = "p04.enabled", havingValue = "false", matchIfMissing = true)
public class RunController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RunController.class);

    private final RunAdmissionService admission;
    private final RunLifecycleService lifecycle;
    private final P2RuntimeProperties properties;
    private final org.springframework.beans.factory.ObjectProvider<
            com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService> authorization;

    public RunController(RunAdmissionService admission, RunLifecycleService lifecycle, P2RuntimeProperties properties,
                         org.springframework.beans.factory.ObjectProvider<
                                 com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService> authorization) {
        this.admission = admission;
        this.lifecycle = lifecycle;
        this.properties = properties;
        this.authorization = authorization;
    }

    public record CancelRequest(Long expectedVersion) {
    }

    public record ResumeRequest(Long expectedVersion) {
    }

    @PostMapping("/runs")
    public ResponseEntity<?> submit(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                    @RequestBody String body) {
        String requestId = requestId();
        try {
            ExecutionPrincipal principal = principal();
            require(principal, "run.submit", "run:new");
            AdmissionRequest request = parseAdmission(body);
            RunAdmissionService.AdmissionResult result = admission.admit(principal, idempotencyKey, request);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("runId", result.runId());
            data.put("status", result.status());
            data.put("createdAt", result.createdAt());
            data.put("replayed", result.replayed());
            data.put("requestId", requestId);
            return RunApiResponses.accepted(data, requestId);
        } catch (RunApiException e) {
            return RunApiResponses.fail(e.errorCode(), e.getMessage(), requestId);
        } catch (com.nageoffer.ai.ragent.framework.security.P04AiException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("run admission rejected type={} cause={}", e.getClass().getSimpleName(),
                    e.getCause()==null ? "none" : e.getCause().getClass().getSimpleName());
            return RunApiResponses.fail(RunErrorCode.INTERNAL_ERROR, requestId);
        }
    }

    private AdmissionRequest parseAdmission(String body) {
        if (body == null || body.length() > 262144) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST);
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(body, AdmissionRequest.class);
        } catch (java.io.IOException e) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "invalid admission JSON");
        }
    }

    @PostMapping("/runs/{runId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String runId,
                                    @RequestBody(required = false) CancelRequest request) {
        String requestId = requestId();
        try {
            ExecutionPrincipal principal = principal();
            require(principal, "run.cancel", "run:" + runId);
            RunRecord run = lifecycle.cancel(principal, runId,
                    request == null ? null : request.expectedVersion());
            return RunApiResponses.ok(org.springframework.http.HttpStatus.OK, runView(run), requestId);
        } catch (RunApiException e) {
            return RunApiResponses.fail(e.errorCode(), e.getMessage(), requestId);
        } catch (com.nageoffer.ai.ragent.framework.security.P04AiException e) {
            // 平台功能级判定（requireFunction）的拒绝必须按**它自己的**符号码外显。
            // 此前这里被下面的 catch(RuntimeException) 收成 500 INTERNAL_ERROR，
            // 于是"有 scope 但功能/资源级不通过"（应为 403 FORBIDDEN）与"服务端崩了"
            // 在客户端完全同形 —— 与 submit 分支（显式 rethrow 给全局映射器）不一致，
            // 也与 AiResourceController 的同一判定口径不一致。
            throw e;
        } catch (RuntimeException e) {
            return RunApiResponses.fail(RunErrorCode.INTERNAL_ERROR, requestId);
        }
    }

    @PostMapping("/runs/{runId}/resume")
    public ResponseEntity<?> resume(@PathVariable String runId,
                                    @RequestBody(required = false) ResumeRequest request) {
        String requestId = requestId();
        try {
            ExecutionPrincipal principal = principal();
            require(principal, "run.resume", "run:" + runId);
            if (request == null || request.expectedVersion() == null) {
                throw new RunApiException(RunErrorCode.BAD_REQUEST, "expectedVersion is required");
            }
            RunRecord run = lifecycle.resume(principal, runId, request.expectedVersion());
            return RunApiResponses.ok(org.springframework.http.HttpStatus.OK, runView(run), requestId);
        } catch (RunApiException e) {
            return RunApiResponses.fail(e.errorCode(), e.getMessage(), requestId);
        } catch (com.nageoffer.ai.ragent.framework.security.P04AiException e) {
            // 同 cancel：平台功能级拒绝按原符号码外显（403），不得收敛成 500。
            throw e;
        } catch (RuntimeException e) {
            return RunApiResponses.fail(RunErrorCode.INTERNAL_ERROR, requestId);
        }
    }

    /** 平台在线复核：动作必须在当前委托 scope 内，且当前成员/策略版本放行。 */
    private void require(ExecutionPrincipal principal, String action, String resourceRef) {
        if (!principal.hasScope(action)) {
            throw new RunApiException(RunErrorCode.FORBIDDEN);
        }
        var service = authorization.getIfAvailable();
        if (service != null) {
            service.requireFunction(principal, action, resourceRef);
        }
    }

    private Map<String, Object> runView(RunRecord run) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("runId", run.runId());
        view.put("status", run.status());
        view.put("version", run.version());
        view.put("attempt", run.attempt());
        view.put("cancelRequestedAt", run.cancelRequestedAt());
        view.put("errorCode", run.errorCode());
        return view;
    }

    private ExecutionPrincipal principal() {
        if (!PrincipalContext.hasPrincipal()) {
            throw new RunApiException(RunErrorCode.AUTH_REQUIRED);
        }
        return PrincipalContext.require();
    }

    private String requestId() {
        return "req-" + UUID.randomUUID().toString().replace("-", "");
    }
}
