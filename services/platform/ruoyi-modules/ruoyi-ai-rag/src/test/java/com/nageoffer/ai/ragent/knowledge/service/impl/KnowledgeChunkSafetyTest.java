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

import cn.hutool.crypto.SecureUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkAssembler;
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.core.ingest.VectorTarget;
import com.nageoffer.ai.ragent.core.ingest.embed.ChunkEmbeddingService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.infra.token.TokenCounterService;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkBatchRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkCreateRequest;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.support.VectorTargetResolver;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RW-04：分块安全管理（D05 批量有界集合 + 序号完整性）。
 *
 * <p>判据分两面：越界/重复/空集合/空 ID 必须<b>在写入之前</b>整体拒绝；
 * 合法集合必须整体提交，且序号默认接着最大值走，显式给出时不得与已有块撞号。
 */
@ExtendWith(MockitoExtension.class)
@Tag("dev")
class KnowledgeChunkSafetyTest {

    private static final String DOC_ID = "1800000000000000001";

    @Mock
    private KnowledgeChunkMapper chunkMapper;
    @Mock
    private KnowledgeDocumentMapper documentMapper;
    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;
    @Mock
    private ChunkEmbeddingService chunkEmbeddingService;
    @Mock
    private VectorTargetResolver vectorTargetResolver;
    @Mock
    private TokenCounterService tokenCounterService;
    @Mock
    private VectorStoreService vectorStoreService;
    @Mock
    private BizChangeLogContext bizChangeLogContext;

