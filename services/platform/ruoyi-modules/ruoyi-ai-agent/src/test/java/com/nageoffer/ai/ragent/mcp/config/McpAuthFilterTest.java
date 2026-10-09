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
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R12 卡1 · F-W9-1：{@code /mcp} 鉴权过滤器的行为判据。
 *
 * <p><b>为什么必须有这一条。</b>{@code /mcp} 是 servlet 注册，绕过 Spring MVC 的
 * Sa-Token 拦截器——W9 实测无凭证/假凭证可 initialize、tools/list、tools/call 全通。
 * 过滤器是修法本体，本判据把"无凭证必须精确拒（举证 errorCode 判决）、
 * 服务凭证或登录令牌二者之一有效才放行、空配置一律拒绝（fail-closed）"钉死；
 * 少了它，任何人把拒绝分支改回放行（最典型：注释掉 writeReject）都不会变红。
 */
@Tag("dev")
class McpAuthFilterTest {

    private static final String CONFIGURED = "r12-service-credential";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Function<String, Object> NO_TOKENS = token -> null;

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/mcp");
    }

    private static String errorCode(MockHttpServletResponse response) throws Exception {
        JsonNode body = MAPPER.readTree(response.getContentAsString());
        assertThat(body.path("code").asInt()).as("HTTP status 必须等于 body.code")
                .isEqualTo(response.getStatus());
        return body.path("data").path("errorCode").asText();
    }

    @Test
    @DisplayName("无任何凭证 → 401 AUTH_REQUIRED，且不进入 servlet 链")
    void missingCredentialRejected() throws Exception {
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();

        new McpAuthFilter(CONFIGURED, NO_TOKENS)
                .doFilter(request, response, (req, res) -> passed.set(true));

        assertThat(passed).as("未被拒的请求才允许进入 /mcp servlet").isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(errorCode(response)).isEqualTo("AUTH_REQUIRED");
    }

    @Test
    @DisplayName("假服务凭证 → 401 DELEGATION_INVALID，不进入 servlet 链")
    void wrongServiceCredentialRejected() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader(McpAuthFilter.SERVICE_CREDENTIAL_HEADER, "wrong-credential");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();

        new McpAuthFilter(CONFIGURED, NO_TOKENS)
                .doFilter(request, response, (req, res) -> passed.set(true));

        assertThat(passed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(errorCode(response)).isEqualTo("DELEGATION_INVALID");
    }

    @Test
    @DisplayName("假 Bearer 令牌 → 401 DELEGATION_INVALID")
    void fakeBearerRejected() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "Bearer r12-fake-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();

        new McpAuthFilter(CONFIGURED, NO_TOKENS)
                .doFilter(request, response, (req, res) -> passed.set(true));

        assertThat(passed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(errorCode(response)).isEqualTo("DELEGATION_INVALID");
    }

    @Test
    @DisplayName("空 Bearer 令牌 → 401 DELEGATION_INVALID（有凭证但无效，不降级为 AUTH_REQUIRED）")
    void blankBearerRejected() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "Bearer ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();

        new McpAuthFilter(CONFIGURED, NO_TOKENS)
                .doFilter(request, response, (req, res) -> passed.set(true));

        assertThat(passed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(errorCode(response)).isEqualTo("DELEGATION_INVALID");
    }

    @Test
    @DisplayName("有效服务凭证 → 放行（进入 servlet 链、响应不被过滤器改写）")
    void validServiceCredentialPasses() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader(McpAuthFilter.SERVICE_CREDENTIAL_HEADER, CONFIGURED);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();

        new McpAuthFilter(CONFIGURED, NO_TOKENS)
                .doFilter(request, response, (req, res) -> passed.set(true));

        assertThat(passed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    @DisplayName("空配置的服务凭证 → 服务凭证通道整体拒绝（fail-closed，不得降级放行）")
    void blankConfiguredCredentialRejectsServicePath() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader(McpAuthFilter.SERVICE_CREDENTIAL_HEADER, "");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();

        new McpAuthFilter("", NO_TOKENS)
                .doFilter(request, response, (req, res) -> passed.set(true));

        assertThat(passed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(errorCode(response)).isEqualTo("AUTH_REQUIRED");
    }

    @Test
    @DisplayName("空配置时携带任意服务凭证 → 同样拒绝（空配置与不匹配同码，无区分度）")
    void blankConfiguredCredentialRejectsPresentedCredential() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader(McpAuthFilter.SERVICE_CREDENTIAL_HEADER, "whatever");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();

        new McpAuthFilter("", NO_TOKENS)
                .doFilter(request, response, (req, res) -> passed.set(true));

        assertThat(passed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(errorCode(response)).isEqualTo("DELEGATION_INVALID");
    }

    @Test
    @DisplayName("有效登录令牌 → 放行（Sa-Token 路径；以注入的解析器替身证明调用关系）")
    void validLoginTokenPasses() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "Bearer token-ok");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();
        Function<String, Object> tokens = token -> "token-ok".equals(token) ? 1001L : null;

        new McpAuthFilter(CONFIGURED, tokens)
                .doFilter(request, response, (req, res) -> passed.set(true));

        assertThat(passed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("非 REQUEST 分派（FORWARD/ASYNC/ERROR）→ 不鉴权直接透传（不打断异步收尾）")
    void nonRequestDispatchPassesThrough() throws Exception {
        MockHttpServletRequest request = request();
        request.setDispatcherType(DispatcherType.ASYNC);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();

        new McpAuthFilter(CONFIGURED, NO_TOKENS)
                .doFilter(request, response, (req, res) -> passed.set(true));

        assertThat(passed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
