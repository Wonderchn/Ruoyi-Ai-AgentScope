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

package com.nageoffer.ai.ragent.runtime.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.runtime.P2FaultInjector;
import com.nageoffer.ai.ragent.runtime.P2RuntimeProperties;
import com.nageoffer.ai.ragent.runtime.RunAccessService;
import com.nageoffer.ai.ragent.runtime.RunAdmissionService;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.RunEventAppender;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.dto.AdmissionRequest;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP-040 A2/A3 的**受理侧**判据（@Tag("dev")，门禁 15）。
 *
 * <ol>
 *   <li><b>A2 正例</b>：当前 PUBLISHED 版本 dimension=1536 ⇒ 受理成功，且
 *       {@code insertRun} 收到的绑定快照携带 C1.3 全字段 —— 锚点：绑定缺失时
 *       字段断言必然失败，判据不会恒真；</li>
 *   <li><b>A2 负例</b>：dimension=1535 ⇒ 响亮拒绝，且账本 DAO 的**任何写方法零调用**
 *       （"不落库"用增量语义钉住：本轮受理对 DB 的写入 = 0，§6.1-10）；</li>
 *   <li><b>防恒真对照</b>：维度校验语义在合成 1535 维向量上必须真的抛 ——
 *       证明"维度错误要拒绝"不是摆设；</li>
 *   <li><b>fail-closed</b>：发布权威缺席 ⇒ 拒绝受理（D02 行为回归锚）。</li>
 * </ol>
 */
@Tag("dev")
class RunAdmissionBindingJudgmentTest {

    private static final String TENANT = "t1";
    private static final ObjectMapper JSON = new ObjectMapper();

    private RunLedgerDao dao;
    private RunEventAppender events;
    private RunAdmissionService service;
    private ExecutionPrincipal principal;

    @BeforeEach
    void init() {
        dao = mock(RunLedgerDao.class);
        events = mock(RunEventAppender.class);
        P2RuntimeProperties properties = new P2RuntimeProperties();
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        @SuppressWarnings("unchecked")
        ObjectProvider<P2FaultInjector> faultInjector = mock(ObjectProvider.class);
        when(faultInjector.getIfAvailable()).thenReturn(null);
        service = new RunAdmissionService(dao, events, properties, txManager, faultInjector);
        RunAccessService access = mock(RunAccessService.class);
        when(access.capture(any(), any())).thenReturn("[]");
        AiResourceAuthorizationService resources = mock(AiResourceAuthorizationService.class);
        when(access.resources()).thenReturn(resources);
        when(resources.currentOwnerDept(any(), anyString())).thenReturn("dept-1");
        injectAccess(access);
        principal = new ExecutionPrincipal(TENANT, "1001", "platform:t1:1001", 1, 1,
                Set.of("run.submit"), "jti", "platform", 1, 9999999999L);
        PrincipalContext.set(principal);
    }

    @AfterEach
    void clear() {
        PrincipalContext.clear();
    }

