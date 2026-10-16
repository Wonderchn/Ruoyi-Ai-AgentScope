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
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkAssembler;
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.core.ingest.VectorTarget;
import com.nageoffer.ai.ragent.core.ingest.embed.ChunkEmbeddingService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.infra.token.TokenCounterService;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkCreateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkUpdateRequest;
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

import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R17-F05:3-M1：分块写面的写身份判据（内嵌传输下 UserContext 恒空）。
 *
 * <p>侦察结论（诊断卡 §2.1）：{@code ai_knowledge_chunk.created_by} 是 V7 NOT NULL 且无默认值
 * （{@code V7__unified_ai_domain.sql:236}），而 {@code create}/{@code update} 用
 * {@code UserContext.getUsername()} 取操作人——内嵌传输下该上下文恒空
 * （{@code UserContextInterceptor} 对 {@code /internal/ai/v1/} + PrincipalContext 跳过填充），
 * 插入时 created_by 落 NULL → PG NOT NULL 违约 → DataAccessException → HTTP 503「依赖不可用」。
 * 写身份必须与 KB/文档先例一致，取自执行主体。
 *
 * <p>判据（红先行）：内嵌前提（UserContext 空＋PrincipalContext 在场）下，create 行
 * {@code created_by}/{@code updated_by} 取主体 userId；update 行 {@code updated_by} 同法；
 * 缺主体时 fail-closed 拒绝且不触达数据库。
 */
@ExtendWith(MockitoExtension.class)
@Tag("dev")
class KnowledgeChunkWriteIdentityTest {

    private static final String TENANT = "T1";
    private static final String USER = "2101";
    private static final String MEMBER = "platform:T1:2101";
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
        PrincipalContext.set(new ExecutionPrincipal(TENANT, USER, MEMBER,
                1, 1, Set.of("kb.write"), "jti", "platform", 0, Long.MAX_VALUE));
    }

    @AfterEach
    void clear() {
        PrincipalContext.clear();
    }

    @Test
    void createFillsCreatedByAndUpdatedByFromTheExecutionPrincipal() {
        stubCreatePath();

        KnowledgeChunkCreateRequest request = new KnowledgeChunkCreateRequest();
        request.setContent("季度营收同比");
        request.setChunkId("1700000000000000009");

        service.create(DOC_ID, request);

        ArgumentCaptor<KnowledgeChunkDO> captor = ArgumentCaptor.forClass(KnowledgeChunkDO.class);
        verify(chunkMapper).insert(captor.capture());
        KnowledgeChunkDO inserted = captor.getValue();
        assertThat(inserted.getCreatedBy())
                .as("created_by 是 V7 NOT NULL 且无默认值；内嵌态 UserContext 恒空，只能取执行主体")
                .isEqualTo(USER);
        assertThat(inserted.getUpdatedBy())
                .as("updated_by 与 created_by 同一 fail-closed 口径")
                .isEqualTo(USER);
        // 行数推进：文档 chunk_count + 1 必须被触发（create 成功的可观测副作用）
        verify(documentMapper).update(any());
    }

    @Test
    void updateFillsUpdatedByFromTheExecutionPrincipal() {
        KnowledgeChunkDO existing = chunk("c1", 0, 1, DOC_ID);
        when(documentMapper.selectOne(any())).thenReturn(document());
        when(chunkMapper.selectOne(any())).thenReturn(existing);
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase());
        when(tokenCounterService.countTokens(any())).thenReturn(2);
        when(vectorTargetResolver.resolve(any())).thenReturn(new VectorTarget(TENANT, "p", "m", 1536));
        when(chunkEmbeddingService.embed(any(), any())).thenReturn(List.of(embedded("c1", 0)));

        KnowledgeChunkUpdateRequest request = new KnowledgeChunkUpdateRequest();
        request.setContent("季度营收环比");

        service.update(DOC_ID, "c1", request);

        assertThat(existing.getUpdatedBy())
                .as("update 的 updated_by 同样只能取执行主体，不能依赖内嵌态恒空的 UserContext")
                .isEqualTo(USER);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaUpdateWrapper<KnowledgeChunkDO>> captor =
                (ArgumentCaptor) ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(chunkMapper).update(isNull(), captor.capture());
        assertThat(captor.getValue().getParamNameValuePairs().values())
                .as("写进 UPDATE 的参数里必须出现主体 userId（而不是 NULL）")
                .contains(USER);
    }

    @Test
    void createRejectsWithoutAnExecutionPrincipalAndNeverTouchesTheDatabase() {
        PrincipalContext.clear();
        KnowledgeChunkCreateRequest request = new KnowledgeChunkCreateRequest();
        request.setContent("季度营收同比");

        assertThatThrownBy(() -> service.create(DOC_ID, request))
                .as("缺主体必须 fail-closed 拒绝，不允许匿名兜底写入")
                .isInstanceOf(ClientException.class);
        verify(chunkMapper, never()).insert(any(KnowledgeChunkDO.class));
    }

    // ------------------------------------------------------------ 夹具

    private void stubCreatePath() {
        when(documentMapper.selectOne(any())).thenReturn(document());
        when(chunkMapper.selectOne(any())).thenReturn(chunk("c4", 4, 1, DOC_ID));
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase());
        when(tokenCounterService.countTokens(any())).thenReturn(2);
        when(vectorTargetResolver.resolve(any())).thenReturn(new VectorTarget(TENANT, "p", "m", 1536));
        when(chunkEmbeddingService.embed(any(), any())).thenReturn(List.of(embedded("1700000000000000009", 5)));
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
                .tenantId(TENANT)
                .name("默认知识库")
                .collectionName("kb_collection")
                .embeddingModel("synthetic-feature-hash-1536")
                .deleted(0)
                .build();
    }

    private static EmbeddedChunk embedded(String chunkId, int index) {
        return new EmbeddedChunk(
                ChunkAssembler.restore(chunkId, index, "季度营收同比", "季度营收同比"),
                new float[1536]);
    }
}
