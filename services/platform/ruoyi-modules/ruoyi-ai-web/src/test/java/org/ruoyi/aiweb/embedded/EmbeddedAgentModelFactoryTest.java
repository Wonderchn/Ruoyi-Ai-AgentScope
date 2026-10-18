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
import com.nageoffer.ai.ragent.rag.service.RuntimeCatalogService;
import com.nageoffer.ai.ragent.runtime.config.ConfigAuthorityUnavailable;
import com.nageoffer.ai.ragent.runtime.config.ProviderConnectionPort;
import com.nageoffer.ai.ragent.runtime.usage.EgressPolicy;
import com.nageoffer.ai.ragent.runtime.usage.ProviderSpendEnvelope;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F09:3-C：{@link EmbeddedAgentModelFactory} 的模型解析判据（离线）。
 *
 * <p>解析顺序写死：{@code currentRevision(tenant)} 命中 modelId ⇒ provider；否则
 * {@code knownModels} 唯一命中；多 provider 歧义 / 未知模型 / 配置缺失 ⇒ fail-closed（抛具名异常）。
 * 端点和密钥只来自既有 {@code ai.providers.*} 与（可选）{@code ProviderConnectionPort}，
 * 不新增凭据；异常文案不得携带密钥值。
 */
@Tag("dev")
class EmbeddedAgentModelFactoryTest {

    private static final String TENANT = "T1";

    private final RuntimeCatalogService catalog = mock(RuntimeCatalogService.class);
    private final AIModelProperties properties = new AIModelProperties();
    private final EgressPolicy egress = mock(EgressPolicy.class);
    private final ProviderSpendEnvelope spend = mock(ProviderSpendEnvelope.class);
    private final ProviderConnectionPort connections = mock(ProviderConnectionPort.class);

    private EmbeddedAgentModelFactory factory(ObjectProvider<ProviderConnectionPort> port) {
        return new EmbeddedAgentModelFactory(catalog, properties, egress, spend, port);
    }

    private EmbeddedAgentModelFactory factory() {
        return factory(providerOf(null));
    }

    @Test
    @DisplayName("currentRevision 命中：优先于 knownModels，模型名即绑定 modelId")
    void currentRevisionWinsAndBuildsTheBoundModel() {
        when(catalog.currentRevision(TENANT)).thenReturn(revision("deepseek", "deepseek-chat"));
        providers("deepseek", "http://127.0.0.1:9", Map.of("chat", "/v1/chat/completions"), "");

        Model model = factory().create(TENANT, "deepseek-chat");

        assertThat(model.getModelName()).isEqualTo("deepseek-chat");
        verify(catalog, never()).knownModels(anyString(), anyInt());
    }

    @Test
    @DisplayName("currentRevision 未命中：knownModels 唯一 provider 命中；缓存复用（只查库一次）")
    void fallsBackToKnownModelsAndReusesTheCachedClient() {
        when(catalog.currentRevision(TENANT)).thenReturn(revision("other-provider", "other-model"));
        when(catalog.knownModels(TENANT, 200)).thenReturn(List.of(
                modelRow("deepseek", "deepseek-chat"),
                modelRow("deepseek", "deepseek-chat")));
        providers("deepseek", "http://127.0.0.1:9", Map.of("chat", "/v1/chat/completions"), "");

        Model first = factory().create(TENANT, "deepseek-chat");
        Model second = factory().create(TENANT, "deepseek-chat");

        assertThat(second).isSameAs(first);
        verify(catalog, times(1)).currentRevision(TENANT);
        verify(catalog, times(1)).knownModels(TENANT, 200);
    }

    @Test
    @DisplayName("未知模型：knownModels 无该 modelId ⇒ fail-closed")
    void unknownModelFailsClosed() {
        when(catalog.currentRevision(TENANT)).thenReturn(null);
        when(catalog.knownModels(TENANT, 200)).thenReturn(List.of(modelRow("deepseek", "another-model")));

        assertThatThrownBy(() -> factory().create(TENANT, "ghost-model"))
                .isInstanceOf(AgentModelFactory.AgentModelUnavailableException.class)
                .hasMessageContaining("ghost-model");
    }

