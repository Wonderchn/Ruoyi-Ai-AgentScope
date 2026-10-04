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
import com.nageoffer.ai.ragent.ingest.UploadService;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ai.api.AiExecutionFacts;
import org.ruoyi.ai.api.identity.AiIdentityPort;
import org.ruoyi.aiintegration.authorization.OrganizationMatchController;
import org.ruoyi.aiintegration.config.AiIntegrationProperties;
import org.ruoyi.aiintegration.identity.CurrentPrincipalResolver;
import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.ruoyi.aiintegration.identity.ProductionAuthorizationProvider;
import org.ruoyi.aiintegration.web.AiGatewayController;
import org.ruoyi.aiweb.AiWebEmbeddedConfiguration;
import org.ruoyi.aiweb.security.AiInternalAccessBoundaryFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E3 单元二收尾：documents 上传/摄取路由组的 JSON 面经<b>本地接口</b>端到端（真实 Tomcat）。
 *
 * <p>装配真实 {@code AiEmbeddedDocumentConfiguration}（真实 UploadController/DeliveryPermits，
 * UploadService 以 mock 驱动——其内部语义由既有摄取/上传测试覆盖）：
 * <ul>
 *   <li>{@code GET /documents/{id}/meta} → 200 + 交付回执头（本地 permit）；
 *   <li>{@code GET /knowledge-bases/{id}/documents} → 200 列表；
 *   <li>{@code POST /documents/{id}/tombstone} → 200；缺 uploadId 的 ingestion → 400；
 *   <li>流式面（multipart 上传、私有 PDF source）→ fail-closed 503（专用流传输未落地）。</li>
 * </ul>
 */
@Tag("dev")
class LocalDocumentRouteDispatchTest {

    private static final String TENANT = "T1";
    private static final String USER = "2101";
    private static final String MEMBER = "platform:T1:2101";
    private static final int PV = 7;
    private static final int AV = 3;

    private static Tomcat tomcat;
    private static int port;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private static final NamedParameterJdbcTemplate namedJdbc = mock(NamedParameterJdbcTemplate.class);
    private static final TransactionOperations transactions = mock(TransactionOperations.class);
    private static final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private static final RevocationGuard revocations = mock(RevocationGuard.class);
    private static final ProductionAuthorizationProvider platformPermits =
            mock(ProductionAuthorizationProvider.class);
    private static final UploadService uploadService = mock(UploadService.class);

