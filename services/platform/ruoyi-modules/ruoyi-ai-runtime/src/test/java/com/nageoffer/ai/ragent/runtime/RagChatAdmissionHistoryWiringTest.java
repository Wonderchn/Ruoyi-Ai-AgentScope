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

package com.nageoffer.ai.ragent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.ingest.EmbeddingGateway;
import com.nageoffer.ai.ragent.runtime.config.EngineModelAuthority;
import com.nageoffer.ai.ragent.runtime.config.RuntimeModelGatewayPort;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.dto.AdmissionRequest;
import com.nageoffer.ai.ragent.runtime.exec.ProviderCallBoundary;
import com.nageoffer.ai.ragent.runtime.exec.RagChatExecutor;
import com.nageoffer.ai.ragent.runtime.exec.RunExecution;
import com.nageoffer.ai.ragent.runtime.exec.RunExecutionGuard;
import com.nageoffer.ai.ragent.runtime.exec.RunExecutor;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import com.nageoffer.ai.ragent.runtime.port.ConversationHistoryPort;
import com.nageoffer.ai.ragent.runtime.port.ConversationTurnMeta;
import com.nageoffer.ai.ragent.runtime.usage.EgressPolicy;
import com.nageoffer.ai.ragent.runtime.usage.PlatformFactsClient;
import com.nageoffer.ai.ragent.runtime.usage.UsageLedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ruoyi.ai.api.runtime.DocumentPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * D7a（s2-chathist-a1）修复判据：**shipped 受理载荷（顶层 conversationId）→ 会话历史端口**。
 *
 * <p><b>缺陷实态</b>（真机 AC2 W1.4：ai_message 全表 0 行）：workbench 与验收探针把
 * conversationId 放在受理体<b>顶层</b>（{@code AdmissionRequest.conversationId} 显式字段；
 * 工作台 {@code run-chat.ts} 的 shipped 形态），而执行器只从 run 行的 input 子树读它
 * （{@code RagChatExecutor.persistConversationHistory} 读 {@code input.path("conversationId")}）。
 * 受理期持久化的是 input 子树本身 ⇒ 顶层字段在 run 行里根本不存在 ⇒ 执行器恒读空
 * ⇒ <b>静默跳过历史写</b>，连"端口未接线"的响亮失败卫都不可达。
 *
 * <p><b>本类怎么钉。</b>把两个真实组件串成一条链：真实 {@code RunAdmissionService.admit}
 * （捕获账本 DAO 实际收到的 input 载荷）→ 把该载荷原样装进 {@link RunRecord} → 真实
 * {@code RagChatExecutor.execute} → 断言会话历史端口收到整轮（conversationId/问题/回答）。
 * 替身边界与既有判据一致：账本 DAO/事件是 mock（同 {@code RunAdmissionBindingJudgmentTest}），
 * 历史端口是 mock（适配器落库由 ai-rag 侧 {@code ConversationHistoryAdapterTest} 与真库 E2E 钉住），
 * 检索为空走执行器真实的"证据不足"分支（同真机 E2E 的夹具口径）——本类判的是
 * "顶层字段能不能到达写历史的那一步"，不是模型效果。
 */
@Tag("dev")
class RagChatAdmissionHistoryWiringTest {

    private static final String TENANT = "t1";
    private static final String MEMBER = "platform:t1:1001";
    private static final String USER = "1001";
    private static final String QUESTION = "顶层载荷问答";
    private static final String CONV_TOP = "conv-shipped-top";
    private static final String CONV_NESTED = "conv-legacy-nested";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final EngineModelAuthority.PublishedModel PUBLISHED = new EngineModelAuthority.PublishedModel(
            TENANT, "rev-1", 1L, "deepseek", "deepseek-chat", "cat-v1", "params-1", "cred-ref-1",
            "op-1", Instant.EPOCH);

    // ---------- 受理侧 ----------
    private RunLedgerDao dao;
    private RunEventAppender events;
    private RunAdmissionService admission;
    private ExecutionPrincipal principal;

