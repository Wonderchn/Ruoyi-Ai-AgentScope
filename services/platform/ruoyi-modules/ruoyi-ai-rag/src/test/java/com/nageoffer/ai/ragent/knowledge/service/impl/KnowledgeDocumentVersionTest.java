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
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.util.Date;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RW-04：文档编辑 / 状态的乐观锁（版本冲突）负例与正例。
 *
 * <p>这几条判据钉的是"读-改-写"窗口：版本不符必须在<b>任何写入之前</b>拒绝，
 * 且成功写入必须让版本前进（否则旧版本永远匹配，乐观锁等于不存在）。
 */
@ExtendWith(MockitoExtension.class)
@Tag("dev")
class KnowledgeDocumentVersionTest {

    private static final String DOC_ID = "1800000000000000001";

    private static final long V1 = 1_700_000_000_000L;

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

    @Test
    void updateShouldRejectStaleVersionBeforeAnyWrite() {
        when(documentMapper.selectOne(any())).thenReturn(document(V1, "pending", 1));

        KnowledgeDocumentUpdateRequest request = new KnowledgeDocumentUpdateRequest();
        request.setDocName("季度报告 v2");
        request.setExpectedVersion(V1 - 1);

        ClientException ex = assertThrows(ClientException.class, () -> service.update(DOC_ID, request));

        assertEquals("A000409", ex.getErrorCode(), "版本冲突必须有独立错误码，不能退化成通用客户端错误");
        verify(documentMapper, never()).update(any(LambdaUpdateWrapper.class));
    }

    @Test
    void updateShouldRejectWhenConditionalUpdateMatchesNoRow() {
        // 前置检查读到 V1（通过），但条件更新 0 行：说明窗口内被别处改过
        when(documentMapper.selectOne(any()))
                .thenReturn(document(V1, "pending", 1), document(V1 + 5, "pending", 1));
        when(documentMapper.update(any(LambdaUpdateWrapper.class))).thenReturn(0);

        KnowledgeDocumentUpdateRequest request = new KnowledgeDocumentUpdateRequest();
        request.setDocName("季度报告 v2");
        request.setExpectedVersion(V1);

        ClientException ex = assertThrows(ClientException.class, () -> service.update(DOC_ID, request));

        assertEquals("A000409", ex.getErrorCode());
    }

    @Test
    void updateShouldCarryVersionPredicateAndAdvanceVersionOnSuccess() {
        when(documentMapper.selectOne(any()))
                .thenReturn(document(V1, "pending", 1), document(V1 + 7, "pending", 1));
        when(documentMapper.update(any(LambdaUpdateWrapper.class))).thenReturn(1);

        KnowledgeDocumentUpdateRequest request = new KnowledgeDocumentUpdateRequest();
        request.setDocName("季度报告 v2");
        request.setExpectedVersion(V1);

        service.update(DOC_ID, request);

        ArgumentCaptor<LambdaUpdateWrapper<KnowledgeDocumentDO>> captor = wrapperCaptor();
        verify(documentMapper).update(captor.capture());
        LambdaUpdateWrapper<KnowledgeDocumentDO> wrapper = captor.getValue();

        // 条件里必须有版本谓词：只有 UPDATE ... WHERE id AND tenant_id AND update_time = ? 才叫 CAS
        assertTrue(whereOf(wrapper).contains("updatetime"),
                "条件更新缺少 update_time 谓词，并发下会退化成最后写入覆盖");
        assertTrue(wrapper.getParamNameValuePairs().containsValue(new Date(V1)),
                "版本谓词没有绑定调用方给出的期望版本");
        // SET 里必须显式写回新版本：wrapper 更新不触发自动填充，不写回则版本永远停在旧值
        assertTrue(setOf(wrapper).contains("updatetime"),
                "成功写入没有推进版本令牌，乐观锁会永远匹配旧值");
    }

