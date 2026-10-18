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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.runtime.RunAdmissionService;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.dto.AdmissionRequest;
import com.nageoffer.ai.ragent.runtime.stream.RunEventStreamService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * F13:2 运行起跑（SSE）：{@code POST /internal/ai/v1/flows/{uuid}/run}。
 *
 * <p>受理走既有 {@link RunAdmissionService}（配置权威/维度门/预算锁/run/outbox/
 * consent 契约 {@link FlowRunContract#onAdmitted} 同事务），受理成功后订阅同一账本事件流。
 * 事件名复用账本（D2），不引入旧 {@code [NODE_*]} 别名。
 *
 * <p>受理失败返回 JSON（HTTP status == body.code）；成功返回 SSE。断开只断订阅，
 * 不改 run 状态（状态事实在账本，不在连接）。
 */
@RestController
@RequestMapping("/internal/ai/v1")
@ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
public class FlowRunController {

    private static final int MAX_BODY = 256 * 1024;
    private static final Set<String> BODY_FIELDS = Set.of("inputs", "budget", "retryOf");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final RunAdmissionService admission;
    private final RunEventStreamService stream;

    public FlowRunController(RunAdmissionService admission, RunEventStreamService stream) {
        this.admission = admission;
        this.stream = stream;
    }

    @PostMapping(value = "/flows/{uuid}/run", produces = "text/event-stream")
    public void run(@PathVariable("uuid") String uuid,
                    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                    @RequestBody(required = false) String body,
                    HttpServletResponse response) throws IOException {
        if (!PrincipalContext.hasPrincipal()) {
            writeError(response, RunErrorCode.AUTH_REQUIRED, RunErrorCode.AUTH_REQUIRED.message());
            return;
        }
        ExecutionPrincipal principal = PrincipalContext.require();
        try {
            if (uuid == null || !uuid.matches("[0-9a-f]{32}")) {
                throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
            }
            RunAdmissionService.AdmissionResult result = admission.admit(principal, idempotencyKey,
                    parse(uuid, body));
            stream.stream(principal, result.runId(), 0, response, FlowRunContract.ACTION);
        } catch (RunApiException e) {
            writeError(response, e.errorCode(), e.getMessage());
        } catch (P04AiException e) {
            writeP04Error(response, e);
        } catch (RuntimeException e) {
            writeError(response, RunErrorCode.INTERNAL_ERROR, RunErrorCode.INTERNAL_ERROR.message());
        }
    }

    private AdmissionRequest parse(String uuid, String body) {
        if (body == null || body.isBlank()) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "flow run body is required");
        }
        if (body.length() > MAX_BODY) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "flow run body is too large");
        }
        JsonNode node;
        try {
            node = JSON.readTree(body);
        } catch (Exception e) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "invalid flow run JSON");
        }
        if (node == null || !node.isObject()) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "flow run body must be an object");
        }
        node.fieldNames().forEachRemaining(name -> {
            if (!BODY_FIELDS.contains(name)) {
                throw new RunApiException(RunErrorCode.BAD_REQUEST, "unsupported flow run field: " + name);
            }
        });
        JsonNode inputs = node.get("inputs");
        if (inputs == null || inputs.isNull()) {
            inputs = JSON.createObjectNode();
        }
        if (!inputs.isObject()) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "inputs must be an object");
        }
        JsonNode budget = node.get("budget");
        if (budget != null && !budget.isNull() && !budget.isObject()) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "budget must be an object");
        }
        String retryOf = null;
        JsonNode retryNode = node.get("retryOf");
        if (retryNode != null && !retryNode.isNull()) {
            if (!retryNode.isTextual() || retryNode.asText().isBlank()) {
                throw new RunApiException(RunErrorCode.BAD_REQUEST, "retryOf must be a run id");
            }
            retryOf = retryNode.asText();
        }
        ObjectNode input = JSON.createObjectNode();
        input.put("flowUuid", uuid);
        input.set("inputs", inputs);
        return new AdmissionRequest(1, FlowRunContract.ACTION, null, null, input, List.of(), null,
                budget == null || budget.isNull() ? null : budget, retryOf);
    }

    private static void writeError(HttpServletResponse response, RunErrorCode code, String message)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorCode", code.name());
        data.put("retryable", code.retryable());
        write(response, code.status().value(), message, data);
    }

    private static void writeP04Error(HttpServletResponse response, P04AiException exception) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        P04AiErrorCode code = exception.errorCode();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorCode", code.name());
        data.put("retryable", false);
        write(response, code.httpStatus(), exception.getMessage(), data);
    }

    private static void write(HttpServletResponse response, int status, String message,
                              Map<String, Object> data) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", status);
        body.put("msg", message);
        body.put("data", data);
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSON.writeValueAsString(body));
        response.getWriter().flush();
    }

    /** 供测试与诊断：受理请求标识形状。 */
    static String requestId() {
        return "req-" + UUID.randomUUID().toString().replace("-", "");
    }
}