    @Test
    @DisplayName("多 provider 歧义：同名模型落在两个 provider ⇒ fail-closed，不猜一个")
    void ambiguousProviderFailsClosed() {
        when(catalog.currentRevision(TENANT)).thenReturn(null);
        when(catalog.knownModels(TENANT, 200)).thenReturn(List.of(
                modelRow("deepseek", "shared-model"),
                modelRow("dashscope", "shared-model")));

        assertThatThrownBy(() -> factory().create(TENANT, "shared-model"))
                .isInstanceOf(AgentModelFactory.AgentModelUnavailableException.class)
                .hasMessageContaining("shared-model");
    }

    @Test
    @DisplayName("配置缺失：ai.providers 无该 provider 或缺 chat 端点 ⇒ fail-closed")
    void missingProviderConfigFailsClosed() {
        when(catalog.currentRevision(TENANT)).thenReturn(revision("ghost", "ghost-model"));

        assertThatThrownBy(() -> factory().create(TENANT, "ghost-model"))
                .isInstanceOf(AgentModelFactory.AgentModelUnavailableException.class)
                .hasMessageContaining("ghost");

        providers("ghost", "http://127.0.0.1:9", Map.of(), "");
        assertThatThrownBy(() -> factory().create(TENANT, "ghost-model"))
                .isInstanceOf(AgentModelFactory.AgentModelUnavailableException.class);
    }

    @Test
    @DisplayName("不泄露密钥：异常文案不得包含配置里的 api-key")
    void failureMessagesNeverCarryTheApiKey() {
        when(catalog.currentRevision(TENANT)).thenReturn(revision("deepseek", "deepseek-chat"));
        providers("deepseek", "http://127.0.0.1:9", Map.of(), "SECRET-KEY-VALUE");

        assertThatThrownBy(() -> factory().create(TENANT, "deepseek-chat"))
                .isInstanceOf(AgentModelFactory.AgentModelUnavailableException.class)
                .hasMessageNotContaining("SECRET-KEY-VALUE");
    }

    @Test
    @DisplayName("ProviderConnectionPort 在场：走 requireConnection；端口无引导项时按配置构造")
    void providerConnectionIsConsultedAndConfigIsTheFallback() {
        when(catalog.currentRevision(TENANT)).thenReturn(revision("deepseek", "deepseek-chat"));
        providers("deepseek", "http://127.0.0.1:9", Map.of("chat", "/v1/chat/completions"), "config-key");
        when(connections.requireConnection("deepseek", null)).thenReturn(
                new ProviderConnectionPort.ProviderConnection("deepseek",
                        URI.create("http://127.0.0.1:10/v2/chat/completions"), "env:deepseek", "port-key"));

        Model model = factory(providerOf(connections)).create(TENANT, "deepseek-chat");

        assertThat(model.getModelName()).isEqualTo("deepseek-chat");
        verify(connections).requireConnection("deepseek", null);

        // 端口在场但没有该 provider 的引导项：只用 ai.providers 配置（不因端口缺席而误配）
        when(connections.requireConnection("dashscope", null))
                .thenThrow(new ConfigAuthorityUnavailable("no connection bootstrap for providerId=dashscope"));
        when(catalog.currentRevision(TENANT)).thenReturn(revision("dashscope", "qwen-plus"));
        providers("dashscope", "http://127.0.0.1:11", Map.of("chat", "/v1/chat/completions"), "config-key");

        Model fallback = factory(providerOf(connections)).create(TENANT, "qwen-plus");
        assertThat(fallback.getModelName()).isEqualTo("qwen-plus");
    }

    // ------------------------------------------------------------------ 夹具

    private void providers(String providerId, String url, Map<String, String> endpoints, String apiKey) {
        AIModelProperties.ProviderConfig config = new AIModelProperties.ProviderConfig();
        config.setUrl(url);
        config.setApiKey(apiKey);
        config.setEndpoints(new java.util.HashMap<>(endpoints));
        properties.getProviders().put(providerId, config);
    }

    private static RuntimeCatalogService.RevisionRow revision(String providerId, String modelId) {
        return new RuntimeCatalogService.RevisionRow(
                TENANT, "rev-1", 7L, "PUBLISHED", providerId, modelId,
                "catalog-v1", "params-hash", "{}", null, "op-1",
                Instant.parse("2026-10-18T00:00:00Z"), 1536, 100L);
    }

    private static RuntimeCatalogService.ModelRow modelRow(String providerId, String modelId) {
        return new RuntimeCatalogService.ModelRow(
                providerId, modelId, "catalog-v1", 1536, Instant.parse("2026-10-18T00:00:00Z"), "params-hash");
    }

    private static <T> ObjectProvider<T> providerOf(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }
        };
    }
}
