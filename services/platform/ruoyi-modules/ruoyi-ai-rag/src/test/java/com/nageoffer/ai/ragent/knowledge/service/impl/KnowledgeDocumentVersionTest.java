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

package com.nageoffer.ai.ragent.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.core.ingest.IngestionKernel;
import com.nageoffer.ai.ragent.core.ingest.sink.ChunkIndexWriter;
import com.nageoffer.ai.ragent.core.parser.registry.ParserRegistry;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionPipelineMapper;
import com.nageoffer.ai.ragent.ingestion.engine.IngestionEngine;
import com.nageoffer.ai.ragent.ingestion.service.IngestionPipelineService;
import com.nageoffer.ai.ragent.knowledge.config.KnowledgeScheduleProperties;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentChunkLogMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.handler.RemoteFileFetcher;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeChunkService;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentScheduleService;
import com.nageoffer.ai.ragent.knowledge.support.IngestionSpecCodec;
import com.nageoffer.ai.ragent.knowledge.support.VectorTargetResolver;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.util.Date;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RW-04 / RW-04-R2：文档编辑与状态的乐观锁（版本冲突）。
 *
 * <p><b>RW-04-R2 起令牌是 V29 的行内计数器</b>（{@code version = version + 1 WHERE version = ?}），
 * 不再是 {@code update_time} 毫秒。原因见 {@code V29__knowledge_document_version_counter.sql}：
 * 毫秒令牌在同一毫秒两次提交时**不会前进**，会让持旧令牌的第三个写入者仍然命中，
 * 造成静默的 lost update。这里用 {@link #installCasStore()} 的内存 CAS 替身把
 * "两次提交 + 旧令牌"的交错**跑出来**，而不是只断言 SQL 片段。
 *
 * <p><b>判据边界（如实声明）</b>：内存替身按 {@code version = version + 1 WHERE version = ?}
 * 的语义执行（相等才自增，否则 0 行），它验的是服务层是否发出了正确的 CAS 并正确翻译 0 行；
 * **真库并发**（行锁、隔离级别、多实例）由 T8/RW-26 在专属窗口复核，本卡记 NOT_RUN。
 */
@ExtendWith(MockitoExtension.class)
@Tag("dev")
class KnowledgeDocumentVersionTest {

    private static final String DOC_ID = "1800000000000000001";

    /** 夹具用的"其它毫秒"值：证明令牌与时钟无关（同毫秒也能分辨）。 */
    private static final long SAME_MILLISECOND = 1_700_000_000_000L;

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;
    @Mock
    private KnowledgeDocumentMapper documentMapper;
    @Mock
    private ParserRegistry parserRegistry;
    @Mock
    private IngestionKernel ingestionKernel;
    @Mock
    private ChunkIndexWriter chunkIndexWriter;
    @Mock
    private IngestionSpecCodec ingestionSpecCodec;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private VectorStoreService vectorStoreService;
    @Mock
    private KnowledgeChunkService knowledgeChunkService;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private KnowledgeDocumentScheduleService scheduleService;
    @Mock
    private IngestionPipelineService ingestionPipelineService;
    @Mock
    private IngestionPipelineMapper ingestionPipelineMapper;
    @Mock
    private IngestionEngine ingestionEngine;
    @Mock
    private KnowledgeDocumentChunkLogMapper chunkLogMapper;
    @Mock
    private KnowledgeChunkMapper chunkMapper;
    @Mock
    private MessageQueueProducer messageQueueProducer;
    @Mock
    private KnowledgeScheduleProperties scheduleProperties;
    @Mock
    private RemoteFileFetcher remoteFileFetcher;
    @Mock
    private VectorTargetResolver vectorTargetResolver;
    @Mock
    private BizChangeLogContext bizChangeLogContext;

    /** 真实事务模板（内联执行回调）：夹具要验的是 CAS 判定，不是事务传播。 */
    @Spy
    private TransactionOperations transactionOperations = new TransactionOperations() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            TransactionStatus status = new SimpleTransactionStatus();
            return action.doInTransaction(status);
        }
    };

    @InjectMocks
    private KnowledgeDocumentServiceImpl service;

    /**
     * Lambda 包装器把方法引用解析成列名要读 MyBatis-Plus 的 lambda 缓存；
     * 该缓存由 MyBatis 解析 mapper 时建立，纯单元测试里没有那一层，必须显式装一次。
     */
    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeDocumentDO.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeBaseDO.class);
    }

    @BeforeEach
    void principal() {
        PrincipalContext.set(new ExecutionPrincipal("T1", "2101", "platform:T1:2101",
                1, 1, Set.of("kb.write"), "jti", "platform", 0, Long.MAX_VALUE));
    }

    @AfterEach
    void clear() {
        PrincipalContext.clear();
    }

    // ------------------------------------------------------------ 前置检查负例

    @Test
    void updateShouldRejectStaleVersionBeforeAnyWrite() {
        when(documentMapper.selectOne(any())).thenReturn(document(3L, "pending", 1));

        KnowledgeDocumentUpdateRequest request = new KnowledgeDocumentUpdateRequest();
        request.setDocName("季度报告 v2");
        request.setExpectedVersion(2L);

        ClientException ex = assertThrows(ClientException.class, () -> service.update(DOC_ID, request));

        assertEquals("A000409", ex.getErrorCode(), "版本冲突必须有独立错误码，不能退化成通用客户端错误");
        verify(documentMapper, never()).update(any(LambdaUpdateWrapper.class));
        verify(scheduleService, never()).upsertSchedule(any());
    }

    @Test
    void updateShouldRejectWhenConditionalUpdateMatchesNoRow() {
        // 前置检查读到 3（通过），但条件更新 0 行：说明窗口内被别处改过
        when(documentMapper.selectOne(any()))
                .thenReturn(document(3L, "pending", 1), document(8L, "pending", 1));
        when(documentMapper.update(any(LambdaUpdateWrapper.class))).thenReturn(0);

        KnowledgeDocumentUpdateRequest request = new KnowledgeDocumentUpdateRequest();
        request.setDocName("季度报告 v2");
        request.setExpectedVersion(3L);

        ClientException ex = assertThrows(ClientException.class, () -> service.update(DOC_ID, request));

        assertEquals("A000409", ex.getErrorCode());
        // 冲突路径不得留下任何副作用（审计快照/计划任务同步都不许发生）
        verify(scheduleService, never()).upsertSchedule(any());
        verify(bizChangeLogContext, never()).put(any(), any(), any());
    }

    // ------------------------------------------------------------ SQL 形状正例

    @Test
    void updateShouldCarryVersionPredicateAndIncrementCounter() {
        when(documentMapper.selectOne(any()))
                .thenReturn(document(3L, "pending", 1), document(4L, "pending", 1));
        when(documentMapper.update(any(LambdaUpdateWrapper.class))).thenReturn(1);

        KnowledgeDocumentUpdateRequest request = new KnowledgeDocumentUpdateRequest();
        request.setDocName("季度报告 v2");
        request.setExpectedVersion(3L);

        service.update(DOC_ID, request);

        ArgumentCaptor<LambdaUpdateWrapper<KnowledgeDocumentDO>> captor = wrapperCaptor();
        verify(documentMapper).update(captor.capture());
        LambdaUpdateWrapper<KnowledgeDocumentDO> wrapper = captor.getValue();

        assertTrue(whereOf(wrapper).contains("version"),
                "条件更新缺少 version 谓词，并发下会退化成最后写入覆盖");
        assertTrue(wrapper.getParamNameValuePairs().containsValue(3L),
                "版本谓词没有绑定调用方给出的期望版本");
        assertTrue(setOf(wrapper).contains("version=version+1"),
                "SET 必须是行内自增 version = version + 1；用 Java 侧算出的新值会覆盖别人的自增");
        assertTrue(!setOf(wrapper).contains("version=#{"),
                "不得把 version 当普通字段写成常量赋值");
    }

    @Test
    void updateWithoutExpectedVersionStaysBackwardCompatible() {
        when(documentMapper.selectOne(any()))
                .thenReturn(document(3L, "pending", 1), document(4L, "pending", 1));
        when(documentMapper.update(any(LambdaUpdateWrapper.class))).thenReturn(1);

        KnowledgeDocumentUpdateRequest request = new KnowledgeDocumentUpdateRequest();
        request.setDocName("季度报告 v2");

        service.update(DOC_ID, request);

        ArgumentCaptor<LambdaUpdateWrapper<KnowledgeDocumentDO>> captor = wrapperCaptor();
        verify(documentMapper).update(captor.capture());
        assertTrue(!whereOf(captor.getValue()).contains("version"),
                "缺省 expectedVersion 时不得凭空造出版本条件（旧客户端应保持可用）");
    }

    // ------------------------------------------------------------ 启停路径

    @Test
    void enableShouldRejectStaleVersionBeforeTouchingVectors() {
        when(documentMapper.selectOne(any())).thenReturn(document(3L, "success", 1));

        ClientException ex = assertThrows(ClientException.class,
                () -> service.enable(DOC_ID, false, 2L));

        assertEquals("A000409", ex.getErrorCode());
        verify(documentMapper, never()).update(any(LambdaUpdateWrapper.class));
        // 冲突请求不得产生任何副作用，尤其不得删掉别的请求刚建好的向量
        verify(vectorStoreService, never()).deleteDocumentVectors(any(), any(), any());
    }

    @Test
    void enableShouldCarryVersionPredicateAndIncrementCounter() {
        when(documentMapper.selectOne(any()))
                .thenReturn(document(3L, "success", 1), document(4L, "success", 0));
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase());
        when(documentMapper.update(any(LambdaUpdateWrapper.class))).thenReturn(1);

        service.enable(DOC_ID, false, 3L);

        ArgumentCaptor<LambdaUpdateWrapper<KnowledgeDocumentDO>> captor = wrapperCaptor();
        verify(documentMapper).update(captor.capture());
        LambdaUpdateWrapper<KnowledgeDocumentDO> wrapper = captor.getValue();
        assertTrue(whereOf(wrapper).contains("version"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(3L));
        assertTrue(setOf(wrapper).contains("version=version+1"));
        verify(vectorStoreService).deleteDocumentVectors("T1", "kb_collection", DOC_ID);
    }

    @Test
    void enableShouldAbortWhenConditionalUpdateMatchesNoRow() {
        when(documentMapper.selectOne(any())).thenReturn(document(3L, "success", 1));
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase());
        when(documentMapper.update(any(LambdaUpdateWrapper.class))).thenReturn(0);

        ClientException ex = assertThrows(ClientException.class, () -> service.enable(DOC_ID, false, 3L));

        assertEquals("A000409", ex.getErrorCode());
        verify(vectorStoreService, never()).deleteDocumentVectors(any(), any(), any());
    }

    // ------------------------------------------------------------ 并发交错：RW-04-R2 的核心判据

    @Test
    void twoWritersHoldingTheSameTokenCannotBothSucceed() {
        // §11.8 场景的可执行版本：A 与 P **各自读到同一个令牌 0**（P 的读发生在 A 提交之前，
        // 旧实现里对应"同一毫秒"），然后依次提交。
        //   旧实现（update_time 令牌）：A 的提交不改变令牌 ⇒ P 的 WHERE 仍然命中 ⇒ 双方都成功、P 静默覆盖 A。
        //   新实现（V29 计数器）：A 的提交让计数器 +1 ⇒ P 的 WHERE 不命中 ⇒ P 必须拿到 409。
        casVersion.set(0L);
        // 读序刻意排成"P 的读早于 A 的提交"：update() 里每个写入者有两次读
        // （前置检查 + 审计快照），所以 P 的两次读都拿到陈旧令牌 0 —— 这样 P 的**前置检查是通过的**，
        // 拒绝必须来自 CAS（这正是本判据要钉的那一层）。
        when(documentMapper.selectOne(any())).thenReturn(
                document(0L, "pending", 1),   // A：前置检查
                document(0L, "pending", 1),   // A：审计快照
                document(0L, "pending", 1),   // P：前置检查（早于 A 的提交）
                document(1L, "pending", 1));  // P：CAS 0 行后的复读
        when(documentMapper.update(any(LambdaUpdateWrapper.class))).thenAnswer(casAnswer());

        service.update(DOC_ID, updateRequest("A 的改名", 0L));
        assertEquals(1L, casVersion.get(), "A 的成功写入必须让计数器前进");

        ClientException ex = assertThrows(ClientException.class,
                () -> service.update(DOC_ID, updateRequest("P 的改名（持旧令牌）", 0L)));

        assertEquals("A000409", ex.getErrorCode(),
                "持旧令牌的第二个写入者必须被拒绝，不得静默覆盖先提交者");
        assertEquals(1L, casVersion.get(), "被拒绝的写入不得改变计数器（零写入）");

        // 两个写入者发出的条件更新都绑定了同一个陈旧令牌 0：冲突是**计数器**判出来的，
        // 不是前置检查碰巧挡住——前置检查在 P 这一侧是"通过"的（它读到 0）。
        ArgumentCaptor<LambdaUpdateWrapper<KnowledgeDocumentDO>> captor = wrapperCaptor();
        verify(documentMapper, times(2)).update(captor.capture());
        assertTrue(captor.getAllValues().stream()
                        .allMatch(w -> w.getParamNameValuePairs().containsValue(0L)),
                "两次提交都必须携带 version = 0 谓词（P 是被 CAS 挡下的，不是被前置检查）");
    }

    @Test
    void versionCounterIsMonotonicAcrossWrites() {
        casVersion.set(0L);
        when(documentMapper.selectOne(any()))
                .thenAnswer(invocation -> document(casVersion.get(), "pending", 1));
        when(documentMapper.update(any(LambdaUpdateWrapper.class))).thenAnswer(casAnswer());

        for (long expected = 0; expected < 5; expected++) {
            service.update(DOC_ID, updateRequest("第 " + (expected + 1) + " 次改名", expected));
        }

        assertEquals(5L, casVersion.get(), "5 次成功写入后计数器必须是 5（严格单调，不依赖时钟）");
        // 夹具的 update_time 恒定：旧毫秒令牌在这 5 次写入里一次都不会前进，
        // 新计数器仍能逐一分辨——这就是"令牌与时钟解耦"的可核对含义。
        assertEquals(SAME_MILLISECOND, document(5L, "pending", 1).getUpdateTime().getTime(),
                "夹具的 update_time 必须恒定，否则这条判据证明不了'与时钟无关'");
    }

    @Test
    void enableAndUpdateShareTheSameCounter() {
        casVersion.set(0L);
        when(documentMapper.selectOne(any()))
                .thenAnswer(invocation -> document(casVersion.get(), "success", 1));
        when(documentMapper.update(any(LambdaUpdateWrapper.class))).thenAnswer(casAnswer());
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase());

        service.enable(DOC_ID, false, 0L);                 // 0 → 1
        service.update(DOC_ID, updateRequest("改名", 1L));  // 1 → 2

        ClientException ex = assertThrows(ClientException.class, () -> service.enable(DOC_ID, true, 0L));
        assertEquals("A000409", ex.getErrorCode(), "启停与编辑必须共用同一个计数器，否则两条写路径会互相踩");
        assertEquals(2L, casVersion.get());
    }

    // ------------------------------------------------------------ 内存 CAS 替身

    /** 内存里的"行版本"，由 {@link #casAnswer()} 按 CAS 语义维护。 */
    private final AtomicLong casVersion = new AtomicLong();

    /**
     * {@code update} 的内存替身：按 {@code version = version + 1 WHERE version = ?} 的真实语义执行——
     * 谓词相等才自增并返回 1 行，否则返回 0 行。
     *
     * <p><b>它同时是负对照</b>：如果实现退回"用 update_time 当令牌"，WHERE 里就没有 {@code version}，
     * 这个替身会直接放行（返回 1 行）⇒ {@link #twoWritersHoldingTheSameTokenCannotBothSucceed}
     * 会因为"第二个写入者没被拒绝"而失败。也就是说这条判据不是恒真的。
     */
    private Answer<Integer> casAnswer() {
        return invocation -> {
            LambdaUpdateWrapper<KnowledgeDocumentDO> wrapper = invocation.getArgument(0);
            String set = wrapper.getSqlSet() == null ? "" : wrapper.getSqlSet().replace(" ", "").toLowerCase();
            if (!set.contains("version=version+1")) {
                throw new AssertionError("SET 子句必须是行内自增 version = version + 1，实际：" + wrapper.getSqlSet());
            }
            String where = wrapper.getSqlSegment() == null ? "" : wrapper.getSqlSegment().toLowerCase();
            if (!where.contains("version")) {
                casVersion.incrementAndGet();
                return 1;
            }
            Long expected = wrapper.getParamNameValuePairs().values().stream()
                    .filter(Long.class::isInstance).map(Long.class::cast).findFirst().orElse(null);
            if (expected != null && expected == casVersion.get()) {
                casVersion.incrementAndGet();
                return 1;
            }
            return 0;
        };
    }

    private static KnowledgeDocumentUpdateRequest updateRequest(String docName, Long expectedVersion) {
        KnowledgeDocumentUpdateRequest request = new KnowledgeDocumentUpdateRequest();
        request.setDocName(docName);
        request.setExpectedVersion(expectedVersion);
        return request;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<LambdaUpdateWrapper<KnowledgeDocumentDO>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    }

    /**
     * SET 子句来自 {@code getSqlSet()}；WHERE 子句来自 {@code getSqlSegment()}
     * （{@code getCustomSqlSegment()} 只回 WHERE，别拿它当全文）。
     *
     * <p>两边都按"去掉下划线、去空格、转小写"归一化再比：SET 里的列名由实体的表信息解析
     * （真实运行配了 map-underscore-to-camel 才是 {@code version}，纯单测的手工装配可能给出
     * 属性名），而 WHERE 里是字面 SQL。判据盯的是"版本列有没有参与"，不是渲染成哪种写法。
     */
    private static String setOf(LambdaUpdateWrapper<KnowledgeDocumentDO> wrapper) {
        String sqlSet = wrapper.getSqlSet();
        return sqlSet == null ? "" : sqlSet.toLowerCase().replace("_", "").replace(" ", "");
    }

    private static String whereOf(LambdaUpdateWrapper<KnowledgeDocumentDO> wrapper) {
        String segment = wrapper.getSqlSegment();
        return segment == null ? "" : segment.toLowerCase().replace("_", "");
    }

    private static KnowledgeDocumentDO document(long version, String status, int enabled) {
        return KnowledgeDocumentDO.builder()
                .id(DOC_ID)
                .kbId("1800000000000000000")
                .docName("季度报告")
                .enabled(enabled)
                .status(status)
                .chunkCount(3)
                .version(version)
                .fileUrl("kb/report.pdf")
                .fileType("pdf")
                .deleted(0)
                .createTime(new Date(SAME_MILLISECOND - 1000))
                // 固定同一个毫秒值：新令牌与时钟无关，旧实现正是在这里失去分辨力
                .updateTime(new Date(SAME_MILLISECOND))
                .build();
    }

    private static KnowledgeBaseDO knowledgeBase() {
        return KnowledgeBaseDO.builder()
                .id("1800000000000000000")
                .tenantId("T1")
                .name("默认知识库")
                .collectionName("kb_collection")
                .embeddingModel("qwen3-embedding:8b-fp16")
                .deleted(0)
                .build();
    }
}