    @Spy
    private TransactionOperations transactionOperations = new TransactionOperations() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            TransactionStatus status = new SimpleTransactionStatus();
            return action.doInTransaction(status);
        }
    };

    @InjectMocks
    private KnowledgeChunkServiceImpl service;

    /** Lambda 列名解析依赖 MyBatis-Plus 的 lambda 缓存，纯单元测试里需显式装配。 */
    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeChunkDO.class);
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

    // ------------------------------------------------------------ 批量启停负例

    @Test
    void batchShouldRejectEmptySet() {
        ClientException ex = assertThrows(ClientException.class,
                () -> service.batchToggleEnabled(DOC_ID, new KnowledgeChunkBatchRequest(), false));
        assertEquals("请指定需要操作的 Chunk，全量启用/禁用请使用文档启用接口", ex.getErrorMessage());
        verify(chunkMapper, never()).update(any(LambdaUpdateWrapper.class));
    }

    @Test
    void batchShouldRejectDuplicateIds() {
        ClientException ex = assertThrows(ClientException.class,
                () -> service.batchToggleEnabled(DOC_ID, batch("c1", "c1"), false));
        assertEquals("批量操作的 Chunk ID 不能重复", ex.getErrorMessage());
        verify(chunkMapper, never()).update(any(LambdaUpdateWrapper.class));
    }

    @Test
    void batchShouldRejectBlankId() {
        ClientException ex = assertThrows(ClientException.class,
                () -> service.batchToggleEnabled(DOC_ID, batch("c1", " "), false));
        assertEquals("批量操作的 Chunk ID 不能为空", ex.getErrorMessage());
        verify(chunkMapper, never()).update(any(LambdaUpdateWrapper.class));
    }

    @Test
    void batchShouldRejectSetLargerThanBound() {
        String[] ids = IntStream.rangeClosed(1, 101).mapToObj(i -> "c" + i).toArray(String[]::new);
        ClientException ex = assertThrows(ClientException.class,
                () -> service.batchToggleEnabled(DOC_ID, batch(ids), false));
        assertEquals("单次批量操作 Chunk 数量不能超过 100", ex.getErrorMessage());
        verify(chunkMapper, never()).update(any(LambdaUpdateWrapper.class));
    }

    @Test
    void batchShouldRejectIdsNotBelongingToDocument() {
        when(documentMapper.selectOne(any())).thenReturn(document());
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("c1", 0, 1, "other-doc")));

        ClientException ex = assertThrows(ClientException.class,
                () -> service.batchToggleEnabled(DOC_ID, batch("c1", "c2"), false));

        assertEquals("存在无效的 Chunk ID，请求 2 个，实际找到 1 个", ex.getErrorMessage());
        verify(chunkMapper, never()).update(any(LambdaUpdateWrapper.class));
        verify(vectorStoreService, never()).deleteChunksByIds(any(), any(), any());
    }

    // ------------------------------------------------------------ 批量启停正例

    @Test
    void batchDisableShouldCommitWholeSet() {
        when(documentMapper.selectOne(any())).thenReturn(document());
        when(chunkMapper.selectList(any())).thenReturn(
                List.of(chunk("c1", 0, 1, DOC_ID), chunk("c2", 1, 1, DOC_ID)),
                List.of(chunk("c1", 0, 1, DOC_ID), chunk("c2", 1, 1, DOC_ID)),
                List.of(chunk("c1", 0, 0, DOC_ID), chunk("c2", 1, 0, DOC_ID)));
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase());
        when(vectorTargetResolver.resolve(any())).thenReturn(new VectorTarget("T1", "p", "m", 1536));

        service.batchToggleEnabled(DOC_ID, batch("c1", "c2"), false);

        // 整体提交：一次条件更新覆盖两个 ID，且带着租户条件
        ArgumentCaptor<LambdaUpdateWrapper<KnowledgeChunkDO>> captor = wrapperCaptor();
        verify(chunkMapper).update(captor.capture());
        assertNotNull(captor.getValue().getSqlSegment());
        // 向量侧按同一集合整体删除（不是逐块 N 次）
        verify(vectorStoreService).deleteChunksByIds("T1", "kb_collection", List.of("c1", "c2"));
    }

    // ------------------------------------------------------------ 新增分块：序号完整性

    @Test
    void createShouldRejectNegativeIndex() {
        when(documentMapper.selectOne(any())).thenReturn(document());

        KnowledgeChunkCreateRequest request = new KnowledgeChunkCreateRequest();
        request.setContent("内容");
        request.setIndex(-1);

        ClientException ex = assertThrows(ClientException.class, () -> service.create(DOC_ID, request));

        assertEquals("Chunk 序号不能为负数", ex.getErrorMessage());
        verify(chunkMapper, never()).insert(any(KnowledgeChunkDO.class));
    }

    @Test
    void createShouldRejectOccupiedIndex() {
        when(documentMapper.selectOne(any())).thenReturn(document());
        when(chunkMapper.selectOne(any())).thenReturn(chunk("c9", 3, 1, DOC_ID));
        when(chunkMapper.selectCount(any())).thenReturn(1L);

        KnowledgeChunkCreateRequest request = new KnowledgeChunkCreateRequest();
        request.setContent("内容");
        request.setIndex(3);

        ClientException ex = assertThrows(ClientException.class, () -> service.create(DOC_ID, request));

        assertEquals("Chunk 序号已被占用：3", ex.getErrorMessage());
        verify(chunkMapper, never()).insert(any(KnowledgeChunkDO.class));
    }

    @Test
    void createShouldAppendAfterHighestIndexWhenIndexOmitted() {
        when(documentMapper.selectOne(any())).thenReturn(document());
        when(chunkMapper.selectOne(any())).thenReturn(chunk("c4", 4, 1, DOC_ID));
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase());
        when(tokenCounterService.countTokens(any())).thenReturn(2);
        when(vectorTargetResolver.resolve(any())).thenReturn(new VectorTarget("T1", "p", "m", 1536));
        when(chunkEmbeddingService.embed(any(), any())).thenReturn(List.of(embedded("1700000000000000009", 5)));

        KnowledgeChunkCreateRequest request = new KnowledgeChunkCreateRequest();
        request.setContent("季度营收同比");
        // MP 的 ASSIGN_ID 在真实 insert 时补 id；mock 不补，而向量化要求块 ID 非空
        request.setChunkId("1700000000000000009");

        service.create(DOC_ID, request);

        ArgumentCaptor<KnowledgeChunkDO> captor = ArgumentCaptor.forClass(KnowledgeChunkDO.class);
        verify(chunkMapper).insert(captor.capture());
        KnowledgeChunkDO inserted = captor.getValue();
        assertEquals(5, inserted.getChunkIndex(), "缺省序号必须接在最大值之后");
        assertEquals(SecureUtil.sha256("季度营收同比"), inserted.getContentHash());
        assertEquals("季度营收同比", inserted.getEmbeddingText(),
                "人工块没有结构信息，向量文本必须显式等于正文而不是留空");
        assertEquals(1, inserted.getEnabled());
    }

    // ------------------------------------------------------------ 夹具

    private static KnowledgeChunkBatchRequest batch(String... ids) {
        KnowledgeChunkBatchRequest request = new KnowledgeChunkBatchRequest();
        request.setChunkIds(new ArrayList<>(Arrays.asList(ids)));
        return request;
    }

    private static KnowledgeDocumentDO document() {
        return KnowledgeDocumentDO.builder()
                .id(DOC_ID)
                .kbId("1800000000000000000")
                .docName("季度报告")
                .enabled(1)
                .status("success")
                .chunkCount(5)
                .fileUrl("kb/report.pdf")
                .fileType("pdf")
                .deleted(0)
                .createTime(new Date(1_700_000_000_000L))
                .updateTime(new Date(1_700_000_000_000L))
                .build();
    }

    private static KnowledgeChunkDO chunk(String id, int index, int enabled, String docId) {
        return KnowledgeChunkDO.builder()
                .id(id)
                .kbId("1800000000000000000")
                .docId(docId)
                .chunkIndex(index)
                .content("内容" + index)
                .embeddingText("内容" + index)
                .enabled(enabled)
                .deleted(0)
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

    private static EmbeddedChunk embedded(String chunkId, int index) {
        return new EmbeddedChunk(
                ChunkAssembler.restore(chunkId, index, "季度营收同比", "季度营收同比"),
                new float[1536]);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<LambdaUpdateWrapper<KnowledgeChunkDO>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    }
}
