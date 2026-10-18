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

package com.nageoffer.ai.ragent.mcp.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * F12-M1 连接测试/刷新的进程内 MCP stub。
 *
 * <p>只实现 streamable HTTP 的最小协议面：{@code initialize} 与 {@code tools/list}
 * 各回一条 JSON-RPC 响应，notification 回 202 空体。用于在没有外网、没有真实 MCP
 * 服务的条件下证明：
 * <ul>
 *   <li>允许目标真的被连接（请求计数 &gt; 0）、工具清单真的被读到；</li>
 *   <li>未批准目标<b>零网络调用</b>（请求计数 == 0）；</li>
 *   <li>401/403 与连接拒绝分别落到具名失败态。</li>
 * </ul>
 */
final class FakeMcpHttpServer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger initializes = new AtomicInteger();
    private final AtomicInteger toolLists = new AtomicInteger();
    private final List<Map<String, Object>> tools;
    private volatile int rejectStatus;

    FakeMcpHttpServer() throws IOException {
        this(defaultTools());
    }

    FakeMcpHttpServer(List<Map<String, Object>> tools) throws IOException {
        this.tools = tools;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/mcp", this::handle);
        this.server.start();
    }

    URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    int requestCount() {
        return requests.get();
    }

    int initializeCount() {
        return initializes.get();
    }

    int toolListCount() {
        return toolLists.get();
    }

    void rejectWith(int status) {
        this.rejectStatus = status;
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
            int rejection = rejectStatus;
            if (rejection != 0) {
                respond(exchange, rejection, null);
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
                // notification（notifications/initialized 等）：202 空体
                respond(exchange, 202, null);
                return;
            }
            Map<String, Object> result = switch (rpcMethod) {
                case "initialize" -> {
                    initializes.incrementAndGet();
                    yield Map.of(
                            "protocolVersion", "2025-06-18",
                            "capabilities", Map.of("tools", Map.of()),
                            "serverInfo", Map.of("name", "fake-mcp", "version", "1.0.0"));
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

    private static List<Map<String, Object>> defaultTools() {
        return List.of(
                tool("alpha", "第一个工具"),
                tool("beta", "第二个工具"));
    }

    private static Map<String, Object> tool(String name, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("value", Map.of("type", "string")));
        schema.put("required", List.of("value"));
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("inputSchema", schema);
        return tool;
    }
}
