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

import com.nageoffer.ai.ragent.authorization.AiResourceWriteService;
import com.nageoffer.ai.ragent.authorization.dao.AiAclEpochMapper;
import com.nageoffer.ai.ragent.authorization.dao.AiResourceAclMapper;
import com.nageoffer.ai.ragent.authorization.dao.AiResourceMapper;
import com.nageoffer.ai.ragent.authorization.dao.ResourceSourceRefMapper;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
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
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E3 单元二收尾：rag 授权/资源路由组经<b>本地接口</b>端到端（真实 Tomcat 容器）。
 *
 * <p>装配真实 {@code AiEmbeddedRagConfiguration}（真实 AiResourceController +
 * 真实 AiResourceAuthorizationService + 本地事实/许可/检查端口），持久层用 mock 驱动
 * （无需 DB/Redis）：
 * <ul>
 *   <li>白名单 GET 路由经 LocalAiGatewayClient 直调到内嵌 AI 控制器，
 *       信封与交付回执（ACK 经 AiDeliveryReleaser 本地释放）完整；</li>
 *   <li>业务负例语义不减：DENY→404、STALE→409、内部路径对外 404、白名单外 404；</li>
 *   <li>授权链的 platform 事实全部来自本地端口（身份源/组织匹配），无 localhost HTTP。</li>
 * </ul>
 */
@Tag("dev")
class LocalRagRouteDispatchTest {

    private static final String TENANT = "T1";
    private static final String USER = "2101";
    private static final String MEMBER = "platform:T1:2101";
    private static final int PV = 7;
    private static final int AV = 3;

    private static Tomcat tomcat;
    private static int port;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static final AiResourceMapper resourceMapper = mock(AiResourceMapper.class);
    private static final AiResourceAclMapper aclMapper = mock(AiResourceAclMapper.class);
    private static final AiAclEpochMapper epochMapper = mock(AiAclEpochMapper.class);
    private static final ResourceSourceRefMapper sourceRefMapper = mock(ResourceSourceRefMapper.class);
    private static final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private static final NamedParameterJdbcTemplate namedJdbc = mock(NamedParameterJdbcTemplate.class);
    private static final TransactionOperations transactions = mock(TransactionOperations.class);
    private static final RevocationGuard revocations = mock(RevocationGuard.class);
    private static final ProductionAuthorizationProvider platformPermits =
            mock(ProductionAuthorizationProvider.class);

    /** 交付回执发生在响应已提交之后（同一请求线程），测试需等待其完成。 */
    private static volatile java.util.concurrent.CountDownLatch deliveryAcks = new java.util.concurrent.CountDownLatch(0);

