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

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 受控 MCP 端点 allow-list（brief §2.4）。
 *
 * <p><b>匹配口径</b>：scheme + host + port 精确匹配（host 大小写不敏感；
 * 端口缺省按 scheme 补 80/443）。路径、查询串不参与匹配——批准的是<b>目标</b>，
 * 不是某一条 URL。
 *
 * <p><b>拒绝面</b>：{@code file:}/{@code javascript:}/无 host/相对 URI 一律不放行；
 * 构造期遇到非 HTTP(S) 或无法解析的条目直接抛 {@link IllegalArgumentException}
 * （配置错误必须响亮失败，而不是静默忽略一个"看起来配置了"的目标）。
 *
 * <p><b>调用时序</b>：调用方必须在建立任何连接之前调用 {@link #requireAllowed(URI)}；
 * 刷新/连接测试因此对未批准目标<b>零网络调用</b>。连接器本身还禁用自动重定向
 * （见 {@link McpRemoteCatalogClient}），两条一起覆盖"重定向到非白名单主机"。
 */
@Component
public class McpEndpointAllowList {

    private final Set<String> approved;

    public McpEndpointAllowList(AgentMcpAllowListProperties properties) {
        this.approved = parse(properties == null ? List.of() : properties.getEndpoints());
    }

    /** 目标是否在批准清单内（null / 非 HTTP(S) / 无 host 一律 false）。 */
    public boolean isAllowed(URI uri) {
        String key = keyOf(uri);
        return key != null && approved.contains(key);
    }

    /**
     * 未批准目标抛 {@link McpEndpointNotAllowedException}（413/403 由控制器映射）。
     */
    public void requireAllowed(URI uri) {
        if (!isAllowed(uri)) {
            throw new McpEndpointNotAllowedException();
        }
    }

    private static Set<String> parse(List<String> endpoints) {
        Set<String> keys = new LinkedHashSet<>();
        if (endpoints == null) {
            return keys;
        }
        for (String endpoint : endpoints) {
            if (endpoint == null || endpoint.isBlank()) {
                continue;
            }
            URI uri;
            try {
                uri = URI.create(endpoint.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("invalid MCP allow-list endpoint", e);
            }
            String key = keyOf(uri);
            if (key == null) {
                throw new IllegalArgumentException("MCP allow-list endpoint must be http(s) with a host");
            }
            keys.add(key);
        }
        return Set.copyOf(keys);
    }

    /**
     * {@code scheme://host:port}；非 HTTP(S)、无 host、端口非法返回 null。
     */
    private static String keyOf(URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) {
            return null;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return null;
        }
        int port = uri.getPort();
        if (port < 0) {
            port = "https".equals(scheme) ? 443 : 80;
        }
        if (port < 1 || port > 65535) {
            return null;
        }
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }
}
