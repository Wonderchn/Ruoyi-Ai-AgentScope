package org.ruoyi.aiweb.embedded;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.admin.controller.DashboardController;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardOverviewVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardPerformanceVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardTrendsVO;
import com.nageoffer.ai.ragent.admin.service.DashboardService;
import com.nageoffer.ai.ragent.audit.controller.BizChangeLogController;
import com.nageoffer.ai.ragent.audit.controller.vo.BizChangeLogVO;
import com.nageoffer.ai.ragent.audit.service.BizChangeLogService;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.ingestion.service.IntentTreeService;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.controller.KnowledgeChunkController;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeChunkVO;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeChunkService;
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
import com.nageoffer.ai.ragent.rag.dto.RecommendedQuestionsPayload;
import com.nageoffer.ai.ragent.rag.eval.EvalController;
import com.nageoffer.ai.ragent.rag.service.QueryTermMappingAdminService;
import com.nageoffer.ai.ragent.rag.service.RecommendedQuestionService;
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
    private final DashboardService dashboard = mock(DashboardService.class);
    private String tenantId = "T1";
    private final IntentTreeService intents = mock(IntentTreeService.class);
    private final QueryTermMappingAdminService mappings = mock(QueryTermMappingAdminService.class);
    private final SampleQuestionService samples = mock(SampleQuestionService.class);
    private final RagTraceQueryService traces = mock(RagTraceQueryService.class);
    private final BizChangeLogService logs = mock(BizChangeLogService.class);
    private final QueryRewriteService rewrite = mock(QueryRewriteService.class);
    private final IntentResolver resolver = mock(IntentResolver.class);
    private final RetrievalEngine retrieval = mock(RetrievalEngine.class);
    private final RevocationGuard guard = mock(RevocationGuard.class);
    private final KnowledgeChunkService chunks = mock(KnowledgeChunkService.class);
    private final RecommendedQuestionService recommended = mock(RecommendedQuestionService.class);
    private final List<String> acknowledgements = new ArrayList<>();
    private AnnotationConfigWebApplicationContext context;
    private MockServletContext servlet;
    private AiGatewayController gateway;
    private Set<String> permissions = Set.of("ai:config:read", "ai:config:publish", "ai:kb:read",
            "ai:kb:retrieve", "ai:run:read", "ai:run:event:read", "ai:document:read",
            "ai:conversation:write");

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

        when(dashboard.loadOverview(any())).thenAnswer(call -> DashboardOverviewVO.builder()
                .engine("r10-overview-" + PrincipalContext.require().tenantId()).window(call.getArgument(0)).build());
        when(dashboard.loadPerformance(any())).thenAnswer(call -> DashboardPerformanceVO.builder()
                .engine("r10-performance-" + PrincipalContext.require().tenantId()).window(call.getArgument(0)).build());
        when(dashboard.loadTrends(anyString(), any(), any())).thenAnswer(call -> DashboardTrendsVO.builder()
                .metric("r10-trends-" + PrincipalContext.require().tenantId()).window(call.getArgument(1))
                .granularity(call.getArgument(2)).build());
        context = new AnnotationConfigWebApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of(
                "ai.integration.enabled", "true", "ai.integration.transport", "local", "p2.enabled", "true")));
        context.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("dashboard", new DashboardController(dashboard));
            factory.registerSingleton("intents", new IntentTreeController(intents));
            factory.registerSingleton("mappings", new QueryTermMappingController(mappings));
            factory.registerSingleton("samples", new SampleQuestionController(samples));
            factory.registerSingleton("traces", new RagTraceController(traces));
            factory.registerSingleton("logs", new BizChangeLogController(logs));
            factory.registerSingleton("eval", new EvalController(rewrite, resolver, retrieval,
                    mock(KnowledgeChunkMapper.class), mock(KnowledgeDocumentMapper.class)));
            ObjectProvider<RevocationGuard> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(guard);
            var permits = new DeliveryPermits(provider);
            factory.registerSingleton("deliveryPermits", permits);
            factory.registerSingleton("chunks", new KnowledgeChunkController(chunks, permits));
            factory.registerSingleton("recommended", new RecommendedQuestionController(recommended));
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
                new PlatformIdentitySource.PlatformIdentity(tenantId, "2101", "platform:" + tenantId + ":2101", true, permissions, 1));
        ObjectProvider<PlatformIdentitySource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(identity);
        var current = mock(CurrentPrincipalResolver.class);
        when(current.resolveCurrentMember()).thenAnswer(call -> Optional.of(
                new CurrentPrincipalResolver.CurrentMember(tenantId, "2101", "platform:" + tenantId + ":2101")));
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
        output.getWriter().write(result.getBody() instanceof String responseBody ? responseBody
                : new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result.getBody()));
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

    @Test void previouslyRegisteredKnowledgeChunkRouteAlsoTraversesThePublicGateway() throws Exception {
        var chunk = new KnowledgeChunkVO();
        chunk.setId("r6-chunk"); chunk.setDocId("doc-1");
        var page = new Page<KnowledgeChunkVO>(); page.setRecords(List.of(chunk));
        when(chunks.pageQuery(eq("doc-1"), any())).thenReturn(page);
        var response = dispatch("GET", "/knowledge-base/docs/doc-1/chunks", null);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("\"code\":200", "r6-chunk");
        verify(guard).enter(any(), eq("document.read"), eq("doc:doc-1"));
        assertThat(acknowledgements).hasSize(1);
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

    /**
     * RW-22-R1-R7：推荐追问面（F17）的公开可达性正腿。
     *
     * <p>判据刻意选<b>只有该控制器能产生</b>的响应特征：服务替身返回的独有标记，
     * 以及"服务收到的 userId 必须是规范主体 2101"——内嵌态下旧的
     * {@code UserContext.getUserId()} 恒为 {@code null}，会把 null 一路送进
     * {@code user_id} 作用域谓词（静默作用域失效）。
     * 不能退化成"路由匹配到了"判据：后者源码级护栏本就能证，而 403 被当通过时正是那种退化。
     */
    @Test void recommendationRouteReachesItsOwnControllerWithCanonicalIdentityAndNoReceipt() throws Exception {
        when(recommended.generate(eq("message-1"), eq("2101")))
                .thenReturn(RecommendedQuestionsPayload.success(List.of("r6-recommended-probe")));
        var response = dispatch("POST", "/conversations/messages/message-1/recommended-questions", "{}");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString())
                .contains("\"code\":200", "r6-recommended-probe")
                .doesNotContain("\"code\":\"0\"");
        verify(recommended).generate("message-1", "2101");
        // 写面：不铸 GET 交付回执（网关只在字节分支释放许可）
        verifyNoInteractions(guard);
        assertThat(acknowledgements).isEmpty();
        assertThat(PrincipalContext.hasPrincipal()).isFalse();
    }

    @Test void recommendationRouteWithoutItsScopeStopsBeforeTheHandler() throws Exception {
        permissions = Set.of();
        assertThat(dispatch("POST", "/conversations/messages/message-1/recommended-questions", "{}").getStatus())
                .isEqualTo(403);
        verifyNoInteractions(recommended, guard);
    }

    /**
     * R（RW-29-R7）REVIEW_FAIL 的回归钉：该 POST 在缓存未命中时会
     * ①调用真实 LLM generator（计费外呼）②UPDATE 落库，
     * 因此**只持 {@code ai:conversation:read} 不得触发**。
     *
     * <p>这条判据钉的是"动作选错"这一类缺陷：把动作从写降回读、或误把该路由
     * 登记为读动作，本判据会立刻变红。它同时防止"用读权限做写 + 触发外呼"复发。
     */
    @Test void recommendationRouteRejectsReadOnlyPermission() throws Exception {
        permissions = Set.of("ai:conversation:read");
        assertThat(dispatch("POST", "/conversations/messages/message-1/recommended-questions", "{}").getStatus())
                .isEqualTo(403);
        verifyNoInteractions(recommended, guard);
    }

    @Test void missingScopeStopsBeforeTheHandlerAndNeighboringPathsStayClosed() throws Exception {
        permissions = Set.of();
        assertThat(dispatch("GET", "/intent-tree/trees", null).getStatus()).isEqualTo(403);
        verifyNoInteractions(intents, guard);
        assertThat(dispatch("GET", "/intent-tree/trees/extra", null).getStatus()).isEqualTo(404);
        var dashboardDenied = dispatch("GET", "/dashboard/overview", null);
        assertThat(dashboardDenied.getStatus()).isEqualTo(403);
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(dashboardDenied.getContentAsString())
                .path("data").path("errorCode").asText()).isEqualTo("FORBIDDEN");
        verifyNoInteractions(dashboard);
    }

    @Test void dashboardGetRoutesReachTheirOwnPayloadsAndConsumeOneReceiptPerTenantRequest() throws Exception {
        permissions = Set.of("ai:run:read");
        var cases = Map.of("/dashboard/overview?window=7d", "r10-overview-",
                "/dashboard/performance?window=30d", "r10-performance-",
                "/dashboard/trends?metric=messages&window=7d&granularity=day", "r10-trends-");
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String tenant : List.of("T1", "T2", "T1")) {
            tenantId = tenant;
            for (var entry : cases.entrySet()) {
                var response = dispatch("GET", entry.getKey(), null);
                assertThat(response.getStatus()).as(entry.getKey()).isEqualTo(200);
                var envelope = json.readTree(response.getContentAsString());
                assertThat(envelope.path("code").isIntegralNumber()).isTrue();
                assertThat(envelope.path("code").intValue()).isEqualTo(200);
                assertThat(envelope.path("data").toString()).contains(entry.getValue() + tenant)
                        .doesNotContain(entry.getValue() + (tenant.equals("T1") ? "T2" : "T1"));
                assertThat(response.getContentAsString()).doesNotContain("X-AI-Delivery-Permit");
                assertThat(PrincipalContext.hasPrincipal()).isFalse();
            }
        }
        verify(dashboard, times(3)).loadOverview("7d");
        verify(dashboard, times(3)).loadPerformance("30d");
        verify(dashboard, times(3)).loadTrends("messages", "7d", "day");
        assertThat(acknowledgements).hasSize(9).doesNotHaveDuplicates();
        verify(guard, times(9)).enter(any(), eq("run.get"), startsWith("admin-read:/api/ai/v1/dashboard/"));
    }

    @Test void dashboardDeniedScopesAndNeighboringPathsNeverCallTheHandler() throws Exception {
        permissions = Set.of("ai:config:read", "ai:kb:read");
        for (String path : List.of("/dashboard/overview", "/dashboard/performance", "/dashboard/trends?metric=messages")) {
            var response = dispatch("GET", path, null);
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString())
                    .path("data").path("errorCode").asText()).isEqualTo("FORBIDDEN");
        }
        assertThat(dispatch("GET", "/dashboard/overview/extra", null).getStatus()).isEqualTo(404);
        assertThat(dispatch("GET", "/admin/dashboard/overview", null).getStatus()).isEqualTo(404);
        verifyNoInteractions(dashboard, guard);
        assertThat(acknowledgements).isEmpty();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(AiEmbeddedAdminDeliveryConfiguration.class)
    static class Fixture {
        @Bean AiInternalExceptionResolver errors() { return new AiInternalExceptionResolver(); }
    }
}
