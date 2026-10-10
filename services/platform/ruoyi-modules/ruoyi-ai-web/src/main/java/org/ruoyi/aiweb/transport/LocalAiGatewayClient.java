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
import org.ruoyi.ai.api.action.AiCanonicalAction;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
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
import org.springframework.web.multipart.MultipartHttpServletRequest;
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
 *       （AI 冻结代码统一从这里取主体），转发结束在 {@code finally} 中恢复旧值；
 *       scopes 由身份权限反查 canonical 动作集合（HTTP 形态委托凭证携带路由动作的
 *       内嵌等价，见 {@link #canonicalActions(AiExecutionFacts)}）；</li>
 *   <li>{@code ExecutionPrincipal} 的传输型字段（jti/issuer/iat/exp）按"本地请求
 *       标识"诚实填充：jti = 每次转送的随机关联标识、issuer = {@code platform:local}、
 *       有效期 5 分钟与许可租约同型——<b>没有</b>任何 JWT 被构造、签名或消费。</li>
 * </ul>
 *
 * <p>安全规则逐条保留：状态契约（2xx/4xx/503 透传，其余视为异常）、
 * 恶意响应 fail-closed（非空体必须是单 JSON 对象、整数 code 与状态一致）。
 * 流式传输（E3 收尾）：SSE 逐帧处理（剥离 {@code : ai-delivery} 元数据、restricted 帧
 * 必须带交付行、逐帧本地回执）、私有 PDF（PDF + 交付证明 + maxBytes 复核）、
 * 上传（multipart 部件直读、声明长度与响应上限）。空闲/总时长/建连超时参数在进程内
 * 由 AI 侧 BoundedSink 与容器写失败承担（无上游 socket 可关），语义缺口已在台账记录。
 */
public class LocalAiGatewayClient extends AiGatewayClient {

    /** 本地转送的 issuer 标识：说明主体由内嵌身份桥构造，而非任何签发的凭证。 */
    public static final String LOCAL_ISSUER = "platform:local";

    /** 与许可租约同型的请求级有效期。 */
    private static final Duration PRINCIPAL_TTL = Duration.ofMinutes(5);

    private static final int MAX_BYTE_BODY = 4 * 1024 * 1024;

    /** 上传响应（JSON 信封）上限：与 HTTP 形态的 256KB 读取上限一致。 */
    private static final int MAX_UPLOAD_RESPONSE = 256 * 1024;

    /** SSE 单帧上限：与 HTTP 形态的 1MB 帧上限一致。 */
    private static final int MAX_STREAM_FRAME = 1024 * 1024;

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
        forwardEventStream(request, response, connectTimeoutMillis, idleTimeoutMillis, maxDurationMillis, null);
    }

    @Override
    public void forwardEventStream(ForwardRequest request, HttpServletResponse response,
                                   int connectTimeoutMillis, int idleTimeoutMillis, int maxDurationMillis,
                                   DeliveryAck delivery) {
        // 空闲/总时长限制在进程内没有上游 socket 可关：由 AI 侧 BoundedSink 的心跳/上限与
        // 容器写失败（客户端断开）承担同等职责。逐帧形状校验、`: ai-delivery` 元数据剥离与
        // 逐帧交付回执与 HTTP 形态逐条一致（见 StreamFrameResponse）。
        StreamFrameResponse streaming = new StreamFrameResponse(response, delivery);
        dispatchTo(request, streaming, (outer, targetPath) ->
                new BodyProvidingRequest(outer, request.body(), targetPath, request.uri().getRawQuery()));
        streaming.finish();
    }

    @Override
    public void forwardPrivateDocument(ForwardRequest request, HttpServletResponse response,
                                       int maxBytes, int deadlineMillis, DeliveryAck delivery) {
        // 内嵌形态下内层控制器已把私有文档读成有界 byte[]（AI 侧 4MB 上限），
        // 没有需要按截止时间关闭的上游读流；本层仍按 maxBytes 复核并 fail-closed。
        Captured capture = dispatch(request);
        int status = capture.status();
        if (status != 200 && !Set.of(400, 401, 403, 404, 409, 413, 503).contains(status)) {
            throw new UpstreamUnavailableException("download status invalid");
        }
        byte[] bytes = capture.body();
        String type = orDefault(capture.header("Content-Type"), "");
        String permit = capture.header("X-AI-Delivery-Permit");
        String operation = capture.header("X-AI-Delivery-Operation");
        if (status == 200) {
            if (!type.toLowerCase(Locale.ROOT).startsWith("application/pdf")
                    || !isDeliveryId(permit) || !isDeliveryId(operation)) {
                throw new UpstreamUnavailableException("download proof missing");
            }
            if (bytes.length > maxBytes) {
                throw new UpstreamUnavailableException("download limit exceeded");
            }
        }
        response.setStatus(status);
        response.setContentType(status == 200 ? "application/pdf" : "application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        try {
            response.getOutputStream().write(bytes);
            response.getOutputStream().flush();
        } catch (IOException e) {
            throw new UpstreamUnavailableException("download delivery failed");
        } finally {
            if (isDeliveryId(permit) || isDeliveryId(operation)) {
                acknowledgeStreamDelivery(delivery, permit, operation);
            }
        }
    }

    @Override
    public void forwardUploadStream(ForwardRequest request, HttpServletResponse response,
                                    long maxBytes, int timeoutMillis) {
        // 上传体不缓冲全量：内层处理器直接读容器已解析的 multipart 部件（与 HTTP 形态
        // 把同一 multipart 体转发给内层由内层解析等价）。超限即拒绝，不静默截断。
        HttpServletRequest outer = currentRequest();
        if (outer.getContentLengthLong() > maxBytes) {
            throw new UpstreamUnavailableException("upload exceeds the gateway limit");
        }
        Captured capture = new Captured(response);
        dispatchTo(request, capture, (servletRequest, targetPath) -> {
            try {
                return new MultipartTargetRequest(servletRequest, targetPath);
            } catch (RuntimeException notMultipart) {
                throw new UpstreamUnavailableException("upload is not multipart");
            }
        });
        int status = capture.status();
        if (!((status >= 200 && status < 300) || (status >= 400 && status < 500) || status == 503)) {
            throw new UpstreamUnavailableException("abnormal upload response");
        }
        byte[] bytes = capture.body();
        if (bytes.length > MAX_UPLOAD_RESPONSE) {
            throw new UpstreamUnavailableException("upload response limit exceeded");
        }
        response.setStatus(status);
        response.setContentType(orDefault(capture.header("Content-Type"), "application/json"));
        response.setHeader("Cache-Control", "no-store");
        try {
            response.getOutputStream().write(bytes);
            response.getOutputStream().flush();
        } catch (IOException e) {
            throw new UpstreamUnavailableException("upload response failed");
        }
    }

    /** 交付标识形状（与 HTTP 形态同一口径）：不透明 id，不含空白/分隔歧义。 */
    private static boolean isDeliveryId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,128}");
    }

    /** 流式交付回执：内嵌形态收敛为本地方法调用（不构造 HTTP ACK 请求）。 */
    private void acknowledgeStreamDelivery(DeliveryAck delivery, String permit, String operation) {
        if (delivery == null || !isDeliveryId(permit) || !isDeliveryId(operation)) {
            // 保护帧没有回执出口：绝不静默放过（permit 保持 ACTIVE）
            throw new UpstreamUnavailableException("delivery identity missing");
        }
        try {
            deliveryReleaser.releaseDelivery(delivery.tenantId(), delivery.memberId(), permit, operation);
        } catch (UpstreamUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new UpstreamUnavailableException("delivery acknowledgement unavailable");
        }
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
        if (attributes.getResponse() == null) {
            throw new UpstreamUnavailableException("delivery output absent");
        }
        return dispatchTo(request, new Captured(attributes.getResponse()), targetRequest(request));
    }

    /**
     * 目标请求形状：<b>multipart 外请求按部件转送</b>，其余按转发体转送。
     *
     * <p>白名单通配分支（{@code AiGatewayController} 的 JSON 转发）此前一律用
     * {@link BodyProvidingRequest} 包体——但 multipart 请求的体在容器解析部件后已经耗尽，
     * 体转发会让内层拿到"空的 multipart"（{@code @RequestPart} 解析不出文件、
     * {@code @ModelAttribute} 读不到表单字段），运行期表现是"路由登记了、请求也到了、
     * 部件却丢了"。故与上传流通道（{@code forwardUploadStream}）同款：
     * 外请求是 {@code MultipartHttpServletRequest}（容器已解析部件）时，
     * 用 {@link MultipartTargetRequest} 把部件与参数按原样交给内层 handler。
     *
     * <p>非 multipart 请求的转送形状一字未改（体 + query 仍走 {@link BodyProvidingRequest}），
     * 因此除"经通配分支的 multipart 路由"外，既有路由的行为与判据都不受影响。
     * 上传流通道（{@code /documents/uploads}）走的是自己的 {@code MultipartTargetRequest} 构造，
     * 不经过本方法。
     */
    private static TargetRequestBuilder targetRequest(ForwardRequest request) {
        return (outer, targetPath) -> outer instanceof MultipartHttpServletRequest
                ? new MultipartTargetRequest(outer, targetPath)
                : new BodyProvidingRequest(outer, request.body(), targetPath, request.uri().getRawQuery());
    }

    private static HttpServletRequest currentRequest() {
        if (!(RequestContextHolder.currentRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            throw new UpstreamUnavailableException("dispatch outside request thread");
        }
        return attributes.getRequest();
    }

    /** 目标请求的构造器（JSON/字节转送与 multipart 上传各自提供形状）。 */
    private interface TargetRequestBuilder {
        HttpServletRequest build(HttpServletRequest outer, RequestPath targetPath);
    }

    /**
     * 通用直调：身份桥（{@link AiIdentityPort} → {@link ExecutionPrincipal}）→
     * 内层 handler 直调 → 恢复主体上下文。响应由调用方给定的包装器截留或流式处理。
     */
    private <R extends HttpServletResponse> R dispatchTo(ForwardRequest request, R response,
                                                         TargetRequestBuilder requestBuilder) {
        HttpServletRequest servletRequest = currentRequest();
        AiExecutionFacts facts = identityPort.currentFacts()
                .orElseThrow(() -> new UpstreamUnavailableException("execution facts unavailable"));

        // Spring 7 的 handler 映射要求请求属性携带目标路径的已解析 RequestPath
        //（外层缓存的是 /api/ai/v1/**，必须预置内层目标）
        String contextPath = servletRequest.getContextPath() == null ? "" : servletRequest.getContextPath();
        RequestPath targetPath = RequestPath.parse(contextPath + request.uri().getRawPath(), contextPath);

        HttpServletRequest target = requestBuilder.build(servletRequest, targetPath);
        ExecutionPrincipal previous = PrincipalContext.set(toLocalPrincipal(facts));
        try {
            invokeHandler(servletRequest, target, response);
            return response;
        } catch (UpstreamUnavailableException e) {
            throw e;
        } catch (Exception e) {
            // 诊断修复（T0，2026-10-06）：此前这里 `new UpstreamUnavailableException("internal dispatch failed")`
            // **既不带 e 也不带 message** ⇒ 没有 Caused by、没有内层异常类型，
            // 任何人都无法从日志判断内层为什么失败。后果是同一个 503 现象被归因了三次
            // （引擎门控 / acl_epoch / 内层不是 bean），每次都只对一部分 —— 观测面在这行被掐断。
            // 现在保留原因与内层目标路径；**对外文案不变**（网关仍回泛化文案，只有服务端日志能看到）。
            throw new UpstreamUnavailableException(
                    "internal dispatch failed: target=" + targetPath.pathWithinApplication() + " cause=" + e, e);
        } finally {
            PrincipalContext.restore(previous);
        }
    }

    /**
     * 经 MVC 基础设施直调内层 handler（与 MockMvc 同款机制）。
     * 刻意不用 RequestDispatcher：servlet forward 按规范在返回前提交响应，
     * 会让网关无法再控制响应（交付回执、信封校验全部失效）。
     */
    private void invokeHandler(HttpServletRequest servletRequest, HttpServletRequest target,
                               HttpServletResponse response) throws Exception {
        WebApplicationContext webContext = RequestContextUtils.findWebApplicationContext(servletRequest);
        if (webContext == null) {
            throw new UpstreamUnavailableException("internal dispatch unavailable");
        }
        // **按名字取，不能按类型取**（T0，2026-10-06，根因定案）：
        // 此前是 `webContext.getBean(RequestMappingHandlerMapping.class)` 按类型取，
        // 而本形态下该类型有**两个**候选：
        //   requestMappingHandlerMapping（正常 MVC）
        //   controllerEndpointHandlerMapping（Spring Boot Actuator 的控制器端点映射；
        //     因 `management.endpoints.web.exposure.include: '*'` 被创建）
        // ⇒ 抛 NoUniqueBeanDefinitionException ⇒ 被 dispatchTo 的 catch-all 泛化成 503
        // ⇒ **在任何路由匹配之前就失败**，表现为"所有已登记 AI 路由 38/38 全 503"。
        // 单测覆盖不到它：测试上下文里通常只有一个该类型的 bean，Actuator 那个
        // 只在真实 Web 上下文 + actuator web exposure 打开时出现。
        // 这是 F-3 同族的第三次（Executor 多 @Primary / OkHttpClient 静默错配 / 本次按类型取歧义）。
        RequestMappingHandlerMapping mapping =
                webContext.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        HandlerExecutionChain chain = mapping.getHandler(target);
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
            adapter.handle(target, response, chain.getHandler());
        } catch (Exception handlerException) {
            // AI 侧异常解析器（@Order 最高优先级）先于 Spring 复合解析器运行，
            // 保证 AI 信封形状不被 platform 全局 @RestControllerAdvice 改写
            List<HandlerExceptionResolver> resolvers = new ArrayList<>(
                    webContext.getBeansOfType(HandlerExceptionResolver.class).values());
            org.springframework.core.annotation.AnnotationAwareOrderComparator.sort(resolvers);
            for (HandlerExceptionResolver resolver : resolvers) {
                if (resolver.resolveException(target, response, chain.getHandler(), handlerException) != null) {
                    return;
                }
            }
            throw handlerException;
        }
    }

    private ExecutionPrincipal toLocalPrincipal(AiExecutionFacts facts) {
        Instant now = Instant.now();
        return new ExecutionPrincipal(facts.tenantId(), facts.userId(), facts.membershipId(),
                facts.policyVersion(), facts.aclVersion(), canonicalActions(facts),
                UUID.randomUUID().toString(), LOCAL_ISSUER,
                now.getEpochSecond(), now.plus(PRINCIPAL_TTL).getEpochSecond());
    }

    /**
     * 身份权限 → canonical 动作集合。
     *
     * <p>HTTP 形态下委托凭证只携带本路由的那一个 canonical 动作（网关签发时确定）；
     * 内嵌形态没有凭证，改由身份的 {@code ai:*} 权限反查 {@link AiCanonicalAction}
     * 得到其可执行动作集合。AI 侧 {@code principal.hasScope(action)} 预检的语义
     * （"本路由动作是否在委托范围内"）保持不变——权威判定仍在 platform 许可检查
     * （按权限精确比较，无通配豁免）。
     */
    private static Set<String> canonicalActions(AiExecutionFacts facts) {
        Set<String> actions = new LinkedHashSet<>();
        for (String action : AiCanonicalAction.knownActions()) {
            String permission = AiCanonicalAction.permissionOf(action).orElse(null);
            if (permission != null && facts.hasScope(permission)) {
                actions.add(action);
            }
        }
        return Set.copyOf(actions);
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

    /**
     * SSE 帧处理响应：把内层写出的字节流按 {@code \n\n} 帧边界截获，剥离
     * {@code : ai-delivery <permit> <operation>} 元数据行，校验保护形状
     * （restricted 帧必须带交付行；未保护的非 ping 帧一律拒绝），写出公共帧后
     * <b>逐帧</b>执行交付回执——与 HTTP 形态的逐帧转送语义一致。
     *
     * <p>非 200（错误信封）直通写真实响应，不做帧处理（与 HTTP 形态的
     * "status != 200 时透传 body"一致）。状态/头由内层处理器设置，经
     * {@link HttpServletResponseWrapper} 默认委派落到真实响应。
     *
     * <p>失败处理：协议违规或回执失败抛 {@link UpstreamUnavailableException}；
     * 内层 SSE 写线程会终止订阅（不再投递后续帧），permit 保持 ACTIVE——
     * 与"无法确认交付结束"的 fail-closed 口径一致。
     */
    private final class StreamFrameResponse extends HttpServletResponseWrapper {

        private final HttpServletResponse real;
        private final DeliveryAck delivery;
        private final java.io.ByteArrayOutputStream frame = new java.io.ByteArrayOutputStream();
        private int previous = -1;
        private ServletOutputStream stream;
        private java.io.PrintWriter writer;

        StreamFrameResponse(HttpServletResponse real, DeliveryAck delivery) {
            super(real);
            this.real = real;
            this.delivery = delivery;
        }

        /** 流结束：刷新内层 writer 与真实响应（客户端可能已断开，写失败即忽略）。 */
        void finish() {
            try {
                if (writer != null) {
                    writer.flush();
                }
                real.getOutputStream().flush();
            } catch (IOException ignored) {
                // 客户端断开：不再可写
            }
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
                    write(new byte[] {(byte) b}, 0, 1);
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    if (real.getStatus() != 200) {
                        rawWrite(b, off, len);
                        return;
                    }
                    for (int i = off; i < off + len; i++) {
                        int value = b[i] & 255;
                        if (frame.size() >= MAX_STREAM_FRAME) {
                            throw new UpstreamUnavailableException("stream frame too large");
                        }
                        frame.write(value);
                        if (previous == '\n' && value == '\n') {
                            deliverFrame(frame.toByteArray());
                            frame.reset();
                        }
                        previous = value;
                    }
                }
            };
        }

        @Override
        public java.io.PrintWriter getWriter() {
            if (writer == null) {
                writer = new java.io.PrintWriter(new java.io.OutputStreamWriter(getOutputStream(),
                        StandardCharsets.UTF_8), false);
            }
            return writer;
        }

        private void rawWrite(byte[] bytes, int off, int len) {
            try {
                real.getOutputStream().write(bytes, off, len);
            } catch (IOException e) {
                throw new UpstreamUnavailableException("stream delivery failed");
            }
        }

        /** 单帧处理：剥离交付元数据 → 形状校验 → 写出公共帧并 flush → 逐帧回执。 */
        private void deliverFrame(byte[] bytes) {
            String text = new String(bytes, StandardCharsets.UTF_8);
            String permit = null;
            String operation = null;
            StringBuilder publicFrame = new StringBuilder();
            boolean restricted = false;
            for (String line : text.split("\n", -1)) {
                if (line.startsWith(": ai-delivery ")) {
                    String[] ids = line.substring(14).trim().split(" ", -1);
                    if (permit != null || ids.length != 2 || !isDeliveryId(ids[0]) || !isDeliveryId(ids[1])) {
                        throw new UpstreamUnavailableException("invalid delivery metadata");
                    }
                    permit = ids[0];
                    operation = ids[1];
                } else {
                    if (line.startsWith("data:") || line.startsWith("id:") || line.startsWith("event:")) {
                        restricted = true;
                    }
                    publicFrame.append(line).append('\n');
                }
            }
            if (restricted && permit == null) {
                throw new UpstreamUnavailableException("unprotected stream frame");
            }
            if (permit == null && !text.equals(": ping\n\n")) {
                throw new UpstreamUnavailableException("unexpected unprotected stream frame");
            }
            try {
                ServletOutputStream out = real.getOutputStream();
                out.write(publicFrame.substring(0, publicFrame.length() - 1).getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                throw new UpstreamUnavailableException("stream delivery failed");
            } finally {
                // 本帧的应用写入已停止（含客户端断开路径）：回执失败绝不自动重试，permit 保持 ACTIVE
                if (permit != null) {
                    acknowledgeStreamDelivery(delivery, permit, operation);
                }
            }
        }
    }

    /**
     * 目标路径 + 容器已解析的 multipart 部件：供内层 {@code @RequestPart} 处理器使用。
     * 外层已缓存的 Spring 解析路径必须替换为内层目标（与 {@link BodyProvidingRequest} 同一职责）。
     */
    private static final class MultipartTargetRequest extends
            org.springframework.web.multipart.support.StandardMultipartHttpServletRequest {

        private RequestPath parsedPath;

        MultipartTargetRequest(HttpServletRequest request, RequestPath parsedPath) {
            super(request);
            this.parsedPath = parsedPath;
        }

        @Override
        public Object getAttribute(String name) {
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
            if (writer != null) { writer.flush(); }
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

        @Override public int getStatus() { return status; }
        @Override public String getHeader(String name) { return header(name); }
        @Override public java.util.Collection<String> getHeaders(String name) {
            return java.util.List.copyOf(headers.getOrDefault(name, java.util.List.of()));
        }
        @Override public java.util.Collection<String> getHeaderNames() { return java.util.List.copyOf(headers.keySet()); }
        @Override public void setContentLength(int length) { setHeader("Content-Length", Integer.toString(length)); }
        @Override public void setContentLengthLong(long length) { setHeader("Content-Length", Long.toString(length)); }

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
            setHeader("Content-Type", type);
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
            if (writer != null) { writer.flush(); }
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
            if (writer != null) { writer.flush(); }
            // 截留语义：不向外层响应写任何字节
        }
    }
}
