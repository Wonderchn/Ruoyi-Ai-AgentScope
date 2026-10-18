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

package com.nageoffer.ai.ragent.flow.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.runtime.CanonicalJson;
import com.nageoffer.ai.ragent.runtime.P2RuntimeProperties;
import com.nageoffer.ai.ragent.runtime.RunAccessService;
import com.nageoffer.ai.ragent.runtime.RunAdmissionService;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.RunEventAppender;
import com.nageoffer.ai.ragent.runtime.config.EngineModelAuthority;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.dto.AdmissionRequest;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F13:2 受理契约：§3.1 可执行谓词/输入/模板/模型覆盖、§3.2 快照写入、
 * §3.3 1 runtime + 3 runtime_node 同事务、D5 在线功能判定、D7 retry 复用父快照。
 */
@Tag("dev")
class FlowRunContractTest {

    private static final String TENANT = "T1";
    private static final String USER = "2101";
    private static final String MEMBER = "platform:T1:2101";
    private static final String WORKFLOW = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String START = "0123456789abcdef0123456789abcdef";
    private static final String LLM = "1123456789abcdef0123456789abcdef";
    private static final String END = "2123456789abcdef0123456789abcdef";
    private static final String BOUND_MODEL = "deepseek-flash";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FlowRuntimeRepository repository = mock(FlowRuntimeRepository.class);
    private final RunAccessService access = mock(RunAccessService.class);
    private final AiResourceAuthorizationService resources = mock(AiResourceAuthorizationService.class);
    private final RunLedgerDao runs = mock(RunLedgerDao.class);
    private final ObjectProvider<EngineModelAuthority> authorityProvider = mock(ObjectProvider.class);
    private final EngineModelAuthority authority = action -> new EngineModelAuthority.PublishedModel(
            TENANT, "rev-1", 1L, "deepseek", BOUND_MODEL, "catalog-1", "params-1", "cred-ref", "op", Instant.now());

    private final FlowRunContract contract = new FlowRunContract(repository, access, authorityProvider, runs);

    FlowRunContractTest() {
        when(access.resources()).thenReturn(resources);
        when(authorityProvider.getIfAvailable()).thenReturn(authority);
    }

    // ------------------------------------------------------------------ onAdmitted

