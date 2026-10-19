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

package com.nageoffer.ai.ragent.media.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.media.config.MediaProperties;
import com.nageoffer.ai.ragent.media.image.MediaProviderRejectedException;
import com.nageoffer.ai.ragent.media.image.MediaProviderUnavailableException;
import com.nageoffer.ai.ragent.media.image.WanxHttpTransport;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * F19:2 视频生成适配器（DashScope 通义万相文生视频异步协议）。
 *
 * <p><b>协议。</b>
 * <ul>
 *   <li>提交：{@code POST /api/v1/services/aigc/video-generation/video-synthesis}，
 *       头 {@code X-DashScope-Async: enable}，返回 {@code output.task_id}；</li>
 *   <li>轮询：{@code GET /api/v1/tasks/{taskId}}，{@code output.task_status} /
 *       {@code output.video_url}。</li>
 * </ul>
 *
 * <p><b>未知语义。</b>2xx 但没有 task_id、或非 4xx 的传输失败都按"结果未知"抛出——
 * 调用方必须落 UNKNOWN，绝不自动重发付费请求。协议没有 quality 字段，不下发。
 *
 * <p><b>凭据。</b>{@code apiKey} 只从配置读取；不打印、不回显、不落盘。
 */
public class DashScopeVideoGenerationService implements MediaVideoProvider {

    private static final String SUBMIT_PATH = "/api/v1/services/aigc/video-generation/video-synthesis";
    private static final String TASK_PATH = "/api/v1/tasks/";
    private static final int MAX_ERROR_MESSAGE = 240;

    private final MediaProperties properties;
    private final WanxHttpTransport transport;
    private final ObjectMapper mapper;

    public DashScopeVideoGenerationService(MediaProperties properties, WanxHttpTransport transport,
                                           ObjectMapper mapper) {
        this.properties = properties;
        this.transport = transport;
        this.mapper = mapper;
    }

    @Override
    public String providerName() {
        return properties.getVideo().getProvider();
    }

