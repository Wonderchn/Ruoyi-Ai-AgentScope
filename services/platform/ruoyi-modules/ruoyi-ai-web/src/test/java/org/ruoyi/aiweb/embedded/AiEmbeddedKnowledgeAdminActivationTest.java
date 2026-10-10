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
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.core.chunk.ChunkingService;
import com.nageoffer.ai.ragent.core.ingest.DefaultIngestionKernel;
import com.nageoffer.ai.ragent.core.ingest.sink.ChunkIndexWriter;
import com.nageoffer.ai.ragent.core.parser.registry.ParserRegistry;
import com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.ingestion.engine.IngestionEngine;
import com.nageoffer.ai.ragent.ingestion.service.impl.IngestionPipelineServiceImpl;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.infra.vlm.VlmService;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionPipelineMapper;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionPipelineNodeMapper;
import com.nageoffer.ai.ragent.knowledge.controller.KnowledgeBaseController;
import com.nageoffer.ai.ragent.knowledge.controller.KnowledgeDocumentController;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentChunkLogMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentScheduleExecMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentScheduleMapper;
import com.nageoffer.ai.ragent.knowledge.handler.RemoteFileFetcher;
import com.nageoffer.ai.ragent.knowledge.service.impl.KnowledgeBaseServiceImpl;
import com.nageoffer.ai.ragent.knowledge.service.impl.KnowledgeDocumentScheduleServiceImpl;
import com.nageoffer.ai.ragent.knowledge.service.impl.KnowledgeDocumentServiceImpl;
import com.nageoffer.ai.ragent.knowledge.support.IngestionSpecSchemaProvider;
import com.nageoffer.ai.ragent.rag.core.storage.ObjectStorageClient;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S2-F05-A1：知识管理面（知识库面 + 文档面）**FullAdmin 闭包**的装配判据
 * （与 {@code AiEmbeddedIngestionActivationTest} / {@code AiEmbeddedKnowledgeScheduleActivationTest} 同形）。
 *
 * <p><b>本判据回答的问题。</b>{@code ai.embedded.knowledge-admin.full=true} 打开时，
 * 「知识库面 + 文档面」的构造闭包是否真的闭合——即整张依赖图能否被装配出来。
 * 这正是 RW-04 期把它默认关闭的原因（当时类注释只写了"缺 15 个单例 bean 与 2 组集合元素"
 * 这种没有出处清单的口径）。本判据把闭包成员逐项断言：缺一个就红，
 * 且红在 {@code hasNotFailed()}（"no qualifying bean"）而不是某个后置断言上。
 *
 * <p><b>装的是真实实现类，协作对象是测试替身。</b>网络/持久层协作对象
 * （Mapper、JdbcTemplate、Redisson、模型服务、RevocationGuard）在这里是 mock，
 * 但<b>所有被核验的闭包成员都是产品类本身</b>：
 * {@link ParserRegistry}（含启动自检——6 个解析器少一个就启动失败）、
 * {@link ChunkingService}、{@link ChunkIndexWriter}、{@link DefaultIngestionKernel}、
 * {@link IngestionEngine}（含 6 个 {@code IngestionNode} 与 2 个 {@code DocumentFetcher}）、
 * {@link RemoteFileFetcher}、{@link IngestionSpecSchemaProvider}、对象存储（fs 后端）、
 * 计划读写面（{@code KnowledgeDocumentScheduleServiceImpl}）与两个控制器。
 *
 * <p><b>门控关掉时整组缺席，且不要求协作对象在场</b>（先例原文：关掉开关的应用起不来
 * 不是 fail-closed，是 bug）。<b>执行前置</b>（{@code p2.enabled=true}、
 * {@code rag.vector.type=pg}、{@code ai.model.enabled=true}、{@code rag.storage.type=fs}）
 * 不在本组门控里：它们是既有内嵌组的条件，缺则装配期以"no qualifying bean"响亮失败
 * （本类第一条用例把这份前置显式摆出来，不在别处静默假设）。
 */
