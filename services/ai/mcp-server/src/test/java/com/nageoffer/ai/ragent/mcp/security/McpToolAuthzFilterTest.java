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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.AuthorizationChecker;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 9099 逐工具授权过滤器判据（SPEC-9099 §2.2/J3，直接 new 驱动，不起容器）：
 * 白名单外 403、无权 403、事实源故障 503、非 tools/call 放行且 body 原样回放、
 * 不可解析/超限/缺主体一律 fail-closed。
 */
@Tag("dev")
class McpToolAuthzFilterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AuthorizationChecker checker;
    private McpToolAuthzFilter filter;

    @BeforeEach
    void setUp() {
        PrincipalContext.clear();
        checker = mock(AuthorizationChecker.class);
        filter = new McpToolAuthzFilter(checker);
        PrincipalContext.set(new ExecutionPrincipal("T1", "42", "platform:T1:42", 1, 7,
                Set.of("ai:agent:execute"), "jti-1", "platform", 1_700_000_000L, 1_700_000_060L));
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    private static MockHttpServletRequest post(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/ai/v1/mcp");
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private record Outcome(MockHttpServletResponse response, boolean chainInvoked, byte[] chainBody) {

        int status() {
            return response.getStatus();
        }

        String errorCode() throws Exception {
            JsonNode node = MAPPER.readTree(response.getContentAsString());
            return node.path("data").path("errorCode").asText(null);
        }

        int bodyCode() throws Exception {
            return MAPPER.readTree(response.getContentAsString()).path("code").intValue();
        }
    }

    private Outcome run(MockHttpServletRequest request) throws Exception {
        boolean[] invoked = {false};
        byte[][] seen = new byte[1][];
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            invoked[0] = true;
            seen[0] = req.getInputStream().readAllBytes();
        });
        return new Outcome(response, invoked[0], seen[0]);
    }

    private static String toolCall(String name) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\""
                + name + "\",\"arguments\":{}}}";
    }

    @Test
    @DisplayName("白名单内+有权：放行，按逐工具资源引用在线判定，body 原字节回放")
    void allowedToolCallPassesWithBodyIntact() throws Exception {
        when(checker.check(any(), eq("agent.execute"), eq("mcp:tool:current_date")))
                .thenReturn(new AuthorizationChecker.AuthorizeResult(true, 1));
        String body = toolCall("current_date");

        Outcome outcome = run(post(body));

        assertThat(outcome.chainInvoked()).isTrue();
        assertThat(outcome.chainBody()).isEqualTo(body.getBytes(StandardCharsets.UTF_8));
        verify(checker).check(any(), eq("agent.execute"), eq("mcp:tool:current_date"));
    }

    @Test
    @DisplayName("白名单外（写类/出网/未知工具）：403 FORBIDDEN，事实源零调用、链路不进入")
    void nonWhitelistedToolRejected403() throws Exception {
        for (String tool : new String[]{"pay_order", "create_order", "set_cart_item",
                "apply_after_sale", "weather_query", "youcom_search", "no_such_tool"}) {
            Outcome outcome = run(post(toolCall(tool)));
            assertThat(outcome.status()).as("tool=%s", tool).isEqualTo(403);
            assertThat(outcome.errorCode()).as("tool=%s", tool).isEqualTo("FORBIDDEN");
            assertThat(outcome.chainInvoked()).as("tool=%s", tool).isFalse();
        }
        verify(checker, never()).check(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("事实源判无权（403）：透传符号码，链路不进入")
    void permissionDeniedFromFactSourceRejected403() throws Exception {
        when(checker.check(any(), anyString(), anyString()))
                .thenThrow(new P04AiException(P04AiErrorCode.FORBIDDEN));

        Outcome outcome = run(post(toolCall("query_order")));

        assertThat(outcome.status()).isEqualTo(403);
        assertThat(outcome.errorCode()).isEqualTo("FORBIDDEN");
        assertThat(outcome.chainInvoked()).isFalse();
    }

    @Test
    @DisplayName("事实源故障（503）：透传 AUTHORIZATION_UNAVAILABLE，不降级放行")
    void factSourceDownRejected503() throws Exception {
        when(checker.check(any(), anyString(), anyString()))
                .thenThrow(new P04AiException(P04AiErrorCode.AUTHORIZATION_UNAVAILABLE));

        Outcome outcome = run(post(toolCall("query_order")));

        assertThat(outcome.status()).isEqualTo(503);
        assertThat(outcome.errorCode()).isEqualTo("AUTHORIZATION_UNAVAILABLE");
        assertThat(outcome.chainInvoked()).isFalse();
    }

    @Test
    @DisplayName("非 tools/call（initialize/tools/list）：不判定、不读事实源，body 原样回放")
    void nonToolCallMethodsPassThrough() throws Exception {
        String initialize = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
        String list = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}";

        Outcome first = run(post(initialize));
        Outcome second = run(post(list));

        assertThat(first.chainInvoked()).isTrue();
        assertThat(first.chainBody()).isEqualTo(initialize.getBytes(StandardCharsets.UTF_8));
        assertThat(second.chainInvoked()).isTrue();
        verify(checker, never()).check(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("GET/DELETE（SSE 与会话终止）：无工具调用语义，直接放行")
    void nonPostMethodsPassThrough() throws Exception {
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/internal/ai/v1/mcp");

        Outcome outcome = run(get);

        assertThat(outcome.chainInvoked()).isTrue();
        verify(checker, never()).check(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("主体缺席（验签过滤器未先行）：503 fail-closed，事实源零调用")
    void missingPrincipalRejected503() throws Exception {
        PrincipalContext.clear();

        Outcome outcome = run(post(toolCall("current_date")));

        assertThat(outcome.status()).isEqualTo(503);
        assertThat(outcome.errorCode()).isEqualTo("AUTHORIZATION_UNAVAILABLE");
        assertThat(outcome.chainInvoked()).isFalse();
        verify(checker, never()).check(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("不可解析 body（重复键/非对象/尾随内容）：403 fail-closed，事实源零调用")
    void uninspectableBodyRejected403() throws Exception {
        String duplicateKey = "{\"method\":\"tools/call\",\"method\":\"tools/call\",\"params\":{\"name\":\"current_date\"}}";
        String arrayBody = "[{\"method\":\"tools/call\",\"params\":{\"name\":\"current_date\"}}]";
        String trailing = "{\"method\":\"tools/list\"}{\"extra\":true}";

        for (String body : new String[]{duplicateKey, arrayBody, trailing}) {
            Outcome outcome = run(post(body));
            assertThat(outcome.status()).as("body=%s", body).isEqualTo(403);
            assertThat(outcome.chainInvoked()).as("body=%s", body).isFalse();
        }
        verify(checker, never()).check(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("tools/call 缺工具名：403 fail-closed")
    void missingToolNameRejected403() throws Exception {
        Outcome outcome = run(post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{}}"));

        assertThat(outcome.status()).isEqualTo(403);
        assertThat(outcome.chainInvoked()).isFalse();
    }

    @Test
    @DisplayName("body 超可检查上限：403 fail-closed（无法判定即可达性）")
    void oversizedBodyRejected403() throws Exception {
        String oversized = "a".repeat(McpToolAuthzFilter.MAX_INSPECTED_BODY_BYTES + 1);

        Outcome outcome = run(post(oversized));

        assertThat(outcome.status()).isEqualTo(403);
        assertThat(outcome.chainInvoked()).isFalse();
        verify(checker, never()).check(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("拒绝形状：HTTP status == body.code，符号码在 data.errorCode")
    void rejectionShapeMatchesEnvelopeContract() throws Exception {
        Outcome outcome = run(post(toolCall("pay_order")));

        assertThat(outcome.bodyCode()).isEqualTo(outcome.status()).isEqualTo(403);
    }
}
