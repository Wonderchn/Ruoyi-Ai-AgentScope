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

package com.nageoffer.ai.ragent.ingestion.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.dto.AdmissionRequest;
import com.nageoffer.ai.ragent.runtime.usage.UsageLedgerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ai.api.runtime.DocumentPort;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F06:1/2-A Stage B：{@code ingestion.run} 受理契约的**红判据**（入参白名单、资源引用、
 * 源权威、管线结构、重试建立独立 version）。
 */
@ExtendWith(MockitoExtension.class)
@Tag("dev")
class IngestionRunContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TENANT = "acme";
    private static final String DOC = "doc-1";
    private static final String KB = "kb-1";
    private static final String UPLOAD = "upl-1";
    private static final String PIPE = "pipe-1";

    @Mock
    private IngestionPipelineReader pipelines;
    @Mock
    private IngestionTaskRepository tasks;
    @Mock
    private DocumentPort documents;
    @Mock
    private UsageLedgerService usage;

    private IngestionRunContract contract() {
        return new IngestionRunContract(pipelines, tasks, documents, usage, MAPPER);
    }

    private static ExecutionPrincipal principal() {
        return new ExecutionPrincipal(TENANT, "1", "platform:" + TENANT + ":1", 1, 1,
                Set.of("run.submit"), "jti-1", "platform", 0L, 0L);
    }

    private static AdmissionRequest request(Map<String, Object> input, String retryOf) {
        JsonNode node = MAPPER.valueToTree(input);
        List<AdmissionRequest.ResourceRef> refs = List.of(
                new AdmissionRequest.ResourceRef("knowledge_base", KB),
                new AdmissionRequest.ResourceRef("document", DOC));
        return new AdmissionRequest(1, IngestionRunContract.ACTION, null, null, node, refs, null, null, retryOf);
    }

    private static Map<String, Object> validInput() {
        return new java.util.LinkedHashMap<>(Map.of(
                "pipelineId", PIPE, "docId", DOC, "kbId", KB, "uploadId", UPLOAD));
    }

    private IngestionPipelineReader.PipelineSnapshot goodSnapshot() {
        IngestionPipelineReader.NodeSnapshot parser =
                new IngestionPipelineReader.NodeSnapshot("n1", "parser", true, "n2", null, null);
        IngestionPipelineReader.NodeSnapshot chunker =
                new IngestionPipelineReader.NodeSnapshot("n2", "chunker", true, "n3", null, null);
        IngestionPipelineReader.NodeSnapshot indexer =
                new IngestionPipelineReader.NodeSnapshot("n3", "indexer", true, null, null, null);
        return new IngestionPipelineReader.PipelineSnapshot(PIPE, "pipe", List.of(parser, chunker, indexer), "h");
    }

    private void stubGoodSnapshot() {
        when(pipelines.load(TENANT, PIPE)).thenReturn(Optional.of(goodSnapshot()));
        lenient().when(pipelines.structureError(any())).thenReturn(null);
    }

    private void stubSource() {
        lenient().when(documents.findDocument(TENANT, DOC)).thenReturn(Optional.of(
                new DocumentPort.DocumentRow(DOC, KB, "report.pdf", "member-1", null, null, Instant.now())));
        lenient().when(documents.findUpload(TENANT, UPLOAD)).thenReturn(Optional.of(
                new DocumentPort.UploadRow(UPLOAD, DOC, KB, "member-1", "report.pdf", "application/pdf",
                        10L, "sha-1", "tenants/acme/upl-1.pdf", "STORED", Instant.now())));
        lenient().when(documents.findVersionByUpload(TENANT, DOC, UPLOAD)).thenReturn(Optional.of(
                new DocumentPort.VersionRow("ver-1", DOC, UPLOAD, null, "STAGING", null, 0, null, null, null)));
    }

    @Test
    void rejectsUnknownInputField() {
        Map<String, Object> input = validInput();
        input.put("url", "http://evil.example/x");
        RunApiException ex = assertThrows(RunApiException.class,
                () -> contract().validate(principal(), request(input, null)));
        assertEquals(RunErrorCode.BAD_REQUEST, ex.errorCode());
    }

    @Test
    void rejectsMissingRequiredField() {
        Map<String, Object> input = validInput();
        input.remove("uploadId");
        RunApiException ex = assertThrows(RunApiException.class,
                () -> contract().validate(principal(), request(input, null)));
        assertEquals(RunErrorCode.BAD_REQUEST, ex.errorCode());
    }

    @Test
    void rejectsWrongResourceRefs() {
        JsonNode input = MAPPER.valueToTree(validInput());
        AdmissionRequest bad = new AdmissionRequest(1, IngestionRunContract.ACTION, null, null, input,
                List.of(new AdmissionRequest.ResourceRef("knowledge_base", "other-kb"),
                        new AdmissionRequest.ResourceRef("document", DOC)),
                null, null, null);
        RunApiException ex = assertThrows(RunApiException.class, () -> contract().validate(principal(), bad));
        assertEquals(RunErrorCode.BAD_REQUEST, ex.errorCode());
    }

    @Test
    void rejectsMissingPipeline() {
        when(pipelines.load(TENANT, PIPE)).thenReturn(Optional.empty());
        RunApiException ex = assertThrows(RunApiException.class,
                () -> contract().validate(principal(), request(validInput(), null)));
        assertEquals(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, ex.errorCode());
    }

    @Test
    void rejectsInvalidPipelineStructure() {
        when(pipelines.load(TENANT, PIPE)).thenReturn(Optional.of(goodSnapshot()));
        when(pipelines.structureError(any())).thenReturn("INGEST_NODE_REQUIRED_MISSING");
        RunApiException ex = assertThrows(RunApiException.class,
                () -> contract().validate(principal(), request(validInput(), null)));
        assertEquals(RunErrorCode.BAD_REQUEST, ex.errorCode());
    }

    @Test
    void rejectsCrossTenantOrStoredStateMismatch() {
        stubGoodSnapshot();
        when(documents.findDocument(TENANT, DOC)).thenReturn(Optional.of(
                new DocumentPort.DocumentRow(DOC, KB, "report.pdf", "member-1", null, null, Instant.now())));
        when(documents.findUpload(TENANT, UPLOAD)).thenReturn(Optional.of(
                new DocumentPort.UploadRow(UPLOAD, DOC, KB, "member-1", "report.pdf", "application/pdf",
                        10L, "sha-1", "tenants/acme/upl-1.pdf", "PENDING", Instant.now())));
        RunApiException ex = assertThrows(RunApiException.class,
                () -> contract().validate(principal(), request(validInput(), null)));
        assertEquals(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, ex.errorCode());
    }

    @Test
    void acceptsValidSourceRegistration() {
        stubGoodSnapshot();
        stubSource();
        contract().validate(principal(), request(validInput(), null));
    }

    @Test
    void onAdmittedUsesExistingVersionForFirstRun() {
        stubSource();
        when(pipelines.load(TENANT, PIPE)).thenReturn(Optional.of(goodSnapshot()));
        contract().onAdmitted(principal(), "r-new", request(validInput(), null));
        verify(documents, never()).insertVersion(anyString(), anyString(), anyString(), anyString(), anyString());
        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(tasks).createTask(anyString(), org.mockito.ArgumentMatchers.eq("r-new"), org.mockito.ArgumentMatchers.eq(PIPE),
                org.mockito.ArgumentMatchers.eq("file"), anyString(), org.mockito.ArgumentMatchers.eq("report.pdf"),
                org.mockito.ArgumentMatchers.eq("pending"), metadata.capture(), anyString());
        org.junit.jupiter.api.Assertions.assertTrue(metadata.getValue().contains("ver-1"));
    }

    @Test
    void onAdmittedCreatesIndependentVersionForRetry() {
        when(documents.findUpload(TENANT, UPLOAD)).thenReturn(Optional.of(
                new DocumentPort.UploadRow(UPLOAD, DOC, KB, "member-1", "report.pdf", "application/pdf",
                        10L, "sha-1", "tenants/acme/upl-1.pdf", "STORED", Instant.now())));
        when(pipelines.load(TENANT, PIPE)).thenReturn(Optional.of(goodSnapshot()));
        Map<String, Object> input = validInput();
        input.put("retryNodeId", "n2");
        contract().onAdmitted(principal(), "r-new", request(input, "r-old"));
        verify(documents).insertVersion(org.mockito.ArgumentMatchers.eq(TENANT), anyString(),
                org.mockito.ArgumentMatchers.eq(DOC), org.mockito.ArgumentMatchers.eq(UPLOAD),
                org.mockito.ArgumentMatchers.eq("r-new"));
    }

    @Test
    void retryRejectsPipelineSnapshotDrift() {
        stubGoodSnapshot();
        Map<String, Object> input = validInput();
        input.put("retryNodeId", "n2");
        when(tasks.findTask(TENANT, "r-old")).thenReturn(Optional.of(taskRow(metaJson("OLD-HASH"))));
        lenient().when(documents.findUpload(TENANT, UPLOAD)).thenReturn(Optional.of(
                new DocumentPort.UploadRow(UPLOAD, DOC, KB, "platform:acme:1", "f.pdf", "application/pdf",
                        10L, "sha-1", "tenants/acme/upl-1.pdf", "STORED", Instant.now())));

        RunApiException ex = assertThrows(RunApiException.class,
                () -> contract().validate(principal(), request(input, "r-old")));
        assertEquals(RunErrorCode.RUN_STATE_CONFLICT, ex.errorCode());
    }

    private String metaJson(String pipelineHash) {
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("runId", "r-old");
        meta.put("pipelineId", PIPE);
        meta.put("docId", DOC);
        meta.put("kbId", KB);
        meta.put("uploadId", UPLOAD);
        meta.put("versionId", "ver-old");
        meta.put("pipelineHash", pipelineHash);
        meta.put("sourceSha256", "sha-1");
        try {
            return MAPPER.writeValueAsString(meta);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static IngestionTaskRepository.TaskRow taskRow(String metadataJson) {
        return new IngestionTaskRepository.TaskRow("r-old", PIPE, "file", "tenants/acme/upl-1.pdf", "f.pdf",
                "failed", 0, null, null, metadataJson, null, null, "1", Instant.now(), Instant.now());
    }
}
