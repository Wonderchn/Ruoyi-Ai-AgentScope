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

package com.nageoffer.ai.ragent.mcp.config;

import com.nageoffer.ai.ragent.framework.security.DelegatedPrincipalFilter;
import com.nageoffer.ai.ragent.mcp.security.McpDelegatedToolPolicy;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MCP Server 配置类
 *
 * <p>受保护模式（{@code ai.integration.security.enabled=true}，SPEC-9099 §7，T0 2026-10-11）：
 * servlet 只挂受保护前缀 {@code /internal/ai/v1/mcp}（默认 {@code /mcp} 映射不存在＝无旁路），
 * 工具面收敛为 {@link McpDelegatedToolPolicy} 白名单；开关关（默认）＝现行为逐字节不变。
 */
@Configuration
public class McpServerConfig {

    /** 受保护模式的 MCP 端点路径（挂在委托验签过滤器的保护前缀下）。 */
    public static final String SECURED_SERVLET_PATH = DelegatedPrincipalFilter.PROTECTED_PREFIX + "/mcp";

    @Bean
    public HttpServletStreamableServerTransportProvider transportProvider(
            @Value("${ai.integration.security.enabled:false}") boolean secured) {
        // provider 自带 endpoint 判定（与 servlet 挂载点必须一致）：受保护模式挂受保护前缀，
        // 默认模式维持 /mcp——两侧同一开关、同一字符串来源，不允许漂移。
        return HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint(secured ? SECURED_SERVLET_PATH : "/mcp")
                .build();
    }

    /** 默认模式（开关关）：现行为原样——{@code /mcp} 映射、全工具面。 */
    @Bean
    @ConditionalOnProperty(name = "ai.integration.security.enabled", havingValue = "false", matchIfMissing = true)
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
            HttpServletStreamableServerTransportProvider transportProvider) {
        return new ServletRegistrationBean<>(transportProvider, "/mcp");
    }

    /** 受保护模式：只挂受保护前缀——裸 {@code /mcp} 不存在，鉴权不可旁路。 */
    @Bean
    @ConditionalOnProperty(name = "ai.integration.security.enabled", havingValue = "true")
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> securedMcpServlet(
            HttpServletStreamableServerTransportProvider transportProvider) {
        return new ServletRegistrationBean<>(transportProvider, SECURED_SERVLET_PATH);
    }

    @Bean
    public McpSyncServer mcpServer(HttpServletStreamableServerTransportProvider transportProvider,
                                   List<McpServerFeatures.SyncToolSpecification> toolSpecs,
                                   @Value("${ai.integration.security.enabled:false}") boolean secured) {
        requireReadOnlyHint(toolSpecs);
        return McpServer.sync(transportProvider)
                .serverInfo("ragent-mcp-server", "0.0.1")
                .tools(secured ? securedSurface(toolSpecs) : toolSpecs)
                .build();
    }

    /**
     * 受保护模式工具面：按白名单收敛（写类/出网工具 tools/list 不可见、
     * tools/call 亦被逐工具过滤器 403 兜底）；白名单成员缺失＝装配错误 fail-fast，
     * 不允许"静默缩小面"——验收判据要求白名单逐项在场。
     */
    static List<McpServerFeatures.SyncToolSpecification> securedSurface(
            List<McpServerFeatures.SyncToolSpecification> toolSpecs) {
        Set<String> present = toolSpecs.stream()
                .map(spec -> spec.tool().name())
                .collect(Collectors.toSet());
        Set<String> whitelist = McpDelegatedToolPolicy.registeredTools();
        List<String> missing = whitelist.stream()
                .filter(name -> !present.contains(name))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("受保护模式白名单工具缺失（装配错误）: " + missing);
        }
        return toolSpecs.stream()
                .filter(spec -> whitelist.contains(spec.tool().name()))
                .toList();
    }

    /**
     * 工具必须自报是读还是写：调用方按 readOnlyHint 决定要不要拦下来让用户确认
     */
    static void requireReadOnlyHint(List<McpServerFeatures.SyncToolSpecification> toolSpecs) {
        List<String> undeclared = toolSpecs.stream()
                .filter(spec -> spec.tool().annotations() == null
                        || spec.tool().annotations().readOnlyHint() == null)
                .map(spec -> spec.tool().name())
                .toList();
        if (!undeclared.isEmpty()) {
            throw new IllegalStateException(
                    "以下 MCP 工具未声明 readOnlyHint，请在 annotations 里显式写明只读或写操作: " + undeclared);
        }
    }
}
