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

import java.util.Set;

/**
 * /mcp 登录令牌 → 平台权威 scope 集合的解析 SPI（F12-A1）。
 *
 * <p><b>为什么是 SPI 而不是在本模块里直接查库。</b>{@code ruoyi-ai-agent} 是中立的 AI 模块
 * （不得反向依赖 platform 身份/权限实现）；"这个登录令牌的身份当前持有哪些
 * {@code ai:*} 权限"属于 platform 侧的权威事实，由装配方注入实现（生产实现见
 * {@code org.ruoyi.aiidentity.RuoYiMcpLoginTokenScopes}，口径与
 * {@code LocalAiIdentityPort}/{@code RuoYiPlatformIdentitySource} 同源：
 * 真实表、启用角色、精确字符串、无 {@code *:*:*} 豁免）。
 *
 * <p><b>fail-closed 契约。</b>解析不出身份、成员停用/删除、租户或套餐不可用、
 * 无权限，<b>一律返回空集</b>；调用方（{@link McpToolAuthzFilter}）把空集当
 * "无任何授权"拒绝，绝不回落到放行。实现方不得返回 null（返回 null 视同空集）。
 */
@FunctionalInterface
public interface McpLoginTokenScopes {

    /**
     * @param token {@code Authorization: Bearer} 中的登录令牌原文（认证过滤器已判定有效）
     * @return 该身份当前持有的权限 scope（形如 {@code ai:agent:execute}；
     *         与 {@code AiExecutionFacts.scopes} 同义）；无法解析 → 空集
     */
    Set<String> scopesOf(String token);
}
