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

package com.nageoffer.ai.ragent.agent.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.config.ReActAgentProvider;
import com.nageoffer.ai.ragent.agent.controller.vo.AgentConversationVO;
import com.nageoffer.ai.ragent.agent.controller.vo.AgentMessageVO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentConversationDO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentConversationMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.agent.dto.AgentBlock;
import com.nageoffer.ai.ragent.agent.dto.AgentConfirmSettlement;
import com.nageoffer.ai.ragent.agent.enums.AgentMessageStatus;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.agent.service.ConversationBatchDeleteService;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunGate;
import com.nageoffer.ai.ragent.agent.state.PgAgentStateStore;
import com.nageoffer.ai.ragent.authorization.AiResourceWriteService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agent 会话管理实现
 *
 * <p>P1.3d：会话/消息路径租户贯通。ai_agent_conversation / ai_agent_message 在 V3 加了
 * tenant_id / member_id、V4 起 NOT NULL，所有读写恒带 (tenant_id, member_id) 条件：
 * <ul>
 *   <li>每个公开入口先 {@link #scope()} 从可信执行主体解析归属，没有主体即
 *       {@link ClientException} 拒绝（{@link PrincipalContext#require()} 的既定语义），
 *       不存在匿名回退；</li>
 *   <li>查询一律显式 eq 租户与成员，不依赖 wrapper 之外的全局拦截器；</li>
 *   <li>更新/删除按 id + tenant + member 双重条件：代理主键全局唯一，
 *       但 id 是可能被日志、导出等旁路带出的引用，隔离谓词必须进 WHERE；</li>
 *   <li>user_id 仍是入参（平台用户展示/legacy 引用），权威主体引用是 member_id。</li>
 * </ul>
 */
@Slf4j
@Service
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentConversationServiceImpl implements AgentConversationService {

    private static final int TITLE_MAX_LENGTH = 30;
    private static final int RENAME_MAX_LENGTH = 128;
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String CONFIRM_STATUS_PENDING = "pending";
    private static final String CONFIRM_STATUS_APPROVED = "approved";
    private static final String CONFIRM_STATUS_DENIED = "denied";
    private static final String CONFIRM_STATUS_EXPIRED = "expired";

    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;
    private final PgAgentStateStore agentStateStore;
    private final AgentRunGate runGate;
    /**
     * 延迟获取，避免与 ReActAgentProvider 循环依赖
     */
    private final ObjectProvider<ReActAgentProvider> agentProviderRef;
    /**
     * 运行时写服务（G-52 会话创建的唯一写路径）。
     *
     * <p>同样是延迟获取：独立部署形态下可能没有这个 bean，硬注入会把"缺一个可选协作方"
     * 变成启动失败；缺 bean 时在 {@link #create(String)} 的调用点明确拒绝。
     */
    private final ObjectProvider<AiResourceWriteService> resourceWriteServiceRef;

    @Override
    public String touchConversation(String conversationId, String userId, String question) {
        Scope scope = scope();
        AgentConversationDO existing = selectConversation(scope, conversationId, userId);
        if (existing != null) {
            return touchLastTime(existing);
        }
        purgeResidue(scope, conversationId, userId);

        // v1 简化：截断首问作标题，不走 LLM 生成
        String title = StrUtil.sub(StrUtil.emptyIfNull(question).trim(), 0, TITLE_MAX_LENGTH);
        // 归属只来自执行主体：调用方传入的 userId 仅作展示引用，不参与归属判定
        AgentConversationDO conversation = AgentConversationDO.builder()
                .tenantId(scope.tenantId())
                .memberId(scope.memberId())
                .conversationId(conversationId)
                .userId(userId)
                .title(title)
                .lastTime(new Date())
                .build();
        try {
            conversationMapper.insert(conversation);
        } catch (DuplicateKeyException dke) {
            // 并发首问唯一键冲突，重查已有记录
            AgentConversationDO winner = selectConversation(scope, conversationId, userId);
            if (winner == null) {
                throw dke;
            }
            return touchLastTime(winner);
        }
        return title;
    }

    private AgentConversationDO selectConversation(Scope scope, String conversationId, String userId) {
        return conversationMapper.selectOne(conversationScope(scope, userId)
                .eq(AgentConversationDO::getConversationId, conversationId));
    }

    private String touchLastTime(AgentConversationDO conversation) {
        conversation.setLastTime(new Date());
        updateConversationScoped(conversation);
        return conversation.getTitle();
    }

    /**
     * 会话行不存在但同 ID 还残留状态/消息时，先清理再建新会话
     */
    private void purgeResidue(Scope scope, String conversationId, String userId) {
        // 状态存储自己在 DAO 访问前解析主体（键含 tenant/member），这里只给会话参数
        agentStateStore.delete(userId, conversationId);
        messageMapper.delete(messageScope(scope, userId)
                .eq(AgentMessageDO::getConversationId, conversationId));
        evictStateCache(userId, conversationId);
    }

    @Override
    public String addUserMessage(String conversationId, String userId, String content) {
        Scope scope = scope();
        AgentMessageDO message = AgentMessageDO.builder()
                .tenantId(scope.tenantId())
                .memberId(scope.memberId())
                .conversationId(conversationId)
                .userId(userId)
                .role(ROLE_USER)
                .content(content)
                .messageStatus(AgentMessageStatus.NORMAL.name())
                .build();
        messageMapper.insert(message);
        return message.getId();
    }

    @Override
    public String addAssistantMessage(String conversationId, String userId, String content, String thinkingContent,
                                      List<AgentBlock> blocks, String replyToMessageId, AgentMessageStatus status,
                                      Long durationMs) {
        Scope scope = scope();
        AgentMessageDO message = AgentMessageDO.builder()
                .tenantId(scope.tenantId())
                .memberId(scope.memberId())
                .conversationId(conversationId)
                .userId(userId)
                .role(ROLE_ASSISTANT)
                .content(content)
                .thinkingContent(StrUtil.blankToDefault(thinkingContent, null))
                .blocks(blocks)
                .replyToMessageId(replyToMessageId)
                .messageStatus(status.name())
                .durationMs(durationMs)
                .build();
        messageMapper.insert(message);
        return message.getId();
    }

    @Override
    public AgentConfirmSettlement getPendingConfirm(String conversationId, String userId, String messageId) {
        Scope scope = scope();
        AgentConversationDO conversation = selectConversation(scope, conversationId, userId);
        if (conversation == null) {
            throw new ClientException("会话不存在");
        }
        PendingConfirm pending = selectPendingConfirm(scope, conversationId, userId, messageId);
        if (pending == null) {
            throw new ClientException("待确认的操作不存在或已处理");
        }
        return new AgentConfirmSettlement(conversation.getTitle(), pending.message().getReplyToMessageId());
    }

    @Override
    public AgentConfirmSettlement settlePendingConfirm(String conversationId, String userId,
                                                      String messageId, boolean approved) {
        Scope scope = scope();
        AgentConversationDO conversation = selectConversation(scope, conversationId, userId);
        if (conversation == null) {
            throw new ClientException("会话不存在");
        }
        AgentMessageDO message = settleConfirmBlock(scope, conversationId, userId, messageId,
                approved ? CONFIRM_STATUS_APPROVED : CONFIRM_STATUS_DENIED);
        if (message == null) {
            throw new ClientException("待确认的操作不存在或已处理");
        }
        return new AgentConfirmSettlement(conversation.getTitle(), message.getReplyToMessageId());
    }

    @Override
    public void expirePendingConfirm(String conversationId, String userId, String messageId) {
        // 卡片标记失效，没找到说明已被结算过
        if (settleConfirmBlock(scope(), conversationId, userId, messageId, CONFIRM_STATUS_EXPIRED) != null) {
            log.warn("待确认卡片已失效，标记结算, conversationId: {}, messageId: {}", conversationId, messageId);
        }
    }

    /**
     * 把挂起的确认卡片改写成终态并落库，返回结算后的消息，没有可结算的卡片返回 null
     */
    private AgentMessageDO settleConfirmBlock(Scope scope, String conversationId, String userId,
                                              String messageId, String blockStatus) {
        PendingConfirm pending = selectPendingConfirm(scope, conversationId, userId, messageId);
        if (pending == null) {
            return null;
        }
        pending.block().setStatus(blockStatus);
        // 卡片有了终态，消息改回 NORMAL 以解除新提问的阻塞
        AgentMessageDO message = pending.message();
        message.setMessageStatus(AgentMessageStatus.NORMAL.name());
        updateMessageScoped(message);
        return message;
    }

    /**
     * 查出仍挂着 pending 确认卡片的消息，连同卡片块一起返回，没有则返回 null
     */
    private PendingConfirm selectPendingConfirm(Scope scope, String conversationId, String userId,
                                                String messageId) {
        AgentMessageDO message = messageMapper.selectOne(messageScope(scope, userId)
                .eq(AgentMessageDO::getId, messageId)
                .eq(AgentMessageDO::getConversationId, conversationId));
        if (message == null || !AgentMessageStatus.AWAITING_CONFIRM.name().equals(message.getMessageStatus())) {
            return null;
        }
        AgentBlock block = findPendingConfirmBlock(message);
        return block == null ? null : new PendingConfirm(message, block);
    }

    /**
     * 一次查询查出的两样东西：待确认的那条消息，和它里面那张 pending 卡片
     */
    private record PendingConfirm(AgentMessageDO message, AgentBlock block) {
    }

    @Override
    public boolean hasPendingConfirm(String conversationId, String userId) {
        return messageMapper.exists(messageScope(scope(), userId)
                .eq(AgentMessageDO::getConversationId, conversationId)
                .eq(AgentMessageDO::getMessageStatus, AgentMessageStatus.AWAITING_CONFIRM.name()));
    }

    private static AgentBlock findPendingConfirmBlock(AgentMessageDO message) {
        if (CollUtil.isEmpty(message.getBlocks())) {
            return null;
        }
        return message.getBlocks().stream()
                .filter(block -> AgentBlock.KIND_CONFIRM.equals(block.getKind()))
                .filter(block -> CONFIRM_STATUS_PENDING.equals(block.getStatus()))
                .findFirst()
                .orElse(null);
    }

    @Override
    public List<AgentConversationVO> listByUserId(String userId) {
        Scope scope = scope();
        List<AgentConversationDO> conversations = conversationMapper.selectList(
                conversationScope(scope, userId)
                        .orderByDesc(AgentConversationDO::getLastTime));
        Map<String, Long> turnCounts = countTurns(scope, conversations, userId);
        return conversations.stream()
                .map(item -> AgentConversationVO.builder()
                        .conversationId(item.getConversationId())
                        .title(item.getTitle())
                        .lastTime(item.getLastTime())
                        .turns(turnCounts.getOrDefault(item.getConversationId(), 0L).intValue())
                        .build())
                .toList();
    }

    /**
     * 按会话统计用户提问数，一次 groupBy 避免 N+1
     */
    private Map<String, Long> countTurns(Scope scope, List<AgentConversationDO> conversations, String userId) {
        if (conversations.isEmpty()) {
            return Map.of();
        }
        List<String> ids = conversations.stream().map(AgentConversationDO::getConversationId).toList();
        QueryWrapper<AgentMessageDO> query = new QueryWrapper<AgentMessageDO>()
                .select("conversation_id", "COUNT(*) AS turn_count")
                .eq("tenant_id", scope.tenantId())
                .eq("member_id", scope.memberId())
                .eq("user_id", userId)
                .eq("role", ROLE_USER)
                .in("conversation_id", ids)
                .groupBy("conversation_id");
        return messageMapper.selectMaps(query).stream()
                .collect(Collectors.toMap(
                        row -> String.valueOf(row.get("conversation_id")),
                        row -> ((Number) row.get("turn_count")).longValue()));
    }

    @Override
    public void rename(String conversationId, String userId, String title) {
        String trimmed = StrUtil.trimToEmpty(title);
        if (trimmed.isEmpty()) {
            throw new ClientException("会话标题不能为空");
        }
        AgentConversationDO conversation = selectConversation(scope(), conversationId, userId);
        if (conversation == null) {
            throw new ClientException("会话不存在");
        }
        conversation.setTitle(StrUtil.sub(trimmed, 0, RENAME_MAX_LENGTH));
        updateConversationScoped(conversation);
    }

    /**
     * 新建会话（F03 / G-52）：只做标题规范化，然后**委托运行时**写服务。
     *
     * <p><b>为什么不在这里写库。</b>会话创建要同时落业务行、registry（{@code ai_resource}）、
     * owner ACL 与 epoch bump，并且必须经过 G-40 {@code ai.integration.high-risk.enabled} 守卫。
     * 这条路径已经存在于 {@code AiResourceWriteService#createConversation}，且是守卫的**唯一**经过点。
     * 在这里另写一份 INSERT 等于制造第二条写路径：其中一条迟早漏掉 registry/ACL/epoch，
     * 而"漏掉 registry"的表现恰好就是判据③的"创建后在列表里看不见"。
     *
     * <p><b>为什么用 {@link ObjectProvider} 而不是构造器硬注入。</b>本模块（Agent 引擎面）在
     * 独立部署形态下可能没有运行时写服务这个 bean；硬注入会把"缺一个可选协作方"变成**启动失败**。
     * 本文件对可选协作方（{@code ReActAgentProvider}）用的就是同一手法，这里保持一致：
     * 缺 bean 时在**调用点**给出明确拒绝，而不是让整个应用起不来。
     *
     * <p><b>归属只来自主体。</b>本方法不接收、不转发任何归属字段；{@code createConversation}
     * 自己从 {@code PrincipalContext} 解析 tenant / member / user。也不做任何授权判定 ——
     * 功能级判定在入口（控制器/surface），资源级与新资源 owner ACL 在运行时那条 {@code write(...)} 里。
     */
    @Override
    public String create(String title) {
        String trimmed = StrUtil.trimToEmpty(title);
        if (trimmed.isEmpty()) {
            throw new ClientException("会话标题不能为空");
        }
        AiResourceWriteService writer = resourceWriteServiceRef.getIfAvailable();
        if (writer == null) {
            throw new ClientException("会话创建在当前装配下不可用：缺少运行时写服务");
        }
        return writer.createConversation(
                new AiResourceWriteService.ConversationDraft(StrUtil.sub(trimmed, 0, RENAME_MAX_LENGTH)));
    }

    @Override
    public List<AgentMessageVO> listMessages(String conversationId, String userId) {
        return messageMapper.selectList(messageScope(scope(), userId)
                        .eq(AgentMessageDO::getConversationId, conversationId)
                        .orderByAsc(AgentMessageDO::getId))
                .stream()
                .map(item -> AgentMessageVO.builder()
                        .id(item.getId())
                        .role(item.getRole())
                        .content(item.getContent())
                        .thinkingContent(item.getThinkingContent())
                        .blocks(item.getBlocks())
                        .messageStatus(item.getMessageStatus())
                        .durationMs(item.getDurationMs())
                        .createTime(item.getCreateTime())
                        .build())
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String conversationId, String userId) {
        Scope scope = scope();
        // 在途流收尾会把状态和消息写回来，必须先拦住
        if (runGate.runningTaskId(userId, conversationId) != null) {
            throw new ClientException("该会话消息正在生成中，请先停止后再删除");
        }
        conversationMapper.delete(conversationScope(scope, userId)
                .eq(AgentConversationDO::getConversationId, conversationId));
        messageMapper.delete(messageScope(scope, userId)
                .eq(AgentMessageDO::getConversationId, conversationId));
        // Agent 状态同库，随事务一起删（存储内部自解析主体，键含 tenant/member）
        agentStateStore.delete(userId, conversationId);
        // 提交后再清内存缓存和停止在途流
        afterCommit(() -> evictStateCache(userId, conversationId));
    }

    @Override
    public boolean existsForUser(String conversationId, String userId) {
        if (conversationId == null || conversationId.isBlank()) {
            return false;
        }
        Scope scope = scope();
        Long count = conversationMapper.selectCount(conversationScope(scope, userId)
                .eq(AgentConversationDO::getConversationId, conversationId));
        return count != null && count > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteBatch(List<String> conversationIds, String userId) {
        // 防御性复核（契约由 ConversationBatchDeleteService 保证，这里不允许绕过）：
        // 空集合**拒绝**而不是静默返回——静默返回会让客户端把"没删任何东西"当成成功。
        if (conversationIds == null || conversationIds.isEmpty()) {
            throw new ClientException("批量删除的会话集合不能为空");
        }
        // 重复 ID **拒绝**而不是 distinct() 静默去重：静默去重会把"实际动作集合 ≠ 请求集合"
        // 藏起来，而调用方通常正是据此算 permit 数量与复核数的。
        if (new java.util.HashSet<>(conversationIds).size() != conversationIds.size()) {
            throw new ClientException("批量删除的会话集合不能包含重复 ID");
        }
        if (conversationIds.size() > ConversationBatchDeleteService.MAX_BATCH) {
            throw new ClientException("批量删除的会话数不能超过 " + ConversationBatchDeleteService.MAX_BATCH);
        }
        // 自调用不经代理，各会话的删除逻辑并入当前事务
        conversationIds.forEach(id -> delete(id, userId));
    }

    private void evictStateCache(String userId, String conversationId) {
        ReActAgentProvider agentProvider = agentProviderRef.getIfAvailable();
        if (agentProvider != null) {
            agentProvider.evictStateCache(userId, conversationId);
        }
    }

    // ---------------------------------------------------------- 租户谓词与更新

    /** 会话表查询谓词：租户 + 成员恒在，再由调用方叠加会话级条件。 */
    private static LambdaQueryWrapper<AgentConversationDO> conversationScope(Scope scope, String userId) {
        return Wrappers.lambdaQuery(AgentConversationDO.class)
                .eq(AgentConversationDO::getTenantId, scope.tenantId())
                .eq(AgentConversationDO::getMemberId, scope.memberId())
                .eq(AgentConversationDO::getUserId, userId);
    }

    /** 消息表查询谓词：同上。 */
    private static LambdaQueryWrapper<AgentMessageDO> messageScope(Scope scope, String userId) {
        return Wrappers.lambdaQuery(AgentMessageDO.class)
                .eq(AgentMessageDO::getTenantId, scope.tenantId())
                .eq(AgentMessageDO::getMemberId, scope.memberId())
                .eq(AgentMessageDO::getUserId, userId);
    }

    /**
     * 按 id + 租户显式条件更新会话行：{@code updateById} 只按代理主键定位，
     * id 一旦被旁路带出（日志/导出）就能改到他租户的行，隔离谓词必须进 WHERE。
     * SET 只放业务字段，代理主键与归属列不参与 SET。
     */
    private void updateConversationScoped(AgentConversationDO conversation) {
        conversationMapper.update(AgentConversationDO.builder()
                        .title(conversation.getTitle())
                        .lastTime(conversation.getLastTime())
                        .build(),
                Wrappers.lambdaUpdate(AgentConversationDO.class)
                        .eq(AgentConversationDO::getId, conversation.getId())
                        .eq(AgentConversationDO::getTenantId, conversation.getTenantId())
                        .eq(AgentConversationDO::getMemberId, conversation.getMemberId()));
    }

    /**
     * 按 id + 租户显式条件更新消息行，同 {@link #updateConversationScoped}。
     * SET 只放卡片结算要动的两列：终态卡片块与消息状态。
     */
    private void updateMessageScoped(AgentMessageDO message) {
        messageMapper.update(AgentMessageDO.builder()
                        .messageStatus(message.getMessageStatus())
                        .blocks(message.getBlocks())
                        .build(),
                Wrappers.lambdaUpdate(AgentMessageDO.class)
                        .eq(AgentMessageDO::getId, message.getId())
                        .eq(AgentMessageDO::getTenantId, message.getTenantId())
                        .eq(AgentMessageDO::getMemberId, message.getMemberId()));
    }

    /**
     * 访问 DAO 前解析租户作用域：无执行主体直接拒绝（{@link PrincipalContext#require()}
     * 抛 {@link ClientException}），绝不落库、绝不回退匿名。
     */
    private Scope scope() {
        ExecutionPrincipal principal = PrincipalContext.require();
        return new Scope(principal.tenantId(), principal.membershipId());
    }

    /**
     * @param tenantId 租户（谓词的一部分）
     * @param memberId canonical membershipId（谓词的一部分，权威主体引用）
     */
    private record Scope(String tenantId, String memberId) {
    }

    /**
     * 事务提交后执行，无事务时立即执行
     */
    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
