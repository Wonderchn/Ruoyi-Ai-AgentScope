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
import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.ingestion.runtime.IngestionTaskRuntimeController;
import com.nageoffer.ai.ragent.runtime.P2FaultInjector;
import com.nageoffer.ai.ragent.runtime.P2RuntimeProperties;
import com.nageoffer.ai.ragent.runtime.RunAccessService;
import com.nageoffer.ai.ragent.runtime.RunAdmissionService;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.RunEventAppender;
import com.nageoffer.ai.ragent.runtime.RunLifecycleService;
import com.nageoffer.ai.ragent.runtime.config.EngineModelAuthority;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.dto.AdmissionRequest;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import com.nageoffer.ai.ragent.runtime.usage.UsageLedgerService;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ruoyi.ai.api.runtime.DocumentPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R17 F06:2 retry R1 离线复现：**cancelled 父 run 的 retry 受理 100% 套死在内层 500**。
 *
 * <p>真机现象（两跑同形）：retry 返回客户端 503，内层无归因日志。源码链已证：
 * {@code IngestionRunContract.onAdmitted} 重试分支在**同一 (tenant,doc,upload) 上再插
 * 一条 version**，撞冻结唯一键 {@code uk_ai_document_version_upload}（V7:2573，V1–V38
 * 无任何放宽）→ {@link DuplicateKeyException} → {@code RunAdmissionService.admit} 兜底
 * 分支（findByIdempotency 空）→ {@code RunApiException(INTERNAL_ERROR,
 * "admission conflict without persisted run")} → 内层 500 信封 → 网关 abnormal 分支
 * 丢弃正文收敛为 503。
 *
 * <p>本类把真实链钉在一起：真 {@code RunAdmissionService.admit} + 真
 * {@code IngestionRunContract}（接线同 {@code RagChatAdmissionHistoryWiringTest}）；
 * 约束替身按真库行为建模（第二次 insertVersion 直接被拒）；服务端其余依赖为 mock。
 *
 * <p>判据分工：
 * <ol>
 *   <li>{@link #retryAdmissionReusesParentVersionWithoutASecondInsert}（红先行）：
 *       重试受理必须复用父 upload 的既有 version、不得再插第二条；</li>
 *   <li>{@link #duplicateKeyInsideAdmissionSurfacesAsTheObservedInternalError}：
 *       钉死受理事务内约束违例 → 观测到的 INTERNAL_ERROR 文案映射（真凶文本载体）；</li>
 *   <li>{@link #retryErrorSurfacesAsTheObservedFiveHundredEnvelope}：钉死该异常经
 *       {@code IngestionTaskRuntimeController.retry} 落 HTTP 500 信封（msg 带真凶文本、
 *       data.errorCode=INTERNAL_ERROR）——与真机网关日志里被丢弃的正文同形。</li>
 * </ol>
 */
@Tag("dev")
class IngestionRetryR1ReproductionTest {

    private static final String TENANT = "t1";
    private static final String MEMBER = "platform:t1:1001";
    private static final String USER = "1001";
    private static final String DOC = "doc-1";
    private static final String KB = "kb-1";
    private static final String UPLOAD = "upl-1";
    private static final String PIPE = "pipe-1";
    private static final String PARENT = "r-parent";
    private static final String PARENT_VERSION = "ver-parent";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final EngineModelAuthority.PublishedModel PUBLISHED = new EngineModelAuthority.PublishedModel(
            TENANT, "rev-1", 1L, "deepseek", "deepseek-chat", "cat-v1", "params-1", "cred-ref-1",
            "op-1", Instant.EPOCH);

    private RunLedgerDao dao;
    private RunEventAppender events;
    private IngestionPipelineReader pipelines;
    private IngestionTaskRepository tasks;
    private DocumentPort documents;
    private UsageLedgerService usage;
    private RunAdmissionService admission;
    private IngestionRunContract contract;
    private ExecutionPrincipal principal;

    private static void inject(Object target, String name, Class<?> type, Object value) throws Exception {
        for (var field : target.getClass().getDeclaredFields()) {
            if (field.getName().equals(name) && field.getType() == type) {
                field.setAccessible(true);
                field.set(target, value);
                return;
            }
        }
        throw new IllegalStateException("field " + name + " not found on " + target.getClass().getSimpleName());
    }

    @BeforeEach
    void setUp() throws Exception {
        dao = mock(RunLedgerDao.class);
        events = mock(RunEventAppender.class);
        when(events.toJson(any())).thenAnswer(invocation -> JSON.writeValueAsString(invocation.getArgument(0)));
        P2RuntimeProperties properties = new P2RuntimeProperties();
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        @SuppressWarnings("unchecked")
        ObjectProvider<P2FaultInjector> faultInjector = mock(ObjectProvider.class);
        when(faultInjector.getIfAvailable()).thenReturn(null);
        admission = new RunAdmissionService(dao, events, properties, txManager, faultInjector);
        RunAccessService access = mock(RunAccessService.class);
        when(access.capture(any(), any())).thenReturn("[]");
        AiResourceAuthorizationService resources = mock(AiResourceAuthorizationService.class);
        when(access.resources()).thenReturn(resources);
        when(resources.currentOwnerDept(any(), anyString())).thenReturn("dept-1");
        inject(admission, "access", RunAccessService.class, access);
        admission.configureModelAuthority(action -> PUBLISHED);

        pipelines = mock(IngestionPipelineReader.class);
        tasks = mock(IngestionTaskRepository.class);
        documents = mock(DocumentPort.class);
        usage = mock(UsageLedgerService.class);
        contract = new IngestionRunContract(pipelines, tasks, documents, usage, JSON);
        admission.configureActions(List.of(contract));

        when(dao.findByIdempotency(anyString(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(dao.lockTenantBudget(anyString(), anyLong())).thenReturn(1000L);
        when(dao.sumReserved(anyString())).thenReturn(0L);
        when(dao.findRun(anyString(), anyString())).thenAnswer(invocation -> {
            String runId = invocation.getArgument(1);
            return Optional.of(PARENT.equals(runId) ? parentRun() : queuedRun(runId));
        });

        when(pipelines.load(TENANT, PIPE)).thenReturn(Optional.of(snapshot()));
        when(pipelines.structureError(any())).thenReturn(null);
        when(documents.findUpload(TENANT, UPLOAD)).thenReturn(Optional.of(upload()));
        when(documents.findVersion(TENANT, PARENT_VERSION)).thenReturn(Optional.of(versionRow()));
        when(documents.findVersionByUpload(TENANT, DOC, UPLOAD)).thenReturn(Optional.of(versionRow()));
        when(usage.usageOf(TENANT, PARENT)).thenReturn(new UsageLedgerService.RunUsage(0, 0, 0));
        when(tasks.findTask(TENANT, PARENT)).thenReturn(Optional.of(parentTask()));
        when(tasks.findNode(TENANT, PARENT, "n2")).thenReturn(Optional.of(node("n2", "failed")));
        when(tasks.findNode(TENANT, PARENT, "n1")).thenReturn(Optional.of(node("n1", "success")));

        principal = new ExecutionPrincipal(TENANT, USER, MEMBER, 1, 1, Set.of("run.submit"),
                "jti", "platform", 1, 9999999999L);
    }

    // ------------------------------------------------------------------ ① 红先行：复用父 version

    @Test
    void retryAdmissionReusesParentVersionWithoutASecondInsert() throws Exception {
        // 约束替身按真库行为：同一 (tenant,doc,upload) 的第二条 version 被冻结唯一键拒绝
        // （V7 uk_ai_document_version_upload）——现行实现正是在这里 100% 必败。
        doThrow(new DuplicateKeyException("uk_ai_document_version_upload"))
                .when(documents).insertVersion(anyString(), anyString(), anyString(), anyString(), anyString());

        RunAdmissionService.AdmissionResult result = admission.admit(principal, "idem-retry-r1", retryRequest());

        assertEquals("QUEUED", result.status());
        verify(documents, never()).insertVersion(anyString(), anyString(), anyString(), anyString(), anyString());
        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(tasks).createTask(eq(TENANT), anyString(), eq(PIPE), eq("file"), anyString(), anyString(),
                eq("pending"), metadata.capture(), eq(USER));
        JsonNode meta = JSON.readTree(metadata.getValue());
        assertEquals(PARENT_VERSION, meta.path("versionId").asText(),
                "重试新 run 必须复用父 upload 的既有 version：实际元数据 " + metadata.getValue());
        assertEquals(PARENT, meta.path("retryOf").asText());
    }

    // ------------------------------------------------------------------ ② 约束违例 → 观测到的 INTERNAL_ERROR 映射

    @Test
    void duplicateKeyInsideAdmissionSurfacesAsTheObservedInternalError() {
        // 合成受理事务内的约束违例（真 insertVersion 的撞键行为由 ① 覆盖；真实
        // createTask 是 ON CONFLICT DO NOTHING，这里只借它的调用点触发兜底分支）：
        // RunAdmissionService.admit 捕获 DuplicateKeyException 后 findByIdempotency 为空
        // ⇒ RunApiException(INTERNAL_ERROR, "admission conflict without persisted run")。
        doThrow(new DuplicateKeyException("uk_ai_document_version_upload"))
                .when(tasks).createTask(anyString(), anyString(), anyString(), anyString(), anyString(),
                        anyString(), anyString(), anyString(), anyString());

        RunApiException thrown = assertThrows(RunApiException.class,
                () -> admission.admit(principal, "idem-retry-r1", retryRequest()));

        assertEquals(RunErrorCode.INTERNAL_ERROR, thrown.errorCode());
        assertEquals("admission conflict without persisted run", thrown.getMessage());
    }

    // ------------------------------------------------------------------ ③ 内层 500 信封（网关日志里被丢弃的正文）

    @Test
    void retryErrorSurfacesAsTheObservedFiveHundredEnvelope() throws Exception {
        RunAdmissionService admissionMock = mock(RunAdmissionService.class);
        when(admissionMock.admit(any(), anyString(), any())).thenThrow(
                new RunApiException(RunErrorCode.INTERNAL_ERROR, "admission conflict without persisted run"));
        IngestionTaskView view = mock(IngestionTaskView.class);
        when(view.parentMetadata(any(), eq(PARENT))).thenReturn(new LinkedHashMap<>(metadata()));

        IngestionTaskRuntimeController controller = new IngestionTaskRuntimeController(
                admissionMock, mock(RunLifecycleService.class), view, mock(DeliveryPermits.class),
                JSON, mock(ObjectProvider.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        PrincipalContext.set(principal);
        try {
            String body = mvc.perform(post("/internal/ai/v1/ingestion/tasks/{runId}/retry", PARENT)
                            .header("Idempotency-Key", "idem-retry-r1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"retryNodeId\":\"n2\"}"))
                    .andExpect(status().isInternalServerError())
                    .andReturn().getResponse().getContentAsString();

            assertTrue(body.contains("\"code\":500"), "内层必须是 500 信封：actual=" + body);
            assertTrue(body.contains("admission conflict without persisted run"),
                    "正文必须带真凶 msg（网关 abnormal 分支丢弃的就是它）：actual=" + body);
            assertTrue(body.contains("INTERNAL_ERROR"), "符号错误码缺失：actual=" + body);
        } finally {
            PrincipalContext.clear();
        }
    }

    // ------------------------------------------------------------------ 夹具

    private AdmissionRequest retryRequest() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("pipelineId", PIPE);
        input.put("docId", DOC);
        input.put("kbId", KB);
        input.put("uploadId", UPLOAD);
        input.put("retryNodeId", "n2");
        return new AdmissionRequest(1, IngestionRunContract.ACTION, null, null, JSON.valueToTree(input),
                List.of(new AdmissionRequest.ResourceRef("knowledge_base", KB),
                        new AdmissionRequest.ResourceRef("document", DOC)),
                null, null, PARENT);
    }

    private static Map<String, Object> metadata() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("runId", PARENT);
        meta.put("pipelineId", PIPE);
        meta.put("docId", DOC);
        meta.put("kbId", KB);
        meta.put("uploadId", UPLOAD);
        meta.put("versionId", PARENT_VERSION);
        meta.put("pipelineHash", "h");
        meta.put("sourceSha256", "sha-1");
        return meta;
    }

    private static IngestionPipelineReader.PipelineSnapshot snapshot() {
        IngestionPipelineReader.NodeSnapshot parser =
                new IngestionPipelineReader.NodeSnapshot("n1", "parser", true, "n2", null, null);
        IngestionPipelineReader.NodeSnapshot chunker =
                new IngestionPipelineReader.NodeSnapshot("n2", "chunker", true, "n3", null, null);
        IngestionPipelineReader.NodeSnapshot indexer =
                new IngestionPipelineReader.NodeSnapshot("n3", "indexer", true, null, null, null);
        return new IngestionPipelineReader.PipelineSnapshot(PIPE, "pipe", List.of(parser, chunker, indexer), "h");
    }

    private static DocumentPort.UploadRow upload() {
        return new DocumentPort.UploadRow(UPLOAD, DOC, KB, MEMBER, "report.pdf", "application/pdf",
                10L, "sha-1", "tenants/t1/upl-1.pdf", "STORED", Instant.EPOCH);
    }

    private static DocumentPort.VersionRow versionRow() {
        return new DocumentPort.VersionRow(PARENT_VERSION, DOC, UPLOAD, PARENT, "STAGING", null, 0, null, null, null);
    }

    private static IngestionTaskRepository.TaskRow parentTask() throws Exception {
        return new IngestionTaskRepository.TaskRow(PARENT, PIPE, "file", "tenants/t1/upl-1.pdf", "report.pdf",
                "failed", 0, null, null, JSON.writeValueAsString(metadata()),
                Instant.EPOCH, Instant.EPOCH, USER, Instant.EPOCH, Instant.EPOCH);
    }

    private static IngestionTaskRepository.NodeRow node(String nodeId, String state) {
        return new IngestionTaskRepository.NodeRow("n-" + nodeId, PARENT, PIPE, nodeId, "parser", 0, state,
                0L, null, null, null, Instant.EPOCH, Instant.EPOCH);
    }

    private static RunRecord parentRun() {
        return new RunRecord(TENANT, PARENT, MEMBER, USER, "ingestion.run", "CANCELLED", "hash-p", "idem-p",
                "{}", "{}", IngestionRunContract.EXECUTION_VERSION, 1, 1, "[]", 2L, 3L, 1, 0L,
                null, null, null, null, null, null, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
    }

    private static RunRecord queuedRun(String runId) {
        return new RunRecord(TENANT, runId, MEMBER, USER, "ingestion.run", "QUEUED", "hash", "idem",
                "{}", "{}", IngestionRunContract.EXECUTION_VERSION, 1, 1, "[]", 1L, 2L, 0, 0L,
                null, null, null, null, null, null, Instant.EPOCH, null, null);
    }
}
