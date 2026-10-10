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

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.ingestion.controller.IngestionPipelineController;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionPipelineCreateRequest;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionPipelineUpdateRequest;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionPipelineVO;
import com.nageoffer.ai.ragent.ingestion.service.IngestionPipelineService;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * S2-F06-A1（摄取流水线 CRUD 装配）：网关 → 内层 MVC → 真实 {@link IngestionPipelineController}
 * 的 <b>dispatch 级</b>证据，与 {@code LocalAdminWriteRouteDispatchTest} 同一根绳。
 *
 * <p><b>为什么需要本类。</b>装配缺口有五种"静态检查都过、真实请求才炸"的形态：
 * ① 白名单没有这 5 条（网关 404）；② 控制器没有类级 {@code /internal/ai/v1} 前缀
 * （内层路由不命中 ⇒ 503）；③ 信封还是 ragent {@code Result} 的<b>字符串</b> code
 * （网关判 "missing envelope code" ⇒ 503）；④ GET 没有交付回执头
 * （网关字节分支判 "delivery receipt missing" ⇒ 503）；⑤ 控制器不是 bean
 * （装配没登记 ⇒ "internal route unmatched" ⇒ 503）。
 * 源码扫描证明不了其中任何一条，只有把网关 + 真实 MVC + 真实控制器拉起来逐条发请求才能证明。
 *
 * <p><b>判据纪律（与 RW-31-ACT dispatch 类同一口径）。</b>
 * <ul>
 *   <li>正腿要求 HTTP <b>200</b> 且信封是<b>整数</b> {@code "code":200}，并显式断言响应里
 *       <b>没有</b> ragent {@code Result} 的字符串 {@code "code":"0"}；</li>
 *   <li>特征串：替身服务返回独有标记（{@code f06-*}），断言它们出现在响应体里
 *       ——标记只可能来自该控制器的返回值；</li>
 *   <li>参数捕获：POST/PUT 用 {@link ArgumentCaptor} 断言替身服务收到的是由路径变量与
 *       JSON 体绑定出来的精确参数；GET 列表断言 {@code pageNo/pageSize} 真的进了
 *       {@code Page}（不是被静默忽略）；</li>
 *   <li>读面（GET）走网关字节分支：<b>每次 200 恰铸一次交付回执</b>并且网关自行 ACK
 *       （本地传输收敛为 {@code deliveryReleaser} 调用）；写面（POST/PUT/DELETE）
 *       <b>零 permit、零 ACK</b>；</li>
 *   <li>scope 分离：只有 {@code ai:config:read} 时三条写路由在 handler 之前 403；
 *       只有 {@code ai:config:publish} 时读路由同样 403（读/写是两个独立条件）。</li>
 * </ul>
 */
@Tag("dev")
class LocalIngestionPipelineDispatchTest {

    private final IngestionPipelineService pipelines = mock(IngestionPipelineService.class);
    private final RevocationGuard guard = mock(RevocationGuard.class);
    private final List<String> acknowledgements = new ArrayList<>();

    private AnnotationConfigWebApplicationContext context;
    private MockServletContext servlet;
    private AiGatewayController gateway;
    private Set<String> permissions = Set.of("ai:config:read", "ai:config:publish");

