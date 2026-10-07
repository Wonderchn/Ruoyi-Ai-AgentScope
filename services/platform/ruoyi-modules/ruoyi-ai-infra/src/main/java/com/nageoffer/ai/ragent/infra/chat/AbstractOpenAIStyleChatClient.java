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
import org.springframework.beans.factory.annotation.Qualifier;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenAI 兼容协议 ChatClient 抽象基类
 *
 * <p><b>为什么四个协作者全部按名字注入（F-3）。</b>
 * 这些字段原先都是裸 {@code @Autowired}（按类型）。在<b>独立 AI 应用</b>里这没问题：
 * 那边 {@code Executor} / {@code OkHttpClient} 各自只有一个候选。但<b>内嵌形态</b>
 * （platform 单上下文同时装平台侧与 AI 侧）里类型级注入立刻不成立：
 * <ul>
 *   <li>{@code Executor}：候选有 {@code platform} 的
 *       {@code ThreadPoolConfig}（{@code @Primary scheduledExecutorService}、
 *       {@code @Primary mainExecutor}、{@code knowledgeParseExecutor}）、Spring Boot 的
 *       {@code applicationTaskExecutor}、以及本链的 {@code modelStreamExecutor} ——
 *       <b>两个 {@code @Primary} 直接让容器以 "more than one 'primary' bean found"
 *       拒绝创建 {@code deepSeekChatClient}，整个应用 20 秒内退出</b>
 *       （T8 在真环境 WP-038 阶段 2 实测）。</li>
 *   <li>{@code OkHttpClient}：候选有 {@code syncHttpClient} 与
 *       {@code @Primary streamingHttpClient}。这一条<b>不报错、更危险</b>：
 *       {@code @Primary} 让"按类型"静默解析到<b>无读超时/无调用超时</b>的流式客户端，
 *       于是同步调用会拿着一个永不超时的客户端 —— 是一处沉默的错配，而不是启动失败。</li>
 * </ul>
 *
 * <p><b>为什么在注入点加限定符，而不是摘掉 platform 那两个 {@code @Primary}。</b>
 * 那两个 bean 有 platform 自己的注入方（{@code AsyncTaskExecutor}/{@code ScheduledExecutorService}
 * 类型的注入点靠 {@code @Primary} 解析），摘掉会波及平台侧；注入点加限定符语义最明确、
 * 改动面最小，也与本仓既有做法一致（{@code RoutingVlmService:63} 与
 * {@code AiEmbeddedModelConfiguration:291-360} 都用 {@code @Qualifier("syncHttpClient")}）。
 *
 * <p>{@link RagStreamTraceSupport} <b>刻意不加</b>限定符：内嵌上下文里它只有一个候选
 * （{@code AiEmbeddedModelConfiguration:206}），而独立 AI 应用里是实现类
 * {@code RagStreamTraceSupportImpl}（bean 名与这里不同）；按名字注入会在独立应用里
 * 找不到 bean。没有歧义就不加约束。
 */
@Slf4j
public abstract class AbstractOpenAIStyleChatClient implements ChatClient {

    @Autowired
    @Qualifier("syncHttpClient")
    private OkHttpClient syncHttpClient;
    @Autowired
    @Qualifier("streamingHttpClient")
    private OkHttpClient streamingHttpClient;
    @Autowired
    @Qualifier("modelStreamExecutor")
    private Executor modelStreamExecutor;
    @Autowired
    private RagStreamTraceSupport streamTraceSupport;

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

        Call httpCall = resolveSyncClient(target.timeoutMs()).newCall(requestHttp);

        JsonObject respJson;
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

        return extractChatContent(respJson);
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

        Call call = streamingHttpClient.newCall(streamRequest);
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
                    cancelled -> doStream(call, wrappedCallback, cancelled, reasoningEnabled)
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

    private void doStream(Call call, StreamCallback callback, AtomicBoolean cancelled, boolean reasoningEnabled) {
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                String body = HttpResponseHelper.readBody(response.body());
                throw new ModelClientException(
                        provider() + " 流式请求失败: HTTP " + response.code() + " - " + body,
                        ModelClientErrorType.fromHttpStatus(response.code()),
                        response.code()
                );
            }
            ResponseBody body = response.body();
            BufferedSource source = body.source();
            boolean completed = false;
            while (!cancelled.get()) {
                String line = source.readUtf8Line();
                if (line == null) {
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }
                try {
                    OpenAIStyleSseParser.ParsedEvent event = OpenAIStyleSseParser.parseLine(line, gson, reasoningEnabled);
                    if (event.hasReasoning()) {
                        callback.onThinking(event.reasoning());
                    }
                    if (event.hasContent()) {
                        callback.onContent(event.content());
                    }
                    if (event.completed()) {
                        callback.onComplete();
                        completed = true;
                        break;
                    }
                } catch (Exception parseEx) {
                    log.warn("{} 流式响应解析失败: line={}", provider(), line, parseEx);
                }
            }
            if (cancelled.get()) {
                log.info("{} 流式响应已被取消", provider());
                return;
            }
            if (!completed) {
                throw new ModelClientException(provider() + " 流式响应异常结束", ModelClientErrorType.INVALID_RESPONSE, null);
            }
        } catch (Exception e) {
            if (!cancelled.get()) {
                callback.onError(e);
            } else {
                log.info("{} 流式响应取消期间产生异常（可忽略）: {}", provider(), e.getMessage());
            }
        }
    }

    // ==================== 公共构建方法 ====================

    protected JsonObject buildRequestBody(ChatRequest request, ModelTarget target, boolean stream) {
        JsonObject body = new JsonObject();
        body.addProperty("model", HttpResponseHelper.requireModel(target, provider()));
        if (stream) {
            body.addProperty("stream", true);
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
        if (request.getMaxTokens() != null) {
            body.addProperty("max_tokens", request.getMaxTokens());
        }

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