@Tag("dev")
class AiEmbeddedKnowledgeAdminActivationTest {

    /** 闭包所需的内嵌档案前置（与 application-embedded.yml 的实际取值一致）。 */
    private static final String[] SHIPPED_GATES = {
            "ai.integration.enabled=true",
            "ai.integration.transport=local",
            "ai.embedded.knowledge-admin.full=true",
            "p2.enabled=true",
            "rag.vector.type=pg",
            "rag.storage.type=fs",
            "rag.storage.root=target/f05-activation-kb-objects",
            "rag.default.dimension=1536"
    };

    private final ApplicationContextRunner configurations = new ApplicationContextRunner()
            // 裸 runner 缺 Boot 的转换服务：{@code RemoteFileFetcher.maxFileSize} 是
            // {@code @Value DataSize}（{@code spring.servlet.multipart.max-file-size}），
            // 没有转换器时 String→DataSize 转换失败会让容器起不来——那是夹具缺陷，不是被测事实
            // （先例：{@code AiEmbeddedKnowledgeScheduleActivationTest#baseRunner}）。
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(AiEmbeddedKnowledgeAdminConfiguration.class,
                    AiEmbeddedKnowledgeConfiguration.class,
                    AiEmbeddedIngestionConfiguration.class)
            .withPropertyValues(SHIPPED_GATES);

    private ApplicationContextRunner collaborators() {
        return configurations
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(BizChangeLogContext.class, () -> new BizChangeLogContext(new ObjectMapper()))
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(TransactionOperations.class, () -> mock(TransactionOperations.class))
                .withBean(EmbeddingService.class, () -> mock(EmbeddingService.class))
                .withBean(LLMService.class, () -> mock(LLMService.class))
                .withBean(VlmService.class, () -> mock(VlmService.class))
                .withBean("syncHttpClient", OkHttpClient.class,
                        () -> new OkHttpClient.Builder().readTimeout(Duration.ofSeconds(30)).build())
                .withBean(KnowledgeBaseMapper.class, () -> mock(KnowledgeBaseMapper.class))
                .withBean(KnowledgeDocumentMapper.class, () -> mock(KnowledgeDocumentMapper.class))
                .withBean(KnowledgeChunkMapper.class, () -> mock(KnowledgeChunkMapper.class))
                .withBean(KnowledgeDocumentChunkLogMapper.class,
                        () -> mock(KnowledgeDocumentChunkLogMapper.class))
                .withBean(KnowledgeDocumentScheduleMapper.class,
                        () -> mock(KnowledgeDocumentScheduleMapper.class))
                .withBean(KnowledgeDocumentScheduleExecMapper.class,
                        () -> mock(KnowledgeDocumentScheduleExecMapper.class))
                .withBean(IngestionPipelineMapper.class, () -> mock(IngestionPipelineMapper.class))
                .withBean(IngestionPipelineNodeMapper.class, () -> mock(IngestionPipelineNodeMapper.class))
                .withBean(RedissonClient.class, AiEmbeddedKnowledgeAdminActivationTest::mockRedisson)
                .withBean(DeliveryPermits.class, AiEmbeddedKnowledgeAdminActivationTest::permissivePermits);
    }

