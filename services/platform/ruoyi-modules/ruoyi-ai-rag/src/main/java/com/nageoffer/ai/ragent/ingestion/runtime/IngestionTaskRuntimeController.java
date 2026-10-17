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

package com.nageoffer.ai.ragent.ingestion.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.ingestion.task.IngestionRunContract;
import com.nageoffer.ai.ragent.ingestion.task.IngestionTaskView;
import com.nageoffer.ai.ragent.runtime.RunAdmissionService;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.RunLifecycleService;
import com.nageoffer.ai.ragent.runtime.dto.AdmissionRequest;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import com.nageoffer.ai.ragent.runtime.web.RunApiResponses;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * F06:1/2 canonical 摄取**任务面**内层控制器（类级 {@code /internal/ai/v1}）。
 *
 * <p>公开面由 {@code AiGatewayController.ROUTES} 六行放行为
 * {@code /api/ai/v1/ingestion/tasks**}；旧裸面 {@code IngestionTaskController} 映射
 * {@code /ingestion/tasks}（无 internal 前缀），二者无 Spring 冲突。
 *
 * <p>身份只来自委托过滤器（{@link PrincipalContext}）；授权 = scope + 平台功能级
 * {@code requireFunction}；写面经 {@link RunAdmissionService} 正式受理（Idempotency-Key 必填）；
 * 读面自铸交付 permit（GET 走网关字节分支，需回执头）。
 */
