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

import org.ruoyi.ai.api.action.AiCanonicalAction;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * /mcp 登录令牌路径的<b>逐工具授权冻结映射</b>（R12 卡1 残留#1 · F12-A1）。
 *
 * <p><b>为什么需要这张表。</b>卡1 的 {@link McpAuthFilter} 只做认证：任意有效登录令牌
 * 都能到达内嵌 /mcp 的全部 10 个工具 —— 认证（authN）不等于授权（authZ）。
 * 本表把"某工具在登录令牌路径上要求什么能力"冻结成显式清单，供
 * {@link McpToolAuthzFilter} 在逐工具调用请求上判定。
 *
 * <p><b>零号源原则。</b>映射的值只用<b>既有</b> canonical 动作名（{@code agent.execute} /
 * {@code tool.sandbox.write}，见 {@link AiCanonicalAction} 的冻结注册表）：
 * 不新造权限行、菜单号、迁移；类初始化时逐个自检动作确实在注册表内，映射写错
 * 直接启动期炸掉（fail-fast），不会静默降级成"永远拒绝"或"永远放行"。
 *
 * <p><b>判定语义。</b>
 * <ul>
 *   <li>读工具（readOnlyHint=true 的 7 个）要求 {@code agent.execute}；
 *       写工具（3 个：{@code asset_renewal_submit}/{@code leave_submit}/
 *       {@code meeting_room_book}）同时要求 {@code agent.execute} 与
 *       {@code tool.sandbox.write}（写侧既有能力，与工具自身的 readOnlyHint 注解对应）；</li>
 *   <li><b>不在本表的工具 = 登录令牌一律拒绝</b>（默认拒绝 / 服务凭证专属）；
 *       条件注册的 {@code youcom_search} 本波也刻意不登记 —— 外部付费检索是否向
 *       登录令牌开放属于 B 面真实接入的语义裁定，不在机制级证明内；</li>
 *   <li>空授权为空：身份 scope 集合为空 ⇒ 全部工具 403（无通配豁免，
 *       与 {@code AiCanonicalAction} 的"精确字符串相等"口径一致）。</li>
 * </ul>
 *
 * <p><b>口径边界（D3）。</b>本表是<b>机制级</b>证明：10 个演示工具（不落库的演示实现）
 * 按读/写语义挂到既有能力名上；真实工具接入时"工具 ↔ 权限"的业务映射是 B 面
 * 独立裁定，不在本表外扩。服务凭证路径不经本表（见 {@link McpToolAuthzFilter}）。
 */
final class McpToolAccessPolicy {

    /** 所有工具调用的基础能力（与 /agent 网关面同一 canonical 动作体系）。 */
    static final String ACTION_AGENT_EXECUTE = "agent.execute";

    /** 写类型工具追加要求的能力（既有写侧动作，对应工具自身的 WRITE 注解）。 */
    static final String ACTION_TOOL_SANDBOX_WRITE = "tool.sandbox.write";

    private static final Map<String, Set<String>> REQUIRED_ACTIONS = build();

    private McpToolAccessPolicy() {
    }

    private static Map<String, Set<String>> build() {
        Map<String, Set<String>> tools = new LinkedHashMap<>();
        // 读工具（readOnlyHint=true）
        for (String tool : new String[]{
                "current_date",
                "weather_query",
                "asset_query",
                "leave_query",
                "meeting_room_query",
                "ticket_query",
                "sales_query"}) {
            tools.put(tool, actions(ACTION_AGENT_EXECUTE));
        }
        // 写工具（readOnlyHint=false，产生真实业务副作用）
        for (String tool : new String[]{
                "asset_renewal_submit",
                "leave_submit",
                "meeting_room_book"}) {
            tools.put(tool, actions(ACTION_AGENT_EXECUTE, ACTION_TOOL_SANDBOX_WRITE));
        }
        for (Map.Entry<String, Set<String>> entry : tools.entrySet()) {
            for (String action : entry.getValue()) {
                if (AiCanonicalAction.permissionOf(action).isEmpty()) {
                    // 映射只能引用既有动作；引用未登记动作说明映射本身写错了
                    throw new IllegalStateException("MCP tool mapping references unknown action: "
                            + action + " (tool=" + entry.getKey() + ")");
                }
            }
        }
        return Collections.unmodifiableMap(tools);
    }

    private static Set<String> actions(String... values) {
        Set<String> set = new LinkedHashSet<>();
        Collections.addAll(set, values);
        return Collections.unmodifiableSet(set);
    }

    /**
     * @param toolName JSON-RPC {@code tools/call} 的工具名
     * @return 该工具在登录令牌路径上要求的 canonical 动作集合；
     *         未登记工具（含未知名字）返回 empty = 一律拒绝
     */
    static Optional<Set<String>> requiredActions(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(REQUIRED_ACTIONS.get(toolName));
    }

    /** @return 已登记的（登录令牌路径可达的）工具名全集，只读 */
    static Set<String> registeredTools() {
        return REQUIRED_ACTIONS.keySet();
    }
}
