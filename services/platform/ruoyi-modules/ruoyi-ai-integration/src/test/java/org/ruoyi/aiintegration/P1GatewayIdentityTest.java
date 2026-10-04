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

package org.ruoyi.aiintegration;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ruoyi.aiintegration.config.AiIntegrationProperties;
import org.ruoyi.aiintegration.delegation.ProductionSigningKeySource;
import org.ruoyi.aiintegration.identity.CurrentPrincipalResolver;
import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.ruoyi.aiintegration.web.AiGatewayClient;
import org.ruoyi.aiintegration.web.AiGatewayController;
import org.ruoyi.aiintegration.web.ApiResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 生产 AI 网关（U06/P1.2c）的 mock 驱动单测：不起容器，直接 new 控制器。
 *
 * <p>覆盖：未登录 401、resolver empty 403、白名单外 404、转发剥离内部身份头并携带
 * 委托凭证（token 声明逐项断言）、无 pv/成员无效 403、身份源缺失 503、客户端异常 503、
 * scope 不足 403、AI 状态码透传。
 *
 * <p>测试类带 {@code dev} 标签（用户长期要求）。
 */
@Tag("dev")
class P1GatewayIdentityTest {

    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final String BASE_URL = "http://ai.test";
    private static final String ENVELOPE = "{\"code\":200,\"msg\":\"success\",\"data\":{}}";

    private static KeyPair signingPair;
    private static PublicKey publicKey;

    private AiIntegrationProperties properties;
    private CurrentPrincipalResolver principalResolver;
    private PlatformIdentitySource identitySource;
    private AiGatewayClient client;
    private AiGatewayController controller;

    @BeforeAll
    static void setUpClass() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        signingPair = generator.generateKeyPair();
        publicKey = signingPair.getPublic();

