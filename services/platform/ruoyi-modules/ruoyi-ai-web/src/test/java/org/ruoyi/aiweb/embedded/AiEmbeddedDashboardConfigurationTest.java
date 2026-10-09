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
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardOverviewVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardPerformanceVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardTrendsVO;
import com.nageoffer.ai.ragent.admin.service.DashboardService;
import com.nageoffer.ai.ragent.admin.service.impl.DashboardServiceImpl;
import com.nageoffer.ai.ragent.agent.admin.AgentDashboardReader;
import com.nageoffer.ai.ragent.agent.admin.AgentDashboardService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceRunMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicit assembly gates and inner authorization; HTTP controls are in LocalAdminRouteDispatchTest. */
@Tag("dev")
class AiEmbeddedDashboardConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiEmbeddedDashboardConfiguration.class)
            .withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=local", "p2.enabled=true")
            .withBean(UserMapper.class, () -> mock(UserMapper.class))
            .withBean(ConversationMapper.class, () -> mock(ConversationMapper.class))
            .withBean(ConversationMessageMapper.class, () -> mock(ConversationMessageMapper.class))
            .withBean(RagTraceRunMapper.class, () -> mock(RagTraceRunMapper.class))
            .withBean(NamedParameterJdbcTemplate.class, () -> mock(NamedParameterJdbcTemplate.class));

    @AfterEach void clear() { PrincipalContext.clear(); }

    @Test void workflowDefaultAssemblesTheRealTenantScopedImplementation() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(DashboardController.class)
                    .hasSingleBean(DashboardService.class).doesNotHaveBean(AgentDashboardReader.class);
            assertThat(context.getBean(DashboardService.class)).isInstanceOf(DashboardServiceImpl.class);
        });
    }

    @Test void agentSelectsItsRealReaderAndCacheWithoutTheWorkflowImplementation() {
        runner.withPropertyValues("ragent.engine.type=agent").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(DashboardController.class)
                    .hasSingleBean(DashboardService.class).hasSingleBean(AgentDashboardReader.class)
                    .doesNotHaveBean(DashboardServiceImpl.class);
            assertThat(context.getBean(DashboardService.class)).isInstanceOf(AgentDashboardService.class);
        });
    }

    @Test void eachGateOffRemovesTheWholeSurfaceAndBothImplementations() {
        for (String closed : Set.of("ai.integration.enabled=false", "ai.integration.transport=http", "p2.enabled=false")) {
            runner.withPropertyValues(closed).run(context -> assertThat(context).hasNotFailed()
                    .doesNotHaveBean(DashboardController.class).doesNotHaveBean(DashboardService.class)
                    .doesNotHaveBean(AgentDashboardReader.class));
        }
    }

    @Test void eachNestedAssemblyAlsoHonorsTheGatesWhenDiscoveredIndependently() {
        for (Class<?> assembly : Set.of(AiEmbeddedDashboardConfiguration.Workflow.class,
                AiEmbeddedDashboardConfiguration.Agent.class)) {
            String engine = assembly == AiEmbeddedDashboardConfiguration.Workflow.class ? "workflow" : "agent";
            var independent = new ApplicationContextRunner().withUserConfiguration(assembly)
                    .withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=local",
                            "p2.enabled=true", "ragent.engine.type=" + engine)
                    .withBean(UserMapper.class, () -> mock(UserMapper.class))
                    .withBean(ConversationMapper.class, () -> mock(ConversationMapper.class))
                    .withBean(ConversationMessageMapper.class, () -> mock(ConversationMessageMapper.class))
                    .withBean(RagTraceRunMapper.class, () -> mock(RagTraceRunMapper.class))
                    .withBean(NamedParameterJdbcTemplate.class, () -> mock(NamedParameterJdbcTemplate.class));
            independent.run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(DashboardService.class);
                assertThat(context.getBean(DashboardService.class)).isInstanceOf(
                        engine.equals("workflow") ? DashboardServiceImpl.class : AgentDashboardService.class);
            });
            for (String closed : Set.of("ai.integration.enabled=false", "ai.integration.transport=http", "p2.enabled=false")) {
                independent.withPropertyValues(closed)
                        .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(DashboardService.class)
                                .doesNotHaveBean(AgentDashboardReader.class));
            }
        }
    }

    @Test void missingOrWrongScopeFailsBeforeEveryBusinessCall() {
        var service = mock(DashboardService.class);
        var controller = new DashboardController(service);
        assertThatThrownBy(() -> controller.overview("24h")).isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> controller.performance("24h")).isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> controller.trends("messages", "24h", "hour")).isInstanceOf(ClientException.class);
        for (String wrong : Set.of("config.read", "run.events", "kb.read")) {
            PrincipalContext.set(principal(Set.of(wrong)));
            assertThatThrownBy(() -> controller.overview("24h")).isInstanceOf(P04AiException.class);
            assertThatThrownBy(() -> controller.performance("24h")).isInstanceOf(P04AiException.class);
            assertThatThrownBy(() -> controller.trends("messages", "24h", "hour")).isInstanceOf(P04AiException.class);
        }
        verifyNoInteractions(service);
    }

    @Test void runReadScopeDelegatesEveryParameterAndKeepsTheIntegerEnvelope() {
        var service = mock(DashboardService.class);
        var overview = DashboardOverviewVO.builder().engine("r10-overview").build();
        var performance = DashboardPerformanceVO.builder().engine("r10-performance").build();
        var trends = DashboardTrendsVO.builder().metric("r10-trends").build();
        when(service.loadOverview("7d")).thenReturn(overview);
        when(service.loadPerformance("30d")).thenReturn(performance);
        when(service.loadTrends("messages", "7d", "day")).thenReturn(trends);
        PrincipalContext.set(principal(Set.of("run.get")));
        var controller = new DashboardController(service);
        assertThat(controller.overview("7d").getBody().code()).isEqualTo(200);
        assertThat(controller.overview("7d").getBody().data()).isSameAs(overview);
        assertThat(controller.performance("30d").getBody().data()).isSameAs(performance);
        assertThat(controller.trends("messages", "7d", "day").getBody().data()).isSameAs(trends);
    }

    @Test void eachGatewayActionIsAcceptedByItsMatchingInnerHandlerAndRemainsRunRead() throws Exception {
        var field = org.ruoyi.aiintegration.web.AiGatewayController.class.getDeclaredField("ROUTES");
        field.setAccessible(true);
        var routes = (java.util.List<?>) field.get(null);
        var actions = new java.util.HashMap<String, String>();
        for (Object route : routes) {
            var method = route.getClass().getDeclaredMethod("method");
            var pattern = route.getClass().getDeclaredMethod("pattern");
            var action = route.getClass().getDeclaredMethod("action");
            method.setAccessible(true); pattern.setAccessible(true); action.setAccessible(true);
            String path = (String) pattern.invoke(route);
            if (path.startsWith("/dashboard/")) {
                assertThat(method.invoke(route)).isEqualTo("GET");
                assertThat(actions.put(path, (String) action.invoke(route))).isNull();
            }
        }
        assertThat(actions).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                "/dashboard/overview", "run.get", "/dashboard/performance", "run.get",
                "/dashboard/trends", "run.get"));
        var service = mock(DashboardService.class);
        var controller = new DashboardController(service);
        PrincipalContext.set(principal(Set.of(actions.get("/dashboard/overview"))));
        assertThat(controller.overview("24h").getBody().code()).isEqualTo(200);
        PrincipalContext.set(principal(Set.of(actions.get("/dashboard/performance"))));
        assertThat(controller.performance("24h").getBody().code()).isEqualTo(200);
        PrincipalContext.set(principal(Set.of(actions.get("/dashboard/trends"))));
        assertThat(controller.trends("messages", "24h", "hour").getBody().code()).isEqualTo(200);
        verify(service).loadOverview("24h"); verify(service).loadPerformance("24h");
        verify(service).loadTrends("messages", "24h", "hour");
    }

    private static ExecutionPrincipal principal(Set<String> scopes) {
        return new ExecutionPrincipal("T-A", "1", "platform:T-A:1", 1, 1, scopes,
                "r10-dashboard", "test", 1, Long.MAX_VALUE);
    }
}
