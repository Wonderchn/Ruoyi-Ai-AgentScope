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

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * MCP Server 配置类
 */
@Configuration
public class McpServerConfig {

    @Bean
    public HttpServletStreamableServerTransportProvider transportProvider() {
        return HttpServletStreamableServerTransportProvider.builder()
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
            HttpServletStreamableServerTransportProvider transportProvider) {
        return new ServletRegistrationBean<>(transportProvider, "/mcp");
    }

    /**
     * /mcp 鉴权过滤器注册（R12 卡1 · F-W9-1）。
     *
     * <p>{@code /mcp} 是 servlet 映射、不经过 Spring MVC，Sa-Token 的拦截器对它是盲区 ——
     * 鉴权必须落在 servlet 链上（判定逻辑与拒绝形状见 {@link McpAuthFilter}）。注册与
     * {@code /mcp} servlet 同门控（外层 {@code ai.integration.enabled} + 内层
     * {@code ragent.mcp.server.enabled}）：开关关时端点与过滤器一起不存在。
     *
     * <p>只接管 {@code REQUEST} 分派（streamable-http 的 SSE/异步收尾不受影响）；
     * 顺序排在 requestId 过滤器（{@code Integer.MIN_VALUE + 50}）之后，
     * 拒绝响应仍带请求标识。
     */
    @Bean
    public FilterRegistrationBean<McpAuthFilter> mcpAuthFilterRegistration(
            @Value("${ai.integration.authorization.service-credential:}") String serviceCredential) {
        FilterRegistrationBean<McpAuthFilter> registration =
                new FilterRegistrationBean<>(new McpAuthFilter(serviceCredential));
        registration.addUrlPatterns("/mcp", "/mcp/*");
        registration.setName("mcpAuthFilter");
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.setOrder(Integer.MIN_VALUE + 150);
        return registration;
    }

    /**
     * /mcp 逐工具授权过滤器注册（F12-A1 · 卡1 残留#1）。
     *
     * <p>认证之后、进入 /mcp servlet 之前，登录令牌路径的 {@code tools/call} 按
     * {@link McpToolAccessPolicy} 的冻结映射逐工具判定（判定与拒绝形状见
     * {@link McpToolAuthzFilter}）。与鉴权过滤器同门控、同 URL 模式、只接管
     * {@code REQUEST}；顺序排在鉴权过滤器（{@code Integer.MIN_VALUE + 150}）<b>之后</b>，
     * 依赖它写入的 {@link McpAuthFilter#ATTR_AUTH_MODE} 交接认证结果。
     *
     * <p>登录令牌 → 身份 scope 的权威事实经 {@link McpLoginTokenScopes} 注入；
     * 未装配实现时按空集处理（登录令牌路径默认拒绝，服务凭证路径不受影响）。
     */
    @Bean
    public FilterRegistrationBean<McpToolAuthzFilter> mcpToolAuthzFilterRegistration(
            ObjectProvider<McpLoginTokenScopes> loginTokenScopes) {
        Function<String, Set<String>> scopesOfToken = token -> {
            McpLoginTokenScopes source = loginTokenScopes.getIfAvailable();
            return source == null ? Set.of() : source.scopesOf(token);
        };
        FilterRegistrationBean<McpToolAuthzFilter> registration =
                new FilterRegistrationBean<>(new McpToolAuthzFilter(scopesOfToken));
        registration.addUrlPatterns("/mcp", "/mcp/*");
        registration.setName("mcpToolAuthzFilter");
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.setOrder(Integer.MIN_VALUE + 200);
        return registration;
    }

    @Bean
    public McpSyncServer mcpServer(HttpServletStreamableServerTransportProvider transportProvider,
                                   List<McpServerFeatures.SyncToolSpecification> toolSpecs) {
        requireReadOnlyHint(toolSpecs);
        return McpServer.sync(transportProvider)
                .serverInfo("ragent-mcp-server", "0.0.1")
                .tools(toolSpecs)
                .build();
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
