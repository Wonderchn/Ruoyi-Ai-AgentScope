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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.controller.vo.RuntimeCatalogVO;
import com.nageoffer.ai.ragent.rag.service.RuntimeCatalogConflictException;
import com.nageoffer.ai.ragent.rag.service.RuntimeCatalogService;
import com.nageoffer.ai.ragent.runtime.config.ConfigAuthorityUnavailable;
import com.nageoffer.ai.ragent.runtime.config.ConfigRevisionPublisher;
import com.nageoffer.ai.ragent.runtime.config.ProviderConnectionPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * RW-06 运行配置目录端点：{@code /internal/ai/v1/runtime-config/**}（内层前缀，公开面经网关白名单）。
 *
 * <p><b>为什么需要它。</b>旧前端（workbench 模型选择）调用 {@code /system/model/modelList}，
 * 而该路径属于已退场模块 ruoyi-chat —— 本形态必然 404；admin 的模型/提供方页同样没有后端。
 * 同时 V15 已把"用户模型选择"的事实收敛成**不可变的已发布配置版本**，V24 又给这张表补了
 * 档位/限额附属行，但这些行在源码里<b>没有任何管理面读写点</b>：只能手写 SQL。
 * 本控制器补上"读目录 + 写档位事实"这一段，且**不新增任何发布/撤销实现**。
 *
 * <p><b>不是第二套权威（本卡最容易被做错的地方）。</b>
 * <ul>
 *   <li>发布 / 读取单版本 / 撤销 / 追加版本回滚：**复用**既有
 *       {@code AiResourceController} 的四条路由（{@code POST /runtime-config/revisions}、
 *       {@code GET /runtime-config/revisions/{revisionId}}、
 *       {@code POST .../revoke}、{@code POST .../rollback}），本类不复制发布事务，
 *       也不复制参数/凭据校验；</li>
 *   <li>本类只读 V15 权威表 + V24 附属行，写也只写**已存在版本的档位行**（write-once）；</li>
 *   <li>YAML 目录（{@code AIModelProperties}）如存在，只作为 {@code display-only} 展示，
 *       逐项标注 {@code runtimeAuthority=false}，绝不参与"当前模型"的判定。</li>
 * </ul>
 *
 * <p><b>密钥口径。</b>凭据只以引用（{@code env:...}）呈现；引用形状不合法（历史明文行）时
 * <b>不回显该值</b>，只回 {@code credentialKind=refused}。YAML 提供方只回
 * {@code apiKeyConfigured} 布尔量。本类不读、不打印任何密钥值。
 *
 * <p><b>失败语义（fail-closed，HTTP status == body.code）。</b>
 * <ul>
 *   <li>无主体 / 无租户上下文：403（{@code TENANT_CONTEXT_MISSING}，沿用
 *       {@code PrincipalContext.require()} 的统一拒绝）；</li>
 *   <li>scope 或功能权限不足：403（{@code FORBIDDEN}）；</li>
 *   <li>该租户没有已发布权威版本：503 {@code CONFIG_AUTHORITY_UNAVAILABLE} ——
 *       **不返回空模型列表、不回退 YAML、不返回"默认模型"**（C1.1）；</li>
 *   <li>提供方未获批准 / 连接引导缺失：503 {@code CONFIG_AUTHORITY_UNAVAILABLE}
 *       ——与既有发布路径同一拒绝层，不临时放行；</li>
 *   <li>对同一不可变版本附加不同档位：409 {@code RESOURCE_VERSION_CONFLICT}
 *       （改档位必须发布新版本）。</li>
 * </ul>
 *
 * <p>动作复用（零新增 canonical 动作、零新增权限行、零迁移）：读取用 {@code config.read}，
 * 档位附加用 {@code config.publish}（与发布/回滚同一动作）。
 */
@RestController
@RequestMapping("/internal/ai/v1/runtime-config")
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class RuntimeCatalogController {

    /** 读取动作：与既有运行配置读取同一动作（{@code ai:config:read}）。 */
    public static final String ACTION_READ = "config.read";

    /** 档位附加动作：与发布/回滚同一动作（{@code ai:config:publish}）。 */
    public static final String ACTION_PUBLISH = "config.publish";

    private static final String CONFIG_REF = "tenant:runtime-config";

    private static final int MAX_MODEL_ROWS = 200;
    private static final int MAX_TIERS = 8;
    private static final int MAX_CANDIDATES = 16;
    private static final int DEFAULT_FAILURE_THRESHOLD = 2;
    private static final int DEFAULT_OPEN_DURATION_SECONDS = 30;

    private static final Pattern TIER_CODE = Pattern.compile("[a-z][a-z0-9._-]{0,31}");

    /** 凭据**引用**形状（与既有发布路径逐字一致）；不匹配者一律不回显。 */
    private static final Pattern CREDENTIAL_REF = Pattern.compile("(env|vault|secret|masked):[A-Za-z][A-Za-z0-9._/-]{0,190}");

    /** 可回显的生成参数白名单（与既有发布路径的写入校验同集）。 */
    private static final Set<String> PARAM_WHITELIST = Set.of(
            "temperature", "maxTokens", "topP", "presencePenalty", "frequencyPenalty", "seed");

    /**
     * 仅展示（非运行权威）的部署配置键白名单。
     *
     * <p>刻意用白名单而不是"遍历所有属性"：后者会把数据源口令、MQ 凭据、
     * 委托私钥路径一并读出来。白名单里全是类型/开关/尺寸，天然不含密钥。
     */
    private static final List<String> DISPLAY_ONLY_KEYS = List.of(
            "rag.vector.type",
            "rag.keyword.type",
            "rag.storage.type",
            "rag.graph.type",
            "ragent.engine.mode",
            "spring.servlet.multipart.max-file-size",
            "spring.servlet.multipart.max-request-size");

    private final AiResourceAuthorizationService authorization;
    private final RuntimeCatalogService catalog;
    private final Environment environment;
    private final ObjectMapper mapper = new ObjectMapper();

    private ConfigRevisionPublisher configPublisher;
    private ProviderConnectionPort configConnections;
    private AIModelProperties yamlCatalog;

    public RuntimeCatalogController(AiResourceAuthorizationService authorization,
                                    RuntimeCatalogService catalog,
                                    Environment environment) {
        this.authorization = authorization;
        this.catalog = catalog;
        this.environment = environment;
    }

    /**
     * 发布权威与连接引导。用 {@code required=false} + 使用点 fail-closed：权威缺席时
     * <b>拒绝写路径</b>而不是启动期拦下整个应用（读面/会话/审计在权威缺失时仍应可用），
     * 与 {@code RunAdmissionService} 的同族口径一致。
     */
    @Autowired(required = false)
    public void configureAuthority(ConfigRevisionPublisher publisher, ProviderConnectionPort connections) {
        this.configPublisher = publisher;
        this.configConnections = connections;
    }

    /** YAML 目录仅用于展示；缺席是常态（{@code ai.model.enabled} 未开）。 */
    @Autowired(required = false)
    public void configureYamlCatalog(AIModelProperties yamlCatalog) {
        this.yamlCatalog = yamlCatalog;
    }

    // ------------------------------------------------------------------ 读取面

    /**
     * 用户模型选择的最小读取契约（替代旧 {@code /system/model/modelList}）。
     *
     * <p>没有已发布权威版本即拒绝（503），不返回空目录：模型选择是"选择一个会被写进 run 的事实"，
     * 允许返回空列表会让前端把"配置未发布"渲染成"没有模型"，而真实原因完全不同。
     */
    @GetMapping("/catalog")
    public ResponseEntity<ApiEnvelope<RuntimeCatalogVO.CatalogView>> catalog() {
        ExecutionPrincipal principal = requireAction(ACTION_READ);
        String tenantId = principal.tenantId();
        RuntimeCatalogService.RevisionRow revision = catalog.currentRevision(tenantId);
        if (revision == null) {
            throw new ConfigAuthorityUnavailable(
                    "no published runtime config revision for tenant; refusing to serve a catalog");
        }
        List<RuntimeCatalogService.ModelRow> modelRows = catalog.knownModels(tenantId, MAX_MODEL_ROWS);
        List<RuntimeCatalogService.TierRow> tierRows = catalog.tiers(tenantId, revision.revisionId());

        List<RuntimeCatalogVO.ProviderView> providers = providers(revision, modelRows);
        Map<String, RuntimeCatalogVO.ProviderView> byProvider = new LinkedHashMap<>();
        for (RuntimeCatalogVO.ProviderView provider : providers) {
            byProvider.put(provider.providerId(), provider);
        }
        Map<String, List<String>> tiersByCandidate = tiersByCandidate(tierRows);

        List<RuntimeCatalogVO.ModelView> models = new ArrayList<>();
        for (RuntimeCatalogService.ModelRow row : modelRows) {
            RuntimeCatalogVO.ProviderView provider = byProvider.get(row.providerId());
            boolean selectable = provider != null && provider.approved() && provider.available();
            boolean current = row.providerId().equals(revision.providerId())
                    && row.modelId().equals(revision.modelId());
            models.add(new RuntimeCatalogVO.ModelView(
                    row.modelId(), row.providerId(), row.modelId(), row.catalogVersion(), row.dimension(),
                    current, selectable,
                    tiersByCandidate.getOrDefault(row.modelId(), List.of()),
                    instant(row.publishedAt()), row.paramsHash()));
        }

        List<String> notes = new ArrayList<>();
        if (models.isEmpty()) {
            notes.add("catalog is empty: the tenant has a published revision but no model rows are readable");
        }
        if (providers.stream().anyMatch(provider -> !provider.approved())) {
            notes.add("unapproved providers are not selectable; publish/attach requires a connection bootstrap");
        }

        RuntimeCatalogVO.CatalogView view = new RuntimeCatalogVO.CatalogView(
                RuntimeCatalogService.AUTHORITY, true, "catalog projects the published runtime revision",
                revisionView(revision), models, providers, tiers(tierRows),
                new RuntimeCatalogVO.LimitsView(revision.dimension(), revision.budgetUnits(), maxTokens(revision)),
                links(), notes);
        return noStore(ApiEnvelope.ok(view));
    }

    /**
     * 设置面权威分布：哪些是可写运行事实、哪些仅展示。
     *
     * <p>与 {@code /catalog} 的差别是刻意的：本视图是**说明**，因此没有已发布版本也要能返回
     * （{@code revisionAvailable=false}），否则管理页无法解释"为什么现在不能选模型"。
     */
    @GetMapping("/settings")
    public ResponseEntity<ApiEnvelope<RuntimeCatalogVO.SettingsAuthorityView>> settings() {
        ExecutionPrincipal principal = requireAction(ACTION_READ);
        String tenantId = principal.tenantId();
        RuntimeCatalogService.RevisionRow revision = catalog.currentRevision(tenantId);
        boolean available = revision != null;

        List<RuntimeCatalogVO.SettingFact> writable = new ArrayList<>();
        writable.add(fact("providerId", revision == null ? null : revision.providerId()));
        writable.add(fact("modelId", revision == null ? null : revision.modelId()));
        writable.add(fact("catalogVersion", revision == null ? null : revision.catalogVersion()));
        writable.add(fact("paramsHash", revision == null ? null : revision.paramsHash()));
        writable.add(fact("params", revision == null ? null : params(revision)));
        writable.add(new RuntimeCatalogVO.SettingFact("embeddingDimension", "published-revision", true,
                revision == null ? null : revision.dimension(),
                "必须等于统一向量列物理维度 1536；不等于即拒绝发布/受理"));
        writable.add(new RuntimeCatalogVO.SettingFact("budgetUnits", "published-revision", true,
                revision == null ? null : revision.budgetUnits(),
                "运行限额随版本固定；改限额 = 发布新版本"));
        writable.add(new RuntimeCatalogVO.SettingFact("tiers", "tier", true,
                revision == null ? List.of() : tiers(catalog.tiers(tenantId, revision.revisionId())),
                "档位随版本固定；对同一版本重复附加不同档位返回 409"));
        writable.add(new RuntimeCatalogVO.SettingFact("settings", "setting", true,
                revision == null ? Map.of() : catalog.settings(tenantId, revision.revisionId()),
                "V24 附属设置行；禁写密钥，改设置 = 发布新版本"));

        List<RuntimeCatalogVO.SettingFact> displayOnly = new ArrayList<>();
        for (String key : DISPLAY_ONLY_KEYS) {
            String value = environment == null ? null : environment.getProperty(key);
            displayOnly.add(new RuntimeCatalogVO.SettingFact(key, "deployment-env", false, value,
                    "部署配置/环境注入；**不是**运行权威，改了不影响已发布版本与在飞 run"));
        }
        displayOnly.add(new RuntimeCatalogVO.SettingFact("chat.config", "legacy-not-runtime-authority", false, null,
                "旧 /chat/config 的操作不影响运行权威；不得据此推断当前模型"));

        List<String> notes = new ArrayList<>();
        if (!available) {
            notes.add("no published revision for this tenant: model selection refuses until a revision is published");
        }
        if (yamlCatalog != null) {
            notes.add("yaml catalog is display-only; it is never the runtime authority");
        }

        RuntimeCatalogVO.SettingsAuthorityView view = new RuntimeCatalogVO.SettingsAuthorityView(
                RuntimeCatalogService.AUTHORITY, false, false, available,
                revision == null ? null : revisionView(revision),
                writable, displayOnly, yamlCatalog(), links(), notes);
        return noStore(ApiEnvelope.ok(view));
    }

    /** 版本序列（只读历史）。发布版本不可删除，因此列表就是审计面的版本轴。 */
    @GetMapping("/revisions")
    public ResponseEntity<ApiEnvelope<RuntimeCatalogVO.RevisionPageView>> revisions(
            @RequestParam(defaultValue = "20") int limit) {
        ExecutionPrincipal principal = requireAction(ACTION_READ);
        if (limit < 1 || limit > 100) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST, "limit must be within 1..100");
        }
        List<RuntimeCatalogVO.RevisionView> views = catalog.revisions(principal.tenantId(), limit).stream()
                .map(this::revisionView)
                .toList();
        return noStore(ApiEnvelope.ok(new RuntimeCatalogVO.RevisionPageView(views.size(), views)));
    }

    // ------------------------------------------------------------------ 写入面（只写已发布版本的档位事实）

    /**
     * 把档位事实附加到**已发布且不可变**的版本上。
     *
     * <p>这是"档位 CRUD"里唯一被允许的写形态：创建=对某版本首次附加，更新=发布新版本后再附加，
     * 删除=撤销该版本（{@code .../revoke}，既有路由）。对同一版本写入不同内容返回 409。
     * 每个候选模型必须来自该租户已发布目录，且其提供方必须已获批准（连接引导存在）——
     * 未批准 provider 在这里就被拒绝，不会产生"发布成功但跑不起来"的版本。
     */
    @PostMapping("/revisions/{revisionId}/catalog")
    public ResponseEntity<ApiEnvelope<RuntimeCatalogVO.CatalogAttachmentView>> attachCatalog(
            @PathVariable String revisionId,
            @RequestBody(required = false) CatalogAttachRequest request) {
        ExecutionPrincipal principal = requireAction(ACTION_PUBLISH);
        ConfigRevisionPublisher publisher = requirePublisher();
        List<RuntimeCatalogService.TierSpec> tiers = tierSpecs(request);

        // 版本必须真实存在且属于本租户（publisher 自身按 PrincipalContext 的租户读取，
        // 跨租户的 revisionId 在这里读不到 ⇒ 绝不落任何行）。
        ConfigRevisionPublisher.ConfigRevisionSnapshot snapshot = publisher.snapshot(revisionId);
        if (!"PUBLISHED".equalsIgnoreCase(snapshot.state())) {
            throw new P04AiException(P04AiErrorCode.RESOURCE_VERSION_CONFLICT,
                    "revision " + revisionId + " is not PUBLISHED");
        }

        Map<String, String> providerOfModel = new LinkedHashMap<>();
        for (RuntimeCatalogService.ModelRow row : catalog.knownModels(principal.tenantId(), MAX_MODEL_ROWS)) {
            providerOfModel.putIfAbsent(row.modelId(), row.providerId());
        }
        for (RuntimeCatalogService.TierSpec tier : tiers) {
            for (String candidateId : tier.candidateIds()) {
                String providerId = providerOfModel.get(candidateId);
                if (providerId == null) {
                    throw new P04AiException(P04AiErrorCode.BAD_REQUEST,
                            "candidate model is not part of this tenant's published catalog: " + candidateId);
                }
                requireApprovedProvider(providerId);
            }
        }

        RuntimeCatalogService.AttachResult result;
        try {
            result = catalog.attachTiers(principal.tenantId(), revisionId, tiers, principal.userId());
        } catch (RuntimeCatalogConflictException conflict) {
            throw new P04AiException(P04AiErrorCode.RESOURCE_VERSION_CONFLICT, conflict.getMessage());
        }
        RuntimeCatalogVO.CatalogAttachmentView view = new RuntimeCatalogVO.CatalogAttachmentView(
                revisionId, tiers(result.tiers()), result.replayed(),
                result.replayed()
                        ? "identical tier facts already attached to this immutable revision"
                        : "tier facts attached to the immutable revision");
        return noStore(ApiEnvelope.ok(view));
    }

    public record CatalogAttachRequest(List<TierRequest> tiers) {

        public record TierRequest(String tierCode, List<String> candidateIds,
                                  Integer failureThreshold, Integer openDurationSeconds) {
        }
    }

    // ------------------------------------------------------------------ 内部

    private ExecutionPrincipal requireAction(String action) {
        ExecutionPrincipal principal = PrincipalContext.require();
        if (!principal.hasScope(action)) {
            throw new P04AiException(P04AiErrorCode.FORBIDDEN);
        }
        if (authorization == null) {
            throw new ServiceException("platform authorization is unavailable");
        }
        authorization.requireFunction(principal, action, CONFIG_REF);
        return principal;
    }

    private ConfigRevisionPublisher requirePublisher() {
        ConfigRevisionPublisher publisher = this.configPublisher;
        if (publisher == null) {
            throw new ServiceException("runtime config publisher unavailable");
        }
        return publisher;
    }

    /** 未批准 / 不可用提供方一律拒绝；异常类型与既有发布路径同一层（503，符号码可归因）。 */
    private void requireApprovedProvider(String providerId) {
        ProviderConnectionPort port = this.configConnections;
        if (port == null) {
            throw new ConfigAuthorityUnavailable("provider connection bootstrap is not wired");
        }
        port.requireConnection(providerId, null);
    }

    private List<RuntimeCatalogVO.ProviderView> providers(RuntimeCatalogService.RevisionRow revision,
                                                          List<RuntimeCatalogService.ModelRow> models) {
        Set<String> providerIds = new LinkedHashSet<>();
        providerIds.add(revision.providerId());
        for (RuntimeCatalogService.ModelRow row : models) {
            providerIds.add(row.providerId());
        }
        List<RuntimeCatalogVO.ProviderView> out = new ArrayList<>();
        ProviderConnectionPort port = this.configConnections;
        for (String providerId : providerIds) {
            if (providerId == null) {
                continue;
            }
            if (port == null) {
                out.add(new RuntimeCatalogVO.ProviderView(providerId, false, false, false, null,
                        "CONNECTION_BOOTSTRAP_NOT_WIRED"));
                continue;
            }
            String declaredRef = providerId.equals(revision.providerId()) ? credentialRef(revision.credentialRef()) : null;
            try {
                ProviderConnectionPort.ProviderConnection connection = port.requireConnection(providerId, declaredRef);
                boolean endpointConfigured = connection != null && connection.endpoint() != null;
                String ref = connection == null ? declaredRef : credentialRef(connection.credentialRef());
                out.add(new RuntimeCatalogVO.ProviderView(providerId, true, endpointConfigured,
                        endpointConfigured, ref == null ? declaredRef : ref, null));
            } catch (ConfigAuthorityUnavailable unavailable) {
                out.add(new RuntimeCatalogVO.ProviderView(providerId, false, false, false, declaredRef,
                        "NO_CONNECTION_BOOTSTRAP"));
            }
        }
        return out;
    }

    private List<RuntimeCatalogService.TierSpec> tierSpecs(CatalogAttachRequest request) {
        if (request == null || request.tiers() == null || request.tiers().isEmpty()) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST, "tiers are required");
        }
        if (request.tiers().size() > MAX_TIERS) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST, "too many tiers");
        }
        Set<String> seen = new LinkedHashSet<>();
        List<RuntimeCatalogService.TierSpec> out = new ArrayList<>();
        for (CatalogAttachRequest.TierRequest tier : request.tiers()) {
            if (tier == null || tier.tierCode() == null || !TIER_CODE.matcher(tier.tierCode().trim()).matches()) {
                throw new P04AiException(P04AiErrorCode.BAD_REQUEST, "tierCode must match [a-z][a-z0-9._-]{0,31}");
            }
            String tierCode = tier.tierCode().trim();
            if (!seen.add(tierCode)) {
                throw new P04AiException(P04AiErrorCode.BAD_REQUEST, "duplicate tierCode: " + tierCode);
            }
            if (tier.candidateIds() == null || tier.candidateIds().isEmpty()
                    || tier.candidateIds().size() > MAX_CANDIDATES) {
                throw new P04AiException(P04AiErrorCode.BAD_REQUEST, "candidateIds must be within 1.." + MAX_CANDIDATES);
            }
            LinkedHashSet<String> candidates = new LinkedHashSet<>();
            for (String candidate : tier.candidateIds()) {
                if (candidate == null || candidate.isBlank()) {
                    throw new P04AiException(P04AiErrorCode.BAD_REQUEST, "candidateIds must not contain blanks");
                }
                candidates.add(candidate.trim());
            }
            int threshold = tier.failureThreshold() == null ? DEFAULT_FAILURE_THRESHOLD : tier.failureThreshold();
            int openSeconds = tier.openDurationSeconds() == null
                    ? DEFAULT_OPEN_DURATION_SECONDS : tier.openDurationSeconds();
            if (threshold < 1 || threshold > 10 || openSeconds < 1 || openSeconds > 600) {
                throw new P04AiException(P04AiErrorCode.BAD_REQUEST,
                        "failureThreshold must be 1..10 and openDurationSeconds must be 1..600");
            }
            out.add(new RuntimeCatalogService.TierSpec(tierCode, List.copyOf(candidates), threshold, openSeconds));
        }
        return out;
    }

    private RuntimeCatalogVO.RevisionView revisionView(RuntimeCatalogService.RevisionRow row) {
        Map<String, Object> parsed = parseJsonObject(row.paramsJson());
        Map<String, Object> params = new LinkedHashMap<>();
        boolean trimmed = false;
        for (Map.Entry<String, Object> entry : parsed.entrySet()) {
            if (PARAM_WHITELIST.contains(entry.getKey())) {
                params.put(entry.getKey(), entry.getValue());
            } else {
                // 历史行里的未知键既不回显也不静默吞掉：只置 trimmed 让调用方知道"这里被裁过"。
                trimmed = true;
            }
        }
        return new RuntimeCatalogVO.RevisionView(
                row.revisionId(), row.revisionNo(), row.state(), row.providerId(), row.modelId(),
                row.catalogVersion(), row.paramsHash(), params, trimmed,
                credentialRef(row.credentialRef()), credentialKind(row.credentialRef()),
                row.dimension(), row.budgetUnits(), row.operatorId(), instant(row.publishedAt()));
    }

    private List<RuntimeCatalogVO.TierView> tiers(List<RuntimeCatalogService.TierRow> rows) {
        List<RuntimeCatalogVO.TierView> out = new ArrayList<>(rows.size());
        for (RuntimeCatalogService.TierRow row : rows) {
            out.add(new RuntimeCatalogVO.TierView(row.tierCode(), row.candidateIds(),
                    row.failureThreshold(), row.openDurationSeconds()));
        }
        return out;
    }

    private Map<String, List<String>> tiersByCandidate(List<RuntimeCatalogService.TierRow> rows) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (RuntimeCatalogService.TierRow row : rows) {
            for (String candidate : row.candidateIds()) {
                out.computeIfAbsent(candidate, key -> new ArrayList<>()).add(row.tierCode());
            }
        }
        return out;
    }

    private RuntimeCatalogVO.YamlCatalogView yamlCatalog() {
        AIModelProperties properties = this.yamlCatalog;
        if (properties == null) {
            return null;
        }
        List<RuntimeCatalogVO.YamlModel> models = new ArrayList<>();
        List<RuntimeCatalogVO.YamlProvider> providers = new ArrayList<>();
        collectYaml(models, "chat", properties.getChat());
        collectYaml(models, "embedding", properties.getEmbedding());
        collectYaml(models, "rerank", properties.getRerank());
        collectYaml(models, "vlm", properties.getVlm());
        if (properties.getProviders() != null) {
            properties.getProviders().forEach((key, value) -> providers.add(new RuntimeCatalogVO.YamlProvider(
                    key,
                    value != null && value.getApiKey() != null && !value.getApiKey().isBlank(),
                    value != null && value.getUrl() != null && !value.getUrl().isBlank())));
        }
        return new RuntimeCatalogVO.YamlCatalogView("yaml-bootstrap-display-only",
                "YAML model directory is display-only: it never decides the model bound to a run; "
                        + "the published revision does",
                models, providers);
    }

    private void collectYaml(List<RuntimeCatalogVO.YamlModel> out, String group,
                             AIModelProperties.ModelGroup modelGroup) {
        if (modelGroup == null || modelGroup.getCandidates() == null) {
            return;
        }
        for (AIModelProperties.ModelCandidate candidate : modelGroup.getCandidates()) {
            if (candidate == null) {
                continue;
            }
            out.add(new RuntimeCatalogVO.YamlModel(candidate.getId(), group, candidate.getProvider(),
                    candidate.getModel(), candidate.getDimension(),
                    Boolean.TRUE.equals(candidate.getEnabled()), false));
        }
    }

    /** 只回显白名单内的生成参数；历史行里的未知键**丢弃**（避免把审计面变成泄漏面）。 */
    private Map<String, Object> params(RuntimeCatalogService.RevisionRow row) {
        Map<String, Object> parsed = parseJsonObject(row.paramsJson());
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : parsed.entrySet()) {
            if (PARAM_WHITELIST.contains(entry.getKey())) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    private Integer maxTokens(RuntimeCatalogService.RevisionRow row) {
        Object value = params(row).get("maxTokens");
        return value instanceof Number number ? number.intValue() : null;
    }

    private Map<String, Object> parseJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = mapper.readValue(json, new TypeReference<Map<String, Object>>() { });
            return parsed == null ? Map.of() : parsed;
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    /**
     * 凭据引用：形状合法才回显。历史行里的明文密钥（如 {@code sk-...}）**不匹配**该形状，
     * 因此这里返回 {@code null} —— 读取面永不回显密钥，也不做"掩码回显"（掩码仍泄露长度与前后缀）。
     */
    private static String credentialRef(String ref) {
        return ref != null && CREDENTIAL_REF.matcher(ref.trim()).matches() ? ref.trim() : null;
    }

    private static String credentialKind(String ref) {
        if (ref == null || ref.isBlank()) {
            return "none";
        }
        String trimmed = ref.trim();
        if (!CREDENTIAL_REF.matcher(trimmed).matches()) {
            // 历史明文行：既不回显也不掩码回显，只标注"该引用不可呈现"。
            return "refused";
        }
        return trimmed.substring(0, trimmed.indexOf(':'));
    }

    private static RuntimeCatalogVO.SettingFact fact(String key, Object value) {
        return new RuntimeCatalogVO.SettingFact(key, "published-revision", true, value,
                "writable runtime fact: change requires publishing a new revision");
    }

    private static Map<String, String> links() {
        return Map.of(
                "publish", "/api/ai/v1/runtime-config/revisions",
                "read", "/api/ai/v1/runtime-config/revisions/{revisionId}",
                "revoke", "/api/ai/v1/runtime-config/revisions/{revisionId}/revoke",
                "rollback", "/api/ai/v1/runtime-config/revisions/{revisionId}/rollback",
                "catalog", "/api/ai/v1/runtime-config/catalog",
                "settings", "/api/ai/v1/runtime-config/settings",
                "revisions", "/api/ai/v1/runtime-config/revisions");
    }

    private static String instant(java.time.Instant value) {
        return value == null ? null : DateTimeFormatter.ISO_INSTANT.format(value);
    }

    private static <T> ResponseEntity<ApiEnvelope<T>> noStore(ApiEnvelope<T> body) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }
}