    @Override
    public String submit(MediaVideoRequest request) {
        MediaProperties.Video video = properties.getVideo();
        requireConfigured(video);
        String model = requireText(request.model(), "model");
        String prompt = requireText(request.prompt(), "prompt");
        String size = request.size() == null || request.size().isBlank()
                ? video.getDefaultSize() : request.size();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("input", Map.of("prompt", prompt));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("size", size);
        if (request.seconds() != null) {
            parameters.put("duration", request.seconds());
        }
        payload.put("parameters", parameters);
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(payload);
        } catch (IOException e) {
            throw new MediaProviderRejectedException("PROVIDER_REQUEST_INVALID", "无法编码视频生成请求");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + video.getApiKey());
        headers.put("Content-Type", "application/json");
        headers.put("X-DashScope-Async", "enable");
        WanxHttpTransport.Reply reply = send("POST", apiUrl(SUBMIT_PATH), headers, body, video);
        JsonNode root = parse(reply);
        if (reply.status() >= 200 && reply.status() < 300) {
            String taskId = text(root.path("output").path("task_id"));
            if (taskId == null) {
                // 2xx 但没有任务 ID：调用可能已经产生费用，按未知处理，绝不自动重发。
                throw new MediaProviderUnavailableException("provider returned no task id");
            }
            return taskId;
        }
        if (definitive(reply.status())) {
            throw new MediaProviderRejectedException(codeFor(reply.status()), providerMessage(root, reply.status()));
        }
        throw new MediaProviderUnavailableException("provider submit failed with status " + reply.status());
    }

    @Override
    public MediaVideoPoll poll(String providerTaskId) {
        MediaProperties.Video video = properties.getVideo();
        requireConfigured(video);
        String taskId = requireText(providerTaskId, "providerTaskId");
        Map<String, String> headers = Map.of("Authorization", "Bearer " + video.getApiKey());
        WanxHttpTransport.Reply reply = send("GET", apiUrl(TASK_PATH + encodePath(taskId)), headers, null, video);
        JsonNode root = parse(reply);
        if (reply.status() >= 200 && reply.status() < 300) {
            JsonNode output = root.path("output");
            String status = normalizeStatus(text(output.path("task_status")));
            String videoUrl = firstResultUrl(output);
            String providerRequestId = text(root.path("request_id"));
            Map<String, Object> usage = usage(root.path("usage"));
            if ("SUCCEEDED".equals(status) && videoUrl == null) {
                return new MediaVideoPoll("UNKNOWN", null, "PROVIDER_RESULT_MISSING",
                        "提供方未返回视频结果地址", providerRequestId, usage);
            }
            String message = text(output.path("message"));
            if (message == null) {
                message = text(output.path("fail_reason"));
            }
            return new MediaVideoPoll(status, videoUrl,
                    "FAILED".equals(status) || "CANCELED".equals(status) ? "PROVIDER_" + status : null,
                    sanitize(message), providerRequestId, usage);
        }
        if (definitive(reply.status())) {
            throw new MediaProviderRejectedException(codeFor(reply.status()), providerMessage(root, reply.status()));
        }
        throw new MediaProviderUnavailableException("provider poll failed with status " + reply.status());
    }

    @Override
    public byte[] download(String providerUrl, long maxBytes) {
        URI uri = requireAllowedResultUri(providerUrl);
        long limit = Math.min(Math.max(1, maxBytes), properties.getVideo().getMaxBytes());
        WanxHttpTransport.Reply reply;
        try {
            // 结果下载不携带 API key：认证头只发往配置的 API host。
            reply = transport.send("GET", uri.toString(), Map.of(), null, limit + 1);
        } catch (IOException e) {
            throw new MediaProviderUnavailableException("result download failed", e);
        }
        if (reply.status() != 200) {
            throw new MediaProviderUnavailableException("result download failed with status " + reply.status());
        }
        String contentType = reply.contentType() == null ? "" : reply.contentType().toLowerCase(Locale.ROOT);
        if (!contentType.startsWith("video/")) {
            throw new MediaProviderRejectedException("PROVIDER_RESULT_INVALID", "结果内容不是视频");
        }
        if (reply.body() == null || reply.body().length == 0) {
            throw new MediaProviderRejectedException("PROVIDER_RESULT_INVALID", "结果内容为空");
        }
        if (reply.body().length > limit) {
            throw new MediaProviderRejectedException("PROVIDER_RESULT_TOO_LARGE", "结果超过大小上限");
        }
        return reply.body();
    }

    /**
     * 结果字节的 MIME 嗅探（mp4/webm/ogg）。
     *
     * @throws MediaProviderRejectedException 无法识别的视频内容
     */
    @Override
    public String sniffMime(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new MediaProviderRejectedException("PROVIDER_RESULT_INVALID", "结果内容为空");
        }
        if (bytes.length >= 12 && bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p') {
            return "video/mp4";
        }
        if (bytes.length >= 4 && (bytes[0] & 0xFF) == 0x1A && (bytes[1] & 0xFF) == 0x45
                && (bytes[2] & 0xFF) == 0xDF && (bytes[3] & 0xFF) == 0xA3) {
            return "video/webm";
        }
        if (bytes.length >= 4 && bytes[0] == 'O' && bytes[1] == 'g' && bytes[2] == 'g' && bytes[3] == 'S') {
            return "video/ogg";
        }
        throw new MediaProviderRejectedException("PROVIDER_RESULT_INVALID", "结果内容不是可识别的视频");
    }

    // ------------------------------------------------------------------ helpers

    private void requireConfigured(MediaProperties.Video video) {
        if (!video.isEnabled()) {
            throw new MediaProviderUnavailableException("video provider is disabled");
        }
        if (video.getApiKey() == null || video.getApiKey().isBlank()) {
            // 不打印/不回显 key；只说明"未配置"。
            throw new MediaProviderUnavailableException("video provider credential is not configured");
        }
        if (video.getModelName() == null || video.getModelName().isBlank()) {
            throw new MediaProviderUnavailableException("video model is not configured");
        }
    }

    private WanxHttpTransport.Reply send(String method, String url, Map<String, String> headers,
                                         byte[] body, MediaProperties.Video video) {
        try {
            return transport.send(method, url, headers, body, video.getMaxBytes());
        } catch (IOException e) {
            throw new MediaProviderUnavailableException("provider request failed", e);
        }
    }

    private JsonNode parse(WanxHttpTransport.Reply reply) {
        try {
            if (reply.body() == null || reply.body().length == 0) {
                return mapper.createObjectNode();
            }
            return mapper.readTree(reply.body());
        } catch (IOException e) {
            // 非 JSON 响应：状态码仍是权威；正文不进入异常。
            return mapper.createObjectNode();
        }
    }

    private String apiUrl(String path) {
        String host = properties.getVideo().getApiHost();
        if (host == null || host.isBlank()) {
            throw new MediaProviderUnavailableException("video provider host is not configured");
        }
        return host.endsWith("/") ? host.substring(0, host.length() - 1) + path : host + path;
    }

    private static boolean definitive(int status) {
        return status >= 400 && status < 500;
    }

    private static String codeFor(int status) {
        return switch (status) {
            case 401, 403 -> "PROVIDER_AUTH_FAILED";
            case 429 -> "PROVIDER_RATE_LIMITED";
            default -> "PROVIDER_REJECTED";
        };
    }

    private static String providerMessage(JsonNode root, int status) {
        String message = text(root.path("message"));
        if (message == null) {
            message = text(root.path("output").path("message"));
        }
        if (message == null) {
            message = text(root.path("output").path("fail_reason"));
        }
        return message == null ? "提供方拒绝了请求（status=" + status + "）" : sanitize(message);
    }

    private static String normalizeStatus(String raw) {
        if (raw == null) {
            return "UNKNOWN";
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "PENDING", "RUNNING", "SUCCEEDED", "FAILED" -> value;
            case "CANCELED", "CANCELLED" -> "CANCELED";
            default -> "UNKNOWN";
        };
    }

    private static String firstResultUrl(JsonNode output) {
        String direct = text(output.path("video_url"));
        if (direct != null) {
            return direct;
        }
        // 兼容 results[].url 形状（部分万相接口按 results 返回）。
        JsonNode results = output.path("results");
        if (!results.isArray() || results.isEmpty()) {
            return null;
        }
        for (JsonNode result : results) {
            String url = text(result.path("url"));
            if (url != null) {
                return url;
            }
        }
        return null;
    }

    private static Map<String, Object> usage(JsonNode usage) {
        if (usage == null || usage.isMissingNode() || usage.isNull() || !usage.isObject()) {
            return null;
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        usage.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value.isIntegralNumber()) {
                facts.put(entry.getKey(), value.longValue());
            } else if (value.isTextual()) {
                facts.put(entry.getKey(), value.textValue());
            }
        });
        return facts.isEmpty() ? null : facts;
    }

    private URI requireAllowedResultUri(String providerUrl) {
        URI uri;
        try {
            uri = new URI(providerUrl == null ? "" : providerUrl.trim());
        } catch (URISyntaxException e) {
            throw new MediaProviderRejectedException("PROVIDER_RESULT_URL_INVALID", "结果地址不合法");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new MediaProviderRejectedException("PROVIDER_RESULT_URL_INVALID", "结果地址不被允许");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        List<String> allowed = properties.getVideo().allowedResultHosts();
        boolean matched = allowed.stream().anyMatch(suffix -> host.equals(suffix) || host.endsWith("." + suffix));
        if (!matched) {
            throw new MediaProviderRejectedException("PROVIDER_RESULT_URL_INVALID", "结果地址不在白名单内");
        }
        return uri;
    }

    private static String encodePath(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new MediaProviderRejectedException("PROVIDER_REQUEST_INVALID", field + " 不能为空");
        }
        return value;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    /** 脱敏有界：去控制字符、截断；provider 文案可能回显请求参数，不整段落库。 */
    static String sanitize(String message) {
        if (message == null) {
            return null;
        }
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < message.length() && clean.length() < MAX_ERROR_MESSAGE; i++) {
            char c = message.charAt(i);
            clean.append(Character.isISOControl(c) ? ' ' : c);
        }
        return clean.toString();
    }
}
