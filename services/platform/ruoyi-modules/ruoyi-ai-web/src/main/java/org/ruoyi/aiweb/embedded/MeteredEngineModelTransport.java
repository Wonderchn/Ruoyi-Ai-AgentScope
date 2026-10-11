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

package org.ruoyi.aiweb.embedded;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.runtime.usage.EgressPolicy;
import com.nageoffer.ai.ragent.runtime.usage.ProviderSpendEnvelope;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 引擎模型外发的计量传输（ENGINE-CHAT-ACCOUNTING 切片，2026-10-11 T0；复核建议收敛项）。
 *
 * <p><b>背景。</b>引擎面 agent chat 用 AgentScope 自有的 OpenAIChatModel/HTTP 客户端，
 * 此前**不经** {@link EgressPolicy} 与 {@link ProviderSpendEnvelope}（闭包 javadoc 的
 * 「egress（R2 登记）」边界；S1b 首跑实证：聊天成功但 ai_provider_spend/ai_model_call
 * 两表 0 行）。本类按复核建议收敛：把外发套进与 p2 {@code RealChatGateway} 同口径的信封。
 *
 * <p><b>口径（镜像 p2，不做新语义）。</b>
 * <ul>
 *   <li>发送前：{@code egress.requireAllowed(provider)}；拒绝即**不出站**（流式路径以
 *       {@code Flux.error} 落地，保持 fail-closed）；</li>
 *   <li>预占：{@code spend.reserve(provider, model, bodyBytes, maxOutputTokens)}——
 *       maxTokens 从请求体 {@code "max_tokens"} 嗅探、缺省 2048（口径登记）；信封自身的
 *       启用/预算/供应商白名单拒绝照原样上抛（不吞、不降级）；</li>
 *   <li>结算：流式逐行嗅探首个 {@code "id"} 与末个 {@code "usage"} 对象，
 *       完成/错误/取消三态各结算一次（恰一次，AtomicBoolean）；同步 {@code execute}
 *       从响应体同理结算；usage 缺失时按契约以状态说明结算
 *       （{@code received(callId, id, {status/error/cancelled})}，与 p2 失败分支同型）。</li>
 * </ul>
 *
 * <p><b>不做。</b>不改 AgentScope 任何类（包装 {@link HttpTransport} 是 Builder 公开面）；
 * 不强制引擎请求体开启 {@code include_usage}（未开时按"usage 缺失"结算——精确 usage 的
 * 全链口径单列后续裁决）。
 */
final class MeteredEngineModelTransport implements HttpTransport {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MAX_TOKENS_PATTERN = Pattern.compile("\"max_tokens\"\\s*:\\s*(\\d+)");
    private static final Pattern USAGE_KEY_PATTERN = Pattern.compile("\"usage\"\\s*:\\s*\\{");
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 2048;

    private final HttpTransport delegate;
    private final EgressPolicy egress;
    private final ProviderSpendEnvelope spend;
    private final String providerId;
    private final String modelId;

    MeteredEngineModelTransport(HttpTransport delegate, EgressPolicy egress, ProviderSpendEnvelope spend,
                                String providerId, String modelId) {
        this.delegate = delegate;
        this.egress = egress;
        this.spend = spend;
        this.providerId = providerId;
        this.modelId = modelId;
    }

    @Override
    public HttpResponse execute(HttpRequest request) throws HttpTransportException {
        String spendId = preflight(request);
        try {
            HttpResponse response = delegate.execute(request);
            settle(spendId, firstMatch(ID_PATTERN, response.getBody()),
                    usageOf(response.getBody()), null);
            return response;
        } catch (HttpTransportException e) {
            settle(spendId, null, null, "transport-error:" + e.getClass().getSimpleName());
            throw e;
        }
    }

    @Override
    public Flux<String> stream(HttpRequest request) {
        String spendId;
        try {
            spendId = preflight(request);
        } catch (RuntimeException e) {
            // 校验/预占失败：不出站（fail-closed），错误沿引擎既有错误路径上行
            return Flux.error(e);
        }
        AtomicBoolean settled = new AtomicBoolean(false);
        StringBuilder providerRequestId = new StringBuilder();
        String[] usage = new String[1];
        return delegate.stream(request)
                .doOnNext(line -> {
                    if (providerRequestId.length() == 0) {
                        String id = firstMatch(ID_PATTERN, line);
                        if (id != null) {
                            providerRequestId.append(id);
                        }
                    }
                    String found = extractBalancedObject(line, USAGE_KEY_PATTERN);
                    if (found != null) {
                        usage[0] = found;
                    }
                })
                .doOnComplete(() -> settleOnce(settled, spendId, idOrNull(providerRequestId), parse(usage[0]), null))
                .doOnError(e -> settleOnce(settled, spendId, idOrNull(providerRequestId), parse(usage[0]),
                        "error:" + e.getClass().getSimpleName()))
                .doOnCancel(() -> settleOnce(settled, spendId, idOrNull(providerRequestId), parse(usage[0]),
                        "cancelled"));
    }

    @Override
    public void close() {
        delegate.close();
    }

    /** egress 校验 + 预占；任何拒绝按原异常上抛（不吞、不改形）。 */
    private String preflight(HttpRequest request) {
        egress.requireAllowed(providerId);
        String body = request.getBody();
        long bytes = body == null ? 0 : body.getBytes(StandardCharsets.UTF_8).length;
        int maxTokens = DEFAULT_MAX_OUTPUT_TOKENS;
        String sniffed = firstMatch(MAX_TOKENS_PATTERN, body);
        if (sniffed != null) {
            try {
                maxTokens = Integer.parseInt(sniffed);
            } catch (NumberFormatException ignored) {
                // 超界交给信封侧校验（estimate 对 >8192 会拒绝）
                maxTokens = DEFAULT_MAX_OUTPUT_TOKENS;
            }
        }
        return spend.reserve(providerId, modelId, bytes, maxTokens);
    }

    private void settleOnce(AtomicBoolean settled, String spendId, String providerRequestId,
                            Map<String, Object> usage, String statusNote) {
        if (settled.compareAndSet(false, true)) {
            settle(spendId, providerRequestId, usage, statusNote);
        }
    }

    private void settle(String spendId, String providerRequestId, Map<String, Object> usage, String statusNote) {
        if (spendId == null) {
            return;
        }
        Map<String, Object> payload = usage;
        if (payload == null) {
            payload = new LinkedHashMap<>();
            payload.put("status", statusNote == null ? "completed-without-usage" : statusNote);
        }
        spend.received(spendId, providerRequestId, payload);
    }

    private static String idOrNull(StringBuilder providerRequestId) {
        return providerRequestId.length() == 0 ? null : providerRequestId.toString();
    }

    private static String firstMatch(Pattern pattern, String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 从行内抽取 {@code "usage":{...}} 的完整对象文本（花括号配平）；无则 null。 */
    private static String extractBalancedObject(String line, Pattern keyPattern) {
        if (line == null) {
            return null;
        }
        Matcher matcher = keyPattern.matcher(line);
        if (!matcher.find()) {
            return null;
        }
        int start = matcher.end() - 1;
        int depth = 0;
        for (int i = start; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return line.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static Map<String, Object> usageOf(String body) {
        String usage = extractBalancedObject(body, USAGE_KEY_PATTERN);
        return parse(usage);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String usageJson) {
        if (usageJson == null) {
            return null;
        }
        try {
            return JSON.readValue(usageJson, Map.class);
        } catch (Exception e) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("status", "usage-unparsable");
            return fallback;
        }
    }
}
