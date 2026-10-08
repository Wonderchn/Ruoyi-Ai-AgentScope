package org.ruoyi.aiweb.embedded;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.audit.controller.BizChangeLogController;
import com.nageoffer.ai.ragent.audit.controller.vo.BizChangeLogVO;
import com.nageoffer.ai.ragent.audit.service.BizChangeLogService;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.ingestion.service.IntentTreeService;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.rag.controller.*;
import com.nageoffer.ai.ragent.rag.controller.vo.IntentNodeTreeVO;
import com.nageoffer.ai.ragent.rag.controller.vo.QueryTermMappingVO;
import com.nageoffer.ai.ragent.rag.controller.vo.RagTraceDetailVO;
import com.nageoffer.ai.ragent.rag.controller.vo.RagTraceRunVO;
import com.nageoffer.ai.ragent.rag.controller.vo.RagTraceNodeVO;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.eval.EvalController;
import com.nageoffer.ai.ragent.rag.service.QueryTermMappingAdminService;
import com.nageoffer.ai.ragent.rag.service.RagTraceQueryService;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import com.nageoffer.ai.ragent.sample.controller.SampleQuestionController;
import com.nageoffer.ai.ragent.sample.controller.vo.SampleQuestionVO;
import com.nageoffer.ai.ragent.sample.service.SampleQuestionService;
import org.junit.jupiter.api.*;
import org.ruoyi.ai.api.AiExecutionFacts;
import org.ruoyi.aiintegration.config.AiIntegrationProperties;
import org.ruoyi.aiintegration.identity.CurrentPrincipalResolver;
import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.ruoyi.aiintegration.web.AiGatewayController;
import org.ruoyi.aiweb.transport.LocalAiGatewayClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real gateway/local MVC/controllers/receipt advice; only business collaborators are doubles. */
@Tag("dev")
class LocalAdminRouteDispatchTest {
    private final IntentTreeService intents = mock(IntentTreeService.class);
    private final QueryTermMappingAdminService mappings = mock(QueryTermMappingAdminService.class);
    private final SampleQuestionService samples = mock(SampleQuestionService.class);
    private final RagTraceQueryService traces = mock(RagTraceQueryService.class);
    private final BizChangeLogService logs = mock(BizChangeLogService.class);
    private final QueryRewriteService rewrite = mock(QueryRewriteService.class);
    private final IntentResolver resolver = mock(IntentResolver.class);
    private final RetrievalEngine retrieval = mock(RetrievalEngine.class);
    private final RevocationGuard guard = mock(RevocationGuard.class);
    private final List<String> acknowledgements = new ArrayList<>();
    private AnnotationConfigWebApplicationContext context;
    private MockServletContext servlet;
    private AiGatewayController gateway;
    private Set<String> permissions = Set.of("ai:config:read", "ai:config:publish", "ai:kb:read",
            "ai:kb:retrieve", "ai:run:read", "ai:run:event:read");

