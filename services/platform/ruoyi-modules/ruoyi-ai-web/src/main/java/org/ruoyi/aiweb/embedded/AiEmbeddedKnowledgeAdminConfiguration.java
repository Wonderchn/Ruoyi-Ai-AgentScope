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
import com.nageoffer.ai.ragent.core.chunk.ChunkingService;
import com.nageoffer.ai.ragent.core.chunk.blockaware.BlockAwareChunkerDispatcher;
import com.nageoffer.ai.ragent.core.chunk.blockaware.ChunkPacker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.CodeChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.HeadingChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.HeadingHandler;
import com.nageoffer.ai.ragent.core.chunk.blockaware.HtmlTableChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.ImageChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.ListChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.ParagraphChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.TableChunker;
import com.nageoffer.ai.ragent.core.ingest.DefaultIngestionKernel;
import com.nageoffer.ai.ragent.core.ingest.sink.ChunkIndexWriter;
import com.nageoffer.ai.ragent.core.parser.CsvDocumentParser;
import com.nageoffer.ai.ragent.core.parser.MarkdownDocumentParser;
import com.nageoffer.ai.ragent.core.parser.TikaDocumentParser;
import com.nageoffer.ai.ragent.core.parser.excel.ExcelDocumentParser;
import com.nageoffer.ai.ragent.core.parser.image.ImageDocumentParser;
import com.nageoffer.ai.ragent.core.parser.image.ImageParseProperties;
import com.nageoffer.ai.ragent.core.parser.mineru.MinerUClient;
import com.nageoffer.ai.ragent.core.parser.mineru.MinerUDocumentParser;
import com.nageoffer.ai.ragent.core.parser.mineru.MinerUPollingExecutor;
import com.nageoffer.ai.ragent.core.parser.mineru.MinerUProperties;
import com.nageoffer.ai.ragent.core.parser.mineru.MinerUResultUnpacker;
import com.nageoffer.ai.ragent.core.parser.registry.ParserRegistry;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer;
import com.nageoffer.ai.ragent.ingestion.engine.ConditionEvaluator;
import com.nageoffer.ai.ragent.ingestion.engine.IngestionEngine;
import com.nageoffer.ai.ragent.ingestion.engine.NodeOutputExtractor;
import com.nageoffer.ai.ragent.ingestion.node.ChunkerNode;
import com.nageoffer.ai.ragent.ingestion.node.EnhancerNode;
import com.nageoffer.ai.ragent.ingestion.node.EnricherNode;
import com.nageoffer.ai.ragent.ingestion.node.FetcherNode;
import com.nageoffer.ai.ragent.ingestion.node.IndexerNode;
import com.nageoffer.ai.ragent.ingestion.node.ParserNode;
import com.nageoffer.ai.ragent.ingestion.strategy.fetcher.FeishuFetcher;
import com.nageoffer.ai.ragent.ingestion.strategy.fetcher.HttpUrlFetcher;
import com.nageoffer.ai.ragent.ingestion.util.HttpClientHelper;
import com.nageoffer.ai.ragent.knowledge.config.KnowledgeScheduleProperties;
import com.nageoffer.ai.ragent.knowledge.controller.KnowledgeBaseController;
import com.nageoffer.ai.ragent.knowledge.controller.KnowledgeChunkController;
import com.nageoffer.ai.ragent.knowledge.controller.KnowledgeDocumentController;
import com.nageoffer.ai.ragent.knowledge.handler.RemoteFileFetcher;
import com.nageoffer.ai.ragent.knowledge.service.impl.KnowledgeBaseServiceImpl;
import com.nageoffer.ai.ragent.knowledge.service.impl.KnowledgeChunkServiceImpl;
import com.nageoffer.ai.ragent.knowledge.service.impl.KnowledgeDocumentScheduleServiceImpl;
import com.nageoffer.ai.ragent.knowledge.service.impl.KnowledgeDocumentServiceImpl;
import com.nageoffer.ai.ragent.knowledge.support.IngestionSpecCodec;
import com.nageoffer.ai.ragent.knowledge.support.IngestionSpecSchemaProvider;
import com.nageoffer.ai.ragent.knowledge.support.VectorTargetResolver;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.config.RagStorageProperties;
import com.nageoffer.ai.ragent.rag.core.storage.ObjectStorageClient;
import com.nageoffer.ai.ragent.rag.core.vector.sink.VectorChunkSink;
import com.nageoffer.ai.ragent.rag.service.impl.DefaultFileStorageService;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.util.StringUtils;

