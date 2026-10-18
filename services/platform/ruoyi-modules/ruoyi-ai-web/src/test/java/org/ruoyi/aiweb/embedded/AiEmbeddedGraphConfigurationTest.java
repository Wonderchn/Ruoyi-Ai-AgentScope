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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.rag.controller.GraphController;
import com.nageoffer.ai.ragent.rag.core.graph.GraphProjectionRepository;
import com.nageoffer.ai.ragent.rag.core.graph.GraphProjectionService;
import com.nageoffer.ai.ragent.rag.core.graph.GraphQueryService;
import com.nageoffer.ai.ragent.rag.core.graph.GraphScopeResolver;
import com.nageoffer.ai.ragent.rag.core.graph.GraphStatusService;
import com.nageoffer.ai.ragent.rag.core.graph.JdbcGraphProjectionRepository;
import com.nageoffer.ai.ragent.rag.core.graph.LightRagClient;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * R13：图谱管理面装配门与依赖闭包。
 *
 * <p>三道门（{@code ai.integration.enabled} / {@code transport=local} / {@code p2.enabled}）
 * 任一关闭时整个面消失；{@code rag.graph.type=lightrag} 才注册 LightRag 客户端，
 * none 形态仍保留读投影/状态面（零外呼）。
 */
@Tag("dev")
class AiEmbeddedGraphConfigurationTest {

    private final ApplicationContextRunner configurations = new ApplicationContextRunner()
            .withUserConfiguration(AiEmbeddedGraphConfiguration.class)
            .withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=local",
                    "p2.enabled=true", "rag.graph.type=none");

    private ApplicationContextRunner collaborators() {
        return configurations
                .withBean(NamedParameterJdbcTemplate.class, () -> mock(NamedParameterJdbcTemplate.class))
                .withBean(AiResourceAuthorizationService.class, () -> mock(AiResourceAuthorizationService.class))
                // 协作件替身仍会执行类上的 @Autowired 方法注入（required），补齐平台判定端口。
                .withBean(com.nageoffer.ai.ragent.framework.security.AuthorizationChecker.class,
                        () -> mock(com.nageoffer.ai.ragent.framework.security.AuthorizationChecker.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean("syncHttpClient", OkHttpClient.class, OkHttpClient::new);
    }

    @Test
    @DisplayName("none 形态：读投影/状态/重建服务齐备，但不注册 LightRag 客户端")
    void noneBackendAssemblesReadPathWithoutClient() {
        collaborators().run(context -> {
            assertThat(context).hasNotFailed();
            for (Class<?> type : new Class<?>[]{GraphController.class, GraphQueryService.class,
                    GraphScopeResolver.class, GraphProjectionService.class, GraphStatusService.class,
                    JdbcGraphProjectionRepository.class}) {
                assertThat(context.getBeansOfType(type)).hasSize(1);
                assertThat(context.getBean(type).getClass()).isEqualTo(type);
            }
            assertThat(context.getBeansOfType(GraphProjectionRepository.class)).hasSize(1);
            assertThat(context).doesNotHaveBean(LightRagClient.class);
        });
    }

    @Test
    @DisplayName("lightrag 形态：按 type 注册客户端；网关可达面不变")
    void lightragBackendRegistersClient() {
        collaborators().withPropertyValues("rag.graph.type=lightrag").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(LightRagClient.class)).hasSize(1);
            assertThat(context).hasSingleBean(GraphController.class);
        });
    }

    @Test
    @DisplayName("任一门关闭：整个图谱面消失，且不要求协作对象在场")
    void everyGateClosesTheSurface() {
        for (String property : new String[]{"ai.integration.enabled=false", "ai.integration.transport=http",
                "p2.enabled=false"}) {
            configurations.withPropertyValues(property).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(GraphController.class)
                        .doesNotHaveBean(GraphQueryService.class)
                        .doesNotHaveBean(GraphProjectionService.class);
            });
        }
    }
}
