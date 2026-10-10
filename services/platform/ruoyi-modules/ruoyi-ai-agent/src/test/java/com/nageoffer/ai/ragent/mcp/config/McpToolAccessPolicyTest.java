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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ai.api.action.AiCanonicalAction;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F12-A1：逐工具授权冻结映射的判据。
 *
 * <p>钉三件事：①登记的工具集合<b>精确</b>等于 10 个无条件工具（多一个少一个都红，
 * 与 {@code McpEmbeddedServerConfigurationTest} 的清单互证）；②每个映射值都只引用
 * {@link AiCanonicalAction} 既有动作（零新造号源）；③未登记工具返回 empty =
 * 默认拒绝（登录令牌路径不可达，服务凭证专属）。
 */
@Tag("dev")
class McpToolAccessPolicyTest {

    private static final Set<String> READ_TOOLS = Set.of(
            "current_date", "weather_query", "asset_query", "leave_query",
            "meeting_room_query", "ticket_query", "sales_query");

    private static final Set<String> WRITE_TOOLS = Set.of(
            "asset_renewal_submit", "leave_submit", "meeting_room_book");

    @Test
    @DisplayName("登记集合精确等于 10 个无条件工具；条件工具 youcom_search 不登记（登录令牌不可达）")
    void registeredToolsAreExactlyTheTenUnconditionalTools() {
        assertThat(McpToolAccessPolicy.registeredTools())
                .containsExactlyInAnyOrderElementsOf(concat(READ_TOOLS, WRITE_TOOLS))
                .doesNotContain("youcom_search");
    }

    @Test
    @DisplayName("读工具只要求 agent.execute；写工具追加 tool.sandbox.write")
    void requiredActionsMatchReadWriteSemantics() {
        for (String tool : READ_TOOLS) {
            assertThat(McpToolAccessPolicy.requiredActions(tool)).as(tool)
                    .hasValue(Set.of(McpToolAccessPolicy.ACTION_AGENT_EXECUTE));
        }
        for (String tool : WRITE_TOOLS) {
            assertThat(McpToolAccessPolicy.requiredActions(tool)).as(tool)
                    .hasValue(Set.of(McpToolAccessPolicy.ACTION_AGENT_EXECUTE,
                            McpToolAccessPolicy.ACTION_TOOL_SANDBOX_WRITE));
        }
    }

    @Test
    @DisplayName("零新造号源：每个映射动作都能在 AiCanonicalAction 冻结注册表里换算成既有权限")
    void everyMappedActionExistsInFrozenRegistry() {
        assertThat(McpToolAccessPolicy.registeredTools()).allSatisfy(tool -> {
            Set<String> actions = McpToolAccessPolicy.requiredActions(tool)
                    .orElseThrow(() -> new AssertionError("已登记工具必须给出动作集合: " + tool));
            assertThat(actions).allSatisfy(action ->
                    assertThat(AiCanonicalAction.permissionOf(action))
                            .as("action=%s 必须存在于既有 canonical 注册表", action)
                            .isPresent());
        });
    }

    @Test
    @DisplayName("未登记/未知工具默认拒绝（empty），空名同样拒绝")
    void unmappedToolsAreDeniedByDefault() {
        assertThat(McpToolAccessPolicy.requiredActions("unknown_tool")).isEmpty();
        assertThat(McpToolAccessPolicy.requiredActions("youcom_search")).isEmpty();
        assertThat(McpToolAccessPolicy.requiredActions("")).isEmpty();
        assertThat(McpToolAccessPolicy.requiredActions(null)).isEmpty();
    }

    @Test
    @DisplayName("映射不可变：外部 Set 视图不得能改到冻结表")
    void registryIsImmutable() {
        Optional<Set<String>> actions = McpToolAccessPolicy.requiredActions("current_date");
        assertThat(actions).isPresent();
        assertThatThrownBy(() -> actions.get().add("agent.delete"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static Set<String> concat(Set<String> left, Set<String> right) {
        java.util.HashSet<String> all = new java.util.HashSet<>(left);
        all.addAll(right);
        return all;
    }
}