    // ---------- 执行侧 ----------
    private ConversationHistoryPort historyPort;
    private RagChatExecutor executor;

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

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
        // ---------------- 受理侧（同 RunAdmissionBindingJudgmentTest 的替身口径） ----------------
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
        RunAccessService admissionAccess = mock(RunAccessService.class);
        when(admissionAccess.capture(any(), any())).thenReturn("[]");
        AiResourceAuthorizationService admissionResources = mock(AiResourceAuthorizationService.class);
        when(admissionAccess.resources()).thenReturn(admissionResources);
        when(admissionResources.currentOwnerDept(any(), anyString())).thenReturn("dept-1");
        inject(admission, "access", RunAccessService.class, admissionAccess);
        admission.configureModelAuthority(action -> PUBLISHED);
        when(dao.findByIdempotency(anyString(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(dao.lockTenantBudget(anyString(), anyLong())).thenReturn(1000L);
        when(dao.sumReserved(anyString())).thenReturn(0L);
        when(dao.findRun(anyString(), anyString())).thenAnswer(invocation -> Optional.of(readback(invocation.getArgument(1))));

        principal = new ExecutionPrincipal(TENANT, USER, MEMBER, 1, 1, Set.of("run.submit"),
                "jti", "platform", 1, 9999999999L);

        // ---------------- 执行侧（同 RagChatExecutorConversationMetaTest 的替身口径） ----------------
        DocumentPort documentPort = mock(DocumentPort.class);
        EmbeddingGateway embeddingGateway = mock(EmbeddingGateway.class);
        AiResourceAuthorizationService authorization = mock(AiResourceAuthorizationService.class);
        historyPort = mock(ConversationHistoryPort.class);
        PlatformFactsClient facts = mock(PlatformFactsClient.class);
        when(facts.currentFacts(anyString(), anyString(), anyString()))
                .thenReturn(new PlatformFactsClient.CurrentFacts(true, 1));
        when(authorization.currentAclVersion(anyString())).thenReturn(1);
        when(authorization.check(any(), anyString(), anyString()))
                .thenReturn(ResourceAuthorizationService.Verdict.GRANT);
        when(embeddingGateway.model()).thenReturn("emb-x");
        when(embeddingGateway.provider()).thenReturn("dashscope");
        when(embeddingGateway.embedBatchWithUsage(any()))
                .thenReturn(new EmbeddingGateway.EmbeddingResult(List.of(List.of(0.1f)), "emb-req", Map.of()));
        when(documentPort.matchesEmbeddingModel(any(), any(), anyString())).thenReturn(true);
        // 与真机 E2E 同口径：检索为空 ⇒ 执行器走真实的"证据不足"分支并照常落历史
        when(documentPort.searchPublished(any(), any(), any(), anyInt(), anyDouble())).thenReturn(List.of());

        executor = new RagChatExecutor(documentPort, embeddingGateway, mock(RuntimeModelGatewayPort.class),
                mock(EgressPolicy.class), mock(UsageLedgerService.class),
                provider(facts), provider(authorization), provider((P2FaultInjector) null),
                mock(JdbcTemplate.class), new ObjectMapper());
        RunAccessService execAccess = mock(RunAccessService.class);
        when(execAccess.current(any(), any())).thenReturn(new ExecutionPrincipal(
                TENANT, USER, MEMBER, 1, 1, Set.of("kb.read"), "jti", "platform",
                Instant.now().getEpochSecond(), 9999999999L));
        inject(executor, "access", RunAccessService.class, execAccess);
        ProviderCallBoundary boundary = mock(ProviderCallBoundary.class);
        when(boundary.enter(any()))
                .thenReturn(new RevocationGuard.Operation(mock(RevocationGuard.class), "permit-1", "op-1"));
        inject(executor, "providerBoundary", ProviderCallBoundary.class, boundary);
        inject(executor, "conversationHistory", ConversationHistoryPort.class, historyPort);
    }

    private static RunRecord readback(String runId) {
        return new RunRecord(TENANT, runId, MEMBER, USER, "rag.chat", "QUEUED",
                "hash", "idem", "{}", "{}", "p2-v1", 1, 1, "[]", 1L, 2L, 0, 0L,
                null, null, null, null, null, null, Instant.EPOCH, null, null);
    }

    /** shipped 受理体 → 真实受理 → 返回账本 DAO 实际收到的 input 载荷 JSON。 */
    private String admitAndCaptureStoredInput(AdmissionRequest request, String idempotencyKey) {
        RunAdmissionService.AdmissionResult result = admission.admit(principal, idempotencyKey, request);
        assertEquals("QUEUED", result.status());
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(dao).insertRun(eq(TENANT), anyString(), eq(MEMBER), eq(USER), eq("rag.chat"),
                eq(idempotencyKey), anyString(), stored.capture(), isNull(), eq("p2-v1"), eq(1), eq(1),
                anyString(), isNull(), any());
        return stored.getValue();
    }

    /** 把 run 行载荷原样装进 RunRecord，跑真实执行器。 */
    private RunExecutor.Outcome executeStoredRun(String storedInputJson) throws Exception {
        return executeStoredRun(storedInputJson, null);
    }

    /** Same as above; when {@code completedStep} is non-null the named step is already COMPLETED (replay/idempotency judgment). */
    private RunExecutor.Outcome executeStoredRun(String storedInputJson, RunLedgerDao.StepRow completedStep)
            throws Exception {
        RunRecord run = new RunRecord(TENANT, "run-1", MEMBER, USER, "rag.chat", "RUNNING", "hash", "key",
                storedInputJson, "{\"maxTokens\":2000}", "p2-v1", 1, 1,
                "[{\"type\":\"knowledge_base\",\"id\":\"kb-1\"}]", 3L, 1L, 1, 7L,
                "worker-1", null, null, null, null, null, Instant.EPOCH, Instant.EPOCH, null);
        RunExecutionGuard guard = mock(RunExecutionGuard.class);
        when(guard.isCancelRequested()).thenReturn(false);
        when(guard.findStep(anyString())).thenReturn(Optional.empty());
        if (completedStep != null) {
            when(guard.findStep(eq(completedStep.stepId()))).thenReturn(Optional.of(completedStep));
        }
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get()).when(guard).commitAtomic(any());
        return executor.execute(new RunExecution(run, guard));
    }

    private static AdmissionRequest request(String inputJson, String conversationId) throws Exception {
        return new AdmissionRequest(1, "rag.chat", conversationId, null,
                JSON.readTree(inputJson),
                List.of(new AdmissionRequest.ResourceRef("knowledge_base", "kb-1")),
                null, null, null);
    }

    private static String answerOf(RunExecutor.Outcome outcome) {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) outcome.terminalResult();
        return String.valueOf(result.get("answer"));
    }

