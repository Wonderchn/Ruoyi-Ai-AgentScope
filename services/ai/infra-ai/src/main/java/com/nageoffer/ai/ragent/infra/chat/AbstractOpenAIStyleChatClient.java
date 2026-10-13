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

package com.nageoffer.ai.ragent.infra.chat;

import cn.hutool.core.collection.CollUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.trace.RagStreamTraceSupport;
import com.nageoffer.ai.ragent.framework.trace.RagStreamTraceSupport.StreamSpan;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.http.HttpMediaTypes;
import com.nageoffer.ai.ragent.infra.http.HttpResponseHelper;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.http.ModelUrlResolver;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenAI 兼容协议 ChatClient 抽象基类
 */
@Slf4j
public abstract class AbstractOpenAIStyleChatClient implements ChatClient {

    @Autowired
    private OkHttpClient syncHttpClient;
    @Autowired
    private OkHttpClient streamingHttpClient;
    @Autowired
    private Executor modelStreamExecutor;
    @Autowired
    private RagStreamTraceSupport streamTraceSupport;

    @Autowired(required = false)
    private ChatEgressPort chatEgressPort = ChatEgressPort.DENY;

    protected Gson gson = new Gson();

    /**
     * 按档位超时预算派生的同步客户端缓存（key=timeoutMs）
     * 档位超时值仅少数几种，派生客户端经 newBuilder 复用连接池/线程池，缓存后避免每次调用重建
     */
    private final Map<Long, OkHttpClient> syncClientByTimeout = new ConcurrentHashMap<>();

    // ==================== 子类钩子方法 ====================

    /**
     * 流式调用时是否启用 reasoning_content 解析，默认根据请求中的 thinking 标志决定
     */
    protected boolean isReasoningEnabledForStream(ChatRequest request) {
        return Boolean.TRUE.equals(request.getThinking());
    }

    /**
     * 提供商是否认识 enable_thinking
     * <p>
     * 该字段是 DashScope 系的私有扩展，OpenAI 兼容协议本身没有，发给不认识它的网关会被判为
     * unknown_parameter 直接 400，因此默认不发，由具体提供商声明
     */
    protected boolean supportsEnableThinkingParam() {
        return false;
    }

    /**
     * 子类可覆写此方法添加提供商特有的请求体字段
     * 默认实现：仅对认识该字段的提供商显式声明思考开关（Qwen3 系不显式关会默认开启思考）
     */
    protected void customizeRequestBody(JsonObject body, ChatRequest request) {
        if (supportsEnableThinkingParam()) {
            body.addProperty("enable_thinking", Boolean.TRUE.equals(request.getThinking()));
        }
    }

    /**
     * 是否要求提供商配置 API Key
     */
    protected boolean requiresApiKey() {
        return true;
    }

    // ==================== 模板方法：同步调用 ====================

    protected String doChat(ChatRequest request, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target, provider());
        if (requiresApiKey()) {
            HttpResponseHelper.requireApiKey(provider, provider());
        }

        JsonObject reqBody = buildRequestBody(request, target, false);
        Request requestHttp = newAuthorizedRequest(provider, target)
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .build();

        ChatEgressPort.Lease lease = authorize(requestHttp, reqBody);
        Call httpCall = resolveSyncClient(target.timeoutMs()).newBuilder()
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build().newCall(requestHttp);

