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

package com.nageoffer.ai.ragent.infra.vlm;

import com.nageoffer.ai.ragent.infra.chat.ChatEgressDeniedException;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F22:6：VLM 路由服务的统一出口 allow-list。
 *
 * <p>provider 取本次路由命中的候选提供方；拒绝/策略缺席 → 受控异常且零请求；
 * 允许 → 请求真实发出并解析 choices[0].message.content。
 */
@Tag("dev")
class RoutingVlmEgressPolicyTest {

    private static final String RESPONSE = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";

    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private ModelSelector selector;
    private RoutingVlmService service;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/vlm", exchange -> {
            requests.incrementAndGet();
            byte[] body = RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        selector = mock(ModelSelector.class);
        when(selector.selectVlmCandidates()).thenReturn(List.of(target()));
        service = new RoutingVlmService(selector, new OkHttpClient());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("策略拒绝：受控异常且零请求")
    void deniedPolicyMakesZeroRequests() {
        ReflectionTestUtils.setField(service, "egressPolicy", providerOf(policy("deepseek")));

        assertThrows(ChatEgressDeniedException.class,
                () -> service.describeImage(new byte[] {1}, "image/png", "describe", 16));
        assertEquals(0, requests.get());
    }

    @Test
    @DisplayName("策略 bean 缺席：fail-closed，零请求")
    void absentPolicyMakesZeroRequests() {
        ReflectionTestUtils.setField(service, "egressPolicy", null);

        assertThrows(ChatEgressDeniedException.class,
                () -> service.describeImage(new byte[] {1}, "image/png", "describe", 16));
        assertEquals(0, requests.get());
    }

    @Test
    @DisplayName("策略允许：请求真实发出并返回模型内容")
    void allowedPolicyMakesRequest() {
        ReflectionTestUtils.setField(service, "egressPolicy", providerOf(policy("bailian")));

        String content = service.describeImage(new byte[] {1}, "image/png", "describe", 16);

        assertEquals("ok", content);
        assertEquals(1, requests.get());
    }

    private ModelTarget target() {
        AIModelProperties.ProviderConfig provider = new AIModelProperties.ProviderConfig();
        provider.setApiKey("test-key");

        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setProvider("bailian");
        candidate.setModel("qwen-vl");
        candidate.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/vlm");
        return new ModelTarget("qwen-vl", candidate, provider, null);
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
}