    @BeforeAll
    static void startContainer() throws Exception {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.register(FixtureConfig.class);
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "fixture", Map.of("ai.integration.enabled", "true", "ai.integration.transport", "local")));

        tomcat = new Tomcat();
        tomcat.setBaseDir("target/local-rag-route-tomcat");
        tomcat.setPort(0);
        File docBase = new File("target/local-rag-route-docroot");
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
        reset(resourceMapper, aclMapper, epochMapper, sourceRefMapper, jdbc, namedJdbc, transactions,
                revocations, platformPermits);

        when(epochMapper.findVersion(TENANT)).thenReturn(Optional.of(AV));
        when(resourceMapper.findByPk(TENANT, "KB", "kb-1")).thenReturn(Optional.of(
                new AiResourceMapper.AiResourceRow(TENANT, "KB", "kb-1", MEMBER, null, null, null, "ACTIVE", 1L)));
        when(resourceMapper.listActive(TENANT)).thenReturn(List.of(
                new AiResourceMapper.AiResourceRow(TENANT, "KB", "kb-1", MEMBER, null, null, null, "ACTIVE", 1L)));
        when(aclMapper.findByResource(TENANT, "KB", "kb-1")).thenReturn(List.of(
                new AiResourceAclMapper.AclRow("acl-1", TENANT, "KB", "kb-1", "MEMBER", MEMBER,
                        "kb.read", null, MEMBER)));
        when(aclMapper.listTenantRows(TENANT)).thenReturn(List.of(
                new AiResourceAclMapper.AclRow("acl-1", TENANT, "KB", "kb-1", "MEMBER", MEMBER,
                        "kb.read", null, MEMBER)));
        when(sourceRefMapper.findChildren(TENANT, "KB", "kb-1")).thenReturn(List.of());
        when(namedJdbc.query(contains("FROM t_knowledge_base"), anyMap(), any(RowMapper.class)))
                .thenReturn(List.of(new AiResourceWriteService.KnowledgeBaseView(
                        "kb-1", "kb-one", "text-embedding-3", "p1-col", MEMBER, null)));
        when(revocations.enter(any(), any(), any())).thenAnswer(invocation ->
                new RevocationGuard.Operation(revocations, java.util.UUID.randomUUID().toString(),
                        java.util.UUID.randomUUID().toString()));
        deliveryAcks = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            deliveryAcks.countDown();
            return null;
        }).when(revocations).releaseDelivery(any(), any(), any(), any());
    }

    private static HttpResponse<String> get(String path, boolean withLogin) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (withLogin) {
            builder.header("Authorization", "Bearer synthetic-session");
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void knowledgeBaseListGoesLocalWithEnvelopeAndDeliveryAck() throws Exception {
        HttpResponse<String> response = get("/api/ai/v1/knowledge-bases", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"code\":200").contains("kb-1").contains("kb-one");
        // 交付回执经本地 releaser 释放（跨进程形态是独立 POST；内嵌形态无 HTTP）
        assertThat(deliveryAcks.await(5, java.util.concurrent.TimeUnit.SECONDS))
                .as("交付回执应在响应提交后完成").isTrue();
        verify(revocations).releaseDelivery(org.mockito.ArgumentMatchers.eq(TENANT),
                org.mockito.ArgumentMatchers.eq(MEMBER), any(), any());
    }

    @Test
    void knowledgeBaseGetGoesLocalWithDocumentRefs() throws Exception {
        HttpResponse<String> response = get("/api/ai/v1/knowledge-bases/kb-1", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"code\":200").contains("\"kbId\":\"kb-1\"");
    }

    @Test
    void denyStays404WithoutExistenceLeak() throws Exception {
        // kb-2 未注册：DENY 与不存在同形
        HttpResponse<String> response = get("/api/ai/v1/knowledge-bases/kb-2", true);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("RESOURCE_NOT_FOUND_OR_FORBIDDEN");
    }

    @Test
    void staleAclVersionStays409() throws Exception {
        // epoch 前进（8）而主体 av=3：STALE，要求重取版本后重试
        when(epochMapper.findVersion(TENANT)).thenReturn(Optional.of(AV + 5));

        HttpResponse<String> response = get("/api/ai/v1/knowledge-bases/kb-1", true);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("POLICY_VERSION_STALE");
    }

    @Test
    void internalPathIs404ForExternalRequests() throws Exception {
        HttpResponse<String> response = get("/internal/ai/v1/knowledge-bases", false);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isEqualTo(AiInternalAccessBoundaryFilter.CLOSED_BODY);
    }

    @Test
    void whitelistOutsideStays404AndMissingLoginStays401() throws Exception {
        assertThat(get("/api/ai/v1/unknown-route", true).statusCode()).isEqualTo(404);
        assertThat(get("/api/ai/v1/knowledge-bases", false).statusCode()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ fixture

    @Configuration
    @EnableWebMvc
    @Import({AiGatewayController.class, AiWebEmbeddedConfiguration.class, AiEmbeddedRagConfiguration.class})
    static class FixtureConfig {

        static final AtomicReference<AiIdentityPort> identityPortOverride = new AtomicReference<>(null);

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
                            Set.of("ai:kb:list", "ai:kb:read"), PV);
                }
            };
        }

        @Bean
        OrganizationMatchController.SubjectMatchSource subjectMatchSource() {
            return new OrganizationMatchController.SubjectMatchSource() {
                @Override
                public Set<String> currentSubjects(String tenantId, String subject, String action) {
                    // 与 RuoYiPlatformIdentitySource 同一编码：member:<canonical membershipId>
                    return Set.of("member:" + MEMBER, "tenant_all:" + TENANT);
                }

                @Override
                public boolean withinDataScope(String tenantId, String subject, String action,
                                               String ownerMember, String ownerDept) {
                    return true;
                }

                @Override
                public boolean subjectExists(String tenant, String ref) {
                    return true;
                }

                @Override
                public Optional<SubjectOrgFacts> orgFacts(String tenantId, String subject) {
                    return Optional.empty();
                }
            };
        }

        @Bean
        AiIdentityPort aiIdentityPort() {
            return () -> {
                AiIdentityPort override = identityPortOverride.get();
                if (override != null) {
                    return override.currentFacts();
                }
                return Optional.of(new AiExecutionFacts(TENANT, USER, MEMBER, PV, AV,
                        Set.of("ai:kb:list", "ai:kb:read")));
            };
        }

        @Bean
        AiResourceMapper aiResourceMapper() {
            return resourceMapper;
        }

        @Bean
        AiResourceAclMapper aiResourceAclMapper() {
            return aclMapper;
        }

        @Bean
        AiAclEpochMapper aiAclEpochMapper() {
            return epochMapper;
        }

        @Bean
        ResourceSourceRefMapper resourceSourceRefMapper() {
            return sourceRefMapper;
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

        /** 同名覆盖装配内的 DefaultRevocationGuard：持久层用 mock，路由与授权链保持真实。 */
        @Bean
        RevocationGuard defaultRevocationGuard() {
            return revocations;
        }

        @Bean
        ProductionAuthorizationProvider productionAuthorizationProvider() {
            return platformPermits;
        }
    }
}
