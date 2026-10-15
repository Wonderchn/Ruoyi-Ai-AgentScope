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
 *   <li>生命周期（R17-B2）：流式为 {@code Flux.defer}，每次订阅独立 spendId/结算门，
 *       未订阅零校验零预占零出站；{@code delegate.stream} 同步抛错也在订阅内结算；
 *       {@code execute} 的普通 RuntimeException 与 HttpTransportException 同口径先记
 *       错误/未知事实再重抛；失败路径记账自身异常以 suppressed 保存到原异常（不吞、不换主）。</li>
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
            settleAfterFailure(spendId, e, "transport-error:" + e.getClass().getSimpleName());
            throw e;
        } catch (RuntimeException e) {
            // 普通运行异常（连接/解析/组装等）同样必须记账：本次预占记错误/未知事实后原样重抛
            settleAfterFailure(spendId, e, "error:" + e.getClass().getSimpleName());
            throw e;
        }
    }

    @Override
    public Flux<String> stream(HttpRequest request) {
        // 每次订阅都是独立调用生命周期：预占、delegate 构造、订阅与终止结算都发生在本次订阅内
        return Flux.defer(() -> {
            String spendId;
            try {
                spendId = preflight(request);
            } catch (RuntimeException e) {
                // 校验/预占失败：不出站（fail-closed），错误在订阅内沿引擎既有错误路径上行
                return Flux.error(e);
            }
            AtomicBoolean settled = new AtomicBoolean(false);
            StringBuilder providerRequestId = new StringBuilder();
            String[] usage = new String[1];
            Flux<String> upstream;
            try {
                upstream = delegate.stream(request);
            } catch (RuntimeException e) {
                // delegate 同步抛错：转为订阅内 onError，并沿用原结算门恰记一次错误事实
                settleAfterFailure(settled, spendId, null, null, "error:" + e.getClass().getSimpleName(), e);
                return Flux.error(e);
            }
            return upstream
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
                    .doOnError(e -> settleAfterFailure(settled, spendId, idOrNull(providerRequestId), parse(usage[0]),
                            "error:" + e.getClass().getSimpleName(), e))
                    .doOnCancel(() -> settleOnce(settled, spendId, idOrNull(providerRequestId), parse(usage[0]),
                            "cancelled"));
        });
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

    /** execute 失败路径：先记账再重抛原异常；记账自身异常以 suppressed 保存（不吞、不换主异常）。 */
    private void settleAfterFailure(String spendId, Throwable primary, String statusNote) {
        try {
            settle(spendId, null, null, statusNote);
        } catch (RuntimeException settleFailure) {
            primary.addSuppressed(settleFailure);
        }
    }

    /** stream 失败路径：与 complete/cancel 共用同一恰一次结算门；记账自身异常以 suppressed 保存到原异常。 */
    private void settleAfterFailure(AtomicBoolean settled, String spendId, String providerRequestId,
                                    Map<String, Object> usage, String statusNote, Throwable primary) {
        try {
            settleOnce(settled, spendId, providerRequestId, usage, statusNote);
        } catch (RuntimeException settleFailure) {
            primary.addSuppressed(settleFailure);
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
