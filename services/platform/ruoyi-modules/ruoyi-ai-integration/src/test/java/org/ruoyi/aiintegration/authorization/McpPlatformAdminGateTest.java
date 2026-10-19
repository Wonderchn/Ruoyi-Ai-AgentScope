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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ai.api.action.AiCanonicalAction;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F12-M1（复核 C3）：MCP 管理面五条动作必须全部通过"平台管理身份"第二道门。
 *
 * <p>只追加本判据，不改既有断言：{@code PLATFORM_ADMIN_ACTIONS} 是
 * {@code AiCanonicalAction} 的从严方向（scope + 平台管理身份同时成立），
 * 与 skill.* 同口径。任何一条 mcp.* 被移出该集合都必须在这里红。
 */
@Tag("dev")
class McpPlatformAdminGateTest {

    @Test
    @DisplayName("mcp.list/read/write/delete/test 均要求平台管理身份")
    void mcpCatalogActionsRequirePlatformAdmin() {
        for (String action : List.of("mcp.list", "mcp.read", "mcp.write", "mcp.delete", "mcp.test")) {
            assertThat(AiCanonicalAction.permissionOf(action))
                    .as("%s 必须有可授予的权限行", action)
                    .isPresent();
            assertThat(AiCanonicalAction.requiresPlatformAdmin(action))
                    .as("%s 必须进平台管理身份第二道门", action)
                    .isTrue();
        }
        assertThat(AiCanonicalAction.requiresPlatformAdmin("kb.list"))
                .as("对照：非目录动作不受第二道门影响")
                .isFalse();
    }
}
