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

package org.ruoyi.aiweb.embedded;

import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.rag.controller.GraphController;
import com.nageoffer.ai.ragent.rag.controller.vo.GraphStatusVO;
import com.nageoffer.ai.ragent.rag.controller.vo.GraphViewVO;
import com.nageoffer.ai.ragent.rag.core.graph.GraphProjectionService;
import com.nageoffer.ai.ragent.rag.core.graph.GraphQueryService;
import com.nageoffer.ai.ragent.rag.core.graph.GraphStatusService;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ai.api.AiExecutionFacts;
import org.ruoyi.aiintegration.config.AiIntegrationProperties;
import org.ruoyi.aiintegration.identity.CurrentPrincipalResolver;
import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.ruoyi.aiintegration.web.AiGatewayController;
import org.ruoyi.aiweb.transport.LocalAiGatewayClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * R13/R14：图谱管理面经真实网关 + 本地 MVC + 真实控制器 + 真实回执 Advice 的 dispatch 级证据。
 *
 * <p>判据只认"只有该控制器能产生的标记"：服务替身返回独有串，响应体必须含它；
 * GET 必须整数 {@code code:200} 且每个请求恰好消费一个回执；POST 重建不铸回执。
 */
@Tag("dev")
class LocalGraphAdminDispatchTest {

    private final GraphQueryService query = mock(GraphQueryService.class);
    private final GraphStatusService status = mock(GraphStatusService.class);
    private final GraphProjectionService projection = mock(GraphProjectionService.class);
    private final RevocationGuard guard = mock(RevocationGuard.class);
    private final List<String> acknowledgements = new ArrayList<>();
    private String tenantId = "T1";
    private AnnotationConfigWebApplicationContext context;
    private MockServletContext servlet;
    private AiGatewayController gateway;
    private Set<String> permissions = Set.of("ai:kb:read", "ai:config:read", "ai:kb:write");

