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

import com.baomidou.mybatisplus.core.MybatisConfiguration;
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
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentUploadRequest;
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
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * S2-F05-A2：文档上传（受理）路径的写身份判据（KB 系真机 503 同类缺陷）。
 *
 * <p>侦察结论：{@code ai_knowledge_document.created_by} 是 V7 NOT NULL 且无默认值
 * （{@code V7__unified_ai_domain.sql:216}），而 {@code upload} 用
 * {@code UserContext.getUsername()} 取值——内嵌传输下该上下文恒空
 * （{@code UserContextInterceptor} 对 {@code /internal/ai/v1/} + PrincipalContext 跳过填充），
 * 插入会与 KB 创建同一形状失败（NOT NULL 违约 → 503）。写身份必须取自执行主体。
 *
 * <p>判据（红先行）：upload 受理的新文档行 {@code created_by}/{@code updated_by}
 * 取当前执行主体的 userId；缺主体时 fail-closed 拒绝且不触达存储与数据库。
 */
@ExtendWith(MockitoExtension.class)
@Tag("dev")
class KnowledgeDocumentWriteIdentityTest {

    private static final String TENANT = "T1";
    private static final String USER = "2101";
    private static final String MEMBER = "platform:T1:2101";

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
    private TransactionOperations transactionOperations;
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

    @InjectMocks
    private KnowledgeDocumentServiceImpl service;

    /** Lambda wrapper 的列名解析需要实体 TableInfo（与 F06-A1 判据同一修法）。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeBaseDO.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeDocumentDO.class);
    }

    @BeforeEach
    void principal() {
        PrincipalContext.set(new ExecutionPrincipal(TENANT, USER, MEMBER,
                1, 1, Set.of("document.upload"), "test-jti", "platform:test", 1L, 9_999_999_999L));
    }

    @AfterEach
    void clear() {
        PrincipalContext.clear();
    }

    @Test
    void uploadFillsAuditColumnsFromTheExecutionPrincipal() {
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(KnowledgeBaseDO.builder()
                .id("kb-1").name("kb").collectionName("coll-1").build());
        when(fileStorageService.upload(eq("coll-1"), any(MultipartFile.class))).thenReturn(StoredFileDTO.builder()
                .url("kb-1/doc-a2.pdf")
                .detectedType("pdf")
                .mimeType("application/pdf")
                .size(9L)
                .originalFilename("doc-a2.pdf")
                .build());
        when(parserRegistry.canParse(anyString())).thenReturn(true);

        KnowledgeDocumentUploadRequest request = new KnowledgeDocumentUploadRequest();
        request.setSourceType("file");
        request.setProcessMode("chunk");
        MockMultipartFile file = new MockMultipartFile("file", "doc-a2.pdf", "application/pdf", "pdf-bytes".getBytes());

        service.upload("kb-1", request, file);

        ArgumentCaptor<KnowledgeDocumentDO> inserted = ArgumentCaptor.forClass(KnowledgeDocumentDO.class);
        verify(documentMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getCreatedBy())
                .as("created_by 是 V7 NOT NULL 且无默认值；内嵌态 UserContext 恒空，只能取执行主体")
                .isEqualTo(USER);
        assertThat(inserted.getValue().getUpdatedBy())
                .as("updated_by 与 created_by 同一 fail-closed 口径")
                .isEqualTo(USER);
    }

    @Test
    void uploadRejectsWithoutAnExecutionPrincipalAndNeverTouchesStorageOrDatabase() {
        PrincipalContext.clear();
        KnowledgeDocumentUploadRequest request = new KnowledgeDocumentUploadRequest();
        request.setSourceType("file");
        request.setProcessMode("chunk");
        MockMultipartFile file = new MockMultipartFile("file", "doc-a2.pdf", "application/pdf", "pdf-bytes".getBytes());

        assertThatThrownBy(() -> service.upload("kb-1", request, file))
                .as("缺主体必须拒绝（fail-closed），不允许匿名兜底写入")
                .isInstanceOf(ClientException.class);
        verifyNoInteractions(knowledgeBaseMapper, documentMapper, fileStorageService, parserRegistry);
    }
}
