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

package org.ruoyi.aiweb.transport;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import org.ruoyi.ai.api.AiExecutionFacts;
import org.ruoyi.ai.api.identity.AiIdentityPort;
import org.ruoyi.aiintegration.web.AiGatewayClient;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import jakarta.servlet.ReadListener;
import jakarta.servlet.WriteListener;

import org.springframework.http.server.RequestPath;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.async.WebAsyncManager;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.HandlerAdapter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.support.RequestContextUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * {@link AiGatewayClient} 的内嵌同进程实现（E3/C3，{@code transport=local}）。
 *
 * <p>把白名单转发从跨进程 HTTP 改为<b>servlet forward</b>：同一个 JVM、同一个
 * Tomcat、同一条请求线程，不存在任何 localhost HTTP 调用。AI 侧内部控制器由
 * 内嵌装配显式注册，其响应状态码与包络体逐字节对齐原 HTTP 透传语义。
 *
 * <p>身份桥（不铸造委托凭证、不伪造 JWT）：
 * <ul>
 *   <li>转发前从 {@link AiIdentityPort} 解析当前执行事实（与网关同源：
 *       已验证登录态 + 真实成员事实 + {@code ai_acl_epoch} 权威版本），
 *       事实不可得即 503 语义，不放行；</li>
 *   <li>事实映射为 AI 侧 {@link ExecutionPrincipal} 放入 {@link PrincipalContext}
 *       （AI 冻结代码统一从这里取主体），转发结束在 {@code finally} 中恢复旧值；</li>
 *   <li>{@code ExecutionPrincipal} 的传输型字段（jti/issuer/iat/exp）按"本地请求
 *       标识"诚实填充：jti = 每次转送的随机关联标识、issuer = {@code platform:local}、
 *       有效期 5 分钟与许可租约同型——<b>没有</b>任何 JWT 被构造、签名或消费。</li>
 * </ul>
 *
 * <p>安全规则逐条保留：状态契约（2xx/4xx/503 透传，其余视为异常）、
 * 恶意响应 fail-closed（非空体必须是单 JSON 对象、整数 code 与状态一致）。
 * 流式方法（SSE/上传/私有 PDF）当前 fail-closed 503——专用本地流传输属后续单元，
 * <b>不</b>静默降级回 HTTP。
 */
public class LocalAiGatewayClient extends AiGatewayClient {

    /** 本地转送的 issuer 标识：说明主体由内嵌身份桥构造，而非任何签发的凭证。 */
    public static final String LOCAL_ISSUER = "platform:local";

    /** 与许可租约同型的请求级有效期。 */
    private static final Duration PRINCIPAL_TTL = Duration.ofMinutes(5);

    private static final int MAX_BYTE_BODY = 4 * 1024 * 1024;

    private final AiIdentityPort identityPort;
    private final AiDeliveryReleaser deliveryReleaser;
    private final ObjectMapper responseMapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public LocalAiGatewayClient(int timeoutMillis, AiIdentityPort identityPort, AiDeliveryReleaser deliveryReleaser) {
        super(timeoutMillis);
        this.identityPort = identityPort;
        this.deliveryReleaser = deliveryReleaser;
    }

    @Override
    public ForwardResponse forward(ForwardRequest request) {
        // 交付回执在响应已提交之后才发生（servlet forward 届时不可用），
        // 内嵌传输把它收敛为本地方法调用；跨进程 HTTP 传输仍是独立 POST。
        if (request.uri().getRawPath().endsWith("/authorization/deliveries/release")) {
            return acknowledgeDelivery(request);
        }
        Captured capture = dispatch(request);
        int status = capture.status();
        boolean passthrough = (status >= 200 && status < 300) || (status >= 400 && status < 500) || status == 503;
        if (!passthrough) {
            throw new UpstreamUnavailableException("abnormal upstream status");
        }
        String body = capture.bodyAsText();
        if (!body.isBlank()) {
            requireSingleJsonObject(body, status);
        }
        return new ForwardResponse(status, body);
    }

