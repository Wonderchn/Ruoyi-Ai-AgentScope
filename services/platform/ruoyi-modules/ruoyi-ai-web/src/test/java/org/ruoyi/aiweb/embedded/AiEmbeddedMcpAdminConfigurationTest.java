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

package org.ruoyi.aiweb.embedded;

import com.nageoffer.ai.ragent.agent.dao.mapper.McpMarketMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.McpMarketToolMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.McpToolMapper;
import com.nageoffer.ai.ragent.mcp.admin.AgentMcpAllowListProperties;
import com.nageoffer.ai.ragent.mcp.admin.McpAdminController;
import com.nageoffer.ai.ragent.mcp.admin.McpAdminServiceImpl;
import com.nageoffer.ai.ragent.mcp.admin.McpEndpointAllowList;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * F12-M1：MCP 管理面装配的激活判据（与 {@code AiEmbeddedAgentSkillActivationTest} 同形）。
 *
 * <p>三道门（{@code ai.integration.enabled} / {@code transport=local} / {@code p2.enabled}）
 * 任一关闭时该面必须整体消失——不装半个面，也不在关闭时要求协作对象在场。
 */
@Tag("dev")
class AiEmbeddedMcpAdminConfigurationTest {

    private final ApplicationContextRunner configurations = new ApplicationContextRunner()
            .withUserConfiguration(AiEmbeddedMcpAdminConfiguration.class)
            .withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=local",
                    "p2.enabled=true");

    private ApplicationContextRunner collaborators() {
        return configurations
                .withBean(com.fasterxml.jackson.databind.ObjectMapper.class,
                        com.fasterxml.jackson.databind.ObjectMapper::new)
                .withBean(McpMarketMapper.class, () -> mock(McpMarketMapper.class))
                .withBean(McpMarketToolMapper.class, () -> mock(McpMarketToolMapper.class))
                .withBean(McpToolMapper.class, () -> mock(McpToolMapper.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(DeliveryPermits.class, () -> mock(DeliveryPermits.class));
    }

    @Test
    void mcpAdminSurfaceIsAssembledExactlyOnce() {
        collaborators().run(context -> {
            assertThat(context).hasNotFailed();
            for (Class<?> type : new Class<?>[]{McpAdminController.class, McpAdminServiceImpl.class,
                    McpEndpointAllowList.class, AgentMcpAllowListProperties.class}) {
                assertThat(context.getBeansOfType(type)).hasSize(1);
                assertThat(context.getBean(type).getClass()).isEqualTo(type);
            }
        });
    }

    @Test
    void everyGateClosesTheSurfaceWithoutRequiringCollaborators() {
        for (String property : new String[]{"ai.integration.enabled=false", "ai.integration.transport=http",
                "p2.enabled=false"}) {
            configurations.withPropertyValues(property).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(McpAdminController.class)
                        .doesNotHaveBean(McpAdminServiceImpl.class);
            });
        }
    }
}
