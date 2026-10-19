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

package com.nageoffer.ai.ragent.media.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.media.config.MediaProperties;
import com.nageoffer.ai.ragent.media.image.MediaProviderRejectedException;
import com.nageoffer.ai.ragent.media.image.MediaProviderUnavailableException;
import com.nageoffer.ai.ragent.media.image.WanxHttpTransport;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * F19:0 语音合成适配器（DashScope qwen-tts HTTP 同步协议）。
 *
 * <p><b>为什么不是 CosyVoice。</b>CosyVoice 的非实时合成依赖 WebSocket；本切片按 brief 的
 * 优先级选择 qwen-tts 的 HTTP 同步端点，可离线可测（注入 {@link WanxHttpTransport}）。
 *
 * <p><b>协议。</b>{@code POST /api/v1/services/aigc/multimodal-generation/generation}：
 * <pre>
 * {"model":"qwen3-tts-flash","input":{"text":"...","voice":"Cherry"}}
 * </pre>
 * 成功响应 {@code output.audio.url}（24h 有效）或 {@code output.audio.data}（base64）；
 * {@code usage.characters} 为输入字符数（qwen-tts 按输入字符计费）。
 * 官方 HTTP 协议没有 format/speed 字段：控制面保留这两个参数（记录/重试/MIME 提示），
 * <b>不下发</b>未定义字段；当前模型返回 wav，交付的 MIME 以字节嗅探为准。
 *
 * <p><b>凭据。</b>{@code apiKey} 只从配置读取；不打印、不回显、不落盘。
 * 401/403 只映射为 {@code PROVIDER_AUTH_FAILED}，响应正文不进入异常消息。
 */
public class DashScopeAudioGenerationService implements MediaAudioProvider {

    private static final String GENERATION_PATH = "/api/v1/services/aigc/multimodal-generation/generation";
    private static final int MAX_ERROR_MESSAGE = 240;

    private final MediaProperties properties;
    private final WanxHttpTransport transport;
    private final ObjectMapper mapper;

    public DashScopeAudioGenerationService(MediaProperties properties, WanxHttpTransport transport,
                                           ObjectMapper mapper) {
        this.properties = properties;
        this.transport = transport;
        this.mapper = mapper;
    }

    @Override
    public String providerName() {
        return properties.getAudio().getProvider();
    }

    @Override
    public MediaAudioReceipt submit(MediaAudioRequest request) {
        MediaProperties.Audio audio = properties.getAudio();
        requireConfigured(audio);
        String model = requireText(request.model(), "model");
        String text = requireText(request.text(), "text");
        String voice = request.voice() == null || request.voice().isBlank()
                ? audio.getDefaultVoice() : request.voice().trim();
        if (voice == null || voice.isBlank()) {
            throw new MediaProviderRejectedException("PROVIDER_REQUEST_INVALID", "音色不能为空");
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("text", text);
        input.put("voice", voice);
        if (request.instructions() != null && !request.instructions().isBlank()) {
            // 仅 instruct 系列支持；普通模型会明确 4xx，映射为确定性失败。
            input.put("instructions", request.instructions().trim());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("input", input);
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(payload);
        } catch (IOException e) {
            throw new MediaProviderRejectedException("PROVIDER_REQUEST_INVALID", "无法编码语音合成请求");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + audio.getApiKey());
        headers.put("Content-Type", "application/json");
        WanxHttpTransport.Reply reply = send("POST", apiUrl(GENERATION_PATH), headers, body, audio);
        JsonNode root = parse(reply);
        if (reply.status() >= 200 && reply.status() < 300) {
            JsonNode audioNode = root.path("output").path("audio");
            String url = text(audioNode.path("url"));
            String data = text(audioNode.path("data"));
            if (url == null && data == null) {
                // 2xx 但没有音频结果：调用可能已经产生费用，按未知处理，绝不自动重发。
                throw new MediaProviderUnavailableException("provider returned no audio result");
            }
            String declaredFormat = request.format() == null || request.format().isBlank()
                    ? audio.getDefaultFormat() : request.format().trim().toLowerCase(Locale.ROOT);
            return new MediaAudioReceipt(text(root.path("request_id")), url, data, declaredFormat,
                    usage(root.path("usage")));
        }
        if (definitive(reply.status())) {
            throw new MediaProviderRejectedException(codeFor(reply.status()), providerMessage(root, reply.status()));
        }
        throw new MediaProviderUnavailableException("provider submit failed with status " + reply.status());
    }

    @Override
    public byte[] download(String providerUrl, long maxBytes, String requestedFormat) {
        URI uri = requireAllowedResultUri(providerUrl);
        long limit = Math.min(Math.max(1, maxBytes), properties.getAudio().getMaxBytes());
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
        boolean declaredPcm = "pcm".equalsIgnoreCase(requestedFormat);
        if (!contentType.startsWith("audio/") && !declaredPcm) {
            throw new MediaProviderRejectedException("PROVIDER_RESULT_INVALID", "结果内容不是音频");
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
     * 结果字节的 MIME 嗅探（wav/mp3/aac/ogg/flac；pcm 无文件头，按请求格式判定）。
     *
     * @throws MediaProviderRejectedException 无法识别的音频内容
     */
    @Override
    public String sniffMime(byte[] bytes, String requestedFormat) {
        if (bytes == null || bytes.length == 0) {
            throw new MediaProviderRejectedException("PROVIDER_RESULT_INVALID", "结果内容为空");
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E') {
            return "audio/wav";
        }
        if (bytes.length >= 3 && bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3') {
            return "audio/mpeg";
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xE0) == 0xE0) {
            return "aac".equalsIgnoreCase(requestedFormat) ? "audio/aac" : "audio/mpeg";
        }
        if (bytes.length >= 4 && bytes[0] == 'O' && bytes[1] == 'g' && bytes[2] == 'g' && bytes[3] == 'S') {
            return "audio/ogg";
        }
        if (bytes.length >= 4 && bytes[0] == 'f' && bytes[1] == 'L' && bytes[2] == 'a' && bytes[3] == 'C') {
            return "audio/flac";
        }
        if ("pcm".equalsIgnoreCase(requestedFormat)) {
            return "audio/pcm";
        }
        throw new MediaProviderRejectedException("PROVIDER_RESULT_INVALID", "结果内容不是可识别的音频");
    }

    // ------------------------------------------------------------------ helpers

    private void requireConfigured(MediaProperties.Audio audio) {
        if (!audio.isEnabled()) {
            throw new MediaProviderUnavailableException("audio provider is disabled");
        }
        if (audio.getApiKey() == null || audio.getApiKey().isBlank()) {
            // 不打印/不回显 key；只说明"未配置"。
            throw new MediaProviderUnavailableException("audio provider credential is not configured");
        }
        if (audio.getModelName() == null || audio.getModelName().isBlank()) {
            throw new MediaProviderUnavailableException("audio model is not configured");
        }
    }

    private WanxHttpTransport.Reply send(String method, String url, Map<String, String> headers,
                                         byte[] body, MediaProperties.Audio audio) {
        try {
            return transport.send(method, url, headers, body, audio.getMaxBytes());
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
        String host = properties.getAudio().getApiHost();
        if (host == null || host.isBlank()) {
            throw new MediaProviderUnavailableException("audio provider host is not configured");
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
        List<String> allowed = properties.getAudio().allowedResultHosts();
        boolean matched = allowed.stream().anyMatch(suffix -> host.equals(suffix) || host.endsWith("." + suffix));
        if (!matched) {
            throw new MediaProviderRejectedException("PROVIDER_RESULT_URL_INVALID", "结果地址不在白名单内");
        }
        return uri;
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
