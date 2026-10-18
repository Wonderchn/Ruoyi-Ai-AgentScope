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

package com.nageoffer.ai.ragent.agent.orchestration;

import com.nageoffer.ai.ragent.agent.runtime.P3Properties;
import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.runtime.CanonicalJson;
import com.nageoffer.ai.ragent.runtime.P2FaultInjector;
import com.nageoffer.ai.ragent.runtime.P2RuntimeProperties;
import com.nageoffer.ai.ragent.runtime.RunAccessService;
import com.nageoffer.ai.ragent.runtime.RunAdmissionService;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.RunEventAppender;
import com.nageoffer.ai.ragent.runtime.RuntimeActionContract;
import com.nageoffer.ai.ragent.runtime.config.ConfigAuthorityUnavailable;
import com.nageoffer.ai.ragent.runtime.config.EngineModelAuthority;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.dto.AdmissionRequest;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F14:0/1/3 · 受理契约（§2.1/§2.2）。
 *
 * <p>覆盖 R1（输入/名册/引用零写入）、R2（缺 agent.execute 403）、R3（action/agentVersion 400）、
 * R4（发布权威缺 503 零写入）、R5（受理恰 1 行 ai_agent_run）。
 */
@Tag("dev")
class SupervisorRunContractTest {

    private static final String VALID_INPUT = "{\"text\":\"question\",\"agents\":["
            + "{\"name\":\"analyst\",\"instruction\":\"analyze\"},"
            + "{\"name\":\"critic\",\"instruction\":\"critique\"}]}";
    private static final String EMPTY_REFS = "[]";

