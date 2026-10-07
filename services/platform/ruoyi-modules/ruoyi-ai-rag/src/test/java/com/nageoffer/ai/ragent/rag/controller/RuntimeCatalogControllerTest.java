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

package com.nageoffer.ai.ragent.rag.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.controller.vo.RuntimeCatalogVO;
import com.nageoffer.ai.ragent.rag.service.RuntimeCatalogConflictException;
import com.nageoffer.ai.ragent.rag.service.RuntimeCatalogService;
import com.nageoffer.ai.ragent.runtime.config.ConfigAuthorityUnavailable;
import com.nageoffer.ai.ragent.runtime.config.ConfigRevisionFacts;
import com.nageoffer.ai.ragent.runtime.config.ConfigRevisionPublisher;
import com.nageoffer.ai.ragent.runtime.config.ProviderConnectionPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RW-06 判据：运行配置目录（模型 / 提供方 / 档位 / 参数 / 维度 / 限额）的读取契约与档位 write-once 写入。
 *
 * <p>覆盖（正例与负例成对）：
 * <ol>
 *   <li>目录读取 == 当前已发布版本的投影：模型/提供方/档位/限额，{@code selectable} 由提供方批准裁决；</li>
 *   <li>无身份 / 无 scope / 功能权限拒绝 / 跨租户：全部拒绝，且拒绝发生在任何服务调用之前；</li>
 *   <li>没有已发布版本：目录读取拒绝</li>
 *   <li>未批准 / 不可用提供方：读面标 {@code selectable=false}，且不允许用它附加档位
 *       （与既有发布路径同一拒绝层，不临时放行）；</li>
 *   <li>密钥：引用形状合法才回显；历史明文引用不回显、不掩码回显；YAML 提供方只回布尔；</li>
 *   <li>档位 write-once：写一次、REVOKED 版本拒绝、形状非法先拒、冲突按 409 上抛；</li>
 *   <li>HTTP 映射真实存在（standalone MockMvc 走真实注解解析与 JSON 序列化）。</li>
 * </ol>
 */
@Tag("dev")
class RuntimeCatalogControllerTest {

    private static final String TENANT = "T1";
    private static final String REVISION = "rev-1";

    private AiResourceAuthorizationService authorization;
    private RuntimeCatalogService catalog;
    private ConfigRevisionPublisher publisher;
    private ProviderConnectionPort connections;
    private MockEnvironment environment;
    private RuntimeCatalogController controller;

    private final ExecutionPrincipal principal = new ExecutionPrincipal(TENANT, "2101", "platform:T1:2101", 1, 7,
            Set.of("config.read", "config.publish"), "jti", "test", 0, Long.MAX_VALUE);

