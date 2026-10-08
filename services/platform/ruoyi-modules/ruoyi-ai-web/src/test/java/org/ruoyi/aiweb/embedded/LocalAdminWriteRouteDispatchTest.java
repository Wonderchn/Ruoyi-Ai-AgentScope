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

import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.ingestion.service.IntentTreeService;
import com.nageoffer.ai.ragent.rag.controller.IntentTreeController;
import com.nageoffer.ai.ragent.rag.controller.QueryTermMappingController;
import com.nageoffer.ai.ragent.rag.controller.request.IntentNodeUpdateRequest;
import com.nageoffer.ai.ragent.rag.controller.request.QueryTermMappingCreateRequest;
import com.nageoffer.ai.ragent.rag.controller.request.QueryTermMappingUpdateRequest;
import com.nageoffer.ai.ragent.rag.service.QueryTermMappingAdminService;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import com.nageoffer.ai.ragent.sample.controller.SampleQuestionController;
import com.nageoffer.ai.ragent.sample.controller.request.SampleQuestionCreateRequest;
import com.nageoffer.ai.ragent.sample.controller.request.SampleQuestionUpdateRequest;
import com.nageoffer.ai.ragent.sample.service.SampleQuestionService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RW-31-ACT-R7（T3）：RW-00-ACTIVATE-R6 登记 24 条新路由里，<b>11 条写路由</b>的
 * <b>dispatch 级</b>证据。
 *
 * <p><b>为什么需要本类。</b>R 的实测结论是"24 条里执行层只有 13 条（12 条 GET +
 * {@code POST /intent-tree}）"，其余 11 条写路由只有源码级扫描证据。源码扫描证明的是
 * "白名单里有这条路由、控制器上有这个方法"，它<b>不</b>证明请求真的能走到那个 handler。
 * 本类把网关 + 真实 MVC + 真实控制器 + 真实异常解析器拉起来，逐条发真实请求。
 *
 * <p><b>判据纪律（N2 / §5.22.7）。</b>
 * <ul>
 *   <li><b>不接受 403 当通过</b>：403 只证明"路由匹配上了但被授权挡下"，是路由匹配级证据。
 *       因此正腿要求 HTTP <b>200</b> 且信封是<b>整数</b> {@code "code":200}，
 *       并显式断言响应里<b>没有</b>平台 {@code Result} 的字符串 {@code "code":"0"}；</li>
 *   <li><b>必须选只有被测控制器能产生的特征</b>：
 *       <ul>
 *         <li>创建类（{@code POST}）由替身服务返回<b>独有标记串</b>，断言它出现在响应体里
 *             —— 标记只可能来自该控制器的返回值；</li>
 *         <li>Void 类（{@code PUT/DELETE/batch}）没有响应载荷标记，因此用
 *             {@link ArgumentCaptor} 断言替身服务收到的是<b>由该请求的路径变量与 JSON 体
 *             绑定出来的精确参数</b>——这证明的是"该控制的参数绑定执行了"，
 *             仍严格强于"路由匹配上了"；</li>
 *         <li>三条 batch 路由路径形状相同（只差最后一段），额外用
 *             {@code verify(..., never())} 钉住它们<b>不互相塌陷</b>；</li>
 *         <li>跨面隔离：调完映射面后断言会话/意图/样例面服务零交互，反之亦然。</li>
 *       </ul></li>
 *   <li><b>写面零 permit、零 ACK</b>：整轮结束后 {@code verifyNoInteractions(guard)} 且
 *       {@code acknowledgements} 为空（网关只对 GET/字节分支铸造交付回执）。</li>
 * </ul>
 *
 * <p><b>不签什么。</b>本类证明的是"经网关可达 + 参数绑定正确 + 写面不回执"。
 * 它<b>不</b>证明业务语义、不证明真库写入、不证明授权模型本身正确
 * （只证明"无 scope 时在 handler 之前 403"这一条）。
 */
@Tag("dev")
class LocalAdminWriteRouteDispatchTest {

    private final IntentTreeService intents = mock(IntentTreeService.class);
    private final QueryTermMappingAdminService mappings = mock(QueryTermMappingAdminService.class);
    private final SampleQuestionService samples = mock(SampleQuestionService.class);
    private final RevocationGuard guard = mock(RevocationGuard.class);
    private final List<String> acknowledgements = new ArrayList<>();

    private AnnotationConfigWebApplicationContext context;
    private MockServletContext servlet;
    private AiGatewayController gateway;
    private Set<String> permissions = Set.of("ai:config:read", "ai:config:publish", "ai:kb:read",
            "ai:kb:retrieve", "ai:run:read", "ai:run:event:read", "ai:document:read",
            "ai:conversation:read");

