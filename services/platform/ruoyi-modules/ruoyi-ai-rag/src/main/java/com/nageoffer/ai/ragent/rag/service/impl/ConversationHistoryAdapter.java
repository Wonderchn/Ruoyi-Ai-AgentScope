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
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import com.nageoffer.ai.ragent.rag.service.ConversationMessageService;
import com.nageoffer.ai.ragent.rag.service.bo.ConversationMessageBO;
import com.nageoffer.ai.ragent.runtime.port.ConversationHistoryPort;
import com.nageoffer.ai.ragent.runtime.port.ConversationTurnMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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

    /**
     * 租户作用域绑定端口（P0 修，s2-chathist-a2；与 ENV-B5b 的
     * {@code MessageFeedbackConsumer} 同一端口、同一纪律）。
     *
     * <p><b>为什么必须有。</b>内嵌部署里 platform 的 MyBatis 租户行拦截器
     * （{@code tenant.enable=true}，{@code PlusTenantLineHandler}）对业务表
     * {@code ai_message} fail-closed：执行器跑在 p2-worker 线程上，<b>没有登录主体、
     * 也没有动态租户</b>，于是 {@code ConversationMessageMapper.insert} 在任何数据库操作
     * <b>之前</b>被拒（真机 r17/p1win AC4 f03 原文：{@code tenant.context.missing}
     * ＋{@code The error may involve ConversationMessageMapper.insert} ⇒ 带 conversationId
     * 的 run 直接 FAILED、ai_message 0 行）。身份（{@code PrincipalContext}）与租户是
     * <b>两道独立的门</b>：只绑身份不够。
     *
     * <p><b>绑定值的来源。</b>端口入参的 {@code tenantId} 由执行器从 <b>run 行</b>权威事实
     * （{@code RunExecution.tenantId()}）取得——不是请求体里的任何值。绑定范围最小：
     * 只包围本方法的两条写入（进入时绑定、退出时清理由端口实现保证，同
     * {@code TenantHelper.dynamic} 的 {@code finally} 语义）。
     *
     * <p><b>端口缺席。</b>独立应用/无租户拦截器的形态没有本端口 ⇒ 按原形态直接执行
     * （与 {@code MessageFeedbackConsumer} 的"端口缺席＝无拦截器"口径一致）。
     * 缺席+有拦截器时不静默放行——写入会在租户门处响亮失败（fail-closed 不劣化）。
     *
     * <p><b>注入方式。</b>字段注入而非构造参数：构造签名被内嵌装配点与既有判据按位置使用
     * （同 {@code RagChatExecutor} 的可选端口处理），本包只叠加这一处最小修改。
     */
    @Autowired(required = false)
    private ObjectProvider<TenantScopeBinder> tenantScopeBinder;

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
            writeTurn(tenantId, conversationId, userId, question, answer, facts);
        } finally {
            PrincipalContext.restore(previous);
        }
    }

    /**
     * 写入本轮的两条消息（用户在前、助手回指）；<b>先身份、后租户</b>——租户经
     * {@link TenantScopeBinder} 端口在身份作用域内侧绑定，绑定范围最小、只包围这次写。
     *
     * <p>为什么租户也要绑：platform 的 MyBatis 租户行拦截器（{@code tenant.enable=true}）
     * 对业务表 {@code ai_message} 要求可信租户上下文，而 worker 线程没有登录主体；
     * 只绑 {@code PrincipalContext}（身份门）过不了第二道门（租户门），这正是
     * s2-chathist-a2 修的真机缺陷。租户值只认端口入参（run 行权威事实，空白在上游
     * 已被拒绝）；端口缺席（独立应用无拦截器）时按原形态直跑——不新增默认租户、
     * 不做 ignore 豁免。
     */
    private void writeTurn(String tenantId, String conversationId, String userId,
                           String question, String answer, ConversationTurnMeta facts) {
        ObjectProvider<TenantScopeBinder> provider = tenantScopeBinder;
        TenantScopeBinder binder = provider == null ? null : provider.getIfAvailable();
        Runnable write = () -> {
            String userMessageId = conversationMessageService.addMessage(
                    message(conversationId, userId, "user", question, null, null));
            conversationMessageService.addMessage(
                    message(conversationId, userId, "assistant", answer, userMessageId, facts));
        };
        if (binder == null) {
            write.run();
            return;
        }
        binder.runWithTenant(tenantId, write);
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