@RestController
@RequestMapping("/internal/ai/v1")
@ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
@ConditionalOnProperty(name = "p04.enabled", havingValue = "false", matchIfMissing = true)
public class IngestionTaskRuntimeController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(IngestionTaskRuntimeController.class);
    private static final int MAX_BODY = 262144;

    private final RunAdmissionService admission;
    private final RunLifecycleService lifecycle;
    private final IngestionTaskView view;
    private final DeliveryPermits deliveryPermits;
    private final ObjectMapper objectMapper;
    private final org.springframework.beans.factory.ObjectProvider<
            com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService> authorization;

    public IngestionTaskRuntimeController(RunAdmissionService admission, RunLifecycleService lifecycle,
                                          IngestionTaskView view, DeliveryPermits deliveryPermits,
                                          ObjectMapper objectMapper,
                                          org.springframework.beans.factory.ObjectProvider<
                                                  com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService> authorization) {
        this.admission = admission;
        this.lifecycle = lifecycle;
        this.view = view;
        this.deliveryPermits = deliveryPermits;
        this.objectMapper = objectMapper;
        this.authorization = authorization;
    }

    public record SubmitRequest(String pipelineId, String docId, String kbId, String uploadId, JsonNode budget) {
    }

    public record RetryRequest(String retryNodeId, JsonNode budget) {
    }

    public record CancelRequest(Long expectedVersion) {
    }

    // ------------------------------------------------------------------ writes

    @PostMapping("/ingestion/tasks")
    public ResponseEntity<?> submit(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                    @RequestBody String body) {
        String requestId = requestId();
        try {
            ExecutionPrincipal principal = principal();
            require(principal, "run.submit", "run:new");
            SubmitRequest request = parse(body, SubmitRequest.class);
            if (request.pipelineId() == null || request.docId() == null
                    || request.kbId() == null || request.uploadId() == null) {
                throw new RunApiException(RunErrorCode.BAD_REQUEST,
                        "pipelineId, docId, kbId and uploadId are required");
            }
            AdmissionRequest admissionRequest = buildAdmissionRequest(request.pipelineId(), request.docId(),
                    request.kbId(), request.uploadId(), null, request.budget(), null);
            RunAdmissionService.AdmissionResult result = admission.admit(principal, idempotencyKey, admissionRequest);
            Map<String, Object> data = acceptedView(result, requestId);
            return RunApiResponses.accepted(data, requestId);
        } catch (RunApiException e) {
            return RunApiResponses.fail(e.errorCode(), e.getMessage(), requestId);
        } catch (com.nageoffer.ai.ragent.framework.security.P04AiException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("ingestion submit rejected type={}", e.getClass().getSimpleName());
            return RunApiResponses.fail(RunErrorCode.INTERNAL_ERROR, requestId);
        }
    }

    @PostMapping("/ingestion/tasks/{runId}/retry")
    public ResponseEntity<?> retry(@PathVariable String runId,
                                   @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                   @RequestBody(required = false) String body) {
        String requestId = requestId();
        try {
            ExecutionPrincipal principal = principal();
            require(principal, "run.submit", "run:" + runId);
            RetryRequest request = body == null || body.isBlank()
                    ? new RetryRequest(null, null) : parse(body, RetryRequest.class);
            if (request.retryNodeId() == null || request.retryNodeId().isBlank()) {
                throw new RunApiException(RunErrorCode.BAD_REQUEST, "retryNodeId is required");
            }
            Map<String, Object> parent = view.parentMetadata(principal, runId);
            String pipelineId = asString(parent.get("pipelineId"));
            String docId = asString(parent.get("docId"));
            String kbId = asString(parent.get("kbId"));
            String uploadId = asString(parent.get("uploadId"));
            if (pipelineId == null || docId == null || kbId == null || uploadId == null) {
                throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT, "parent task has no retryable source");
            }
            AdmissionRequest admissionRequest = buildAdmissionRequest(pipelineId, docId, kbId, uploadId,
                    request.retryNodeId(), request.budget(), runId);
            RunAdmissionService.AdmissionResult result = admission.admit(principal, idempotencyKey, admissionRequest);
            Map<String, Object> data = acceptedView(result, requestId);
            data.put("retryOf", runId);
            return RunApiResponses.accepted(data, requestId);
        } catch (RunApiException e) {
            return RunApiResponses.fail(e.errorCode(), e.getMessage(), requestId);
        } catch (com.nageoffer.ai.ragent.framework.security.P04AiException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("ingestion retry rejected type={}", e.getClass().getSimpleName());
            return RunApiResponses.fail(RunErrorCode.INTERNAL_ERROR, requestId);
        }
    }

    @PostMapping("/ingestion/tasks/{runId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String runId,
                                    @RequestBody(required = false) CancelRequest request) {
        String requestId = requestId();
        try {
            ExecutionPrincipal principal = principal();
            require(principal, "run.cancel", "run:" + runId);
            RunRecord run = lifecycle.cancel(principal, runId, request == null ? null : request.expectedVersion());
            return RunApiResponses.ok(HttpStatus.OK, view.runView(run), requestId);
        } catch (RunApiException e) {
            return RunApiResponses.fail(e.errorCode(), e.getMessage(), requestId);
        } catch (com.nageoffer.ai.ragent.framework.security.P04AiException e) {
            throw e;
        } catch (RuntimeException e) {
            return RunApiResponses.fail(RunErrorCode.INTERNAL_ERROR, requestId);
        }
    }

    // ------------------------------------------------------------------ reads

    @GetMapping("/ingestion/tasks")
    public ResponseEntity<?> list(@RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
                                  @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
                                  @RequestParam(value = "status", required = false) String status) {
        String requestId = requestId();
        DeliveryPermits.Permit permit = null;
        try {
            ExecutionPrincipal principal = principal();
            require(principal, "run.get", "tenant:ingestion-tasks");
            permit = deliveryPermits.enter(principal, "run.get", "tenant:ingestion-tasks");
            return RunApiResponses.okWithHeaders(HttpStatus.OK, view.page(principal, pageNo, pageSize, status),
                    requestId, permitHeaders(permit));
        } catch (RunApiException e) {
            closeQuietly(permit);
            return RunApiResponses.fail(e.errorCode(), e.getMessage(), requestId);
        } catch (com.nageoffer.ai.ragent.framework.security.P04AiException e) {
            closeQuietly(permit);
            throw e;
        } catch (RuntimeException e) {
            closeQuietly(permit);
            return RunApiResponses.fail(RunErrorCode.INTERNAL_ERROR, requestId);
        }
    }

    @GetMapping("/ingestion/tasks/{runId}")
    public ResponseEntity<?> detail(@PathVariable String runId) {
        return readWithPermit(runId, () -> view.detail(principal(), runId));
    }

    @GetMapping("/ingestion/tasks/{runId}/nodes")
    public ResponseEntity<?> nodes(@PathVariable String runId) {
        return readWithPermit(runId, () -> view.nodes(principal(), runId));
    }

    private interface ReadSupplier {
        Object get();
    }

    private ResponseEntity<?> readWithPermit(String runId, ReadSupplier supplier) {
        String requestId = requestId();
        DeliveryPermits.Permit permit = null;
        try {
            ExecutionPrincipal principal = principal();
            require(principal, "run.get", "run:" + runId);
            permit = deliveryPermits.enter(principal, "run.get", "run:" + runId);
            return RunApiResponses.okWithHeaders(HttpStatus.OK, supplier.get(), requestId, permitHeaders(permit));
        } catch (RunApiException e) {
            closeQuietly(permit);
            return RunApiResponses.fail(e.errorCode(), e.getMessage(), requestId);
        } catch (com.nageoffer.ai.ragent.framework.security.P04AiException e) {
            closeQuietly(permit);
            throw e;
        } catch (RuntimeException e) {
            closeQuietly(permit);
            return RunApiResponses.fail(RunErrorCode.INTERNAL_ERROR, requestId);
        }
    }

    // ------------------------------------------------------------------ helpers

    private AdmissionRequest buildAdmissionRequest(String pipelineId, String docId, String kbId, String uploadId,
                                                   String retryNodeId, JsonNode budget, String retryOf) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("pipelineId", pipelineId);
        input.put("docId", docId);
        input.put("kbId", kbId);
        input.put("uploadId", uploadId);
        if (retryNodeId != null) {
            input.put("retryNodeId", retryNodeId);
        }
        JsonNode inputNode = objectMapper.valueToTree(input);
        List<AdmissionRequest.ResourceRef> refs = List.of(
                new AdmissionRequest.ResourceRef("knowledge_base", kbId),
                new AdmissionRequest.ResourceRef("document", docId));
        return new AdmissionRequest(1, IngestionRunContract.ACTION, null, null, inputNode, refs, null, budget, retryOf);
    }

    private Map<String, Object> acceptedView(RunAdmissionService.AdmissionResult result, String requestId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", result.runId());
        data.put("status", result.status());
        data.put("createdAt", result.createdAt());
        data.put("replayed", result.replayed());
        data.put("requestId", requestId);
        return data;
    }

    private <T> T parse(String body, Class<T> type) {
        if (body == null || body.length() > MAX_BODY) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST);
        }
        try {
            return new ObjectMapper()
                    .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(body, type);
        } catch (java.io.IOException e) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "invalid request JSON");
        }
    }

    private Map<String, String> permitHeaders(DeliveryPermits.Permit permit) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-AI-Delivery-Permit", permit.permitId());
        headers.put("X-AI-Delivery-Operation", permit.operationId());
        return headers;
    }

    private static void closeQuietly(DeliveryPermits.Permit permit) {
        if (permit != null) {
            try {
                permit.close();
            } catch (RuntimeException ignored) {
                // 关闭失败不遮蔽原始拒绝
            }
        }
    }

    private void require(ExecutionPrincipal principal, String action, String resourceRef) {
        if (!principal.hasScope(action)) {
            throw new RunApiException(RunErrorCode.FORBIDDEN);
        }
        var service = authorization.getIfAvailable();
        if (service != null) {
            service.requireFunction(principal, action, resourceRef);
        }
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

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