    @Override
    public ByteResponse forwardBytes(ForwardRequest request) {
        Captured capture = dispatch(request);
        int status = capture.status();
        byte[] bytes = capture.body();
        if (bytes.length > MAX_BYTE_BODY) {
            throw new UpstreamUnavailableException("upstream body limit exceeded");
        }
        String permit = orDefault(capture.header("X-AI-Delivery-Permit"), "");
        String operation = orDefault(capture.header("X-AI-Delivery-Operation"), "");
        String type = orDefault(capture.header("Content-Type"), "application/octet-stream");
        String range = orDefault(capture.header("Content-Range"), "");
        if (status == 200 || status == 206) {
            if (type.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                requireSingleJsonObject(capture.bodyAsText(), status);
            }
            if (!permit.matches("[0-9a-f-]{36}") || !operation.matches("[0-9a-f-]{36}")
                    || (status == 206 && !range.matches("bytes [0-9]+-[0-9]+/[0-9]+"))) {
                throw new UpstreamUnavailableException("delivery receipt missing");
            }
        } else {
            if (!((status >= 400 && status < 500) || status == 503)) {
                throw new UpstreamUnavailableException("abnormal byte response");
            }
            requireSingleJsonObject(capture.bodyAsText(), status);
        }
        return new ByteResponse(status, bytes, type, range, permit, operation);
    }

    @Override
    public void forwardEventStream(ForwardRequest request, HttpServletResponse response,
                                   int connectTimeoutMillis, int idleTimeoutMillis, int maxDurationMillis) {
        throw new UpstreamUnavailableException("local stream transport not wired");
    }

    @Override
    public void forwardEventStream(ForwardRequest request, HttpServletResponse response,
                                   int connectTimeoutMillis, int idleTimeoutMillis, int maxDurationMillis,
                                   DeliveryAck delivery) {
        throw new UpstreamUnavailableException("local stream transport not wired");
    }

    @Override
    public void forwardPrivateDocument(ForwardRequest request, HttpServletResponse response,
                                       int maxBytes, int deadlineMillis, DeliveryAck delivery) {
        throw new UpstreamUnavailableException("local stream transport not wired");
    }

    @Override
    public void forwardUploadStream(ForwardRequest request, HttpServletResponse response,
                                    long maxBytes, int timeoutMillis) {
        throw new UpstreamUnavailableException("local stream transport not wired");
    }

    /** 处理交付回执：解析网关构造的 ACK 体，调用本地释放策略，返回 204 形状。 */
    private ForwardResponse acknowledgeDelivery(ForwardRequest request) {
        try {
            JsonNode body = responseMapper.readTree(request.body() == null
                    ? new byte[0] : request.body());
            deliveryReleaser.releaseDelivery(
                    body.path("tenantId").asText(),
                    body.path("memberId").asText(),
                    body.path("permitId").asText(),
                    body.path("operationId").asText());
            return new ForwardResponse(204, "");
        } catch (AiGatewayClient.UpstreamUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new UpstreamUnavailableException("delivery acknowledgement unavailable");
        }
    }

    // ------------------------------------------------------------------ 内部转送

