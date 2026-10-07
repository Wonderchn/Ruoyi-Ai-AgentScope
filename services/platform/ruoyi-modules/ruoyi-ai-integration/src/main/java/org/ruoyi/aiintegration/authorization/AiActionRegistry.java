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

package org.ruoyi.aiintegration.authorization;

import org.ruoyi.ai.api.action.AiCanonicalAction;
import org.ruoyi.aiintegration.web.P04ErrorCode;
import org.ruoyi.aiintegration.web.P04Exception;

import java.util.Optional;
import java.util.Set;

/**
 * AI canonical 动作 → 平台权限（菜单 perms）的固定映射表（05 §4.2）。
 *
 * <p>权限值与 V4 迁移声明的菜单（menu_id 7100–7113）一一对应：
 * {@code ai:kb:*}、{@code ai:document:*}、{@code ai:conversation:*}、
 * {@code ai:memory:read}、{@code ai:run:read}、{@code ai:run:event:read}。
 *
 * <p><b>未知动作一律拒绝</b>（返回 empty / 抛 {@link P04Exception}），不默认允许；
 * 平台权限与菜单 perms 是精确字符串相等，不做通配（超管的 {@code *:*:*} 不得
 * 产生 AI 资源 ACL 豁免）。
 *
 * @author AI-Integration
 */
public final class AiActionRegistry {

    /**
     * 动作 → 平台权限。表体已移到 {@code ruoyi-ai-api} 的 {@code AiCanonicalAction}：
     * 内嵌后网关与 AI 运行模块在同一进程内对"该动作需要什么权限"必须给出一致答案，
     * 两份拷贝迟早会漂移。本类只保留原有门面，保持既有调用点不变。
     */
    /**
     * @param action AI canonical 动作标识
     * @return 对应的平台权限；未知动作返回 empty（调用方必须拒绝）
     */
    public static Optional<String> permissionOf(String action) {
        return AiCanonicalAction.permissionOf(action);
    }

    /**
     * 取动作对应的平台权限，未知动作直接拒绝。
     *
     * @param action AI canonical 动作标识
     * @return 平台权限
     * @throws P04Exception 未知/缺失动作 → {@link P04ErrorCode#FORBIDDEN}
     */
    public static String requirePermission(String action) {
        return AiCanonicalAction.permissionOf(action)
            .orElseThrow(() -> new P04Exception(P04ErrorCode.FORBIDDEN));
    }

    /**
     * @return 全部已知动作（只读）
     */
    public static Set<String> knownActions() {
        return AiCanonicalAction.knownActions();
    }

    /**
     * 该动作是否<b>额外</b>要求平台管理身份（维护者裁决 A2-ter）。
     *
     * <p>与 {@link #requirePermission(String)} 是<b>两个独立条件</b>：对返回
     * {@code true} 的动作，网关既要求身份显式持有对应 scope，也要求调用方是平台
     * 管理身份；<b>只满足其一必须拒绝</b>。判定表与动作→权限表同源
     * （{@code AiCanonicalAction}），避免两处漂移。
     *
     * @param action AI canonical 动作标识
     * @return 是否额外要求平台管理身份
     */
    public static boolean requiresPlatformAdmin(String action) {
        return AiCanonicalAction.requiresPlatformAdmin(action);
    }

}