        JsonObject respJson;
        lease.beforeSend();
        try (Response response = httpCall.execute()) {
            if (!response.isSuccessful()) {
                String body = HttpResponseHelper.readBody(response.body());
                log.warn("{} 同步请求失败: status={}, body={}", provider(), response.code(), body);
                throw new ModelClientException(
                        provider() + " 同步请求失败: HTTP " + response.code(),
                        ModelClientErrorType.fromHttpStatus(response.code()),
                        response.code()
                );
            }
            respJson = HttpResponseHelper.parseJson(response.body(), provider());
        } catch (IOException e) {
            throw new ModelClientException(
                    provider() + " 同步请求失败: " + e.getMessage(),
                    ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        String content = extractChatContent(respJson);
        lease.received(OpenAIStyleSseParser.providerId(respJson), OpenAIStyleSseParser.usage(respJson));
        return content;
    }

    /**
     * 取按档位超时预算派生的同步客户端；timeoutMs 为空时用基础客户端（走 HttpClientConfig 默认超时）
     * <p>
     * connect/write 沿用基础客户端（请求体小、连接建立无需占用整段预算），仅覆盖 read/call
     */
    private OkHttpClient resolveSyncClient(Long timeoutMs) {
        if (timeoutMs == null) {
            return syncHttpClient;
        }
        return syncClientByTimeout.computeIfAbsent(timeoutMs, ms -> syncHttpClient.newBuilder()
                .readTimeout(ms, TimeUnit.MILLISECONDS)
                .callTimeout(ms, TimeUnit.MILLISECONDS)
                .build());
    }

    // ==================== 模板方法：流式调用 ====================

    protected StreamCancellationHandle doStreamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target, provider());
        if (requiresApiKey()) {
            HttpResponseHelper.requireApiKey(provider, provider());
        }

        JsonObject reqBody = buildRequestBody(request, target, true);
        Request streamRequest = newAuthorizedRequest(provider, target)
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .addHeader("Accept", "text/event-stream")
                .build();

        ChatEgressPort.Lease lease = authorize(streamRequest, reqBody);
        Call call = streamingHttpClient.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .build().newCall(streamRequest);
        boolean reasoningEnabled = isReasoningEnabledForStream(request);