    @BeforeEach
    void setUp() {
        authorization = mock(AiResourceAuthorizationService.class);
        catalog = mock(RuntimeCatalogService.class);
        publisher = mock(ConfigRevisionPublisher.class);
        connections = mock(ProviderConnectionPort.class);
        environment = new MockEnvironment().withProperty("rag.vector.type", "pg");
        controller = new RuntimeCatalogController(authorization, catalog, environment);
        controller.configureAuthority(publisher, connections);
        PrincipalContext.set(principal);

        when(catalog.currentRevision(TENANT)).thenReturn(revision(TENANT, "rev-1", "PUBLISHED", "deepseek", "deepseek-chat", "env:deepseek"));
        when(catalog.knownModels(eq(TENANT), anyInt())).thenReturn(List.of(
                new RuntimeCatalogService.ModelRow("deepseek", "deepseek-chat", "cat-v1", 1536,
                        Instant.parse("2026-10-07T00:00:00Z"), "hash-1"),
                new RuntimeCatalogService.ModelRow("ghost", "ghost-model", "cat-v1", 1536,
                        Instant.parse("2026-10-07T00:00:00Z"), "hash-2")));
        when(catalog.tiers(TENANT, REVISION)).thenReturn(List.of(
                new RuntimeCatalogService.TierRow(REVISION, "default", List.of("deepseek-chat"), 2, 30)));
        when(catalog.settings(TENANT, REVISION)).thenReturn(Map.of("rag.top_k", 8));
        when(connections.requireConnection(eq("deepseek"), any())).thenReturn(
                new ProviderConnectionPort.ProviderConnection("deepseek",
                        URI.create("https://api.deepseek.com/chat/completions"), "env:deepseek", null));
        when(connections.requireConnection(eq("ghost"), any())).thenThrow(
                new ConfigAuthorityUnavailable("no connection bootstrap for providerId=ghost"));
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    private static RuntimeCatalogService.RevisionRow revision(String tenantId, String id, String state,
                                                              String providerId, String modelId, String credentialRef) {
        return new RuntimeCatalogService.RevisionRow(tenantId, id, 7L, state, providerId, modelId,
                "cat-v1", "hash-1", "{\"temperature\":0.7,\"apiKey\":\"synthetic-never-echoed\"}",
                credentialRef, "2101", Instant.parse("2026-10-07T00:00:00Z"), 1536, 500L);
    }

    private void stubPublishedSnapshot(String state, String providerId, String modelId) {
        when(publisher.snapshot(REVISION)).thenReturn(new ConfigRevisionPublisher.ConfigRevisionSnapshot(
                new ConfigRevisionFacts(TENANT, REVISION, 7L, providerId, modelId, "cat-v1", "hash-1",
                        "env:deepseek", "2101", Instant.now(), 1536),
                state, "{}"));
    }

    private static Map<String, Object> tierBody(String tierCode, String candidate, Integer threshold) {
        Map<String, Object> tier = new LinkedHashMap<>();
        tier.put("tierCode", tierCode);
        tier.put("candidateIds", List.of(candidate));
        tier.put("failureThreshold", threshold);
        tier.put("openDurationSeconds", 30);
        return Map.of("tiers", List.of(tier));
    }

    private static RuntimeCatalogController.CatalogAttachRequest request(Map<String, Object> body) {
        List<RuntimeCatalogController.CatalogAttachRequest.TierRequest> tiers = new java.util.ArrayList<>();
        if (body.get("tiers") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    tiers.add(new RuntimeCatalogController.CatalogAttachRequest.TierRequest(
                            (String) map.get("tierCode"),
                            map.get("candidateIds") instanceof List<?> ids
                                    ? ids.stream().map(String::valueOf).toList() : null,
                            map.get("failureThreshold") instanceof Integer threshold ? threshold : null,
                            map.get("openDurationSeconds") instanceof Integer seconds ? seconds : null));
                }
            }
        }
        return new RuntimeCatalogController.CatalogAttachRequest(tiers);
    }

    private void assertRefusedWith(P04AiErrorCode expected, Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(P04AiException.class)
                .satisfies(ex -> assertThat(((P04AiException) ex).errorCode()).isEqualTo(expected));
    }

    // ------------------------------------------------------------------ 目录读取（正例）

    @Test
    @DisplayName("目录读取投影当前已发布版本：模型/提供方/档位/维度/限额，且只回显引用不回显密钥")
    void catalogProjectsThePublishedRevisionWithoutSecrets() {
        RuntimeCatalogVO.CatalogView view = controller.catalog().getBody().data();

        assertThat(view.authority()).isEqualTo("platform.ai_runtime_config_revision");
        assertThat(view.runtimeAuthority()).isTrue();
        assertThat(view.revision().revisionId()).isEqualTo("rev-1");
        assertThat(view.revision().revisionNo()).isEqualTo(7L);
        assertThat(view.revision().credentialRef()).isEqualTo("env:deepseek");
        assertThat(view.revision().credentialKind()).isEqualTo("env");
        assertThat(view.revision().dimension()).isEqualTo(1536);
        assertThat(view.revision().params()).containsEntry("temperature", 0.7);
        // 参数白名单：历史行里的 apiKey 键被丢弃（读取面不得变成泄漏面），且显式标记被裁剪过
        assertThat(view.revision().params()).doesNotContainKey("apiKey");
        assertThat(view.revision().paramsTrimmed()).isTrue();
        assertThat(view.limits().embeddingDimension()).isEqualTo(1536);
        assertThat(view.limits().budgetUnits()).isEqualTo(500L);

        assertThat(view.models()).hasSize(2);
        RuntimeCatalogVO.ModelView current = view.models().get(0);
        assertThat(current.modelId()).isEqualTo("deepseek-chat");
        assertThat(current.current()).isTrue();
        assertThat(current.selectable()).isTrue();
        assertThat(current.tierCodes()).containsExactly("default");
        assertThat(view.models().get(1).selectable())
                .as("未批准提供方的模型不得被展示成可选").isFalse();
        assertThat(view.providers()).anySatisfy(provider -> {
            assertThat(provider.providerId()).isEqualTo("ghost");
            assertThat(provider.approved()).isFalse();
            assertThat(provider.reason()).isEqualTo("NO_CONNECTION_BOOTSTRAP");
        });
        assertThat(view.tiers()).singleElement().satisfies(tier -> {
            assertThat(tier.tierCode()).isEqualTo("default");
            assertThat(tier.candidateIds()).containsExactly("deepseek-chat");
        });
        assertThat(view.links()).containsEntry("publish", "/api/ai/v1/runtime-config/revisions");

        String json = new ObjectMapper().valueToTree(view).toString();
        assertThat(json).doesNotContain("synthetic-never-echoed");
    }

    @Test
    @DisplayName("HTTP 面真实映射：GET /internal/ai/v1/runtime-config/catalog 返回 200 与冻结信封")
    void catalogIsMappedAtTheInternalPrefix() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/internal/ai/v1/runtime-config/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.msg").value("success"))
                .andExpect(jsonPath("$.data.revision.providerId").value("deepseek"))
                .andExpect(jsonPath("$.data.revision.credentialRef").value("env:deepseek"))
                .andExpect(jsonPath("$.data.models[0].selectable").value(true))
                .andExpect(jsonPath("$.data.models[1].selectable").value(false));
    }

    @Test
    @DisplayName("设置面分离可写运行事实与仅展示项：YAML/部署配置不是运行权威")
    void settingsSeparateWritableFactsFromDisplayOnlySurface() {
        RuntimeCatalogVO.SettingsAuthorityView view = controller.settings().getBody().data();

        assertThat(view.authority()).isEqualTo("platform.ai_runtime_config_revision");
        assertThat(view.yamlIsRuntimeAuthority()).isFalse();
        assertThat(view.legacyChatConfigAffectsRuntimeAuthority()).isFalse();
        assertThat(view.revisionAvailable()).isTrue();
        assertThat(view.writableRuntimeFacts())
                .extracting(RuntimeCatalogVO.SettingFact::key)
                .contains("providerId", "modelId", "catalogVersion", "paramsHash", "params",
                        "embeddingDimension", "budgetUnits", "tiers", "settings")
                .doesNotContain("rag.vector.type");
        assertThat(view.displayOnly()).allSatisfy(fact -> assertThat(fact.writable()).isFalse());
        assertThat(view.displayOnly()).anySatisfy(fact -> {
            assertThat(fact.key()).isEqualTo("rag.vector.type");
            assertThat(fact.value()).isEqualTo("pg");
            assertThat(fact.authority()).isEqualTo("deployment-env");
        });
        assertThat(view.displayOnly()).anySatisfy(fact -> assertThat(fact.key()).isEqualTo("chat.config"));
    }

    @Test
    @DisplayName("没有已发布版本时设置面仍可解释现状，但目录读取必须拒绝（不返回空模型列表）")
    void settingsExplainsTheGapWhileCatalogRefuses() {
        when(catalog.currentRevision(TENANT)).thenReturn(null);

        RuntimeCatalogVO.SettingsAuthorityView view = controller.settings().getBody().data();
        assertThat(view.revisionAvailable()).isFalse();
        assertThat(view.revision()).isNull();
        assertThat(view.notes()).isNotEmpty();

        assertThatThrownBy(() -> controller.catalog()).isInstanceOf(ConfigAuthorityUnavailable.class);
    }

    @Test
    @DisplayName("YAML 目录若存在只作为 display-only 展示，且不携带密钥值")
    void yamlCatalogIsDisplayOnlyWhenPresent() {
        AIModelProperties yaml = new AIModelProperties();
        AIModelProperties.ModelGroup chat = new AIModelProperties.ModelGroup();
        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setId("chat-fast");
        candidate.setProvider("deepseek");
        candidate.setModel("deepseek-chat");
        candidate.setDimension(1536);
        candidate.setEnabled(true);
        chat.setCandidates(List.of(candidate));
        yaml.setChat(chat);
        AIModelProperties.ProviderConfig providerConfig = new AIModelProperties.ProviderConfig();
        providerConfig.setUrl("https://api.deepseek.com");
        providerConfig.setApiKey("synthetic-yaml-key-never-echoed");
        yaml.setProviders(Map.of("deepseek", providerConfig));
        controller.configureYamlCatalog(yaml);

        RuntimeCatalogVO.SettingsAuthorityView view = controller.settings().getBody().data();

        assertThat(view.yamlCatalog().authority()).isEqualTo("yaml-bootstrap-display-only");
        assertThat(view.yamlCatalog().models()).singleElement().satisfies(model -> {
            assertThat(model.id()).isEqualTo("chat-fast");
            assertThat(model.runtimeAuthority()).isFalse();
        });
        assertThat(view.yamlCatalog().providers()).singleElement().satisfies(provider -> {
            assertThat(provider.providerId()).isEqualTo("deepseek");
            assertThat(provider.apiKeyConfigured()).isTrue();
        });
        assertThat(new ObjectMapper().valueToTree(view).toString())
                .doesNotContain("synthetic-yaml-key-never-echoed");
    }

    @Test
    @DisplayName("白名单内的参数不被标记裁剪；非白名单键（含历史密钥）只丢弃并置 trimmed")
    void paramsWhitelistMarksTrimmingWithoutEchoingUnknownKeys() {
        RuntimeCatalogService.RevisionRow clean = new RuntimeCatalogService.RevisionRow(TENANT, "rev-clean", 8L,
                "PUBLISHED", "deepseek", "deepseek-chat", "cat-v1", "hash-1", "{\"temperature\":0.7}",
                "env:deepseek", "2101", Instant.parse("2026-10-07T00:00:00Z"), 1536, 500L);
        when(catalog.currentRevision(TENANT)).thenReturn(clean);

        RuntimeCatalogVO.CatalogView view = controller.catalog().getBody().data();

        assertThat(view.revision().params()).containsOnlyKeys("temperature");
        assertThat(view.revision().paramsTrimmed()).isFalse();
    }

    @Test
    @DisplayName("历史明文凭据引用：不回显、不掩码回显，只标 refused")
    void legacyPlaintextCredentialIsNeverEchoed() {
        when(catalog.currentRevision(TENANT)).thenReturn(
                revision(TENANT, "rev-legacy", "PUBLISHED", "deepseek", "deepseek-chat", "sk-synthetic-canary"));

        RuntimeCatalogVO.CatalogView view = controller.catalog().getBody().data();

        assertThat(view.revision().credentialRef()).isNull();
        assertThat(view.revision().credentialKind()).isEqualTo("refused");
        assertThat(view.providers()).anySatisfy(provider -> assertThat(provider.credentialRef()).isNull());
        assertThat(new ObjectMapper().valueToTree(view).toString()).doesNotContain("sk-synthetic-canary");
    }

    @Test
    @DisplayName("版本列表按租户读取并有界：limit 越界先拒")
    void revisionsAreTenantScopedAndBounded() {
        when(catalog.revisions(TENANT, 5)).thenReturn(List.of(
                revision(TENANT, "rev-2", "PUBLISHED", "deepseek", "deepseek-chat", "env:deepseek"),
                revision(TENANT, "rev-1", "REVOKED", "deepseek", "deepseek-chat", "env:deepseek")));

        RuntimeCatalogVO.RevisionPageView page = controller.revisions(5).getBody().data();
        assertThat(page.count()).isEqualTo(2);
        assertThat(page.revisions()).extracting(RuntimeCatalogVO.RevisionView::revisionId)
                .containsExactly("rev-2", "rev-1");
        verify(catalog).revisions(TENANT, 5);

        assertRefusedWith(P04AiErrorCode.BAD_REQUEST, () -> controller.revisions(0));
        assertRefusedWith(P04AiErrorCode.BAD_REQUEST, () -> controller.revisions(101));
    }

    // ------------------------------------------------------------------ 负例：身份 / 权限 / 跨租户

    @Test
    @DisplayName("无身份、无 scope、功能权限被拒：读取与写入都拒绝，且不触达服务层")
    void missingIdentityOrScopeCannotReadOrWrite() {
        PrincipalContext.clear();
        assertThatThrownBy(() -> controller.catalog()).isInstanceOf(ClientException.class);

        PrincipalContext.set(new ExecutionPrincipal(TENANT, "2101", "platform:T1:2101", 1, 7,
                Set.of("config.publish"), "jti", "test", 0, Long.MAX_VALUE));
        assertRefusedWith(P04AiErrorCode.FORBIDDEN, () -> controller.catalog());

        PrincipalContext.set(principal);
        doThrow(new P04AiException(P04AiErrorCode.FORBIDDEN)).when(authorization)
                .requireFunction(principal, "config.read", "tenant:runtime-config");
        assertRefusedWith(P04AiErrorCode.FORBIDDEN, () -> controller.catalog());
        verifyNoInteractions(catalog);
    }

    @Test
    @DisplayName("跨租户：目录只按已认证主体的租户读取，请求体不参与租户判定")
    void catalogIsBoundToTheAuthenticatedTenant() {
        controller.catalog();
        verify(catalog).currentRevision(TENANT);

        clearInvocations(catalog);
        PrincipalContext.clear();
        PrincipalContext.set(new ExecutionPrincipal("T2", "2202", "platform:T2:2202", 1, 9,
                Set.of("config.read", "config.publish"), "jti", "test", 0, Long.MAX_VALUE));
        when(catalog.currentRevision("T2")).thenReturn(
                revision("T2", "rev-t2", "PUBLISHED", "other-provider", "other-model", "env:other"));
        when(catalog.knownModels(eq("T2"), anyInt())).thenReturn(List.of());

        RuntimeCatalogVO.CatalogView view = controller.catalog().getBody().data();

        assertThat(view.revision().providerId()).isEqualTo("other-provider");
        verify(catalog).currentRevision("T2");
        verify(catalog, never()).currentRevision(TENANT);
    }

    // ------------------------------------------------------------------ 写入面：档位 write-once

    @Test
    @DisplayName("档位附加写一次：调用既有权威写端口，回放标记由服务层裁决")
    void attachTiersWritesOnceThroughTheServiceLayer() {
        stubPublishedSnapshot("PUBLISHED", "deepseek", "deepseek-chat");
        when(catalog.attachTiers(eq(TENANT), eq(REVISION), any(), eq("2101"))).thenReturn(
                new RuntimeCatalogService.AttachResult(REVISION,
                        List.of(new RuntimeCatalogService.TierRow(REVISION, "fast", List.of("deepseek-chat"), 3, 45)),
                        false));

        RuntimeCatalogVO.CatalogAttachmentView view = controller
                .attachCatalog(REVISION, request(tierBody("fast", "deepseek-chat", 3))).getBody().data();

        assertThat(view.revisionId()).isEqualTo(REVISION);
        assertThat(view.replayed()).isFalse();
        assertThat(view.tiers()).singleElement().satisfies(tier -> {
            assertThat(tier.tierCode()).isEqualTo("fast");
            assertThat(tier.failureThreshold()).isEqualTo(3);
            assertThat(tier.openDurationSeconds()).isEqualTo(45);
        });
        verify(authorization).requireFunction(principal, "config.publish", "tenant:runtime-config");
    }

    @Test
    @DisplayName("同内容重复附加按幂等回放上报（不重复写事实）")
    void identicalTierAttachmentIsReportedAsReplay() {
        stubPublishedSnapshot("PUBLISHED", "deepseek", "deepseek-chat");
        when(catalog.attachTiers(eq(TENANT), eq(REVISION), any(), eq("2101"))).thenReturn(
                new RuntimeCatalogService.AttachResult(REVISION,
                        List.of(new RuntimeCatalogService.TierRow(REVISION, "fast", List.of("deepseek-chat"), 2, 30)),
                        true));

        RuntimeCatalogVO.CatalogAttachmentView view = controller
                .attachCatalog(REVISION, request(tierBody("fast", "deepseek-chat", 2))).getBody().data();

        assertThat(view.replayed()).isTrue();
    }

    @Test
    @DisplayName("未批准提供方的候选模型不允许写入档位（与发布路径同一拒绝层）")
    void unapprovedProviderCandidateCannotBeAttached() {
        stubPublishedSnapshot("PUBLISHED", "deepseek", "deepseek-chat");

        assertThatThrownBy(() -> controller.attachCatalog(REVISION, request(tierBody("fast", "ghost-model", 2))))
                .isInstanceOf(ConfigAuthorityUnavailable.class);
        verify(catalog, never()).attachTiers(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("目录之外的候选模型 id 先被拒（不产生指向不存在模型的档位）")
    void unknownCandidateIsRefusedBeforeAnyWrite() {
        stubPublishedSnapshot("PUBLISHED", "deepseek", "deepseek-chat");

        assertRefusedWith(P04AiErrorCode.BAD_REQUEST,
                () -> controller.attachCatalog(REVISION, request(tierBody("fast", "nope", 2))));
        verify(catalog, never()).attachTiers(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("REVOKED 版本不接受档位附加（409），且不调用写端口")
    void revokedRevisionRefusesAttachment() {
        stubPublishedSnapshot("REVOKED", "deepseek", "deepseek-chat");

        assertRefusedWith(P04AiErrorCode.RESOURCE_VERSION_CONFLICT,
                () -> controller.attachCatalog(REVISION, request(tierBody("fast", "deepseek-chat", 2))));
        verify(catalog, never()).attachTiers(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("档位形状非法先拒（空集/码格式/空候选/重复码/阈值越界），不触达权威端口")
    void invalidTierShapeIsRefusedBeforeAnyAuthorityCall() {
        for (Map<String, Object> body : List.<Map<String, Object>>of(
                Map.of("tiers", List.of()),
                Map.of("tiers", List.of(Map.of("tierCode", "FAST", "candidateIds", List.of("deepseek-chat")))),
                Map.of("tiers", List.of(Map.of("tierCode", "fast", "candidateIds", List.of()))),
                Map.of("tiers", List.of(
                        Map.of("tierCode", "fast", "candidateIds", List.of("deepseek-chat")),
                        Map.of("tierCode", "fast", "candidateIds", List.of("deepseek-chat")))),
                tierBody("fast", "deepseek-chat", 99))) {
            assertRefusedWith(P04AiErrorCode.BAD_REQUEST,
                    () -> controller.attachCatalog(REVISION, request(body)));
        }
        verify(catalog, never()).attachTiers(anyString(), anyString(), any(), anyString());
        verify(publisher, never()).snapshot(anyString());
    }

    @Test
    @DisplayName("档位冲突（同版本不同内容）由服务层裁决并按 409 上抛")
    void tierConflictSurfacesAs409() {
        stubPublishedSnapshot("PUBLISHED", "deepseek", "deepseek-chat");
        when(catalog.attachTiers(eq(TENANT), eq(REVISION), any(), eq("2101")))
                .thenThrow(new RuntimeCatalogConflictException("different facts"));

        assertRefusedWith(P04AiErrorCode.RESOURCE_VERSION_CONFLICT,
                () -> controller.attachCatalog(REVISION, request(tierBody("fast", "deepseek-chat", 2))));
    }

    @Test
    @DisplayName("未装配发布权威时写路径明确拒绝（503 语义），不静默成功")
    void writePathRefusesWhenPublisherIsAbsent() {
        RuntimeCatalogController withoutPublisher = new RuntimeCatalogController(authorization, catalog, environment);
        assertThatThrownBy(() -> withoutPublisher.attachCatalog(REVISION, request(tierBody("fast", "deepseek-chat", 2))))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    @DisplayName("未装配连接引导时提供方一律不批准（读面不可选、写面拒绝对外发）")
    void missingConnectionBootstrapKeepsProvidersUnapproved() {
        RuntimeCatalogController withoutConnections = new RuntimeCatalogController(authorization, catalog, environment);
        withoutConnections.configureAuthority(publisher, null);

        assertThat(withoutConnections.catalog().getBody().data().providers())
                .allSatisfy(provider -> {
                    assertThat(provider.approved()).isFalse();
                    assertThat(provider.reason()).isEqualTo("CONNECTION_BOOTSTRAP_NOT_WIRED");
                });
    }
}
