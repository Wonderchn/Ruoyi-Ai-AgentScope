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

package com.nageoffer.ai.ragent.media.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.media.config.MediaProperties;
import org.ruoyi.common.chat.entity.image.ImageContext;
import org.ruoyi.common.chat.entity.media.MediaGenerationResponse;
import org.ruoyi.common.chat.service.image.IImageGenerationService;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 通义万相（DashScope 文生图）图片适配器：旧 {@code TongYiWanxImageServiceImpl} 的
 * 内嵌结构窄移植（{@code git show 89dce18:.../TongYiWanxImageServiceImpl.java}）。
 *
 * <p><b>协议。</b>原实现用 DashScope SDK 的同步 {@code ImageSynthesis.call}；内嵌结构不引入
 * DashScope SDK（POM 不新增该依赖），直接按同一公开协议调用：
 * <ul>
 *   <li>提交：{@code POST /api/v1/services/aigc/text2image/image-synthesis}，
 *       头 {@code X-DashScope-Async: enable}，返回 {@code output.task_id}；</li>
 *   <li>轮询：{@code GET /api/v1/tasks/{taskId}}，{@code output.task_status}。</li>
 * </ul>
 *
 * <p><b>为什么异步。</b>F19:1 要求提交与查询关联同一 requestId、重复查询不重复提交；
 * 异步任务 ID 就是服务端任务权威里的 {@code provider_task_id}。同步 {@link #generateImage}
 * 仅供接口兼容（内部使用），不作为前端通道。
 *
 * <p><b>凭据。</b>{@code apiKey} 只从配置读取；本类不打印、不回显、不落盘。401/403 只映射为
 * 明确的 {@code PROVIDER_AUTH_FAILED}，响应正文不进入异常消息。
 */
public class TongYiWanxImageGenerationService implements IImageGenerationService, MediaImageProvider {

    private static final String SUBMIT_PATH = "/api/v1/services/aigc/text2image/image-synthesis";
    private static final String TASK_PATH = "/api/v1/tasks/";
    private static final int MAX_ERROR_MESSAGE = 240;

    private final MediaProperties properties;
    private final WanxHttpTransport transport;
    private final ObjectMapper mapper;

    public TongYiWanxImageGenerationService(MediaProperties properties, WanxHttpTransport transport,
                                            ObjectMapper mapper) {
        this.properties = properties;
        this.transport = transport;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ provider port

    @Override
    public String providerName() {
        return properties.getImage().getProvider();
    }

    @Override
    public String submit(MediaImageRequest request) {
        MediaProperties.Image image = properties.getImage();
        requireConfigured(image);
        String model = requireText(request.model(), "model");
        String prompt = requireText(request.prompt(), "prompt");
        String size = request.size() == null || request.size().isBlank()
                ? image.getDefaultSize() : request.size();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("input", Map.of("prompt", prompt));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("size", size);
        parameters.put("n", 1);
        if (request.seed() != null) {
            parameters.put("seed", request.seed());
        }
        payload.put("parameters", parameters);
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(payload);
        } catch (IOException e) {
            throw new MediaProviderRejectedException("PROVIDER_REQUEST_INVALID", "无法编码图片生成请求");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + image.getApiKey());
        headers.put("Content-Type", "application/json");
        headers.put("X-DashScope-Async", "enable");
        WanxHttpTransport.Reply reply = send("POST", apiUrl(SUBMIT_PATH), headers, body, image);
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
    public MediaImagePoll poll(String providerTaskId) {
        MediaProperties.Image image = properties.getImage();
        requireConfigured(image);
        String taskId = requireText(providerTaskId, "providerTaskId");
        Map<String, String> headers = Map.of("Authorization", "Bearer " + image.getApiKey());
        WanxHttpTransport.Reply reply = send("GET", apiUrl(TASK_PATH + encodePath(taskId)), headers, null, image);
        JsonNode root = parse(reply);
        if (reply.status() >= 200 && reply.status() < 300) {
            JsonNode output = root.path("output");
            String status = normalizeStatus(text(output.path("task_status")));
            String imageUrl = firstResultUrl(output);
            String providerRequestId = text(root.path("request_id"));
            Map<String, Object> usage = usage(root.path("usage"));
            if ("SUCCEEDED".equals(status) && imageUrl == null) {
                // 提供方称成功但无结果地址：记为未知（可能已计费），不自动重发。
                return new MediaImagePoll("UNKNOWN", null, "PROVIDER_RESULT_MISSING",
                        "提供方未返回图片结果地址", providerRequestId, usage);
            }
            String message = text(output.path("message"));
            if (message == null) {
                message = text(output.path("fail_reason"));
            }
            return new MediaImagePoll(status, imageUrl,
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
        long limit = Math.min(Math.max(1, maxBytes), properties.getImage().getMaxBytes());
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
        if (!contentType.startsWith("image/")) {
            throw new MediaProviderRejectedException("PROVIDER_RESULT_INVALID", "结果内容不是图片");
        }
        if (reply.body() == null || reply.body().length == 0) {
            throw new MediaProviderRejectedException("PROVIDER_RESULT_INVALID", "结果内容为空");
        }
        if (reply.body().length > limit) {
            throw new MediaProviderRejectedException("PROVIDER_RESULT_TOO_LARGE", "结果超过大小上限");
        }
        return reply.body();
    }

    // ------------------------------------------------------------------ IImageGenerationService

    /**
     * 同步兼容路径（内部使用，不对外暴露）：提交后有界轮询到终态，返回 provider 结果 URL。
     *
     * <p>前端不使用该路径——结果 URL 是 provider 裸地址，只允许服务端在私有化存储时消费。
     */
    @Override
    public String generateImage(ImageContext imageContext) {
        if (imageContext == null || imageContext.getChatModelVo() == null) {
            throw new MediaProviderRejectedException("PROVIDER_REQUEST_INVALID", "图片上下文不能为空");
        }
        MediaImageRequest request = new MediaImageRequest(imageContext.getChatModelVo().getModelName(),
                imageContext.getPrompt(), imageContext.getSize(), imageContext.getSeed());
        String taskId = submit(request);
        long deadline = System.nanoTime()
                + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(properties.getImage().getPollTimeoutMs());
        while (System.nanoTime() < deadline) {
            MediaImagePoll poll = poll(taskId);
            if ("SUCCEEDED".equals(poll.status())) {
                return poll.imageUrl();
            }
            if ("FAILED".equals(poll.status()) || "CANCELED".equals(poll.status())
                    || "UNKNOWN".equals(poll.status())) {
                throw new MediaProviderRejectedException(
                        poll.errorCode() == null ? "PROVIDER_FAILED" : poll.errorCode(),
                        poll.message() == null ? "图片生成失败" : poll.message());
            }
            try {
                Thread.sleep(Math.max(1, properties.getImage().getPollIntervalMs()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MediaProviderUnavailableException("图片生成等待被中断");
            }
        }
        throw new MediaProviderUnavailableException("图片生成轮询超时");
    }

    @Override
    public MediaGenerationResponse startImageGeneration(ImageContext imageContext) {
        if (imageContext == null || imageContext.getChatModelVo() == null) {
            throw new MediaProviderRejectedException("PROVIDER_REQUEST_INVALID", "图片上下文不能为空");
        }
        String taskId = submit(new MediaImageRequest(imageContext.getChatModelVo().getModelName(),
                imageContext.getPrompt(), imageContext.getSize(), imageContext.getSeed()));
        return MediaGenerationResponse.builder()
                .type("image")
                .id(taskId)
                .status("pending")
                .build();
    }

    @Override
    public String getProviderName() {
        return providerName();
    }

    // ------------------------------------------------------------------ helpers

    private void requireConfigured(MediaProperties.Image image) {
        if (!image.isEnabled()) {
            throw new MediaProviderUnavailableException("image provider is disabled");
        }
        if (image.getApiKey() == null || image.getApiKey().isBlank()) {
            // 不打印/不回显 key；只说明"未配置"。
            throw new MediaProviderUnavailableException("image provider credential is not configured");
        }
    }

    private WanxHttpTransport.Reply send(String method, String url, Map<String, String> headers,
                                         byte[] body, MediaProperties.Image image) {
        try {
            return transport.send(method, url, headers, body, image.getMaxBytes());
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
        String host = properties.getImage().getApiHost();
        if (host == null || host.isBlank()) {
            throw new MediaProviderUnavailableException("image provider host is not configured");
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
        List<String> allowed = properties.getImage().allowedResultHosts();
        boolean matched = allowed.stream().anyMatch(suffix ->
                host.equals(suffix) || host.endsWith("." + suffix));
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
