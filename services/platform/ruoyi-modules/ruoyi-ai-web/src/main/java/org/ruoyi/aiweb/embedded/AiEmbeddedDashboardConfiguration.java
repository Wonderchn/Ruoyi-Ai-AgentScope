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

import com.nageoffer.ai.ragent.admin.controller.DashboardController;
import com.nageoffer.ai.ragent.admin.service.impl.DashboardServiceImpl;
import com.nageoffer.ai.ragent.agent.admin.AgentDashboardReader;
import com.nageoffer.ai.ragent.agent.admin.AgentDashboardService;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Tenant-scoped Dashboard reads, enabled only after RW-23-R1/R2 isolation. */
@AutoConfiguration
@ConditionalOnEmbeddedLocal
@ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
@Import(DashboardController.class)
public class AiEmbeddedDashboardConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEmbeddedLocal
    @ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "ragent.engine", name = "type", havingValue = "workflow", matchIfMissing = true)
    @Import(DashboardServiceImpl.class)
    static class Workflow { }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEmbeddedLocal
    @ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
    @ConditionalOnAgentEngine
    @Import({AgentDashboardReader.class, AgentDashboardService.class})
    static class Agent { }
}
