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

package com.nageoffer.ai.ragent.ingest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.runtime.config.RunConfigBinding;
import com.nageoffer.ai.ragent.runtime.usage.EgressPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F03 op6：run 绑定版本声明的生成参数**真的进了提供方请求体**（不是只存了 params_hash）。
 *
 * <p><b>为什么必须用真 HTTP 回环捕获请求体。</b>"参数生效"这件事发生在
 * {@code RealChatGateway.stream} 组装的 JSON 上，只在源码里断言"有这个键"等于测实现而不是测行为；
 * 回环 server 逐字节收下网关真实发出的请求体，测的就是"提供方会看到什么"。
 *
 * <p><b>负例同场钉住：</b>非白名单键（{@code max_tokens} 由 run 预算决定、{@code unknown_key}
 * 未知）与越界值（{@code presence_penalty=2.5}）必须**不在**请求体里 —— 白名单是安全边界
 * （这条由第二个用例在纯函数层面再钉一遍，覆盖边界值）。
 */
@Tag("dev")
class RealChatGatewayRequestParamsTest {

    private static EgressPolicy egress() {
        EgressPolicy policy = new EgressPolicy();
        policy.setEnabled(true);
        policy.setAllowedProviders("deepseek");
        return policy;
    }

    @Test
    void generationParamsFromTheBoundRevisionReachTheProviderRequestBody() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] request = exchange.getRequestBody().readAllBytes();
            captured.set(new String(request, StandardCharsets.UTF_8));
            byte[] response = ("data: {\"id\":\"req-1\",\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n"
                    + "data: {\"id\":\"req-1\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"total_tokens\":7}}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("temperature", 0.7);
            params.put("top_p", 0.9);
            params.put("presence_penalty", 2.5);   // 越界：必须丢弃
            params.put("max_tokens", 9999);        // 非白名单：长度只认 run 预算
            params.put("unknown_key", "x");        // 未知键：必须丢弃
            RunConfigBinding binding = new RunConfigBinding("t1", "run-1", "rag.chat", "rev-1", 1L,
                    "deepseek", "deepseek-chat", "cat-1", "sha256:p", "env:DEEPSEEK_API_KEY", "op-1",
                    Instant.EPOCH, params);
            RealChatGateway gateway = new RealChatGateway("test-key", egress(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));

            List<String> deltas = new ArrayList<>();
            var result = gateway.stream(binding, List.of(ChatMessage.user("q")), 321, deltas::add);

            assertEquals("stop", result.finishReason());
            assertEquals(List.of("hi"), deltas);

            Map<String, Object> body = new ObjectMapper().readValue(captured.get(),
                    new TypeReference<Map<String, Object>>() { });
            assertEquals("deepseek-chat", body.get("model"));
            assertEquals(321, ((Number) body.get("max_tokens")).intValue(),
                    "长度只由 run 预算决定：参数里的 max_tokens 不得覆盖");
            assertEquals(0.7d, ((Number) body.get("temperature")).doubleValue(), 1e-9);
            assertEquals(0.9d, ((Number) body.get("top_p")).doubleValue(), 1e-9);
            assertFalse(body.containsKey("presence_penalty"), "越界值必须被挡在请求体之外");
            assertFalse(body.containsKey("unknown_key"), "未知键不得外发");
            @SuppressWarnings("unchecked")
            Map<String, Object> thinking = (Map<String, Object>) body.get("thinking");
            assertEquals("disabled", thinking.get("type"), "thinking 是既定 disabled，不被参数覆盖");
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    void whitelistKeepsOnlyInRangeSamplingParamsAtTheBoundaries() {
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("temperature", 0.0);          // 下界合法
        params.put("top_p", 0.0);                // (0,1]：0 丢弃
        params.put("frequency_penalty", -2.0);   // 下界合法
        params.put("amount", "0.7");             // 非数值：丢弃（不做字符串解析）
        RealChatGateway.applyGenerationParams(body, params);

        assertEquals(0.0d, ((Number) body.get("temperature")).doubleValue(), 1e-9);
        assertFalse(body.containsKey("top_p"), "top_p=0 不合法（(0,1]）");
        assertEquals(-2.0d, ((Number) body.get("frequency_penalty")).doubleValue(), 1e-9);
        assertFalse(body.containsKey("amount"), "字符串不是合法数值形态");
        assertEquals(2, body.size(), "只放行两条合法参数");

        // 空/缺参数：请求体不被加料（老行为逐字不变）
        Map<String, Object> untouched = new LinkedHashMap<>();
        RealChatGateway.applyGenerationParams(untouched, Map.of());
        RealChatGateway.applyGenerationParams(untouched, null);
        assertTrue(untouched.isEmpty());
    }
}
