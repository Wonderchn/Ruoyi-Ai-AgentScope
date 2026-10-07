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

package com.nageoffer.ai.ragent.agent.tool;

import com.nageoffer.ai.ragent.framework.integration.SaasBoundaryProperties;
import com.nageoffer.ai.ragent.framework.integration.SaasCapabilityBoundary;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import org.junit.jupiter.api.Tag;

@Tag("dev")
class AgentMcpClientsModeTest {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(AgentMcpClients.class);

    @Test
    void shouldCreateAgentScopeClientOnlyInAgentMode() {
        contexts.withPropertyValues("ragent.engine.type=agent")
                .run(context -> assertThat(context).hasSingleBean(AgentMcpClients.class));
        contexts.withPropertyValues("ragent.engine.type=workflow")
                .run(context -> assertThat(context).doesNotHaveBean(AgentMcpClients.class));
    }

    /**
     * P1.2a 装配护栏：默认关闭下 Bean 必须可构造、可销毁，且<b>一次远端 MCP 都没连</b>。
     *
     * <p>这条覆盖的是"上下文里<b>没有</b> SaasCapabilityBoundary Bean"的最坏装配情形：
     * 漏装配守卫不得被解释成"没有守卫，于是照常连接"，也不得让启动失败——
     * 启动期不连 MCP 是默认状态，真正需要失败的是想用工具的业务请求。
     */
    @Test
    void shouldNotConnectAnyRemoteMcpWhenBoundaryIsAbsentOrClosed() {
        int before = AgentMcpClients.connectAttempts();

        contexts.withPropertyValues("ragent.engine.type=agent")
                .run(context -> {
                    assertThat(context).hasSingleBean(AgentMcpClients.class);
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(AgentMcpClients.connectAttempts())
                            .as("边界 Bean 缺席时不得尝试连接远端 MCP")
                            .isEqualTo(before);
                    AgentMcpClients clients = context.getBean(AgentMcpClients.class);
                    assertThat(clients.get("default")).isNull();
                    assertThatCode(clients::close).doesNotThrowAnyException();
                });

        // 显式关闭态（与产品默认配置同义）同样零连接
        contexts.withPropertyValues("ragent.engine.type=agent")
                .withBean(SaasBoundaryProperties.class,
                        () -> new SaasBoundaryProperties(false, true,
                                new SaasBoundaryProperties.CustomerApi(false), false))
                .withBean(SaasCapabilityBoundary.class,
                        () -> new SaasCapabilityBoundary(new SaasBoundaryProperties(false, true,
                                new SaasBoundaryProperties.CustomerApi(false), false)))
                .run(context -> assertThat(AgentMcpClients.connectAttempts())
                        .as("关闭态下不得尝试连接远端 MCP")
                        .isEqualTo(before));
    }
}
