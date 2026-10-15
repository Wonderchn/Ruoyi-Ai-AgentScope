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

package com.nageoffer.ai.ragent.ingestion.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.ingestion.task.IngestionPipelineReader;
import com.nageoffer.ai.ragent.ingestion.task.IngestionRunContract;
import com.nageoffer.ai.ragent.ingestion.task.IngestionTaskRepository;
import com.nageoffer.ai.ragent.runtime.CanonicalJson;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.config.RunConfigBindingPort;
import com.nageoffer.ai.ragent.runtime.config.RuntimeModelGatewayPort;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.exec.ProviderCallBoundary;
import com.nageoffer.ai.ragent.runtime.exec.RunExecution;
import com.nageoffer.ai.ragent.runtime.exec.RunExecutionGuard;
import com.nageoffer.ai.ragent.runtime.exec.RunExecutor;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import com.nageoffer.ai.ragent.runtime.usage.UsageLedgerService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.ruoyi.ai.api.runtime.ChunkingPort;
import org.ruoyi.ai.api.runtime.DocumentPort;
import org.ruoyi.ai.api.runtime.MinerUPort;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F06:1/2-A Stage C **行为**判据（Mockito canonical 端口桩）：
 * <ul>
 *   <li>happy-path：fetcher→parser→chunker→indexer→publish（F06-PLAN §80 端口映射）；</li>
 *   <li>取消检查点：取消后零后继 provider 调用、零 publish（§80）；</li>
 *   <li>单节点重试前驱复用：仅从直接前驱 COMPLETED 检查点恢复、已完成前驱零重复 provider（§84）；</li>
 *   <li>前驱产物 hash 失配 → 拒绝（§84 不伪装）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Tag("dev")
class IngestionPipelineExecutorTest {

    private static final String TENANT = "acme";
    private static final String RUN = "r-" + "a".repeat(32);
    private static final String PARENT = "r-" + "b".repeat(32);
    private static final String DOC = "doc-1";
    private static final String KB = "kb-1";
    private static final String UPLOAD = "upl-1";
    private static final String PIPE = "pipe-1";
    private static final String VERSION = "ver-1";
    private static final String OBJECT_KEY = "tenants/acme/upl-1.pdf";

    private static final String MARKDOWN = "# Title\n\nbody text";
    private static final String MD_SHA = CanonicalJson.sha256(MARKDOWN);
    private static final String STRUCTURED = "{}";
    private static final String ST_SHA = CanonicalJson.sha256(STRUCTURED);
    private static final String PARSE_KEY = "tenants/acme/docs/doc-1/ver-1/parsed-" + MD_SHA + ".md";
    private static final String STRUCTURED_KEY = "tenants/acme/docs/doc-1/ver-1/pages-" + ST_SHA + ".json";

    @Mock
    private IngestionPipelineReader pipelines;
    @Mock
    private IngestionTaskRepository tasks;
    @Mock
    private DocumentPort documents;
    @Mock
    private com.nageoffer.ai.ragent.ingest.PrivateObjectStore objectStore;
    @Mock
    private ObjectProvider<MinerUPort> mineruProvider;
    @Mock
    private MinerUPort mineru;
    @Mock
    private ChunkingPort chunker;
    @Mock
    private com.nageoffer.ai.ragent.ingest.EmbeddingGateway embeddingGateway;
    @Mock
    private RuntimeModelGatewayPort chat;
    @Mock
    private ObjectProvider<RunConfigBindingPort> bindingPortProvider;
    @Mock
    private UsageLedgerService usageLedger;
    @Mock
    private ProviderCallBoundary providerBoundary;
    @Mock
    private com.nageoffer.ai.ragent.runtime.RunAccessService access;
    @Mock
    private RunLedgerDao ledger;
    @Mock
    private RunExecutionGuard guard;
    @Mock
    private RevocationGuard revocationGuard;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private IngestionPipelineExecutor executor() {
        return new IngestionPipelineExecutor(pipelines, tasks, documents, objectStore, mineruProvider, chunker,
                embeddingGateway, chat, bindingPortProvider, usageLedger, providerBoundary, access, ledger,
                objectMapper);
    }

    private static IngestionPipelineReader.PipelineSnapshot chain() {
        return new IngestionPipelineReader.PipelineSnapshot(PIPE, "p", List.of(
                new IngestionPipelineReader.NodeSnapshot("n1", "parser", true, "n2", null, null),
                new IngestionPipelineReader.NodeSnapshot("n2", "chunker", true, "n3", null, null),
                new IngestionPipelineReader.NodeSnapshot("n3", "indexer", true, null, null, null)), "h");
    }

