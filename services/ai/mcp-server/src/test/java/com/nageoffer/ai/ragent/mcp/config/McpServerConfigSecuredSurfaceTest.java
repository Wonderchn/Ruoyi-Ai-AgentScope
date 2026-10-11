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

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 受保护模式工具面判据（SPEC-9099 §2.1/J2/J3）：白名单收敛（写类/出网不在场）、
 * 白名单成员缺失装配期 fail-fast（不静默缩小面）。
 */
@Tag("dev")
class McpServerConfigSecuredSurfaceTest {

    @Test
    @DisplayName("按白名单收敛：只留 current_date + bit 只读 5 件，写类/出网被剔除")
    void keepsOnlyWhitelistedTools() {
        List<McpServerFeatures.SyncToolSpecification> all = List.of(
                spec("current_date"),
                spec("pay_order"),
                spec("search_product"),
                spec("weather_query"),
                spec("query_order"),
                spec("query_logistics"),
                spec("query_coupons"),
                spec("query_after_sale"),
                spec("youcom_search"));

        List<String> surface = McpServerConfig.securedSurface(all).stream()
                .map(spec -> spec.tool().name())
                .toList();

        assertThat(surface).containsExactly(
                "current_date", "search_product", "query_order", "query_logistics",
                "query_coupons", "query_after_sale");
    }

    @Test
    @DisplayName("白名单成员缺失（装配错误）：fail-fast 点名缺失工具，绝不静默缩小面")
    void missingWhitelistMemberFailsFast() {
        List<McpServerFeatures.SyncToolSpecification> incomplete = List.of(
                spec("current_date"),
                spec("search_product"),
                spec("query_order"),
                spec("query_logistics"),
                spec("query_coupons"));

        assertThatThrownBy(() -> McpServerConfig.securedSurface(incomplete))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("白名单工具缺失")
                .hasMessageContaining("query_after_sale");
    }

    private McpServerFeatures.SyncToolSpecification spec(String name) {
        Tool tool = Tool.builder()
                .name(name)
                .description("测试工具")
                .inputSchema(new JsonSchema("object", Map.of(), List.of(), false, null, null))
                .annotations(new ToolAnnotations(null, true, false, true, false, null))
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> null)
                .build();
    }
}
