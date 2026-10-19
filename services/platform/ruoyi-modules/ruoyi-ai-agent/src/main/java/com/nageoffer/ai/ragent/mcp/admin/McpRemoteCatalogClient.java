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

import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 受控 MCP 端点连接器：只负责"initialize → tools/list"的协议发现。
 *
 * <p><b>安全边界</b>：
 * <ul>
 *   <li>调用方必须先经 {@link McpEndpointAllowList#requireAllowed(URI)}；
 *       本类拿到的是已批准目标，自身不重复做策略判断；</li>
 *   <li>流式 HTTP 客户端显式 {@code followRedirects(NEVER)}——不跟随重定向，
 *       因此不会把请求（连同解析后的凭据头）带到非白名单主机；</li>
 *   <li>URL 归一化：去尾部 {@code /}，未以 {@code /mcp} 结尾时补 {@code /mcp}
 *       （与既有 {@code AgentMcpClients} 同口径）。</li>
 * </ul>
 */
@Component
public class McpRemoteCatalogClient {

    /** 单次协议发现的上限；MCP 工具清单通常毫秒级返回。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /**
     * 连接并读取工具清单；失败以运行时异常抛出，由服务层分类成具名失败态
     * （不抛未捕获异常到网关、不写库）。
     */
    public List<Tool> discover(String endpoint, Map<String, String> headers) {
        String url = normalizeEndpoint(endpoint);
        McpClientWrapper client = null;
        try {
            McpClientBuilder builder = McpClientBuilder.create("mcp-admin")
                    .streamableHttpTransport(url)
                    .customizeStreamableHttpClient(http -> http.followRedirects(HttpClient.Redirect.NEVER))
                    .timeout(TIMEOUT)
                    .initializationTimeout(TIMEOUT);
            if (headers != null && !headers.isEmpty()) {
                builder.headers(headers);
            }
            client = builder.buildSync();
            client.initialize().block(TIMEOUT);
            List<Tool> tools = client.listTools().block(TIMEOUT);
            return tools == null ? List.of() : List.copyOf(tools);
        } finally {
            if (client != null) {
                try {
                    client.close();
                } catch (RuntimeException ignored) {
                    // 关闭失败不影响本次发现结果；连接按请求级短生命周期使用
                }
            }
        }
    }

    /** {@code baseUrl} → 流式 HTTP 端点（去尾斜杠并补 {@code /mcp}）。 */
    public static String normalizeEndpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("MCP endpoint is required");
        }
        String base = baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.endsWith("/mcp") ? base : base + "/mcp";
    }

    /**
     * 失败路径的 HTTP 状态探针（只用于区分 401/403 与"连接失败"）。
     *
     * <p>为什么需要：MCP SDK 0.17.0 的流式传输对非 2xx 只抛
     * {@code RuntimeException("Failed to send message: ...")}，消息里<b>没有</b>状态码；
     * 而连接测试要求把鉴权失败单独归类。这里对<b>同一个已批准目标</b>发一条最小
     * {@code initialize} 请求并只读状态码；不解析响应、不重试、不跟随重定向。
     *
     * <p>仅在发现已失败之后调用（零成功路径开销）；网络不可达时返回 -1。
     */
    public int probeStatus(String endpoint, Map<String, String> headers) {
        String url = normalizeEndpoint(endpoint);
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(TIMEOUT)
                    .build();
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(INITIALIZE_PROBE));
            if (headers != null) {
                headers.forEach(request::header);
            }
            return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (IOException | IllegalArgumentException e) {
            return -1;
        }
    }

    /** 最小 initialize 请求体（探针只用；不含凭据，凭据在头里）。 */
    private static final String INITIALIZE_PROBE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize",
             "params":{"protocolVersion":"2025-06-18","capabilities":{},
                       "clientInfo":{"name":"mcp-admin-probe","version":"1.0"}}}
            """;
}