    @BeforeEach
    void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of(
                "ai.integration.enabled", "true", "ai.integration.transport", "local", "p2.enabled", "true")));
        context.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("ingestionPipelines", new IngestionPipelineController(pipelines));
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
                "T1", "2101", "platform:T1:2101", 1, 1, permissions)),
                (tenant, member, permit, operation) -> acknowledgements.add(permit + ":" + operation));
        var identity = mock(PlatformIdentitySource.class);
        when(identity.membership(anyString(), anyString(), anyString())).thenAnswer(call ->
                new PlatformIdentitySource.PlatformIdentity("T1", "2101", "platform:T1:2101", true, permissions, 1));
        ObjectProvider<PlatformIdentitySource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(identity);
        var current = mock(CurrentPrincipalResolver.class);
        when(current.resolveCurrentMember()).thenReturn(Optional.of(
                new CurrentPrincipalResolver.CurrentMember("T1", "2101", "platform:T1:2101")));
        when(current.isPlatformAdmin()).thenReturn(true);
        var properties = new AiIntegrationProperties();
        properties.setTransport("local");
        properties.setAiBaseUrl("http://local");
        gateway = new AiGatewayController(current, provider, mock(ObjectProvider.class), client, properties);
    }

    @AfterEach
    void cleanup() {
        com.nageoffer.ai.ragent.framework.context.PrincipalContext.clear();
        RequestContextHolder.resetRequestAttributes();
        if (context != null) {
            context.close();
        }
    }

    @Test
    @DisplayName("GET 列表/详情到达 handler：整数信封 + 分页参数绑定 + 每响应恰一次交付回执")
    void readRoutesReachTheirHandlerWithDeliveryReceipts() throws Exception {
        // Mockito 的接口替身不会执行 default 方法：`enter` 必须显式桩成真实 Operation
        // （与 LocalAdminRouteDispatchTest 同一修法）。
        when(guard.enter(any(), anyString(), anyString())).thenAnswer(call -> new RevocationGuard.Operation(
                guard, UUID.randomUUID().toString(), UUID.randomUUID().toString()));

        IngestionPipelineVO listRow = new IngestionPipelineVO();
        listRow.setId("p-1");
        listRow.setName("f06-read-list");
        Page<IngestionPipelineVO> page = new Page<>(1, 10);
        page.setTotal(1);
        page.setRecords(List.of(listRow));
        when(pipelines.page(any(), any())).thenReturn(page);

        var listed = dispatch("GET", "/ingestion/pipelines?pageNo=1&pageSize=10", null);
        assertEnvelope(listed, "/ingestion/pipelines");
        assertThat(listed.getContentAsString())
                .as("响应体里的独有标记只可能来自这个控制器的返回值")
                .contains("f06-read-list")
                .contains("\"records\"")
                .contains("\"total\":1");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Page<IngestionPipelineVO>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(pipelines).page(pageCaptor.capture(), isNull());
        assertThat(pageCaptor.getValue().getCurrent())
                .as("pageNo 必须真的绑定进 Page（写错参数名会被静默忽略，永远第 1 页）")
                .isEqualTo(1);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(10);

        IngestionPipelineVO detail = new IngestionPipelineVO();
        detail.setId("p-2");
        detail.setName("f06-read-detail");
        when(pipelines.get("p-2")).thenReturn(detail);

        var got = dispatch("GET", "/ingestion/pipelines/p-2", null);
        assertEnvelope(got, "/ingestion/pipelines/p-2");
        assertThat(got.getContentAsString()).contains("f06-read-detail");
        verify(pipelines).get("p-2");

        verify(guard, times(2)).enter(any(), eq("config.read"),
                startsWith("admin-read:/api/ai/v1/ingestion/pipelines"));
        assertThat(acknowledgements)
                .as("GET 走网关字节分支：每个 200 交付必须恰有一次本地回执（否则许可泄漏/或内层缺回执头）")
                .hasSize(2);
    }

    @Test
    @DisplayName("POST/PUT/DELETE 到达 handler：整数信封 + 路径变量/JSON 体精确绑定 + 写面零 permit/零 ACK")
    void writeRoutesReachTheirHandlerWithoutDeliveryReceipts() throws Exception {
        when(pipelines.create(any())).thenAnswer(call -> {
            IngestionPipelineCreateRequest request = call.getArgument(0);
            IngestionPipelineVO created = new IngestionPipelineVO();
            created.setId("p-created");
            created.setName(request.getName());
            return created;
        });
        var created = dispatch("POST", "/ingestion/pipelines",
                "{\"name\":\"f06-created\",\"description\":\"d\","
                        + "\"nodes\":[{\"nodeId\":\"n1\",\"nodeType\":\"fetcher\"}]}");
        assertEnvelope(created, "/ingestion/pipelines");
        assertThat(created.getContentAsString()).contains("f06-created");
        ArgumentCaptor<IngestionPipelineCreateRequest> createCaptor =
                ArgumentCaptor.forClass(IngestionPipelineCreateRequest.class);
        verify(pipelines).create(createCaptor.capture());
        assertThat(createCaptor.getValue().getName()).isEqualTo("f06-created");
        assertThat(createCaptor.getValue().getDescription()).isEqualTo("d");
        assertThat(createCaptor.getValue().getNodes())
                .as("JSON 体里的 nodes 必须被这个控制器的参数绑定读出")
                .hasSize(1);
        assertThat(createCaptor.getValue().getNodes().get(0).getNodeId()).isEqualTo("n1");

        when(pipelines.update(eq("p-2"), any())).thenAnswer(call -> {
            IngestionPipelineUpdateRequest request = call.getArgument(1);
            IngestionPipelineVO updated = new IngestionPipelineVO();
            updated.setId("p-2");
            updated.setName(request.getName());
            return updated;
        });
        var updated = dispatch("PUT", "/ingestion/pipelines/p-2", "{\"name\":\"f06-updated\"}");
        assertEnvelope(updated, "/ingestion/pipelines/p-2");
        assertThat(updated.getContentAsString()).contains("f06-updated");
        ArgumentCaptor<IngestionPipelineUpdateRequest> updateCaptor =
                ArgumentCaptor.forClass(IngestionPipelineUpdateRequest.class);
        verify(pipelines).update(eq("p-2"), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getName())
                .as("路径变量 + JSON 体必须被这个控制器的参数绑定读出")
                .isEqualTo("f06-updated");

        var deleted = dispatch("DELETE", "/ingestion/pipelines/p-3", null);
        assertEnvelope(deleted, "/ingestion/pipelines/p-3");
        verify(pipelines).delete("p-3");

        verifyNoInteractions(guard);
        assertThat(acknowledgements)
                .as("写面不得铸造交付回执（网关只在 GET/字节分支铸造）")
                .isEmpty();
        assertThat(com.nageoffer.ai.ragent.framework.context.PrincipalContext.hasPrincipal()).isFalse();
    }

    @Test
    @DisplayName("scope 分离：只有 read 时三条写路由 403；只有 publish 时读路由同样 403（都在 handler 之前）")
    void readAndWriteScopesAreIndependent() throws Exception {
        permissions = Set.of("ai:config:read");
        for (WriteCall call : threeWriteRoutes()) {
            var response = dispatch(call.method(), call.path(), call.body());
            assertThat(response.getStatus()).as(call.method() + " " + call.path()).isEqualTo(403);
        }
        verifyNoInteractions(pipelines, guard);
        assertThat(acknowledgements).isEmpty();

        permissions = Set.of("ai:config:publish");
        var read = dispatch("GET", "/ingestion/pipelines", null);
        assertThat(read.getStatus())
                .as("只持写权限不得读：读路由需要 ai:config:read")
                .isEqualTo(403);
        verifyNoInteractions(pipelines);
        assertThat(acknowledgements).isEmpty();
    }

    /** 三条写路由的 (方法, 路径, 体) 清单；两处判据共用，避免"清单漂移"。 */
    private static List<WriteCall> threeWriteRoutes() {
        return List.of(
                new WriteCall("POST", "/ingestion/pipelines", "{\"name\":\"x\"}"),
                new WriteCall("PUT", "/ingestion/pipelines/p-2", "{\"name\":\"x\"}"),
                new WriteCall("DELETE", "/ingestion/pipelines/p-3", null));
    }

    private record WriteCall(String method, String path, String body) {
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 信封判据：HTTP 200 + 整数 {@code "code":200} + <b>没有</b> ragent {@code Result} 的
     * 字符串 {@code "code":"0"}（后者经网关必然 503，出现即说明这一面没改造完）。
     */
    private static void assertEnvelope(MockHttpServletResponse response, String path) throws Exception {
        assertThat(response.getStatus())
                .as("%s：路由必须真到达 handler；404（未登记白名单）/503（内层不命中）/403（scope）都不是通过", path)
                .isEqualTo(200);
        String body = response.getContentAsString();
        assertThat(body).as("%s：必须是整数 code 的信封", path).contains("\"code\":200");
        assertThat(body).as("%s：不得是 ragent Result 的字符串 code", path).doesNotContain("\"code\":\"0\"");
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
            output.getWriter().write(String.valueOf(result.getBody()));
        }
        return output;
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
}