        // 在调用线程开 stream span，使后续 first-packet 子节点能正确归属父节点；
        // 该 span 由 SSE 终态（onComplete / onError）或 cancel 时收尾，记录真实端到端耗时
        StreamSpan span = streamTraceSupport.beginStreamNode(provider() + "-stream-chat", "LLM_PROVIDER");
        StreamSpanCallback wrappedCallback;
        try {
            wrappedCallback = new StreamSpanCallback(callback, span);
            StreamCancellationHandle inner = StreamAsyncExecutor.submit(
                    modelStreamExecutor,
                    call,
                    wrappedCallback,
                    cancelled -> doStream(call, wrappedCallback, cancelled, reasoningEnabled, lease)
            );
            return () -> {
                try {
                    inner.cancel();
                } finally {
                    wrappedCallback.onCancel();
                }
            };
        } finally {
            // 同步部分结束：把节点从当前线程的 NODE_STACK 弹出，避免污染兄弟节点的父节点链
            span.detach();
        }
    }

    private void doStream(Call call, StreamCallback callback, AtomicBoolean cancelled, boolean reasoningEnabled,
                          ChatEgressPort.Lease lease) {
        AtomicBoolean terminalCallback = new AtomicBoolean();
        try {
            if (cancelled.get()) { return; }
            lease.beforeSend();
            if (cancelled.get()) { return; }
            try (Response response = call.execute()) {
                if (!response.isSuccessful()) {
                    throw new ModelClientException(provider() + " stream HTTP " + response.code(),
                            ModelClientErrorType.fromHttpStatus(response.code()), response.code());
                }
                ResponseBody body = response.body();
                if (body == null) { throw new ModelClientException("stream body absent", ModelClientErrorType.INVALID_RESPONSE, null); }
                BufferedSource source = body.source();
                boolean finished = false, terminal = false;
                String providerId = null;
                Map<String,Object> usage = null;
                while (!cancelled.get()) {
                    String line = source.readUtf8Line();
                    if (line == null) { break; }
                    if (line.isBlank() || !line.trim().startsWith("data:")) { continue; }
                    OpenAIStyleSseParser.ParsedEvent event;
                    try { event = OpenAIStyleSseParser.parseLine(line, gson, reasoningEnabled); }
                    catch (RuntimeException invalid) { throw new ModelClientException("invalid stream event", ModelClientErrorType.INVALID_RESPONSE, null, invalid); }
                    if (event.providerId() != null) { providerId = event.providerId(); }
                    if (event.usage() != null) { usage = event.usage(); }
                    if (event.hasReasoning()) { callback.onThinking(event.reasoning()); }
                    if (event.hasContent()) { callback.onContent(event.content()); }
                    finished |= event.completed();
                    if (event.terminal()) { terminal = true; break; }
                }
                if (cancelled.get()) { return; }
                if (!terminal && !finished) { throw new ModelClientException("stream ended before completion", ModelClientErrorType.INVALID_RESPONSE, null); }
                if (providerId != null && usage != null) { lease.received(providerId, usage); }
                if (terminalCallback.compareAndSet(false, true)) { callback.onComplete(); }
            }
        } catch (Exception error) {
            if (ChatEgressDeniedException.find(error) != null) {
                // 安全拒绝是终止语义，不受取消影响（F-C2-8）：并发取消不得吞掉它，否则路由层
                // 收不到拒绝，会把拒绝误记成 provider 健康失败并触发下一候选（与
                // ModelRoutingExecutor 的 find(e) 守卫同形）；terminalCallback 的 CAS
                // 仍保证至多一次终态回调。
                if (terminalCallback.compareAndSet(false, true)) { callback.onError(error); }
            } else if (!cancelled.get() && terminalCallback.compareAndSet(false, true)) {
                callback.onError(error);
            }
        }
    }

    private ChatEgressPort.Lease authorize(Request httpRequest, JsonObject body) {
        return chatEgressPort.authorize(new ChatEgressPort.CallSpec(provider(),
                body.get("model").getAsString(), httpRequest.url().uri(),
                body.toString().getBytes(StandardCharsets.UTF_8).length, body.get("max_tokens").getAsInt()));
    }

    // ==================== 公共构建方法 ====================

    protected JsonObject buildRequestBody(ChatRequest request, ModelTarget target, boolean stream) {
        JsonObject body = new JsonObject();
        body.addProperty("model", HttpResponseHelper.requireModel(target, provider()));
        if (stream) {
            body.addProperty("stream", true);
            JsonObject streamOptions = new JsonObject();
            streamOptions.addProperty("include_usage", true);
            body.add("stream_options", streamOptions);
        }

        body.add("messages", buildMessages(request));

        if (request.getTemperature() != null) {
            body.addProperty("temperature", request.getTemperature());
        }
        if (request.getTopP() != null) {
            body.addProperty("top_p", request.getTopP());
        }
        if (request.getTopK() != null) {
            body.addProperty("top_k", request.getTopK());
        }
        int outputTokens = request.getMaxTokens() == null ? 2048 : request.getMaxTokens();
        if (outputTokens < 1 || outputTokens > 8192) {
            throw new ChatEgressDeniedException("invalid chat output limit");
        }
        body.addProperty("max_tokens", outputTokens);

        customizeRequestBody(body, request);
        return body;
    }

    private JsonArray buildMessages(ChatRequest request) {
        JsonArray arr = new JsonArray();
        List<ChatMessage> messages = request.getMessages();
        if (CollUtil.isNotEmpty(messages)) {
            for (ChatMessage m : messages) {
                JsonObject msg = new JsonObject();
                msg.addProperty("role", toOpenAiRole(m.getRole()));
                msg.addProperty("content", m.getContent());
                arr.add(msg);
            }
        }
        return arr;
    }

    private String toOpenAiRole(ChatMessage.Role role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
        };
    }

    private Request.Builder newAuthorizedRequest(AIModelProperties.ProviderConfig provider, ModelTarget target) {
        Request.Builder builder = new Request.Builder()
                .url(ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.CHAT));
        if (requiresApiKey()) {
            builder.addHeader("Authorization", "Bearer " + provider.getApiKey());
        }
        return builder;
    }

    private String extractChatContent(JsonObject respJson) {
        if (respJson == null || !respJson.has("choices")) {
            throw new ModelClientException(provider() + " 响应缺少 choices", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonArray choices = respJson.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new ModelClientException(provider() + " 响应 choices 为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject choice0 = choices.get(0).getAsJsonObject();
        if (choice0 == null || !choice0.has("message")) {
            throw new ModelClientException(provider() + " 响应缺少 message", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject message = choice0.getAsJsonObject("message");
        if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
            throw new ModelClientException(provider() + " 响应缺少 content", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        String content = message.get("content").getAsString();
        if (content.isBlank()) {
            throw new ModelClientException(provider() + " 响应 content 为空白", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        return content;
    }
}