    @Test
    void updateWithoutExpectedVersionStaysBackwardCompatible() {
        when(documentMapper.selectOne(any()))
                .thenReturn(document(V1, "pending", 1), document(V1 + 7, "pending", 1));
        when(documentMapper.update(any(LambdaUpdateWrapper.class))).thenReturn(1);

        KnowledgeDocumentUpdateRequest request = new KnowledgeDocumentUpdateRequest();
        request.setDocName("季度报告 v2");

        service.update(DOC_ID, request);

        ArgumentCaptor<LambdaUpdateWrapper<KnowledgeDocumentDO>> captor = wrapperCaptor();
        verify(documentMapper).update(captor.capture());
        assertTrue(!whereOf(captor.getValue()).contains("updatetime"),
                "缺省 expectedVersion 时不得凭空造出版本条件（旧客户端应保持可用）");
    }

    @Test
    void enableShouldRejectStaleVersionBeforeTouchingVectors() {
        when(documentMapper.selectOne(any())).thenReturn(document(V1, "success", 1));

        ClientException ex = assertThrows(ClientException.class,
                () -> service.enable(DOC_ID, false, V1 - 1));

        assertEquals("A000409", ex.getErrorCode());
        verify(documentMapper, never()).update(any(LambdaUpdateWrapper.class));
        // 冲突请求不得产生任何副作用，尤其不得删掉别的请求刚建好的向量
        verify(vectorStoreService, never()).deleteDocumentVectors(any(), any(), any());
    }

    @Test
    void enableShouldAdvanceVersionOnSuccess() {
        when(documentMapper.selectOne(any()))
                .thenReturn(document(V1, "success", 1), document(V1 + 3, "success", 0));
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase());
        when(documentMapper.update(any(LambdaUpdateWrapper.class))).thenReturn(1);

        service.enable(DOC_ID, false, V1);

        ArgumentCaptor<LambdaUpdateWrapper<KnowledgeDocumentDO>> captor = wrapperCaptor();
        verify(documentMapper).update(captor.capture());
        LambdaUpdateWrapper<KnowledgeDocumentDO> wrapper = captor.getValue();
        assertTrue(whereOf(wrapper).contains("updatetime"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(new Date(V1)));
        assertTrue(setOf(wrapper).contains("updatetime"));
        verify(vectorStoreService).deleteDocumentVectors("T1", "kb_collection", DOC_ID);
    }

    @Test
    void enableShouldAbortWhenConditionalUpdateMatchesNoRow() {
        when(documentMapper.selectOne(any())).thenReturn(document(V1, "success", 1));
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase());
        when(documentMapper.update(any(LambdaUpdateWrapper.class))).thenReturn(0);

        ClientException ex = assertThrows(ClientException.class, () -> service.enable(DOC_ID, false, V1));

        assertEquals("A000409", ex.getErrorCode());
        verify(vectorStoreService, never()).deleteDocumentVectors(any(), any(), any());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<LambdaUpdateWrapper<KnowledgeDocumentDO>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    }

    /**
     * SET 子句来自 {@code getSqlSet()}；WHERE 子句来自 {@code getSqlSegment()}
     * （{@code getCustomSqlSegment()} 只回 WHERE，别拿它当全文）。
     *
     * <p>两边都按"去掉下划线、转小写"归一化再比：SET 里的列名由实体的
     * 表信息解析（真实运行配了 map-underscore-to-camel 才是 {@code update_time}，
     * 纯单测的手工装配可能给出属性名 {@code updateTime}），而 WHERE 里是字面 SQL。
     * 判据要盯的是"版本列有没有参与"，不是渲染成哪种写法。
     */
    private static String setOf(LambdaUpdateWrapper<KnowledgeDocumentDO> wrapper) {
        String sqlSet = wrapper.getSqlSet();
        return sqlSet == null ? "" : sqlSet.toLowerCase().replace("_", "");
    }

    private static String whereOf(LambdaUpdateWrapper<KnowledgeDocumentDO> wrapper) {
        String segment = wrapper.getSqlSegment();
        return segment == null ? "" : segment.toLowerCase().replace("_", "");
    }

    private static KnowledgeDocumentDO document(long updateTime, String status, int enabled) {
        return KnowledgeDocumentDO.builder()
                .id(DOC_ID)
                .kbId("1800000000000000000")
                .docName("季度报告")
                .enabled(enabled)
                .status(status)
                .chunkCount(3)
                .fileUrl("kb/report.pdf")
                .fileType("pdf")
                .deleted(0)
                .createTime(new Date(updateTime - 1000))
                .updateTime(new Date(updateTime))
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