    @BeforeEach
    void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of(
                "ai.integration.enabled", "true", "ai.integration.transport", "local", "p2.enabled", "true")));
        context.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("intents", new IntentTreeController(intents));
            factory.registerSingleton("mappings", new QueryTermMappingController(mappings));
            factory.registerSingleton("samples", new SampleQuestionController(samples));
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
        when(identity.tenantState("T1")).thenReturn(PlatformIdentitySource.TenantState.ENABLED);
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
    @DisplayName("11 条写路由逐条到达自己的控制器：整数信封 + 精确参数绑定 + 写面零回执")
    void everyRegisteredWriteRouteReachesItsOwnController() throws Exception {
        // ---------- 1/11 PUT /intent-tree/{id} → config.publish
        assertWriteEnvelope(dispatch("PUT", "/intent-tree/intent-1", "{\"name\":\"rw31-intent-updated\"}"),
                "/intent-tree/intent-1");
        var intentUpdate = ArgumentCaptor.forClass(IntentNodeUpdateRequest.class);
        verify(intents).updateNode(eq("intent-1"), intentUpdate.capture());
        assertThat(intentUpdate.getValue().getName())
                .as("路径变量 + JSON 体必须被这个控制器的参数绑定读出")
                .isEqualTo("rw31-intent-updated");

        // ---------- 2/11 DELETE /intent-tree/{id} → config.publish
        assertWriteEnvelope(dispatch("DELETE", "/intent-tree/intent-2", null), "/intent-tree/intent-2");
        verify(intents).deleteNode("intent-2");

        // ---------- 3/11 POST /intent-tree/batch/enable → config.publish
        // 三条 batch 路由只差最后一段，逐条清空交互后各自验证，防止它们互相塌陷。
        clearInvocations(intents);
        assertWriteEnvelope(dispatch("POST", "/intent-tree/batch/enable",
                "{\"ids\":[\"rw31-enable-a\",\"rw31-enable-b\"]}"), "/intent-tree/batch/enable");
        var enabledIds = ArgumentCaptor.forClass(List.class);
        verify(intents).batchEnableNodes(enabledIds.capture());
        assertThat(enabledIds.getValue())
                .as("batch/enable 必须收到自己的 ids（且顺序保留）")
                .containsExactly("rw31-enable-a", "rw31-enable-b");
        verify(intents, never()).batchDisableNodes(any());
        verify(intents, never()).batchDeleteNodes(any());

        // ---------- 4/11 POST /intent-tree/batch/disable → config.publish
        clearInvocations(intents);
        assertWriteEnvelope(dispatch("POST", "/intent-tree/batch/disable",
                "{\"ids\":[\"rw31-disable-a\"]}"), "/intent-tree/batch/disable");
        var disabledIds = ArgumentCaptor.forClass(List.class);
        verify(intents).batchDisableNodes(disabledIds.capture());
        assertThat(disabledIds.getValue()).containsExactly("rw31-disable-a");
        verify(intents, never()).batchEnableNodes(any());
        verify(intents, never()).batchDeleteNodes(any());

        // ---------- 5/11 POST /intent-tree/batch/delete → config.publish
        clearInvocations(intents);
        assertWriteEnvelope(dispatch("POST", "/intent-tree/batch/delete",
                "{\"ids\":[\"rw31-delete-a\",\"rw31-delete-b\"]}"), "/intent-tree/batch/delete");
        var deletedIds = ArgumentCaptor.forClass(List.class);
        verify(intents).batchDeleteNodes(deletedIds.capture());
        assertThat(deletedIds.getValue()).containsExactly("rw31-delete-a", "rw31-delete-b");
        verify(intents, never()).batchEnableNodes(any());
        verify(intents, never()).batchDisableNodes(any());
        verifyNoInteractions(mappings, samples);

        // ================= 面 2/3：映射面 =================
        clearInvocations(intents, mappings, samples);

        // ---------- 6/11 POST /mappings → config.publish（创建类：响应体带独有标记）
        when(mappings.create(any())).thenReturn("rw31-created-mapping");
        var mappingCreated = dispatch("POST", "/mappings", "{\"sourceTerm\":\"rw31-src\",\"targetTerm\":\"rw31-tgt\"}");
        assertWriteEnvelope(mappingCreated, "/mappings");
        assertThat(mappingCreated.getContentAsString())
                .as("响应体里的独有标记只可能来自这个控制器的返回值")
                .contains("rw31-created-mapping");
        var mappingCreate = ArgumentCaptor.forClass(QueryTermMappingCreateRequest.class);
        verify(mappings).create(mappingCreate.capture());
        assertThat(mappingCreate.getValue().getSourceTerm()).isEqualTo("rw31-src");
        assertThat(mappingCreate.getValue().getTargetTerm()).isEqualTo("rw31-tgt");

        // ---------- 7/11 PUT /mappings/{id} → config.publish
        assertWriteEnvelope(dispatch("PUT", "/mappings/mapping-1",
                "{\"sourceTerm\":\"rw31-src-2\",\"remark\":\"rw31-remark\"}"), "/mappings/mapping-1");
        var mappingUpdate = ArgumentCaptor.forClass(QueryTermMappingUpdateRequest.class);
        verify(mappings).update(eq("mapping-1"), mappingUpdate.capture());
        assertThat(mappingUpdate.getValue().getRemark()).isEqualTo("rw31-remark");

        // ---------- 8/11 DELETE /mappings/{id} → config.publish
        assertWriteEnvelope(dispatch("DELETE", "/mappings/mapping-2", null), "/mappings/mapping-2");
        verify(mappings).delete("mapping-2");
        verifyNoInteractions(intents, samples);

        // ================= 面 3/3：样例问题面 =================
        clearInvocations(intents, mappings, samples);

        // ---------- 9/11 POST /sample-questions → config.publish（创建类：响应体带独有标记）
        when(samples.create(any())).thenReturn("rw31-created-sample");
        var sampleCreated = dispatch("POST", "/sample-questions",
                "{\"title\":\"rw31-title\",\"question\":\"rw31-question\"}");
        assertWriteEnvelope(sampleCreated, "/sample-questions");
        assertThat(sampleCreated.getContentAsString()).contains("rw31-created-sample");
        var sampleCreate = ArgumentCaptor.forClass(SampleQuestionCreateRequest.class);
        verify(samples).create(sampleCreate.capture());
        assertThat(sampleCreate.getValue().getQuestion()).isEqualTo("rw31-question");
        verifyNoInteractions(intents, mappings);

        // ---------- 10/11 PUT /sample-questions/{id} → config.publish
        assertWriteEnvelope(dispatch("PUT", "/sample-questions/sample-1",
                "{\"title\":\"rw31-title-2\"}"), "/sample-questions/sample-1");
        var sampleUpdate = ArgumentCaptor.forClass(SampleQuestionUpdateRequest.class);
        verify(samples).update(eq("sample-1"), sampleUpdate.capture());
        assertThat(sampleUpdate.getValue().getTitle()).isEqualTo("rw31-title-2");

        // ---------- 11/11 DELETE /sample-questions/{id} → config.publish
        assertWriteEnvelope(dispatch("DELETE", "/sample-questions/sample-2", null), "/sample-questions/sample-2");
        verify(samples).delete("sample-2");
        verifyNoInteractions(intents, mappings);

        // ---------- 写面：零交付 permit、零 ACK、无残留主体
        verifyNoInteractions(guard);
        assertThat(acknowledgements)
                .as("写面不得铸造交付回执（网关只在 GET/字节分支铸造）")
                .isEmpty();
        assertThat(com.nageoffer.ai.ragent.framework.context.PrincipalContext.hasPrincipal()).isFalse();
    }

    @Test
    @DisplayName("无 publish scope 时 11 条写路由全部在 handler 之前 403（证明上一条不是靠 403 通过的）")
    void withoutThePublishScopeEveryWriteRouteStopsBeforeItsHandler() throws Exception {
        permissions = Set.of("ai:config:read");
        for (WriteCall call : elevenWriteRoutes()) {
            var response = dispatch(call.method(), call.path(), call.body());
            assertThat(response.getStatus()).as(call.method() + " " + call.path()).isEqualTo(403);
        }
        verifyNoInteractions(intents, mappings, samples, guard);
        assertThat(acknowledgements).isEmpty();
    }

    /** 11 条写路由的 (方法, 路径, 体) 清单；两处判据共用，避免"清单漂移"。 */
    private static List<WriteCall> elevenWriteRoutes() {
        return List.of(
                new WriteCall("PUT", "/intent-tree/intent-1", "{\"name\":\"x\"}"),
                new WriteCall("DELETE", "/intent-tree/intent-2", null),
                new WriteCall("POST", "/intent-tree/batch/enable", "{\"ids\":[\"a\"]}"),
                new WriteCall("POST", "/intent-tree/batch/disable", "{\"ids\":[\"a\"]}"),
                new WriteCall("POST", "/intent-tree/batch/delete", "{\"ids\":[\"a\"]}"),
                new WriteCall("POST", "/mappings", "{}"),
                new WriteCall("PUT", "/mappings/mapping-1", "{}"),
                new WriteCall("DELETE", "/mappings/mapping-2", null),
                new WriteCall("POST", "/sample-questions", "{}"),
                new WriteCall("PUT", "/sample-questions/sample-1", "{}"),
                new WriteCall("DELETE", "/sample-questions/sample-2", null));
    }

    private record WriteCall(String method, String path, String body) {
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 写面信封判据：HTTP 200 + 整数 {@code "code":200} + <b>没有</b>平台 Result 的字符串
     * {@code "code":"0"}（后者经网关必然 503，出现即说明这一面没改造完）。
     */
    private static void assertWriteEnvelope(MockHttpServletResponse response, String path) throws Exception {
        assertThat(response.getStatus())
                .as("%s：写路由必须真到达 handler；403/404 都不是通过", path)
                .isEqualTo(200);
        String body = response.getContentAsString();
        assertThat(body).as("%s：必须是整数 code 的信封", path).contains("\"code\":200");
        assertThat(body).as("%s：不得是平台 Result 的字符串 code", path).doesNotContain("\"code\":\"0\"");
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
