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

package com.nageoffer.ai.ragent.mcp.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 9099 委托路径逐工具策略的冻结判据（SPEC-9099 §2.1/J3）。
 *
 * <p>零号源：动作名必须精确等于既有的 {@code agent.execute}（漂移会被本测试钉死）；
 * 白名单精确等于 "current_date + bit 只读 5 件"，写类/出网工具不得登记。
 */
@Tag("dev")
class McpDelegatedToolPolicyTest {

    @Test
    @DisplayName("白名单冻结：current_date + bit 只读 5 件，一个不多一个不少")
    void whitelistIsFrozenExactly() {
        assertThat(McpDelegatedToolPolicy.registeredTools()).containsExactly(
                "current_date",
                "search_product",
                "query_order",
                "query_logistics",
                "query_coupons",
                "query_after_sale");
    }

    @Test
    @DisplayName("零号源：动作精确等于 agent.execute（平台 AiCanonicalAction 既有动作，V6 menu 7120）")
    void requiredActionIsExactlyAgentExecute() {
        assertThat(McpDelegatedToolPolicy.ACTION_AGENT_EXECUTE).isEqualTo("agent.execute");
        for (String tool : McpDelegatedToolPolicy.registeredTools()) {
            assertThat(McpDelegatedToolPolicy.requiredActions(tool))
                    .as("tool=%s", tool)
                    .hasValue(Set.of("agent.execute"));
        }
    }

    @Test
    @DisplayName("写类/出网/未知工具一律不登记（未登记=403 默认拒绝）")
    void writeAndNetworkToolsAreNeverRegistered() {
        for (String tool : new String[]{
                "create_order", "pay_order", "cancel_order", "set_cart_item", "change_address",
                "apply_after_sale", "submit_ticket",
                "weather_query", "youcom_search",
                "query_cart",
                "asset_query", "leave_submit", "meeting_room_book", "sales_query", "ticket_query",
                "no_such_tool", ""}) {
            assertThat(McpDelegatedToolPolicy.requiredActions(tool))
                    .as("tool=%s", tool)
                    .isEmpty();
        }
        assertThat(McpDelegatedToolPolicy.requiredActions(null)).isEmpty();
    }
}
