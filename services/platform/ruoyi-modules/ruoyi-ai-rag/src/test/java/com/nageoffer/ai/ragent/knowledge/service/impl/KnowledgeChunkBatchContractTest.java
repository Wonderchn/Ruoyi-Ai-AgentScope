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

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkPageRequest;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.support.VectorTargetResolver;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R17 F05:3 分块服务收口：批量启停跨租户/混集合、停用/删除的向量清理（检索排除的服务层机制）、
 * 删除路径契约与合法批量正例。
 *
 * <p>判据映射（红先行首跑；通过即该判据已绿）：
 * <ul>
 *   <li>判据 1：{@link #batchDisableShouldRejectMixedOtherDocumentChunkWhenCountMatches}、
 *       {@link #batchEnableShouldRejectMixedOtherDocumentChunkBeforeModelOrVectorWrites}、
 *       {@link #batchShouldRejectWhenCrossTenantChunksFilteredOutAndStillCarryTenantPredicate}</li>
 *   <li>判据 2（服务层可测部分）：{@link #pageQueryShouldCarryEnabledAndTenantFilterForListingExclusion}、
 *       {@link #disableChunkShouldRemoveVectorRowUnderPrincipalTenant}、
 *       {@link #batchDisableShouldUpdateWithTenantAndDeleteWholeVectorSet}；
 *       检索结果层面的排除依赖真库/真机（window 腿，见交付报告）</li>
 *   <li>判据 3：{@link #deleteShouldRemoveVectorRowWithSameChunkIdAndTenantScopedRowDelete}、
 *       {@link #deleteShouldRejectBlankOrUnknownChunkIdWithoutWrites}、
 *       {@link #deleteShouldRejectChunkOfAnotherDocumentWithoutWrites}</li>
 *   <li>判据 4：{@link #batchEnableShouldUpdateWithTenantAndIndexWholeSet}、
 *       {@link #batchDisableShouldUpdateWithTenantAndDeleteWholeVectorSet}、
 *       {@link #batchDisableShouldOnlyTouchChunksThatNeedChange}、
 *       {@link #batchEnableShouldOnlyIndexChunksThatNeedChange}、
 *       {@link #batchEnableShouldRejectDisabledDocumentWithoutWritesOrModelCalls}、
 *       {@link #batchDisableWhenNothingLeftToToggleShouldRejectWithoutVectorWrites}、
 *       {@link #batchToggleShouldRejectWhileDocumentIsChunkingWithoutWrites}、
 *       {@link #enableChunkShouldIndexVectorOnEnableWithResolvedTarget}</li>
 * </ul>
 *
 * <p>"零写"断言是行状态不变的离线代理：拒绝路径不得触达任何 update/delete/向量写；
 * 行状态真实不变依赖真库回滚语义（window 腿）。
 */
@ExtendWith(MockitoExtension.class)
@Tag("dev")
class KnowledgeChunkBatchContractTest {

    private static final String DOC_ID = "1800000000000000001";
    private static final String KB_ID = "1800000000000000000";
    private static final String OTHER_DOC_ID = "1800000000000000002";

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

    // ------------------------------------------------------------ 判据 1：跨租户/混集合整体拒绝

    /**
     * SafetyTest 只覆盖了"请求 N 个 / 找到 M 个"的数量失配；这里补数量相等、
     * 但集合里混入他文档 chunk 的组合——必须整体拒绝，且在任何写之前。
     */
    @Test
    void batchDisableShouldRejectMixedOtherDocumentChunkWhenCountMatches() {
        when(documentMapper.selectOne(any())).thenReturn(document());
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk("c1", 0, 1, DOC_ID),
                chunk("c2", 1, 1, OTHER_DOC_ID)));

        ClientException ex = assertThrows(ClientException.class,
                () -> service.batchToggleEnabled(DOC_ID, batch("c1", "c2"), false));

        assertEquals("Chunk c2 不属于文档 " + DOC_ID, ex.getErrorMessage());
        verifyNoChunkWrites();
        verify(vectorStoreService, never()).deleteChunksByIds(any(), any(), any());
        verify(chunkEmbeddingService, never()).embed(any(), any());
    }

    /**
     * 混集合在启用分支同样要在模型/向量写之前拒绝：嵌入调用（真实外呼）零发生。
     */
    @Test
    void batchEnableShouldRejectMixedOtherDocumentChunkBeforeModelOrVectorWrites() {
        when(documentMapper.selectOne(any())).thenReturn(document());
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk("c1", 0, 0, DOC_ID),
                chunk("c2", 1, 0, OTHER_DOC_ID)));

        ClientException ex = assertThrows(ClientException.class,
                () -> service.batchToggleEnabled(DOC_ID, batch("c1", "c2"), true));

        assertEquals("Chunk c2 不属于文档 " + DOC_ID, ex.getErrorMessage());
        verifyNoChunkWrites();
        verify(chunkEmbeddingService, never()).embed(any(), any());
        verify(vectorStoreService, never()).indexDocumentChunks(any(), any(), any(), any());
    }

    /**
     * 跨租户混入的语义是"被租户条件过滤掉"：请求 2 个、本租户找到 0 个 → 数量失配拒绝。
     * 同时把候选查询的租户谓词载体断言出来（跨租户行正是在这里被挡住的）。
     */
    @Test
    void batchShouldRejectWhenCrossTenantChunksFilteredOutAndStillCarryTenantPredicate() {
        when(documentMapper.selectOne(any())).thenReturn(document());
        when(chunkMapper.selectList(any())).thenReturn(List.of());

        ClientException ex = assertThrows(ClientException.class,
                () -> service.batchToggleEnabled(DOC_ID, batch("c1", "c2"), false));

        assertEquals("存在无效的 Chunk ID，请求 2 个，实际找到 0 个", ex.getErrorMessage());

        ArgumentCaptor<LambdaQueryWrapper<KnowledgeChunkDO>> captor = queryCaptor();
        verify(chunkMapper).selectList(captor.capture());
        assertCarriesTenant(captor.getValue());

        verifyNoChunkWrites();
        verify(vectorStoreService, never()).deleteChunksByIds(any(), any(), any());
    }

    // ------------------------------------------------------------ 判据 2：停用/删除的检索排除（服务层可测部分）

    /**
     * 列表查询的"查询条件载体"：enabled 过滤与租户谓词都必须出现在 wrapper 上。
     * （检索结果层面的排除需要真库 SQL + 向量实例，记 window 腿。）
     */
    @Test
    void pageQueryShouldCarryEnabledAndTenantFilterForListingExclusion() {
        when(documentMapper.selectOne(any())).thenReturn(document());
        Page<KnowledgeChunkDO> stubPage = new Page<>(1, 10);
        when(chunkMapper.selectPage(any(), any())).thenReturn(stubPage);

        KnowledgeChunkPageRequest request = new KnowledgeChunkPageRequest();
        request.setEnabled(0);

        service.pageQuery(DOC_ID, request);

        ArgumentCaptor<LambdaQueryWrapper<KnowledgeChunkDO>> captor = queryCaptor();
        verify(chunkMapper).selectPage(any(), captor.capture());
        LambdaQueryWrapper<KnowledgeChunkDO> wrapper = captor.getValue();
        assertCarriesTenant(wrapper);
        assertTrue(wrapper.getSqlSegment().contains("enabled"),
                "列表查询的 enabled 过滤条件必须进 SQL：" + wrapper.getSqlSegment());
        assertTrue(wrapper.getParamNameValuePairs().containsValue(0),
                "enabled=0 必须作为绑定参数传入，禁止拼死常量");
    }

    /**
     * 单个停用：向量行必须按主体租户物理移除——这是"停用块不再可检索"在服务层的可测机制。
     */
    @Test
    void disableChunkShouldRemoveVectorRowUnderPrincipalTenant() {
        when(documentMapper.selectOne(any())).thenReturn(document());
        when(chunkMapper.selectOne(any())).thenReturn(
                chunk("c1", 0, 1, DOC_ID),
                chunk("c1", 0, 0, DOC_ID));
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase());

        service.enableChunk(DOC_ID, "c1", false);

        ArgumentCaptor<LambdaUpdateWrapper<KnowledgeChunkDO>> captor = wrapperCaptor();
        verify(chunkMapper).update(any(), captor.capture());
        assertCarriesTenant(captor.getValue());
        assertTrue(captor.getValue().getSqlSet().contains("enabled"),
                "停用必须写 enabled 列：" + captor.getValue().getSqlSet());

        verify(vectorStoreService).deleteChunkById("T1", "kb_collection", "c1");
        verify(vectorStoreService, never()).indexDocumentChunks(any(), any(), any(), any());
        verify(chunkEmbeddingService, never()).embed(any(), any());
    }

    // ------------------------------------------------------------ 判据 3：删除路径与契约错误

    /**
     * 删除：行删除与向量清理必须指向同一个 chunkId，且两个写条件都带主体租户；
     * 文档 chunk_count 递减随行删除发生。
     */
    @Test
    void deleteShouldRemoveVectorRowWithSameChunkIdAndTenantScopedRowDelete() {
        when(documentMapper.selectOne(any())).thenReturn(document());
        when(chunkMapper.selectOne(any())).thenReturn(chunk("c1", 0, 1, DOC_ID));
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase());

        service.delete(DOC_ID, "c1");

        ArgumentCaptor<LambdaQueryWrapper<KnowledgeChunkDO>> rowDelete = queryCaptor();
        verify(chunkMapper).delete(rowDelete.capture());
        assertCarriesTenant(rowDelete.getValue());
        assertTrue(rowDelete.getValue().getParamNameValuePairs().containsValue("c1"),
                "行删除必须按该 chunkId 收窄");

        ArgumentCaptor<LambdaUpdateWrapper<KnowledgeDocumentDO>> docUpdate = docWrapperCaptor();
        verify(documentMapper).update(docUpdate.capture());
        assertCarriesTenant(docUpdate.getValue());
        assertTrue(docUpdate.getValue().getSqlSet().contains("chunk_count"),
                "删除后文档 chunk_count 必须递减：" + docUpdate.getValue().getSqlSet());

        verify(vectorStoreService).deleteChunkById("T1", "kb_collection", "c1");
        verify(vectorStoreService, never()).deleteChunksByIds(any(), any(), any());
        verify(chunkEmbeddingService, never()).embed(any(), any());
    }

    /** 空/不存在的 chunkId 按现有契约报"Chunk 不存在"，且零写、零向量调用。 */
    @Test
    void deleteShouldRejectBlankOrUnknownChunkIdWithoutWrites() {
        when(documentMapper.selectOne(any())).thenReturn(document());

        ClientException ex = assertThrows(ClientException.class, () -> service.delete(DOC_ID, " "));

        assertEquals("Chunk 不存在", ex.getErrorMessage());
        verifyNoChunkWrites();
        verify(documentMapper, never()).update(any(LambdaUpdateWrapper.class));
        verify(vectorStoreService, never()).deleteChunkById(any(), any(), any());
    }

    /** 他文档的 chunk 不能借本文档 docId 删除。 */
    @Test
    void deleteShouldRejectChunkOfAnotherDocumentWithoutWrites() {
        when(documentMapper.selectOne(any())).thenReturn(document());
        when(chunkMapper.selectOne(any())).thenReturn(chunk("c9", 0, 1, OTHER_DOC_ID));

        ClientException ex = assertThrows(ClientException.class, () -> service.delete(DOC_ID, "c9"));

        assertEquals("Chunk 不属于该文档", ex.getErrorMessage());
        verifyNoChunkWrites();
        verify(vectorStoreService, never()).deleteChunkById(any(), any(), any());
    }

    // ------------------------------------------------------------ 判据 4：合法批量启/停正例（含租户绑定）

    /** 批量启用：整体更新带租户条件；向量按同一集合整体重建，租户取落点身份。 */
    @Test
    void batchEnableShouldUpdateWithTenantAndIndexWholeSet() {
        when(documentMapper.selectOne(any())).thenReturn(document());
        when(chunkMapper.selectList(any())).thenReturn(
                List.of(chunk("c1", 0, 0, DOC_ID), chunk("c2", 1, 0, DOC_ID)),
                List.of(chunk("c1", 0, 0, DOC_ID), chunk("c2", 1, 0, DOC_ID)),
                List.of(chunk("c1", 0, 1, DOC_ID), chunk("c2", 1, 1, DOC_ID)));
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase());
        when(vectorTargetResolver.resolve(any())).thenReturn(target());
        List<EmbeddedChunk> vectors = List.of(embedded("c1", 0), embedded("c2", 1));
        when(chunkEmbeddingService.embed(any(), any())).thenReturn(vectors);

        service.batchToggleEnabled(DOC_ID, batch("c1", "c2"), true);

        ArgumentCaptor<LambdaUpdateWrapper<KnowledgeChunkDO>> captor = wrapperCaptor();
        verify(chunkMapper).update(captor.capture());
        assertCarriesTenant(captor.getValue());
        assertTrue(captor.getValue().getSqlSet().contains("enabled"),
                "批量启用必须写 enabled 列");

        ArgumentCaptor<List<EmbeddedChunk>> vectorCaptor = vectorListCaptor();
        verify(vectorStoreService).indexDocumentChunks(eq("T1"), eq("kb_collection"), eq(DOC_ID),
                vectorCaptor.capture());
        assertEquals(2, vectorCaptor.getValue().size(), "向量重建必须覆盖同一集合全部块");
        assertSame(vectors.get(0), vectorCaptor.getValue().get(0));
        assertSame(vectors.get(1), vectorCaptor.getValue().get(1));
        verify(vectorStoreService, never()).deleteChunksByIds(any(), any(), any());
    }

    /** 批量停用：整体更新带租户条件；向量删除与需变更集合一致（多一个不多、少一个不少）。 */
    @Test
    void batchDisableShouldUpdateWithTenantAndDeleteWholeVectorSet() {
        when(documentMapper.selectOne(any())).thenReturn(document());
        when(chunkMapper.selectList(any())).thenReturn(
                List.of(chunk("c1", 0, 1, DOC_ID), chunk("c2", 1, 1, DOC_ID)),
                List.of(chunk("c1", 0, 1, DOC_ID), chunk("c2", 1, 1, DOC_ID)),
                List.of(chunk("c1", 0, 0, DOC_ID), chunk("c2", 1, 0, DOC_ID)));
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase());
        when(vectorTargetResolver.resolve(any())).thenReturn(target());

        service.batchToggleEnabled(DOC_ID, batch("c1", "c2"), false);

        ArgumentCaptor<LambdaUpdateWrapper<KnowledgeChunkDO>> captor = wrapperCaptor();
        verify(chunkMapper).update(captor.capture());
        assertCarriesTenant(captor.getValue());
        assertTrue(captor.getValue().getSqlSet().contains("enabled"));

        verify(vectorStoreService).deleteChunksByIds("T1", "kb_collection", List.of("c1", "c2"));
        verify(vectorStoreService, never()).indexDocumentChunks(any(), any(), any(), any());
        verify(chunkEmbeddingService, never()).embed(any(), any());
    }

    /**
     * 只对状态需要变更的子集操作：已是停用态的块不重复写、不重复删向量
     * （更新 IN 集合与 deleteChunksByIds 集合都等于 needUpdate 子集，而不是请求全集）。
     */
    @Test
    void batchDisableShouldOnlyTouchChunksThatNeedChange() {
        when(documentMapper.selectOne(any())).thenReturn(document());
        when(chunkMapper.selectList(any())).thenReturn(
                List.of(chunk("c1", 0, 1, DOC_ID), chunk("c2", 1, 0, DOC_ID)),
                List.of(chunk("c1", 0, 1, DOC_ID)),
                List.of(chunk("c1", 0, 0, DOC_ID), chunk("c2", 1, 0, DOC_ID)));
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase());
        when(vectorTargetResolver.resolve(any())).thenReturn(target());

        service.batchToggleEnabled(DOC_ID, batch("c1", "c2"), false);

        ArgumentCaptor<LambdaUpdateWrapper<KnowledgeChunkDO>> captor = wrapperCaptor();
        verify(chunkMapper).update(captor.capture());
        assertCarriesTenant(captor.getValue());
        Map<String, Object> updateParams = captor.getValue().getParamNameValuePairs();
        assertTrue(updateParams.containsValue("c1"), "更新必须命中需变更子集 c1：" + updateParams);
        assertFalse(updateParams.containsValue("c2"), "已是停用态的 c2 不得进入更新条件：" + updateParams);

        verify(vectorStoreService).deleteChunksByIds("T1", "kb_collection", List.of("c1"));
        verify(chunkEmbeddingService, never()).embed(any(), any());
        verify(vectorStoreService, never()).indexDocumentChunks(any(), any(), any(), any());
    }

    /**
     * 启用子集：只为需变更的块重建向量（已是启用态的块不重复嵌入、不重复索引）。
     */
    @Test
    void batchEnableShouldOnlyIndexChunksThatNeedChange() {
        when(documentMapper.selectOne(any())).thenReturn(document());
        when(chunkMapper.selectList(any())).thenReturn(
                List.of(chunk("c1", 0, 1, DOC_ID), chunk("c2", 1, 0, DOC_ID)),
                List.of(chunk("c2", 1, 0, DOC_ID)),
                List.of(chunk("c1", 0, 1, DOC_ID), chunk("c2", 1, 1, DOC_ID)));
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase());
        when(vectorTargetResolver.resolve(any())).thenReturn(target());
        List<EmbeddedChunk> vectors = List.of(embedded("c2", 1));
        when(chunkEmbeddingService.embed(any(), any())).thenReturn(vectors);

        service.batchToggleEnabled(DOC_ID, batch("c1", "c2"), true);

        ArgumentCaptor<LambdaUpdateWrapper<KnowledgeChunkDO>> captor = wrapperCaptor();
        verify(chunkMapper).update(captor.capture());
        assertCarriesTenant(captor.getValue());
        Map<String, Object> updateParams = captor.getValue().getParamNameValuePairs();
        assertTrue(updateParams.containsValue("c2"), "更新必须命中需变更子集 c2：" + updateParams);
        assertFalse(updateParams.containsValue("c1"), "已是启用态的 c1 不得进入更新条件：" + updateParams);

        ArgumentCaptor<List<EmbeddedChunk>> vectorCaptor = vectorListCaptor();
        verify(vectorStoreService).indexDocumentChunks(eq("T1"), eq("kb_collection"), eq(DOC_ID),
                vectorCaptor.capture());
        assertEquals(1, vectorCaptor.getValue().size(), "只为需变更子集重建向量");
        assertSame(vectors.get(0), vectorCaptor.getValue().get(0));
        verify(vectorStoreService, never()).deleteChunksByIds(any(), any(), any());
    }

    /** 文档未启用时批量启用被拒：零写、零嵌入调用。 */
    @Test
    void batchEnableShouldRejectDisabledDocumentWithoutWritesOrModelCalls() {
        KnowledgeDocumentDO disabled = document();
        disabled.setEnabled(0);
        when(documentMapper.selectOne(any())).thenReturn(disabled);

        ClientException ex = assertThrows(ClientException.class,
                () -> service.batchToggleEnabled(DOC_ID, batch("c1"), true));

        assertEquals("文档未启用，无法启用Chunk，请先启用文档", ex.getErrorMessage());
        verifyNoChunkWrites();
        verify(chunkEmbeddingService, never()).embed(any(), any());
    }

    /** 集合内全部已是目标状态：按现有契约显式拒绝（而不是空更新/重复删向量）。 */
    @Test
    void batchDisableWhenNothingLeftToToggleShouldRejectWithoutVectorWrites() {
        when(documentMapper.selectOne(any())).thenReturn(document());
        when(chunkMapper.selectList(any())).thenReturn(
                List.of(chunk("c1", 0, 0, DOC_ID), chunk("c2", 1, 0, DOC_ID)),
                List.of());

        ClientException ex = assertThrows(ClientException.class,
                () -> service.batchToggleEnabled(DOC_ID, batch("c1", "c2"), false));

        assertEquals("所有 Chunk 已全部禁用，无需重复操作", ex.getErrorMessage());
        verifyNoChunkWrites();
        verify(vectorStoreService, never()).deleteChunksByIds(any(), any(), any());
    }

    /** 文档分块处理中（running）：批量启停整体拒绝，零写。 */
    @Test
    void batchToggleShouldRejectWhileDocumentIsChunkingWithoutWrites() {
        KnowledgeDocumentDO running = document();
        running.setStatus("running");
        when(documentMapper.selectOne(any())).thenReturn(running);

        ClientException ex = assertThrows(ClientException.class,
                () -> service.batchToggleEnabled(DOC_ID, batch("c1"), false));

        assertEquals("文档正在分块处理中，暂不支持批量修改 Chunk 状态", ex.getErrorMessage());
        verifyNoChunkWrites();
        verify(vectorStoreService, never()).deleteChunksByIds(any(), any(), any());
    }

    /** 单个启用：向量按落点身份写回（启用块可检索的必要条件）。 */
    @Test
    void enableChunkShouldIndexVectorOnEnableWithResolvedTarget() {
        when(documentMapper.selectOne(any())).thenReturn(document());
        when(chunkMapper.selectOne(any())).thenReturn(
                chunk("c1", 0, 0, DOC_ID),
                chunk("c1", 0, 1, DOC_ID));
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase());
        when(vectorTargetResolver.resolve(any())).thenReturn(target());
        List<EmbeddedChunk> vectors = List.of(embedded("c1", 0));
        when(chunkEmbeddingService.embed(any(), any())).thenReturn(vectors);

        service.enableChunk(DOC_ID, "c1", true);

        ArgumentCaptor<LambdaUpdateWrapper<KnowledgeChunkDO>> captor = wrapperCaptor();
        verify(chunkMapper).update(any(), captor.capture());
        assertCarriesTenant(captor.getValue());

        ArgumentCaptor<List<EmbeddedChunk>> vectorCaptor = vectorListCaptor();
        verify(vectorStoreService).indexDocumentChunks(eq("T1"), eq("kb_collection"), eq(DOC_ID),
                vectorCaptor.capture());
        assertEquals(1, vectorCaptor.getValue().size());
        assertSame(vectors.get(0), vectorCaptor.getValue().get(0));
        verify(vectorStoreService, never()).deleteChunkById(any(), any(), any());
    }

    // ------------------------------------------------------------ 断言与夹具

    /** 拒绝路径的"零写"代理断言：两个 update 重载、delete、insert 都不得被触达。 */
    private void verifyNoChunkWrites() {
        verify(chunkMapper, never()).update(any(LambdaUpdateWrapper.class));
        verify(chunkMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
        verify(chunkMapper, never()).delete(any(LambdaQueryWrapper.class));
        verify(chunkMapper, never()).insert(any(KnowledgeChunkDO.class));
    }

    /** 条件载体必须带租户谓词，且租户绑定为当前主体 T1。 */
    private static void assertCarriesTenant(AbstractWrapper<?, ?, ?> wrapper) {
        String sql = wrapper.getSqlSegment();
        assertTrue(sql != null && sql.contains("tenant_id"),
                "条件必须带租户谓词：" + sql);
        assertTrue(wrapper.getParamNameValuePairs().containsValue("T1"),
                "租户必须绑定当前执行主体 T1：" + wrapper.getParamNameValuePairs());
    }

    private static KnowledgeChunkBatchRequest batch(String... ids) {
        KnowledgeChunkBatchRequest request = new KnowledgeChunkBatchRequest();
        request.setChunkIds(new ArrayList<>(Arrays.asList(ids)));
        return request;
    }

    private static KnowledgeDocumentDO document() {
        return KnowledgeDocumentDO.builder()
                .id(DOC_ID)
                .kbId(KB_ID)
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
                .kbId(KB_ID)
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
                .id(KB_ID)
                .tenantId("T1")
                .name("默认知识库")
                .collectionName("kb_collection")
                .embeddingModel("qwen3-embedding:8b-fp16")
                .deleted(0)
                .build();
    }

    private static VectorTarget target() {
        return new VectorTarget("T1", "kb_collection", "qwen3-embedding:8b-fp16", 1536);
    }

    private static EmbeddedChunk embedded(String chunkId, int index) {
        return new EmbeddedChunk(
                ChunkAssembler.restore(chunkId, index, "内容" + index, "内容" + index),
                new float[1536]);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<LambdaQueryWrapper<KnowledgeChunkDO>> queryCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<LambdaUpdateWrapper<KnowledgeChunkDO>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<LambdaUpdateWrapper<KnowledgeDocumentDO>> docWrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<List<EmbeddedChunk>> vectorListCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }
}