    private Captured dispatch(ForwardRequest request) {
        if (!(RequestContextHolder.currentRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            throw new UpstreamUnavailableException("dispatch outside request thread");
        }
        HttpServletRequest servletRequest = attributes.getRequest();
        HttpServletResponse servletResponse = attributes.getResponse();
        if (servletResponse == null) {
            throw new UpstreamUnavailableException("delivery output absent");
        }

        AiExecutionFacts facts = identityPort.currentFacts()
                .orElseThrow(() -> new UpstreamUnavailableException("execution facts unavailable"));

        // Spring 7 的 handler 映射要求请求属性携带目标路径的已解析 RequestPath
        //（外层缓存的是 /api/ai/v1/**，必须预置内层目标）
        String contextPath = servletRequest.getContextPath() == null ? "" : servletRequest.getContextPath();
        RequestPath targetPath = RequestPath.parse(contextPath + request.uri().getRawPath(), contextPath);

        ExecutionPrincipal previous = PrincipalContext.set(toLocalPrincipal(facts));
        try {
            Captured capture = new Captured(servletResponse);
            invokeHandler(servletRequest, capture, request, targetPath);
            return capture;
        } catch (UpstreamUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new UpstreamUnavailableException("internal dispatch failed");
        } finally {
            PrincipalContext.restore(previous);
        }
    }

    /**
     * 经 MVC 基础设施直调内层 handler（与 MockMvc 同款机制）。
     * 刻意不用 RequestDispatcher：servlet forward 按规范在返回前提交响应，
     * 会让网关无法再控制响应（交付回执、信封校验全部失效）。
     */
    private void invokeHandler(HttpServletRequest servletRequest, Captured capture,
                               ForwardRequest request, RequestPath targetPath) throws Exception {
        WebApplicationContext webContext = RequestContextUtils.findWebApplicationContext(servletRequest);
        if (webContext == null) {
            throw new UpstreamUnavailableException("internal dispatch unavailable");
        }
        BodyProvidingRequest wrapped = new BodyProvidingRequest(servletRequest, request.body(), targetPath,
                request.uri().getRawQuery());
        RequestMappingHandlerMapping mapping = webContext.getBean(RequestMappingHandlerMapping.class);
        HandlerExecutionChain chain = mapping.getHandler(wrapped);
        if (chain == null) {
            throw new UpstreamUnavailableException("internal route unmatched");
        }
        HandlerAdapter adapter = null;
        for (HandlerAdapter candidate : webContext.getBeansOfType(HandlerAdapter.class).values()) {
            if (candidate.supports(chain.getHandler())) {
                adapter = candidate;
                break;
            }
        }
        if (adapter == null) {
            throw new UpstreamUnavailableException("internal dispatch unsupported");
        }
        // 刻意不执行 HandlerInterceptor：内嵌装配的身份/边界语义在传输层与控制器内实现，
        // 不得依赖拦截器（C2：AI 侧旧拦截器不参与装配）
        try {
            adapter.handle(wrapped, capture, chain.getHandler());
        } catch (Exception handlerException) {
            for (HandlerExceptionResolver resolver : webContext.getBeansOfType(HandlerExceptionResolver.class).values()) {
                if (resolver.resolveException(wrapped, capture, chain.getHandler(), handlerException) != null) {
                    return;
                }
            }
            throw handlerException;
        }
    }

    private ExecutionPrincipal toLocalPrincipal(AiExecutionFacts facts) {
        Instant now = Instant.now();
        return new ExecutionPrincipal(facts.tenantId(), facts.userId(), facts.membershipId(),
                facts.policyVersion(), facts.aclVersion(), facts.scopes(),
                UUID.randomUUID().toString(), LOCAL_ISSUER,
                now.getEpochSecond(), now.plus(PRINCIPAL_TTL).getEpochSecond());
    }

    /** 与 {@code AiGatewayClient.requireSingleJsonObject} 同一条 fail-closed 规则。 */
    private void requireSingleJsonObject(String body, int status) {
        JsonNode root;
        try {
            root = responseMapper.readTree(body);
        } catch (Exception e) {
            throw new UpstreamUnavailableException("malformed upstream body");
        }
        if (root == null || !root.isObject()) {
            throw new UpstreamUnavailableException("non-object upstream body");
        }
        if (!root.path("code").isIntegralNumber()
                || (root.path("code").intValue() != status && !(status == 202 && root.path("code").intValue() == 200))) {
            throw new UpstreamUnavailableException("missing envelope code");
        }
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 转送请求包装：注入转发体，并隔离外层请求缓存的 Spring 路径解析结果。 */
    private static final class BodyProvidingRequest extends HttpServletRequestWrapper {

        private final byte[] body;
        private final Map<String, String[]> queryParameters;
        private RequestPath parsedPath;

        BodyProvidingRequest(HttpServletRequest request, byte[] body, RequestPath parsedPath, String rawQuery) {
            super(request);
            this.body = body == null ? new byte[0] : body;
            this.queryParameters = parseQuery(rawQuery);
            this.parsedPath = parsedPath;
        }

        private static Map<String, String[]> parseQuery(String rawQuery) {
            Map<String, String[]> parameters = new HashMap<>();
            if (rawQuery == null || rawQuery.isBlank()) {
                return parameters;
            }
            for (String pair : rawQuery.split("&")) {
                int split = pair.indexOf('=');
                String key = urlDecode(split < 0 ? pair : pair.substring(0, split));
                String value = split < 0 ? "" : urlDecode(pair.substring(split + 1));
                if (key.isBlank()) {
                    continue;
                }
                parameters.merge(key, new String[] {value}, (a, b) -> {
                    String[] merged = new String[a.length + 1];
                    System.arraycopy(a, 0, merged, 0, a.length);
                    merged[a.length] = value;
                    return merged;
                });
            }
            return parameters;
        }

        private static String urlDecode(String value) {
            try {
                return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception malformed) {
                return value;
            }
        }

        @Override
        public String getQueryString() {
            // query 已在构造时并入参数映射；内层控制器经 getParameter 系列读取
            return null;
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            return queryParameters;
        }

        @Override
        public String[] getParameterValues(String name) {
            return queryParameters.get(name);
        }

        @Override
        public String getParameter(String name) {
            String[] values = queryParameters.get(name);
            return values == null || values.length == 0 ? null : values[0];
        }

        @Override
        public java.util.Enumeration<String> getParameterNames() {
            return java.util.Collections.enumeration(queryParameters.keySet());
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    // 同步转送，无读监听
                }

                @Override
                public int read() {
                    return source.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public Object getAttribute(String name) {
            // FORWARD 分派必须按目标路径重新做 handler 映射：
            // 外层（/api/ai/v1/**）已缓存的 Spring 解析路径不得泄漏到内层
            if (ServletRequestPathUtils.PATH_ATTRIBUTE.equals(name)) {
                return parsedPath;
            }
            return super.getAttribute(name);
        }

        @Override
        public void setAttribute(String name, Object value) {
            if (ServletRequestPathUtils.PATH_ATTRIBUTE.equals(name) && value instanceof RequestPath path) {
                parsedPath = path;
                return;
            }
            super.setAttribute(name, value);
        }
    }

    /** 转送响应捕获：状态、头与体全部截留在内存，不触达真实客户端响应。 */
    private static final class Captured extends HttpServletResponseWrapper {

        private final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        private final Map<String, java.util.List<String>> headers =
                new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private int status = 200;
        private ServletOutputStream stream;
        private java.io.PrintWriter writer;
        private String characterEncoding = "UTF-8";

        Captured(HttpServletResponse response) {
            super(response);
        }

        int status() {
            return status;
        }

        byte[] body() {
            return buffer.toByteArray();
        }

        String bodyAsText() {
            return new String(body(), charsetFromContentType());
        }

        String header(String name) {
            var values = headers.get(name);
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        private Charset charset() {
            try {
                return Charset.forName(characterEncoding);
            } catch (Exception unknown) {
                return StandardCharsets.UTF_8;
            }
        }

        private Charset charsetFromContentType() {
            String type = header("Content-Type");
            if (type != null) {
                for (String part : type.toLowerCase(Locale.ROOT).split(";")) {
                    String trimmed = part.trim();
                    if (trimmed.startsWith("charset=")) {
                        try {
                            return Charset.forName(trimmed.substring("charset=".length()));
                        } catch (Exception ignored) {
                            return StandardCharsets.UTF_8;
                        }
                    }
                }
            }
            return StandardCharsets.UTF_8;
        }

        private void put(String name, String value) {
            if (value == null) {
                return;
            }
            headers.computeIfAbsent(name, k -> new java.util.ArrayList<>()).add(value);
        }

        @Override
        public void setStatus(int sc) {
            this.status = sc;
        }

        @Override
        public void sendError(int sc) {
            this.status = sc;
        }

        @Override
        public void sendError(int sc, String msg) {
            this.status = sc;
        }

        @Override
        public void sendRedirect(String location) {
            this.status = 302;
            put("Location", location);
        }

        @Override
        public void setContentType(String type) {
            put("Content-Type", type);
            if (type != null) {
                for (String part : type.toLowerCase(Locale.ROOT).split(";")) {
                    String trimmed = part.trim();
                    if (trimmed.startsWith("charset=")) {
                        characterEncoding = trimmed.substring("charset=".length());
                    }
                }
            }
        }

        @Override
        public String getContentType() {
            return header("Content-Type");
        }

        @Override
        public void setCharacterEncoding(String charset) {
            characterEncoding = charset;
        }

        @Override
        public String getCharacterEncoding() {
            return characterEncoding;
        }

        @Override
        public void setLocale(java.util.Locale locale) {
            // 仅捕获语义：内层 locale 不影响真实响应
        }

        @Override
        public boolean isCommitted() {
            return false;
        }

        @Override
        public void resetBuffer() {
            buffer.reset();
        }

        @Override
        public void setHeader(String name, String value) {
            headers.remove(name);
            put(name, value);
        }

        @Override
        public void addHeader(String name, String value) {
            put(name, value);
        }

        @Override
        public void setIntHeader(String name, int value) {
            setHeader(name, String.valueOf(value));
        }

        @Override
        public void addIntHeader(String name, int value) {
            put(name, String.valueOf(value));
        }

        @Override
        public boolean containsHeader(String name) {
            return headers.containsKey(name);
        }

        @Override
        public ServletOutputStream getOutputStream() {
            if (stream != null) {
                return stream;
            }
            return stream = new ServletOutputStream() {
                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {
                    // 同步转送，无写监听
                }

                @Override
                public void write(int b) {
                    buffer.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    buffer.write(b, off, len);
                }
            };
        }

        @Override
        public java.io.PrintWriter getWriter() {
            if (writer == null) {
                writer = new java.io.PrintWriter(new java.io.OutputStreamWriter(buffer, charset()), true);
            }
            return writer;
        }

        @Override
        public void flushBuffer() {
            // 截留语义：不向外层响应写任何字节
        }
    }
}