    @BeforeEach
    void setup() {
        when(guard.enter(any(), anyString(), anyString())).thenAnswer(call -> new RevocationGuard.Operation(
                guard, UUIDHolder.next(), UUIDHolder.next()));
        when(query.getGraph(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(GraphViewVO.builder()
                .nodes(List.of(GraphViewVO.Node.builder().id("r8-node").name("Alice").build()))
                .edges(List.of()).truncated(false).droppedUnattributed(0).readSource("projection").build());
        when(query.searchEntities(any(), anyInt())).thenReturn(List.of("r8-label"));
        when(status.status()).thenReturn(GraphStatusVO.builder()
                .backendType("lightrag").configured(true).status("UP").reachable(true)
                .readSource("projection").build());
        when(projection.rebuild(any(), any())).thenReturn(new GraphProjectionService.RebuildResult(
                2, 7, 5, 0, false));

        context = new AnnotationConfigWebApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of(
                "ai.integration.enabled", "true", "ai.integration.transport", "local", "p2.enabled", "true")));
        context.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("graphController", new GraphController(query, status, projection));
            ObjectProvider<RevocationGuard> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(guard);
            factory.registerSingleton("deliveryPermits", new DeliveryPermits(provider));
        });
        servlet = new MockServletContext();
        context.setServletContext(servlet);
        context.register(Fixture.class);
        context.refresh();
        servlet.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, context);
        var client = new LocalAiGatewayClient(2000, () -> Optional.of(new AiExecutionFacts(
                tenantId, "2101", "platform:" + tenantId + ":2101", 1, 1, permissions)),
                (tenant, member, permit, operation) -> {
                    assertThat(tenant).isEqualTo(tenantId);
                    assertThat(member).isEqualTo("platform:" + tenantId + ":2101");
                    acknowledgements.add(permit + ":" + operation);
                });
        var identity = mock(PlatformIdentitySource.class);
        when(identity.tenantState(anyString())).thenReturn(PlatformIdentitySource.TenantState.ENABLED);
        when(identity.membership(anyString(), anyString(), anyString())).thenAnswer(call ->
                new PlatformIdentitySource.PlatformIdentity(tenantId, "2101", "platform:" + tenantId + ":2101",
                        true, permissions, 1));
        ObjectProvider<PlatformIdentitySource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(identity);
        var current = mock(CurrentPrincipalResolver.class);
        when(current.resolveCurrentMember()).thenAnswer(call -> Optional.of(
                new CurrentPrincipalResolver.CurrentMember(tenantId, "2101", "platform:" + tenantId + ":2101")));
        when(current.isPlatformAdmin()).thenReturn(true);
        var properties = new AiIntegrationProperties();
        properties.setTransport("local");
        properties.setAiBaseUrl("http://local");
        gateway = new AiGatewayController(current, provider, mock(ObjectProvider.class), client, properties);
    }

    @AfterEach
    void cleanup() {
        PrincipalContext.clear();
        RequestContextHolder.resetRequestAttributes();
        if (context != null) {
            context.close();
        }
    }

    private MockHttpServletResponse dispatch(String method, String path, String body) throws Exception {
        int queryAt = path.indexOf('?');
        var request = new MockHttpServletRequest(servlet, method, "/api/ai/v1"
                + (queryAt < 0 ? path : path.substring(0, queryAt)));
        if (queryAt >= 0) {
            request.setQueryString(path.substring(queryAt + 1));
        }
        request.addHeader("Authorization", "fixture-session");
        request.setContentType("application/json");
        request.setAttribute(DispatcherServlet.WEB_APPLICATION_CONTEXT_ATTRIBUTE, context);
        var output = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, output));
        var result = gateway.gateway(request, output,
                body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
        if (result != null) {
            output.setStatus(result.getStatusCode().value());
            output.getWriter().write(result.getBody() instanceof String responseBody ? responseBody
                    : new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result.getBody()));
        }
        return output;
    }

    @Test
    @DisplayName("三个 GET 均到达自己的控制器：整数信封 + 每请求一个回执")
    void graphGetsReturnEnvelopeAndConsumeOneReceiptEach() throws Exception {
        var graph = dispatch("GET", "/admin/kg/graph?entity=Alice&kbId=kb1&depth=2&limit=50", null);
        assertThat(graph.getStatus()).isEqualTo(200);
        assertThat(graph.getContentAsString()).contains("\"code\":200", "r8-node", "\"readSource\":\"projection\"");
        verify(query).getGraph(eq("Alice"), eq("kb1"), isNull(), isNull(), eq(2), eq(50));
        verify(guard).enter(any(), eq("kb.read"), startsWith("admin-read:/api/ai/v1/admin/kg/graph"));

        var labels = dispatch("GET", "/admin/kg/labels?keyword=ali&limit=50", null);
        assertThat(labels.getStatus()).isEqualTo(200);
        assertThat(labels.getContentAsString()).contains("\"code\":200", "r8-label");
        verify(query).searchEntities("ali", 50);

        var statusResponse = dispatch("GET", "/admin/kg/status", null);
        assertThat(statusResponse.getStatus()).isEqualTo(200);
        assertThat(statusResponse.getContentAsString()).contains("\"code\":200", "\"status\":\"UP\"");
        verify(status).status();
        verify(guard).enter(any(), eq("config.read"), startsWith("admin-read:/api/ai/v1/admin/kg/status"));

        assertThat(acknowledgements).hasSize(3).doesNotHaveDuplicates();
        assertThat(PrincipalContext.hasPrincipal()).isFalse();
    }

    @Test
    @DisplayName("POST 重建：整数信封、参数绑定正确、不铸回执")
    void rebuildWritesEnvelopeWithoutReceipt() throws Exception {
        var response = dispatch("POST", "/admin/kg/projection/rebuild?kbId=kb1", "{}");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("\"code\":200", "\"entities\":7");
        verify(projection).rebuild("kb1", null);
        verifyNoInteractions(guard);
        assertThat(acknowledgements).isEmpty();
    }

    @Test
    @DisplayName("缺配置读权限：status 在 handler 前 403")
    void statusRequiresConfigRead() throws Exception {
        permissions = Set.of("ai:kb:read");
        assertThat(dispatch("GET", "/admin/kg/status", null).getStatus()).isEqualTo(403);
        verifyNoInteractions(status, guard);
    }

    @Test
    @DisplayName("缺 kb.read：graph/labels 在 handler 前 403")
    void graphRequiresKbRead() throws Exception {
        permissions = Set.of("ai:config:read", "ai:kb:write");
        assertThat(dispatch("GET", "/admin/kg/graph?kbId=kb1", null).getStatus()).isEqualTo(403);
        assertThat(dispatch("GET", "/admin/kg/labels", null).getStatus()).isEqualTo(403);
        verifyNoInteractions(query, guard);
    }

    @Test
    @DisplayName("缺 kb.write：重建在 handler 前 403")
    void rebuildRequiresKbWrite() throws Exception {
        permissions = Set.of("ai:kb:read", "ai:config:read");
        assertThat(dispatch("POST", "/admin/kg/projection/rebuild?kbId=kb1", "{}").getStatus()).isEqualTo(403);
        verifyNoInteractions(projection);
    }

    @Test
    @DisplayName("邻接路径不塌陷：/admin/kg/graph/extra 与旧公开前缀均 404")
    void neighboringPathsStayClosed() throws Exception {
        assertThat(dispatch("GET", "/admin/kg/graph/extra", null).getStatus()).isEqualTo(404);
        assertThat(dispatch("GET", "/admin/kg/status/extra", null).getStatus()).isEqualTo(404);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(AiEmbeddedAdminDeliveryConfiguration.class)
    static class Fixture {
        @Bean
        AiInternalExceptionResolver errors() {
            return new AiInternalExceptionResolver();
        }
    }

    private static final class UUIDHolder {
        private static java.util.concurrent.atomic.AtomicInteger SEQ =
                new java.util.concurrent.atomic.AtomicInteger();

        static String next() {
            int n = SEQ.incrementAndGet();
            return String.format("00000000-0000-4000-8000-%012d", n);
        }
    }
}
