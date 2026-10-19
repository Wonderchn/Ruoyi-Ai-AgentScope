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

package com.nageoffer.ai.ragent.media.task;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import com.nageoffer.ai.ragent.runtime.web.RunApiResponses;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F19 媒体工作台内部面（{@code /internal/ai/v1}），经平台网关 {@code /api/ai/v1} 到达。
 *
 * <p><b>路由（与 workbench 现有契约对齐）。</b>
 * <ul>
 *   <li>{@code POST /media/image}：提交图片任务，返回任务 ID/状态；</li>
 *   <li>{@code GET /media/prediction?model&predictionId}：查询任务；</li>
 *   <li>{@code GET /media/tasks} / {@code GET /media/tasks/{id}}：服务端任务列表/详情；</li>
 *   <li>{@code POST /media/tasks/{id}/retry}：显式重试（新 attempt）；</li>
 *   <li>{@code GET /media/content?model&predictionId}：受控私有取流（Range/Content-Type）。</li>
 * </ul>
 *
 * <p><b>网关契约。</b>GET 在网关走字节分支：成功响应必须同时给整数 {@link ApiEnvelope}
 * 与两个交付回执头（{@code X-AI-Delivery-Permit}/{@code X-AI-Delivery-Operation}），
 * POST 走 JSON 分支、不铸回执（JSON 分支铸造 = 永久 ACTIVE 许可泄漏）。
 *
 * <p><b>身份。</b>主体只来自 {@link PrincipalContext}（本地传输由身份桥写入）；
 * 不接受 body/header 里的租户/用户字段。任务查询一律 tenant+属主限定。
 */
@RestController
@RequestMapping("/internal/ai/v1")
public class MediaController {

    private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");

    private final MediaTaskService service;
    private final DeliveryPermits permits;

    public MediaController(MediaTaskService service, DeliveryPermits permits) {
        this.service = service;
        this.permits = permits;
    }

    /** 提交图片任务（幂等键可来自 body 或 {@code Idempotency-Key}）。 */
    @PostMapping("/media/image")
    public ResponseEntity<?> submitImage(@RequestBody(required = false) ImageRequest body,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        String requestId = "req-" + UUID.randomUUID();
        try {
            ExecutionPrincipal principal = principal();
            ImageRequest request = body == null ? new ImageRequest(null, null, null, null, null) : body;
            String id = idempotencyKey == null || idempotencyKey.isBlank()
                    ? request.requestId() : idempotencyKey;
            MediaTaskView view = service.submitImage(principal,
                    new MediaTaskService.ImageSubmitCommand(request.model(), request.prompt(),
                            request.size(), request.seed(), id));
            return RunApiResponses.ok(HttpStatus.OK, view, requestId);
        } catch (RunApiException e) {
            return RunApiResponses.fail(e.errorCode(), e.getMessage(), requestId);
        } catch (ClientException e) {
            return RunApiResponses.fail(RunErrorCode.AUTH_REQUIRED, RunErrorCode.AUTH_REQUIRED.message(), requestId);
        }
    }

    /** 查询任务（workbench 轮询沿用 {@code /media/prediction}）。 */
    @GetMapping("/media/prediction")
    public ResponseEntity<?> prediction(@RequestParam(value = "model", required = false) String model,
                                        @RequestParam(value = "predictionId", required = false) String predictionId) {
        try {
            ExecutionPrincipal principal = principal();
            MediaTaskView view = service.query(principal, predictionId);
            DeliveryPermits.Permit permit = permits.enter(principal, "run.get", "media:" + view.id());
            try {
                return ResponseEntity.ok()
                        .header("Cache-Control", "no-store")
                        .header("X-AI-Delivery-Permit", permit.permitId())
                        .header("X-AI-Delivery-Operation", permit.operationId())
                        .body(ApiEnvelope.ok(view));
            } catch (RuntimeException e) {
                permit.close();
                throw e;
            }
        } catch (RunApiException e) {
            return fail(e);
        } catch (ClientException e) {
            return fail(RunErrorCode.AUTH_REQUIRED);
        }
    }

    /** 服务端任务列表：刷新后任务仍在，不依赖前端内存。 */
    @GetMapping("/media/tasks")
    public ResponseEntity<?> tasks(@RequestParam(value = "limit", required = false, defaultValue = "20") int limit) {
        try {
            ExecutionPrincipal principal = principal();
            MediaTaskView.Page page = service.list(principal, limit);
            DeliveryPermits.Permit permit = permits.enter(principal, "run.get", "media:mine");
            try {
                return ResponseEntity.ok()
                        .header("Cache-Control", "no-store")
                        .header("X-AI-Delivery-Permit", permit.permitId())
                        .header("X-AI-Delivery-Operation", permit.operationId())
                        .body(ApiEnvelope.ok(page));
            } catch (RuntimeException e) {
                permit.close();
                throw e;
            }
        } catch (RunApiException e) {
            return fail(e);
        } catch (ClientException e) {
            return fail(RunErrorCode.AUTH_REQUIRED);
        }
    }

    /** 任务详情。 */
    @GetMapping("/media/tasks/{taskId}")
    public ResponseEntity<?> task(@PathVariable("taskId") String taskId) {
        try {
            ExecutionPrincipal principal = principal();
            MediaTaskView view = service.query(principal, taskId);
            DeliveryPermits.Permit permit = permits.enter(principal, "run.get", "media:" + view.id());
            try {
                return ResponseEntity.ok()
                        .header("Cache-Control", "no-store")
                        .header("X-AI-Delivery-Permit", permit.permitId())
                        .header("X-AI-Delivery-Operation", permit.operationId())
                        .body(ApiEnvelope.ok(view));
            } catch (RuntimeException e) {
                permit.close();
                throw e;
            }
        } catch (RunApiException e) {
            return fail(e);
        } catch (ClientException e) {
            return fail(RunErrorCode.AUTH_REQUIRED);
        }
    }

