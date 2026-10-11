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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 9099 独立进程·委托凭据路径的逐工具授权冻结映射与白名单（SPEC-9099 §2.1/§7，T0 2026-10-11）。
 *
 * <p><b>零号源原则。</b>映射值只挂<b>既有</b> canonical 动作 {@code agent.execute}
 * （平台侧 {@code AiCanonicalAction} 冻结注册表 → 权限 {@code ai:agent:execute}，
 * V6 迁移 menu 7120；内嵌孪生版 {@code McpToolAccessPolicy} 的读工具同款）。
 * 动作名漂移时平台在线判定端对未知动作一律 403——漂移导致 fail-closed 而非放行，
 * 本类再以测试钉死字符串（见 {@code McpDelegatedToolPolicyTest}）。
 *
 * <p><b>白名单（本期验收集，冻结）</b>={@code current_date}（离线确定性）+
 * bit 只读 5 件（商品搜索/订单查询/物流查询/优惠券查询/售后查询）。写类工具
 * （下单/支付/取消/购物车改/工单/售后提交）与出网工具（天气/YouCom）<b>不登记</b>：
 * 受保护模式工具面按本表白名单装配（{@code McpServerConfig.securedSurface}），
 * 未登记工具一律 403（默认拒绝），无通配豁免。
 */
public final class McpDelegatedToolPolicy {

    /** 读工具的基础能力（与内嵌 MCP 面同一 canonical 动作体系）。 */
    public static final String ACTION_AGENT_EXECUTE = "agent.execute";

    private static final Map<String, Set<String>> REQUIRED_ACTIONS = build();

    private McpDelegatedToolPolicy() {
    }

    private static Map<String, Set<String>> build() {
        Map<String, Set<String>> tools = new LinkedHashMap<>();
        for (String tool : new String[]{
                "current_date",
                "search_product",
                "query_order",
                "query_logistics",
                "query_coupons",
                "query_after_sale"}) {
            tools.put(tool, Set.of(ACTION_AGENT_EXECUTE));
        }
        return Collections.unmodifiableMap(tools);
    }

    /**
     * @param toolName JSON-RPC {@code tools/call} 的工具名
     * @return 该工具要求的 canonical 动作集合；未登记工具（含未知名字、{@code null}）返回
     *         empty=一律拒绝
     */
    static Optional<Set<String>> requiredActions(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(REQUIRED_ACTIONS.get(toolName));
    }

    /** @return 白名单（受保护模式工具面）全集，只读，顺序即声明顺序 */
    public static Set<String> registeredTools() {
        return REQUIRED_ACTIONS.keySet();
    }
}
