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

package com.nageoffer.ai.ragent.rag.service.impl;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.authorization.TenantConversationReadRepository;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.rag.service.ConversationMessageService;
import com.nageoffer.ai.ragent.rag.service.bo.ConversationMessageBO;
import com.nageoffer.ai.ragent.runtime.port.ConversationHistoryPort;
import com.nageoffer.ai.ragent.runtime.port.ConversationTurnMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

/**
 * {@link ConversationHistoryPort} 在 ai-rag 侧的实现 —— RW-01-CHATHIST。
 *
 * <p>执行器（{@code RagChatExecutor}，在 ai-runtime）在工作线程里跑，
 * {@code RunWorker} <b>不</b>绑定 {@code PrincipalContext}；而
 * {@link ConversationMessageService#addMessage} 与 {@code AiDomainWriteIdentity}
 * 都要求主体（统一库 {@code ai_message.tenant_id/member_id} 是 NOT NULL，
 * 且该 helper 刻意不提供"外部传参"重载以防伪造）。所以本适配器把执行器从
 * <b>run 行</b>取得的权威身份<b>临时</b>绑定成主体，写完即 {@code restore}。
 *
 * <p><b>身份只来自端口入参</b>，不读请求体、不读载荷。缺任一身份即拒绝——
 * 不填空、不落默认租户、不写"无成员"哨兵（与 {@code AiDomainWriteIdentity} 同一纪律）。
 *
 * <p>归属校验交给 {@code ConversationMessageServiceImpl.listMessages} 用的同一条判据：
 * 消息落库前必须确认该会话属于该用户（{@code tenant+user} 限域）。这里的做法是
 * 先按 {@code (tenantId, userId, conversationId)} 读一次会话归属——见
 * {@link #appendTurn}；读不到就抛（fail-closed），绝不为一个不属于本主体的会话写历史。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class ConversationHistoryAdapter implements ConversationHistoryPort {

    private final ConversationMessageService conversationMessageService;
    /**
     * 会话归属判据**复用读路径那一份**（{@code tenant_id + member_id + conversation_id + deleted=0}），
     * 不另写一份"差不多"的 SQL：跨租户/他人会话与"不存在"同样读不到，这里据此拒绝写入。
     */
    private final TenantConversationReadRepository conversations;

    @Override
    public void appendTurn(String tenantId, String memberId, String userId, String conversationId,
                           String question, String answer) {
        appendTurn(tenantId, memberId, userId, conversationId, question, answer, ConversationTurnMeta.EMPTY);
    }

    @Override
    public void appendTurn(String tenantId, String memberId, String userId, String conversationId,
                           String question, String answer, ConversationTurnMeta meta) {
        if (StrUtil.isBlank(tenantId) || StrUtil.isBlank(memberId) || StrUtil.isBlank(userId)) {
            throw new IllegalStateException(
                    "conversation history requires run-scoped identity (tenant/member/user); "
                            + "refusing to write history without it");
        }
        if (StrUtil.isBlank(conversationId)) {
            throw new IllegalStateException("conversationId is required to append conversation history");
        }
        if (conversations.findConversation(tenantId, memberId, conversationId).isEmpty()) {
            // 与读路径同外显：跨租户/他人的会话"等同不存在"，且这里直接拒绝写入
            throw new IllegalStateException(
                    "conversation " + conversationId + " is not owned by the run subject; refusing to write history");
        }

        ConversationTurnMeta facts = meta == null ? ConversationTurnMeta.EMPTY : meta;
        ExecutionPrincipal previous = PrincipalContext.get();
        PrincipalContext.set(new ExecutionPrincipal(tenantId, userId, memberId, 1, 1, Set.of(),
                "run-history-" + conversationId, "platform",
                Instant.now().getEpochSecond(), Instant.now().getEpochSecond() + 60));
        try {
            String userMessageId = conversationMessageService.addMessage(
                    message(conversationId, userId, "user", question, null, null));
            conversationMessageService.addMessage(
                    message(conversationId, userId, "assistant", answer, userMessageId, facts));
        } finally {
            PrincipalContext.restore(previous);
        }
    }

    private ConversationMessageBO message(String conversationId, String userId, String role,
                                          String content, String replyToMessageId, ConversationTurnMeta meta) {
        ConversationMessageBO message = new ConversationMessageBO();
        message.setConversationId(conversationId);
        message.setUserId(userId);
        message.setRole(role);
        message.setContent(content);
        message.setMessageStatus("NORMAL");
        message.setReplyToMessageId(replyToMessageId);
        if (meta != null && "assistant".equals(role)) {
            // F03 op3 写侧收口：模型绑定 / 逐消息用量 / 耗时随助手消息落库；
            // 用户消息不写这三列（它不是模型调用的产物）。
            // 注意 thinkingDuration → 冻结列 thinking_duration：普通聊天 thinking 为 disabled，
            // 该列在本读取面（thinkingDuration）承载"消息耗时"，值=模型步墙钟毫秒，见 ConversationTurnMeta。
            message.setModelName(meta.modelName());
            message.setTotalTokens(meta.totalTokens());
            message.setThinkingDuration(meta.durationMs());
        }
        return message;
    }
}
