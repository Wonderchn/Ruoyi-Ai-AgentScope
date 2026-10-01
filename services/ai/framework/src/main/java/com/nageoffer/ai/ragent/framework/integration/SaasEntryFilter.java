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

package com.nageoffer.ai.ragent.framework.integration;

import com.nageoffer.ai.ragent.framework.security.AiRequestIdFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * 产品 HTTP 入口边界（P1.2a）。
 *
 * <p>在 MVC 与旧 SaToken 拦截器<b>之前</b>执行：P1.2a 没有任何客户 API，
 * 因此除极少数无内容基础设施路径外，所有入口统一 404，
 * 且业务 handler / UserMapper / MQ / 对象 / 模型都不被触碰。
 *
 * <p>关键行为（Spec 01 §3.1）：
 * <ul>
 *   <li>RequestDispatcher 感知：{@code ERROR} 放行（否则错误页本身被拦成递归 404）；
 *       {@code ASYNC} 放行（已完成异步的收尾分派不得被二次拒绝，且不会重新执行 handler）；
 *       {@code REQUEST}/{@code FORWARD} 走同一条判定。</li>
 *   <li>路径规范化：先剥 contextPath，再单次 URL 解码；<b>任何残留 {@code %}</b>
 *       （多轮编码）、{@code .} / {@code ..} 段、反斜杠、控制字符一律按"未注册"处理，
 *       不能靠 {@code startsWith("/auth")} 这类字符串前缀比较。</li>
 *   <li>不因为缺少 handler 才拒绝：即使旧 controller 仍注册着，也在 handler 之前拒绝。</li>
 *   <li>不解析 X-Tenant / X-User / body 中的身份字段，不把它们写进安全日志。</li>
 * </ul>
 *
 * <p>允许的路径只有三类，且都不含客户内容：
 * <ol>
 *   <li>{@code OPTIONS} 预检 → 204（不执行 handler）；</li>
 *   <li>{@code GET} 且最后一段是 {@code health} → 交给后续链路：产品没有 health handler
 *       时仍然是 404，绝不伪造成"健康 API 可用"；</li>
 *   <li>容器 {@code ERROR} 分派。</li>
 * </ol>
 */
public class SaasEntryFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SaasEntryFilter.class);

    /** 允许探活的基础设施路径后缀（无内容 handler）。 */
    private static final String HEALTH_PATH_SUFFIX = "/health";

    /** 与 P04 外显口径一致的关闭响应；{@code HTTP status == body.code == 404}。 */
    static final String CLOSED_BODY =
            "{\"code\":404,\"msg\":\"资源不存在或无权访问\","
                    + "\"data\":{\"errorCode\":\"RESOURCE_NOT_FOUND_OR_FORBIDDEN\"}}";

    private static final String CONTENT_TYPE_JSON = "application/json;charset=UTF-8";

    private static final Set<String> CORS_ALLOW_HEADERS =
            Set.of("x-request-id", "content-type", "authorization", "accept", "origin", "x-requested-with");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!allowsDispatch(request)) {
            reject(response, request);
            return;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            // CORS 预检不执行 handler，也不代表业务动作可用（后续实际方法仍走同一判定）
            preflight(response);
            return;
        }
        String path = normalizedPath(request);
        if (isInfrastructurePath(path, request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        reject(response, request);
    }

    /**
     * 分派类型策略（显式，不依赖容器默认）：
     * <ul>
     *   <li>{@code REQUEST}：正常入口，走下面的路径判定；</li>
     *   <li>{@code ASYNC}：异步收尾分派，<b>放行</b>——它只把已提交的异步响应交还客户端，
     *       不会重新执行产品 handler；拒绝它会让已完成的异步请求得不到收尾；
     *   <li>{@code ERROR}：错误页分派，<b>放行</b>——否则错误页本身被拦成递归 404；</li>
     *   <li>{@code FORWARD}：内部转发绕过了客户端可见路径，一律拒绝。</li>
     * </ul>
     */
    private static boolean allowsDispatch(HttpServletRequest request) {
        DispatcherType type = request.getDispatcherType();
        return type == DispatcherType.REQUEST
                || type == DispatcherType.ASYNC
                || type == DispatcherType.ERROR;
    }

    /**
     * 只有无内容基础设施路径可以继续走链路。
     *
     * <p>形状判定用规范化结果，不做任何"以旧模块名开头就算旧能力"的宽松匹配：
     * 未注册即为未注册。
     *
     * <p>{@code path} 为 {@code null} 表示规范化拒绝（多轮编码/穿越形状），同样不放行。
     */
    private static boolean isInfrastructurePath(String path, String method) {
        if (path == null || !"GET".equalsIgnoreCase(method)) {
            return false;
        }
        return path.endsWith(HEALTH_PATH_SUFFIX) && path.length() > HEALTH_PATH_SUFFIX.length();
    }

    /**
     * 规范化请求路径；不可信形状返回 {@code null}（调用方按未注册处理）。
     *
     * <p>返回 {@code null} 的情形都是"可能绕过前缀比较"的编码/穿越形状，宁可拒绝。
     */
    static String normalizedPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || uri.isEmpty()) {
            return null;
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        if (uri.isEmpty()) {
            uri = "/";
        }
        // 任何残留百分号说明存在一次性解码无法消除的编码（含双重编码），不能相信其字面形状
        if (uri.indexOf('%') >= 0 || uri.indexOf('\\') >= 0) {
            return null;
        }
        String decoded;
        try {
            decoded = java.net.URLDecoder.decode(uri, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (decoded.indexOf('%') >= 0 || decoded.indexOf('\\') >= 0) {
            return null;
        }
        for (int i = 0; i < decoded.length(); i++) {
            char c = decoded.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return null;
            }
        }
        String lower = decoded.toLowerCase(Locale.ROOT);
        if (lower.contains("/../") || lower.endsWith("/..") || lower.contains("/./") || lower.endsWith("/.")) {
            return null;
        }
        return lower;
    }

    private void preflight(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        response.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", String.join(",", CORS_ALLOW_HEADERS));
        response.setHeader("Cache-Control", "no-store");
    }

    private void reject(HttpServletResponse response, HttpServletRequest request) throws IOException {
        ensureRequestId(response, request);
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType(CONTENT_TYPE_JSON);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        // 安全审计只记形状（是否已注册不区分），不含 query/token/正文
        log.debug("saas entry boundary rejected dispatch={} method={} requestId={}",
                request.getDispatcherType(), request.getMethod(), AiRequestIdFilter.currentOrEmpty());
        response.getWriter().write(CLOSED_BODY);
    }

    /**
     * 保证每个响应都有 {@code X-Request-Id}。
     *
     * <p>正常路径由 {@link AiRequestIdFilter} 写入；这里只在前置过滤器缺席
     * （例如单测直接调用本过滤器）时补一个服务端生成值，且不回显原始 header。
     */
    private static void ensureRequestId(HttpServletResponse response, HttpServletRequest request) {
        if (response.getHeader(AiRequestIdFilter.HEADER) != null) {
            return;
        }
        Object attribute = request.getAttribute(AiRequestIdFilter.HEADER);
        if (attribute instanceof String value && !value.isBlank()) {
            response.setHeader(AiRequestIdFilter.HEADER, value);
            return;
        }
        response.setHeader(AiRequestIdFilter.HEADER, AiRequestIdFilter.resolve(null));
    }
}
