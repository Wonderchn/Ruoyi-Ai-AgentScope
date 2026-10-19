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
import com.nageoffer.ai.ragent.rag.config.GraphProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.controller.GraphController;
import com.nageoffer.ai.ragent.rag.core.graph.GraphProjectionRepository;
import com.nageoffer.ai.ragent.rag.core.graph.GraphProjectionService;
import com.nageoffer.ai.ragent.rag.core.graph.GraphQueryService;
import com.nageoffer.ai.ragent.rag.core.graph.GraphScopeResolver;
import com.nageoffer.ai.ragent.rag.core.graph.GraphStatusService;
import com.nageoffer.ai.ragent.rag.core.graph.JdbcGraphProjectionRepository;
import com.nageoffer.ai.ragent.rag.core.graph.LightRagClient;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * 知识图谱管理面（{@code /internal/ai/v1/admin/kg}）的内嵌装配（F08:0–3）。
 *
 * <p><b>为什么需要这一组。</b>{@code GraphController} 及其服务都在
 * {@code com.nageoffer.ai.ragent.**}，不在 platform 的 {@code org.ruoyi} 扫描根下 ⇒ 内嵌形态里
 * 它们从来不是 bean（旧 {@code /admin/kg} 路径也从未进入网关白名单）。本类显式登记
 * 投影仓储、读路径、重建、状态与控制器，公开面再由 {@code AiGatewayController.ROUTES} 四条放行。
 *
 * <p><b>门控。</b>{@code ai.integration.enabled + transport=local + p2.enabled}：
 * 三个 GET 走网关字节分支，必须能铸造交付回执（{@link com.nageoffer.ai.ragent.runtime.web.DeliveryPermits}
 * 由 P2 组提供）；p2 关闭时整个面不装（fail-closed），而不是装一个永远 503 的面。
 *
 * <p><b>LightRag 客户端按 type 装配。</b>{@code rag.graph.type=lightrag} 才注册
 * {@link LightRagClient}；{@code none} 形态仍保留读投影/状态面（零外呼、status=NOT_CONFIGURED）。
 * 客户端沿用 {@code syncHttpClient}（连接 10s/读 30s/调用 45s），探测另建短超时客户端。
 *
 * <p>本类必须由 T0 追加到
 * {@code ruoyi-ai-web/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}。
 */
@AutoConfiguration
@ConditionalOnEmbeddedLocal
@ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
@EnableConfigurationProperties({GraphProperties.class, SearchChannelProperties.class})
public class AiEmbeddedGraphConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GraphProjectionRepository graphProjectionRepository(NamedParameterJdbcTemplate jdbc) {
        return new JdbcGraphProjectionRepository(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean
    public GraphScopeResolver graphScopeResolver(GraphProjectionRepository repository,
                                                 AiResourceAuthorizationService authorization) {
        return new GraphScopeResolver(repository, authorization);
    }

    @Bean
    @ConditionalOnMissingBean
    public GraphQueryService graphQueryService(GraphProjectionRepository repository,
                                               GraphScopeResolver scopeResolver) {
        return new GraphQueryService(repository, scopeResolver);
    }

    /**
     * 重建事务：与 platform 主数据源同一事务管理器。
     *
     * <p>必须按 {@link TransactionTemplate} 具体类型声明并注册：若按
     * {@link TransactionOperations} 声明，会先于 Boot 的 TransactionTemplate 自动配置注册，
     * 使其因 {@code @ConditionalOnMissingBean(TransactionOperations.class)} 退让，
     * 而按具体类型注入 {@code TransactionTemplate} 的组件（如 RevocationBarrierCoordinator）将缺 bean。
     */
    @Bean
    @ConditionalOnMissingBean(TransactionTemplate.class)
    public TransactionTemplate graphTransactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public GraphProjectionService graphProjectionService(GraphProjectionRepository repository,
                                                         ObjectProvider<LightRagClient> clientProvider,
                                                         GraphProperties properties,
                                                         AiResourceAuthorizationService authorization,
                                                         TransactionOperations transactionOperations) {
        return new GraphProjectionService(repository, clientProvider, properties, authorization,
                transactionOperations);
    }

    @Bean
    @ConditionalOnMissingBean
    public GraphStatusService graphStatusService(GraphProperties properties,
                                                 GraphProjectionRepository repository,
                                                 ObjectProvider<LightRagClient> clientProvider,
                                                 AiResourceAuthorizationService authorization,
                                                 ObjectProvider<Clock> clockProvider) {
        return new GraphStatusService(properties, repository, clientProvider, authorization,
                clockProvider == null ? Clock.systemUTC() : clockProvider.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    public GraphController graphController(GraphQueryService graphQueryService,
                                           GraphStatusService graphStatusService,
                                           GraphProjectionService graphProjectionService) {
        return new GraphController(graphQueryService, graphStatusService, graphProjectionService);
    }

    /**
     * LightRAG 客户端：只在 {@code rag.graph.type=lightrag} 时装配。
     *
     * <p>显式钉住 {@code syncHttpClient}：内嵌模型组里类型候选有两个且 {@code streamingHttpClient}
     * 带 {@code @Primary}（无读超时/无调用超时），按类型注入会让图谱调用拿到流式客户端。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "rag.graph", name = "type", havingValue = "lightrag")
    public LightRagClient lightRagClient(@Qualifier("syncHttpClient") OkHttpClient syncHttpClient,
                                         ObjectMapper objectMapper,
                                         GraphProperties properties,
                                         SearchChannelProperties searchChannelProperties) {
        return new LightRagClient(syncHttpClient, objectMapper, properties, searchChannelProperties);
    }
}
