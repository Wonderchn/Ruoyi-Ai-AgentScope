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

package com.nageoffer.ai.ragent.mcp.config;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Function;

/**
 * /mcp 端点鉴权过滤器（R12 卡1 · F-W9-1）。
 *
 * <p><b>为什么需要 servlet 级过滤器。</b>{@code /mcp} 由 {@link McpServerConfig#mcpServlet}
 * 以 {@code ServletRegistrationBean} 注册，走容器 servlet 链、<b>绕过 Spring MVC</b> ——
 * platform 的 {@code SaInterceptor}（{@code addPathPatterns("/**")}）只覆盖 DispatcherServlet
 * 的 handler，{@code AllUrlHandler} 也只扫 {@code RequestMappingHandlerMapping}，因此
 * {@code /mcp} 从未进入任何登录检查（W9 实测：无凭证/假凭证 initialize、tools/list、
 * tools/call 全通）。修法即把登录检查补到 servlet 链上（与
 * {@code AiInternalAccessBoundaryFilter} 同款 servlet 过滤器惯用法）。
 *
 * <p><b>判定：服务凭证或登录令牌二者之一有效即放行。</b>
 * <ul>
 *   <li><b>服务凭证</b>：请求头 {@code X-P04-Service-Credential}，与属性
 *       {@code ai.integration.authorization.service-credential} 常量时间比较
 *       （与 platform 侧 {@code ProductionAuthorizationController}、AI 侧
 *       {@code ServiceIdentityVerifier} 同源属性/同口径）；<b>空配置 = 服务凭证
 *       通道整体拒绝</b>，绝不降级放行；</li>
 *   <li><b>登录令牌</b>：{@code Authorization: Bearer <token>}，经 Sa-Token
 *       {@code StpUtil.getLoginIdByToken} 判定（取不到登录 ID = 无效/冻结 = 拒）。</li>
 * </ul>
 *
 * <p><b>拒绝形状</b>（与 {@code DelegatedPrincipalFilter} 同一套）：无任何凭证 →
 * 401 {@code AUTH_REQUIRED}；携带了凭证但无效 → 401 {@code DELEGATION_INVALID}
 * （空配置与不匹配统一为一码，不给探测者区分度）。响应体 = {@code ApiEnvelope.error}，
 * HTTP status == body.code，符号码在 {@code data.errorCode}。
 *
 * <p><b>不读 body、不动响应。</b>MCP streamable-http 的请求体是流式协议载体，过滤器
 * 只读请求头；通过时原样透传 request/response，不打断 SSE 与异步收尾。仅接管
 * {@code REQUEST} 分派（注册方已限，运行时再兜底判一次）。
 */
public class McpAuthFilter implements Filter {

    /** 与 platform 侧内部端点同名（{@code X-P04-Service-Credential}）。 */
    public static final String SERVICE_CREDENTIAL_HEADER = "X-P04-Service-Credential";

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private static final String BEARER_PREFIX = "Bearer ";

    private static final String CONTENT_TYPE_JSON = "application/json;charset=UTF-8";

    private static final ObjectMapper ERROR_MAPPER = new ObjectMapper();

    /** 服务凭证期望值；空白 = 服务凭证通道不可用（fail-closed）。 */
    private final String configuredCredential;

    /** 登录令牌 → 登录 ID（取不到返回 null）；生产为 Sa-Token，测试可注入替身。 */
    private final Function<String, Object> loginIdByToken;

    public McpAuthFilter(String configuredCredential) {
        this(configuredCredential, StpUtil::getLoginIdByToken);
    }

    McpAuthFilter(String configuredCredential, Function<String, Object> loginIdByToken) {
        this.configuredCredential = configuredCredential;
        this.loginIdByToken = loginIdByToken;
    }

    @Override
    public void init(FilterConfig filterConfig) {
        // 无状态过滤器
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest servletRequest)
                || !(response instanceof HttpServletResponse servletResponse)
                || !blocks(servletRequest.getDispatcherType())) {
            // 非目标环境 / 非 REQUEST 分派：注册模式之外的防御性兜底，完全不受影响
            chain.doFilter(request, response);
            return;
        }

        String presentedCredential = servletRequest.getHeader(SERVICE_CREDENTIAL_HEADER);
        if (presentedCredential != null && !presentedCredential.isBlank()
                && serviceCredentialMatches(presentedCredential)) {
            chain.doFilter(request, response);
            return;
        }

        String authorization = servletRequest.getHeader(AUTHORIZATION_HEADER);
        if (authorization != null
                && authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            String token = authorization.substring(BEARER_PREFIX.length()).trim();
            if (!token.isEmpty() && loginIdByToken.apply(token) != null) {
                chain.doFilter(request, response);
                return;
            }
        }

        boolean anyCredential = (presentedCredential != null && !presentedCredential.isBlank())
                || (authorization != null && !authorization.isBlank());
        writeReject(servletResponse,
                anyCredential ? P04AiErrorCode.DELEGATION_INVALID : P04AiErrorCode.AUTH_REQUIRED);
    }

    /** 常量时间比较；空配置（未设置属性）时服务凭证通道整体拒绝。 */
    private boolean serviceCredentialMatches(String presentedCredential) {
        return configuredCredential != null && !configuredCredential.isBlank()
                && MessageDigest.isEqual(configuredCredential.getBytes(StandardCharsets.UTF_8),
                        presentedCredential.getBytes(StandardCharsets.UTF_8));
    }

    /** 失败响应：HTTP status == body.code，符号码在 {@code data.errorCode}（与 DelegatedPrincipalFilter 同形）。 */
    private static void writeReject(HttpServletResponse response, P04AiErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.httpStatus());
        response.setContentType(CONTENT_TYPE_JSON);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(ERROR_MAPPER.writeValueAsString(
                ApiEnvelope.error(errorCode.httpStatus(), errorCode.message(), errorCode.name())));
    }

    /** 只服务 REQUEST 分派（与注册方约定一致，双保险断言）。 */
    static boolean blocks(DispatcherType type) {
        return type == DispatcherType.REQUEST;
    }
}