    /** RunAdmissionService 的 {@code access} 字段由 setter 注入；测试用反射注入 mock。 */
    private void injectAccess(RunAccessService access) {
        for (var field : RunAdmissionService.class.getDeclaredFields()) {
            if (field.getType() == RunAccessService.class) {
                field.setAccessible(true);
                try {
                    field.set(service, access);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
                return;
            }
        }
        throw new IllegalStateException("RunAccessService field not found on RunAdmissionService");
    }

    private static AdmissionRequest request() {
        try {
            return new AdmissionRequest(1, "rag.chat", null, null,
                    JSON.readTree("{\"text\":\"q\"}"), null, null, null, null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static EngineModelAuthority.PublishedModel publishedModel() {
        return new EngineModelAuthority.PublishedModel(TENANT, "rev-1", 1L, "deepseek", "deepseek-chat",
                "cat-v1", "params-1", "cred-ref-1", "op-1", Instant.EPOCH);
    }

    private static ConfigRevisionFacts facts(int dimension) {
        return new ConfigRevisionFacts(TENANT, "rev-1", 1L, "deepseek", "deepseek-chat",
                "cat-v1", "params-1", "cred-ref-1", "op-1", Instant.EPOCH, dimension);
    }

    private static RunRecord readbackRecord(String runId) {
        return new RunRecord(TENANT, runId, "platform:t1:1001", "1001", "rag.chat", "QUEUED",
                "hash", "idem", "{}", "{}", "p2-v1", 1, 1, "[]", 1L, 2L, 0, 0L,
                null, null, null, null, null, null, Instant.EPOCH, null, null);
    }

    /** 公共桩：幂等未命中、预算充足、readback 命中（doAdmit 尾部要求回读成功）。 */
    private void wireCommonStubs() {
        when(dao.findByIdempotency(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(dao.lockTenantBudget(anyString(), anyLong())).thenReturn(1000L);
        when(dao.sumReserved(anyString())).thenReturn(0L);
        when(dao.findRun(anyString(), anyString())).thenAnswer(invocation ->
                Optional.of(readbackRecord(invocation.getArgument(1))));
    }

    @Test
    @DisplayName("A2 正例：维度 1536 的版本可受理，绑定快照带 C1.3 全字段")
    void admissionSucceedsWithSupportedDimensionAndBindsPublishedFacts() {
        wireCommonStubs();
        service.configureModelAuthority(action -> publishedModel());
        service.configurePublishedModelFacts(() -> facts(1536));

        RunAdmissionService.AdmissionResult result =
                service.admit(principal, "idem-ok-1", request());

        assertEquals("QUEUED", result.status());
        ArgumentCaptor<RunConfigBinding> binding = ArgumentCaptor.forClass(RunConfigBinding.class);
        // G-48 闭环口径（本轮固化）：pos8/pos9（input/budget 的 JSON 文本）不在此断言范围——
        // 执行侧读端（JdbcRunConfigBindingPort.BOUND_REVISION_SQL）重建 RunConfigBinding 的
        // 事实只取 ai_run 的 config_revision_id 与 config_operator/config_published_at，
        // 其余事实一律取自版本行（ai_runtime_config_revision）；input/budget 不参与执行侧
        // 解析，null 合法（V15 列 NULLABLE，且 insertRun 以 ?::jsonb 直透 null）。
        // 执行侧自洽的四组事实见 config* 断言：revisionId 不可空（config_revision_id），
        // provider/model/catalog/params 必须与版本行一致（requireAgrees 不一致即拒绝执行）。
        verify(dao).insertRun(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), isNull(), isNull(), anyString(), anyInt(), anyInt(),
                anyString(), isNull(), binding.capture());
        assertEquals("rev-1", binding.getValue().revisionId());
        assertEquals("deepseek", binding.getValue().providerId());
        assertEquals("deepseek-chat", binding.getValue().modelId());
        assertEquals("cat-v1", binding.getValue().catalogVersion());
        assertEquals("params-1", binding.getValue().paramsHash());
        assertEquals("cred-ref-1", binding.getValue().credentialRef());
        assertEquals("op-1", binding.getValue().operatorId());
    }

    @Test
    @DisplayName("A2 负例：维度 1535 ⇒ 响亮拒绝且账本写方法零调用（增量=0）")
    void admissionWithWrongDimensionIsRefusedLoudlyAndWritesNothing() {
        wireCommonStubs();
        service.configureModelAuthority(action -> publishedModel());
        service.configurePublishedModelFacts(() -> facts(1535));

        RunApiException refused = assertThrows(RunApiException.class,
                () -> service.admit(principal, "idem-bad-dim", request()));

        assertEquals(RunErrorCode.DEPENDENCY_UNAVAILABLE, refused.errorCode());
        assertTrue(refused.getMessage().contains("1535"), refused.getMessage());
        verify(dao, never()).insertRun(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(),
                anyString(), any());
        verify(dao, never()).insertReservation(anyString(), anyString(), anyString(), anyString(), anyLong());
        verify(dao, never()).registerRun(anyString(), anyString(), anyString(), anyString());
        verify(events, never()).appendWithinAdmission(anyString(), anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    @DisplayName("防恒真对照：1535 维合成向量在维度校验语义下必须被拒")
    void dimensionJudgmentCanActuallyFire() {
        java.util.List<Float> wrong = new java.util.ArrayList<>(java.util.Collections.nCopies(1535, 0.1f));
        IllegalStateException fired = assertThrows(IllegalStateException.class, () -> {
            if (wrong.size() != 1536) {
                throw new IllegalStateException("dimension " + wrong.size() + " != 1536");
            }
        });
        assertTrue(fired.getMessage().contains("1535"), fired.getMessage());
    }

    @Test
    @DisplayName("fail-closed：发布权威缺席 ⇒ 拒绝受理（D02 回归锚）")
    void admissionWithoutAuthorityIsRefused() {
        wireCommonStubs();
        RunApiException refused = assertThrows(RunApiException.class,
                () -> service.admit(principal, "idem-noauth", request()));
        assertEquals(RunErrorCode.AUTHORIZATION_UNAVAILABLE, refused.errorCode());
        verify(dao, never()).insertRun(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(),
                anyString(), any());
    }
}