    private IngestionTaskRepository.TaskRow task(String metadataJson) {
        return new IngestionTaskRepository.TaskRow(RUN, PIPE, "file", OBJECT_KEY, "f.pdf", "pending", 0, null,
                null, metadataJson, null, null, "1", Instant.now(), Instant.now());
    }

    private static String metadata(String retryOf) {
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("runId", RUN);
        meta.put("pipelineId", PIPE);
        meta.put("docId", DOC);
        meta.put("kbId", KB);
        meta.put("uploadId", UPLOAD);
        meta.put("versionId", VERSION);
        meta.put("pipelineHash", "h");
        meta.put("sourceSha256", "sha-1");
        if (retryOf != null) {
            meta.put("retryOf", retryOf);
        }
        try {
            return new ObjectMapper().writeValueAsString(meta);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private RunRecord run(String inputJson) {
        return new RunRecord(TENANT, RUN, "platform:acme:1", "1", IngestionRunContract.ACTION, "RUNNING",
                "rh", "idem-1", inputJson, null, IngestionRunContract.EXECUTION_VERSION, 1, 1, "[]",
                1L, 1L, 1, 7L, "worker-1", Instant.now().plusSeconds(60), null, null, null, null,
                Instant.now(), null, null);
    }

    private RunExecution execution(String inputJson) {
        return new RunExecution(run(inputJson), guard);
    }

    private void stubCommon() {
        when(pipelines.load(TENANT, PIPE)).thenReturn(Optional.of(chain()));
        when(documents.findDocument(TENANT, DOC)).thenReturn(Optional.of(new DocumentPort.DocumentRow(
                DOC, KB, "f.pdf", "platform:acme:1", null, null, Instant.now())));
        when(documents.findUpload(TENANT, UPLOAD)).thenReturn(Optional.of(new DocumentPort.UploadRow(
                UPLOAD, DOC, KB, "platform:acme:1", "f.pdf", "application/pdf", 10L, "sha-1", OBJECT_KEY,
                "STORED", Instant.now())));
        when(documents.findVersion(TENANT, VERSION)).thenReturn(Optional.of(new DocumentPort.VersionRow(
                VERSION, DOC, UPLOAD, null, "STAGING", null, 0, null, null, null)));
        doAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get()).when(guard).commitAtomic(any());
        when(guard.stillOwned()).thenReturn(true);
        lenient().when(objectStore.exists(OBJECT_KEY)).thenReturn(true);
    }

    private void stubProviderPorts() {
        when(mineruProvider.getIfAvailable()).thenReturn(mineru);
        when(mineru.submit(any(), anyString(), anyString())).thenReturn(new MinerUPort.ParseJob("job-1", "file-1"));
        when(mineru.awaitResult(any(), anyInt(), any())).thenReturn(new MinerUPort.ParseResult(
                MARKDOWN, MD_SHA, "job-1", "file-1", "flash", "v1", 12L, "1-1", STRUCTURED, ST_SHA));
        lenient().when(objectStore.get(STRUCTURED_KEY)).thenReturn(STRUCTURED.getBytes(StandardCharsets.UTF_8));
        lenient().when(objectStore.get(PARSE_KEY)).thenReturn(MARKDOWN.getBytes(StandardCharsets.UTF_8));
        when(chunker.chunkArtifact(DOC, VERSION, STRUCTURED, ChunkingPort.DEFAULT_MAX_CHARS))
                .thenReturn(List.of(new ChunkingPort.ChunkDraft(0, "c-0", "chunk-0",
                        CanonicalJson.sha256("chunk-0"), 7, null, null)));
        when(embeddingGateway.provider()).thenReturn("dashscope");
        when(embeddingGateway.model()).thenReturn("emb-1");
        when(embeddingGateway.dimension()).thenReturn(1536);
        when(embeddingGateway.embedBatchWithUsage(List.of("chunk-0"))).thenReturn(
                new com.nageoffer.ai.ragent.ingest.EmbeddingGateway.EmbeddingResult(
                        List.of(List.of(0.1f, 0.2f)), "pr-1", Map.of("total_tokens", 5)));
        when(providerBoundary.enter(any())).thenReturn(new RevocationGuard.Operation(revocationGuard, "permit-1", "op-1"));
        when(usageLedger.startCall(anyString(), anyString(), anyInt(), anyString(), anyString(), anyString(),
                anyString(), any())).thenReturn("call-1");
        when(usageLedger.unresolved(anyString(), anyString(), anyString())).thenReturn(false);
        when(documents.updateVersionStateFenced(anyString(), anyString(), anyString(), anyLong(), anyString(),
                any(), any(), any(), any())).thenReturn(true);
        when(documents.countChunks(TENANT, VERSION, "STAGING")).thenReturn(1L);
        when(documents.publishVersionFenced(anyString(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(true);
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }

    @Test
    void happyPathParsesChunksEmbedsAndPublishes() {
        stubCommon();
        stubProviderPorts();
        when(tasks.findTask(TENANT, RUN)).thenReturn(Optional.of(task(metadata(null))));

        RunExecutor.Outcome outcome = executor().execute(execution("{\"pipelineId\":\"pipe-1\",\"docId\":\"doc-1\","
                + "\"kbId\":\"kb-1\",\"uploadId\":\"upl-1\"}"));

        assertEquals("SUCCEEDED", outcome.status());
        verify(mineru).submit(any(), anyString(), anyString());
        verify(chunker).chunkArtifact(DOC, VERSION, STRUCTURED, ChunkingPort.DEFAULT_MAX_CHARS);
        verify(embeddingGateway).embedBatchWithUsage(List.of("chunk-0"));
        verify(documents).insertStagingChunk(anyString(), anyString(), anyString(), anyInt(), anyString(),
                anyString(), anyString(), anyString(), anyInt(), any(), any(), any(), anyString());
        verify(documents).publishVersionFenced(anyString(), anyString(), anyString(), anyString(), anyLong());
        verify(tasks).markTerminal(TENANT, RUN, "completed", 1, null);
    }

    @Test
    void cancelRequestedStopsBeforeAnyProviderCall() {
        stubCommon();
        when(tasks.findTask(TENANT, RUN)).thenReturn(Optional.of(task(metadata(null))));
        when(guard.isCancelRequested()).thenReturn(true);

        RunExecutor.Outcome outcome = executor().execute(execution("{\"pipelineId\":\"pipe-1\",\"docId\":\"doc-1\","
                + "\"kbId\":\"kb-1\",\"uploadId\":\"upl-1\"}"));

        assertEquals("CANCELLED", outcome.status());
        verify(mineru, never()).submit(any(), anyString(), anyString());
        verify(embeddingGateway, never()).embedBatchWithUsage(any());
        verify(documents, never()).publishVersionFenced(anyString(), anyString(), anyString(), anyString(), anyLong());
        verify(tasks).markTerminal(TENANT, RUN, "cancelled", null, null);
    }

    @Test
    void retryReusesPredecessorAndNeverReparses() {
        stubCommon();
        stubProviderPorts();
        when(tasks.findTask(TENANT, RUN)).thenReturn(Optional.of(task(metadata(PARENT))));
        Map<String, Object> parseRef = Map.of("objectKey", PARSE_KEY, "hash", MD_SHA,
                "structuredObjectKey", STRUCTURED_KEY, "structuredHash", ST_SHA);
        when(ledger.latestCompletedStep(TENANT, PARENT, "n1-parse")).thenReturn(Optional.of(
                new RunLedgerDao.StepRow("n1-parse", 1, 1, "parse", "COMPLETED",
                        toJson(parseRef), MD_SHA, null, 0L, Instant.now(), Instant.now())));

        RunExecutor.Outcome outcome = executor().execute(execution("{\"pipelineId\":\"pipe-1\",\"docId\":\"doc-1\","
                + "\"kbId\":\"kb-1\",\"uploadId\":\"upl-1\",\"retryNodeId\":\"n3\"}"));

        assertEquals("SUCCEEDED", outcome.status());
        verify(mineru, never()).submit(any(), anyString(), anyString());
        verify(embeddingGateway, times(1)).embedBatchWithUsage(List.of("chunk-0"));
        verify(documents).publishVersionFenced(anyString(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void retryRejectsPredecessorArtifactHashMismatch() {
        stubCommon();
        when(tasks.findTask(TENANT, RUN)).thenReturn(Optional.of(task(metadata(PARENT))));
        when(objectStore.get(PARSE_KEY)).thenReturn(MARKDOWN.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> tampered = Map.of("objectKey", PARSE_KEY, "hash", "deadbeef",
                "structuredObjectKey", STRUCTURED_KEY, "structuredHash", ST_SHA);
        when(ledger.latestCompletedStep(TENANT, PARENT, "n1-parse")).thenReturn(Optional.of(
                new RunLedgerDao.StepRow("n1-parse", 1, 1, "parse", "COMPLETED",
                        toJson(tampered), "deadbeef", null, 0L, Instant.now(), Instant.now())));

        RunApiException ex = assertThrows(RunApiException.class, () -> executor().execute(
                execution("{\"pipelineId\":\"pipe-1\",\"docId\":\"doc-1\",\"kbId\":\"kb-1\",\"uploadId\":\"upl-1\","
                        + "\"retryNodeId\":\"n3\"}")));
        assertEquals(RunErrorCode.RECONCILIATION_REQUIRED, ex.errorCode());
        verify(mineru, never()).submit(any(), anyString(), anyString());
        verify(documents, never()).publishVersionFenced(anyString(), anyString(), anyString(), anyString(), anyLong());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
