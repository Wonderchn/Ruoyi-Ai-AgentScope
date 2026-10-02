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

package org.ruoyi.aiintegration.web;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * AI 网关转发客户端（U06/P1.2c）：platform → AI 内部 API 的受控 HTTP 转发。
 *
 * <p>与 AI 侧 {@code PlatformAuthorizationClient} 同款技术栈（JDK
 * {@link HttpClient}），关键约束：
 * <ul>
 *   <li><b>不跟随重定向</b>（{@code Redirect.NEVER}）：内部 API 的 3xx 属异常状态；</li>
 *   <li>单请求超时默认 2 秒（{@code ai.integration.forward-timeout-millis}）；
 *       连接失败/超时/中断一律判为上游不可用；</li>
 *   <li>状态契约：只透传 2xx 与 4xx；3xx（重定向）与 5xx（AI 内部错误直接外泄）
 *       视为状态异常 → 503 语义，不透传；</li>
 *   <li>响应体校验（恶意响应 fail-closed → 503 语义）：非空 body 必须是<b>单个
 *       JSON 对象</b>——重复键、trailing token、非对象根、坏 JSON 一律拒绝；
 *       空体允许（如 204）。</li>
 * </ul>
 *
 * <p>所有拒绝统一抛 {@link UpstreamUnavailableException}，由调用方映射为
 * 503 {@code AUTHORIZATION_UNAVAILABLE} 响应，不向客户端泄露上游细节。
 */
public class AiGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(AiGatewayClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper responseMapper;
    private final int timeoutMillis;

    public AiGatewayClient(int timeoutMillis) {
        this(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                // 显式不跟随重定向：内部 API 出现 3xx 属于异常状态，而不是要跟的指令
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(), timeoutMillis);
    }

    /** 测试注入口：注入受控的 {@link HttpClient} 替身。 */
    public AiGatewayClient(HttpClient httpClient, int timeoutMillis) {
        this.httpClient = httpClient;
        this.timeoutMillis = timeoutMillis;
        this.responseMapper = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** 上游不可用/恶意响应的受控异常（调用方一律映射 503，不透出原因）。 */
    public static final class UpstreamUnavailableException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public UpstreamUnavailableException(String reason) {
            super(reason);
        }
    }

    /**
     * 转发请求。
     *
     * @param method  HTTP 方法（白名单校验后的固定集合）
     * @param uri     目标 URI（AI 基地址 + 内部路径，含 query）
     * @param headers 已净化的转发头（内部身份头/逐跳头已剥除，Authorization 已换成委托凭证）
     * @param body    请求体（GET/DELETE 为 {@code null}）
     */
    public record ForwardRequest(String method, URI uri, Map<String, String> headers, byte[] body) {
    }

    /**
     * 转发结果。
     *
     * @param status AI 响应状态码（已通过状态契约）
     * @param body   AI 响应体（已通过恶意响应校验；可为空串）
     */
    public record ForwardResponse(int status, String body) {
    }

    public record ByteResponse(int status,byte[] bytes,String contentType,String contentRange,String permitId,String operationId) { }

    public ByteResponse forwardBytes(ForwardRequest request) {
        try {
            HttpRequest.Builder builder=HttpRequest.newBuilder(request.uri()).timeout(Duration.ofMillis(timeoutMillis));
            if("GET".equals(request.method())){builder.GET();}
            else if("POST".equals(request.method())){builder.POST(request.body()==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(request.body()));}
            else {throw new UpstreamUnavailableException("unsupported delivery method");}
            request.headers().forEach(builder::header);
            var response=httpClient.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try(var source=response.body()){bytes=source.readNBytes(4*1024*1024+1);}
            if(bytes.length>4*1024*1024){throw new UpstreamUnavailableException("upstream body limit exceeded");}
            int status=response.statusCode();
            String permit=response.headers().firstValue("X-AI-Delivery-Permit").orElse("");
            String operation=response.headers().firstValue("X-AI-Delivery-Operation").orElse("");
            String type=response.headers().firstValue("Content-Type").orElse("application/octet-stream");
            String range=response.headers().firstValue("Content-Range").orElse("");
            if(status==200 || status==206){
                if(type.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")){
                    requireSingleJsonObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8),status);
                }
                if(!permit.matches("[0-9a-f-]{36}") || !operation.matches("[0-9a-f-]{36}")
                        || (status==206 && !range.matches("bytes [0-9]+-[0-9]+/[0-9]+"))){
                    throw new UpstreamUnavailableException("delivery receipt missing");
                }
            } else {
                if(!((status>=400 && status<500)||status==503)){throw new UpstreamUnavailableException("abnormal byte response");}
                requireSingleJsonObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8),status);
            }
            return new ByteResponse(status,bytes,type,range,permit,operation);
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new UpstreamUnavailableException("byte transfer interrupted");
        } catch(UpstreamUnavailableException e){throw e;
        } catch(Exception e){throw new UpstreamUnavailableException("byte transfer unavailable");}
    }

    public ForwardResponse forward(ForwardRequest request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                .timeout(Duration.ofMillis(timeoutMillis))
                .expectContinue(false);
        request.headers().forEach(builder::header);
        HttpRequest.BodyPublisher publisher = request.body() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(request.body());
        switch (request.method()) {
            case "GET" -> builder.GET();
            case "DELETE" -> builder.DELETE();
            case "POST" -> builder.POST(publisher);
            case "PUT" -> builder.PUT(publisher);
            default -> throw new UpstreamUnavailableException("unsupported method");
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamUnavailableException("forward interrupted");
        } catch (Exception e) {
            // 连接失败、超时等：不可用即不放行，也不向客户端泄露上游错误细节
            log.warn("ai gateway upstream unavailable type={}", e.getClass().getSimpleName());
            throw new UpstreamUnavailableException("upstream unreachable");
        }

        int status = response.statusCode();
        // 状态契约：2xx/4xx 透传；3xx（重定向异常）与 1xx/5xx 视为状态异常
        boolean passthrough = (status >= 200 && status < 300) || (status >= 400 && status < 500) || status == 503;
        if (!passthrough) {
            log.warn("ai gateway upstream abnormal status={}", status);
            throw new UpstreamUnavailableException("abnormal upstream status");
        }

        String body = response.body() == null ? "" : response.body();
        if (!body.isBlank()) {
            requireSingleJsonObject(body, status);
        }
        return new ForwardResponse(status, body);
    }

    /**
     * 恶意响应校验：非空 body 必须是单个 JSON 对象。
     * 重复键（{@code FAIL_ON_READING_DUP_TREE_KEY}）、trailing token
     * （{@code FAIL_ON_TRAILING_TOKENS}）、非对象根、坏 JSON 一律 503 语义。
     */
    private void requireSingleJsonObject(String body, int status) {
        JsonNode root;
        try {
            root = responseMapper.readTree(body);
        } catch (Exception e) {
            log.warn("ai gateway upstream malformed body reason={}", e.getClass().getSimpleName());
            throw new UpstreamUnavailableException("malformed upstream body");
        }
        if (root == null || !root.isObject()) {
            throw new UpstreamUnavailableException("non-object upstream body");
        }
        // 响应必须是本协议包络（code 为整数、与状态一致的数据由 AI 侧保证）；
        // 这里只强制"单对象 + 携带整数 code"的形状，防止把任意 JSON 原样透传给浏览器
        if (!root.path("code").isIntegralNumber()
                || (root.path("code").intValue() != status && !(status == 202 && root.path("code").intValue() == 200))) {
            throw new UpstreamUnavailableException("missing envelope code");
        }
    }
}
