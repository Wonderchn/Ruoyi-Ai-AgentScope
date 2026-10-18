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

import com.nageoffer.ai.ragent.agent.config.AgentModelFactory;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.rag.service.RuntimeCatalogService;
import com.nageoffer.ai.ragent.runtime.config.ConfigAuthorityUnavailable;
import com.nageoffer.ai.ragent.runtime.config.ProviderConnectionPort;
import com.nageoffer.ai.ragent.runtime.usage.EgressPolicy;
import com.nageoffer.ai.ragent.runtime.usage.ProviderSpendEnvelope;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.transport.JdkHttpTransport;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * F09:3-C：内嵌形态的绑定模型工厂（与 {@link MeteredEngineModelTransport} 同包，
 * 以便把引擎外发套进既有 egress ＋ 预算信封）。
 *
 * <p><b>解析顺序（写死）。</b>
 * <ol>
 *   <li>{@code RuntimeCatalogService.currentRevision(tenant)} 命中 {@code modelId} ⇒ 用其 provider；</li>
 *   <li>否则 {@code knownModels(tenant)} 中该 {@code modelId} 的**唯一** provider；</li>
 *   <li>多 provider 同名 ⇒ 歧义 fail-closed；未知模型 ⇒ fail-closed（不回落全局模型）。</li>
 * </ol>
 *
 * <p><b>端点/凭据只来自既有配置。</b>基线照 {@code AiEmbeddedAgentEngineClosureConfiguration#agentChatModel}
 * 读 {@code ai.providers.<provider>.{url,endpoints.chat,api-key}}；若 {@link ProviderConnectionPort} 在场且
 * 该 provider 有引导项，则端点/密钥以引导项为准（D16 凭据外注入）。端口缺席或该 provider 无引导项时
 * **只用配置**；两者都没有 ⇒ fail-closed。不新增凭据，不打印/不落日志。
 *
 * <p><b>缓存。</b>客户端按 {@code providerId + '\0' + modelId} 缓存复用；provider 解析结果按
 * {@code tenant + '\0' + modelId} 缓存，未命中才查库（目录发布后 provider 变更需进程重启生效，
 * 见交付报告风险）。
 */
public class EmbeddedAgentModelFactory implements AgentModelFactory {

    private static final int KNOWN_MODEL_LIMIT = 200;

    private final RuntimeCatalogService runtimeCatalogService;
    private final AIModelProperties aiModelProperties;
    private final EgressPolicy egressPolicy;
    private final ProviderSpendEnvelope spendEnvelope;
    private final ObjectProvider<ProviderConnectionPort> connectionPort;

    /** tenant + '\0' + modelId → providerId（仅缓存未命中时查库）。 */
    private final ConcurrentHashMap<String, String> providerByModel = new ConcurrentHashMap<>();

    /** providerId + '\0' + modelId → model client（键不含端点/密钥）。 */
    private final ConcurrentHashMap<String, Model> models = new ConcurrentHashMap<>();

    public EmbeddedAgentModelFactory(RuntimeCatalogService runtimeCatalogService,
                                     AIModelProperties aiModelProperties,
                                     EgressPolicy egressPolicy,
                                     ProviderSpendEnvelope spendEnvelope,
                                     ObjectProvider<ProviderConnectionPort> connectionPort) {
        this.runtimeCatalogService = runtimeCatalogService;
        this.aiModelProperties = aiModelProperties;
        this.egressPolicy = egressPolicy;
        this.spendEnvelope = spendEnvelope;
        this.connectionPort = connectionPort;
    }

    @Override
    public Model create(String tenantId, String modelId) {
        if (!StringUtils.hasText(tenantId)) {
            throw new AgentModelUnavailableException("MODEL 解析缺少租户");
        }
        if (!StringUtils.hasText(modelId)) {
            throw new AgentModelUnavailableException("MODEL 解析缺少模型标识");
        }

        String resolutionKey = tenantId + '\0' + modelId;
        String providerId = providerByModel.get(resolutionKey);
        if (providerId == null) {
            providerByModel.putIfAbsent(resolutionKey, resolveProvider(tenantId, modelId));
            providerId = providerByModel.get(resolutionKey);
        }

        String modelKey = providerId + '\0' + modelId;
        Model cached = models.get(modelKey);
        if (cached != null) {
            return cached;
        }
        Model created = build(providerId, modelId);
        Model raced = models.putIfAbsent(modelKey, created);
        return raced == null ? created : raced;
    }

    /** currentRevision 优先；否则 knownModels 唯一 provider；歧义/未知 fail-closed。 */
    private String resolveProvider(String tenantId, String modelId) {
        RuntimeCatalogService.RevisionRow current = runtimeCatalogService.currentRevision(tenantId);
        if (current != null && modelId.equals(current.modelId()) && StringUtils.hasText(current.providerId())) {
            return current.providerId();
        }
        List<RuntimeCatalogService.ModelRow> known = runtimeCatalogService.knownModels(tenantId, KNOWN_MODEL_LIMIT);
        Set<String> providers = new LinkedHashSet<>();
        if (known != null) {
            for (RuntimeCatalogService.ModelRow row : known) {
                if (row != null && modelId.equals(row.modelId()) && StringUtils.hasText(row.providerId())) {
                    providers.add(row.providerId());
                }
            }
        }
        if (providers.size() > 1) {
            throw new AgentModelUnavailableException(
                    "模型目录存在多个 provider 候选，拒绝猜测: " + modelId);
        }
        if (providers.size() == 1) {
            return providers.iterator().next();
        }
        throw new AgentModelUnavailableException("该租户运行目录未发布模型: " + modelId);
    }

    /** 按既有配置（可选连接引导）构建 OpenAIChatModel，并套上既有计量传输。 */
    private Model build(String providerId, String modelId) {
        Map<String, AIModelProperties.ProviderConfig> providers = aiModelProperties.getProviders();
        AIModelProperties.ProviderConfig provider = providers == null ? null : providers.get(providerId);
        if (provider == null) {
            throw new AgentModelUnavailableException("模型 provider 未在 ai.providers 配置: " + providerId);
        }
        String endpointPath = provider.getEndpoints() == null ? null : provider.getEndpoints().get("chat");
        if (!StringUtils.hasText(provider.getUrl()) || !StringUtils.hasText(endpointPath)) {
            throw new AgentModelUnavailableException("模型 provider 缺少 url 或 endpoints.chat: " + providerId);
        }

        String baseUrl = provider.getUrl();
        String apiKey = provider.getApiKey();
        ProviderConnectionPort port = connectionPort == null ? null : connectionPort.getIfAvailable();
        if (port != null) {
            try {
                ProviderConnectionPort.ProviderConnection connection = port.requireConnection(providerId, null);
                URI endpoint = connection.endpoint();
                // 连接引导给的是完整调用地址：拆成 baseUrl + endpointPath（与闭包构造同形）
                if (endpoint != null && StringUtils.hasText(endpoint.getRawPath())) {
                    baseUrl = endpoint.getScheme() + "://" + endpoint.getRawAuthority();
                    endpointPath = endpoint.getRawPath()
                            + (StringUtils.hasText(endpoint.getRawQuery()) ? "?" + endpoint.getRawQuery() : "");
                }
                if (connection.apiKey() != null && !connection.apiKey().isBlank()) {
                    apiKey = connection.apiKey();
                }
            } catch (ConfigAuthorityUnavailable noBootstrap) {
                // 端口在场但该 provider 无引导项：按 ai.providers 配置构造（不新增凭据、不猜端点）
            }
        }

        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .baseUrl(baseUrl)
                .endpointPath(endpointPath)
                .apiKey(apiKey)
                .modelName(modelId)
                .stream(true)
                // 与闭包同口径：外发经 egress 校验 + 预占/结算信封，按真实 provider/model 记账
                .httpTransport(new MeteredEngineModelTransport(
                        JdkHttpTransport.builder().build(), egressPolicy, spendEnvelope, providerId, modelId))
                // 兼容端点普遍无法同时处理 response_format 与工具调用，统一走 generate_response 兜底
                .nativeStructuredOutputWithTools(false);
        if (ModelProvider.DEEP_SEEK.matches(providerId)) {
            builder.formatter(new DeepSeekFormatter());
        }
        try {
            return builder.build();
        } catch (RuntimeException failure) {
            // 文案只带 provider/model，不携带端点或密钥值
            throw new AgentModelUnavailableException(
                    "模型客户端构建失败: " + providerId + "/" + modelId, failure);
        }
    }
}
