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

        /**
         * 带原因的构造（诊断用）。
         *
         * <p><b>为什么需要它。</b>内层派发失败时，`LocalAiGatewayClient.dispatchTo` 原实现
         * 只抛 `new UpstreamUnavailableException("internal dispatch failed")` ——
         * **既不带 message 也不带 cause**，于是没有 `Caused by`、没有内层异常类型，
         * 任何人都无法从日志判断内层为什么失败。后果是同一个 503 现象被归因了三次
         * （引擎门控 / acl_epoch / 内层不是 bean），每次都只解释了一部分（T8 实测）。
         *
         * <p><b>对外不泄露。</b>本异常由调用方（网关）一律映射为固定的 503 包络，
         * message 与 cause **只进服务端日志**。因此这里保留完整原因不会扩大对外暴露面。
         *
         * <p>⚠️ 这是**加性**变更：单参构造保留，既有调用点行为不变。
         */
        public UpstreamUnavailableException(String reason, Throwable cause) {
            super(reason, cause);
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
    public record ForwardRequest(String method, URI uri, Map<String, String> headers, byte[] body,
                                 java.util.function.Supplier<java.io.InputStream> streamBody) {

        public ForwardRequest(String method, URI uri, Map<String, String> headers, byte[] body) {
            this(method, uri, headers, body, null);
        }
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

    // ------------------------------------------------------------------ 专用流式传输

    /** SSE 专用流：建连/空闲/总时长受限；不缓冲全量，逐块 flush。 */
    public void forwardEventStream(ForwardRequest request, jakarta.servlet.http.HttpServletResponse response,
                                   int connectTimeoutMillis, int idleTimeoutMillis, int maxDurationMillis) {
        forwardEventStream(request,response,connectTimeoutMillis,idleTimeoutMillis,maxDurationMillis,null);
    }

    public record DeliveryAck(URI uri,String tenantId,String memberId,String serviceCredential) {}

    private void acknowledge(DeliveryAck delivery,String permit,String operation) {
        if(delivery==null || !validDeliveryId(permit) || !validDeliveryId(operation))
            throw new UpstreamUnavailableException("delivery identity missing");
        try {
            byte[] body=responseMapper.writeValueAsBytes(Map.of("tenantId",delivery.tenantId(),
                    "memberId",delivery.memberId(),"permitId",permit,"operationId",operation));
            var result=forward(new ForwardRequest("POST",delivery.uri(),Map.of("Content-Type","application/json",
                    "X-P04-Service-Credential",delivery.serviceCredential()),body));
            if(result.status()!=204 || result.body()!=null && !result.body().isBlank())
                throw new UpstreamUnavailableException("delivery acknowledgement unavailable");
        } catch(java.io.IOException e) {throw new UpstreamUnavailableException("delivery acknowledgement unavailable");}
    }

    private static boolean validDeliveryId(String value) {
        return value!=null && value.matches("[A-Za-z0-9_-]{1,128}");
    }

    void deliverFrame(byte[] bytes,jakarta.servlet.http.HttpServletResponse response,DeliveryAck delivery)
            throws java.io.IOException {
        String frame=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
        String permit=null,operation=null;
        StringBuilder publicFrame=new StringBuilder();
        boolean restricted=false;
        for(String line:frame.split("\n",-1)) {
            if(line.startsWith(": ai-delivery ")) {
                String[] ids=line.substring(14).trim().split(" ",-1);
                if(permit!=null || ids.length!=2 || !validDeliveryId(ids[0]) || !validDeliveryId(ids[1]))
                    throw new UpstreamUnavailableException("invalid delivery metadata");
                permit=ids[0]; operation=ids[1];
            } else {
                if(line.startsWith("data:") || line.startsWith("id:") || line.startsWith("event:")) restricted=true;
                publicFrame.append(line).append('\n');
            }
        }
        if(restricted && permit==null) throw new UpstreamUnavailableException("unprotected stream frame");
        if(permit==null && !frame.equals(": ping\n\n"))
            throw new UpstreamUnavailableException("unexpected unprotected stream frame");
        try {
            var out=response.getOutputStream();
            out.write(publicFrame.substring(0,publicFrame.length()-1).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
        } finally {
            // No subsequent application write can belong to this complete frame, including client abort.
            // Unknown/failed ACK is never retried automatically and leaves the permit ACTIVE.
            if(permit!=null) acknowledge(delivery,permit,operation);
        }
    }

    public void forwardEventStream(ForwardRequest request, jakarta.servlet.http.HttpServletResponse response,
                                   int connectTimeoutMillis,int idleTimeoutMillis,int maxDurationMillis,
                                   DeliveryAck delivery) {
        HttpClient streamClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        // 方法必须按 ForwardRequest 选（C13.3-3）：本方法此前硬写 .GET()，于是
        // `POST /agent/v1/chat/confirm`（审批确认，POST + JSON body + text/event-stream 响应）
        // 在 http 传输形态下永远只能以 GET 发出上游，上游按方法不匹配拒绝或空跑。
        // 内嵌 local 形态不走本方法（LocalAiGatewayClient 覆写了两个重载，且内层直调的
        // 请求方法继承外层原始请求），所以此前只有 http 形态受影响 —— 但契约上必须一致。
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                .timeout(Duration.ofMillis(connectTimeoutMillis));
        request.headers().forEach(builder::header);
        if ("POST".equals(request.method())) {
            builder.POST(request.body() == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(request.body()));
        } else if ("GET".equals(request.method())) {
            builder.GET();
        } else {
            // 其余方法不在本专用通道的登记范围内：显式拒绝，不做静默降级为 GET
            throw new UpstreamUnavailableException("unsupported event-stream method");
        }
        HttpResponse<java.io.InputStream> upstream;
        try {
            upstream = streamClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamUnavailableException("event stream interrupted");
        } catch (Exception e) {
            throw new UpstreamUnavailableException("event stream unavailable");
        }
        int status = upstream.statusCode();
        try (java.io.InputStream body = upstream.body()) {
            if (status != 200) {
                byte[] bytes = body.readNBytes(64 * 1024);
                response.setStatus(status);
                response.setContentType(upstream.headers().firstValue("Content-Type").orElse("application/json"));
                String permit=upstream.headers().firstValue("X-AI-Delivery-Permit").orElse(null);
                String operation=upstream.headers().firstValue("X-AI-Delivery-Operation").orElse(null);
                try {
                    response.getOutputStream().write(bytes);
                    response.getOutputStream().flush();
                } finally { if(permit!=null || operation!=null) acknowledge(delivery,permit,operation); }
                return;
            }
            response.setStatus(200);
            response.setContentType(upstream.headers().firstValue("Content-Type").orElse("text/event-stream;charset=UTF-8"));
            response.setHeader("Cache-Control", "no-cache, no-store");
            response.setHeader("X-Accel-Buffering", "no");
            java.util.concurrent.atomic.AtomicLong lastRead = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
            java.util.concurrent.atomic.AtomicBoolean aborted = new java.util.concurrent.atomic.AtomicBoolean(false);
            long startedAt = System.currentTimeMillis();
            var watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ai-gateway-sse-watchdog");
                thread.setDaemon(true);
                return thread;
            });
            watchdog.scheduleAtFixedRate(() -> {
                long now = System.currentTimeMillis();
                if (now - lastRead.get() > idleTimeoutMillis || now - startedAt > maxDurationMillis) {
                    aborted.set(true);
                    try {
                        body.close();
                    } catch (java.io.IOException ignored) {
                        // 关闭上游以解除阻塞读
                    }
                }
            }, 1000, 1000, java.util.concurrent.TimeUnit.MILLISECONDS);
            try {
                byte[] buffer = new byte[8192];
                int read;
                var frame = new java.io.ByteArrayOutputStream();
                int previous=-1;
                while (!aborted.get() && (read = body.read(buffer)) >= 0) {
                    lastRead.set(System.currentTimeMillis());
                    for(int i=0;i<read;i++) {
                        int value=buffer[i]&255;
                        if(frame.size()>=1024*1024) throw new UpstreamUnavailableException("stream frame too large");
                        frame.write(value);
                        if(previous=='\n' && value=='\n') {
                            deliverFrame(frame.toByteArray(),response,delivery);
                            frame.reset();
                        }
                        previous=value;
                    }
                }
            } catch (java.io.IOException e) {
                // 客户端断开或上游结束：只断订阅，不改变运行
            } finally {
                watchdog.shutdownNow();
            }
        } catch (java.io.IOException e) {
            throw new UpstreamUnavailableException("event stream body unavailable");
        }
    }

    /** Dedicated bounded private PDF delivery; the ordinary JSON limit remains unchanged. */
    public void forwardPrivateDocument(ForwardRequest request,jakarta.servlet.http.HttpServletResponse response,
                                       int maxBytes,int deadlineMillis,DeliveryAck delivery) {
        HttpResponse<java.io.InputStream> upstream;
        try {
            var builder=HttpRequest.newBuilder(request.uri()).timeout(Duration.ofMillis(deadlineMillis)).GET();
            request.headers().forEach(builder::header);
            upstream=httpClient.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new UpstreamUnavailableException("download interrupted");}
        catch(Exception e){throw new UpstreamUnavailableException("download unavailable");}
        String permit=upstream.headers().firstValue("X-AI-Delivery-Permit").orElse(null);
        String operation=upstream.headers().firstValue("X-AI-Delivery-Operation").orElse(null);
        var watchdog=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"ai-private-download-deadline");t.setDaemon(true);return t;});
        watchdog.schedule(()->{try{upstream.body().close();}catch(java.io.IOException ignored){}},deadlineMillis,java.util.concurrent.TimeUnit.MILLISECONDS);
        try(java.io.InputStream body=upstream.body()) {
            int status=upstream.statusCode();
            if(status!=200 && !java.util.Set.of(400,401,403,404,409,413,503).contains(status)) throw new UpstreamUnavailableException("download status invalid");
            String type=upstream.headers().firstValue("Content-Type").orElse("");
            if(status==200 && (!type.toLowerCase(java.util.Locale.ROOT).startsWith("application/pdf") || permit==null || operation==null))
                throw new UpstreamUnavailableException("download proof missing");
            int limit=status==200 ? maxBytes : 65536;
            byte[] bytes=body.readNBytes(limit+1);
            if(bytes.length>limit) throw new UpstreamUnavailableException("download limit exceeded");
            response.setStatus(status);response.setContentType(status==200 ? "application/pdf" : "application/json;charset=UTF-8");
            response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");
            response.getOutputStream().write(bytes);response.getOutputStream().flush();
        } catch(java.io.IOException e){throw new UpstreamUnavailableException("download delivery failed");}
        finally {
            watchdog.shutdownNow();
            // Reached only after our read and final write have stopped, including client abort.
            if(permit!=null || operation!=null) acknowledge(delivery,permit,operation);
        }
    }

    /** 专用上传流：请求体流式转发，超限即拒绝（不缓冲全量）。 */
    public void forwardUploadStream(ForwardRequest request, jakarta.servlet.http.HttpServletResponse response,
                                    long maxBytes, int timeoutMillis) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri()).timeout(Duration.ofMillis(timeoutMillis));
        request.headers().forEach(builder::header);
        builder.method(request.method(), HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.InputStream() {
            private long total = 0;

            private final java.io.InputStream delegate = request.streamBody().get();

            @Override
            public int read() throws java.io.IOException {
                int value = delegate.read();
                if (value >= 0 && ++total > maxBytes) {
                    throw new java.io.IOException("upload exceeds the gateway limit");
                }
                return value;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws java.io.IOException {
                int read = delegate.read(buffer, offset, length);
                if (read > 0) {
                    total += read;
                    if (total > maxBytes) {
                        throw new java.io.IOException("upload exceeds the gateway limit");
                    }
                }
                return read;
            }

            @Override
            public void close() throws java.io.IOException {
                delegate.close();
            }
        }));
        try {
            HttpResponse<java.io.InputStream> upstream = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            int status = upstream.statusCode();
            byte[] bytes;
            try (java.io.InputStream body = upstream.body()) {
                bytes = body.readNBytes(256 * 1024);
            }
            response.setStatus(status);
            response.setContentType(upstream.headers().firstValue("Content-Type").orElse("application/json"));
            response.setHeader("Cache-Control", "no-store");
            response.getOutputStream().write(bytes);
            response.getOutputStream().flush();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamUnavailableException("upload interrupted");
        } catch (java.io.IOException e) {
            throw new UpstreamUnavailableException("upload stream rejected");
        } catch (Exception e) {
            throw new UpstreamUnavailableException("upload unavailable");
        }
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
