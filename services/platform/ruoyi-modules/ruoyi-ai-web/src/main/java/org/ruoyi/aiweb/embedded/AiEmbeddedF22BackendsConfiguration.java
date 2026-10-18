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

import cn.hutool.core.thread.ThreadFactoryBuilder;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.alibaba.ttl.threadpool.TtlExecutors;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.core.ingest.embed.ChunkEmbeddingService;
import com.nageoffer.ai.ragent.framework.integration.SaasCapabilityBoundary;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.infra.token.HeuristicTokenCounterService;
import com.nageoffer.ai.ragent.infra.token.TokenCounterService;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeChunkService;
import com.nageoffer.ai.ragent.knowledge.service.impl.KnowledgeChunkServiceImpl;
import com.nageoffer.ai.ragent.knowledge.sink.RelationalChunkSink;
import com.nageoffer.ai.ragent.knowledge.support.VectorTargetResolver;
import com.nageoffer.ai.ragent.rag.config.EsClientConfig;
import com.nageoffer.ai.ragent.rag.config.GraphProperties;
import com.nageoffer.ai.ragent.rag.config.GraphSyncVectorStorePostProcessor;
import com.nageoffer.ai.ragent.rag.config.KeywordProperties;
import com.nageoffer.ai.ragent.rag.config.KeywordSyncVectorStorePostProcessor;
import com.nageoffer.ai.ragent.rag.config.MilvusConfig;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.config.VectorSpaceInitializer;
import com.nageoffer.ai.ragent.rag.core.graph.LightRagClient;
import com.nageoffer.ai.ragent.rag.core.keyword.EsKeywordIndexService;
import com.nageoffer.ai.ragent.rag.core.keyword.EsKeywordRetrieverService;
import com.nageoffer.ai.ragent.rag.core.keyword.KeywordIndexService;
import com.nageoffer.ai.ragent.rag.core.keyword.KeywordRetrieverService;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.GraphSearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.KeywordSearchChannel;
import com.nageoffer.ai.ragent.rag.core.vector.MilvusVectorRetrieverService;
import com.nageoffer.ai.ragent.rag.core.vector.MilvusVectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.core.vector.MilvusVectorStoreService;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import io.milvus.v2.client.MilvusClientV2;
import okhttp3.OkHttpClient;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.util.StringUtils;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * F22:2：内嵌形态的可选检索后端（Milvus / ES 关键词 / LightRAG 图谱）条件装配。
 *
 * <p><b>为什么需要这一组。</b>三条后端的实现都在 {@code com.nageoffer.ai.ragent.**}，
 * 而 platform 的扫描根是 {@code org.ruoyi}——内嵌形态里它们从来不是 bean。
 * {@link AiEmbeddedKnowledgeConfiguration} 只覆盖默认的 PG 向量链；本类按
 * {@code rag.vector.type} / {@code rag.keyword.type} / {@code rag.graph.type} 三个
 * <b>互不重叠</b>的开关分别装配，默认配置（{@code pg} / {@code none} / 无 graph）
 * 下本类贡献零 bean，与装配前逐 bean 等价。
 *
 * <p><b>连接配置 fail-closed。</b>显式选择某后端后，缺关键连接配置不是"先起来再说"：
 * <ul>
 *   <li>{@code rag.vector.type=milvus} 必须有 {@code milvus.uri}（{@code milvus.token} 可选）；</li>
 *   <li>{@code rag.keyword.type=es} 的 {@code rag.keyword.es.uris} 显式为空即拒绝
 *       （缺省时用 {@link KeywordProperties.Es#getUris()} 的本地默认值）；</li>
 *   <li>{@code rag.graph.type=lightrag} 的 {@code rag.graph.lightrag.base-url} 显式为空即拒绝。</li>
 * </ul>
 * 检查用 {@link BeanFactoryPostProcessor} 在所有常规单例实例化之前执行，
 * 失败信息指名配置键与改回默认后端的办法，避免"Bean 创建到一半才报一个底层连接异常"。
 *
 * <p><b>ES 启动初始化。</b>{@link EsKeywordIndexService#initSharedIndex()} 在旧能力关闭时
 * <b>跳过</b>（零 ES 请求，首次写入懒建），因此只把 {@code rag.keyword.type} 打开不会
 * 因为"关闭旧能力"而让应用起不来；边界打开时的初始化失败仍照常冒泡。
 *
 * <p><b>与既有装配的关系。</b>{@link AiEmbeddedGraphConfiguration} 在
 * {@code p2.enabled=true} 时已经注册 {@link LightRagClient}；本类按
 * {@code @ConditionalOnMissingBean} 退让（类级 {@code @AutoConfigureAfter} 固定顺序），
 * 同容器内客户端恰好一个。{@code innerRetrievalExecutor} 同理：引擎闭包在
 * {@code ragent.engine.type=agent} 时提供，缺席时本类按同一形状补一个，
 * 保证关键词通道在任何内嵌组合下都可构造。
 *
 * <p><b>装配登记。</b>本类需由 T0 追加到
 * {@code ruoyi-ai-web/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}；
 * 各嵌套组的开关条件在组件扫描路径下也会独立求值（与既有内嵌装配组同款写法）。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
@AutoConfigureAfter({AiEmbeddedGraphConfiguration.class, AiEmbeddedAgentEngineClosureConfiguration.class})
public class AiEmbeddedF22BackendsConfiguration {

    /**
     * Milvus 向量后端：{@code rag.vector.type=milvus}。
     *
     * <p>客户端沿用 {@link MilvusConfig}（{@code milvus.uri}/{@code milvus.token}），
     * 写/读/管理三件按与 PG 三件相同的接口显式登记。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEmbeddedLocal
    @ConditionalOnClass(MilvusClientV2.class)
    @ConditionalOnProperty(prefix = "rag.vector", name = "type", havingValue = "milvus")
    @Import(MilvusConfig.class)
    public static class MilvusEnabled {

        /** 缺 {@code milvus.uri} 时在客户端创建之前失败，错误信息指名配置键。 */
        @Bean
        public static BeanFactoryPostProcessor milvusUriFailClosed() {
            return new RequiredBackendProperty("milvus.uri", true,
                    "rag.vector.type=milvus 已选择，但 milvus.uri 为空：无法创建 Milvus 客户端。"
                            + "请配置 milvus.uri（例如 http://127.0.0.1:19530），"
                            + "或显式设回 rag.vector.type=pg。");
        }

        /**
         * RAG 默认参数（collection 名 / 维度 / 度量）。
         *
         * <p>与 {@link AiEmbeddedKnowledgeConfiguration} 的 PG 分支同款登记：
         * {@code RAGDefaultProperties} 的 {@code @Configuration} 依赖组件扫描，
         * 内嵌形态必须在 {@code org.ruoyi} 侧重新登记，Milvus 三件才能拿到共享 collection 与维度。
         */
        @Bean
        @ConditionalOnMissingBean
        public RAGDefaultProperties ragDefaultProperties() {
            return new RAGDefaultProperties();
        }

        // ---------------------------------------------------- 与向量后端无关的摄取/管理协作件
        //
        // 这些 bean 原先只登记在 PG 分支（AiEmbeddedKnowledgeConfiguration.PgVectorEnabled /
        // AiEmbeddedKnowledgeAdminConfiguration.ChunkAdmin）。它们与"向量存到哪里"无关，却是
        // 摄取闭包与知识管理闭包的构造依赖：只切 rag.vector.type=milvus 时它们全部缺席，
        // DefaultIngestionKernel 会以 "required ChunkEmbeddingService" 启动失败
        // （真机 app-6044-f222.log:95-124）。此处按 missing-bean 补齐，pg 模式本组不激活，
        // 因此不会重复注册。

        /** 摄取链的 token 计数（chunk sink 写 token_count 用；无外部依赖）。 */
        @Bean
        @ConditionalOnMissingBean
        public TokenCounterService tokenCounterService() {
            return new HeuristicTokenCounterService();
        }

        /** 摄取链的 chunk → 向量步骤（嵌入服务由模型组提供，与向量落点无关）。 */
        @Bean
        @ConditionalOnMissingBean
        public ChunkEmbeddingService chunkEmbeddingService(EmbeddingService embeddingService) {
            return new ChunkEmbeddingService(embeddingService);
        }

        /** 关系型 chunk 落库（知识资产的权威存储；向量表只是索引）。 */
        @Bean
        @ConditionalOnMissingBean
        public RelationalChunkSink relationalChunkSink(
                KnowledgeChunkMapper chunkMapper, TokenCounterService tokenCounterService) {
            return new RelationalChunkSink(chunkMapper, tokenCounterService);
        }

        /** 向量落点解析（维度 fail-closed；与具体后端无关）。 */
        @Bean
        @ConditionalOnMissingBean
        public VectorTargetResolver vectorTargetResolver(RAGDefaultProperties ragDefaultProperties) {
            return new VectorTargetResolver(ragDefaultProperties);
        }

        /**
         * 分块管理服务：知识文档面（FullAdmin）的构造依赖。
         *
         * <p>pg 模式下由 {@code AiEmbeddedKnowledgeAdminConfiguration.ChunkAdmin} 注册；
         * milvus 模式下 ChunkAdmin 不激活，这里按接口 missing-bean 补齐。
         * 控制器仍留在 pg 门内（公开面不变）。
         */
        @Bean
        @ConditionalOnMissingBean(KnowledgeChunkService.class)
        public KnowledgeChunkServiceImpl knowledgeChunkService(
                KnowledgeChunkMapper chunkMapper,
                KnowledgeDocumentMapper documentMapper,
                KnowledgeBaseMapper knowledgeBaseMapper,
                ChunkEmbeddingService chunkEmbeddingService,
                VectorTargetResolver vectorTargetResolver,
                TokenCounterService tokenCounterService,
                VectorStoreService vectorStoreService,
                TransactionOperations transactionOperations,
                BizChangeLogContext bizChangeLogContext) {
            return new KnowledgeChunkServiceImpl(chunkMapper, documentMapper, knowledgeBaseMapper,
                    chunkEmbeddingService, vectorTargetResolver, tokenCounterService,
                    vectorStoreService, transactionOperations, bizChangeLogContext);
        }

        /** 向量写入（共享物理 collection，租户字段是隔离边界）。 */
        @Bean
        @ConditionalOnMissingBean
        public MilvusVectorStoreService milvusVectorStoreService(
                MilvusClientV2 milvusClient, RAGDefaultProperties ragDefaultProperties) {
            return new MilvusVectorStoreService(milvusClient, ragDefaultProperties);
        }

        /** 向量检索（租户条件永久存在于过滤表达式）。 */
        @Bean
        @ConditionalOnMissingBean
        public MilvusVectorRetrieverService milvusVectorRetrieverService(
                EmbeddingService embeddingService,
                MilvusClientV2 milvusClient,
                RAGDefaultProperties ragDefaultProperties) {
            return new MilvusVectorRetrieverService(embeddingService, milvusClient, ragDefaultProperties);
        }

        /** 向量空间形状管理（只操作共享 collection，不做跨后端回落）。 */
        @Bean
        @ConditionalOnMissingBean
        public MilvusVectorStoreAdmin milvusVectorStoreAdmin(
                MilvusClientV2 milvusClient, RAGDefaultProperties ragDefaultProperties) {
            return new MilvusVectorStoreAdmin(milvusClient, ragDefaultProperties);
        }

        /**
         * 共享向量空间启动初始化器（F22:2 真机第七缺口）。
         *
         * <p>{@code VectorSpaceInitializer} 负责在启动期幂等创建共享 collection
         * （{@code rag_default_store}）。它此前不在内嵌 @Import / F22 组里，消费链首次
         * {@code deleteDocumentVectors} 得到 {@code collection not found}；PG 下依赖迁移建表、
         * 该初始化器为空操作，所以直到 Milvus 窗才暴露。
         *
         * <p>门控语义沿用本体：boundary 缺席/关闭时 {@code @PostConstruct} 自行跳过
         * （零向量后端/Redis 调用）；开启时先查存在、再取 Redisson 锁、双重检查后建。
         * 不改本体，也不改变 PG/ES/LightRAG 组。
         */
        @Bean
        @ConditionalOnMissingBean
        public VectorSpaceInitializer vectorSpaceInitializer(
                VectorStoreAdmin vectorStoreAdmin,
                RAGDefaultProperties ragDefaultProperties,
                RedissonClient redissonClient,
                ObjectProvider<SaasCapabilityBoundary> capabilityBoundary) {
            return new VectorSpaceInitializer(vectorStoreAdmin, ragDefaultProperties,
                    redissonClient, capabilityBoundary);
        }
    }

    /**
     * ES 关键词后端：{@code rag.keyword.type=es}。
     *
     * <p>客户端沿用 {@link EsClientConfig}（{@code rag.keyword.es.uris/index/analyzer}），
     * 索引与检索服务、关键词通道显式登记。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEmbeddedLocal
    @ConditionalOnClass(ElasticsearchClient.class)
    @ConditionalOnProperty(prefix = "rag.keyword", name = "type", havingValue = "es")
    @Import(EsClientConfig.class)
    @EnableConfigurationProperties({KeywordProperties.class, SearchChannelProperties.class})
    public static class EsKeywordEnabled {

        /**
         * {@code rag.keyword.es.uris} 显式为空时启动失败。
         *
         * <p>{@link KeywordProperties.Es#getUris()} 自带本地默认值，所以"未配置"仍走默认；
         * 只有显式写空（例如环境变量注入成空串）才拒绝——那等价于"选了 ES 但没给地址"。
         */
        @Bean
        public static BeanFactoryPostProcessor esKeywordUrisFailClosed() {
            return new RequiredBackendProperty("rag.keyword.es.uris", false,
                    "rag.keyword.type=es 已选择，但 rag.keyword.es.uris 为空：无法创建 Elasticsearch 客户端。"
                            + "请配置 rag.keyword.es.uris（例如 http://127.0.0.1:9200），"
                            + "或显式设回 rag.keyword.type=none。");
        }

        /**
         * 关键词索引写入。
         *
         * <p>{@link SaasCapabilityBoundary} 以 {@link ObjectProvider} 注入：缺席按关闭处理，
         * 启动初始化跳过、首次写入懒建（见 {@link EsKeywordIndexService#initSharedIndex()}）。
         */
        @Bean
        @ConditionalOnMissingBean
        public EsKeywordIndexService esKeywordIndexService(
                ElasticsearchClient esClient,
                KeywordProperties keywordProperties,
                ObjectProvider<SaasCapabilityBoundary> capabilityBoundary) {
            return new EsKeywordIndexService(esClient, keywordProperties, capabilityBoundary);
        }

        /** 关键词检索（BM25，命中 _id 与向量库主键对齐）。 */
        @Bean
        @ConditionalOnMissingBean
        public EsKeywordRetrieverService esKeywordRetrieverService(
                ElasticsearchClient esClient, KeywordProperties keywordProperties) {
            return new EsKeywordRetrieverService(esClient, keywordProperties);
        }

        /**
         * RAG 内部检索池：引擎闭包在 {@code ragent.engine.type=agent} 时已提供同名 bean，
         * 这里按 {@code @ConditionalOnMissingBean(name)} 退让；缺席时镜像
         * {@code ThreadPoolExecutorConfig#innerRetrievalExecutor} 的形状补一个，
         * 使关键词通道不依赖引擎开关即可构造。
         */
        @Bean(name = "innerRetrievalExecutor")
        @ConditionalOnMissingBean(name = "innerRetrievalExecutor")
        public Executor innerRetrievalExecutor() {
            int cpuCount = Runtime.getRuntime().availableProcessors();
            ThreadPoolExecutor executor = new ThreadPoolExecutor(
                    cpuCount << 1,
                    cpuCount << 2,
                    60,
                    TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(100),
                    ThreadFactoryBuilder.create()
                            .setNamePrefix("rag_inner_retrieval_executor_")
                            .build(),
                    new ThreadPoolExecutor.CallerRunsPolicy());
            return TtlExecutors.getTtlExecutor(executor);
        }

        /** 关键词检索通道（仅在通道开关 {@code rag.search.channels.keyword.enabled} 打开时参与召回）。 */
        @Bean
        @ConditionalOnMissingBean
        public KeywordSearchChannel keywordSearchChannel(
                KeywordRetrieverService keywordRetriever,
                SearchChannelProperties properties,
                @Qualifier("innerRetrievalExecutor") Executor innerRetrievalExecutor) {
            return new KeywordSearchChannel(keywordRetriever, properties, innerRetrievalExecutor);
        }

        /**
         * 关键词同步织入器（F22:2 真机第八缺口）。
         *
         * <p>ES 关键词实现在场时把 {@code VectorStoreService} 包成
         * {@code KeywordSyncingVectorStoreService}，向量写入/删除自动同步 ES。
         * 与 LightRAG 组的图谱同步织入器是对偶；它此前只在内嵌扫描根外，故真机出现
         * "块索引写入完成 落点数=2 但 ES _count 前后为 0"。
         *
         * <p>{@code static @Bean}：BeanPostProcessor 实例化早于普通单例，用静态工厂
         * 避免提前实例化本配置类；关键词实现通过 {@link ObjectProvider} 惰性解析，
         * 关闭态（type=none）不会进入本组。
         */
        @Bean
        public static KeywordSyncVectorStorePostProcessor keywordSyncVectorStorePostProcessor(
                ObjectProvider<KeywordIndexService> keywordIndexServiceProvider) {
            return new KeywordSyncVectorStorePostProcessor(keywordIndexServiceProvider);
        }
    }

    /**
     * LightRAG 图谱后端：{@code rag.graph.type=lightrag}。
     *
     * <p>客户端可能与 {@link AiEmbeddedGraphConfiguration} 重复登记，按
     * {@code @ConditionalOnMissingBean} 退让；图谱检索通道与向量写同步织入器只在本组登记。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEmbeddedLocal
    @ConditionalOnClass(LightRagClient.class)
    @ConditionalOnProperty(prefix = "rag.graph", name = "type", havingValue = "lightrag")
    @EnableConfigurationProperties({GraphProperties.class, SearchChannelProperties.class})
    public static class LightRagEnabled {

        /**
         * {@code rag.graph.lightrag.base-url} 显式为空时启动失败。
         *
         * <p>{@link GraphProperties.LightRag#getBaseUrl()} 自带本地默认值，未配置走默认；
         * 显式写空等价于"选了图谱但没给地址"，启动即拒绝。
         */
        @Bean
        public static BeanFactoryPostProcessor lightRagBaseUrlFailClosed() {
            return new RequiredBackendProperty("rag.graph.lightrag.base-url", false,
                    "rag.graph.type=lightrag 已选择，但 rag.graph.lightrag.base-url 为空：无法定位 LightRAG 服务。"
                            + "请配置 rag.graph.lightrag.base-url（例如 http://127.0.0.1:9621），"
                            + "或显式设回 rag.graph.type=none。");
        }

        /**
         * LightRAG HTTP 客户端。
         *
         * <p>显式钉住 {@code syncHttpClient}：内嵌模型组里类型候选有两个且
         * {@code streamingHttpClient} 带 {@code @Primary}（无读超时/无调用超时）。
         */
        @Bean
        @ConditionalOnMissingBean
        public LightRagClient lightRagClient(
                @Qualifier("syncHttpClient") OkHttpClient syncHttpClient,
                ObjectMapper objectMapper,
                GraphProperties properties,
                SearchChannelProperties searchChannelProperties) {
            return new LightRagClient(syncHttpClient, objectMapper, properties, searchChannelProperties);
        }

        /** 图谱检索通道（仅在通道开关 {@code rag.search.channels.graph.enabled} 打开时参与召回）。 */
        @Bean
        @ConditionalOnMissingBean
        public GraphSearchChannel graphSearchChannel(
                LightRagClient lightRagClient,
                GraphProperties graphProperties,
                SearchChannelProperties properties) {
            return new GraphSearchChannel(lightRagClient, graphProperties, properties);
        }

        /**
         * 向量写入图谱同步织入器：容器里存在 {@link LightRagClient} 时把
         * {@code VectorStoreService} 包成 {@code GraphSyncingVectorStoreService}。
         *
         * <p>BeanPostProcessor 实例化早于普通单例，用 {@code static} 工厂方法避免提前实例化本配置类；
         * 客户端通过 {@link ObjectProvider} 惰性解析，不把织入器的创建顺序绑死在客户端上。
         */
        @Bean
        public static GraphSyncVectorStorePostProcessor graphSyncVectorStorePostProcessor(
                ObjectProvider<LightRagClient> lightRagClientProvider) {
            return new GraphSyncVectorStorePostProcessor(lightRagClientProvider);
        }
    }

    /**
     * 启动期连接配置检查。
     *
     * <p>用 {@link BeanFactoryPostProcessor} 而不是普通单例，是为了让失败发生在
     * {@code MilvusClientV2}/{@code ElasticsearchClient} 等客户端创建之前：
     * 显式空值不会先撞上底层 SDK 的模糊报错，而是直接指名配置键。
     *
     * <p>{@code EnvironmentAware} 由容器在 BFPP 调用前注入；不依赖 {@code @Value}
     * 占位符解析顺序。
     */
    private static final class RequiredBackendProperty implements BeanFactoryPostProcessor, EnvironmentAware {

        private final String key;
        private final boolean missingAlsoFails;
        private final String failureMessage;
        private Environment environment;

        private RequiredBackendProperty(String key, boolean missingAlsoFails, String failureMessage) {
            this.key = key;
            this.missingAlsoFails = missingAlsoFails;
            this.failureMessage = failureMessage;
        }

        @Override
        public void setEnvironment(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            String value = environment == null ? null : environment.getProperty(key);
            // 有代码默认值的键：缺席合法，显式空白非法；无默认值的键：缺席也是失败。
            boolean invalid = missingAlsoFails
                    ? !StringUtils.hasText(value)
                    : value != null && !StringUtils.hasText(value);
            if (invalid) {
                throw new IllegalStateException(failureMessage);
            }
        }
    }
}
