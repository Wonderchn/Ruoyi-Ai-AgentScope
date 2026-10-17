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
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.runtime.config.EngineModelAuthority;
import com.nageoffer.ai.ragent.runtime.config.RunConfigBinding;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.dto.AdmissionRequest;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import com.nageoffer.ai.ragent.runtime.port.ChatAttachmentPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * F04 brief7：文本模型零 provider —— runtime 受理侧"缺能力 = 零写入"判据。
 *
 * <p><b>顺序即契约</b>：附件能力校验（{@code ChatAttachmentPort.validateAdmission}）在
 * {@code doAdmit} 的 0b 步执行，**先于**预算锁、run 插入、注册与 outbox 事件。拒绝时
 * 本轮受理对账本的写入必须为 0（与 WP-040 A2 维度门同口径，用"调用零次"钉增量=0）：
 * 没有 run、没有预占、没有受理事件，就不存在"文本模型的图片 run 被受理后由执行期兜底"
 * 的窗口。端口缺席（旧装配）同样在第 0b 步响亮拒绝，不静默丢附件继续受理。</p>
 *
 * <p><b>防恒真对照</b>：同一请求在不拒绝的端口下必须真的走到账本写入——否则 never()
 * 断言可能只是因为请求本身不可受理而空转。</p>
 */
@Tag("dev")
class AttachmentAdmissionZeroRunWritesTest {

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

    /** RunAdmissionService 的 {@code access} 字段无 setter 注入；测试用反射注入 mock。 */
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

    private static AdmissionRequest requestWithAttachment() {
        try {
            return new AdmissionRequest(1, "rag.chat", "conv-1", null,
                    JSON.readTree("{\"text\":\"q\",\"attachments\":[{\"id\":\"att-0123456789abcdef0123456789abcdef\","
                            + "\"version\":1}]}"),
                    null, null, null, null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static EngineModelAuthority.PublishedModel publishedModel() {
        return new EngineModelAuthority.PublishedModel(TENANT, "rev-1", 1L, "deepseek", "deepseek-chat",
                "cat-v1", "params-1", "cred-ref-1", "op-1", Instant.EPOCH);
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

    /** 零写入判据：预算锁/run/注册/预占/outbox/回读全部不可达。 */
    private void verifyNoLedgerWritesOrEvents() {
        verify(dao, never()).lockTenantBudget(anyString(), anyLong());
        verify(dao, never()).sumReserved(anyString());
        verify(dao, never()).insertRun(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(),
                anyString(), anyString(), any(RunConfigBinding.class));
        verify(dao, never()).registerRun(anyString(), anyString(), anyString(), anyString());
        verify(dao, never()).insertReservation(anyString(), anyString(), anyString(), anyString(), anyLong());
        verify(dao, never()).findRun(anyString(), anyString());
        verify(events, never()).appendWithinAdmission(anyString(), anyString(), anyString(), any(), anyBoolean());
    }

    private static ChatAttachmentPort refusingPort(String message) {
        return new ChatAttachmentPort() {
            @Override
            public void validateAdmission(ExecutionPrincipal principal, AdmissionRequest request,
                                          EngineModelAuthority.PublishedModel model) {
                throw new RunApiException(RunErrorCode.BAD_REQUEST, message);
            }

            @Override
            public List<ChatMessage> prepare(RunRecord run, RunConfigBinding binding, List<ChatMessage> messages) {
                throw new IllegalStateException("prepare must not run during admission");
            }

            @Override
            public void bind(String tenant, String member, String user, String kind, String conversation,
                             String message, JsonNode refs) {
                throw new IllegalStateException("bind must not run during admission");
            }

            @Override
            public List<Map<String, Object>> list(String tenant, String member, String user, String kind,
                                                  String conversation, String message) {
                throw new IllegalStateException("list must not run during admission");
            }

            @Override
            public void persistAgentTurn(RunRecord run, String question, String answer) {
                throw new IllegalStateException("persistAgentTurn must not run during admission");
            }
        };
    }

    private static ChatAttachmentPort passingPort() {
        return new ChatAttachmentPort() {
            @Override
            public void validateAdmission(ExecutionPrincipal principal, AdmissionRequest request,
                                          EngineModelAuthority.PublishedModel model) {
                // 能力已声明：受理放行
            }

            @Override
            public List<ChatMessage> prepare(RunRecord run, RunConfigBinding binding, List<ChatMessage> messages) {
                return messages;
            }

            @Override
            public void bind(String tenant, String member, String user, String kind, String conversation,
                             String message, JsonNode refs) {
            }

            @Override
            public List<Map<String, Object>> list(String tenant, String member, String user, String kind,
                                                  String conversation, String message) {
                return List.of();
            }

            @Override
            public void persistAgentTurn(RunRecord run, String question, String answer) {
            }
        };
    }

    @Test
    @DisplayName("文本模型缺能力：受理拒绝且预算锁/run/注册/预占/outbox 零调用")
    void textModelRefusalPropagatesWithoutAnyLedgerWrite() {
        wireCommonStubs();
        service.configureModelAuthority(action -> publishedModel());
        service.configureAttachments(refusingPort(
                "The selected published model does not support image attachments"));

        RunApiException refused = assertThrows(RunApiException.class,
                () -> service.admit(principal, "idem-att-refused", requestWithAttachment()));

        assertEquals(RunErrorCode.BAD_REQUEST, refused.errorCode());
        assertTrue(refused.getMessage().contains("does not support image attachments"), refused.getMessage());
        // 正锚：确实走过了受理内部（幂等快路径已查），拒绝不是栽在请求形态校验前
        verify(dao).findByIdempotency(anyString(), anyString(), anyString(), anyString());
        verifyNoLedgerWritesOrEvents();
    }

    @Test
    @DisplayName("装配缺附件端口：同样第 0b 步响亮拒绝，零写入（不静默丢附件继续受理）")
    void missingAttachmentPortRefusesWithZeroWrites() {
        wireCommonStubs();
        service.configureModelAuthority(action -> publishedModel());
        // attachments 端口缺席（旧装配形态）

        RunApiException refused = assertThrows(RunApiException.class,
                () -> service.admit(principal, "idem-att-no-port", requestWithAttachment()));

        assertEquals(RunErrorCode.BAD_REQUEST, refused.errorCode());
        assertTrue(refused.getMessage().contains("unavailable"), refused.getMessage());
        verifyNoLedgerWritesOrEvents();
    }

    @Test
    @DisplayName("防恒真对照：端口放行时同一请求真的达到账本写入与受理事件")
    void withAPassingPortTheRequestReachesTheLedger() {
        wireCommonStubs();
        service.configureModelAuthority(action -> publishedModel());
        service.configureAttachments(passingPort());

        RunAdmissionService.AdmissionResult result =
                service.admit(principal, "idem-att-ok", requestWithAttachment());

        assertEquals("QUEUED", result.status());
        verify(dao).insertRun(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), isNull(), isNull(), anyString(), anyInt(), anyInt(),
                anyString(), isNull(), any(RunConfigBinding.class));
        verify(events).appendWithinAdmission(anyString(), anyString(), anyString(), any(), anyBoolean());
        verify(dao).insertReservation(anyString(), anyString(), anyString(), anyString(), anyLong());
    }
}
