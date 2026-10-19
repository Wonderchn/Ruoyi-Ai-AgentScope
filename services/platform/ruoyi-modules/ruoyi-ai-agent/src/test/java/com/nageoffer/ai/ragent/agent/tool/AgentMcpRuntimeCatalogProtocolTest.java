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

package com.nageoffer.ai.ragent.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.agent.dao.entity.McpToolDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.McpToolMapper;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.McpToolBinding;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.mcp.admin.AgentMcpAllowListProperties;
import com.nageoffer.ai.ragent.mcp.admin.McpEndpointAllowList;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.permission.PermissionBehavior;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F12-M2 真实协议最小闭环：进程内 streamable HTTP MCP stub（initialize + tools/list）。
 *
 * <p>证明的不是 mock 语义，而是：
 * <ul>
 *   <li>批准目标真的走 MCP 协议完成 initialize + 工具发现，{@code resolve} 得到可直接挂载的
 *       {@link AgentMcpClients.RemoteTool}；</li>
 *   <li>未批准目标对真实连接器也<b>零请求</b>（stub 的 requestCount == 0）；</li>
 *   <li>拿到的工具交给 {@link McpToolProxy} 后，意图树 requireConfirm / readOnlyHint
 *       分别落到 ask / allow（本包不改判定，只提供真实工具使该路径可验证）。</li>
 * </ul>
 */
@Tag("dev")
class AgentMcpRuntimeCatalogProtocolTest {

    private static final String TENANT = "T1";

    private final McpToolMapper toolMapper = mock(McpToolMapper.class);
    private final AgentMcpRuntimeProperties properties = new AgentMcpRuntimeProperties();
    private final AgentMcpAllowListProperties allowProperties = new AgentMcpAllowListProperties();