    /** 显式重试：仅 FAILED/UNKNOWN；UNKNOWN 的旧预占不退还、也不复用。 */
    @PostMapping("/media/tasks/{taskId}/retry")
    public ResponseEntity<?> retry(@PathVariable("taskId") String taskId,
                                   @RequestBody(required = false) RetryRequest body,
                                   @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        String requestId = "req-" + UUID.randomUUID();
        try {
            ExecutionPrincipal principal = principal();
            String id = idempotencyKey == null || idempotencyKey.isBlank()
                    ? (body == null ? null : body.requestId()) : idempotencyKey;
            MediaTaskView view = service.retry(principal, taskId, id);
            return RunApiResponses.ok(HttpStatus.OK, view, requestId);
        } catch (RunApiException e) {
            return RunApiResponses.fail(e.errorCode(), e.getMessage(), requestId);
        } catch (ClientException e) {
            return RunApiResponses.fail(RunErrorCode.AUTH_REQUIRED, RunErrorCode.AUTH_REQUIRED.message(), requestId);
        }
    }

    /**
     * 受控私有取流：provider 裸 URL 从不下发；只按任务 ID 从私有对象存储读，
     * 成功后才铸交付回执。Range 只支持单区间；非法/多区间按整段 200 返回。
     */
    @GetMapping("/media/content")
    public ResponseEntity<?> content(@RequestParam(value = "predictionId", required = false) String predictionId,
                                     @RequestParam(value = "model", required = false) String model,
                                     @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {
        try {
            ExecutionPrincipal principal = principal();
            MediaTaskService.MediaContent content = service.content(principal, predictionId);
            DeliveryPermits.Permit permit = permits.enter(principal, "run.get", "media:" + predictionId);
            try {
                byte[] bytes = content.bytes();
                ByteRange range = parseRange(rangeHeader, bytes.length);
                HttpStatus status = range == null ? HttpStatus.OK : HttpStatus.PARTIAL_CONTENT;
                byte[] body = range == null ? bytes : slice(bytes, range);
                String mime = content.mimeType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : content.mimeType();
                var builder = ResponseEntity.status(status)
                        .header("Cache-Control", "no-store")
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Content-Security-Policy", "default-src 'none'; sandbox")
                        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "inline; filename=\"" + safeFileName(predictionId, mime) + "\"")
                        .header("X-AI-Delivery-Permit", permit.permitId())
                        .header("X-AI-Delivery-Operation", permit.operationId())
                        .contentType(MediaType.parseMediaType(mime))
                        .contentLength(body.length);
                if (range != null) {
                    builder.header(HttpHeaders.CONTENT_RANGE,
                            "bytes " + range.start() + "-" + range.end() + "/" + bytes.length);
                }
                return builder.body(body);
            } catch (RuntimeException e) {
                permit.close();
                throw e;
            }
        } catch (RunApiException e) {
            return fail(e);
        } catch (ClientException e) {
            return fail(RunErrorCode.AUTH_REQUIRED);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static ExecutionPrincipal principal() {
        return PrincipalContext.require();
    }

    private static ResponseEntity<?> fail(RunApiException e) {
        return RunApiResponses.fail(e.errorCode(), e.getMessage(), "req-" + UUID.randomUUID());
    }

    private static ResponseEntity<?> fail(RunErrorCode code) {
        return RunApiResponses.fail(code, code.message(), "req-" + UUID.randomUUID());
    }

    private record ByteRange(long start, long end) {
    }

    /** 单区间 {@code bytes=start-end}；非法/多区间/越界返回 null（按整段响应）。 */
    private static ByteRange parseRange(String header, long total) {
        if (header == null || header.isBlank() || total <= 0 || header.indexOf(',') >= 0) {
            return null;
        }
        Matcher matcher = RANGE.matcher(header.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            return null;
        }
        String rawStart = matcher.group(1);
        String rawEnd = matcher.group(2);
        try {
            long start;
            long end;
            if (rawStart.isEmpty()) {
                if (rawEnd.isEmpty()) {
                    return null;
                }
                long suffix = Long.parseLong(rawEnd);
                if (suffix <= 0) {
                    return null;
                }
                start = Math.max(0, total - suffix);
                end = total - 1;
            } else {
                start = Long.parseLong(rawStart);
                end = rawEnd.isEmpty() ? total - 1 : Long.parseLong(rawEnd);
            }
            if (start < 0 || end < start || start >= total) {
                return null;
            }
            return new ByteRange(start, Math.min(end, total - 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static byte[] slice(byte[] bytes, ByteRange range) {
        int length = (int) (range.end() - range.start() + 1);
        byte[] body = new byte[length];
        System.arraycopy(bytes, (int) range.start(), body, 0, length);
        return body;
    }

    private static String safeFileName(String taskId, String mime) {
        String id = taskId == null ? "media" : taskId.replaceAll("[^A-Za-z0-9_-]", "_");
        String extension = switch (mime == null ? "" : mime) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "image/avif" -> "avif";
            default -> "bin";
        };
        String base = id.length() > 80 ? id.substring(0, 80) : id;
        return base + "." + extension;
    }

    public record ImageRequest(String model, String prompt, String size, Integer seed, String requestId) {
    }

    public record RetryRequest(String requestId) {
    }
}
