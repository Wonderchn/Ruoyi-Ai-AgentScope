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
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.core.ingest.IngestionKernel;
import com.nageoffer.ai.ragent.core.ingest.IngestionOutcome;
import com.nageoffer.ai.ragent.core.ingest.IngestionSpec;
import com.nageoffer.ai.ragent.core.ingest.VectorTarget;
import com.nageoffer.ai.ragent.core.ingest.sink.ChunkIndexWriter;
import com.nageoffer.ai.ragent.core.parser.registry.ParseProfile;
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
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentUploadRequest;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentChunkLogDO;
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
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * R17 F05:6：文档服务层对摄取规格的<b>实际采用</b>与<b>非法即拒（零任务副作用）</b>。
 *
 * <p>判据映射：
 * <ul>
 *   <li>判据 1（非法 spec → 4xx 语义拒绝、零任务副作用）：
 *       {@link #uploadWithIllegalSpecIsRejectedBeforeAnySideEffect}、
 *       {@link #updateWithIllegalSpecIsRejectedBeforeAnyWrite}；
 *       codec 侧的严格校验见 {@code IngestionSpecCodecStrictnessTest}</li>
 *   <li>判据 2（两组配置实际被消费、采用值随 spec 变化）：
 *       {@link #uploadAdoptsDifferentSpecsIntoTheStoredRows}（落库值）、
 *       {@link #executeChunkPassesStoredSpecIntoKernelAndLog}（任务实际采用值）</li>
 *   <li>判据 3（缺省语义钉死）：{@link #uploadWithoutSpecKeepsColumnNullSoDefaultsApply}</li>
 * </ul>
 *
 * <p>本类用<b>真实 codec</b>（{@code @Spy}）而非 mock：判据 1/2 说的就是"非法值真的被拦下"与
 * "提交值真的被采用"，把 codec mock 掉等于把要证的东西替换成断言的空壳。
 * "零任务副作用"= 不落文档行、不发 MQ、不触调度/存储——以 mock 零交互断言。
 */
@ExtendWith(MockitoExtension.class)
@Tag("dev")
class KnowledgeDocumentIngestionSpecConsumptionTest {

    private static final String KB_ID = "kb-1";
    private static final String DOC_ID = "doc-a";

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
    private FileStorageService fileStorageService;
    @Mock
    private VectorStoreService vectorStoreService;
    @Mock
    private KnowledgeChunkService knowledgeChunkService;
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

    /** 真实 codec：本类要证的正是"非法值被拦下、合法值被采用"。 */
    @Spy
    private IngestionSpecCodec ingestionSpecCodec = new IngestionSpecCodec(new ObjectMapper());

    @InjectMocks
    private KnowledgeDocumentServiceImpl service;

    /** Lambda wrapper 的列名解析需要实体 TableInfo（与既有文档服务测试同一修法）。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeBaseDO.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeDocumentDO.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeDocumentChunkLogDO.class);
    }

    @BeforeEach
    void principal() {
        PrincipalContext.set(new ExecutionPrincipal("T1", "2101", "platform:T1:2101",
                1, 1, Set.of("document.upload"), "test-jti", "platform:test", 1L, 9_999_999_999L));
    }

    @AfterEach
    void clear() {
        PrincipalContext.clear();
    }

    // ------------------------------------------------------------------ 判据 1：非法 spec 零任务副作用

    /** 上传：非法 spec 必须在"存对象/落文档行/发 MQ"之前被拒——一条副作用都不许发生。 */
    @Test
    void uploadWithIllegalSpecIsRejectedBeforeAnySideEffect() {
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase());

        ClientException ex = assertThrows(ClientException.class,
                () -> service.upload(KB_ID, uploadRequest("{\"chunkSize\":120}"), pdf("a.pdf")));

        assertTrue(ex.getErrorMessage().contains("chunkSize"), ex.getErrorMessage());
        verify(fileStorageService, never()).upload(any(), any(MultipartFile.class));
        verify(documentMapper, never()).insert(any(KnowledgeDocumentDO.class));
        verify(messageQueueProducer, never()).sendInTransaction(any(), any(), any(), any(), any());
        verifyNoInteractions(scheduleService);
    }

    /** 更新：非法 spec 在 wrapper 组装期就被拒，任何写都不许落。 */
    @Test
    void updateWithIllegalSpecIsRejectedBeforeAnyWrite() {
        when(documentMapper.selectOne(any())).thenReturn(document(DOC_ID, null));

        KnowledgeDocumentUpdateRequest request = new KnowledgeDocumentUpdateRequest();
        request.setDocName("季度报告");
        request.setProcessMode("chunk");
        request.setIngestionSpec("{\"maxChars\":\"abc\"}");

        ClientException ex = assertThrows(ClientException.class, () -> service.update(DOC_ID, request));

        assertTrue(ex.getErrorMessage().contains("maxChars"), ex.getErrorMessage());
        verify(documentMapper, never()).update(any(LambdaUpdateWrapper.class));
        verify(documentMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
        verify(documentMapper, never()).updateById(any(KnowledgeDocumentDO.class));
        verifyNoInteractions(scheduleService, messageQueueProducer);
    }

    // ------------------------------------------------------------------ 判据 1b：缺/非法来源与处理模式 → 具名 4xx（ClientException）

    /**
     * F05 运行时收口（③）：缺/非法 {@code sourceType} 必须产出具名 4xx 语义（{@code ClientException}），
     * 而不是逃逸成平台 500 信封。
     *
     * <p>真机实证（v4 窗 W-U a）：仅 file 部件的上传，内层 {@code SourceType.normalize} 对空值抛
     * {@code IllegalArgumentException} → 平台 {@code GlobalExceptionHandler} 500 信封 → 网关
     * fail-closed 成 503「授权服务不可用」。修法：客户端输入归一化处把 IAE 驯化
     * 为框架 {@code ClientException}（内层解析器映射为具名 4xx）。
     */
    @Test
    void uploadWithoutSourceTypeIsRejectedAsNamedClientError() {
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase());

        ClientException ex = assertThrows(ClientException.class,
                () -> service.upload(KB_ID, uploadRequest(null, "chunk", null), pdf("a.pdf")));

        assertTrue(ex.getErrorMessage().contains("来源类型"), ex.getErrorMessage());
        verifyNoUploadSideEffects();
    }

    @Test
    void uploadWithUnknownSourceTypeIsRejectedAsNamedClientError() {
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase());

        ClientException ex = assertThrows(ClientException.class,
                () -> service.upload(KB_ID, uploadRequest("carrier-pigeon", "chunk", null), pdf("a.pdf")));

        assertTrue(ex.getErrorMessage().contains("不支持的来源类型"), ex.getErrorMessage());
        verifyNoUploadSideEffects();
    }

    @Test
    void uploadWithoutProcessModeIsRejectedAsNamedClientError() {
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase());

        ClientException ex = assertThrows(ClientException.class,
                () -> service.upload(KB_ID, uploadRequest("file", null, null), pdf("a.pdf")));

        assertTrue(ex.getErrorMessage().contains("处理模式"), ex.getErrorMessage());
        verifyNoUploadSideEffects();
    }

    @Test
    void uploadWithUnknownProcessModeIsRejectedAsNamedClientError() {
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase());

        ClientException ex = assertThrows(ClientException.class,
                () -> service.upload(KB_ID, uploadRequest("file", "no-such-mode", null), pdf("a.pdf")));

        assertTrue(ex.getErrorMessage().contains("不支持的处理模式"), ex.getErrorMessage());
        verifyNoUploadSideEffects();
    }

    /** 非法输入必须在任何副作用之前被拒：不落对象、不落文档行、不发 MQ、不触调度。 */
    private void verifyNoUploadSideEffects() {
        verify(fileStorageService, never()).upload(any(), any(MultipartFile.class));
        verify(documentMapper, never()).insert(any(KnowledgeDocumentDO.class));
        verify(messageQueueProducer, never()).sendInTransaction(any(), any(), any(), any(), any());
        verifyNoInteractions(scheduleService);
    }

    // ------------------------------------------------------------------ 判据 2：两组配置的实际采用值

    /** 落库采用值：同一文档上传两次、两组 spec，落库 JSON 必须各自不同且等于提交值。 */
    @Test
    void uploadAdoptsDifferentSpecsIntoTheStoredRows() {
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase());
        when(fileStorageService.upload(any(), any(MultipartFile.class)))
                .thenReturn(stored("doc.pdf"));
        when(parserRegistry.canParse(anyString())).thenReturn(true);

        service.upload(KB_ID, uploadRequest(
                "{\"parseProfile\":\"fast\",\"maxChars\":512,\"overlapChars\":64,\"rowsPerChunk\":50}"), pdf("a.pdf"));
        service.upload(KB_ID, uploadRequest(
                "{\"parseProfile\":\"fidelity\",\"maxChars\":2048,\"overlapChars\":256,\"rowsPerChunk\":20,"
                        + "\"toleranceFactor\":4}"), pdf("b.pdf"));

        ArgumentCaptor<KnowledgeDocumentDO> inserted = ArgumentCaptor.forClass(KnowledgeDocumentDO.class);
        verify(documentMapper, times(2)).insert(inserted.capture());

        String small = inserted.getAllValues().get(0).getIngestionSpec();
        String large = inserted.getAllValues().get(1).getIngestionSpec();
        assertNotEquals(small, large);

        IngestionSpec adoptedSmall = ingestionSpecCodec.read(small);
        IngestionSpec adoptedLarge = ingestionSpecCodec.read(large);
        assertEquals(ParseProfile.FAST, adoptedSmall.parseProfile());
        assertEquals(512, adoptedSmall.budget().maxChars());
        assertEquals(ParseProfile.FIDELITY, adoptedLarge.parseProfile());
        assertEquals(2048, adoptedLarge.budget().maxChars());
        assertEquals(256, adoptedLarge.budget().overlapChars());
        assertEquals(20, adoptedLarge.budget().rowsPerChunk());
        assertEquals(4, adoptedLarge.budget().toleranceFactor());
        assertNotEquals(ingestionSpecCodec.write(IngestionSpec.defaults()), small);
    }

    /**
     * 任务实际采用值：执行分块时传给摄取内核的 spec 必须逐格等于该文档落库的那一份，
     * 且分块日志记录的解析档位随 spec 变化——这是"配置真的被消费"而不是"JSON 存下来了"。
     */
    @Test
    void executeChunkPassesStoredSpecIntoKernelAndLog() {
        // 落库列里存的是 codec 归一化后的嵌套线路形状（version/parseProfile/budget），
        // 直接用提交时的扁平形状构造夹具会让 read 回落默认值——夹具必须与生产同一形状
        String storedSmall = ingestionSpecCodec.normalize(
                "{\"parseProfile\":\"fast\",\"maxChars\":512,\"overlapChars\":64,\"rowsPerChunk\":50}");
        String storedLarge = ingestionSpecCodec.normalize(
                "{\"parseProfile\":\"fidelity\",\"maxChars\":2048,\"overlapChars\":256,\"rowsPerChunk\":20,"
                        + "\"toleranceFactor\":4}");
        when(documentMapper.selectById("doc-a")).thenReturn(document("doc-a", storedSmall));
        when(documentMapper.selectById("doc-b")).thenReturn(document("doc-b", storedLarge));
        when(knowledgeBaseMapper.selectById(KB_ID)).thenReturn(knowledgeBase());
        when(vectorTargetResolver.resolve(any())).thenReturn(target());
        when(fileStorageService.openStream(anyString()))
                .thenAnswer(invocation -> new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8)));
        when(ingestionKernel.run(any(), any(), any(), any())).thenReturn(outcome());

        service.executeChunk("doc-a");
        service.executeChunk("doc-b");

        ArgumentCaptor<IngestionSpec> adopted = ArgumentCaptor.forClass(IngestionSpec.class);
        verify(ingestionKernel, times(2)).run(any(), any(), adopted.capture(), any());

        IngestionSpec small = adopted.getAllValues().get(0);
        IngestionSpec large = adopted.getAllValues().get(1);
        assertEquals(ParseProfile.FAST, small.parseProfile());
        assertEquals(512, small.budget().maxChars());
        assertEquals(64, small.budget().overlapChars());
        assertEquals(ParseProfile.FIDELITY, large.parseProfile());
        assertEquals(2048, large.budget().maxChars());
        assertEquals(256, large.budget().overlapChars());
        assertEquals(4, large.budget().toleranceFactor());

        // 分块日志里的档位与实际采用值一致（配置生效的可观测锚点）
        ArgumentCaptor<KnowledgeDocumentChunkLogDO> logs = ArgumentCaptor.forClass(KnowledgeDocumentChunkLogDO.class);
        verify(chunkLogMapper, times(2)).insert(logs.capture());
        assertEquals(ParseProfile.FAST.getCode(), logs.getAllValues().get(0).getParseProfile());
        assertEquals(ParseProfile.FIDELITY.getCode(), logs.getAllValues().get(1).getParseProfile());
    }

    // ------------------------------------------------------------------ 判据 3：缺省语义

    /** 不传 spec：列必须留空（null）而不是落一份快照 JSON——缺省由读路径回落同一份默认值。 */
    @Test
    void uploadWithoutSpecKeepsColumnNullSoDefaultsApply() {
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase());
        when(fileStorageService.upload(any(), any(MultipartFile.class))).thenReturn(stored("doc.pdf"));
        when(parserRegistry.canParse(anyString())).thenReturn(true);

        service.upload(KB_ID, uploadRequest(null), pdf("a.pdf"));

        ArgumentCaptor<KnowledgeDocumentDO> inserted = ArgumentCaptor.forClass(KnowledgeDocumentDO.class);
        verify(documentMapper).insert(inserted.capture());
        assertNull(inserted.getValue().getIngestionSpec(),
                "缺省必须留空列，落快照会让后续默认值升级失效");
    }

    // ------------------------------------------------------------------ 夹具

    private static KnowledgeDocumentUploadRequest uploadRequest(String spec) {
        return uploadRequest("file", "chunk", spec);
    }

    private static KnowledgeDocumentUploadRequest uploadRequest(String sourceType, String processMode, String spec) {
        KnowledgeDocumentUploadRequest request = new KnowledgeDocumentUploadRequest();
        request.setSourceType(sourceType);
        request.setProcessMode(processMode);
        request.setIngestionSpec(spec);
        return request;
    }

    private static MultipartFile pdf(String name) {
        return new MockMultipartFile("file", name, "application/pdf", "pdf-bytes".getBytes(StandardCharsets.UTF_8));
    }

    private static KnowledgeBaseDO knowledgeBase() {
        return KnowledgeBaseDO.builder()
                .id(KB_ID)
                .name("默认知识库")
                .collectionName("coll-1")
                .embeddingModel("qwen3-embedding:8b-fp16")
                .deleted(0)
                .build();
    }

    private static StoredFileDTO stored(String filename) {
        return StoredFileDTO.builder()
                .url(KB_ID + "/" + filename)
                .detectedType("pdf")
                .mimeType("application/pdf")
                .size(9L)
                .originalFilename(filename)
                .build();
    }

    private static KnowledgeDocumentDO document(String docId, String ingestionSpec) {
        return KnowledgeDocumentDO.builder()
                .id(docId)
                .kbId(KB_ID)
                .docName("季度报告.pdf")
                .status("success")
                .processMode("chunk")
                .fileUrl(KB_ID + "/doc.pdf")
                .fileType("pdf")
                .mimeType("application/pdf")
                .ingestionSpec(ingestionSpec)
                .enabled(1)
                .deleted(0)
                .build();
    }

    private static VectorTarget target() {
        return new VectorTarget("T1", "coll-1", "qwen3-embedding:8b-fp16", 1536);
    }

    private static IngestionOutcome outcome() {
        return new IngestionOutcome("application/pdf", "tika", 1, List.of(), IngestionOutcome.IngestionTimings.zero());
    }
}
