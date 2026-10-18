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

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.rag.config.GraphProperties;
import com.nageoffer.ai.ragent.rag.config.GraphSyncVectorStorePostProcessor;
import com.nageoffer.ai.ragent.rag.config.KeywordProperties;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.graph.LightRagClient;
import com.nageoffer.ai.ragent.rag.core.keyword.EsKeywordIndexService;
import com.nageoffer.ai.ragent.rag.core.keyword.EsKeywordRetrieverService;
import com.nageoffer.ai.ragent.rag.core.keyword.KeywordRetrieverService;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.GraphSearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.KeywordSearchChannel;
import com.nageoffer.ai.ragent.rag.core.vector.MilvusVectorRetrieverService;
import com.nageoffer.ai.ragent.rag.core.vector.MilvusVectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.core.vector.MilvusVectorStoreService;
import com.nageoffer.ai.ragent.rag.core.vector.PgVectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.core.vector.PgVectorRetrieverService;
import com.nageoffer.ai.ragent.rag.core.vector.PgVectorStoreService;
import com.nageoffer.ai.ragent.rag.core.vector.VectorRetrieverService;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import io.milvus.v2.client.MilvusClientV2;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * F22:2：内嵌形态三条可选检索后端（Milvus / ES 关键词 / LightRAG 图谱）的条件装配判据。
 *
 * <p>内嵌只扫 {@code org.ruoyi}，{@code com.nageoffer.ai.ragent.*} 下的实现不会成为 bean。
 * 本组盯四件事：
 * <ol>
 *   <li>每条后端仅在对应 {@code rag.*.type} 显式选择时装配，且只装自己那一组
 *       （逐条正例 + 互不干扰）；</li>
 *   <li>选择后缺关键连接配置（{@code milvus.uri} / {@code rag.keyword.es.uris} /
 *       {@code rag.graph.lightrag.base-url}）必须<b>启动期响亮失败</b>并指名配置键，
 *       不允许"装上了但永远连不上"；</li>
 *   <li>默认形态（{@code pg} / {@code none} / 无 graph）本组贡献<b>零 bean</b>，
 *       与装配前逐 bean 等价；</li>
 *   <li>与既有图谱管理面 {@link AiEmbeddedGraphConfiguration} 同容器时
 *       {@link LightRagClient} 恰好一个（顺序约定靠 {@code @AutoConfigureAfter}）。</li>
 * </ol>
 */
@Tag("dev")
class AiEmbeddedF22BackendsConfigurationTest {

