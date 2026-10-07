package org.ruoyi.aiweb.transport;

import com.nageoffer.ai.ragent.authorization.*;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.security.*;
import com.nageoffer.ai.ragent.rag.core.retrieval.AuthorizedRetrievalScope;
import com.nageoffer.ai.ragent.runtime.config.*;
import org.junit.jupiter.api.*;
import org.ruoyi.ai.api.AiExecutionFacts;
import org.ruoyi.ai.api.runtime.AuthorizedRetrievalPort;
import org.ruoyi.aiintegration.config.AiIntegrationProperties;
import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.ruoyi.aiintegration.identity.CurrentPrincipalResolver;
import org.ruoyi.aiintegration.web.AiGatewayController;
import org.ruoyi.aiweb.embedded.AiInternalExceptionResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.net.URI;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class LocalRetrievalDeliveryTest {
    private static AiResourceController fixture;
    private final AiResourceAuthorizationService authorization = mock(AiResourceAuthorizationService.class);
    private final RevocationGuard guard = mock(RevocationGuard.class);
    private final AuthorizedRetrievalPort<AuthorizedRetrievalScope,RetrievedChunk> retriever = mock(AuthorizedRetrievalPort.class);
    private final String permit = UUID.randomUUID().toString(), operation = UUID.randomUUID().toString();
    private AnnotationConfigWebApplicationContext context;
    private AiGatewayController gateway;
    private MockHttpServletRequest request;
    private MockHttpServletResponse output;
    private final List<String> acknowledgements = new ArrayList<>();
    private final AiDeliveryPermitConfiguration.DeliveryPermitHolder holder = new AiDeliveryPermitConfiguration.DeliveryPermitHolder();
    private Set<String> permissions = Set.of("ai:kb:retrieve");
    private final ConfigRevisionPublisher publisher = mock(ConfigRevisionPublisher.class);

    @BeforeEach void setup() {
        fixture = new AiResourceController(authorization, mock(AiResourceWriteService.class));
        ObjectProvider<AuthorizedRetrievalPort<AuthorizedRetrievalScope,RetrievedChunk>> retrievers = mock(ObjectProvider.class);
        when(retrievers.getIfAvailable()).thenReturn(retriever);
        fixture.configureExecution(guard, retrievers);
        fixture.configureDeliveryPermits(holder);
        var connections = mock(ProviderConnectionPort.class);
        when(connections.requireConnection(anyString(), any())).thenReturn(new ProviderConnectionPort.ProviderConnection(
                "deepseek", URI.create("http://localhost/chat/completions"), "env:deepseek", null));
        fixture.configureRuntimeConfig(publisher, connections);
        when(authorization.resolve(any(), eq("kb.retrieve"), anyCollection())).thenAnswer(call ->
                AuthorizedResourceScope.granted(call.getArgument(0), "kb.retrieve", List.of("kb:kb-1"), System.currentTimeMillis()));
        when(authorization.toRetrievalScope(any())).thenAnswer(call -> AuthorizedRetrievalScope.of(call.getArgument(0),
                Set.of("kb:kb-1"), Set.of("doc:d1"), Set.of("chunk:c1"), Set.of("collection-1")));
        when(guard.enter(any(), eq("kb.retrieve"), eq("tenant:retrieval")))
                .thenReturn(new RevocationGuard.Operation(guard, permit, operation));
        when(retriever.retrieve(any(), anyString(), anyInt())).thenReturn(List.of(RetrievedChunk.builder().id("c1").text("controlled fixture").score(1.0f).build()));
        context = new AnnotationConfigWebApplicationContext();
        // This slice supplies a preassembled real controller, then exercises MVC and the real gateway.
        context.addBeanFactoryPostProcessor(factory -> factory.registerSingleton("resources", fixture));
        var servlet = new MockServletContext();
        context.setServletContext(servlet); context.register(Fixture.class); context.refresh();
        servlet.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, context);
        request = new MockHttpServletRequest(servlet, "POST", "/api/ai/v1/knowledge-bases/retrievals");
        request.setContentType("application/json"); request.addHeader("Authorization", "fixture-session");
        request.setAttribute(DispatcherServlet.WEB_APPLICATION_CONTEXT_ATTRIBUTE, context);
        output = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, output));
        var client = new LocalAiGatewayClient(2000, () -> Optional.of(new AiExecutionFacts("T1", "2101", "platform:T1:2101", 1, 1,
                permissions)), (tenant, member, permitId, operationId) -> {
            assertThat(tenant).isEqualTo("T1"); assertThat(member).isEqualTo("platform:T1:2101");
            acknowledgements.add(permitId + ":" + operationId);
        });
        var identities = mock(PlatformIdentitySource.class);
        when(identities.tenantState("T1")).thenReturn(PlatformIdentitySource.TenantState.ENABLED);
        when(identities.membership("T1", "2101", "platform:T1:2101")).thenAnswer(call -> new PlatformIdentitySource.PlatformIdentity("T1", "2101", "platform:T1:2101", true, permissions, 1));
        ObjectProvider<PlatformIdentitySource> identityProvider = mock(ObjectProvider.class);
        when(identityProvider.getIfAvailable()).thenReturn(identities);
        var resolver = mock(CurrentPrincipalResolver.class);
        when(resolver.resolveCurrentMember()).thenReturn(Optional.of(new CurrentPrincipalResolver.CurrentMember("T1", "2101", "platform:T1:2101")));
        var properties = new AiIntegrationProperties(); properties.setTransport("local"); properties.setAiBaseUrl("http://local");
        gateway = new AiGatewayController(resolver, identityProvider, mock(ObjectProvider.class), client, properties);
    }
    @AfterEach void cleanup() {
        if (request != null) { holder.closeAll(request); }
        PrincipalContext.clear(); RequestContextHolder.resetRequestAttributes();
        if (context != null) { context.close(); }
    }
    private byte[] body() { return "{\"query\":\"test\",\"requestedKbIds\":[\"kb-1\"],\"topK\":3}".getBytes(StandardCharsets.UTF_8); }
    @Test void actualRetrievalRouteReturnsTheJsonAndAcknowledgesAfterDelivery() throws Exception {
        assertTimeout(Duration.ofSeconds(3), () -> assertThat(gateway.gateway(request, output, body())).isNull());
        assertThat(output.getStatus()).isEqualTo(200);
        assertThat(output.getContentAsString()).contains("controlled fixture", "\"code\":200");
        assertThat(acknowledgements).containsExactly(permit + ":" + operation);
        assertThat(PrincipalContext.hasPrincipal()).isFalse();
        assertThat(holder.closeAll(request)).isEqualTo(1);
        verify(guard).release(permit, operation);
    }
    @Test void failedRetrievalReturns503AndClosesTheOperationWithoutDeliveryAck() {
        when(retriever.retrieve(any(), anyString(), anyInt())).thenThrow(new DataAccessResourceFailureException("synthetic-db-secret"));
        var response = gateway.gateway(request, output, body());
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().toString()).contains("DEPENDENCY_UNAVAILABLE").doesNotContain("synthetic-db-secret");
        assertThat(acknowledgements).isEmpty(); verify(guard).release(permit, operation);
    }
    @Test void emptyAuthorizedScopeCannotReachTheRetriever() {
        doAnswer(call -> AuthorizedRetrievalScope.of(call.getArgument(0), Set.of(), Set.of(), Set.of(), Set.of()))
                .when(authorization).toRetrievalScope(any());
        var response = gateway.gateway(request, output, body());
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verifyNoInteractions(retriever); verifyNoInteractions(guard);
    }

    private byte[] configBody() { return "{\"providerId\":\"deepseek\",\"modelId\":\"deepseek-flash\",\"catalogVersion\":\"catalog\",\"dimension\":1536,\"params\":{\"temperature\":0.7},\"tenantId\":\"T2\"}".getBytes(StandardCharsets.UTF_8); }

    @Test void configurationPublishUsesTheAuthorityAndCurrentIdentityThroughTheRealHttpRoute() {
        permissions = Set.of("ai:config:publish");
        request.setRequestURI("/api/ai/v1/runtime-config/revisions");
        request.addHeader("X-Tenant-Id", "T2");
        when(publisher.publish(any())).thenAnswer(call -> {
            assertThat(PrincipalContext.require().tenantId()).isEqualTo("T1");
            assertThat(PrincipalContext.require().userId()).isEqualTo("2101");
            return new ConfigRevisionFacts("T1", "rev-http", 1, "deepseek", "deepseek-flash", "catalog", "hash",
                    "env:deepseek", "2101", Instant.now(), 1536);
        });
        var result = gateway.gateway(request, output, configBody());
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody().toString()).contains("rev-http", "\"tenantId\":\"T1\"").doesNotContain("\"tenantId\":\"T2\"");
        verify(publisher).publish(any());
        assertThat(PrincipalContext.hasPrincipal()).isFalse();
    }

    @Test void configurationPublishWithoutItsSeparatePermissionCannotReachTheAuthority() {
        request.setRequestURI("/api/ai/v1/runtime-config/revisions");
        var result = gateway.gateway(request, output, configBody());
        assertThat(result.getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(publisher);
    }
    @Configuration @EnableWebMvc static class Fixture {
        @Bean AiInternalExceptionResolver errors() { return new AiInternalExceptionResolver(); }
    }
}
