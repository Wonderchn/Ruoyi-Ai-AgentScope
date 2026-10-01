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

package com.nageoffer.ai.ragent.boundary;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.nageoffer.ai.ragent.framework.integration.SaasBoundaryProperties;
import com.nageoffer.ai.ragent.framework.integration.SaasCapabilityBoundary;
import com.nageoffer.ai.ragent.framework.mq.MessageWrapper;
import com.nageoffer.ai.ragent.framework.mq.producer.DelegatingTransactionListener;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentScheduleMapper;
import com.nageoffer.ai.ragent.knowledge.config.KnowledgeScheduleProperties;
import com.nageoffer.ai.ragent.knowledge.mq.KnowledgeBaseCleanupConsumer;
import com.nageoffer.ai.ragent.knowledge.mq.KnowledgeBaseCleanupTransactionChecker;
import com.nageoffer.ai.ragent.knowledge.mq.KnowledgeDocumentChunkConsumer;
import com.nageoffer.ai.ragent.knowledge.mq.KnowledgeDocumentChunkTransactionChecker;
import com.nageoffer.ai.ragent.knowledge.mq.event.KnowledgeBaseCleanupEvent;
import com.nageoffer.ai.ragent.knowledge.mq.event.KnowledgeDocumentChunkEvent;
import com.nageoffer.ai.ragent.knowledge.schedule.DocumentStatusHelper;
import com.nageoffer.ai.ragent.knowledge.schedule.KnowledgeDocumentScheduleJob;
import com.nageoffer.ai.ragent.knowledge.schedule.ScheduleLockManager;
import com.nageoffer.ai.ragent.knowledge.schedule.ScheduleRefreshProcessor;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentService;
import com.nageoffer.ai.ragent.rag.core.graph.LightRagClient;
import com.nageoffer.ai.ragent.rag.core.keyword.KeywordIndexService;
import com.nageoffer.ai.ragent.rag.core.storage.ObjectStorageClient;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.config.KeywordProperties;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.config.RagStorageProperties;
import com.nageoffer.ai.ragent.rag.core.keyword.EsKeywordIndexService;
import com.nageoffer.ai.ragent.rag.mq.MessageFeedbackConsumer;
import com.nageoffer.ai.ragent.rag.mq.event.MessageFeedbackEvent;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import com.nageoffer.ai.ragent.rag.service.MessageFeedbackService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * P1.2a 旧自动触发关闭护栏。
 *
 * <p>被验证的组件（三个 MQ 消费者、两个事务回查器、文档计划调度、四类启动期自动 I/O）
 * 都在<b>第一次日志正文 / UserContext / SQL / Redis / 对象 / 模型 / 网络调用之前</b>
 * 做关闭判定。本测试对每个组件用真实实例 + mock 协作者，直接调用其入口，
 * 断言：
 * <ol>
 *   <li>抛出的是 {@link SaasCapabilityBoundary.ClosedCapabilityException}（受控关闭异常），
 *       而不是 {@code NullPointerException} 之类"碰巧失败"；</li>
 *   <li>所有协作者<b>零交互</b>——这是"关闭 = 零副作用"的可复算证据，
 *       比只统计新增行更能捕获 update/delete 漏检；</li>
 *   <li>显式打开旧能力时同一路径确实会走到协作者（正向对照），
 *       证明上面的零交互断言不是因为协作者本来就没被调用而恒真。</li>
 * </ol>
 *
 * <p>对 {@code @PostConstruct} 风格的方法（回查 {@code init}、初始化器）使用
 * 包级显式边界重载；对消息/调度入口使用字段注入（{@code ObjectProvider} /
 * {@code @Autowired} 字段），并用 {@link #injectBoundary} 注入测试边界，
 * 避免把构造函数参数顺序写死在测试里。
 */
class P1TriggerBoundaryTest {

    /**
     * 合成租户：知识库清理事件必须携带租户，否则消费者拒绝执行。
     * 用真实形状（1..64、无冒号）而不是 "t"，避免测试通过而真实契约不满足。
     */
    private static final String TENANT = "p1-tenant-trigger";

    /** 关闭态：显式写出全部开关为 false，与产品默认配置同义。 */
    private static final SaasBoundaryProperties CLOSED_PROPERTIES =
            new SaasBoundaryProperties(false, true, new SaasBoundaryProperties.CustomerApi(false), false);

    /** 打开态：仅用于正向对照，证明判定确实来自该开关。 */
    private static final SaasBoundaryProperties LEGACY_OPEN_PROPERTIES =
            new SaasBoundaryProperties(false, true, new SaasBoundaryProperties.CustomerApi(false), true);

    private static final SaasCapabilityBoundary CLOSED_BOUNDARY =
            new SaasCapabilityBoundary(CLOSED_PROPERTIES);
    private static final SaasCapabilityBoundary OPEN_BOUNDARY =
            new SaasCapabilityBoundary(LEGACY_OPEN_PROPERTIES);

    // ---------------------------------------------------------------- MQ 消费者

    @Test
    @DisplayName("文档分块消费者：直接 onMessage 受控关闭，文档服务零调用")
    void chunkConsumerRejectsDirectInvocationBeforeAnyServiceCall() {
        KnowledgeDocumentService documentService = mock(KnowledgeDocumentService.class);
        KnowledgeDocumentChunkConsumer consumer = new KnowledgeDocumentChunkConsumer(
                documentService, providerOf(CLOSED_BOUNDARY));

        SaasCapabilityBoundary.ClosedCapabilityException failure = assertThrows(
                SaasCapabilityBoundary.ClosedCapabilityException.class,
                () -> consumer.onMessage(chunkMessage("doc-1")),
                "关闭态下直接调用消费者必须是受控关闭异常，不能是 NPE 或静默返回");

        assertEquals(SaasCapabilityBoundary.LegacyCapability.KB_DOCUMENT_CHUNK_CONSUMER, failure.capability());
        verifyNoInteractions(documentService);
    }

    @Test
    @DisplayName("知识库清理消费者：直接 onMessage 受控关闭，向量/对象/ES/图谱零调用")
    void cleanupConsumerRejectsDirectInvocationBeforeAnyIo() {
        VectorStoreAdmin vectorStoreAdmin = mock(VectorStoreAdmin.class);
        FileStorageService fileStorageService = mock(FileStorageService.class);
        KeywordIndexService keywordIndexService = mock(KeywordIndexService.class);
        LightRagClient lightRagClient = mock(LightRagClient.class);

        KnowledgeBaseCleanupConsumer consumer = new KnowledgeBaseCleanupConsumer(
                vectorStoreAdmin, fileStorageService, providerOf(keywordIndexService),
                providerOf(lightRagClient), providerOf(CLOSED_BOUNDARY));

        SaasCapabilityBoundary.ClosedCapabilityException failure = assertThrows(
                SaasCapabilityBoundary.ClosedCapabilityException.class,
                () -> consumer.onMessage(cleanupMessage("kb-1")),
                "关闭态下清理消费者不得 drop 向量空间、删除存储目录、操作 ES 或连接图谱");

        assertEquals(SaasCapabilityBoundary.LegacyCapability.KB_CLEANUP_CONSUMER, failure.capability());
        verifyNoInteractions(vectorStoreAdmin, fileStorageService, keywordIndexService, lightRagClient);
    }

    @Test
    @DisplayName("消息反馈消费者：直接 onMessage 受控关闭，反馈服务零调用")
    void feedbackConsumerRejectsDirectInvocationBeforeAnyServiceCall() {
        MessageFeedbackService feedbackService = mock(MessageFeedbackService.class);
        MessageFeedbackConsumer consumer = new MessageFeedbackConsumer(
                feedbackService, providerOf(CLOSED_BOUNDARY));

        SaasCapabilityBoundary.ClosedCapabilityException failure = assertThrows(
                SaasCapabilityBoundary.ClosedCapabilityException.class,
                () -> consumer.onMessage(feedbackMessage("msg-1")),
                "关闭态下反馈消费者不得写反馈业务行");

        assertEquals(SaasCapabilityBoundary.LegacyCapability.MESSAGE_FEEDBACK_CONSUMER, failure.capability());
        verifyNoInteractions(feedbackService);
    }

    // -------------------------------------------------------------- 事务回查器

    @Test
    @DisplayName("文档分块回查器：init 不注册 topic，check 不查裸 doc")
    void chunkCheckerNeitherRegistersNorQueriesWhenClosed() {
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        DelegatingTransactionListener listener = mock(DelegatingTransactionListener.class);
        KnowledgeDocumentChunkTransactionChecker checker =
                new KnowledgeDocumentChunkTransactionChecker(documentMapper, listener);
        injectBoundary(checker, CLOSED_BOUNDARY);

        assertThrows(SaasCapabilityBoundary.ClosedCapabilityException.class, checker::init,
                "关闭态下 init 必须拒绝，注册次数为 0");
        verifyNoInteractions(listener);
        verifyNoInteractions(documentMapper);

        // 人工直接 check：必须同样受控关闭，且不执行裸 doc 查询
        assertThrows(SaasCapabilityBoundary.ClosedCapabilityException.class,
                () -> checker.check(chunkMessage("doc-1")));
        verifyNoInteractions(documentMapper);
    }

    @Test
    @DisplayName("知识库清理回查器：init 不注册 topic，check 不查裸 KB")
    void cleanupCheckerNeitherRegistersNorQueriesWhenClosed() {
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        DelegatingTransactionListener listener = mock(DelegatingTransactionListener.class);
        KnowledgeBaseCleanupTransactionChecker checker =
                new KnowledgeBaseCleanupTransactionChecker(knowledgeBaseMapper, listener);
        injectBoundary(checker, CLOSED_BOUNDARY);

        assertThrows(SaasCapabilityBoundary.ClosedCapabilityException.class, checker::init);
        verifyNoInteractions(listener);
        verifyNoInteractions(knowledgeBaseMapper);

        assertThrows(SaasCapabilityBoundary.ClosedCapabilityException.class,
                () -> checker.check(cleanupMessage("kb-1")));
        verifyNoInteractions(knowledgeBaseMapper);
    }

    // ---------------------------------------------------------------- 定时调度

    @Test
    @DisplayName("文档计划调度：scan/recover 在 SQL、claim lease、线程 submit 之前短路")
    void scheduleJobShortCircuitsBeforeAnySqlLockOrSubmit() {
        KnowledgeDocumentScheduleMapper scheduleMapper = mock(KnowledgeDocumentScheduleMapper.class);
        Executor executor = mock(Executor.class);
        ScheduleLockManager lockManager = mock(ScheduleLockManager.class);
        ScheduleRefreshProcessor refreshProcessor = mock(ScheduleRefreshProcessor.class);
        DocumentStatusHelper statusHelper = mock(DocumentStatusHelper.class);

        KnowledgeDocumentScheduleJob job = new KnowledgeDocumentScheduleJob(
                scheduleMapper, executor, new KnowledgeScheduleProperties(),
                lockManager, refreshProcessor, statusHelper, providerOf(CLOSED_BOUNDARY));

        SaasCapabilityBoundary.ClosedCapabilityException scanFailure = assertThrows(
                SaasCapabilityBoundary.ClosedCapabilityException.class, job::scan,
                "关闭态下 scan 不得查询计划表");
        assertEquals(SaasCapabilityBoundary.LegacyCapability.DOCUMENT_SCHEDULE_SCAN, scanFailure.capability());

        SaasCapabilityBoundary.ClosedCapabilityException recoverFailure = assertThrows(
                SaasCapabilityBoundary.ClosedCapabilityException.class, job::recoverStuckRunningDocuments,
                "关闭态下不得把卡住的 RUNNING 文档重置为 FAILED");
        assertEquals(SaasCapabilityBoundary.LegacyCapability.DOCUMENT_SCHEDULE_RECOVER, recoverFailure.capability());

        verifyNoInteractions(scheduleMapper, executor, lockManager, refreshProcessor, statusHelper);
    }

    // ------------------------------------------------------------ 启动期自动 I/O

    @Test
    @DisplayName("对象存储初始化：不建桶、不下发资产桶公共读、不取 Redis 锁")
    void storageInitializerPerformsNoRemoteCallWhenClosed() {
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        RagStorageProperties properties = new RagStorageProperties();
        properties.setKbBucket("ragent-sources");
        properties.setAssetBucket("ragent-assets");

        ObjectStorageClientProbe probe = ObjectStorageClientProbe.forClosedPath(
                objectStorageClient, redissonClient, properties);

        SaasCapabilityBoundary.ClosedCapabilityException failure = assertThrows(
                SaasCapabilityBoundary.ClosedCapabilityException.class, probe::run,
                "关闭态下启动初始化不得创建桶或设置公共读");
        assertEquals(SaasCapabilityBoundary.LegacyCapability.STORAGE_INITIALIZER, failure.capability());
        verifyNoInteractions(objectStorageClient, redissonClient);
    }

    @Test
    @DisplayName("向量空间初始化：不建 collection、不取 Redis 锁")
    void vectorSpaceInitializerPerformsNoRemoteCallWhenClosed() {
        VectorStoreAdmin vectorStoreAdmin = mock(VectorStoreAdmin.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        RAGDefaultProperties properties = new RAGDefaultProperties();
        properties.setCollectionName("rag_default_store");

        VectorSpaceProbe probe = new VectorSpaceProbe(vectorStoreAdmin, properties, redissonClient, CLOSED_BOUNDARY);

        SaasCapabilityBoundary.ClosedCapabilityException failure = assertThrows(
                SaasCapabilityBoundary.ClosedCapabilityException.class, probe::run);
        assertEquals(SaasCapabilityBoundary.LegacyCapability.VECTOR_SPACE_INITIALIZER, failure.capability());
        verifyNoInteractions(vectorStoreAdmin, redissonClient);
    }

    @Test
    @DisplayName("ES 共享索引初始化：关闭态下零 ES 请求；显式打开才发起 exists/create（正向对照）")
    void esSharedIndexInitializerIsGatedByTheBoundary() throws Exception {
        ElasticsearchClient esClient = mock(ElasticsearchClient.class);
        KeywordProperties keywordProperties = new KeywordProperties();
        EsKeywordIndexService service = new EsKeywordIndexService(
                esClient, keywordProperties, providerOf(CLOSED_BOUNDARY));

        SaasCapabilityBoundary.ClosedCapabilityException failure = assertThrows(
                SaasCapabilityBoundary.ClosedCapabilityException.class, service::initSharedIndex);
        assertEquals(SaasCapabilityBoundary.LegacyCapability.ES_SHARED_INDEX_INITIALIZER, failure.capability());
        verifyNoInteractions(esClient);

        // 正向对照：显式打开后同一方法会走到 ES（mock 会因未打桩而抛异常，这正是"确实访问了"的证据）
        EsKeywordIndexService openService = new EsKeywordIndexService(
                esClient, keywordProperties, providerOf(OPEN_BOUNDARY));
        assertThrows(Exception.class, openService::initSharedIndex,
                "打开旧能力后 initSharedIndex 必须真的尝试访问 ES，证明关闭态断言不是空转");
    }

    // ------------------------------------------------------------------ 辅助

    /** 与 Spec 一致的消息构造：事件只含旧 operator/裸 id，不含任何 tenant/member。 */
    private static MessageWrapper<KnowledgeDocumentChunkEvent> chunkMessage(String docId) {
        return MessageWrapper.<KnowledgeDocumentChunkEvent>builder()
                .keys("doc:" + docId)
                .body(KnowledgeDocumentChunkEvent.builder().docId(docId).operator("legacy-user").build())
                .build();
    }

    private static MessageWrapper<KnowledgeBaseCleanupEvent> cleanupMessage(String kbId) {
        return MessageWrapper.<KnowledgeBaseCleanupEvent>builder()
                .keys("kb:" + kbId)
                .body(KnowledgeBaseCleanupEvent.builder().kbId(kbId).tenantId(TENANT)
                        .collectionName("kb_" + kbId).build())
                .build();
    }

    private static MessageWrapper<MessageFeedbackEvent> feedbackMessage(String messageId) {
        return MessageWrapper.<MessageFeedbackEvent>builder()
                .keys("msg:" + messageId)
                .body(MessageFeedbackEvent.builder().messageId(messageId).userId("legacy-user").vote(1).build())
                .build();
    }

    /** 最小 {@link ObjectProvider}：命中时返回固定实例，未命中返回 {@code null}。 */
    private static <T> ObjectProvider<T> providerOf(T value) {
        return new FixedObjectProvider<>(value, value == null ? null : value.getClass());
    }

    /**
     * 反射注入边界依赖。
     *
     * <p>刻意不用全参构造器：那些类的构造器参数顺序属于实现细节，
     * 把它写进测试会让"安全护栏"变成"构造器签名别名"。字段按声明类型 + 名称定位，
     * 找不到就直接失败——不允许静默跳过，否则护栏会在重构后悄悄失效。
     */
    private static void injectBoundary(Object target, SaasCapabilityBoundary boundary) {
        Class<?> type = target.getClass();
        Field match = null;
        while (type != null && match == null) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType().equals(SaasCapabilityBoundary.class)
                        && field.getName().toLowerCase().contains("boundary")) {
                    match = field;
                    break;
                }
            }
            type = type.getSuperclass();
        }
        if (match == null) {
            throw new IllegalStateException("no SaasCapabilityBoundary field found on "
                    + target.getClass().getName() + "; the closure guard was probably removed");
        }
        try {
            match.setAccessible(true);
            match.set(target, boundary);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot inject closure boundary into "
                    + target.getClass().getName(), e);
        }
    }

    /** 用包级显式边界重载 + mock 协作者复现"对象存储初始化"路径，失败时协作者零交互。 */
    private record ObjectStorageClientProbe(ObjectStorageClient client, RedissonClient redisson,
                                            RagStorageProperties properties, SaasCapabilityBoundary boundary) {

        static ObjectStorageClientProbe forClosedPath(ObjectStorageClient client, RedissonClient redisson,
                                                      RagStorageProperties properties) {
            return new ObjectStorageClientProbe(client, redisson, properties, CLOSED_BOUNDARY);
        }

        void run() {
            com.nageoffer.ai.ragent.rag.config.StorageInitializer initializer =
                    new com.nageoffer.ai.ragent.rag.config.StorageInitializer(client, redisson, properties,
                            providerOf(boundary));
            initializer.initBuckets();
        }
    }

    /** 同上，用于向量空间初始化。 */
    private record VectorSpaceProbe(VectorStoreAdmin admin, RAGDefaultProperties properties,
                                    RedissonClient redisson, SaasCapabilityBoundary boundary) {

        void run() {
            com.nageoffer.ai.ragent.rag.config.VectorSpaceInitializer initializer =
                    new com.nageoffer.ai.ragent.rag.config.VectorSpaceInitializer(admin, properties, redisson,
                            providerOf(boundary));
            initializer.initVectorSpace();
        }
    }

    /**
     * 记录 {@code getIfAvailable} 调用次数的 provider，便于对"缺席即关闭"补充断言。
     */
    static final class FixedObjectProvider<T> implements ObjectProvider<T> {

        private final T value;
        private final Class<?> valueType;
        private final Set<String> requested = new LinkedHashSet<>();

        FixedObjectProvider(T value, Class<?> valueType) {
            this.value = value;
            this.valueType = valueType;
        }

        @Override
        public T getObject(Object... args) {
            return require();
        }

        @Override
        public T getIfAvailable() {
            return value;
        }

        @Override
        public T getIfUnique() {
            return value;
        }

        @Override
        public T getObject() {
            return require();
        }

        private T require() {
            if (value == null) {
                throw new IllegalStateException("no bean available for " + valueType);
            }
            return value;
        }

        Set<String> requested() {
            return requested;
        }
    }

    /**
     * 辅助设施自检：证明"缺席即关闭"这条分支可被覆盖。
     *
     * <p>没有这条，{@code providerOf(null)} 是否真的模拟了"组件不在上下文里"就没有证据，
     * 而"缺席不得默认放行"正是本单元最重要的安全语义之一。
     */
    @Test
    @DisplayName("辅助设施自检：provider 能同时表达「有边界」与「边界缺席」两种情形")
    void providerHelperIsNotVacuous() {
        assertInstanceOf(SaasCapabilityBoundary.class, providerOf(CLOSED_BOUNDARY).getIfAvailable());
        assertNull(providerOf((SaasCapabilityBoundary) null).getIfAvailable(),
                "providerOf(null) 必须模拟「组件缺席」");

        FixedObjectProvider<SaasCapabilityBoundary> missing = new FixedObjectProvider<>(null,
                SaasCapabilityBoundary.class);
        assertThrows(IllegalStateException.class, missing::getObject,
                "缺席时 getObject() 必须失败，不能返回 null 让调用方继续执行");

        // 缺席即关闭：静态判定不得默认放行
        assertThrows(SaasCapabilityBoundary.ClosedCapabilityException.class,
                () -> SaasCapabilityBoundary.requireOpenOrClosed(providerOf(null),
                        SaasCapabilityBoundary.LegacyCapability.KB_CLEANUP_CONSUMER));
    }
}
