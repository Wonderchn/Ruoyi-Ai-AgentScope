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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F12-M1 受控端点 allow-list 判据：fail-closed、scheme+host+port 精确匹配、
 * 拒绝非 HTTP 协议。
 */
@Tag("dev")
class McpEndpointAllowListTest {

    private static McpEndpointAllowList allowList(String... endpoints) {
        AgentMcpAllowListProperties properties = new AgentMcpAllowListProperties();
        properties.setEndpoints(List.of(endpoints));
        return new McpEndpointAllowList(properties);
    }

    @Test
    @DisplayName("默认空清单拒绝一切目标（fail-closed）")
    void emptyAllowListRejectsEverything() {
        McpEndpointAllowList allowList = allowList();

        assertThat(allowList.isAllowed(URI.create("https://example.com/mcp"))).isFalse();
        assertThat(allowList.isAllowed(URI.create("http://127.0.0.1:8080/mcp"))).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> allowList.requireAllowed(URI.create("https://example.com/mcp")))
                .isInstanceOf(McpEndpointNotAllowedException.class)
                .satisfies(e -> assertThat(((McpEndpointNotAllowedException) e).errorCode())
                        .isEqualTo("MCP_ENDPOINT_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("scheme + host + port 精确匹配；host 大小写不敏感；缺省端口按 scheme 补 80/443")
    void schemeHostAndPortAreMatchedExactly() {
        McpEndpointAllowList allowList = allowList("https://Example.COM", "http://127.0.0.1:18080");

        assertThat(allowList.isAllowed(URI.create("https://example.com/mcp"))).isTrue();
        assertThat(allowList.isAllowed(URI.create("https://example.com:443/mcp"))).isTrue();
        assertThat(allowList.isAllowed(URI.create("http://example.com/mcp")))
                .as("scheme 不同即不匹配")
                .isFalse();
        assertThat(allowList.isAllowed(URI.create("https://example.com:8443/mcp")))
                .as("port 不同即不匹配")
                .isFalse();
        assertThat(allowList.isAllowed(URI.create("http://127.0.0.1:18080/mcp"))).isTrue();
        assertThat(allowList.isAllowed(URI.create("http://127.0.0.1:18081/mcp"))).isFalse();
        assertThat(allowList.isAllowed(URI.create("http://127.0.0.1:80/mcp")))
                .as("条目显式 18080 时缺省 80 不能被当成同一目标")
                .isFalse();
    }

    @Test
    @DisplayName("file:/javascript: 等非 HTTP 协议一律拒绝（包括空 host 与相对 URI）")
    void nonHttpSchemesAreRejected() {
        McpEndpointAllowList allowList = allowList("https://example.com");

        assertThat(allowList.isAllowed(URI.create("file:///etc/passwd"))).isFalse();
        assertThat(allowList.isAllowed(URI.create("javascript:alert(1)"))).isFalse();
        assertThat(allowList.isAllowed(URI.create("ftp://example.com/mcp"))).isFalse();
        assertThat(allowList.isAllowed(null)).isFalse();
    }

    @Test
    @DisplayName("非法 allow-list 条目在构造期响亮失败（不静默忽略）")
    void invalidConfiguredEndpointFailsClosedAtConstruction() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> allowList("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> allowList("not a uri"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
