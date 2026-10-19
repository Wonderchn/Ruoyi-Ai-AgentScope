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

package com.nageoffer.ai.ragent.infra.embedding;

import com.nageoffer.ai.ragent.infra.chat.ChatEgressDeniedException;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import com.nageoffer.ai.ragent.runtime.usage.EgressPolicy;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F22:6：embedding 适配器的统一出口 allow-list。
 *
 * <p>四个 OpenAI 兼容 embedding 客户端共用 {@link AbstractOpenAIStyleEmbeddingClient}：
 * 允许 → 请求真实发出；拒绝/策略 bean 缺席 → 抛 {@link ChatEgressDeniedException} 且零请求。
 */
@Tag("dev")
class EmbeddingEgressPolicyTest {

    private static final String RESPONSE = "{\"data\":[{\"embedding\":[0.1,0.2]}]}";

    private static final Map<String, Supplier<AbstractOpenAIStyleEmbeddingClient>> CLIENTS =
            new LinkedHashMap<>() {{
                put("bailian", () -> new BaiLianEmbeddingClient(new OkHttpClient()));
                put("ollama", () -> new OllamaEmbeddingClient(new OkHttpClient()));
                put("siliconflow", () -> new SiliconFlowEmbeddingClient(new OkHttpClient()));
                put("aihubmix", () -> new AIHubMixEmbeddingClient(new OkHttpClient()));
            }};

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            requests.incrementAndGet();
            byte[] body = RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("策略拒绝或 bean 缺席：四个客户端都零请求并抛受控异常")
    void deniedOrAbsentPolicyMakesZeroRequests() {
        for (Map.Entry<String, Supplier<AbstractOpenAIStyleEmbeddingClient>> entry : CLIENTS.entrySet()) {
            int before = requests.get();

            AbstractOpenAIStyleEmbeddingClient denied = entry.getValue().get();
            ReflectionTestUtils.setField(denied, "egressPolicy", providerOf(policy("deepseek")));
            assertThrows(ChatEgressDeniedException.class,
                    () -> denied.embed("hello", target()), "denied provider=" + entry.getKey());
            assertEquals(before, requests.get(), "denied must not call provider=" + entry.getKey());

            AbstractOpenAIStyleEmbeddingClient absent = entry.getValue().get();
            ReflectionTestUtils.setField(absent, "egressPolicy", null);
            assertThrows(ChatEgressDeniedException.class,
                    () -> absent.embed("hello", target()), "absent policy provider=" + entry.getKey());
            assertEquals(before, requests.get(), "absent policy must not call provider=" + entry.getKey());
        }
    }

    @Test
    @DisplayName("策略允许：四个客户端都真实发出请求并解析向量")
    void allowedPolicyMakesRequest() {
        int expected = 0;
        for (Map.Entry<String, Supplier<AbstractOpenAIStyleEmbeddingClient>> entry : CLIENTS.entrySet()) {
            AbstractOpenAIStyleEmbeddingClient client = entry.getValue().get();
            ReflectionTestUtils.setField(client, "egressPolicy", providerOf(policy(entry.getKey())));

            List<Float> vector = client.embed("hello", target());

            expected++;
            assertEquals(expected, requests.get(), "allowed must call provider=" + entry.getKey());
            assertEquals(2, vector.size());
            assertTrue(vector.get(0) > 0F);
        }
    }

    static EgressPolicy policy(String... providers) {
        EgressPolicy policy = new EgressPolicy();
        policy.setEnabled(true);
        policy.setAllowedProviders(String.join(",", providers));
        return policy;
    }

    static <T> ObjectProvider<T> providerOf(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }

    private ModelTarget target() {
        AIModelProperties.ProviderConfig provider = new AIModelProperties.ProviderConfig();
        provider.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        provider.setApiKey("test-key");
        provider.setEndpoints(Map.of("embedding", "/embeddings"));

        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setModel("test-embedding");
        candidate.setDimension(2);
        return new ModelTarget("test-embedding", candidate, provider, null);
    }
}
