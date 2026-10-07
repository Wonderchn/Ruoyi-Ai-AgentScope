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

import com.nageoffer.ai.ragent.core.ingest.embed.ChunkEmbeddingService;
import com.nageoffer.ai.ragent.infra.token.HeuristicTokenCounterService;
import com.nageoffer.ai.ragent.infra.token.TokenCounterService;
import com.nageoffer.ai.ragent.knowledge.sink.RelationalChunkSink;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.core.vector.PgVectorRetrieverService;
import com.nageoffer.ai.ragent.rag.core.vector.PgVectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.core.vector.PgVectorStoreService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * WP-032：知识/向量链（摄取 → 索引 → 检索）的内嵌装配。
 *
 * <p><b>为什么需要单独一组。</b>这一条链的组件同样都在
 * {@code com.nageoffer.ai.ragent.*} 下由 {@code @Service}/{@code @Component} 标注，
 * 而 platform 的扫描根是 {@code org.ruoyi}——内嵌形态里它们<b>从来不是 bean</b>。
 * 后果不是"某个端点少了增强"，而是整条检索/索引路径没有后端：
 * {@code /api/ai/v1/knowledge-bases/retrievals} 拿不到 {@code VectorRetrieverService}，
 * 指纹看不到 {@code embedding} 服务，文档摄取也没有 chunk sink 可落。
 *
 * <p><b>门控是 {@code rag.vector.type=pg}，与既有实现逐字一致。</b>
 * {@code PgVectorStoreService}/{@code PgVectorRetrieverService}/{@code PgVectorStoreAdmin}
 * 自己就带这个 {@code @ConditionalOnProperty}，本组沿用同一个条件，
 * 保证"内嵌态装配的那套"和"独立 AI 态装配的那套"是同一个判据——
 * 不出现内嵌下装了 Milvus 而独立态装的是 PG 这种分叉。
 * 本期只装配 PG：Milvus/ES/LightRAG 属可选技术后端（WP-053），本轮不冒充已启用。
 *
 * <p><b>PG 向量表形状由迁移拥有，应用只用 DML。</b>
 * {@code ai_knowledge_vector} 的列、{@code (tenant_id, id)} 复合唯一键与
 * {@code extensions.vector_cosine_ops} 的 HNSW 索引都来自冻结迁移；
 * 迁移未部署时写路径会以 {@code BadSqlGrammarException} <b>响亮失败</b>，
 * 绝不回落到不带租户条件的旧语句（见 {@code PgVectorStoreService.requireVectorSchema}）。
 * 本组不建表、不改 DDL。
 *
 * <p><b>向量维度是 fail-closed 的。</b>{@link RAGDefaultProperties#getDimension()}
 * 缺失时 {@code VectorTargetResolver} 直接拒绝（"部署未配置向量维度"），
 * 而不是猜一个维度去写库——猜错会把不同模型的向量混进同一列，事后无法区分。
 * 因此 {@code application-embedded.yml} 必须显式声明 {@code rag.default.dimension}。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiEmbeddedKnowledgeConfiguration {

    /** 本地传输下的知识/向量链装配。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEmbeddedLocal
    public static class LocalTransport {

        /**
         * PG 向量后端门控：{@code rag.vector.type=pg}。
         *
         * <p>与 {@code PgVector*} 三个实现类自身的条件完全相同；
         * 非 pg（例如显式配 {@code milvus}）时本组不注册任何向量 bean，
         * 调用方以缺 bean 明确失败，不做静默回退。
         */
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnEmbeddedLocal
        @ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
        public static class PgVectorEnabled {

            /**
             * RAG 默认参数（collection 名 / 维度 / 度量 / SSE 超时）。
             *
             * <p>{@code RAGDefaultProperties} 自带 {@code @Configuration}，但那个注解依赖组件扫描；
             * 内嵌形态下必须在 {@code org.ruoyi} 侧重新登记，否则 {@link PgVectorStoreAdmin}
             * 拿不到维度去做索引形状校验。
             */
            @Bean
            @ConditionalOnMissingBean
            public RAGDefaultProperties ragDefaultProperties() {
                return new RAGDefaultProperties();
            }

            /** 轻量 token 计数（chunk sink 写 token_count 用；无外部依赖）。 */
            @Bean
            @ConditionalOnMissingBean
            public TokenCounterService tokenCounterService() {
                return new HeuristicTokenCounterService();
            }

            /** 向量写入（upsert 到 {@code ai_knowledge_vector}，按租户限定）。 */
            @Bean
            @ConditionalOnMissingBean
            public PgVectorStoreService pgVectorStoreService(JdbcTemplate jdbcTemplate,
                                                            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
                return new PgVectorStoreService(jdbcTemplate, objectMapper);
            }

            /** 向量检索（同时实现授权检索端口，供检索前资源授权复核）。 */
            @Bean
            @ConditionalOnMissingBean
            public PgVectorRetrieverService pgVectorRetrieverService(
                    JdbcTemplate jdbcTemplate,
                    com.nageoffer.ai.ragent.infra.embedding.EmbeddingService embeddingService) {
                return new PgVectorRetrieverService(jdbcTemplate, embeddingService);
            }

            /** 向量空间形状校验（只读 pg_index，不执行 DDL）。 */
            @Bean
            @ConditionalOnMissingBean
            public PgVectorStoreAdmin pgVectorStoreAdmin(JdbcTemplate jdbcTemplate,
                                                        RAGDefaultProperties ragDefaultProperties) {
                return new PgVectorStoreAdmin(jdbcTemplate, ragDefaultProperties);
            }

            /** 批量嵌入（摄取链的 chunk → 向量步骤）。 */
            @Bean
            @ConditionalOnMissingBean
            public ChunkEmbeddingService chunkEmbeddingService(
                    com.nageoffer.ai.ragent.infra.embedding.EmbeddingService embeddingService) {
                return new ChunkEmbeddingService(embeddingService);
            }

            /**
             * 关系型 chunk 落库（chunk 表是知识资产的权威存储，向量表只是索引）。
             *
             * <p>依赖 {@code KnowledgeChunkMapper}：该 Mapper 由
             * {@link AiEmbeddedMapperConfiguration} 的 {@code @MapperScan} 覆盖
             * （{@code com.nageoffer.ai.ragent.knowledge.dao.mapper}），
             * 不在这里重复注册。
             */
            @Bean
            @ConditionalOnMissingBean
            public RelationalChunkSink relationalChunkSink(
                    com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper chunkMapper,
                    TokenCounterService tokenCounterService) {
                return new RelationalChunkSink(chunkMapper, tokenCounterService);
            }
        }
    }
}
