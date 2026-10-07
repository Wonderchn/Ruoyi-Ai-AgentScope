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

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ai.api.AiExecutionFacts;
import org.ruoyi.ai.api.identity.AiIdentityPort;
import org.ruoyi.aiintegration.web.AiGatewayClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E3/C3 身份桥与传输契约单元语义：本地转送在调用内层 handler 前设置
 * {@code PrincipalContext}、返回后恢复（同步链无残留）；执行事实不可得时
 * fail-closed；恶意响应（非 JSON 对象 / 缺整数 code / 状态与 code 不一致 /
 * 重复键）拒绝并收敛为 503 语义；4xx 与匹配包络透传。
 */
@Tag("dev")
class LocalAiGatewayClientBridgeTest {

    private static final AiExecutionFacts FACTS = new AiExecutionFacts(
            "T1", "2101", "platform:T1:2101", 3, 7, Set.of("ai:kb:list"));

    /** fixture 控制器的可配置应答。 */
    private static final AtomicReference<Integer> stubStatus = new AtomicReference<>(200);
    private static final AtomicReference<String> stubBody = new AtomicReference<>("{\"code\":200,\"data\":{}}");
    private static final AtomicReference<String> forwardedMembership = new AtomicReference<>();
    private static final AtomicReference<String> forwardedIssuer = new AtomicReference<>();

    @BeforeEach
    void holdRequestContext() {
        stubStatus.set(200);
        stubBody.set("{\"code\":200,\"data\":{}}");
        forwardedMembership.set(null);
        forwardedIssuer.set(null);
    }

    @AfterEach
    void releaseRequestContext() {
        RequestContextHolder.resetRequestAttributes();
        PrincipalContext.clear();
    }

    private LocalAiGatewayClient client(AiIdentityPort port) {
        return new LocalAiGatewayClient(2000, port, (tenantId, memberId, permitId, operationId) -> { });
    }

    private AiGatewayClient.ForwardRequest forwardRequest() {
        return new AiGatewayClient.ForwardRequest("GET",
                URI.create("http://local/internal/ai/v1/knowledge-bases"), Map.of(), null);
    }

    private MockHttpServletRequest requestWithWebContext() {
        MockServletContext servletContext = new MockServletContext();
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.register(BridgeFixture.class);
        context.setServletContext(servletContext);
        context.refresh();
        servletContext.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, context);
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext, "GET", "/api/ai/v1/knowledge-bases");
        // 与 DispatcherServlet 处理外层请求时相同：请求属性携带 Web 上下文
        request.setAttribute(org.springframework.web.servlet.DispatcherServlet.WEB_APPLICATION_CONTEXT_ATTRIBUTE, context);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, new MockHttpServletResponse()));
        return request;
    }

    @Test
    void bridgesFactsIntoPrincipalContextForTheDispatchAndRestoresAfterwards() {
        requestWithWebContext();

        AiGatewayClient.ForwardResponse response = client(fixedFacts()).forward(forwardRequest());

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"code\":200,\"data\":{}}");
        assertThat(forwardedMembership.get()).isEqualTo("platform:T1:2101");
        // 身份桥填充的是本地转送标识，不是任何 JWT
        assertThat(forwardedIssuer.get()).isEqualTo(LocalAiGatewayClient.LOCAL_ISSUER);
        // 转发结束：同步链主体恢复为空（无残留、无污染）
        assertThat(PrincipalContext.hasPrincipal()).isFalse();
    }

    @Test
    void factsUnavailableFailsClosed() {
        requestWithWebContext();

        assertThatThrownBy(() -> client(() -> Optional.empty()).forward(forwardRequest()))
                .isInstanceOf(AiGatewayClient.UpstreamUnavailableException.class)
                .hasMessageContaining("execution facts unavailable");
        assertThat(PrincipalContext.hasPrincipal()).isFalse();
    }

    @Test
    void rejectsNonObjectBody() {
        requestWithWebContext();
        stubBody.set("[1,2,3]");

        assertThatThrownBy(() -> client(fixedFacts()).forward(forwardRequest()))
                .isInstanceOf(AiGatewayClient.UpstreamUnavailableException.class)
                .hasMessageContaining("non-object upstream body");
    }

    @Test
    void rejectsEnvelopeWithoutCode() {
        requestWithWebContext();
        stubBody.set("{\"unexpected\":true}");

        assertThatThrownBy(() -> client(fixedFacts()).forward(forwardRequest()))
                .isInstanceOf(AiGatewayClient.UpstreamUnavailableException.class)
                .hasMessageContaining("missing envelope code");
    }

    @Test
    void rejectsEnvelopeCodeMismatch() {
        requestWithWebContext();
        stubBody.set("{\"code\":404,\"msg\":\"x\",\"data\":{}}");

        assertThatThrownBy(() -> client(fixedFacts()).forward(forwardRequest()))
                .isInstanceOf(AiGatewayClient.UpstreamUnavailableException.class)
                .hasMessageContaining("missing envelope code");
    }

    @Test
    void rejectsDuplicateKeys() {
        requestWithWebContext();
        stubBody.set("{\"code\":404,\"code\":404}");

        assertThatThrownBy(() -> client(fixedFacts()).forward(forwardRequest()))
                .isInstanceOf(AiGatewayClient.UpstreamUnavailableException.class);
    }

    @Test
    void passesThrough4xxWithMatchingEnvelope() {
        requestWithWebContext();
        stubStatus.set(404);
        stubBody.set("{\"code\":404,\"msg\":\"x\",\"data\":{\"errorCode\":\"X\"}}");

        AiGatewayClient.ForwardResponse response = client(fixedFacts()).forward(forwardRequest());
        assertThat(response.status()).isEqualTo(404);
        assertThat(PrincipalContext.hasPrincipal()).isFalse();
    }

    private static AiIdentityPort fixedFacts() {
        return () -> Optional.of(FACTS);
    }

    @RestController
    @RequestMapping("/internal/ai/v1")
    static class BridgeFixtureController {

        @GetMapping("/knowledge-bases")
        public ResponseEntity<String> listKnowledgeBases() {
            ExecutionPrincipal principal = PrincipalContext.require();
            forwardedMembership.set(principal.membershipId());
            forwardedIssuer.set(principal.issuer());
            return ResponseEntity.status(stubStatus.get())
                    .header("Content-Type", "application/json")
                    .body(stubBody.get());
        }
    }

    @Configuration
    @EnableWebMvc
    @Import(BridgeFixtureController.class)
    static class BridgeFixture {
    }
}