    @BeforeEach void setup() {
        when(intents.getFullTree()).thenReturn(List.of(IntentNodeTreeVO.builder().id("r6-intent").build()));
        when(intents.createNode(any())).thenReturn("r6-created-intent");
        when(mappings.queryById("mapping-1")).thenReturn(QueryTermMappingVO.builder().id("r6-mapping").build());
        var mappingsPage = new Page<QueryTermMappingVO>();
        mappingsPage.setRecords(List.of(QueryTermMappingVO.builder().id("r6-mapping-page").build()));
        when(mappings.pageQuery(any())).thenReturn(mappingsPage);
        when(samples.listRandomQuestions(anyInt())).thenReturn(List.of(SampleQuestionVO.builder().id("r6-sample").build()));
        when(samples.queryById("sample-1")).thenReturn(SampleQuestionVO.builder().id("r6-sample-detail").build());
        var page = new Page<SampleQuestionVO>();
        page.setRecords(List.of(SampleQuestionVO.builder().id("r6-sample-root").build()));
        when(samples.pageQuery(any())).thenReturn(page);
        when(traces.detail(eq("trace-1"), any())).thenReturn(RagTraceDetailVO.builder()
                .run(RagTraceRunVO.builder().traceId("r6-trace").build()).build());
        var tracesPage = new Page<RagTraceRunVO>();
        tracesPage.setRecords(List.of(RagTraceRunVO.builder().traceId("r6-trace-page").build()));
        when(traces.pageRuns(any(), any())).thenReturn(tracesPage);
        when(traces.listNodes(eq("trace-1"), any())).thenReturn(List.of(
                RagTraceNodeVO.builder().nodeId("r6-trace-node").build()));
        when(logs.get(eq("log-1"), any())).thenReturn(BizChangeLogVO.builder().id("r6-change-log").build());
        var logsPage = new Page<BizChangeLogVO>();
        logsPage.setRecords(List.of(BizChangeLogVO.builder().id("r6-change-log-page").build()));
        when(logs.page(any(), any())).thenReturn(logsPage);
        when(resolver.resolve(any())).thenReturn(List.of());
        var retrieved = mock(RetrievalContext.class);
        when(retrieved.getIntentChunks()).thenReturn(Map.of());
        when(retrieval.retrieve(any())).thenReturn(retrieved);
        when(guard.enter(any(), anyString(), anyString())).thenAnswer(call -> new RevocationGuard.Operation(
                guard, UUID.randomUUID().toString(), UUID.randomUUID().toString()));

        context = new AnnotationConfigWebApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of(
                "ai.integration.enabled", "true", "ai.integration.transport", "local", "p2.enabled", "true")));
        context.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("intents", new IntentTreeController(intents));
            factory.registerSingleton("mappings", new QueryTermMappingController(mappings));
            factory.registerSingleton("samples", new SampleQuestionController(samples));
            factory.registerSingleton("traces", new RagTraceController(traces));
            factory.registerSingleton("logs", new BizChangeLogController(logs));
            factory.registerSingleton("eval", new EvalController(rewrite, resolver, retrieval,
                    mock(KnowledgeChunkMapper.class), mock(KnowledgeDocumentMapper.class)));
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
                (tenant, member, permit, operation) -> {
                    assertThat(tenant).isEqualTo("T1");
                    assertThat(member).isEqualTo("platform:T1:2101");
                    acknowledgements.add(permit + ":" + operation);
                });
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
        properties.setTransport("local"); properties.setAiBaseUrl("http://local");
        gateway = new AiGatewayController(current, provider, mock(ObjectProvider.class), client, properties);
    }

    @AfterEach void cleanup() {
        PrincipalContext.clear(); RequestContextHolder.resetRequestAttributes();
        if (context != null) context.close();
    }

    private MockHttpServletResponse dispatch(String method, String path, String body) throws Exception {
        int queryAt = path.indexOf('?');
        var request = new MockHttpServletRequest(servlet, method, "/api/ai/v1" +
                (queryAt < 0 ? path : path.substring(0, queryAt)));
        if (queryAt >= 0) request.setQueryString(path.substring(queryAt + 1));
        request.addHeader("Authorization", "fixture-session"); request.setContentType("application/json");
        request.setAttribute(DispatcherServlet.WEB_APPLICATION_CONTEXT_ATTRIBUTE, context);
        var output = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, output));
        var result = gateway.gateway(request, output, body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
        if (result != null) {
            output.setStatus(result.getStatusCode().value());
            output.getWriter().write(String.valueOf(result.getBody()));
        }
        return output;
    }

    @Test void publicGetRoutesReturnTheirOwnPayloadsAndAcknowledgeEveryReceipt() throws Exception {
        var cases = Map.ofEntries(Map.entry("/intent-tree/trees", "r6-intent"),
                Map.entry("/mappings/mapping-1", "r6-mapping"), Map.entry("/mappings", "r6-mapping-page"),
                Map.entry("/sample-questions/random", "r6-sample"),
                Map.entry("/sample-questions/sample-1", "r6-sample-detail"),
                Map.entry("/sample-questions", "r6-sample-root"), Map.entry("/rag/traces/runs/trace-1", "r6-trace"),
                Map.entry("/rag/traces/runs", "r6-trace-page"),
                Map.entry("/rag/traces/runs/trace-1/nodes", "r6-trace-node"),
                Map.entry("/biz-change-logs/log-1", "r6-change-log"),
                Map.entry("/biz-change-logs", "r6-change-log-page"));
        for (var entry : cases.entrySet()) {
            var response = dispatch("GET", entry.getKey(), null);
            assertThat(response.getStatus()).as(entry.getKey()).isEqualTo(200);
            assertThat(response.getContentAsString()).contains("\"code\":200", entry.getValue());
        }
        assertThat(acknowledgements).hasSize(cases.size()).doesNotHaveDuplicates();
        verify(guard, times(cases.size())).enter(any(), anyString(), anyString());
        assertThat(PrincipalContext.hasPrincipal()).isFalse();
    }

    @Test void writeRoutesReturnIntegerEnvelopeWithoutMintingUnconsumedReceipts() throws Exception {
        var response = dispatch("POST", "/intent-tree", "{}");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("\"code\":200", "r6-created-intent");
        verifyNoInteractions(guard);
        assertThat(acknowledgements).isEmpty();
    }

    @Test void evalUsesItsActualControllerAndCarriesTheRetrievalReceipt() throws Exception {
        // Query string goes through the same parsing path used by the local bridge.
        var response = dispatch("GET", "/rag/eval?question=synthetic-r6", null);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("\"code\":200", "retrievedContextDocIds");
        verify(rewrite).rewriteWithSplit(eq("synthetic-r6"), anyList());
        verify(guard).enter(any(), eq("kb.retrieve"), anyString());
        assertThat(acknowledgements).hasSize(1);
    }

    @Test void missingScopeStopsBeforeTheHandlerAndNeighboringPathsStayClosed() throws Exception {
        permissions = Set.of();
        assertThat(dispatch("GET", "/intent-tree/trees", null).getStatus()).isEqualTo(403);
        verifyNoInteractions(intents, guard);
        assertThat(dispatch("GET", "/intent-tree/trees/extra", null).getStatus()).isEqualTo(404);
        assertThat(dispatch("GET", "/admin/dashboard/overview", null).getStatus()).isEqualTo(404);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(AiEmbeddedAdminDeliveryConfiguration.class)
    static class Fixture {
        @Bean AiInternalExceptionResolver errors() { return new AiInternalExceptionResolver(); }
    }
}
