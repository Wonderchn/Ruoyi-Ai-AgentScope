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
import com.nageoffer.ai.ragent.authorization.TenantConversationReadRepository;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import com.nageoffer.ai.ragent.runtime.port.ConversationContextPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link ConversationContextPort} 的 canonical 实现（F16 op2）：从<b>可验证的 run 账本</b>
 * 读取历史轮次，而不是把 {@code platform.ai_message} 的存量文本直接注入模型。
 *
 * <h2>为什么用 run 账本而不是 ai_message</h2>
 * {@code ai_message} 是 UI 历史，旧 assistant 文本没有"当时用的是哪些 KB/文档版本"的
 * provenance，无法在第 N 轮之后用<b>当前</b>授权事实复核"当时是否被授权"。run 账本不同：
 * 每个 SUCCEEDED 的 {@code rag.chat} run 有受理时固定的身份四元、resourceRefs、retrieve
 * 步骤里的 chunk 版本事实——它们可以在注入前用<i>当前</i>主体逐轮重判。因此本实现：
 * <ul>
 *   <li>按 {@code tenant + member + subject + action='rag.chat' + input.conversationId}
 *       精确选出 SUCCEEDED 且 retrieve/model/conversation-history 步骤完整的历史 run；</li>
 *   <li>逐轮跑 {@link RunAccessService#visible}：来源失效/撤权（404 语义）跳过该轮，
 *       依赖不可用（503 语义）中止整个加载并可见拒绝——不把故障伪装成"没有历史"；</li>
 *   <li>问答正文：问题取 run 行受理 input 的 {@code text}，答案取该 run 的
 *       {@code platform.ai_chat_message}（role=assistant）——与恢复路径同源。</li>
 * </ul>
 *
 * <h2>保守的可用性成本（显式声明，不暗中放宽）</h2>
 * {@code visible} 的属主守卫要求历史 run 的 policyVersion/aclVersion 与<i>当前</i>主体完全
 * 相等。租户策略或 ACL epoch 一旦变化，即使该轮原来的 KB 仍有权，该轮也会因四元滞后
 * 被跳过。这是刻意的保守拒绝：宁可少给上下文，不可用过期授权事实喂模型。
 *
 * <h2>身份与归属</h2>
 * 构造是显式依赖注入（不经过 Spring 扫描；装配由内嵌配置显式登记）。{@code load} 首先
 * 复核传入主体与当前 run 行归属完全一致；随后核 {@code conversation.read} 资源授权与
 * {@link TenantConversationReadRepository} 的 tenant+member 归属；跨成员/跨租户/已删除
 * 会话一律 404 语义（不泄露存在性）。
 */
public class AuthorizedRunConversationContext implements ConversationContextPort {

    /** 与 AiResourceAuthorizationService / 路由登记一致的动作名。 */
    private static final String ACTION_CONVERSATION_READ = "conversation.read";

    private final RunLedgerDao ledger;
    private final RunAccessService access;
    private final TenantConversationReadRepository conversations;
    private final AiResourceAuthorizationService authorization;
    private final int historyKeepTurns;
    private final ObjectMapper objectMapper;

    /**
     * @param historyKeepTurns 保留的最近完整轮数（来自配置，已在校验层收敛在 1..100；
     *                         本构造再要求 {@code >= 1}，非正值是装配错误，响亮拒绝）
     */
    public AuthorizedRunConversationContext(RunLedgerDao ledger, RunAccessService access,
                                            TenantConversationReadRepository conversations,
                                            AiResourceAuthorizationService authorization,
                                            int historyKeepTurns, ObjectMapper objectMapper) {
        if (historyKeepTurns < 1) {
            throw new IllegalArgumentException("historyKeepTurns must be >= 1");
        }
        this.ledger = ledger;
        this.access = access;
        this.conversations = conversations;
        this.authorization = authorization;
        this.historyKeepTurns = historyKeepTurns;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ChatMessage> load(RunRecord currentRun, ExecutionPrincipal currentPrincipal) {
        requireSameOwner(currentRun, currentPrincipal);
        String conversationId = conversationIdOf(currentRun);
        if (conversationId.isBlank()) {
            // 受理载荷没有 conversationId：没有会话可归属，这是唯一的合法空历史。
            return List.of();
        }
        requireConversationRead(currentPrincipal, conversationId);
        requireOwnership(currentPrincipal, conversationId);
        // DAO 按 (created_at DESC, run_id DESC) 取"最近 N 轮"；这里翻转为稳定正序（旧 → 新）重放。
        List<RunRecord> recentNewestFirst = listRecentCompleted(currentRun, conversationId);
        List<ChatMessage> messages = new ArrayList<>();
        for (int index = recentNewestFirst.size() - 1; index >= 0; index--) {
            RunRecord historical = recentNewestFirst.get(index);
            String question = questionOf(historical);
            if (question.isBlank()) {
                continue;
            }
            String answer = answerOf(historical);
            if (answer.isBlank()) {
                continue;
            }
            if (!turnStillAuthorized(currentPrincipal, historical)) {
                continue;
            }
            messages.add(ChatMessage.user(question));
            messages.add(ChatMessage.assistant(answer));
        }
        return messages;
    }

    /** 传入主体必须与当前 run 行的租户/成员/主体/版本四元完全一致（仿 ownerAndVersions；不匹配即 404）。 */
    private static void requireSameOwner(RunRecord run, ExecutionPrincipal principal) {
        if (run == null || principal == null
                || !principal.tenantId().equals(run.tenantId())
                || !principal.membershipId().equals(run.memberId())
                || !principal.userId().equals(run.subject())
                || principal.policyVersion() != run.policyVersion()
                || principal.aclVersion() != run.aclVersion()) {
            throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        }
    }

    /**
     * 会话读取资源授权：GRANT 放行；DENY → 404（与"不存在"同形，不泄露存在性）；
     * 判定依赖异常 / UNKNOWN → 503（授权不可用不得当"无历史"）。
     */
    private void requireConversationRead(ExecutionPrincipal principal, String conversationId) {
        ResourceAuthorizationService.Verdict verdict;
        try {
            var scoped = RunAccessService.scoped(principal, java.util.Set.of(ACTION_CONVERSATION_READ));
            verdict = authorization.check(scoped, ACTION_CONVERSATION_READ, "conv:" + conversationId);
        } catch (RuntimeException e) {
            throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE,
                    "conversation read authorization unavailable");
        }
        if (verdict == ResourceAuthorizationService.Verdict.GRANT) {
            return;
        }
        if (verdict == ResourceAuthorizationService.Verdict.DENY) {
            throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        }
        throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE,
                "conversation read authorization unresolved");
    }

    /** 会话归属：tenant + member + conversation + 未删除；查不到（含跨成员/跨租户）一律 404，依赖异常 503。 */
    private void requireOwnership(ExecutionPrincipal principal, String conversationId) {
        Optional<TenantConversationReadRepository.ConversationRow> owned;
        try {
            owned = conversations.findConversation(principal.tenantId(), principal.membershipId(), conversationId);
        } catch (RunApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE,
                    "conversation ownership lookup unavailable");
        }
        if (owned == null || owned.isEmpty()) {
            throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        }
    }

    private List<RunRecord> listRecentCompleted(RunRecord currentRun, String conversationId) {
        try {
            return ledger.listCompletedConversationRuns(currentRun.tenantId(), currentRun.memberId(),
                    currentRun.subject(), currentRun.action(), conversationId, currentRun.runId(),
                    historyKeepTurns);
        } catch (RunApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE,
                    "conversation history ledger unavailable");
        }
    }

    /**
     * 逐轮用<b>当前</b>主体重判历史 run 的来源与引用：
     * 来源失效/撤权/四元滞后（404 语义）→ 跳过该轮；授权依赖重复失败（503 语义/未预期异常）→ 中止并可见拒绝。
     *
     * @return true = 该轮仍然可授权，可进入模型上下文
     */
    private boolean turnStillAuthorized(ExecutionPrincipal principal, RunRecord historical) {
        try {
            access.visible(principal, historical);
            return true;
        } catch (RunApiException e) {
            if (e.errorCode() == RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN) {
                return false;
            }
            throw e;
        } catch (RuntimeException e) {
            throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE,
                    "conversation history recheck unavailable");
        }
    }

    /**
     * 问题取 run 行受理 input（与执行器读同一份权威载荷），按 action 分派：
     * {@code flow.run} 的问题在 {@code inputs.input}；其余沿用 {@code text}。
     * 缺失/畸形视为无内容轮。
     */
    private String questionOf(RunRecord run) {
        try {
            JsonNode input = run.inputJson() == null ? null : objectMapper.readTree(run.inputJson());
            if (input == null) {
                return "";
            }
            if ("flow.run".equals(run.action())) {
                return input.path("inputs").path("input").asText("");
            }
            return input.path("text").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private String conversationIdOf(RunRecord run) {
        try {
            JsonNode input = run.inputJson() == null ? null : objectMapper.readTree(run.inputJson());
            return input == null ? "" : input.path("conversationId").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    /** 答案取该 run 的 ai_chat_message（role=assistant、非空、按 sequence）；读不到即该轮无内容。 */
    private String answerOf(RunRecord run) {
        try {
            return ledger.loadCompletedConversationAnswer(run.tenantId(), run.runId()).orElse("");
        } catch (RunApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE,
                    "conversation answer lookup unavailable");
        }
    }
}
