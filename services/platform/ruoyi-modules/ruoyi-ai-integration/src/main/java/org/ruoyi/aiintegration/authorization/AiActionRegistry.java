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

import org.ruoyi.aiintegration.web.P04ErrorCode;
import org.ruoyi.aiintegration.web.P04Exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
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
     * 动作 → 平台权限。固定映射，运行期不可扩展。
     */
    private static final Map<String, String> ACTIONS = buildActions();

    private AiActionRegistry() {
    }

    private static Map<String, String> buildActions() {
        Map<String, String> actions = new LinkedHashMap<>();
        actions.put("kb.list", "ai:kb:list");
        actions.put("kb.read", "ai:kb:read");
        actions.put("kb.write", "ai:kb:write");
        actions.put("kb.delete", "ai:kb:delete");
        actions.put("kb.acl.manage", "ai:kb:acl");
        actions.put("kb.retrieve", "ai:kb:retrieve");
        actions.put("document.read", "ai:document:read");
        actions.put("document.download", "ai:document:download");
        actions.put("conversation.read", "ai:conversation:read");
        actions.put("conversation.export", "ai:conversation:export");
        actions.put("memory.read", "ai:memory:read");
        actions.put("run.get", "ai:run:read");
        actions.put("run.events", "ai:run:event:read");
        actions.put("run.submit", "ai:run:submit");
        actions.put("run.cancel", "ai:run:cancel");
        actions.put("run.resume", "ai:run:resume");
        actions.put("run.stream", "ai:run:stream");
        actions.put("document.upload", "ai:document:upload");
        actions.put("document.ingest", "ai:document:ingest");
        actions.put("document.list", "ai:document:read");
        actions.put("agent.execute", "ai:agent:execute");
        actions.put("run.approve", "ai:run:approve");
        actions.put("run.reconcile", "ai:run:reconcile");
        actions.put("tool.sandbox.write", "ai:tool:sandbox:write");
        return Collections.unmodifiableMap(actions);
    }

    /**
     * @param action AI canonical 动作标识
     * @return 对应的平台权限；未知动作返回 empty（调用方必须拒绝）
     */
    public static Optional<String> permissionOf(String action) {
        if (action == null || action.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(ACTIONS.get(action));
    }

    /**
     * 取动作对应的平台权限，未知动作直接拒绝。
     *
     * @param action AI canonical 动作标识
     * @return 平台权限
     * @throws P04Exception 未知/缺失动作 → {@link P04ErrorCode#FORBIDDEN}
     */
    public static String requirePermission(String action) {
        return permissionOf(action).orElseThrow(() -> new P04Exception(P04ErrorCode.FORBIDDEN));
    }

    /**
     * @return 全部已知动作（只读）
     */
    public static Set<String> knownActions() {
        return ACTIONS.keySet();
    }

}
