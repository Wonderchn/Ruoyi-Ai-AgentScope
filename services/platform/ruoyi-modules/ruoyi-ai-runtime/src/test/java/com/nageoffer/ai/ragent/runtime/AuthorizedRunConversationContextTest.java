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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.authorization.TenantConversationReadRepository;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F16 op2：{@link AuthorizedRunConversationContext} 的契约判据（真实实现 + 替身依赖）。
 *
 * <p>判据分层：
 * <ul>
 *   <li><b>身份</b>：传入主体必须与 run 行四元完全一致，不一致 404 且不触碰会话/账本；</li>
 *   <li><b>会话可读/归属</b>：DENY/查不到 404；UNKNOWN/依赖异常 503；</li>
 *   <li><b>有界重放</b>：DAO 倒序（最近 N）→ 稳定正序（旧→新）；空问题/空答案轮跳过；
 *       逐轮用当前主体重判，404 跳过该轮、503 中止整个加载；</li>
 *   <li><b>唯一合法空历史</b>：无 conversationId 直接空表，不触碰任何依赖。</li>
 * </ul>
 * 真库侧的 DAO 判据在 {@code RunLedgerDaoConversationContextTest}（需要隔离 PG）。
 */
@Tag("dev")
class AuthorizedRunConversationContextTest {

    private static final String TENANT = "t1";
    private static final String MEMBER = "platform:t1:1001";
    private static final String USER = "1001";
    private static final String CONV = "conv-1";
    private static final String CURRENT_RUN = "run-current";
    private static final int KEEP = 8;

    private RunLedgerDao ledger;
    private RunAccessService access;
    private TenantConversationReadRepository conversations;
    private AiResourceAuthorizationService authorization;
    private AuthorizedRunConversationContext context;

    @BeforeEach
    void setUp() {
        ledger = mock(RunLedgerDao.class);
        access = mock(RunAccessService.class);
        conversations = mock(TenantConversationReadRepository.class);
        authorization = mock(AiResourceAuthorizationService.class);
        context = new AuthorizedRunConversationContext(ledger, access, conversations, authorization,
                KEEP, new ObjectMapper());
    }

    private static ExecutionPrincipal principal() {
        return principal(TENANT, USER, MEMBER, 1, 1);
    }

    private static ExecutionPrincipal principal(String tenant, String user, String member, int pv, int av) {
        return new ExecutionPrincipal(tenant, user, member, pv, av, Set.of("conversation.read"),
                "jti", "platform", 1, 9999999999L);
    }

    private static RunRecord run(String runId, String inputJson) {
        return run("rag.chat", runId, inputJson);
    }

    private static RunRecord run(String action, String runId, String inputJson) {
        return new RunRecord(TENANT, runId, MEMBER, USER, action, "RUNNING", "hash", "key",
                inputJson, "{}", "p2-v1", 1, 1, "[]", 1L, 1L, 1, 0L,
                "worker-1", null, null, null, null, null, Instant.EPOCH, Instant.EPOCH, null);
    }

    private static RunRecord currentRun() {
        return run(CURRENT_RUN, "{\"text\":\"本轮问题\",\"conversationId\":\"" + CONV + "\"}");
    }

    private static RunRecord historyRun(String runId, String question) {
        return run(runId, "{\"text\":\"" + question + "\",\"conversationId\":\"" + CONV + "\"}");
    }

    /** 会话可读 + 归属存在的公共前提；各负例只破坏这条链的某一环。 */
    private void authorizeConversation() {
        when(authorization.check(any(), eq("conversation.read"), eq("conv:" + CONV)))
                .thenReturn(ResourceAuthorizationService.Verdict.GRANT);
        when(conversations.findConversation(TENANT, MEMBER, CONV))
                .thenReturn(Optional.of(new TenantConversationReadRepository.ConversationRow(CONV, "标题", null)));
    }

    private static void assertMessages(List<ChatMessage> actual, String... expectedContents) {
        assertEquals(expectedContents.length, actual.size(), "消息条数");
        for (int i = 0; i < expectedContents.length; i++) {
            assertEquals(expectedContents[i], actual.get(i).getContent(), "第 " + i + " 条内容");
        }
    }

