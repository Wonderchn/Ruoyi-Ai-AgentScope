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

package com.nageoffer.ai.ragent.runtime.web;

import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.authorization.AiResourceWriteService;
import com.nageoffer.ai.ragent.authorization.TenantConversationReadRepository;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.framework.security.StaleVersionException;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.lang.reflect.Field;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ConversationBatchDeleteService} 的 D05 契约判据（F03 普通会话集合删除）。
 *
 * <p>每条判据都对应 D05 的一句话，而不是"跑通一次删除"：
 * 空/重复/超限/空白整体拒绝；全部资源先校验、任一不可访问整体失败且 <b>0 permit、0 写入</b>；
 * 覆盖全部资源的<b>批量</b> permit（不是单资源 permit 复用；permitCount 恒为 1）；
 * epoch 复核；整体事务（影响行数不等于集合大小即回滚）；删除 SQL 与唯一写路径逐字一致。
 */
@Tag("dev")
class ConversationBatchDeleteServiceTest {

    private static final String TENANT = "t1";
    private static final String MEMBER = "platform:t1:1001";
    private static final String USER = "1001";

    private AiResourceAuthorizationService authorization;
    private TenantConversationReadRepository conversations;
    private RevocationGuard guard;
    private NamedParameterJdbcTemplate jdbc;
    private ConversationBatchDeleteService service;

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RevocationGuard> provider(RevocationGuard guard) {
        ObjectProvider<RevocationGuard> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(guard);
        return provider;
    }

