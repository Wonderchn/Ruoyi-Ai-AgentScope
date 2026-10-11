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

package com.nageoffer.ai.ragent.mcp.security;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.framework.security.AuthorizationChecker;
import com.nageoffer.ai.ragent.framework.security.DelegatedPrincipal;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 9099 受保护 MCP 面的逐工具授权过滤器（SPEC-9099 §2.2/§7，T0 2026-10-11）。
 *
 * <p><b>在链路中的位置。</b>注册在受保护前缀（{@code /internal/ai/v1/*}）上、
 * 排在委托验签过滤器（{@link com.nageoffer.ai.ragent.framework.security.DelegatedPrincipalFilter}，
 * 顺序 {@code Integer.MIN_VALUE + 200}）之后——认证已由它完成，主体在
 * {@link PrincipalContext} 里；本过滤器只判"这个身份能不能调这个工具"（authZ），
 * 403/503 拒绝形状与 401 同源（HTTP status == body.code，符号码在 {@code data.errorCode}）。
 *
 * <p><b>判定语义（fail-closed，无默认放行）。</b>
 * <ul>
 *   <li><b>主体闸门</b>：到达受保护端点的每个 REQUEST 分派请求都要求主体已由委托验签链建立
 *       （{@link PrincipalContext#get()} 非空）；缺席一律 401 {@code AUTH_REQUIRED}——
 *       容器按归一化路径做映射、而 framework 内部前缀自检用 raw URI，路径变体
 *       （{@code //}、{@code /./}、{@code %2e}、{@code ;} 路径参数）会绕过后者但<b>不会</b>
 *       绕过本过滤器注册面，本闸门即在该处兜住（A1 复核 F1 修复，2026-10-11）；</li>
 *   <li>只有 {@code POST} 的 JSON-RPC 消息带工具调用语义；仅 {@code tools/call} 判定，
 *       其余方法（initialize / tools/list / notifications）原样放行；</li>
 *   <li>工具名取自请求体（协议载体，非业务数据），随后把请求体<b>原字节</b>重新暴露给
 *       下游 servlet——streamable-http 的 GET（SSE）/DELETE（会话终止）不受影响；</li>
 *   <li>工具→必需 canonical 动作来自 {@link McpDelegatedToolPolicy}（零号源，未登记工具
 *       一律拒绝）；逐个动作向平台授权事实源在线复核
 *       （{@link AuthorizationChecker}，HTTP 事实端口，不与管理面共库）；</li>
 *   <li>请求体不可读（超限）、不可解析（含重复键/尾随内容）、非对象（含批数组）、
 *       {@code tools/call} 缺工具名、主体缺席——一律拒绝，绝不回落放行；
 *       事实源故障（超时/坏响应/连接失败）按 503 {@code AUTHORIZATION_UNAVAILABLE}
 *       透传，不降级。</li>
 * </ul>
 *
 * <p><b>不读不写。</b>不改请求头、不动响应；通过时只做请求体的一次性缓存包装。
 */
public class McpToolAuthzFilter implements Filter {

    /** 单次可检查的请求体上限：MCP 控制面消息（initialize/tools/call）远小于此；超限即无法判定。 */
    static final int MAX_INSPECTED_BODY_BYTES = 256 * 1024;

    private static final Logger log = LoggerFactory.getLogger(McpToolAuthzFilter.class);

    private static final String POST_METHOD = "POST";

    private static final String METHOD_FIELD = "method";

    private static final String PARAMS_FIELD = "params";

    private static final String NAME_FIELD = "name";

    private static final String TOOLS_CALL_METHOD = "tools/call";

    /** 工具调用的资源引用前缀（平台端只回显用于证据关联，唯一性来自工具名）。 */
    static final String RESOURCE_REF_PREFIX = "mcp:tool:";

    /**
     * 严格解析：重复键（解析器差异的经典利用面）与尾随内容直接判不可读 ⇒ 拒绝。
     */
    private static final ObjectMapper INSPECTION_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final ObjectMapper ERROR_MAPPER = new ObjectMapper();

    /** 平台授权事实源（HTTP 事实端口客户端；缺失时装配即失败，见 McpSecurityConfiguration）。 */
    private final AuthorizationChecker checker;

    public McpToolAuthzFilter(AuthorizationChecker checker) {
        this.checker = checker;
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
                || servletRequest.getDispatcherType() != DispatcherType.REQUEST) {
            // 非目标环境 / 非 REQUEST 分派：注册模式之外的防御性兜底
            chain.doFilter(request, response);
            return;
        }

        // ===== 主体闸门（A1 复核 F1 修复）：到达受保护端点的【每个】请求都必须已有主体。 =====
        // 背景：容器按"解码+归一化"后的路径做过滤器/servlet 映射，而 framework 委托验签过滤器
        // 的内部前缀自检用 raw getRequestURI()（DelegatedPrincipalFilter.isProtectedPath）——
        // `//internal/ai/v1/mcp`、`/./…`、`/%2e/…` 等变体虽命中映射（本过滤器照常执行），
        // 却在 framework 自检处被判"非前缀"而整链跳过鉴权。本闸门在注册面内兜住该缺口：
        // 主体缺席（无论请求形态：initialize / tools/list / GET SSE / DELETE / tools/call）
        // 一律 401 AUTH_REQUIRED，与"无凭据"同形；工具执行面的判定在其后照旧。
        ExecutionPrincipal gatedPrincipal = PrincipalContext.get();
        if (gatedPrincipal == null) {
            log.warn("mcp principal gate rejected errorCode={} method={} path={}",
                    P04AiErrorCode.AUTH_REQUIRED.name(), servletRequest.getMethod(),
                    servletRequest.getRequestURI());
            writeReject(servletResponse, P04AiErrorCode.AUTH_REQUIRED);
            return;
        }

        // 工具身份只存在于 POST 的 JSON-RPC 消息里；GET（SSE）/DELETE（会话终止）无工具调用语义
        if (!POST_METHOD.equalsIgnoreCase(servletRequest.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        byte[] body;
        try {
            body = readBounded(servletRequest, MAX_INSPECTED_BODY_BYTES);
        } catch (BodyTooLargeException tooLarge) {
            reject(servletResponse, servletRequest, "body-too-large", null);
            return;
        }

        Inspection inspection = inspect(body, charsetOf(servletRequest));
        if (!inspection.readable()) {
            reject(servletResponse, servletRequest, "uninspectable-body", null);
            return;
        }
        if (!inspection.toolCall()) {
            // 非 tools/call（initialize / tools/list / notifications / response）：无工具
            // 可达性可判，原样放行；请求体以原字节重新暴露给下游 servlet
            chain.doFilter(new CachedBodyRequest(servletRequest, body), response);
            return;
        }

        String toolName = inspection.toolName();
        if (toolName == null || toolName.isBlank()) {
            reject(servletResponse, servletRequest, "tool-name-missing", null);
            return;
        }

        Set<String> requiredActions = McpDelegatedToolPolicy.requiredActions(toolName).orElse(null);
        if (requiredActions == null) {
            // 未登记工具（白名单外，含写类/出网/未知名字）：与无权同形拒绝，不泄露差异
            reject(servletResponse, servletRequest, "tool-not-authorized", toolName);
            return;
        }

        DelegatedPrincipal delegated = new DelegatedPrincipal(gatedPrincipal.issuer(), gatedPrincipal.tenantId(),
                gatedPrincipal.userId(), gatedPrincipal.membershipId(), gatedPrincipal.policyVersion(),
                gatedPrincipal.scopes(), gatedPrincipal.jti());
        String resourceRef = RESOURCE_REF_PREFIX + toolName;
        for (String action : requiredActions) {
            AuthorizationChecker.AuthorizeResult result;
            try {
                result = checker.check(delegated, action, resourceRef);
            } catch (P04AiException authorizationError) {
                // 平台事实源拒绝（403/409）或故障（503）：透传同一符号码，不降级
                log.warn("mcp tool authz rejected errorCode={} method={} path={} tool={}",
                        authorizationError.errorCode().name(), servletRequest.getMethod(),
                        servletRequest.getRequestURI(), toolName);
                writeReject(servletResponse, authorizationError.errorCode());
                return;
            } catch (RuntimeException unavailable) {
                log.warn("mcp tool authz unavailable type={} method={} path={} tool={}",
                        unavailable.getClass().getSimpleName(), servletRequest.getMethod(),
                        servletRequest.getRequestURI(), toolName);
                writeReject(servletResponse, P04AiErrorCode.AUTHORIZATION_UNAVAILABLE);
                return;
            }
            if (result == null || !result.allowed()) {
                reject(servletResponse, servletRequest, "tool-not-authorized", toolName);
                return;
            }
        }
        chain.doFilter(new CachedBodyRequest(servletRequest, body), response);
    }

    @Override
    public void destroy() {
        // 无需销毁
    }

    /** 403 拒绝：形状与 401 同源（HTTP status == body.code，符号码在 data.errorCode）。 */
    private static void reject(HttpServletResponse response, HttpServletRequest request,
                               String reason, String toolName) throws IOException {
        // 审计：只记符号事实，不记凭证值（工具名不是凭证，用于定位策略缺口）
        log.warn("mcp tool authz rejected errorCode={} reason={} method={} path={} tool={}",
                P04AiErrorCode.FORBIDDEN.name(), reason, request.getMethod(),
                request.getRequestURI(), toolName);
        writeReject(response, P04AiErrorCode.FORBIDDEN);
    }

    /** 拒绝响应写出（HTTP status == body.code，符号码在 data.errorCode）。 */
    static void writeReject(HttpServletResponse response, P04AiErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.httpStatus());
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(ERROR_MAPPER.writeValueAsString(
                ApiEnvelope.error(errorCode.httpStatus(), errorCode.message(), errorCode.name())));
    }

    /** 请求体检查结果：可读性 + 是否 tools/call + 工具名（仅单条 JSON-RPC 消息）。 */
    record Inspection(boolean readable, boolean toolCall, String toolName) {

        static Inspection unreadable() {
            return new Inspection(false, false, null);
        }
    }

    /**
     * 解析请求体并定位工具名：根必须是 JSON 对象（批数组与标量一律 fail-closed），
     * 只有 {@code method == tools/call} 才进入授权判定。
     */
    private static Inspection inspect(byte[] body, Charset charset) {
        JsonNode root;
        try {
            root = INSPECTION_MAPPER.readTree(new String(body, charset));
        } catch (Exception unreadable) {
            return Inspection.unreadable();
        }
        if (root == null || !root.isObject()) {
            return Inspection.unreadable();
        }
        if (!TOOLS_CALL_METHOD.equals(root.path(METHOD_FIELD).asText(null))) {
            return new Inspection(true, false, null);
        }
        return new Inspection(true, true, root.path(PARAMS_FIELD).path(NAME_FIELD).asText(null));
    }

    /** 读取整段请求体，超过上限立即中断（fail-closed 由调用方执行）。 */
    private static byte[] readBounded(HttpServletRequest request, int maxBytes) throws IOException {
        InputStream in = request.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) >= 0) {
            total += read;
            if (total > maxBytes) {
                throw new BodyTooLargeException();
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static Charset charsetOf(HttpServletRequest request) {
        String encoding = request.getCharacterEncoding();
        if (encoding == null || encoding.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(encoding);
        } catch (Exception unsupported) {
            return StandardCharsets.UTF_8;
        }
    }

    /** 请求体已读字节的原样回放包装：不改变方法/头/参数，只让下游再读一次。 */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    return input.read(buffer, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("synchronous cached body");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    /** 请求体超过可检查上限：无法判定即可达性，调用方按 403 fail-closed。 */
    private static final class BodyTooLargeException extends IOException {
    }
}
