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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
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
import org.ruoyi.ai.api.action.AiCanonicalAction;
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
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * /mcp 逐工具授权过滤器（F12-A1 · R12 卡1 残留#1 的消解本体）。
 *
 * <p><b>为什么需要它。</b>{@link McpAuthFilter}（认证）修好之后，"任意有效登录令牌
 * 可达全部 10 个工具"仍是敞口 —— 认证≠授权。本过滤器补上缺失的 authZ 判定：
 * <b>只有登录令牌路径</b>、<b>只有 {@code tools/call} 请求</b>、<b>只按
 * {@link McpToolAccessPolicy} 的冻结映射</b>判定；不通过 → HTTP 403
 * {@code FORBIDDEN}（形状与 401 同源，{@link McpAuthFilter#writeReject} 单一出处），
 * 请求不再进入 /mcp servlet，工具不会执行。
 *
 * <p><b>为什么必须在 servlet 过滤层而不是 MCP 工具处理器里。</b>一是判定形状：
 * MCP SDK 的工具处理器异常/错误结果只会进 200 响应体（JSON-RPC error / SSE 帧），
 * 拿不到 HTTP 403，验收判据"零授权 → 逐工具 403"无法成立；二是判定点必须在
 * 工具执行之前、与"到达哪个工具"同一条请求路径上闭合。因此本过滤器读 POST 请求体
 * 只为<b>取方法名与工具名</b>（协议载体，非业务数据），随后把请求体原字节重新暴露
 * 给下游 servlet —— streamable-http 的 SSE（GET）与收尾（DELETE）无请求体，
 * 不受影响。
 *
 * <p><b>边界（D3 书面口径）。</b>职责严格切分：{@link McpAuthFilter}＝仅认证
 * （建立/拒绝身份，401 归它）；本过滤器＝仅授权（判定工具可达性，403 归它）。
 * 判定上下文由认证过滤器通过请求属性 {@link McpAuthFilter#ATTR_AUTH_MODE} 交接：
 * <ul>
 *   <li><b>服务凭证路径</b>：不介入 —— 不看请求体、不包请求、不判权限，
 *       request/response 原样透传（与卡1 语义连续，授权正链不受影响）；</li>
 *   <li><b>登录令牌路径</b>：仅 POST 请求体被检查；{@code tools/call} 按冻结映射
 *       逐工具判定，非 {@code tools/call}（initialize / tools/list / notifications）
 *       原样放行；</li>
 *   <li><b>fail-closed</b>：请求体无法读全（超过 {@link #MAX_INSPECTED_BODY_BYTES}）、
 *       无法解析（含重复键）、{@code tools/call} 缺工具名、或工具不在映射表内 ——
 *       一律 403，绝不回落到放行。认证过滤器缺位（无 {@code ATTR_AUTH_MODE}）时
 *       本过滤器透传：认证是 {@link McpAuthFilter} 的 fail-closed 职责，本过滤器
 *       不复刻第二套认证。</li>
 * </ul>
 *
 * <p><b>授权事实来源。</b>登录令牌持有的 scope 集合由 {@link McpLoginTokenScopes}
 * 解析（platform 权威事实，装配方注入）；映射表中的 canonical 动作经
 * {@link AiCanonicalAction} 精确换算成 {@code ai:*} 权限后做<b>集合包含</b>判定，
 * 无通配豁免；空 scope 集合 = 无任何授权（"空授权为空"）。
 *
 * <p><b>口径边界（勿越界声称）。</b>本过滤器证明的是"登录令牌路径逐工具授权"的
 * <b>机制</b>（默认拒绝、空授权为空、子集授权、未知工具不泄露），映射对象是当前
 * 10 个演示工具（不落库的演示实现）；真实工具接入时的工具-权限业务映射属 B 面。
 * 本过滤器不声称跨服务身份/租户隔离（AGENTS.md 既有声明不因本切片变化）。
 */
public class McpToolAuthzFilter implements Filter {

    /** 单次可检查的请求体上限：MCP 控制面消息（initialize/tools/call）远小于此；超限即无法判定。 */
    static final int MAX_INSPECTED_BODY_BYTES = 256 * 1024;

    private static final Logger log = LoggerFactory.getLogger(McpToolAuthzFilter.class);

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private static final String BEARER_PREFIX = "Bearer ";

    private static final String POST_METHOD = "POST";

    private static final String METHOD_FIELD = "method";

    private static final String PARAMS_FIELD = "params";

    private static final String NAME_FIELD = "name";

    private static final String TOOLS_CALL_METHOD = "tools/call";

    /**
     * 严格解析：重复键（解析器差异的经典利用面）与尾随内容直接判不可读 ⇒ 登录令牌 403。
     */
    private static final ObjectMapper INSPECTION_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** 登录令牌 → 该身份持有的 {@code ai:*} scope（空集 = 无授权；不可解析亦为空集）。 */
    private final Function<String, Set<String>> scopesOfToken;

    public McpToolAuthzFilter(Function<String, Set<String>> scopesOfToken) {
        this.scopesOfToken = scopesOfToken;
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
                || !McpAuthFilter.blocks(servletRequest.getDispatcherType())) {
            chain.doFilter(request, response);
            return;
        }

        // 只接登录令牌路径：服务凭证（或认证过滤器未参与）时不介入，行为与卡1 完全一致
        Object authMode = servletRequest.getAttribute(McpAuthFilter.ATTR_AUTH_MODE);
        if (!McpAuthFilter.AUTH_MODE_LOGIN_TOKEN.equals(authMode)) {
            chain.doFilter(request, response);
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

        BodyInspection inspection = inspect(body, charsetOf(servletRequest));
        if (!inspection.readable()) {
            reject(servletResponse, servletRequest, "uninspectable-body", null);
            return;
        }
        if (inspection.toolCallNames().isEmpty()) {
            // 非 tools/call（initialize / tools/list / notifications / response）：
            // 无工具可达性可判，原样放行；请求体以原字节重新暴露给下游 servlet
            chain.doFilter(new CachedBodyRequest(servletRequest, body), response);
            return;
        }

        Set<String> grantedScopes;
        try {
            grantedScopes = scopesOfToken.apply(bearerToken(servletRequest));
        } catch (RuntimeException unavailable) {
            // 授权事实源故障：fail-closed，且与运行面既有拒绝语义一致（503 AUTHORIZATION_UNAVAILABLE）
            log.warn("mcp tool authz unavailable errorCode={} method={} path={}",
                    P04AiErrorCode.AUTHORIZATION_UNAVAILABLE.name(), servletRequest.getMethod(),
                    servletRequest.getRequestURI());
            McpAuthFilter.writeReject(servletResponse, P04AiErrorCode.AUTHORIZATION_UNAVAILABLE);
            return;
        }
        if (grantedScopes == null) {
            grantedScopes = Set.of();
        }
        for (String toolName : inspection.toolCallNames()) {
            Set<String> requiredActions = McpToolAccessPolicy.requiredActions(toolName).orElse(null);
            if (requiredActions == null || !holdsAll(grantedScopes, requiredActions)) {
                // 未登记工具与有登记但无权：同形拒绝，不泄露工具是否存在
                reject(servletResponse, servletRequest, "tool-not-authorized", toolName);
                return;
            }
        }
        chain.doFilter(new CachedBodyRequest(servletRequest, body), response);
    }

    /** 精确换算 canonical 动作 → {@code ai:*} 权限并做包含判定（无通配豁免）。 */
    private static boolean holdsAll(Set<String> grantedScopes, Set<String> requiredActions) {
        for (String action : requiredActions) {
            String permission = AiCanonicalAction.permissionOf(action).orElse(null);
            if (permission == null || !grantedScopes.contains(permission)) {
                return false;
            }
        }
        return true;
    }

    /** 403 拒绝：形状与 401 同源（HTTP status == body.code，符号码在 data.errorCode）。 */
    private static void reject(HttpServletResponse response, HttpServletRequest request,
                               String reason, String toolName) throws IOException {
        // 审计：只记符号事实，不记凭证值（工具名不是凭证，用于定位策略缺口）
        log.warn("mcp tool authz rejected errorCode={} reason={} method={} path={} tool={}",
                P04AiErrorCode.FORBIDDEN.name(), reason, request.getMethod(), request.getRequestURI(), toolName);
        McpAuthFilter.writeReject(response, P04AiErrorCode.FORBIDDEN);
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

    private static String bearerToken(HttpServletRequest request) {
        String authorization = request.getHeader(AUTHORIZATION_HEADER);
        if (authorization != null
                && authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return authorization.substring(BEARER_PREFIX.length()).trim();
        }
        return "";
    }

    private static Charset charsetOf(HttpServletRequest request) {
        String encoding = request.getCharacterEncoding();
        if (encoding == null || encoding.isBlank()) {
            // 与 platform 默认请求编码（UTF-8）一致；只影响登录令牌路径的 JSON 解码
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(encoding);
        } catch (Exception unsupported) {
            return StandardCharsets.UTF_8;
        }
    }

    /**
     * 检查请求体：找出全部 {@code tools/call} 的工具名。
     *
     * <p>{@code readable=false} = 无法确认（解析失败/结构不符/缺工具名），调用方 fail-closed。
     * 支持 JSON-RPC 单消息与批量数组（任一元素为 tools/call 即纳入判定）。
     */
    static BodyInspection inspect(byte[] body, Charset charset) {
        if (body == null || body.length == 0) {
            return BodyInspection.unreadable();
        }
        JsonNode root;
        try {
            root = INSPECTION_MAPPER.readTree(new String(body, charset));
        } catch (Exception unparseable) {
            return BodyInspection.unreadable();
        }
        if (root == null) {
            return BodyInspection.unreadable();
        }
        Set<String> names = new LinkedHashSet<>();
        if (root.isArray()) {
            if (root.isEmpty()) {
                // 空批量：没有可执行的工具调用（servlet 会按协议拒绝整个消息）
                return BodyInspection.readable(names);
            }
            for (JsonNode element : root) {
                InspectionStep step = inspectMessage(element);
                if (step.unreadable()) {
                    return BodyInspection.unreadable();
                }
                if (step.name() != null) {
                    names.add(step.name());
                }
            }
            return BodyInspection.readable(names);
        }
        if (!root.isObject()) {
            return BodyInspection.unreadable();
        }
        InspectionStep step = inspectMessage(root);
        if (step.unreadable()) {
            return BodyInspection.unreadable();
        }
        if (step.name() != null) {
            names.add(step.name());
        }
        return BodyInspection.readable(names);
    }

    /** 单条 JSON-RPC 消息：UNREADABLE / 非 tools/call（name=null）/ tools/call（name=工具名）。 */
    private static InspectionStep inspectMessage(JsonNode message) {
        if (message == null || !message.isObject()) {
            return InspectionStep.UNREADABLE;
        }
        JsonNode methodNode = message.get(METHOD_FIELD);
        if (methodNode == null) {
            // 无 method：JSON-RPC 响应（客户端对 server→client 回调的应答）等，非工具调用
            return InspectionStep.NOT_TOOL_CALL;
        }
        if (!methodNode.isTextual()) {
            return InspectionStep.UNREADABLE;
        }
        if (!TOOLS_CALL_METHOD.equals(methodNode.asText())) {
            return InspectionStep.NOT_TOOL_CALL;
        }
        JsonNode params = message.get(PARAMS_FIELD);
        JsonNode nameNode = params == null ? null : params.get(NAME_FIELD);
        if (nameNode == null || !nameNode.isTextual() || nameNode.asText().isBlank()) {
            // tools/call 却取不到工具名：无法判定 ⇒ fail-closed
            return InspectionStep.UNREADABLE;
        }
        return new InspectionStep(nameNode.asText(), false);
    }

    /** 请求体检查结果：readable=false 视为无法确认。 */
    record BodyInspection(boolean readable, Set<String> toolCallNames) {

        static BodyInspection readable(Set<String> toolCallNames) {
            return new BodyInspection(true, Set.copyOf(toolCallNames));
        }

        static BodyInspection unreadable() {
            return new BodyInspection(false, Set.of());
        }
    }

    /** 单条消息检查结果：unreadable 哨兵 / 非 tools/call（name=null）/ tools/call（name=工具名）。 */
    private record InspectionStep(String name, boolean unreadable) {

        static final InspectionStep UNREADABLE = new InspectionStep(null, true);

        static final InspectionStep NOT_TOOL_CALL = new InspectionStep(null, false);
    }

    /**
     * 检查过的请求体以原字节重新暴露（下游 MCP servlet 用 {@code getReader()} 读流，
     * 包裹后逐字节等价，SSE/中文内容不受影响）。
     */
    static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CachedBodyInputStream(body);
        }

        @Override
        public BufferedReader getReader() throws IOException {
            // 与过滤器解析请求体用同一字符集，保证解码字节一致
            return new BufferedReader(new InputStreamReader(getInputStream(),
                    charsetOf((HttpServletRequest) getRequest())));
        }
    }

    /** 内存字节流；同步 servlet 语义，不支持异步监听。 */
    private static final class CachedBodyInputStream extends ServletInputStream {

        private final ByteArrayInputStream delegate;

        private CachedBodyInputStream(byte[] body) {
            this.delegate = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            return delegate.read(bytes, offset, length);
        }

        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            // 不发异步通知：请求体已整段在内存，消费方按阻塞读即可
        }
    }

    /** 请求体超过可检查上限。 */
    private static final class BodyTooLargeException extends IOException {
    }
}
