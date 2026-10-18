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

package com.nageoffer.ai.ragent.flow.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.stream.RunEventStreamService;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * F13:2 runtime 视图面（§3.6）：列表 / 详情 / 节点 / 续流 / 清理 / 软删。
 *
 * <p><b>资源边界（owner-only）。</b>列表、详情、节点、续流都按
 * {@code tenant + user_id + is_deleted=0} 限定；跨租户/他人运行与"不存在"同形 404。
 * 清理/软删走 {@code flow.delete}（D1），置 {@code is_deleted=1}，不物理删。
 *
 * <p><b>GET 走字节分支。</b>先取数、后铸整数 {@link ApiEnvelope} 与两个交付回执头
 * （{@code X-AI-Delivery-Permit}/{@code X-AI-Delivery-Operation}）；写面走 JSON 分支，
 * 不铸回执。续流是 SSE，逐帧回执由 {@link RunEventStreamService} 承担。
 */
@RestController
@RequestMapping("/internal/ai/v1")
@ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
public class FlowRuntimeController {

    private static final int MAX_PAGE_SIZE = 100;

    private final FlowRuntimeRepository repository;
    private final DeliveryPermits permits;
    private final RunEventStreamService stream;
    private final ObjectMapper objectMapper;

    public FlowRuntimeController(FlowRuntimeRepository repository, DeliveryPermits permits,
                                 RunEventStreamService stream, ObjectMapper objectMapper) {
        this.repository = repository;
        this.permits = permits;
        this.stream = stream;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ reads

    @GetMapping("/flows/runs")
    public ResponseEntity<ApiEnvelope<?>> list(
            @RequestParam(value = "wfUuid", required = false) String wfUuid,
            @RequestParam(value = "currentPage", required = false, defaultValue = "1") int currentPage,
            @RequestParam(value = "pageSize", required = false, defaultValue = "10") int pageSize) {
        try {
            ExecutionPrincipal principal = requirePrincipal();
            long userId = FlowRuntimeRepository.ownerUserId(principal);
            int size = Math.min(MAX_PAGE_SIZE, Math.max(1, pageSize));
            int current = Math.max(1, currentPage);
            List<FlowRuntimeRepository.RuntimeView> rows = repository.listRuntimes(principal.tenantId(),
                    userId, wfUuid, size, (current - 1) * size);
            long total = repository.countRuntimes(principal.tenantId(), userId, wfUuid);
            Map<String, Object> data = FlowRuntimeViews.list(rows, total, current, size, objectMapper);
            DeliveryPermits.Permit permit = permits.enter(principal, "flow.run.read", "flow:run:mine");
            try {
                return ResponseEntity.ok()
                        .header("Cache-Control", "no-store")
                        .header("X-AI-Delivery-Permit", permit.permitId())
                        .header("X-AI-Delivery-Operation", permit.operationId())
                        .body(ApiEnvelope.ok(data));
            } catch (RuntimeException e) {
                permit.close();
                throw e;
            }
        } catch (RunApiException e) {
            return fail(e.errorCode(), e.getMessage());
        } catch (P04AiException e) {
            return p04Fail(e);
        }
    }

    @GetMapping("/flows/runs/{runtimeUuid}")
    public ResponseEntity<ApiEnvelope<?>> detail(@PathVariable("runtimeUuid") String runtimeUuid) {
        try {
            ExecutionPrincipal principal = requirePrincipal();
            long userId = FlowRuntimeRepository.ownerUserId(principal);
            Optional<FlowRuntimeRepository.RuntimeView> view = repository.findRuntimeView(
                    principal.tenantId(), userId, runtimeUuid);
            if (view.isEmpty()) {
                return notFound();
            }
            Map<String, Object> data = FlowRuntimeViews.runtime(view.get(), objectMapper);
            DeliveryPermits.Permit permit = permits.enter(principal, "flow.run.read",
                    "flow:run:" + runtimeUuid);
            try {
                return ResponseEntity.ok()
                        .header("Cache-Control", "no-store")
                        .header("X-AI-Delivery-Permit", permit.permitId())
                        .header("X-AI-Delivery-Operation", permit.operationId())
                        .body(ApiEnvelope.ok(data));
            } catch (RuntimeException e) {
                permit.close();
                throw e;
            }
        } catch (RunApiException e) {
            return fail(e.errorCode(), e.getMessage());
        } catch (P04AiException e) {
            return p04Fail(e);
        }
    }

    @GetMapping("/flows/runs/{runtimeUuid}/nodes")
    public ResponseEntity<ApiEnvelope<?>> nodes(@PathVariable("runtimeUuid") String runtimeUuid) {
        try {
            ExecutionPrincipal principal = requirePrincipal();
            long userId = FlowRuntimeRepository.ownerUserId(principal);
            if (repository.findRuntimeView(principal.tenantId(), userId, runtimeUuid).isEmpty()) {
                return notFound();
            }
            List<Map<String, Object>> data = FlowRuntimeViews.nodes(
                    repository.listRuntimeNodes(principal.tenantId(), userId, runtimeUuid), objectMapper);
            DeliveryPermits.Permit permit = permits.enter(principal, "flow.run.read",
                    "flow:run:" + runtimeUuid);
            try {
                return ResponseEntity.ok()
                        .header("Cache-Control", "no-store")
                        .header("X-AI-Delivery-Permit", permit.permitId())
                        .header("X-AI-Delivery-Operation", permit.operationId())
                        .body(ApiEnvelope.ok(data));
            } catch (RuntimeException e) {
                permit.close();
                throw e;
            }
        } catch (RunApiException e) {
            return fail(e.errorCode(), e.getMessage());
        } catch (P04AiException e) {
            return p04Fail(e);
        }
    }

    // ------------------------------------------------------------------ writes (no permits)

    @PostMapping("/flows/runs/clear")
    public ResponseEntity<ApiEnvelope<?>> clear(
            @RequestParam(value = "wfUuid", required = false) String wfUuid) {
        try {
            ExecutionPrincipal principal = requirePrincipal();
            int cleared = repository.softDeleteByWorkflow(principal.tenantId(),
                    FlowRuntimeRepository.ownerUserId(principal), wfUuid);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("cleared", cleared);
            return ResponseEntity.ok().body(ApiEnvelope.ok(data));
        } catch (RunApiException e) {
            return fail(e.errorCode(), e.getMessage());
        } catch (P04AiException e) {
            return p04Fail(e);
        }
    }

    @DeleteMapping("/flows/runs/{runtimeUuid}")
    public ResponseEntity<ApiEnvelope<?>> delete(@PathVariable("runtimeUuid") String runtimeUuid) {
        try {
            ExecutionPrincipal principal = requirePrincipal();
            boolean deleted = repository.softDeleteRuntime(principal.tenantId(),
                    FlowRuntimeRepository.ownerUserId(principal), runtimeUuid);
            if (!deleted) {
                return notFound();
            }
            return ResponseEntity.ok().body(ApiEnvelope.ok(null));
        } catch (RunApiException e) {
            return fail(e.errorCode(), e.getMessage());
        } catch (P04AiException e) {
            return p04Fail(e);
        }
    }

    // ------------------------------------------------------------------ continuation SSE

    @GetMapping("/flows/runs/{runtimeUuid}/events")
    public void events(@PathVariable("runtimeUuid") String runtimeUuid,
                       @RequestParam(value = "afterSeq", required = false) Long afterSeq,
                       @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                       HttpServletResponse response) throws IOException {
        if (!PrincipalContext.hasPrincipal()) {
            writeJsonError(response, RunErrorCode.AUTH_REQUIRED);
            return;
        }
        ExecutionPrincipal principal = PrincipalContext.require();
        try {
            String runId = FlowRuntimeRepository.runIdOf(runtimeUuid);
            long userId = FlowRuntimeRepository.ownerUserId(principal);
            if (repository.findRuntimeView(principal.tenantId(), userId, runtimeUuid).isEmpty()) {
                writeJsonError(response, RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
                return;
            }
            long cursor = afterSeq != null ? afterSeq : parseCursor(lastEventId);
            if (cursor < 0) {
                writeJsonError(response, RunErrorCode.BAD_REQUEST);
                return;
            }
            stream.stream(principal, runId, cursor, response, "flow.run.read");
        } catch (RunApiException e) {
            writeJsonError(response, e.errorCode());
        } catch (P04AiException e) {
            writeP04Error(response, e);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static ExecutionPrincipal requirePrincipal() {
        if (!PrincipalContext.hasPrincipal()) {
            throw new RunApiException(RunErrorCode.AUTH_REQUIRED);
        }
        return PrincipalContext.require();
    }

    private static ResponseEntity<ApiEnvelope<?>> notFound() {
        return ResponseEntity.status(404).header("Cache-Control", "no-store")
                .body(ApiEnvelope.error(404, "运行实例不存在", "RESOURCE_NOT_FOUND_OR_FORBIDDEN"));
    }

    private static ResponseEntity<ApiEnvelope<?>> fail(RunErrorCode code, String message) {
        return ResponseEntity.status(code.status())
                .body(ApiEnvelope.error(code.status().value(), message, code.name()));
    }

    private static ResponseEntity<ApiEnvelope<?>> p04Fail(P04AiException exception) {
        P04AiErrorCode code = exception.errorCode();
        return ResponseEntity.status(code.httpStatus())
                .body(ApiEnvelope.error(code.httpStatus(), exception.getMessage(), code.name()));
    }

    private static long parseCursor(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(lastEventId.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void writeJsonError(HttpServletResponse response, RunErrorCode code) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorCode", code.name());
        data.put("retryable", code.retryable());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code.status().value());
        body.put("msg", code.message());
        body.put("data", data);
        response.setStatus(code.status().value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(new ObjectMapper().writeValueAsString(body));
        response.getWriter().flush();
    }

    private static void writeP04Error(HttpServletResponse response, P04AiException exception) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        P04AiErrorCode code = exception.errorCode();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorCode", code.name());
        data.put("retryable", false);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code.httpStatus());
        body.put("msg", exception.getMessage());
        body.put("data", data);
        response.setStatus(code.httpStatus());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(new ObjectMapper().writeValueAsString(body));
        response.getWriter().flush();
    }
}