    @Test
    @DisplayName("D7a：shipped 顶层载荷（conversationId 在受理体顶层）→ 整轮进会话历史端口")
    void shippedTopLevelConversationIdReachesTheHistoryPort() throws Exception {
        String stored = admitAndCaptureStoredInput(
                request("{\"text\":\"" + QUESTION + "\"}", CONV_TOP), "idem-d7a-top");
        assertEquals(CONV_TOP, JSON.readTree(stored).path("conversationId").asText(),
                "受理持久化的 run 载荷必须带上顶层 conversationId：执行器（与 RunWorker 一样）只读 run 行");

        RunExecutor.Outcome outcome = executeStoredRun(stored);
        assertEquals("SUCCEEDED", outcome.status());
        ArgumentCaptor<JsonNode> attachments = ArgumentCaptor.forClass(JsonNode.class);
        verify(historyPort).appendAttachedTurn(eq(TENANT), eq(MEMBER), eq(USER), eq(CONV_TOP),
                eq(QUESTION), eq(answerOf(outcome)), any(ConversationTurnMeta.class), attachments.capture());
        assertTrue(attachments.getValue().isMissingNode() || attachments.getValue().isNull(),
                "无附件载荷必须原样透传：不伪造 attachments 数组");
    }

    @Test
    @DisplayName("兼容：旧的嵌套形态（conversationId 在 input 子树里）仍按原样落历史")
    void legacyNestedConversationIdStillReachesTheHistoryPort() throws Exception {
        String stored = admitAndCaptureStoredInput(
                request("{\"text\":\"" + QUESTION + "\",\"conversationId\":\"" + CONV_NESTED + "\"}", null),
                "idem-legacy-nested");
        assertEquals(CONV_NESTED, JSON.readTree(stored).path("conversationId").asText());

        RunExecutor.Outcome outcome = executeStoredRun(stored);
        assertEquals("SUCCEEDED", outcome.status());
        ArgumentCaptor<JsonNode> attachments = ArgumentCaptor.forClass(JsonNode.class);
        verify(historyPort).appendAttachedTurn(eq(TENANT), eq(MEMBER), eq(USER), eq(CONV_NESTED),
                eq(QUESTION), eq(answerOf(outcome)), any(ConversationTurnMeta.class), attachments.capture());
        assertTrue(attachments.getValue().isMissingNode() || attachments.getValue().isNull(),
                "无附件载荷必须原样透传：不伪造 attachments 数组");
    }