    private static AdmissionRequest request(String input, String action, String version, String refs) {
        try {
            return CanonicalJson.strictMapper().readValue(
                    "{\"schemaVersion\":1,\"action\":\"" + action + "\",\"agentVersion\":\"" + version
                            + "\",\"input\":" + input + ",\"resourceRefs\":" + refs + "}",
                    AdmissionRequest.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static AdmissionRequest validRequest() {
        return request(VALID_INPUT, "agent.supervisor", "supervisor-v1", EMPTY_REFS);
    }

    private static ExecutionPrincipal principal() {
        return new ExecutionPrincipal("t1", "1001", "platform:t1:1001", 1, 1,
                Set.of("run.submit", "agent.execute"), "jti", "platform", 1, 9999999999L);
    }

    private static RunRecord run(String runId) {
        return new RunRecord("t1", runId, "platform:t1:1001", "1001", "agent.supervisor", "QUEUED",
                "hash", "idem", VALID_INPUT, "{}", "supervisor-v1", 1, 1, "[]", 1L, 2L, 0, 0L,
                null, null, null, null, null, null, Instant.EPOCH, null, null);
    }

    private static EngineModelAuthority.PublishedModel published() {
        return new EngineModelAuthority.PublishedModel("t1", "rev-1", 1L, "deepseek", "deepseek-flash",
                "cat-v1", "params-1", "cred-ref-1", "op-1", Instant.EPOCH);
    }

    // ------------------------------------------------------------ R1/R3：形状白名单

    @Test
    @DisplayName("合法形状放行：action=agent.supervisor、agentVersion=supervisor-v1、resourceRefs 为空")
    void validShapeIsAccepted() {
        SupervisorRunContract.validateShape(validRequest());
    }

    @Test
    @DisplayName("R3：action/agentVersion 漂移、resourceRefs 非空、未知字段一律 400")
    void wrongActionVersionOrRefsAreRejected() {
        assertBad(request(VALID_INPUT, "agent.run", "supervisor-v1", EMPTY_REFS));
        assertBad(request(VALID_INPUT, "agent.supervisor", "supervisor-v2", EMPTY_REFS));
        assertBad(request(VALID_INPUT, "agent.supervisor", "core-v1", EMPTY_REFS));
        assertBad(request(VALID_INPUT, "agent.supervisor", "supervisor-v1",
                "[{\"type\":\"knowledge_base\",\"id\":\"kb1\"}]"));
        assertBad(request("{\"text\":\"q\",\"agents\":[{\"name\":\"analyst\",\"instruction\":\"a\"},"
                        + "{\"name\":\"critic\",\"instruction\":\"c\"}],\"model\":\"x\"}",
                "agent.supervisor", "supervisor-v1", EMPTY_REFS));
    }

    private static void assertBad(AdmissionRequest request) {
        RunApiException exception = assertThrows(RunApiException.class,
                () -> SupervisorRunContract.validateShape(request));
        assertEquals(RunErrorCode.BAD_REQUEST, exception.errorCode());
    }

    // ------------------------------------------------------------ R2：agent.execute

    @Test
    @DisplayName("R2：缺 agent.execute 授权 → 403，且校验先于任何写入")
    void missingAgentExecuteIsForbidden() {
        RunAccessService access = mock(RunAccessService.class);
        AiResourceAuthorizationService resources = mock(AiResourceAuthorizationService.class);
        when(access.resources()).thenReturn(resources);
        doThrow(new RunApiException(RunErrorCode.FORBIDDEN))
                .when(resources).requireFunction(any(), eq("agent.execute"), eq("run:new"));
        SupervisorRunContract contract = new SupervisorRunContract(new P3Properties(),
                mock(SupervisorLedger.class), mock(RunLedgerDao.class), access);

        RunApiException denied = assertThrows(RunApiException.class,
                () -> contract.validate(principal(), validRequest()));
        assertEquals(RunErrorCode.FORBIDDEN, denied.errorCode());
    }

    // ------------------------------------------------------------ R4：发布权威

    @Test
    @DisplayName("R4：发布权威缺位 → 拒绝受理（503 语义）且账本零写入")
    void admissionWithoutAuthorityWritesNothing() {
        RunLedgerDao dao = mock(RunLedgerDao.class);
        RunEventAppender events = mock(RunEventAppender.class);
        RunAccessService access = mock(RunAccessService.class);
        when(access.capture(any(), any())).thenReturn(EMPTY_REFS);
        AiResourceAuthorizationService resources = mock(AiResourceAuthorizationService.class);
        when(access.resources()).thenReturn(resources);
        SupervisorLedger ledger = mock(SupervisorLedger.class);
        SupervisorRunContract contract = new SupervisorRunContract(new P3Properties(), ledger, dao, access);
        RunAdmissionService service = admissionService(dao, events, access);
        service.configureActions(List.of((RuntimeActionContract) contract));

        RunApiException refused = assertThrows(RunApiException.class,
                () -> service.admit(principal(), "idem-noauth", validRequest()));

        assertEquals(RunErrorCode.AUTHORIZATION_UNAVAILABLE, refused.errorCode());
        verify(dao, never()).insertRun(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(),
                anyString(), any(), any());
        verify(dao, never()).registerRun(anyString(), anyString(), anyString(), anyString());
        verify(dao, never()).insertReservation(anyString(), anyString(), anyString(), anyString(), anyLong());
        verify(events, never()).appendWithinAdmission(anyString(), anyString(), anyString(), any(), anyBoolean());
        verifyNoInteractions(ledger);
    }

    @Test
    @DisplayName("onAdmitted 真实模式：权威缺位拒绝写绑定行；权威在场写权威模型")
    void onAdmittedBindsPublishedModelOrRefuses() {
        P3Properties properties = new P3Properties();
        RunLedgerDao runs = mock(RunLedgerDao.class);
        when(runs.findRun("t1", "r1")).thenReturn(Optional.of(run("r1")));
        SupervisorLedger ledger = mock(SupervisorLedger.class);
        SupervisorRunContract contract = new SupervisorRunContract(properties, ledger, runs,
                mock(RunAccessService.class));
        AdmissionRequest request = validRequest();

        assertThrows(ConfigAuthorityUnavailable.class,
                () -> contract.onAdmitted(principal(), "r1", request));
        verifyNoInteractions(ledger);

        contract.configureModelAuthority(action -> {
            assertEquals("agent.supervisor", action);
            return published();
        });
        contract.onAdmitted(principal(), "r1", request);
        verify(ledger).admitted(eq(run("r1")), eq(request), eq("deepseek-flash"));
    }

    @Test
    @DisplayName("onAdmitted 合成模式：写合成绑定模型（与执行器 bound 同判据）")
    void onAdmittedSyntheticBinding() {
        P3Properties properties = new P3Properties();
        properties.setSyntheticModel(true);
        RunLedgerDao runs = mock(RunLedgerDao.class);
        when(runs.findRun("t1", "r1")).thenReturn(Optional.of(run("r1")));
        SupervisorLedger ledger = mock(SupervisorLedger.class);
        SupervisorRunContract contract = new SupervisorRunContract(properties, ledger, runs,
                mock(RunAccessService.class));

        contract.onAdmitted(principal(), "r1", validRequest());

        verify(ledger).admitted(eq(run("r1")), any(AdmissionRequest.class), eq("synthetic-echo"));
    }

    // ------------------------------------------------------------ R5：恰 1 行 ai_agent_run

    @Test
    @DisplayName("R5：受理成功时恰好写一次绑定行（同一父 run，executionVersion=supervisor-v1）")
    void admissionWritesExactlyOneBindingRow() {
        RunLedgerDao dao = mock(RunLedgerDao.class);
        RunEventAppender events = mock(RunEventAppender.class);
        RunAccessService access = mock(RunAccessService.class);
        when(access.capture(any(), any())).thenReturn(EMPTY_REFS);
        AiResourceAuthorizationService resources = mock(AiResourceAuthorizationService.class);
        when(access.resources()).thenReturn(resources);
        when(resources.currentOwnerDept(any(), anyString())).thenReturn("dept-1");
        when(dao.findByIdempotency(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(dao.lockTenantBudget(anyString(), anyLong())).thenReturn(1000L);
        when(dao.sumReserved(anyString())).thenReturn(0L);
        when(dao.findRun(anyString(), anyString())).thenAnswer(invocation ->
                Optional.of(run(invocation.getArgument(1))));
        P3Properties properties = new P3Properties();
        properties.setSyntheticModel(true);
        SupervisorLedger ledger = mock(SupervisorLedger.class);
        SupervisorRunContract contract = new SupervisorRunContract(properties, ledger, dao, access);
        RunAdmissionService service = admissionService(dao, events, access);
        service.configureActions(List.of((RuntimeActionContract) contract));
        service.configureModelAuthority(action -> published());

        RunAdmissionService.AdmissionResult result = service.admit(principal(), "idem-ok", validRequest());

        assertEquals("QUEUED", result.status());
        verify(ledger, times(1)).admitted(any(RunRecord.class), eq(validRequest()), eq("synthetic-echo"));
        verify(dao, times(1)).insertRun(anyString(), anyString(), eq("platform:t1:1001"), eq("1001"),
                eq("agent.supervisor"), eq("idem-ok"), anyString(), isNull(), isNull(),
                eq("supervisor-v1"), eq(1), eq(1), eq(EMPTY_REFS), isNull(), any());
        verify(dao, times(1)).registerRun(anyString(), anyString(), anyString(), eq("dept-1"));
    }

    private static RunAdmissionService admissionService(RunLedgerDao dao, RunEventAppender events,
                                                        RunAccessService access) {
        P2RuntimeProperties properties = new P2RuntimeProperties();
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        @SuppressWarnings("unchecked")
        ObjectProvider<P2FaultInjector> faultInjector = mock(ObjectProvider.class);
        when(faultInjector.getIfAvailable()).thenReturn(null);
        RunAdmissionService service = new RunAdmissionService(dao, events, properties, transactionManager,
                faultInjector);
        for (var field : RunAdmissionService.class.getDeclaredFields()) {
            if (field.getType() == RunAccessService.class) {
                field.setAccessible(true);
                try {
                    field.set(service, access);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
                return service;
            }
        }
        throw new IllegalStateException("RunAccessService field not found on RunAdmissionService");
    }
}