    @BeforeAll
    static void startContainer() throws Exception {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.register(FixtureConfig.class);
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "fixture", Map.of("ai.integration.enabled", "true", "ai.integration.transport", "local",
                        "p2.enabled", "true", "p2.object-store.type", "fs",
                        "p2.object-store.root", "target/local-document-objects")));

        tomcat = new Tomcat();
        tomcat.setBaseDir("target/local-document-route-tomcat");
        tomcat.setPort(0);
        File docBase = new File("target/local-document-route-docroot");
        docBase.mkdirs();
        Context ctx = tomcat.addContext("", docBase.getAbsolutePath());

        org.apache.tomcat.util.descriptor.web.FilterDef def =
                new org.apache.tomcat.util.descriptor.web.FilterDef();
        def.setFilterName("aiInternalAccessBoundaryFilter");
        def.setFilter(new AiInternalAccessBoundaryFilter());
        ctx.addFilterDef(def);
        org.apache.tomcat.util.descriptor.web.FilterMap map =
                new org.apache.tomcat.util.descriptor.web.FilterMap();
        map.setFilterName("aiInternalAccessBoundaryFilter");
        map.addURLPatternDecoded("/internal/ai/v1/*");
        map.setDispatcher("REQUEST");
        ctx.addFilterMap(map);

        org.apache.catalina.core.StandardWrapper wrapper =
                (org.apache.catalina.core.StandardWrapper) Tomcat.addServlet(ctx, "dispatcher",
                        new org.springframework.web.servlet.DispatcherServlet(context));
        wrapper.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/", "dispatcher");

        tomcat.start();
        port = tomcat.getConnector().getLocalPort();
    }

    @AfterAll
    static void stopContainer() throws LifecycleException {
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    @BeforeEach
    void baselineStubs() {
        reset(jdbc, namedJdbc, transactions, transactionManager, revocations, platformPermits, uploadService);
        when(revocations.enter(any(), any(), any())).thenAnswer(invocation ->
                new RevocationGuard.Operation(revocations, java.util.UUID.randomUUID().toString(),
                        java.util.UUID.randomUUID().toString()));
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + path))
                        .header("Authorization", "Bearer synthetic-session").GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + path))
                        .header("Authorization", "Bearer synthetic-session")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void documentMetaGoesLocalWithDeliveryPermit() throws Exception {
        when(uploadService.documentView(any(), eq("doc-1"))).thenReturn(Map.of("docId", "doc-1", "kbId", "kb-1"));

        HttpResponse<String> response = get("/api/ai/v1/documents/doc-1/meta");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"code\":200").contains("\"docId\":\"doc-1\"");
        verify(uploadService).documentView(any(), eq("doc-1"));
        verify(revocations).enter(any(), eq("document.read"), eq("doc:doc-1"));
    }

    @Test
    void knowledgeBaseDocumentsGoesLocal() throws Exception {
        when(uploadService.listDocuments(any(), eq("kb-1")))
                .thenReturn(List.of(Map.of("docId", "doc-1"), Map.of("docId", "doc-2")));

        HttpResponse<String> response = get("/api/ai/v1/knowledge-bases/kb-1/documents");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"code\":200").contains("doc-1").contains("doc-2");
    }

    @Test
    void tombstoneGoesLocalAndIngestionWithoutUploadIdStays400() throws Exception {
        when(uploadService.tombstone(any(), eq("doc-1"))).thenReturn(Map.of("docId", "doc-1", "status", "TOMBSTONED"));

        HttpResponse<String> tombstone = post("/api/ai/v1/documents/doc-1/tombstone", "");
        assertThat(tombstone.statusCode()).isEqualTo(200);
        assertThat(tombstone.body()).contains("TOMBSTONED");

        HttpResponse<String> ingestion = post("/api/ai/v1/documents/doc-1/ingestions", "{}");
        assertThat(ingestion.statusCode()).isEqualTo(400);
        assertThat(ingestion.body()).contains("BAD_REQUEST");
    }

    @Test
    void streamingRoutesStayFailClosed503() throws Exception {
        // 上传（multipart）与私有 PDF 取流属流式传输面：专用本地流未落地前 fail-closed
        HttpResponse<String> upload = CLIENT.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/api/ai/v1/documents/uploads"))
                        .header("Authorization", "Bearer synthetic-session")
                        .header("Content-Type", "multipart/form-data; boundary=X")
                        .POST(HttpRequest.BodyPublishers.ofString("--X--")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(upload.statusCode()).isEqualTo(503);

        HttpResponse<String> source = get("/api/ai/v1/documents/doc-1/source");
        assertThat(source.statusCode()).isEqualTo(503);
    }

    // ------------------------------------------------------------------ fixture

    @Configuration
    @EnableWebMvc
    @Import({AiGatewayController.class, AiWebEmbeddedConfiguration.class, AiEmbeddedRagConfiguration.class,
            AiEmbeddedRunConfiguration.class, AiEmbeddedDocumentConfiguration.class})
    static class FixtureConfig {

        @Bean
        AiIntegrationProperties aiIntegrationProperties() {
            AiIntegrationProperties properties = new AiIntegrationProperties();
            properties.setEnabled(true);
            properties.setTransport("local");
            properties.setForwardTimeoutMillis(2000);
            return properties;
        }

        @Bean
        CurrentPrincipalResolver currentPrincipalResolver() {
            return () -> Optional.of(new CurrentPrincipalResolver.CurrentMember(TENANT, USER, MEMBER));
        }

        @Bean
        PlatformIdentitySource platformIdentitySource() {
            return new PlatformIdentitySource() {
                @Override
                public TenantState tenantState(String tenantId) {
                    return TenantState.ENABLED;
                }

                @Override
                public PlatformIdentity membership(String tenantId, String subject, String membershipId) {
                    if (!MEMBER.equals(membershipId)) {
                        return null;
                    }
                    return new PlatformIdentity(TENANT, USER, MEMBER, true,
                            Set.of("ai:document:read", "ai:document:download", "ai:document:upload",
                            "ai:document:ingest", "ai:kb:delete"), PV);
                }
            };
        }

        @Bean
        OrganizationMatchController.SubjectMatchSource subjectMatchSource() {
            return new OrganizationMatchController.SubjectMatchSource() {
                @Override
                public Set<String> currentSubjects(String tenantId, String subject, String action) {
                    return Set.of("member:" + MEMBER, "tenant_all:" + TENANT);
                }

                @Override
                public Optional<SubjectOrgFacts> orgFacts(String tenantId, String subject) {
                    return Optional.empty();
                }
            };
        }

        @Bean
        AiIdentityPort aiIdentityPort() {
            return () -> Optional.of(new AiExecutionFacts(TENANT, USER, MEMBER, PV, AV,
                    Set.of("ai:document:read", "ai:document:download", "ai:document:upload",
                    "ai:document:ingest", "ai:kb:delete")));
        }

        @Bean
        JdbcTemplate jdbcTemplate() {
            return jdbc;
        }

        @Bean
        NamedParameterJdbcTemplate namedParameterJdbcTemplate() {
            return namedJdbc;
        }

        @Bean
        TransactionOperations transactionOperations() {
            return transactions;
        }

        @Bean
        PlatformTransactionManager platformTransactionManager() {
            return transactionManager;
        }

        @Bean
        com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper();
        }

        @Bean
        RevocationGuard defaultRevocationGuard() {
            return revocations;
        }

        @Bean
        ProductionAuthorizationProvider productionAuthorizationProvider() {
            return platformPermits;
        }

        /** 同名覆盖装配实现：路由/信封/许可真实，摄取与存储语义由既有测试覆盖。 */
        @Bean
        UploadService uploadService() {
            return uploadService;
        }
    }
}