    @BeforeEach
    void setUp() {
        PrincipalContext.set(new ExecutionPrincipal(TENANT, "2101", "platform:" + TENANT + ":2101",
                1, 1, Set.of(), "jti", "platform", 0, Long.MAX_VALUE));
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    @DisplayName("批准目标：真实 initialize + tools/list → RemoteTool → McpToolProxy ask/allow")
    void allowedEndpointResolvesRealToolAndFeedsProxyPermissionDecision() throws IOException {
        try (StubMcpServer server = new StubMcpServer()) {
            allow(server.baseUri().toString());
            when(toolMapper.selectList(any())).thenReturn(List.of(row("alpha", server.baseUri().toString())));
            AgentMcpRuntimeCatalog catalog = catalog();

            AgentMcpClients.RemoteTool alpha = catalog.resolve("alpha");

            assertThat(alpha).isNotNull();
            assertThat(alpha.definition().name()).isEqualTo("alpha");
            assertThat(server.initializeCount()).isGreaterThanOrEqualTo(1);
            assertThat(server.toolListCount()).isGreaterThanOrEqualTo(1);
            assertThat(catalog.connectCount()).isEqualTo(1);

            // 意图树未声明 requireConfirm、服务端也未声明 readOnlyHint ⇒ ask
            McpToolBinding plain = McpToolBinding.of("alpha", "订单查询", "查询订单", false, alpha);
            assertThat(new McpToolProxy(plain).checkPermissions(Map.of(), null).block().getBehavior())
                    .isEqualTo(PermissionBehavior.ASK);

            // readOnlyHint=true 且意图树未要求确认 ⇒ allow
            when(toolMapper.selectList(any())).thenReturn(List.of(row("reader", server.baseUri().toString())));
            AgentMcpClients.RemoteTool reader = catalog.resolve("reader");
            assertThat(reader).isNotNull();
            McpToolBinding readOnly = McpToolBinding.of("reader", "只读查询", "", false, reader);
            assertThat(new McpToolProxy(readOnly).checkPermissions(Map.of(), null).block().getBehavior())
                    .isEqualTo(PermissionBehavior.ALLOW);

            // 意图树显式要求确认时，即使 readOnly 也仍是 ask（规则不变，仅证明输入可用）
            McpToolBinding confirm = McpToolBinding.of("reader", "只读查询", "", true, reader);
            assertThat(new McpToolProxy(confirm).checkPermissions(Map.of(), null).block().getBehavior())
                    .isEqualTo(PermissionBehavior.ASK);

            catalog.close();
        }
    }

    @Test
    @DisplayName("未批准目标：真实连接器零请求（不是先连后拒）")
    void unapprovedEndpointSendsNoRequestAtAll() throws IOException {
        try (StubMcpServer server = new StubMcpServer()) {
            // allow-list 为空 = fail-closed
            when(toolMapper.selectList(any())).thenReturn(List.of(row("alpha", server.baseUri().toString())));
            AgentMcpRuntimeCatalog catalog = catalog();

            assertThat(catalog.resolve("alpha")).isNull();

            assertThat(server.requestCount()).as("未批准目标不允许任何网络请求").isZero();
            assertThat(catalog.connectCount()).isZero();
            assertThat(catalog.deniedCount()).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ 辅助

    private AgentMcpRuntimeCatalog catalog() {
        return new AgentMcpRuntimeCatalog(toolMapper, providerOf(new McpEndpointAllowList(allowProperties)),
                providerOf(null), properties);
    }

    private void allow(String endpoint) {
        List<String> endpoints = new ArrayList<>(allowProperties.getEndpoints());
        endpoints.add(endpoint);
        allowProperties.setEndpoints(endpoints);
    }

    private static McpToolDO row(String name, String baseUrl) {
        return McpToolDO.builder()
                .name(name)
                .type("REMOTE")
                .status("ENABLED")
                .delFlag("0")
                .tenantId(TENANT)
                .configJson("{\"baseUrl\":\"" + baseUrl + "\"}")
                .build();
    }

    private static <T> ObjectProvider<T> providerOf(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

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
        };
    }

    /**
     * 最小 streamable HTTP MCP stub：与 F12-M1 的 {@code FakeMcpHttpServer} 同形
     * （initialize / tools/list / notification 202 / GET 405 / DELETE 200），
     * 额外记录请求计数与一个可观测的凭据头。
     */
    private static final class StubMcpServer implements AutoCloseable {

        private static final ObjectMapper JSON = new ObjectMapper();

        private final HttpServer server;
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicInteger initializes = new AtomicInteger();
        private final AtomicInteger toolLists = new AtomicInteger();
        private final List<Map<String, Object>> tools = List.of(
                tool("alpha", "第一个工具", null),
                tool("reader", "只读工具", Map.of("readOnlyHint", true)));

        private StubMcpServer() throws IOException {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/mcp", this::handle);
            this.server.start();
        }

        private URI baseUri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        private int requestCount() {
            return requests.get();
        }

        private int initializeCount() {
            return initializes.get();
        }

        private int toolListCount() {
            return toolLists.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            requests.incrementAndGet();
            try {
                String method = exchange.getRequestMethod();
                if ("GET".equalsIgnoreCase(method)) {
                    respond(exchange, 405, null);
                    return;
                }
                if ("DELETE".equalsIgnoreCase(method)) {
                    respond(exchange, 200, null);
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                JsonNode request = body.isBlank() ? null : JSON.readTree(body);
                if (request == null) {
                    respond(exchange, 400, null);
                    return;
                }
                JsonNode id = request.get("id");
                String rpcMethod = request.path("method").asText("");
                if (id == null || id.isNull()) {
                    respond(exchange, 202, null);
                    return;
                }
                Map<String, Object> result = switch (rpcMethod) {
                    case "initialize" -> {
                        initializes.incrementAndGet();
                        yield Map.of(
                                "protocolVersion", "2025-06-18",
                                "capabilities", Map.of("tools", Map.of()),
                                "serverInfo", Map.of("name", "stub-mcp", "version", "1.0.0"));
                    }
                    case "tools/list" -> {
                        toolLists.incrementAndGet();
                        yield Map.of("tools", tools);
                    }
                    case "ping" -> Map.of();
                    default -> null;
                };
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("jsonrpc", "2.0");
                response.put("id", JSON.treeToValue(id, Object.class));
                if (result == null) {
                    response.put("error", Map.of("code", -32601, "message", "method not found"));
                } else {
                    response.put("result", result);
                }
                respond(exchange, 200, JSON.writeValueAsBytes(response));
            } catch (RuntimeException e) {
                respond(exchange, 500, null);
            }
        }

        private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
            if (body == null) {
                exchange.sendResponseHeaders(status, -1);
            } else {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        }

        private static Map<String, Object> tool(String name, String description, Map<String, Object> annotations) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", Map.of("value", Map.of("type", "string")));
            schema.put("required", List.of("value"));
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", name);
            tool.put("description", description);
            tool.put("inputSchema", schema);
            if (annotations != null) {
                tool.put("annotations", annotations);
            }
            return tool;
        }
    }
}