        // 私钥按运行期约定落成 PKCS#8 PEM；写到模块 target 下，避免 Windows 临时目录权限问题
        Path dir = Path.of("target", "p1-gateway-test");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("platform-private.pem"), pkcs8Pem(signingPair), StandardCharsets.UTF_8);
    }

    @BeforeEach
    void setUp() {
        properties = new AiIntegrationProperties();
        properties.setServiceCredential("synthetic-service-credential");
        properties.setEnabled(true);
        properties.setAiBaseUrl(BASE_URL);
        properties.setForwardTimeoutMillis(2000);
        properties.setMaxForwardBodyBytes(1024 * 1024);

        principalResolver = mock(CurrentPrincipalResolver.class);
        identitySource = mock(PlatformIdentitySource.class);
        client = mock(AiGatewayClient.class);
        controller = new AiGatewayController(principalResolver, provider(identitySource),
                signingKeys(new ProductionSigningKeySource(privateKeyPath(), Clock.fixed(NOW, ZoneOffset.UTC))),
                client, properties);
    }

    private static String privateKeyPath() {
        return Path.of("target", "p1-gateway-test", "platform-private.pem").toAbsolutePath().toString();
    }

    private static String pkcs8Pem(KeyPair pair) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(pair.getPrivate().getEncoded());
        return "-----BEGIN " + "PRIVATE KEY-----\n" + base64 + "\n-----END " + "PRIVATE KEY-----\n";
    }

    private static ObjectProvider<PlatformIdentitySource> provider(PlatformIdentitySource source) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        if (source != null) {
            beanFactory.addBean("identitySource", source);
        }
        return beanFactory.getBeanProvider(PlatformIdentitySource.class);
    }

    private static ObjectProvider<ProductionSigningKeySource> signingKeys(
            ProductionSigningKeySource source) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        if (source != null) {
            beanFactory.addBean("signingKeys", source);
        }
        return beanFactory.getBeanProvider(ProductionSigningKeySource.class);
    }

    private static PlatformIdentitySource.PlatformIdentity enabledIdentity(Set<String> scopes) {
        return new PlatformIdentitySource.PlatformIdentity(
                "T1", "42", "platform:T1:42", true, scopes, 3);
    }

    private void stubMember() {
        when(principalResolver.resolveCurrentMember())
                .thenReturn(Optional.of(new CurrentPrincipalResolver.CurrentMember("T1", "42", "platform:T1:42")));
        when(identitySource.membership("T1", "42", "platform:T1:42"))
                .thenReturn(enabledIdentity(Set.of("ai:kb:read", "ai:kb:list", "ai:kb:write")));
        when(client.forward(any())).thenReturn(new AiGatewayClient.ForwardResponse(200, ENVELOPE));
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader("Authorization", "satoken-evidence");
        return request;
    }

    private static int status(ResponseEntity<?> response) {
        return response.getStatusCode().value();
    }

    @Test
    void finalOutputFlushPrecedesServiceRelease() throws Exception {
        stubMember();
        when(client.forwardBytes(any())).thenReturn(new AiGatewayClient.ByteResponse(200,
                ENVELOPE.getBytes(StandardCharsets.UTF_8),"application/json","","permit","operation"));
        var response=new org.springframework.mock.web.MockHttpServletResponse();
        when(client.forward(any())).thenAnswer(invocation -> {
            assertTrue(response.isCommitted());
            assertEquals(ENVELOPE,response.getContentAsString());
            AiGatewayClient.ForwardRequest acknowledgement=invocation.getArgument(0);
            assertTrue(acknowledgement.uri().getPath().endsWith("/deliveries/release"));
            return new AiGatewayClient.ForwardResponse(204,"");
        });
        assertEquals(null,controller.gateway(request("GET","/api/ai/v1/knowledge-bases/kb-a"),response,null));
        verify(client).forward(any());
        assertEquals(null,response.getHeader("X-AI-Delivery-Permit"));
    }

    @Test
    void clientAbortStopsWritesBeforeServiceRelease() throws Exception {
        stubMember();
        when(client.forwardBytes(any())).thenReturn(new AiGatewayClient.ByteResponse(200,
                ENVELOPE.getBytes(StandardCharsets.UTF_8),"application/json","","permit","operation"));
        var writes=new java.util.concurrent.atomic.AtomicInteger();
        var stopped=new java.util.concurrent.atomic.AtomicBoolean();
        var output=new jakarta.servlet.ServletOutputStream(){
            @Override public boolean isReady(){return true;}
            @Override public void setWriteListener(jakarta.servlet.WriteListener listener){}
            @Override public void write(int value) throws java.io.IOException {
                assertFalse(stopped.get());writes.incrementAndGet();throw new java.io.IOException("synthetic client abort");
            }
        };
        var response=new org.springframework.mock.web.MockHttpServletResponse(){
            @Override public jakarta.servlet.ServletOutputStream getOutputStream(){return output;}
        };
        when(client.forward(any())).thenAnswer(invocation->{stopped.set(true);return new AiGatewayClient.ForwardResponse(204,"");});
        assertEquals(null,controller.gateway(request("GET","/api/ai/v1/knowledge-bases/kb-a"),response,null));
        assertEquals(1,writes.get());assertTrue(stopped.get());assertEquals(503,response.getStatus());
    }

    @SuppressWarnings("unchecked")
    private static String errorCode(ResponseEntity<?> response) {
        ApiResponse<Map<String, Object>> body = (ApiResponse<Map<String, Object>>) response.getBody();
        assertNotNull(body);
        return String.valueOf(body.data().get("errorCode"));
    }

    @Test
    @DisplayName("未登录（无任何凭证证据）→ 401 AUTH_REQUIRED，且不触碰签发与转发")
    void missingLoginEvidenceIs401() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ai/v1/knowledge-bases");
        ResponseEntity<?> response = controller.gateway(request, null);
        assertEquals(401, status(response));
        assertEquals("AUTH_REQUIRED", errorCode(response));
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("resolver empty（已登录但不归属当前租户）→ 403 TENANT_CONTEXT_MISSING")
    void unresolvedMemberIs403() {
        stubMember();
        when(principalResolver.resolveCurrentMember()).thenReturn(Optional.empty());
        ResponseEntity<?> response = controller.gateway(request("GET", "/api/ai/v1/knowledge-bases"), null);
        assertEquals(403, status(response));
        assertEquals("TENANT_CONTEXT_MISSING", errorCode(response));
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("白名单外（未知形状/未列方法/编码穿越）→ 404，且与登录状态无关")
    void outsideWhitelistIs404() {
        for (HttpServletRequest suspect : List.of(
                request("GET", "/api/ai/v1/knowledge-bases/1/secret"),
                request("PATCH", "/api/ai/v1/knowledge-bases/1"),
                request("GET", "/api/ai/v1/%2e%2e/knowledge-bases"))) {
            ResponseEntity<?> response = controller.gateway(suspect, null);
            assertEquals(404, status(response), String.valueOf(suspect.getRequestURI()));
            assertEquals("RESOURCE_NOT_FOUND_OR_FORBIDDEN", errorCode(response),
                    String.valueOf(suspect.getRequestURI()));
        }
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("转发剥离内部身份头/cookie/浏览器凭证，附委托凭证，token 声明逐项正确")
    void forwardStripsInternalHeadersAndCarriesDelegation() throws Exception {
        stubMember();
        MockHttpServletRequest request = request("POST", "/api/ai/v1/knowledge-bases");
        request.setQueryString("tag=alpha");
        request.addHeader("X-Tenant", "hacked-tenant");
        request.addHeader("X-User", "hacked-user");
        request.addHeader("X-Membership-Id", "hacked-membership");
        request.addHeader("X-Policy-Version", "99");
        request.addHeader("Cookie", "session=hijack");
        request.addHeader("Accept", "application/json");
        byte[] body = "{\"name\":\"kb\"}".getBytes(StandardCharsets.UTF_8);
        request.setContent(body);

        ResponseEntity<?> response = controller.gateway(request, body);
        assertEquals(200, status(response));

        ArgumentCaptor<AiGatewayClient.ForwardRequest> captor =
                ArgumentCaptor.forClass(AiGatewayClient.ForwardRequest.class);
        verify(client).forward(captor.capture());
        AiGatewayClient.ForwardRequest forwarded = captor.getValue();

        // 目标 = AI 基地址 + 内部前缀 + 子路径 + query
        assertEquals(URI.create(BASE_URL + "/internal/ai/v1/knowledge-bases?tag=alpha"), forwarded.uri());
        assertEquals("POST", forwarded.method());

        Map<String, String> headers = forwarded.headers();
        assertFalse(headers.containsKey("X-Tenant"));
        assertFalse(headers.containsKey("X-User"));
        assertFalse(headers.containsKey("X-Membership-Id"));
        assertFalse(headers.containsKey("X-Policy-Version"));
        assertFalse(headers.containsKey("Cookie"));
        assertFalse(headers.containsKey("Authorization".toLowerCase()), "浏览器凭证必须被替换为委托凭证");
        assertEquals("application/json", headers.get("Accept"), "无害头原样透传");

        String bearer = headers.get("Authorization");
        assertTrue(bearer.startsWith("Bearer "), "必须以委托凭证转发");
        // 用与签发一致的固定时钟解析（避免真实时钟越过测试时刻导致 exp 判定漂移）
        Jws<Claims> jws = Jwts.parser().verifyWith(publicKey)
                .clock(() -> java.util.Date.from(NOW)).build()
                .parseSignedClaims(bearer.substring("Bearer ".length()));
        Claims claims = jws.getPayload();
        assertEquals("platform", claims.getIssuer());
        assertTrue(claims.getAudience().contains("ai"));
        assertEquals("platform-prod-k1", jws.getHeader().getKeyId());
        assertEquals("42", claims.getSubject());
        assertEquals("T1", claims.get("tid", String.class));
        assertEquals("platform:T1:42", claims.get("mid", String.class));
        assertEquals(3, claims.get("pv", Integer.class));
        assertEquals(List.of("kb.write"), claims.get("scope", List.class),
                "委托只携带本路由所需 scope");
        assertNotNull(claims.getId(), "jti 必须全新");
    }

    @Test
    @DisplayName("无 pv（身份源返回 null 成员）→ 403 MEMBERSHIP_INVALID；停用成员同样 403")
    void missingPolicyVersionOrDisabledMemberIs403() {
        stubMember();
        when(identitySource.membership("T1", "42", "platform:T1:42")).thenReturn(null);
        ResponseEntity<?> response = controller.gateway(request("GET", "/api/ai/v1/knowledge-bases"), null);
        assertEquals(403, status(response));
        assertEquals("MEMBERSHIP_INVALID", errorCode(response));

        when(identitySource.membership("T1", "42", "platform:T1:42")).thenReturn(
                new PlatformIdentitySource.PlatformIdentity("T1", "42", "platform:T1:42", false,
                        Set.of("ai:kb:read"), 3));
        response = controller.gateway(request("GET", "/api/ai/v1/knowledge-bases"), null);
        assertEquals(403, status(response));
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("身份源缺失（无 bean）→ 503 fail-closed")
    void missingIdentitySourceIs503() {
        stubMember();
        controller = new AiGatewayController(principalResolver, provider(null),
                signingKeys(new ProductionSigningKeySource(privateKeyPath(), Clock.fixed(NOW, ZoneOffset.UTC))),
                client, properties);
        ResponseEntity<?> response = controller.gateway(request("GET", "/api/ai/v1/knowledge-bases"), null);
        assertEquals(503, status(response));
        assertEquals("AUTHORIZATION_UNAVAILABLE", errorCode(response));
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("scope 不足（身份不含路由所需权限）→ 403 FORBIDDEN，不签发委托")
    void missingScopeIs403() {
        stubMember();
        when(identitySource.membership("T1", "42", "platform:T1:42"))
                .thenReturn(enabledIdentity(Set.of("ai:kb:list")));
        ResponseEntity<?> response = controller.gateway(request("DELETE", "/api/ai/v1/knowledge-bases/9"), null);
        assertEquals(403, status(response));
        assertEquals("FORBIDDEN", errorCode(response));
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("客户端异常（上游不可用）→ 503，不泄露原因")
    void clientFailureIs503() {
        stubMember();
        when(client.forward(any())).thenThrow(new AiGatewayClient.UpstreamUnavailableException("timeout"));
        ResponseEntity<?> response = controller.gateway(request("GET", "/api/ai/v1/knowledge-bases"), null);
        assertEquals(503, status(response));
        assertEquals("AUTHORIZATION_UNAVAILABLE", errorCode(response));
    }

    @Test
    @DisplayName("AI 状态码透传（如 202 受理）与响应体")
    void upstreamStatusIsPassedThrough() {
        stubMember();
        String accepted = "{\"code\":200,\"msg\":\"success\",\"data\":{\"runId\":\"r1\"}}";
        when(client.forward(any())).thenReturn(new AiGatewayClient.ForwardResponse(202, accepted));
        ResponseEntity<?> response = controller.gateway(request("GET", "/api/ai/v1/knowledge-bases"), null);
        assertEquals(202, status(response));
        assertEquals(accepted, response.getBody());
    }

    @Test
    @DisplayName("恶意响应（重复键 / trailing token / 非对象）由客户端判 503")
    void maliciousUpstreamBodyFailsClosed() {
        stubMember();
        for (String body : List.of(
                "{\"code\":200,\"code\":200}",
                "{\"code\":200} {}",
                "[1,2]",
                "not-json")) {
            // doThrow：重复打桩时 when(mock...) 会先调用当前桩（上轮的 thenThrow）而误抛
            doThrow(new AiGatewayClient.UpstreamUnavailableException("malformed: " + body))
                    .when(client).forward(any());
            ResponseEntity<?> response = controller.gateway(request("GET", "/api/ai/v1/knowledge-bases"), null);
            assertEquals(503, status(response), body);
            assertEquals("AUTHORIZATION_UNAVAILABLE", errorCode(response), body);
        }
    }
}