    @Test
    @DisplayName("构造：historyKeepTurns 必须 >= 1（装配错误响亮拒绝，不静默取默认）")
    void keepTurnsMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new AuthorizedRunConversationContext(
                ledger, access, conversations, authorization, 0, new ObjectMapper()));
    }

    @Test
    @DisplayName("无 conversationId：空表且不触碰账本/授权/归属（唯一合法空历史）")
    void runWithoutConversationIdIsTheOnlyLegalEmptyHistory() {
        RunRecord run = run(CURRENT_RUN, "{\"text\":\"无会话\"}");

        assertTrue(context.load(run, principal()).isEmpty());
        verifyNoInteractions(ledger, access, conversations, authorization);
    }

    @Test
    @DisplayName("主体与 run 行四元任一不符 ⇒ 404，不进入会话/账本")
    void mismatchedPrincipalIsPrivate404() {
        RunRecord run = currentRun();
        List<ExecutionPrincipal> mismatched = List.of(
                principal("t2", USER, "platform:t2:" + USER, 1, 1),
                principal(TENANT, "2002", "platform:t1:2002", 1, 1),
                principal(TENANT, USER, MEMBER, 2, 1),
                principal(TENANT, USER, MEMBER, 1, 2));
        for (ExecutionPrincipal principal : mismatched) {
            RunApiException e = assertThrows(RunApiException.class, () -> context.load(run, principal));
            assertEquals(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, e.errorCode(), principal.toString());
        }
        verifyNoInteractions(ledger, access, conversations, authorization);
    }

    @Test
    @DisplayName("conversation.read 明确 DENY ⇒ 404（与不存在同形），且不查归属")
    void deniedConversationReadIsPrivate404() {
        when(authorization.check(any(), eq("conversation.read"), eq("conv:" + CONV)))
                .thenReturn(ResourceAuthorizationService.Verdict.DENY);

        RunApiException e = assertThrows(RunApiException.class, () -> context.load(currentRun(), principal()));
        assertEquals(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, e.errorCode());
        verifyNoInteractions(conversations, ledger);
    }

    @Test
    @DisplayName("conversation.read 无法判定（UNKNOWN）⇒ 503，绝不退化成'无历史'")
    void unresolvedConversationReadIs503() {
        when(authorization.check(any(), eq("conversation.read"), eq("conv:" + CONV)))
                .thenReturn(ResourceAuthorizationService.Verdict.UNKNOWN);

        RunApiException e = assertThrows(RunApiException.class, () -> context.load(currentRun(), principal()));
        assertEquals(RunErrorCode.AUTHORIZATION_UNAVAILABLE, e.errorCode());
        verifyNoInteractions(conversations, ledger);
    }

    @Test
    @DisplayName("conversation.read 判定抛异常 ⇒ 503（依赖故障不得伪装成'无历史'）")
    void conversationReadCheckFailureIs503() {
        when(authorization.check(any(), eq("conversation.read"), eq("conv:" + CONV)))
                .thenThrow(new IllegalStateException("auth offline"));

        RunApiException e = assertThrows(RunApiException.class, () -> context.load(currentRun(), principal()));
        assertEquals(RunErrorCode.AUTHORIZATION_UNAVAILABLE, e.errorCode());
    }

    @Test
    @DisplayName("会话查不到（跨成员/已删除/无此会话同形）⇒ 404，不读账本")
    void conversationNotOwnedIsPrivate404() {
        when(authorization.check(any(), eq("conversation.read"), eq("conv:" + CONV)))
                .thenReturn(ResourceAuthorizationService.Verdict.GRANT);
        when(conversations.findConversation(TENANT, MEMBER, CONV)).thenReturn(Optional.empty());

        RunApiException e = assertThrows(RunApiException.class, () -> context.load(currentRun(), principal()));
        assertEquals(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, e.errorCode());
        verifyNoInteractions(ledger);
    }

    @Test
    @DisplayName("归属查询故障 ⇒ 503（不得当 404 跳过该范围）")
    void ownershipLookupFailureIs503() {
        when(authorization.check(any(), eq("conversation.read"), eq("conv:" + CONV)))
                .thenReturn(ResourceAuthorizationService.Verdict.GRANT);
        when(conversations.findConversation(TENANT, MEMBER, CONV))
                .thenThrow(new IllegalStateException("conversation store offline"));

        RunApiException e = assertThrows(RunApiException.class, () -> context.load(currentRun(), principal()));
        assertEquals(RunErrorCode.AUTHORIZATION_UNAVAILABLE, e.errorCode());
    }

    @Test
    @DisplayName("账本查询故障 ⇒ 503")
    void ledgerFailureIs503() {
        authorizeConversation();
        when(ledger.listCompletedConversationRuns(TENANT, MEMBER, USER, "rag.chat", CONV, CURRENT_RUN, KEEP))
                .thenThrow(new IllegalStateException("ledger offline"));

        RunApiException e = assertThrows(RunApiException.class, () -> context.load(currentRun(), principal()));
        assertEquals(RunErrorCode.AUTHORIZATION_UNAVAILABLE, e.errorCode());
    }

    @Test
    @DisplayName("答案读取故障 ⇒ 503（不把故障跳过成少一轮）")
    void answerLookupFailureIs503() {
        authorizeConversation();
        RunRecord r1 = historyRun("run-1", "q1");
        when(ledger.listCompletedConversationRuns(TENANT, MEMBER, USER, "rag.chat", CONV, CURRENT_RUN, KEEP))
                .thenReturn(List.of(r1));
        when(ledger.loadCompletedConversationAnswer(TENANT, "run-1"))
                .thenThrow(new IllegalStateException("answer store offline"));

        RunApiException e = assertThrows(RunApiException.class, () -> context.load(currentRun(), principal()));
        assertEquals(RunErrorCode.AUTHORIZATION_UNAVAILABLE, e.errorCode());
    }

    @Test
    @DisplayName("DAO 倒序（最近 N）翻转为稳定正序；空答案轮跳过且不参与重判")
    void newestFirstIsReplayedOldestFirstAndUnusableTurnsAreSkipped() {
        ExecutionPrincipal principal = principal();
        authorizeConversation();
        RunRecord r1 = historyRun("run-1", "q1");
        RunRecord r2 = historyRun("run-2", "q2");
        RunRecord r3 = historyRun("run-3", "q3");
        // DAO 契约：created_at DESC, run_id DESC（最近在前）
        when(ledger.listCompletedConversationRuns(TENANT, MEMBER, USER, "rag.chat", CONV, CURRENT_RUN, KEEP))
                .thenReturn(List.of(r3, r2, r1));
        when(ledger.loadCompletedConversationAnswer(TENANT, "run-1")).thenReturn(Optional.of("a1"));
        when(ledger.loadCompletedConversationAnswer(TENANT, "run-2")).thenReturn(Optional.empty());
        when(ledger.loadCompletedConversationAnswer(TENANT, "run-3")).thenReturn(Optional.of("a3"));

        List<ChatMessage> messages = context.load(currentRun(), principal);

        assertMessages(messages, "q1", "a1", "q3", "a3");
        assertEquals(ChatMessage.Role.USER, messages.get(0).getRole());
        assertEquals(ChatMessage.Role.ASSISTANT, messages.get(1).getRole());
        // 空答案轮：答案读取返回空 ⇒ 该轮跳过（不进入历史、也不参与重判）
        verify(access, never()).visible(any(), eq(r2));
        verify(access).visible(principal, r1);
        verify(access).visible(principal, r3);
        // 查询范围：当前 run 显式排除 + 配置上限透传
        verify(ledger).listCompletedConversationRuns(TENANT, MEMBER, USER, "rag.chat", CONV, CURRENT_RUN, KEEP);
    }

    @Test
    @DisplayName("flow.run：action 透传到账本，问题取 inputs.input（与 rag.chat 的 text 分派）")
    void flowRunQuestionComesFromInputsInput() {
        ExecutionPrincipal principal = principal();
        authorizeConversation();
        RunRecord current = run("flow.run", CURRENT_RUN,
                "{\"inputs\":{\"input\":\"本轮流程问题\"},\"conversationId\":\"" + CONV + "\"}");
        RunRecord previous = run("flow.run", "run-f1",
                "{\"inputs\":{\"input\":\"上一轮流程问题\"},\"conversationId\":\"" + CONV + "\"}");
        when(ledger.listCompletedConversationRuns(TENANT, MEMBER, USER, "flow.run", CONV, CURRENT_RUN, KEEP))
                .thenReturn(List.of(previous));
        when(ledger.loadCompletedConversationAnswer(TENANT, "run-f1")).thenReturn(Optional.of("上一轮流程答案"));

        List<ChatMessage> messages = context.load(current, principal);

        assertMessages(messages, "上一轮流程问题", "上一轮流程答案");
        verify(ledger).listCompletedConversationRuns(TENANT, MEMBER, USER, "flow.run", CONV, CURRENT_RUN, KEEP);
    }

    @Test
    @DisplayName("历史轮来源失效/撤权（404 语义）⇒ 只跳过该轮，不中止")
    void revokedHistoricalTurnIsSkipped() {
        ExecutionPrincipal principal = principal();
        authorizeConversation();
        RunRecord r1 = historyRun("run-1", "q1");
        RunRecord r2 = historyRun("run-2", "q2");
        RunRecord r3 = historyRun("run-3", "q3");
        when(ledger.listCompletedConversationRuns(TENANT, MEMBER, USER, "rag.chat", CONV, CURRENT_RUN, KEEP))
                .thenReturn(List.of(r3, r2, r1));
        when(ledger.loadCompletedConversationAnswer(TENANT, "run-1")).thenReturn(Optional.of("a1"));
        when(ledger.loadCompletedConversationAnswer(TENANT, "run-2")).thenReturn(Optional.of("a2"));
        when(ledger.loadCompletedConversationAnswer(TENANT, "run-3")).thenReturn(Optional.of("a3"));
        doThrow(new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN))
                .when(access).visible(principal, r2);

        assertMessages(context.load(currentRun(), principal), "q1", "a1", "q3", "a3");
    }

    @Test
    @DisplayName("历史轮重判依赖失败（503 语义）⇒ 整个加载中止且可见拒绝，不部分注入")
    void dependencyFailureDuringRecheckAbortsWholeLoad() {
        ExecutionPrincipal principal = principal();
        authorizeConversation();
        RunRecord r1 = historyRun("run-1", "q1");
        when(ledger.listCompletedConversationRuns(TENANT, MEMBER, USER, "rag.chat", CONV, CURRENT_RUN, KEEP))
                .thenReturn(List.of(r1));
        when(ledger.loadCompletedConversationAnswer(TENANT, "run-1")).thenReturn(Optional.of("a1"));
        doThrow(new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE, "recheck unavailable"))
                .when(access).visible(principal, r1);

        RunApiException e = assertThrows(RunApiException.class, () -> context.load(currentRun(), principal));
        assertEquals(RunErrorCode.AUTHORIZATION_UNAVAILABLE, e.errorCode());
    }

    @Test
    @DisplayName("重判抛未预期异常 ⇒ 503，不静默跳过")
    void unexpectedRecheckFailureIs503() {
        authorizeConversation();
        RunRecord r1 = historyRun("run-1", "q1");
        when(ledger.listCompletedConversationRuns(TENANT, MEMBER, USER, "rag.chat", CONV, CURRENT_RUN, KEEP))
                .thenReturn(List.of(r1));
        when(ledger.loadCompletedConversationAnswer(TENANT, "run-1")).thenReturn(Optional.of("a1"));
        doThrow(new IllegalStateException("boom")).when(access).visible(any(), eq(r1));

        RunApiException e = assertThrows(RunApiException.class, () -> context.load(currentRun(), principal()));
        assertEquals(RunErrorCode.AUTHORIZATION_UNAVAILABLE, e.errorCode());
    }
}
