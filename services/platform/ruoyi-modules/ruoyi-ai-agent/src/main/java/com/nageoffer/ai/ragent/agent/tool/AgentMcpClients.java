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

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.framework.integration.SaasCapabilityBoundary;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 模式的 MCP 连接和工具发现由 AgentScope 持有
 *
 * <p>P1.2a：启动期自动连接远程 MCP 属于<b>未批准旧能力</b>（MCP demo 服务另有独立示例库，
 * 不在 32 表内）。{@link #init()} 在第一次网络调用之前短路关闭，因此默认启动不连接任何
 * MCP 服务、不发现工具。Bean 与 {@link #close()} 保留，保证依赖解析与销毁语义不变。
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
@EnableConfigurationProperties(AgentMcpProperties.class)
public class AgentMcpClients {

    private final AgentMcpProperties properties;
    /**
     * 旧能力关闭判定；缺席按关闭处理（不默认放行）。
     */
    private final ObjectProvider<SaasCapabilityBoundary> capabilityBoundary;
    private final Map<String, RemoteTool> tools = new LinkedHashMap<>();
    private final List<McpClientWrapper> clients = new ArrayList<>();

    @PostConstruct
    public void init() {
        init(capabilityBoundary == null ? null : capabilityBoundary.getIfAvailable());
    }

    /**
     * 显式边界版本：关闭时<b>不连接任何 MCP 服务</b>，且不发起任何网络调用。
     *
     * <p>与旧 MQ 消费者不同，这里<b>不抛异常</b>：{@link #init()} 是 {@code @PostConstruct}，
     * 抛异常会让整个应用启动失败，而"启动期不连 MCP"本身不是错误状态——它正是默认状态。
     * 真正的强制点在入口：没有可信身份的请求在 HTTP 层就被拒绝，工具表也拿不到任何远端工具。
     *
     * <p>边界 Bean 缺席同样按关闭处理（记 ERROR 便于发现装配错误），
     * 绝不因为"没注入守卫"就默认去连远端。
     */
    void init(SaasCapabilityBoundary boundary) {
        if (boundary == null) {
            log.error("SaaS capability boundary bean is missing; MCP startup stays closed "
                    + "(no remote MCP connection will be attempted)");
            return;
        }
        if (!tryOpen(boundary)) {
            return;
        }

        List<AgentMcpProperties.ServerConfig> servers = properties.getServers();
        if (servers == null) {
            return;
        }
        for (AgentMcpProperties.ServerConfig server : servers) {
            connect(server);
        }
    }

    private static boolean tryOpen(SaasCapabilityBoundary boundary) {
        try {
            boundary.requireOpen(SaasCapabilityBoundary.LegacyCapability.AGENT_MCP_STARTUP);
            return true;
        } catch (SaasCapabilityBoundary.ClosedCapabilityException e) {
            log.info("MCP startup is closed by the SaaS capability boundary; no remote MCP connection attempted");
            return false;
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger CONNECT_ATTEMPTS =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 本进程内累计的远端 MCP 连接尝试次数。
     *
     * <p>关闭护栏需要"零网络调用"的<b>可观测</b>证据，而不仅是异常类型推断：
     * 装配边界测试用它在真实 Bean 上断言关闭态确实一次都没连过。
     */
    static int connectAttempts() {
        return CONNECT_ATTEMPTS.get();
    }

    private void connect(AgentMcpProperties.ServerConfig server) {
        CONNECT_ATTEMPTS.incrementAndGet();
        McpClientWrapper client = null;
        try {
            String baseUrl = StrUtil.removeSuffix(server.getUrl(), "/");
            String url = baseUrl.endsWith("/mcp") ? baseUrl : baseUrl + "/mcp";
            client = McpClientBuilder.create(server.getName())
                    .streamableHttpTransport(url)
                    .buildSync();
            client.initialize().block();
            List<Tool> discovered = client.listTools().block();
            clients.add(client);
            if (discovered != null) {
                for (Tool tool : discovered) {
                    RemoteTool existing = tools.putIfAbsent(tool.name(), new RemoteTool(tool, client));
                    if (existing != null) {
                        log.warn("MCP 工具重名，保留先连接的服务, toolId={}, server={}", tool.name(), server.getName());
                    }
                }
            }
            log.info("AgentScope MCP 服务已连接, server={}, tools={}", server.getName(),
                    discovered == null ? 0 : discovered.size());
        } catch (Exception e) {
            if (client != null) {
                try {
                    client.close();
                } catch (Exception closeError) {
                    log.warn("关闭失败的 MCP 连接时发生异常, server={}", server.getName(), closeError);
                }
            }
            log.error("AgentScope MCP 服务连接失败, server={}, reason={}", server.getName(), e.getMessage());
        }
    }

    public RemoteTool get(String toolId) {
        return tools.get(toolId);
    }

    @PreDestroy
    public void close() {
        for (McpClientWrapper client : clients) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("关闭 MCP 连接失败, server={}", client.getName(), e);
            }
        }
    }

    public record RemoteTool(Tool definition, McpClientWrapper client) {
    }
}