    /** 真事务语义的最小替身：直接执行回调，异常原样上抛（= 回滚）。 */
    private static final TransactionOperations TRANSACTIONS = new TransactionOperations() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(new SimpleTransactionStatus());
        }
    };

    @BeforeEach
    void setUp() {
        authorization = mock(AiResourceAuthorizationService.class);
        conversations = mock(TenantConversationReadRepository.class);
        guard = mock(RevocationGuard.class);
        jdbc = mock(NamedParameterJdbcTemplate.class);
        service = new ConversationBatchDeleteService(authorization, conversations, provider(guard),
                jdbc, TRANSACTIONS);
        principal(1, 1);
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    private static void principal(int policyVersion, int aclVersion) {
        PrincipalContext.set(new ExecutionPrincipal(TENANT, USER, MEMBER, policyVersion, aclVersion,
                Set.of(ConversationBatchDeleteService.DELETE_ACTION), "jti", "platform",
                java.time.Instant.now().getEpochSecond(), 9999999999L));
    }

    private void visible(String... ids) {
        for (String id : ids) {
            when(conversations.findConversation(TENANT, MEMBER, id))
                    .thenReturn(Optional.of(new TenantConversationReadRepository.ConversationRow(
                            id, "t", new Timestamp(0L))));
        }
    }

    private void granted(String... ids) {
        Map<String, ResourceAuthorizationService.Verdict> verdicts = new LinkedHashMap<>();
        for (String id : ids) {
            verdicts.put("conv:" + id, ResourceAuthorizationService.Verdict.GRANT);
        }
        when(authorization.checkBatch(any(), anyString(), any())).thenReturn(verdicts);
    }

    private void permitGranted() {
        when(guard.acquire(any())).thenAnswer(invocation -> {
            RevocationGuard.PermitRequest request = invocation.getArgument(0);
            return new RevocationGuard.PermitGrant("permit-" + request.resourceRefsHash().substring(0, 8), 1);
        });
    }

    // ------------------------------------------------------------ 成功路径

    @Test
    @DisplayName("D05 成功：3 个会话一次批量 permit、一条整批 SQL、deletedCount=3 / permitCount=1")
    void deletesWholeSetWithSingleBatchPermit() {
        visible("c-1", "c-2", "c-3");
        granted("c-1", "c-2", "c-3");
        permitGranted();
        when(jdbc.update(anyString(), any(Map.class))).thenReturn(3);

        ConversationBatchDeleteService.Outcome outcome =
                service.deleteAll(List.of("c-3", "c-1", "c-2"));

        assertEquals(3, outcome.deletedCount());
        assertEquals(1, outcome.permitCount(), "D05：覆盖全部资源的批量 permit ⇒ permit 数恒为 1");

        // 只有一条写语句、只在整批一次
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(jdbc).update(anyString(), params.capture());
        assertEquals(List.of("c-1", "c-2", "c-3"), params.getValue().get("conversations"),
                "删除集合必须是确定顺序（排序后）");
        assertEquals(TENANT, params.getValue().get("tenant"));
        assertEquals(MEMBER, params.getValue().get("member"));

        // permit 只取一个，且其 resourceRefsHash 覆盖全部资源 —— 不是任一单资源 ref 的 hash
        ArgumentCaptor<RevocationGuard.PermitRequest> permit =
                ArgumentCaptor.forClass(RevocationGuard.PermitRequest.class);
        verify(guard).acquire(permit.capture());
        assertEquals(ConversationBatchDeleteService.DELETE_ACTION, permit.getValue().action());
        List<String> refs = List.of("conv:c-1", "conv:c-2", "conv:c-3");
        assertEquals(ConversationBatchDeleteService.batchRefsHash(refs), permit.getValue().resourceRefsHash());
        for (String single : refs) {
            assertNotEquals(ConversationBatchDeleteService.batchRefsHash(List.of(single)),
                    permit.getValue().resourceRefsHash(),
                    "批量 permit 的摘要不得等于任何单资源摘要：" + single);
        }
        verify(guard).release(org.mockito.ArgumentMatchers.eq(outcome.permitId()), anyString());
    }

    @Test
    @DisplayName("D05 成功：请求顺序不同 ⇒ 同一摘要、同一删除集合（顺序确定）")
    void deterministicForPermutedInput() {
        visible("c-1", "c-2");
        granted("c-1", "c-2");
        permitGranted();
        when(jdbc.update(anyString(), any(Map.class))).thenReturn(2);

        service.deleteAll(List.of("c-1", "c-2"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> first = ArgumentCaptor.forClass(Map.class);
        verify(jdbc).update(anyString(), first.capture());
        ArgumentCaptor<RevocationGuard.PermitRequest> p1 =
                ArgumentCaptor.forClass(RevocationGuard.PermitRequest.class);
        verify(guard).acquire(p1.capture());

        PrincipalContext.clear();
        principal(1, 1);
        service.deleteAll(List.of("c-2", "c-1"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> second = ArgumentCaptor.forClass(Map.class);
        verify(jdbc, org.mockito.Mockito.times(2)).update(anyString(), second.capture());
        ArgumentCaptor<RevocationGuard.PermitRequest> p2 =
                ArgumentCaptor.forClass(RevocationGuard.PermitRequest.class);
        verify(guard, org.mockito.Mockito.times(2)).acquire(p2.capture());

        assertEquals(first.getValue().get("conversations"), second.getValue().get("conversations"));
        assertEquals(p1.getValue().resourceRefsHash(), p2.getValue().resourceRefsHash());
    }

    // ------------------------------------------------------------ 集合形状（整体拒绝）

    @Test
    @DisplayName("D05 负例：null / 空集合 ⇒ 400，且不触达授权、permit、数据库")
    void rejectsEmptySet() {
        for (List<String> ids : List.of(
                new ArrayList<String>(),
                java.util.Collections.<String>emptyList())) {
            RunApiException e = assertThrows(RunApiException.class, () -> service.deleteAll(ids));
            assertEquals(RunErrorCode.BAD_REQUEST, e.errorCode());
        }
        assertThrows(RunApiException.class, () -> service.deleteAll(null));
        verify(guard, never()).acquire(any());
        verify(jdbc, never()).update(anyString(), any(Map.class));
        verify(conversations, never()).findConversation(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("D05 负例：重复 ID ⇒ 400（不去重静默）")
    void rejectsDuplicateIds() {
        RunApiException e = assertThrows(RunApiException.class,
                () -> service.deleteAll(List.of("c-1", "c-1")));
        assertEquals(RunErrorCode.BAD_REQUEST, e.errorCode());
        verify(guard, never()).acquire(any());
        verify(jdbc, never()).update(anyString(), any(Map.class));
    }

    @Test
    @DisplayName("D05 负例：空白/缺项 ID ⇒ 400")
    void rejectsBlankIds() {
        for (List<String> ids : List.of(
                java.util.Arrays.asList("c-1", (String) null),
                List.of("c-1", "  "),
                List.of(" "))) {
            RunApiException e = assertThrows(RunApiException.class, () -> service.deleteAll(ids));
            assertEquals(RunErrorCode.BAD_REQUEST, e.errorCode());
        }
        verify(guard, never()).acquire(any());
    }

    @Test
    @DisplayName("D05 边界：100 条通过形状校验；101 条整体拒绝（不截断）")
    void rejectsOverLimitWithoutTruncation() {
        List<String> hundred = new ArrayList<>();
        for (int i = 0; i < ConversationBatchDeleteService.MAX_BATCH; i++) {
            hundred.add("c-" + i);
        }
        assertEquals(ConversationBatchDeleteService.MAX_BATCH,
                ConversationBatchDeleteService.requireWellFormedSet(hundred).size());

        List<String> tooMany = new ArrayList<>(hundred);
        tooMany.add("c-over");
        RunApiException e = assertThrows(RunApiException.class, () -> service.deleteAll(tooMany));
        assertEquals(RunErrorCode.BAD_REQUEST, e.errorCode());
        verify(guard, never()).acquire(any());
        verify(jdbc, never()).update(anyString(), any(Map.class));
    }

    // ------------------------------------------------------------ 身份与授权（整体拒绝）

    @Test
    @DisplayName("缺主体 ⇒ 401，且不触达授权与数据库（不得退化成无用户限定）")
    void rejectsMissingPrincipal() {
        PrincipalContext.clear();
        RunApiException e = assertThrows(RunApiException.class, () -> service.deleteAll(List.of("c-1")));
        assertEquals(RunErrorCode.AUTH_REQUIRED, e.errorCode());
        verify(authorization, never()).requireFunction(any(), anyString(), anyString());
        verify(guard, never()).acquire(any());
        verify(jdbc, never()).update(anyString(), any(Map.class));
    }

    @Test
    @DisplayName("功能级拒绝（scope 有但平台判定不通过）⇒ 403，且不取 permit、不写库")
    void rejectsFunctionLevelDenial() {
        doAnswer(invocation -> {
            throw new P04AiException(P04AiErrorCode.FORBIDDEN);
        }).when(authorization).requireFunction(any(), anyString(), anyString());

        RunApiException e = assertThrows(RunApiException.class, () -> service.deleteAll(List.of("c-1")));
        assertEquals(RunErrorCode.FORBIDDEN, e.errorCode());
        verify(guard, never()).acquire(any());
        verify(jdbc, never()).update(anyString(), any(Map.class));
    }

    @Test
    @DisplayName("资源级：任一 DENY（跨租户/越权/已撤权）⇒ 404，且 0 permit、0 写入（无部分成功）")
    void rejectsWhenAnyResourceDenied() {
        Map<String, ResourceAuthorizationService.Verdict> verdicts = new HashMap<>();
        verdicts.put("conv:c-1", ResourceAuthorizationService.Verdict.GRANT);
        verdicts.put("conv:c-2", ResourceAuthorizationService.Verdict.DENY);
        when(authorization.checkBatch(any(), anyString(), any())).thenReturn(verdicts);

        RunApiException e = assertThrows(RunApiException.class,
                () -> service.deleteAll(List.of("c-1", "c-2")));
        assertEquals(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, e.errorCode());
        verify(guard, never()).acquire(any());
        verify(jdbc, never()).update(anyString(), any(Map.class));
        verify(conversations, never()).findConversation(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("资源级：判定结果缺席（事实源不回该 ref）⇒ 404，不放行")
    void rejectsWhenVerdictAbsent() {
        when(authorization.checkBatch(any(), anyString(), any())).thenReturn(Map.of());
        RunApiException e = assertThrows(RunApiException.class, () -> service.deleteAll(List.of("c-1")));
        assertEquals(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, e.errorCode());
        verify(guard, never()).acquire(any());
    }

    @Test
    @DisplayName("撤权（STALE）⇒ 409 VERSION_CONFLICT；UNKNOWN ⇒ 503，均不取 permit")
    void mapsStaleAndUnknownVerdicts() {
        when(authorization.checkBatch(any(), anyString(), any())).thenReturn(
                Map.of("conv:c-1", ResourceAuthorizationService.Verdict.STALE));
        RunApiException stale = assertThrows(RunApiException.class, () -> service.deleteAll(List.of("c-1")));
        assertEquals(RunErrorCode.VERSION_CONFLICT, stale.errorCode());

        when(authorization.checkBatch(any(), anyString(), any())).thenReturn(
                Map.of("conv:c-1", ResourceAuthorizationService.Verdict.UNKNOWN));
        RunApiException unknown = assertThrows(RunApiException.class, () -> service.deleteAll(List.of("c-1")));
        assertEquals(RunErrorCode.AUTHORIZATION_UNAVAILABLE, unknown.errorCode());

        verify(guard, never()).acquire(any());
        verify(jdbc, never()).update(anyString(), any(Map.class));
    }

    @Test
    @DisplayName("授权事实源不可用（ServiceException）⇒ 503，不放行")
    void authorizationSourceUnavailableIsRefused() {
        doAnswer(invocation -> {
            throw new ServiceException("facts unavailable");
        }).when(authorization).requireFunction(any(), anyString(), anyString());
        RunApiException e = assertThrows(RunApiException.class, () -> service.deleteAll(List.of("c-1")));
        assertEquals(RunErrorCode.AUTHORIZATION_UNAVAILABLE, e.errorCode());
        verify(guard, never()).acquire(any());
    }

    @Test
    @DisplayName("跨租户/不可见（任一 findConversation 为空）⇒ 404，且 0 permit、0 写入")
    void rejectsWhenAnyConversationInvisible() {
        visible("c-1");
        when(conversations.findConversation(TENANT, MEMBER, "c-2")).thenReturn(Optional.empty());
        granted("c-1", "c-2");

        RunApiException e = assertThrows(RunApiException.class,
                () -> service.deleteAll(List.of("c-1", "c-2")));
        assertEquals(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, e.errorCode());
        verify(guard, never()).acquire(any());
        verify(jdbc, never()).update(anyString(), any(Map.class));
    }

    // ------------------------------------------------------------ permit 与 epoch

    @Test
    @DisplayName("撤权屏障不可用（无 RevocationGuard）⇒ 503，不做无屏障的批量删除")
    void refusesWithoutRevocationGuard() {
        visible("c-1");
        granted("c-1");
        ConversationBatchDeleteService bare = new ConversationBatchDeleteService(
                authorization, conversations, provider(null), jdbc, TRANSACTIONS);

        RunApiException e = assertThrows(RunApiException.class, () -> bare.deleteAll(List.of("c-1")));
        assertEquals(RunErrorCode.AUTHORIZATION_UNAVAILABLE, e.errorCode());
        verify(jdbc, never()).update(anyString(), any(Map.class));
    }

    @Test
    @DisplayName("permit 取得后 epoch 变化 ⇒ 409，且不写库、permit 仍被释放")
    void rechecksEpochBeforeWrite() {
        visible("c-1");
        granted("c-1");
        doAnswer(invocation -> {
            // 模拟"取得 permit 之后、写入之前"并发发生的策略/ACL 版本变更
            principal(2, 1);
            return new RevocationGuard.PermitGrant("permit-x", 1);
        }).when(guard).acquire(any());

        RunApiException e = assertThrows(RunApiException.class, () -> service.deleteAll(List.of("c-1")));
        assertEquals(RunErrorCode.VERSION_CONFLICT, e.errorCode());
        verify(jdbc, never()).update(anyString(), any(Map.class));
        verify(guard).release(anyString(), anyString());
    }

    @Test
    @DisplayName("取得 permit 时 aclVersion 过期（StaleVersionException）⇒ 409，不写库")
    void mapsPermitStaleToConflict() {
        visible("c-1");
        granted("c-1");
        doAnswer(invocation -> {
            throw new StaleVersionException("aclVersion changed");
        }).when(guard).acquire(any());

        RunApiException e = assertThrows(RunApiException.class, () -> service.deleteAll(List.of("c-1")));
        assertEquals(RunErrorCode.VERSION_CONFLICT, e.errorCode());
        verify(jdbc, never()).update(anyString(), any(Map.class));
    }

    // ------------------------------------------------------------ 整体事务

    @Test
    @DisplayName("影响行数 ≠ 集合大小（并发软删/预检后撤权）⇒ 整体失败，不允许部分成功")
    void rollsBackWhenAffectedRowsMismatch() {
        visible("c-1", "c-2");
        granted("c-1", "c-2");
        permitGranted();
        when(jdbc.update(anyString(), any(Map.class))).thenReturn(1);

        RunApiException e = assertThrows(RunApiException.class,
                () -> service.deleteAll(List.of("c-1", "c-2")));
        assertEquals(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, e.errorCode());
        // 事务替身把异常原样上抛 = 真实事务回滚；permit 仍然被释放
        verify(guard).release(anyString(), anyString());
    }

    // ------------------------------------------------------------ SQL 一致性（防漂移）

    @Test
    @DisplayName("批量删除 SQL 与唯一写路径 AiResourceWriteService 的软删 SQL 语义逐字一致（防两套口径）")
    void batchSqlStaysEquivalentToSingleDeleteSql() throws Exception {
        Field field = AiResourceWriteService.class.getDeclaredField("SQL_SOFT_DELETE_CONVERSATION");
        field.setAccessible(true);
        String single = (String) field.get(null);
        assertNotNull(single, "AiResourceWriteService 的软删 SQL 常量缺失：本判据失去锚点");

        assertEquals(normalize(single), normalize(ConversationBatchDeleteService.SQL_SOFT_DELETE_CONVERSATIONS),
                "批量软删 SQL 与单资源软删 SQL 已漂移（谓词/列集合/SET 子句必须一致）");
    }

    /**
     * 归一化：把"单条 {@code = :conversation} 谓词"与"集合 {@code IN (:conversations)} 谓词"
     * 折叠成同一形状，并抹掉命名参数名差异 —— 只比较语义骨架（表、SET、租户/成员/软删谓词）。
     */
    private static String normalize(String sql) {
        return sql.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .replace("in (:conversations)", "= :conversation")
                .replace(":tenant", ":t")
                .replace(":member", ":m")
                .replace(":conversation", ":c")
                .trim();
    }

    @Test
    @DisplayName("批量 permit 摘要：不同集合不同摘要、同集合恒定（不低于 64 位十六进制）")
    void batchRefsHashIsSetShaped() {
        String a = ConversationBatchDeleteService.batchRefsHash(List.of("conv:c-1", "conv:c-2"));
        String b = ConversationBatchDeleteService.batchRefsHash(List.of("conv:c-2", "conv:c-1"));
        String c = ConversationBatchDeleteService.batchRefsHash(List.of("conv:c-1", "conv:c-3"));
        assertEquals(a, b, "同一集合必须得到同一摘要");
        assertNotEquals(a, c, "不同集合不得碰撞出同一摘要");
        assertTrue(a.matches("[0-9a-f]{64}"));
    }
}