    @Test
    @DisplayName("两处都给时：顶层显式字段优先（不允许嵌套旧形态静默压过显式字段）")
    void topLevelConversationIdWinsOverNestedWhenBothPresent() throws Exception {
        String stored = admitAndCaptureStoredInput(
                request("{\"text\":\"" + QUESTION + "\",\"conversationId\":\"" + CONV_NESTED + "\"}", CONV_TOP),
                "idem-both");
        assertEquals(CONV_TOP, JSON.readTree(stored).path("conversationId").asText());

        RunExecutor.Outcome outcome = executeStoredRun(stored);
        assertEquals("SUCCEEDED", outcome.status());
        ArgumentCaptor<JsonNode> attachments = ArgumentCaptor.forClass(JsonNode.class);
        verify(historyPort).appendAttachedTurn(eq(TENANT), eq(MEMBER), eq(USER), eq(CONV_TOP),
                eq(QUESTION), eq(answerOf(outcome)), any(ConversationTurnMeta.class), attachments.capture());
        assertTrue(attachments.getValue().isMissingNode() || attachments.getValue().isNull(),
                "无附件载荷必须原样透传：不伪造 attachments 数组");
    }

    @Test
    @DisplayName("无 conversationId 的载荷：不伪造字段、跳过历史写（唯一合法跳过）")
    void payloadWithoutConversationIdSkipsTheHistoryWriteWithoutFabricatingOne() throws Exception {
        String stored = admitAndCaptureStoredInput(
                request("{\"text\":\"" + QUESTION + "\"}", null), "idem-no-conv");
        assertTrue(JSON.readTree(stored).path("conversationId").isMissingNode(),
                "载荷没有 conversationId 时不得伪造一个");

        RunExecutor.Outcome outcome = executeStoredRun(stored);
        assertEquals("SUCCEEDED", outcome.status());
        verifyNoInteractions(historyPort);
    }

    @Test
    @DisplayName("fenced checkpoint 幂等：conversation-history 已 COMPLETED → 重放不再追写历史（整轮只落一次）")
    void completedHistoryCheckpointSkipsTheAppendOnReplay() throws Exception {
        String stored = admitAndCaptureStoredInput(
                request("{\"text\":\"" + QUESTION + "\"}", CONV_TOP), "idem-history-checkpoint");
        RunLedgerDao.StepRow completed = new RunLedgerDao.StepRow("conversation-history", 1, 1,
                "conversation-history", "COMPLETED", "{\"conversationId\":\"" + CONV_TOP + "\"}", null,
                "{\"calls\":0}", 7L, Instant.EPOCH, Instant.EPOCH);

        RunExecutor.Outcome outcome = executeStoredRun(stored, completed);

        assertEquals("SUCCEEDED", outcome.status());
        verifyNoInteractions(historyPort);
    }
}