    private static final String[] EMBEDDED_LOCAL = {
            "ai.integration.enabled=true", "ai.integration.transport=local"};

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiEmbeddedF22BackendsConfiguration.class, Fixture.class);

    // ------------------------------------------------------------ 默认等价

    @Test
    @DisplayName("默认形态：本组不贡献任何可选后端 bean（pg / none / 无 graph 逐 bean 等价）")
    void defaultShapeContributesNoOptionalBackendBeans() {
        runner.withPropertyValues(EMBEDDED_LOCAL).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(MilvusClientV2.class);
            assertThat(context).doesNotHaveBean(ElasticsearchClient.class);
            assertThat(context).doesNotHaveBean(LightRagClient.class);
            assertThat(context).doesNotHaveBean(KeywordSearchChannel.class);
            assertThat(context).doesNotHaveBean(GraphSearchChannel.class);
            assertThat(context).doesNotHaveBean(GraphSyncVectorStorePostProcessor.class);
            assertThat(context).doesNotHaveBean(RAGDefaultProperties.class);
        });
    }

    @Test
    @DisplayName("shipped pg 形态：既有 PG 三件与知识链装配不变，可选后端仍缺席")
    void shippedPgShapeRemainsEquivalent() {
        new ApplicationContextRunner()
                .withUserConfiguration(AiEmbeddedF22BackendsConfiguration.class,
                        AiEmbeddedKnowledgeConfiguration.class, Fixture.class)
                .withPropertyValues(EMBEDDED_LOCAL)
                .withPropertyValues("rag.vector.type=pg")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(VectorStoreService.class);
                    assertThat(context.getBean(VectorStoreService.class))
                            .isInstanceOf(PgVectorStoreService.class);
                    assertThat(context).hasSingleBean(VectorRetrieverService.class);
                    assertThat(context.getBean(VectorRetrieverService.class))
                            .isInstanceOf(PgVectorRetrieverService.class);
                    assertThat(context).hasSingleBean(VectorStoreAdmin.class);
                    assertThat(context.getBean(VectorStoreAdmin.class))
                            .isInstanceOf(PgVectorStoreAdmin.class);
                    assertThat(context).doesNotHaveBean(MilvusClientV2.class);
                    assertThat(context).doesNotHaveBean(ElasticsearchClient.class);
                    assertThat(context).doesNotHaveBean(LightRagClient.class);
                    assertThat(context).doesNotHaveBean(KeywordSearchChannel.class);
                    assertThat(context).doesNotHaveBean(GraphSearchChannel.class);
                    assertThat(context).doesNotHaveBean(GraphSyncVectorStorePostProcessor.class);
                });
    }

    // ------------------------------------------------------------ Milvus

    @Test
    @DisplayName("rag.vector.type=milvus：客户端 + 写/读/管理三件装配，其余后端缺席")
    void milvusBackendAssemblesOnlyItsOwnGroup() {
        runner.withPropertyValues(EMBEDDED_LOCAL)
                .withPropertyValues("rag.vector.type=milvus",
                        "milvus.uri=http://127.0.0.1:19530")
                // MilvusClientV2 的构造会真的连一次服务（10s 超时）；装配测试用替身客户端，
                // 真实客户端的创建由 MilvusConfig 自身覆盖（本类只验证条件与 bean 形状）。
                .withBean(MilvusClientV2.class, () -> mock(MilvusClientV2.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MilvusClientV2.class);
                    assertThat(context).hasSingleBean(VectorStoreService.class);
                    assertThat(context.getBean(VectorStoreService.class))
                            .isInstanceOf(MilvusVectorStoreService.class);
                    assertThat(context).hasSingleBean(VectorRetrieverService.class);
                    assertThat(context.getBean(VectorRetrieverService.class))
                            .isInstanceOf(MilvusVectorRetrieverService.class);
                    assertThat(context).hasSingleBean(VectorStoreAdmin.class);
                    assertThat(context.getBean(VectorStoreAdmin.class))
                            .isInstanceOf(MilvusVectorStoreAdmin.class);
                    assertThat(context).hasSingleBean(RAGDefaultProperties.class);

                    assertThat(context).doesNotHaveBean(PgVectorStoreService.class);
                    assertThat(context).doesNotHaveBean(ElasticsearchClient.class);
                    assertThat(context).doesNotHaveBean(LightRagClient.class);
                    assertThat(context).doesNotHaveBean(KeywordSearchChannel.class);
                    assertThat(context).doesNotHaveBean(GraphSearchChannel.class);
                    assertThat(context).doesNotHaveBean(GraphSyncVectorStorePostProcessor.class);
                });
    }

    @Test
    @DisplayName("rag.vector.type=milvus 缺 milvus.uri：启动期指名配置键失败（空值与缺席都拒绝）")
    void milvusWithoutUriFailsAtStartup() {
        runner.withPropertyValues(EMBEDDED_LOCAL)
                .withPropertyValues("rag.vector.type=milvus", "milvus.uri=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("rag.vector.type=milvus 已选择")
                            .hasStackTraceContaining("milvus.uri");
                });
        runner.withPropertyValues(EMBEDDED_LOCAL)
                .withPropertyValues("rag.vector.type=milvus")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("rag.vector.type=milvus 已选择")
                            .hasStackTraceContaining("milvus.uri");
                });
    }

    // ------------------------------------------------------------ ES 关键词

    @Test
    @DisplayName("rag.keyword.type=es：客户端 + 索引/检索服务 + 通道装配；关闭态启动初始化跳过")
    void esBackendAssemblesOnlyItsOwnGroupAndStartsWithClosedCapability() {
        runner.withPropertyValues(EMBEDDED_LOCAL)
                .withPropertyValues("rag.keyword.type=es",
                        "rag.keyword.es.uris=http://127.0.0.1:9200")
                .run(context -> {
                    // 没有 ES 服务、没有 SaasCapabilityBoundary bean：
                    // @PostConstruct 必须跳过（F22:2），所以上下文仍能起来。
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ElasticsearchClient.class);
                    assertThat(context).hasSingleBean(EsKeywordIndexService.class);
                    assertThat(context).hasSingleBean(EsKeywordRetrieverService.class);
                    assertThat(context).hasSingleBean(KeywordRetrieverService.class);
                    assertThat(context).hasSingleBean(KeywordSearchChannel.class);
                    assertThat(context).hasSingleBean(KeywordProperties.class);
                    assertThat(context).hasSingleBean(SearchChannelProperties.class);

                    assertThat(context).doesNotHaveBean(MilvusClientV2.class);
                    assertThat(context).doesNotHaveBean(LightRagClient.class);
                    assertThat(context).doesNotHaveBean(GraphSearchChannel.class);
                    assertThat(context).doesNotHaveBean(GraphSyncVectorStorePostProcessor.class);
                });
    }

    @Test
    @DisplayName("rag.keyword.type=es 且 uris 显式为空：启动期指名配置键失败")
    void esWithBlankUrisFailsAtStartup() {
        runner.withPropertyValues(EMBEDDED_LOCAL)
                .withPropertyValues("rag.keyword.type=es", "rag.keyword.es.uris=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("rag.keyword.es.uris 为空");
                });
    }

    // ------------------------------------------------------------ LightRAG 图谱

    @Test
    @DisplayName("rag.graph.type=lightrag：客户端 + 图谱通道 + 向量写同步织入器装配，其余后端缺席")
    void lightRagBackendAssemblesOnlyItsOwnGroup() {
        runner.withPropertyValues(EMBEDDED_LOCAL)
                .withPropertyValues("rag.graph.type=lightrag",
                        "rag.graph.lightrag.base-url=http://127.0.0.1:9621")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LightRagClient.class);
                    assertThat(context).hasSingleBean(GraphSearchChannel.class);
                    assertThat(context).hasSingleBean(GraphSyncVectorStorePostProcessor.class);
                    assertThat(context).hasSingleBean(GraphProperties.class);
                    assertThat(context).hasSingleBean(SearchChannelProperties.class);

                    assertThat(context).doesNotHaveBean(MilvusClientV2.class);
                    assertThat(context).doesNotHaveBean(ElasticsearchClient.class);
                    assertThat(context).doesNotHaveBean(KeywordSearchChannel.class);
                });
    }

    @Test
    @DisplayName("rag.graph.type=lightrag 且 base-url 显式为空：启动期指名配置键失败")
    void lightRagWithBlankBaseUrlFailsAtStartup() {
        runner.withPropertyValues(EMBEDDED_LOCAL)
                .withPropertyValues("rag.graph.type=lightrag", "rag.graph.lightrag.base-url=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("rag.graph.lightrag.base-url 为空");
                });
    }

    // ------------------------------------------------------------ 组合与门控

    @Test
    @DisplayName("三条后端同时显式选择时互不干扰：各自单例、共享属性单例")
    void allThreeBackendsCoexistWithoutInterference() {
        runner.withPropertyValues(EMBEDDED_LOCAL)
                .withPropertyValues(
                        "rag.vector.type=milvus", "milvus.uri=http://127.0.0.1:19530",
                        "rag.keyword.type=es", "rag.keyword.es.uris=http://127.0.0.1:9200",
                        "rag.graph.type=lightrag",
                        "rag.graph.lightrag.base-url=http://127.0.0.1:9621")
                .withBean(MilvusClientV2.class, () -> mock(MilvusClientV2.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MilvusClientV2.class);
                    assertThat(context).hasSingleBean(VectorStoreService.class);
                    assertThat(context).hasSingleBean(MilvusVectorRetrieverService.class);
                    assertThat(context).hasSingleBean(MilvusVectorStoreAdmin.class);
                    assertThat(context).hasSingleBean(ElasticsearchClient.class);
                    assertThat(context).hasSingleBean(EsKeywordIndexService.class);
                    assertThat(context).hasSingleBean(KeywordSearchChannel.class);
                    assertThat(context).hasSingleBean(LightRagClient.class);
                    assertThat(context).hasSingleBean(GraphSearchChannel.class);
                    assertThat(context).hasSingleBean(GraphSyncVectorStorePostProcessor.class);
                    assertThat(context).hasSingleBean(RAGDefaultProperties.class);
                });
    }

    @Test
    @DisplayName("集成总开关或本地传输关闭时：三条可选后端一律不装配")
    void integrationGatesCloseEveryOptionalBackend() {
        for (String[] gates : new String[][] {
                {"ai.integration.enabled=false", "ai.integration.transport=local"},
                {"ai.integration.enabled=true", "ai.integration.transport=http"}}) {
            runner.withPropertyValues(gates)
                    .withPropertyValues(
                            "rag.vector.type=milvus", "milvus.uri=http://127.0.0.1:19530",
                            "rag.keyword.type=es", "rag.keyword.es.uris=http://127.0.0.1:9200",
                            "rag.graph.type=lightrag",
                            "rag.graph.lightrag.base-url=http://127.0.0.1:9621")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).doesNotHaveBean(MilvusClientV2.class);
                        assertThat(context).doesNotHaveBean(ElasticsearchClient.class);
                        assertThat(context).doesNotHaveBean(LightRagClient.class);
                        assertThat(context).doesNotHaveBean(KeywordSearchChannel.class);
                        assertThat(context).doesNotHaveBean(GraphSearchChannel.class);
                    });
        }
    }

    @Test
    @DisplayName("与图谱管理面同容器：lightrag 客户端仍恰好一个（F22 组按 missing-bean 退让）")
    void lightRagClientIsNotDuplicatedWhenGraphManagementConfigIsPresent() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AiEmbeddedGraphConfiguration.class,
                        AiEmbeddedF22BackendsConfiguration.class))
                .withUserConfiguration(Fixture.class)
                .withPropertyValues(EMBEDDED_LOCAL)
                .withPropertyValues("p2.enabled=true", "rag.graph.type=lightrag",
                        "rag.graph.lightrag.base-url=http://127.0.0.1:9621")
                .withBean(NamedParameterJdbcTemplate.class,
                        () -> mock(NamedParameterJdbcTemplate.class))
                .withBean(AiResourceAuthorizationService.class,
                        () -> mock(AiResourceAuthorizationService.class))
                .withBean(com.nageoffer.ai.ragent.framework.security.AuthorizationChecker.class,
                        () -> mock(com.nageoffer.ai.ragent.framework.security.AuthorizationChecker.class))
                .withBean(PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(LightRagClient.class)).hasSize(1);
                    assertThat(context).hasSingleBean(GraphSearchChannel.class);
                    assertThat(context).hasSingleBean(GraphSyncVectorStorePostProcessor.class);
                });
    }

    /** 最小协作者集合：所有后端都只依赖接口，行为断言不需要真实实现。 */
    @Configuration(proxyBeanMethods = false)
    static class Fixture {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        EmbeddingService embeddingService() {
            return mock(EmbeddingService.class);
        }

        @Bean(name = "syncHttpClient")
        OkHttpClient syncHttpClient() {
            return mock(OkHttpClient.class);
        }

        @Bean
        JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }

        @Bean
        KnowledgeChunkMapper knowledgeChunkMapper() {
            return mock(KnowledgeChunkMapper.class);
        }
    }
}