import java.util.function.Consumer;

/**
 * 知识<b>管理面</b>（{@code /knowledge-base/**}）的内嵌装配（RW-04-R1；闭包于 S2-F05-A1 闭合）。
 *
 * <p><b>为什么需要这一组。</b>三个知识控制器
 * （{@link KnowledgeBaseController}/{@link KnowledgeDocumentController}/{@link KnowledgeChunkController}）
 * 都在 {@code com.nageoffer.ai.ragent.knowledge.controller} 下，而 platform 的扫描根是
 * {@code org.ruoyi} ⇒ 内嵌形态里它们<b>从来不是 bean</b>，方法级路径一条都不存在（404）。
 * 这不是"接口写错"，是"没装配"。
 *
 * <p><b>形态取自既有 precedent（{@code UploadController}）。</b>控制器带类级
 * {@code @RequestMapping("/internal/ai/v1")}（RW-04-R1 已改），本类显式登记 bean，
 * 公开面再由 {@code AiGatewayController} 白名单逐条放行为
 * {@code /api/ai/v1/knowledge-base/**}（单数 = 管理面；复数 {@code /knowledge-bases/**}
 * 是 {@code AiResourceController} 的 AI 资源面，两者不同面、不冲突）。
 * <b>不用</b>"保留裸路径 + 平台 Sa-Token 直挂"：裸路径不在
 * {@code DelegatedPrincipalFilter.PROTECTED_PREFIX} 之下，拿不到委托主体，
 * 而"缺主体必须拒绝"是硬约束。
 *
 * <p><b>分块管理面（{@link ChunkAdmin}，RW-04 起已装）。</b>
 * {@code KnowledgeChunkServiceImpl} ← {@code KnowledgeChunkMapper}/{@code KnowledgeDocumentMapper}/
 * {@code KnowledgeBaseMapper}（{@code AiEmbeddedMapperConfiguration} 的 {@code @MapperScan}
 * 覆盖 {@code knowledge.dao.mapper}）、{@code ChunkEmbeddingService}/{@code TokenCounterService}/
 * {@code VectorStoreService}（{@code AiEmbeddedKnowledgeConfiguration}，门控
 * {@code rag.vector.type=pg}）、{@code TransactionOperations}（Boot 的 {@code TransactionTemplate}）、
 * {@code BizChangeLogContext}（{@code AiEmbeddedAgentCatalogConfiguration} 已 {@code @Import}）
 * ⇒ 只剩 {@link VectorTargetResolver} 需要本类补一个 bean，故本组沿用
 * {@code rag.vector.type=pg} 判据，避免"控制器装了、向量后端没装"的半装配态。
 *
 * <p><b>知识库面与文档面（{@link FullAdmin}，S2-F05-A1 闭合）。</b>
 * 开关 {@code ai.embedded.knowledge-admin.full=true}，闭包按构造依赖<b>逐项核过</b>
 * （RW-04 期类注释只写了"15 个单例 bean 与 2 组集合元素"，那是一个没有出处清单的口径；
 * 以树内实际为准的完整闭包如下，本组 @Import 与它逐项对应）：
 * <ul>
 *   <li><b>对象存储</b>：{@code KnowledgeBaseServiceImpl} → {@code FileStorageService} →
 *       {@link DefaultFileStorageService} → {@code ObjectStorageClient} + {@link RagStorageProperties}。
 *       内嵌档案用 {@link EmbeddedFsObjectStorageClient}（{@code rag.storage.type=fs}，
 *       无凭据、本地目录，与 P2 的 {@code FsPrivateObjectStore} 同款决策）；
 *       <b>真实 S3/OSS 未接通</b>——{@code rag.storage.type=s3|oss} 的内嵌接线与验收属 F22 op3；</li>
 *   <li><b>解析器闭包</b>：{@link ParserRegistry} ← {@code List<DocumentParser>}（6 个实现：
 *       Tika/CSV/Markdown/Excel/<b>Image</b>（← {@code VlmService} + {@code FileStorageService} +
 *       {@code ImageParseProperties}）/<b>MinerU</b>（← {@code MinerUClient} + {@code MinerUPollingExecutor}
 *       + {@code MinerUResultUnpacker} + {@code MinerUProperties} + {@code RedissonClient}}）。
 *       6 个都必须装：{@code ParserRegistry} 的启动自检要求 {@code SUPPORTED_EXTENSIONS}
 *       每个扩展名探测出的 MIME 都被"精确认领"，缺 MinerU 则 {@code .pdf} 无人认领即启动失败
 *       （这正是"缺 bean 响亮失败"的一例，不是可以省略的部件）；</li>
 *   <li><b>分块闭包</b>：{@code ChunkingService} ← {@code BlockAwareChunkerDispatcher} ←
 *       {@code HeadingHandler} + {@code ChunkPacker} + {@code List<BlockChunker<?>>}（7 个）；</li>
 *   <li><b>摄取闭包</b>：{@code DefaultIngestionKernel} + {@code ChunkIndexWriter}
 *       （← {@code List<ChunkSink>}：{@code RelationalChunkSink}（已装）+ {@link VectorChunkSink}）
 *       + {@code IngestionSpecCodec} + {@code IngestionSpecSchemaProvider}；</li>
 *   <li><b>管线引擎闭包</b>：{@code IngestionEngine} ← {@code List<IngestionNode>}（6 个：
 *       Chunker/Enhancer/Enricher/Fetcher/Indexer/Parser）+ {@code ConditionEvaluator}
 *       + {@code NodeOutputExtractor}；{@code FetcherNode} 还要 {@code List<DocumentFetcher>}
 *       （{@code HttpUrlFetcher}/{@code FeishuFetcher}）；</li>
 *   <li><b>远程取件与计划属性</b>：{@code RemoteFileFetcher} ← {@code HttpClientHelper}
 *       （+ 无 {@code @Qualifier} 的 {@code OkHttpClient}，由本组显式钉住 {@code syncHttpClient}）；
 *       {@code KnowledgeScheduleProperties} 与 {@code KnowledgeDocumentScheduleServiceImpl}
 *       ——后者是文档服务的 22 个构造依赖之一，<b>只做读写装配、不开调度</b>
 *       （调度作业仍属 {@code AiEmbeddedKnowledgeScheduleConfiguration} 的 FullAdmin × legacy 门，
 *       见 {@code FullAdmin#knowledgeAdminMessageQueueProducer} 同款"同闭包不拆两套门"纪律）；</li>
 *   <li><b>消息队列</b>：两个服务的事务消息（知识库删除 / 文档重解析）经
 *       {@link MessageQueueProducer} 承载<b>本地事务本身</b>（软删/置 RUNNING 都在回调里）,
 *       故该端口必须可解析。内嵌档案没有 broker（RocketMQ 属部署决策 C12.5-5），
 *       由 {@code FullAdmin#knowledgeAdminMessageQueueProducer} 显式失败关闭；</li>
 *   <li>其余协作对象由既有内嵌组提供：{@code IngestionPipelineService}（{@code AiEmbeddedIngestionConfiguration}，F06-A1）、
 *       {@code VectorStoreService}/{@code VectorStoreAdmin}/{@code ChunkEmbeddingService}/
 *       {@code TokenCounterService}/{@code RAGDefaultProperties}/{@code RelationalChunkSink}
 *       （{@code AiEmbeddedKnowledgeConfiguration}）、{@code LLMService}（{@code AiEmbeddedModelConfiguration}）、
 *       {@code DeliveryPermits}（{@code AiEmbeddedRunConfiguration} 的 P2 组）、
 *       {@code BizChangeLogContext}、全部 Mapper（{@code AiEmbeddedMapperConfiguration}）、
 *       {@code RedissonClient}、{@code ObjectMapper}、{@code TransactionOperations}。</li>
 * </ul>
 * 打开开关后若依赖闭包仍缺，启动阶段即以"no qualifying bean"失败——这是刻意的
 * fail-closed：宁可启动失败，也不要一个"装了但永远 404/500"的管理面。
 *
 * <p><b>授权不在本层</b>：内层前缀对外由 {@code AiInternalAccessBoundaryFilter} 关成 404，
 * 公开面必须过网关白名单 + {@code AiCanonicalAction} 的 scope 比较，因此内层 controller
 * 不加 {@code @SaCheckPermission}（与 {@code AiResourceController}/{@code AgentChatController} 同形）。
 * 逐条"公开路由 → canonical 动作"映射见 RW-04 报告 §11.4；S2-F05-A1 放行的 17 条
 * （KB 5 + 文档 12）见 {@code AiGatewayController.ROUTES} 与
 * {@code LocalWhitelistEnvelopeShapeTest#knowledgeAdminFacesAreReleasedAndCounted}。
 *
 * <p><b>本类必须由 T0 追加到</b>
 * {@code ruoyi-ai-web/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 才生效（该文件不在本卡租约内）。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiEmbeddedKnowledgeAdminConfiguration {

    /**
     * 分块管理面：闭包已闭合（见类注释），随内嵌 local 传输 + PG 向量后端 + <b>p2 交付面</b>装配。
     *
     * <p>用 {@code @Import} 登记实现类与控制器，让构造注入照常工作——
     * 与 {@code AiEmbeddedAgentCatalogConfiguration} 的写法一致，避免手写 {@code @Bean}
     * 方法时把参数顺序抄错。
     *
     * <p><b>为什么多一个 {@code p2.enabled} 判据（RW-04-R3 / D2）。</b>
     * {@code KnowledgeChunkController} 的列表方法在网关上走<b>字节分支</b>，必须铸造交付回执头，
     * 其依赖 {@link com.nageoffer.ai.ragent.runtime.web.DeliveryPermits} 由
     * {@code AiEmbeddedRunConfiguration.LocalTransport.P2Enabled} 提供（门控 {@code p2.enabled}）。
     * 不跟着门控的话，{@code p2.enabled=false} 的部署会在<b>启动阶段</b>因缺 bean 失败；
     * 跟着门控则表现为"交付面不在 ⇒ 该面不装"（与 documents 面同形，fail-closed 且可解释）。
     * 内嵌档案 {@code application-embedded.yml} 显式 {@code p2.enabled: true}，实际部署仍是可达面。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEmbeddedLocal
    @ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
    @ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
    @Import({KnowledgeChunkServiceImpl.class, KnowledgeChunkController.class})
    static class ChunkAdmin {

        /**
         * 向量落点解析：{@code AiEmbeddedKnowledgeConfiguration} 只登记了
         * {@link RAGDefaultProperties}，解析器本身要在 {@code org.ruoyi} 侧补登记。
         *
         * <p>维度是 fail-closed 的：{@code rag.default.dimension} 缺失时
         * {@link VectorTargetResolver} 直接拒绝，不猜一个维度去写库。
         */
        @Bean
        @ConditionalOnMissingBean
        public VectorTargetResolver vectorTargetResolver(RAGDefaultProperties ragDefaultProperties) {
            return new VectorTargetResolver(ragDefaultProperties);
        }
    }

    /**
     * 知识库面 + 文档面：{@code ai.embedded.knowledge-admin.full=true} 时整闭包装配（默认关）。
     *
     * <p>打开后若依赖闭包仍缺，启动阶段即以"no qualifying bean"失败——这是刻意的
     * fail-closed：宁可启动失败，也不要一个"装了但永远 404/500"的管理面。
     * 闭包清单与逐项理由见类注释。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEmbeddedLocal
    @ConditionalOnProperty(name = "ai.embedded.knowledge-admin.full", havingValue = "true")
    @EnableConfigurationProperties({RagStorageProperties.class, KnowledgeScheduleProperties.class,
            MinerUProperties.class, ImageParseProperties.class})
    @Import({KnowledgeBaseServiceImpl.class, KnowledgeBaseController.class,
            KnowledgeDocumentServiceImpl.class, KnowledgeDocumentController.class,
            // 对象存储（后端由 rag.storage.type 决定；内嵌档案 = fs，见本组 fsObjectStorageClient）
            DefaultFileStorageService.class,
            // 解析器闭包
            ParserRegistry.class, TikaDocumentParser.class, CsvDocumentParser.class,
            MarkdownDocumentParser.class, ExcelDocumentParser.class, ImageDocumentParser.class,
            MinerUDocumentParser.class, MinerUClient.class, MinerUPollingExecutor.class,
            MinerUResultUnpacker.class,
            // 分块闭包
            ChunkingService.class, BlockAwareChunkerDispatcher.class, HeadingHandler.class,
            ChunkPacker.class, CodeChunker.class, HeadingChunker.class, HtmlTableChunker.class,
            ImageChunker.class, ListChunker.class, ParagraphChunker.class, TableChunker.class,
            // 摄取闭包
            ChunkIndexWriter.class, VectorChunkSink.class, DefaultIngestionKernel.class,
            IngestionSpecCodec.class, IngestionSpecSchemaProvider.class,
            // 管线引擎闭包
            IngestionEngine.class, ChunkerNode.class, EnhancerNode.class, EnricherNode.class,
            FetcherNode.class, IndexerNode.class, ParserNode.class, ConditionEvaluator.class,
            NodeOutputExtractor.class,
            // 远程取件闭包（三件 HTTP 协作件走本组显式 @Bean，见 httpClientHelper）
            RemoteFileFetcher.class,
            // 计划读写面（只装配服务与属性；调度作业仍属 ScheduleEnabled 门）
            KnowledgeDocumentScheduleServiceImpl.class})
    static class FullAdmin {

        /**
         * 同步 HTTP 协作件：{@link HttpClientHelper} 的构造参数按<b>类型</b>取 {@code OkHttpClient}，
         * 而内嵌模型组里类型候选有两个且 {@code streamingHttpClient} 带 {@code @Primary}
         * （无读超时/无调用超时）——按 {@code @Import} 装配会让远程取件链静默拿到流式客户端。
         * 因此这三件<b>显式构造</b>并钉住 {@code syncHttpClient}（连接 10s/读 30s/调用 45s），
         * 与 {@code EmbeddedChatClientInjectionDisambiguationTest} 记录的隐患同一处置方向：
         * 不靠"按类型碰巧选对"，靠显式限定。
         */
        @Bean
        @ConditionalOnMissingBean
        public HttpClientHelper httpClientHelper(@Qualifier("syncHttpClient") OkHttpClient syncHttpClient) {
            return new HttpClientHelper(syncHttpClient);
        }

        @Bean
        @ConditionalOnMissingBean
        public HttpUrlFetcher httpUrlFetcher(HttpClientHelper httpClientHelper) {
            return new HttpUrlFetcher(httpClientHelper);
        }

        @Bean
        @ConditionalOnMissingBean
        public FeishuFetcher feishuFetcher(@Qualifier("syncHttpClient") OkHttpClient syncHttpClient,
                                          HttpClientHelper httpClientHelper) {
            return new FeishuFetcher(syncHttpClient, httpClientHelper);
        }

        /**
         * 内嵌档案的本地对象存储后端（{@code rag.storage.type=fs}）。
         *
         * <p>只在显式选择 fs 时注册：{@code rag.storage.type=s3|oss} 时本 bean 缺席，
         * 由那两个后端自己的实现承接（内嵌接线属 F22 op3，本切片不冒充已接通）。
         */
        @Bean
        @ConditionalOnMissingBean(ObjectStorageClient.class)
        @ConditionalOnProperty(name = "rag.storage.type", havingValue = "fs")
        public ObjectStorageClient fsObjectStorageClient(
                @Value("${rag.storage.root:./data/kb-objects}") String root) {
            return new EmbeddedFsObjectStorageClient(root);
        }

        /**
         * 内嵌形态的消息生产者：<b>没有 broker 时不静默降级，发送即显式失败</b>。
         *
         * <p><b>为什么需要这个 bean（而不是让端口缺席）。</b>知识库删除与文档重解析走
         * {@code MessageQueueProducer.sendInTransaction(...)}：<b>本地事务本身在它的回调里</b>
         * （软删 KB 行 / 置文档 RUNNING + upsert 计划行）。端口缺席会让整个应用启动失败
         * （两个服务的构造依赖），端口存在但不发送则会把"事件永远不离开本进程"做成假成功
         * （D07 红线）。因此：装配一个"发送即拒绝"的生产者，让这两个操作拿到<b>确定的失败</b>，
         * 其余 15 条管理面路由不受影响。
         *
         * <p><b>开门条件与真实生产者互补。</b>{@code ai.integration.legacy-listeners-enabled=true}
         * 时本 bean 不注册，由 {@code AiEmbeddedFeedbackConfiguration.LegacyFeedbackProducerAssembly}
         * 装配真实 RocketMQ 生产者（那是显式部署决策，C12.5-5）；未开启（shipped 默认）时
         * 由本 bean 承担"内嵌无 broker"的显式拒绝语义。
         *
         * <p>失败形状：{@link ServiceException} → AI 信封 503（依赖不可用），
         * 网关 503 透传且原因进服务端日志；与 {@code MessageFeedbackServiceImpl.requireProducer()}
         * 的"消息链缺席时响亮拒绝"同一口径（那里端口可为 {@code null}，此处端口由本 bean 占位）。
         */
        @Bean
        @ConditionalOnMissingBean(MessageQueueProducer.class)
        @ConditionalOnProperty(name = "ai.integration.legacy-listeners-enabled",
                havingValue = "false", matchIfMissing = true)
        public MessageQueueProducer knowledgeAdminMessageQueueProducer() {
            return new EmbeddedBrokerAbsentMessageQueueProducer();
        }
    }

    /**
     * "内嵌无 broker"生产者：所有发送路径显式失败（见
     * {@link FullAdmin#knowledgeAdminMessageQueueProducer()}）。
     *
     * <p>刻意不实现成"本地直投/丢消息"：事务消息承载的是本地事务本身，
     * 只跑回调不投递会把下游事件静默吞掉；只投递不跑回调会丢掉本地写。
     * 两条路都是假成功，故选<b>不执行</b>。
     */
    static final class EmbeddedBrokerAbsentMessageQueueProducer implements MessageQueueProducer {

        @Override
        public org.apache.rocketmq.client.producer.SendResult send(String topic, String keys,
                                                                   String bizDesc, Object body) {
            throw unavailable(bizDesc);
        }

        @Override
        public void sendInTransaction(String topic, String keys, String bizDesc, Object body,
                                      Consumer<Object> localTransaction) {
            // 本地事务不执行：事务消息的"提交后投递"语义在本进程内无法保证，
            // 与其写库不投递（静默丢事件），不如整笔拒绝。
            throw unavailable(bizDesc);
        }

        private ServiceException unavailable(String bizDesc) {
            String what = StringUtils.hasText(bizDesc) ? bizDesc : "本次操作";
            // 不静默降级：客户端拿到确定的失败（503 依赖不可用），服务端日志留因由。
            return new ServiceException("内嵌形态未配置消息队列后端（broker 缺席），" + what + " 未受理");
        }
    }
}
