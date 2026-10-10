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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F12-A1：/mcp 逐工具授权的行为判据（R12 卡1 残留#1 的消解判据）。
 *
 * <p><b>为什么必须有这一条。</b>认证修好之后，"任意有效登录令牌可达全部 10 个工具"
 * 仍是敞口：没有本判据，任何人删掉/绕过授权判定（最典型：把拒绝分支改回放行）
 * 都不会变红。矩阵把卡 D2 的口径逐条钉死：
 * <ul>
 *   <li>零授权 → 逐工具 403（全部 10 个）；空授权为空；</li>
 *   <li>子集授权 → 仅子集可达（读工具 7 个放行、写工具 3 个 403）；</li>
 *   <li>未知工具不泄露（与"已知但无权"同形同字节）；</li>
 *   <li>服务凭证路径维持现状（不读体、不判权、不触 resolver）；</li>
 *   <li>fail-closed：不可读请求体 / 超限请求体 / tools/call 缺工具名 / 重复键；
 *       授权事实源故障 → 503（不静默降级）；</li>
 *   <li>请求体原字节透传（下游 servlet 仍能读到完整 JSON）。</li>
 * </ul>
 *
 * <p>与认证过滤器的合链判据（401 矩阵逐字回归 + 403 增量）见
 * {@link #chainedAuthNThenAuthZKeeps401MatrixAndAdds403()}。
 */
@Tag("dev")
class McpToolAuthzFilterTest {

    private static final String TOKEN = "login-token-ok";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Function<String, Set<String>> NO_SCOPES = token -> Set.of();

    /** 与 RuoYiMcpLoginTokenScopes 同形：身份 scopes 是 ai:* 权限字符串。 */
    private static final Set<String> READ_ONLY_SCOPES = Set.of("ai:agent:execute");

    private static final Set<String> FULL_SCOPES = Set.of("ai:agent:execute", "ai:tool:sandbox:write");

    private static final List<String> READ_TOOLS = List.of(
            "current_date", "weather_query", "asset_query", "leave_query",
            "meeting_room_query", "ticket_query", "sales_query");

    private static final List<String> WRITE_TOOLS = List.of(
            "asset_renewal_submit", "leave_submit", "meeting_room_book");

    // ------------------------------------------------------------ 夹具

    private static MockHttpServletRequest loginTokenRequest(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        request.setAttribute(McpAuthFilter.ATTR_AUTH_MODE, McpAuthFilter.AUTH_MODE_LOGIN_TOKEN);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static String toolsCall(String tool) {
        return "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\""
                + tool + "\",\"arguments\":{}}}";
    }

    private record Outcome(boolean passed, HttpServletRequest seenRequest, MockHttpServletResponse response) {
    }

    private static Outcome run(McpToolAuthzFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> {
            passed.set(true);
            seen.set((HttpServletRequest) req);
        };
        filter.doFilter(request, response, chain);
        return new Outcome(passed.get(), seen.get(), response);
    }

    private static JsonNode body(MockHttpServletResponse response) throws Exception {
        JsonNode node = MAPPER.readTree(response.getContentAsString());
        assertThat(node.path("code").asInt()).as("HTTP status 必须等于 body.code")
                .isEqualTo(response.getStatus());
        return node;
    }

    private static String errorCode(MockHttpServletResponse response) throws Exception {
        return body(response).path("data").path("errorCode").asText();
    }

    private static void assertForbidden(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(errorCode(response)).isEqualTo("FORBIDDEN");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    private static MockHttpServletRequest serviceCredentialRequest(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader(McpAuthFilter.SERVICE_CREDENTIAL_HEADER, "configured");
        request.setAttribute(McpAuthFilter.ATTR_AUTH_MODE, McpAuthFilter.AUTH_MODE_SERVICE_CREDENTIAL);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    // ------------------------------------------------------------ 零授权 / 空授权为空

    @Test
    @DisplayName("零授权登录令牌：10 个工具逐个 tools/call 全部 403 FORBIDDEN，且不进入 servlet 链")
    void zeroScopeLoginTokenBlockedForAllTenTools() throws Exception {
        McpToolAuthzFilter filter = new McpToolAuthzFilter(NO_SCOPES);
        for (String tool : concat(READ_TOOLS, WRITE_TOOLS)) {
            Outcome outcome = run(filter, loginTokenRequest(toolsCall(tool)));
            assertThat(outcome.passed()).as("零授权不得到达 servlet（tool=%s）", tool).isFalse();
            assertForbidden(outcome.response());
        }
    }

    @Test
    @DisplayName("空授权为空（不是通配）：身份 scope 集合为空 ⇒ 读工具同样 403")
    void emptyScopeIsNotWildcard() throws Exception {
        Outcome outcome = run(new McpToolAuthzFilter(token -> Set.of()), loginTokenRequest(toolsCall("current_date")));
        assertThat(outcome.passed()).isFalse();
        assertForbidden(outcome.response());
    }

    // ------------------------------------------------------------ 子集授权

    @Test
    @DisplayName("子集授权仅子集可达：只有 agent.execute 时读工具 7 个放行、写工具 3 个 403")
    void partialScopesReachOnlySubset() throws Exception {
        McpToolAuthzFilter filter = new McpToolAuthzFilter(token -> READ_ONLY_SCOPES);
        for (String tool : READ_TOOLS) {
            Outcome outcome = run(filter, loginTokenRequest(toolsCall(tool)));
            assertThat(outcome.passed()).as("读工具应在 agent.execute 内可达（tool=%s）", tool).isTrue();
            assertThat(outcome.response().getStatus()).isEqualTo(200);
        }
        for (String tool : WRITE_TOOLS) {
            Outcome outcome = run(filter, loginTokenRequest(toolsCall(tool)));
            assertThat(outcome.passed()).as("写工具还要求 tool.sandbox.write（tool=%s）", tool).isFalse();
            assertForbidden(outcome.response());
        }
    }

    @Test
    @DisplayName("完整授权：agent.execute + tool.sandbox.write ⇒ 10 个工具全部可达且请求体可原样重读")
    void fullScopesReachAllTenToolsWithReadableBody() throws Exception {
        McpToolAuthzFilter filter = new McpToolAuthzFilter(token -> FULL_SCOPES);
        for (String tool : concat(READ_TOOLS, WRITE_TOOLS)) {
            String json = toolsCall(tool);
            Outcome outcome = run(filter, loginTokenRequest(json));
            assertThat(outcome.passed()).as("全量授权应可达（tool=%s）", tool).isTrue();
            assertThat(read(outcome.seenRequest())).as("下游必须能读回完整请求体（tool=%s）", tool)
                    .isEqualTo(json);
        }
    }

    // ------------------------------------------------------------ 未知工具 / 不泄露

    @Test
    @DisplayName("未知工具不泄露：全量 scope 下未知工具 403，响应与\"已知但无权\"逐字节同形")
    void unknownToolDeniedIndistinguishably() throws Exception {
        McpToolAuthzFilter full = new McpToolAuthzFilter(token -> FULL_SCOPES);
        Outcome unknown = run(full, loginTokenRequest(toolsCall("definitely-not-a-registered-tool")));
        assertThat(unknown.passed()).isFalse();
        assertForbidden(unknown.response());

        McpToolAuthzFilter partial = new McpToolAuthzFilter(token -> READ_ONLY_SCOPES);
        Outcome knownButForbidden = run(partial, loginTokenRequest(toolsCall("leave_submit")));
        assertThat(knownButForbidden.passed()).isFalse();
        assertThat(unknown.response().getContentAsString())
                .as("未知工具与已知但无权的拒绝必须同形，不给探测者区分度")
                .isEqualTo(knownButForbidden.response().getContentAsString());
    }

    // ------------------------------------------------------------ 非 tools/call / 服务凭证 / 防区外方法

    @Test
    @DisplayName("非 tools/call（initialize/tools/list/notifications）登录令牌放行，请求体原字节可重读")
    void nonToolCallMessagesPassThrough() throws Exception {
        McpToolAuthzFilter filter = new McpToolAuthzFilter(NO_SCOPES);
        List<String> bodies = List.of(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        for (String json : bodies) {
            Outcome outcome = run(filter, loginTokenRequest(json));
            assertThat(outcome.passed()).as("非工具调用不得被授权过滤器拦截: %s", json).isTrue();
            assertThat(read(outcome.seenRequest())).isEqualTo(json);
        }
    }

    @Test
    @DisplayName("服务凭证路径维持现状：不读体、不判权、不触 resolver，tools/call 直通")
    void serviceCredentialPathUntouched() throws Exception {
        AtomicBoolean resolverTouched = new AtomicBoolean();
        McpToolAuthzFilter filter = new McpToolAuthzFilter(token -> {
            resolverTouched.set(true);
            return Set.of();
        });
        for (String tool : WRITE_TOOLS) {
            Outcome outcome = run(filter, serviceCredentialRequest(toolsCall(tool)));
            assertThat(outcome.passed()).as("服务凭证路径不受登录令牌判定影响（tool=%s）", tool).isTrue();
            assertThat(outcome.response().getStatus()).isEqualTo(200);
        }
        assertThat(resolverTouched).as("服务凭证路径不得查询登录令牌 scope").isFalse();
    }

    @Test
    @DisplayName("非 POST（GET SSE/DELETE 会话终止）登录令牌直通：工具身份只存在于 POST 消息")
    void nonPostMethodsPassThrough() throws Exception {
        McpToolAuthzFilter filter = new McpToolAuthzFilter(NO_SCOPES);
        for (String method : List.of("GET", "DELETE")) {
            MockHttpServletRequest request = new MockHttpServletRequest(method, "/mcp");
            request.addHeader("Authorization", "Bearer " + TOKEN);
            request.setAttribute(McpAuthFilter.ATTR_AUTH_MODE, McpAuthFilter.AUTH_MODE_LOGIN_TOKEN);
            Outcome outcome = run(filter, request);
            assertThat(outcome.passed()).as("%s 无工具调用语义，不得被拦截", method).isTrue();
        }
    }

    @Test
    @DisplayName("认证交接缺位（无 ATTR_AUTH_MODE）时透传：认证 fail-closed 是 McpAuthFilter 的职责")
    void missingAuthModeMarkerPassesThrough() throws Exception {
        AtomicBoolean resolverTouched = new AtomicBoolean();
        McpToolAuthzFilter filter = new McpToolAuthzFilter(token -> {
            resolverTouched.set(true);
            return Set.of();
        });
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.setContent(toolsCall("current_date").getBytes(StandardCharsets.UTF_8));
        Outcome outcome = run(filter, request);
        assertThat(outcome.passed()).isTrue();
        assertThat(resolverTouched).isFalse();
    }

    // ------------------------------------------------------------ fail-closed

    @Test
    @DisplayName("fail-closed：不可解析请求体 / 重复键 / tools/call 缺工具名 ⇒ 登录令牌 403")
    void uninspectableBodiesFailClosed() throws Exception {
        McpToolAuthzFilter filter = new McpToolAuthzFilter(token -> FULL_SCOPES);
        List<String> bodies = List.of(
                "not-json-at-all",
                "",
                "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"\"}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"method\":\"tools/call\",\"params\":{\"name\":\"current_date\"}}",
                "42");
        for (String json : bodies) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
            request.addHeader("Authorization", "Bearer " + TOKEN);
            request.setAttribute(McpAuthFilter.ATTR_AUTH_MODE, McpAuthFilter.AUTH_MODE_LOGIN_TOKEN);
            request.setContent(json.getBytes(StandardCharsets.UTF_8));
            Outcome outcome = run(filter, request);
            assertThat(outcome.passed()).as("不可判定请求体必须 fail-closed: %s", json).isFalse();
            assertForbidden(outcome.response());
        }
    }

    @Test
    @DisplayName("fail-closed：请求体超过可检查上限 ⇒ 登录令牌 403；服务凭证不受限")
    void oversizedBodyFailsClosedForLoginTokenOnly() throws Exception {
        byte[] oversized = new byte[McpToolAuthzFilter.MAX_INSPECTED_BODY_BYTES + 1];
        java.util.Arrays.fill(oversized, (byte) 'x');

        MockHttpServletRequest login = new MockHttpServletRequest("POST", "/mcp");
        login.addHeader("Authorization", "Bearer " + TOKEN);
        login.setAttribute(McpAuthFilter.ATTR_AUTH_MODE, McpAuthFilter.AUTH_MODE_LOGIN_TOKEN);
        login.setContent(oversized);
        Outcome blocked = run(new McpToolAuthzFilter(NO_SCOPES), login);
        assertThat(blocked.passed()).isFalse();
        assertForbidden(blocked.response());

        MockHttpServletRequest service = new MockHttpServletRequest("POST", "/mcp");
        service.setAttribute(McpAuthFilter.ATTR_AUTH_MODE, McpAuthFilter.AUTH_MODE_SERVICE_CREDENTIAL);
        service.setContent(oversized);
        Outcome passed = run(new McpToolAuthzFilter(NO_SCOPES), service);
        assertThat(passed.passed()).isTrue();
    }

    @Test
    @DisplayName("批量数组同样逐工具判定：任一 tools/call 未授权 ⇒ 403；纯通知批量放行")
    void batchMessagesAreEnforcedPerTool() throws Exception {
        McpToolAuthzFilter filter = new McpToolAuthzFilter(token -> NO_SCOPES.apply(token));
        String batchWithCall = "[" + toolsCall("current_date") + ",{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}]";
        Outcome blocked = run(filter, loginTokenRequest(batchWithCall));
        assertThat(blocked.passed()).isFalse();
        assertForbidden(blocked.response());

        String notificationsOnly = "[{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}]";
        Outcome passed = run(filter, loginTokenRequest(notificationsOnly));
        assertThat(passed.passed()).isTrue();
        assertThat(read(passed.seenRequest())).isEqualTo(notificationsOnly);
    }

    @Test
    @DisplayName("授权事实源故障 ⇒ 503 AUTHORIZATION_UNAVAILABLE（fail-closed，不静默降级成 403/放行）")
    void scopeSourceFailureIsServiceUnavailable() throws Exception {
        McpToolAuthzFilter filter = new McpToolAuthzFilter(token -> {
            throw new IllegalStateException("scope source down");
        });
        Outcome outcome = run(filter, loginTokenRequest(toolsCall("current_date")));
        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.response().getStatus()).isEqualTo(503);
        assertThat(errorCode(outcome.response())).isEqualTo("AUTHORIZATION_UNAVAILABLE");
    }

    // ------------------------------------------------------------ 与认证过滤器的合链

    @Test
    @DisplayName("合链（认证→授权）：401 矩阵逐字不变；认证通过后登录令牌零授权由 403 接管")
    void chainedAuthNThenAuthZKeeps401MatrixAndAdds403() throws Exception {
        Function<String, Object> tokens = token -> TOKEN.equals(token) ? 1001L : null;
        McpAuthFilter auth = new McpAuthFilter("configured-credential", tokens);
        McpToolAuthzFilter authz = new McpToolAuthzFilter(NO_SCOPES);

        // A 无凭证 initialize → 401 AUTH_REQUIRED（授权过滤器不介入）
        MockHttpServletRequest noCredential = new MockHttpServletRequest("POST", "/mcp");
        noCredential.setContent("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}".getBytes(StandardCharsets.UTF_8));
        Outcome a = runChain(auth, authz, noCredential);
        assertThat(a.passed()).isFalse();
        assertThat(a.response().getStatus()).isEqualTo(401);
        assertThat(errorCode(a.response())).isEqualTo("AUTH_REQUIRED");

        // B 假 Bearer initialize → 401 DELEGATION_INVALID
        MockHttpServletRequest fake = new MockHttpServletRequest("POST", "/mcp");
        fake.addHeader("Authorization", "Bearer r12-fake-token");
        fake.setContent("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}".getBytes(StandardCharsets.UTF_8));
        Outcome b = runChain(auth, authz, fake);
        assertThat(b.passed()).isFalse();
        assertThat(b.response().getStatus()).isEqualTo(401);
        assertThat(errorCode(b.response())).isEqualTo("DELEGATION_INVALID");

        // C 有效服务凭证 initialize → 链达（授权路径不介入）
        MockHttpServletRequest service = new MockHttpServletRequest("POST", "/mcp");
        service.addHeader(McpAuthFilter.SERVICE_CREDENTIAL_HEADER, "configured-credential");
        service.setContent("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}".getBytes(StandardCharsets.UTF_8));
        Outcome c = runChain(auth, authz, service);
        assertThat(c.passed()).isTrue();
        assertThat(c.seenRequest().getAttribute(McpAuthFilter.ATTR_AUTH_MODE))
                .isEqualTo(McpAuthFilter.AUTH_MODE_SERVICE_CREDENTIAL);

        // D 有效登录令牌 initialize → 链达（非工具调用不判权）
        MockHttpServletRequest init = new MockHttpServletRequest("POST", "/mcp");
        init.addHeader("Authorization", "Bearer " + TOKEN);
        init.setContent("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}".getBytes(StandardCharsets.UTF_8));
        Outcome d = runChain(auth, authz, init);
        assertThat(d.passed()).isTrue();
        assertThat(d.seenRequest().getAttribute(McpAuthFilter.ATTR_AUTH_MODE))
                .isEqualTo(McpAuthFilter.AUTH_MODE_LOGIN_TOKEN);

        // E 有效登录令牌 tools/call 零授权 → 403 FORBIDDEN（本切片新增的判定）
        MockHttpServletRequest call = new MockHttpServletRequest("POST", "/mcp");
        call.addHeader("Authorization", "Bearer " + TOKEN);
        call.setContent(toolsCall("current_date").getBytes(StandardCharsets.UTF_8));
        Outcome e = runChain(auth, authz, call);
        assertThat(e.passed()).isFalse();
        assertForbidden(e.response());
    }

    private static Outcome runChain(McpAuthFilter auth, McpToolAuthzFilter authz, MockHttpServletRequest request)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
        FilterChain tail = (req, res) -> {
            passed.set(true);
            seen.set((HttpServletRequest) req);
        };
        auth.doFilter(request, response, (req, res) -> authz.doFilter(req, res, tail));
        return new Outcome(passed.get(), seen.get(), response);
    }

    private static String read(HttpServletRequest request) throws Exception {
        StringBuilder text = new StringBuilder();
        java.io.BufferedReader reader = request.getReader();
        char[] buffer = new char[1024];
        int count;
        while ((count = reader.read(buffer)) >= 0) {
            text.append(buffer, 0, count);
        }
        return text.toString();
    }

    private static List<String> concat(List<String> left, List<String> right) {
        java.util.ArrayList<String> all = new java.util.ArrayList<>(left);
        all.addAll(right);
        return all;
    }
}