    @Test
    @DisplayName("onAdmitted：runtime.uuid = runId.substring(2)（32 字符，适配 varchar(32)）+ 每节点一行")
    void onAdmittedWritesRuntimeAndThreeNodesWithVarchar32Uuid() {
        when(repository.findExecutableGraph(TENANT, WORKFLOW, 2101L))
                .thenReturn(Optional.of(graphSource()));
        when(repository.insertRuntime(eq(TENANT), anyString(), eq(2101L), eq(41L), anyString())).thenReturn(7L);

        String runId = "r-" + UUID.randomUUID().toString().replace("-", "");
        contract.onAdmitted(principal(), runId, request(null, "{\"name\":\"Ada\"}"));

        ArgumentCaptor<String> runtimeUuid = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> inputJson = ArgumentCaptor.forClass(String.class);
        verify(repository).insertRuntime(eq(TENANT), runtimeUuid.capture(), eq(2101L), eq(41L), inputJson.capture());
        assertThat(runtimeUuid.getValue())
                .as("ai_flow_runtime.uuid 是 varchar(32)；runId 是 r- + 32hex = 34")
                .isEqualTo(runId.substring(2))
                .hasSize(32);
        JsonNode persisted = read(inputJson.getValue());
        assertThat(persisted.has("payload")).isTrue();
        assertThat(persisted.path("payload").path("inputs").path("name").asText()).isEqualTo("Ada");
        assertThat(persisted.path("graph").path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(persisted.path("graphHash").asText()).matches("[0-9a-f]{64}");

        verify(repository, times(3)).insertRuntimeNode(eq(TENANT), eq(7L), anyLong(), eq(2101L));
        ArgumentCaptor<Long> nodeIds = ArgumentCaptor.forClass(Long.class);
        verify(repository, times(3)).insertRuntimeNode(eq(TENANT), eq(7L), nodeIds.capture(), eq(2101L));
        assertThat(nodeIds.getAllValues()).containsExactly(11L, 12L, 13L);
    }

    @Test
    @DisplayName("onAdmitted 与受理同事务：先写 runtime/节点，后 commit（同一 TransactionTemplate 回调内）")
    void onAdmittedRunsInsideTheAdmissionTransaction() {
        when(repository.findExecutableGraph(TENANT, WORKFLOW, 2101L))
                .thenReturn(Optional.of(graphSource()));
        when(repository.insertRuntime(eq(TENANT), anyString(), eq(2101L), eq(41L), anyString())).thenReturn(7L);

        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        RunLedgerDao ledger = mock(RunLedgerDao.class);
        when(ledger.lockTenantBudget(TENANT, 1000L)).thenReturn(1000L);
        when(ledger.sumReserved(TENANT)).thenReturn(0L);
        when(ledger.allocateSeq(eq(TENANT), anyString())).thenReturn(1L);
        when(ledger.findRun(eq(TENANT), anyString())).thenReturn(Optional.of(queuedRun("r-" + UUID.randomUUID()
                .toString().replace("-", ""))));
        RunAccessService admissionAccess = mock(RunAccessService.class);
        when(admissionAccess.resources()).thenReturn(resources);
        when(admissionAccess.capture(any(), any())).thenReturn("[]");
        when(admissionAccess.resources().currentOwnerDept(any(), eq("run.submit"))).thenReturn(null);

        RunAdmissionService admission = new RunAdmissionService(ledger,
                new RunEventAppender(ledger, MAPPER), new P2RuntimeProperties(), transactionManager,
                emptyProvider());
        admission.configureActions(List.of(contract));
        admission.configureModelAuthority(authority);
        ReflectionTestUtils.setField(admission, "access", admissionAccess);

        RunAdmissionService.AdmissionResult result = admission.admit(principal(), "idem-1",
                request(null, "{\"name\":\"Ada\"}"));

        assertThat(result.runId()).startsWith("r-");
        InOrder order = inOrder(transactionManager, repository);
        order.verify(transactionManager).getTransaction(any());
        order.verify(repository).insertRuntime(eq(TENANT), eq(result.runId().substring(2)), eq(2101L), eq(41L),
                anyString());
        order.verify(repository, times(3)).insertRuntimeNode(eq(TENANT), eq(7L), anyLong(), eq(2101L));
        order.verify(transactionManager).commit(any());
    }

    // ------------------------------------------------------------------ validate

    @Test
    @DisplayName("可执行谓词 0 行 -> 404 RESOURCE_NOT_FOUND_OR_FORBIDDEN（不区分越权/不存在）")
    void validateRefusesNonExecutableWorkflow() {
        when(repository.findExecutableGraph(TENANT, WORKFLOW, 2101L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> contract.validate(principal(), request(null, "{\"name\":\"Ada\"}")))
                .isInstanceOf(RunApiException.class)
                .extracting(e -> ((RunApiException) e).errorCode())
                .isEqualTo(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
    }

    @Test
    @DisplayName("D5：validate 期在线复核 flow.run（资源 flow:<uuid>）")
    void validateRequiresFlowRunFunction() {
        when(repository.findExecutableGraph(TENANT, WORKFLOW, 2101L))
                .thenReturn(Optional.of(graphSource()));

        contract.validate(principal(), request(null, "{\"name\":\"Ada\"}"));

        verify(resources).requireFunction(any(), eq("flow.run"), eq("flow:" + WORKFLOW));
    }

    @Test
    @DisplayName("输入变量缺失 -> 400 flow input missing: <name>")
    void validateRefusesMissingInputVariable() {
        when(repository.findExecutableGraph(TENANT, WORKFLOW, 2101L))
                .thenReturn(Optional.of(graphSource("hello {question}")));

        assertThatThrownBy(() -> contract.validate(principal(), request(null, "{\"name\":\"Ada\"}")))
                .isInstanceOf(RunApiException.class)
                .hasMessage("flow input missing: question")
                .extracting(e -> ((RunApiException) e).errorCode())
                .isEqualTo(RunErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("model_name 覆盖 -> 400 flow model override is not allowed")
    void validateRefusesModelOverride() {
        when(repository.findExecutableGraph(TENANT, WORKFLOW, 2101L))
                .thenReturn(Optional.of(graphSourceWithModel("gpt-4o")));

        assertThatThrownBy(() -> contract.validate(principal(), request(null, "{\"name\":\"Ada\"}")))
                .isInstanceOf(RunApiException.class)
                .hasMessage("flow model override is not allowed");
    }

    @Test
    @DisplayName("预算 maxTokens > 8192 -> 400 BAD_REQUEST（同一受理服务的既有预算门）")
    void admissionRefusesBudgetBeyondContract() {
        when(repository.findExecutableGraph(TENANT, WORKFLOW, 2101L))
                .thenReturn(Optional.of(graphSource()));
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        RunLedgerDao ledger = mock(RunLedgerDao.class);
        RunAccessService admissionAccess = mock(RunAccessService.class);
        when(admissionAccess.resources()).thenReturn(resources);
        when(admissionAccess.capture(any(), any())).thenReturn("[]");
        RunAdmissionService admission = new RunAdmissionService(ledger,
                new RunEventAppender(ledger, MAPPER), new P2RuntimeProperties(), transactionManager,
                emptyProvider());
        admission.configureActions(List.of(contract));
        admission.configureModelAuthority(authority);
        ReflectionTestUtils.setField(admission, "access", admissionAccess);

        AdmissionRequest tooLarge = new AdmissionRequest(1, "flow.run", null, null,
                input("{\"name\":\"Ada\"}"), List.of(), null, read("{\"maxTokens\":9000}"), null);

        assertThatThrownBy(() -> admission.admit(principal(), "idem-budget", tooLarge))
                .isInstanceOf(RunApiException.class)
                .extracting(e -> ((RunApiException) e).errorCode())
                .isEqualTo(RunErrorCode.BAD_REQUEST);
        verify(ledger, never()).insertRun(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), anyString(), any(Integer.class), any(Integer.class),
                anyString(), any(), any());
    }

    // ------------------------------------------------------------------ D7 retry

    @Test
    @DisplayName("D7：retry 复用父 runtime.input.graph，不重读当前图；父 run 必须 flow.run 且终态")
    void retryReusesParentSnapshotWithoutReadingCurrentGraph() {
        String parentRunId = "r-" + UUID.randomUUID().toString().replace("-", "");
        RunRecord parent = terminalRun(parentRunId);
        when(runs.findRun(TENANT, parentRunId)).thenReturn(Optional.of(parent));
        when(repository.findParentGraph(TENANT, parentRunId.substring(2)))
                .thenReturn(Optional.of(new FlowRuntimeRepository.ParentGraph(41L, parentRuntimeInput())));
        when(repository.findNodeId(TENANT, 41L, START)).thenReturn(OptionalLong.of(101L));
        when(repository.findNodeId(TENANT, 41L, LLM)).thenReturn(OptionalLong.of(102L));
        when(repository.findNodeId(TENANT, 41L, END)).thenReturn(OptionalLong.of(103L));
        when(repository.insertRuntime(eq(TENANT), anyString(), eq(2101L), eq(41L), anyString())).thenReturn(7L);

        AdmissionRequest retry = request(parentRunId, "{\"name\":\"Ada\"}");
        contract.validate(principal(), retry);
        verify(repository, never()).findExecutableGraph(anyString(), anyString(), anyLong());

        String runId = "r-" + UUID.randomUUID().toString().replace("-", "");
        contract.onAdmitted(principal(), runId, retry);

        verify(repository, never()).findExecutableGraph(anyString(), anyString(), anyLong());
        ArgumentCaptor<String> inputJson = ArgumentCaptor.forClass(String.class);
        verify(repository).insertRuntime(eq(TENANT), eq(runId.substring(2)), eq(2101L), eq(41L),
                inputJson.capture());
        JsonNode persisted = read(inputJson.getValue());
        assertThat(persisted.path("graphHash").asText()).isEqualTo(parentGraphHash());
        assertThat(persisted.path("graph").path("nodes")).hasSize(3);
    }

    @Test
    @DisplayName("D7：父 run 非 flow.run / 非终态一律拒绝，且不读当前图")
    void retryRefusesWrongParent() {
        String parentRunId = "r-" + UUID.randomUUID().toString().replace("-", "");
        when(runs.findRun(TENANT, parentRunId)).thenReturn(Optional.of(new RunRecord(
                TENANT, parentRunId, MEMBER, USER, "rag.chat", "SUCCEEDED", "h", "k", "{}", "{}", "p2-v1",
                3, 5, "[]", 0L, 1L, 1, 1L, null, null, null, null, null, null, Instant.now(), null, null)));

        assertThatThrownBy(() -> contract.validate(principal(), request(parentRunId, "{\"name\":\"Ada\"}")))
                .isInstanceOf(RunApiException.class)
                .extracting(e -> ((RunApiException) e).errorCode())
                .isEqualTo(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        verify(repository, never()).findExecutableGraph(anyString(), anyString(), anyLong());
    }

    // ------------------------------------------------------------------ helpers

    private ExecutionPrincipal principal() {
        long now = Instant.now().getEpochSecond();
        return new ExecutionPrincipal(TENANT, USER, MEMBER, 3, 5, Set.of("flow.run"), "jti-1", "platform",
                now, now + 300);
    }

    private AdmissionRequest request(String retryOf, String inputsJson) {
        return new AdmissionRequest(1, "flow.run", null, null, input(inputsJson), List.of(), null, null, retryOf);
    }

    private JsonNode input(String inputsJson) {
        JsonNode inputs = read(inputsJson);
        var node = MAPPER.createObjectNode();
        node.put("flowUuid", WORKFLOW);
        node.set("inputs", inputs);
        return node;
    }

    private FlowRuntimeRepository.GraphSource graphSource() {
        return graphSource("hello {name}");
    }

    private FlowRuntimeRepository.GraphSource graphSource(String prompt) {
        return graphSourceWith("{\"type\":\"LLM\",\"prompt\":\"" + prompt + "\",\"model_name\":\""
                + BOUND_MODEL + "\"}");
    }

    private FlowRuntimeRepository.GraphSource graphSourceWithModel(String model) {
        return graphSourceWith("{\"type\":\"LLM\",\"prompt\":\"hello {name}\",\"model_name\":\""
                + model + "\"}");
    }

    private FlowRuntimeRepository.GraphSource graphSourceWith(String llmConfig) {
        return new FlowRuntimeRepository.GraphSource(41L, WORKFLOW,
                List.of(new FlowRuntimeRepository.NodeSource(11L, START, "start", 0L, "{}",
                                "{\"type\":\"START\"}"),
                        new FlowRuntimeRepository.NodeSource(12L, LLM, "llm", 0L, "{}", llmConfig),
                        new FlowRuntimeRepository.NodeSource(13L, END, "end", 0L, "{}",
                                "{\"type\":\"END\",\"result\":\"final {name}\"}")),
                List.of(new FlowRuntimeRepository.EdgeSource(START, LLM),
                        new FlowRuntimeRepository.EdgeSource(LLM, END)),
                Map.of());
    }

    private String parentRuntimeInput() {
        FlowGraphSnapshot snapshot = FlowGraphSnapshot.compile(graphSource(), BOUND_MODEL);
        var wrapper = MAPPER.createObjectNode();
        wrapper.set("payload", input("{\"name\":\"Ada\"}"));
        wrapper.set("graph", snapshot.toJson());
        wrapper.put("graphHash", snapshot.hash());
        return wrapper.toString();
    }

    private String parentGraphHash() {
        FlowGraphSnapshot snapshot = FlowGraphSnapshot.compile(graphSource(), BOUND_MODEL);
        return snapshot.hash();
    }

    private RunRecord terminalRun(String runId) {
        return new RunRecord(TENANT, runId, MEMBER, USER, "flow.run", "SUCCEEDED", "h", "k",
                "{\"flowUuid\":\"" + WORKFLOW + "\",\"inputs\":{\"name\":\"Ada\"}}", "{}",
                FlowRunContract.EXECUTION_VERSION, 3, 5, "[]", 3L, 5L, 1, 1L, null, null,
                "{\"runtimeUuid\":\"x\"}", null, null, null, Instant.now(), Instant.now(), Instant.now());
    }

    private RunRecord queuedRun(String runId) {
        return new RunRecord(TENANT, runId, MEMBER, USER, "flow.run", "QUEUED", "h", "k",
                "{\"flowUuid\":\"" + WORKFLOW + "\",\"inputs\":{\"name\":\"Ada\"}}", "{}",
                FlowRunContract.EXECUTION_VERSION, 3, 5, "[]", 0L, 1L, 0, 0L, null, null, null, null,
                null, null, Instant.now(), null, null);
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