    /**
     * MinerU 解析器的 {@code @PostConstruct} 会取 Redis 信号量做并发限流初始化
     * （与独立应用同一行为）；替身必须回一个非空信号量，否则装配期 NPE——
     * 那是"替身不完整"，不是产品缺陷。
     */
    private static RedissonClient mockRedisson() {
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getPermitExpirableSemaphore(anyString()))
                .thenReturn(mock(RPermitExpirableSemaphore.class));
        return redisson;
    }

    private static DeliveryPermits permissivePermits() {
        RevocationGuard guard = mock(RevocationGuard.class);
        when(guard.enter(org.mockito.ArgumentMatchers.any(), anyString(), anyString()))
                .thenAnswer(call -> new RevocationGuard.Operation(guard,
                        java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString()));
        @SuppressWarnings("unchecked")
        ObjectProvider<RevocationGuard> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(guard);
        return new DeliveryPermits(provider);
    }

    @Test
    void fullAdminClosureAssemblesWithEveryMemberPresent() {
        collaborators().run(context -> {
            assertThat(context).hasNotFailed();

            // 公开面两端（控制器）与服务端两端（服务实现）
            for (Class<?> type : new Class<?>[]{KnowledgeBaseController.class, KnowledgeDocumentController.class,
                    KnowledgeBaseServiceImpl.class, KnowledgeDocumentServiceImpl.class}) {
                assertThat(context.getBeansOfType(type))
                        .as(type.getSimpleName() + " 必须恰一个 bean").hasSize(1);
            }

            // 闭包成员逐项：解析器 → 分块 → 摄取 → 引擎 → 取件 → 计划读写 → 对象存储
            for (Class<?> type : new Class<?>[]{ParserRegistry.class, ChunkingService.class,
                    ChunkIndexWriter.class, DefaultIngestionKernel.class, IngestionEngine.class,
                    RemoteFileFetcher.class, IngestionSpecSchemaProvider.class,
                    KnowledgeDocumentScheduleServiceImpl.class, ObjectStorageClient.class,
                    IngestionPipelineServiceImpl.class, MessageQueueProducer.class}) {
                assertThat(context.getBeansOfType(type))
                        .as(type.getSimpleName() + " 必须在 FullAdmin 闭包内（缺则管理面启动失败）")
                        .hasSize(1);
            }
            assertThat(context.getBean(ObjectStorageClient.class))
                    .as("内嵌档案的对象存储后端必须是本地 fs 实现（真实 S3/OSS 属 F22 op3）")
                    .isInstanceOf(EmbeddedFsObjectStorageClient.class);
        });
    }

    @Test
    void integrationGatesCloseTheFullAdminSurfaceWithoutRequiringCollaborators() {
        // 这两条门同时关掉知识/向量组与摄取组（都是 local 传输下的嵌套组），
        // 因此关态上下文**不带任何协作替身**也必须起得来：
        // "关掉开关的应用起不来"不是 fail-closed，是 bug。
        for (String off : new String[]{"ai.integration.enabled=false", "ai.integration.transport=http"}) {
            configurations.withPropertyValues(off).run(context -> {
                assertThat(context).as("门控 %s 关闭时必须整体缺席", off).hasNotFailed();
                assertThat(context)
                        .doesNotHaveBean(KnowledgeBaseController.class)
                        .doesNotHaveBean(KnowledgeDocumentController.class)
                        .doesNotHaveBean(KnowledgeBaseServiceImpl.class)
                        .doesNotHaveBean(KnowledgeDocumentServiceImpl.class)
                        .doesNotHaveBean(ParserRegistry.class)
                        .doesNotHaveBean(IngestionEngine.class);
            });
        }
    }

    @Test
    void fullFlagClosesOnlyTheFullClosureAndKeepsTheChunkSurface() {
        // 分块管理面（ChunkAdmin，RW-04-R1）是独立子门：只关 full 时它必须仍在
        // ——知识管理面的放行不得以撤掉既有分块面为代价。
        // （知识/向量组仍开着，故这里要带协作替身，与上面两条"门全关"的关态不同。）
        collaborators().withPropertyValues("ai.embedded.knowledge-admin.full=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(
                    com.nageoffer.ai.ragent.knowledge.controller.KnowledgeChunkController.class);
            assertThat(context)
                    .doesNotHaveBean(KnowledgeBaseController.class)
                    .doesNotHaveBean(KnowledgeDocumentController.class)
                    .doesNotHaveBean(KnowledgeBaseServiceImpl.class)
                    .doesNotHaveBean(KnowledgeDocumentServiceImpl.class)
                    .doesNotHaveBean(ParserRegistry.class)
                    .doesNotHaveBean(IngestionEngine.class)
                    .doesNotHaveBean(MessageQueueProducer.class);
        });
    }
}
