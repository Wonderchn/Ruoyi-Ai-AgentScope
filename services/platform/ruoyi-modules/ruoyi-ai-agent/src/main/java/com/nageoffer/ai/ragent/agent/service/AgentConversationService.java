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

package com.nageoffer.ai.ragent.agent.service;

import com.nageoffer.ai.ragent.agent.controller.vo.AgentConversationVO;
import com.nageoffer.ai.ragent.agent.controller.vo.AgentMessageVO;
import com.nageoffer.ai.ragent.agent.dto.AgentBlock;
import com.nageoffer.ai.ragent.agent.dto.AgentConfirmSettlement;
import com.nageoffer.ai.ragent.agent.enums.AgentMessageStatus;

import java.util.List;

/**
 * Agent 会话管理：建会话、消息读写、确认卡片结算、删除
 */
public interface AgentConversationService {

    /**
     * 首问建会话（截断问题作标题），已存在则刷新最后活动时间，返回会话标题
     */
    String touchConversation(String conversationId, String userId, String question);

    /**
     * 保存用户消息
     */
    String addUserMessage(String conversationId, String userId, String content);

    /**
     * 保存助手消息
     */
    String addAssistantMessage(String conversationId, String userId, String content, String thinkingContent,
                               List<AgentBlock> blocks, String replyToMessageId, AgentMessageStatus status,
                               Long durationMs);

    /**
     * 结算挂起的确认卡片：卡片状态改 approved/denied，消息状态改回 NORMAL
     */
    AgentConfirmSettlement settlePendingConfirm(String conversationId, String userId, String messageId, boolean approved);

    /**
     * 续跑前的检查：卡片还挂在待确认状态才放行，这里只读不落库
     * 把卡片改成终态是 settlePendingConfirm 的事，要等流真的启动那一刻才做
     */
    AgentConfirmSettlement getPendingConfirm(String conversationId, String userId, String messageId);

    /**
     * Agent 状态里已无待确认工具，但卡片还是 pending，标记为 expired 以解除会话阻塞
     */
    void expirePendingConfirm(String conversationId, String userId, String messageId);

    /**
     * 该会话是否有待确认的操作
     */
    boolean hasPendingConfirm(String conversationId, String userId);

    /**
     * 查询用户的会话列表
     */
    List<AgentConversationVO> listByUserId(String userId);

    /**
     * 查询会话消息列表
     */
    List<AgentMessageVO> listMessages(String conversationId, String userId);

    /**
     * 手动改标题，空白标题拒绝
     */
    void rename(String conversationId, String userId, String title);

    /**
     * 新建会话（F03 / G-52），返回新的 {@code conversationId}。
     *
     * <p><b>为什么入参只有标题。</b>归属（tenant / member / user）只能来自可信执行主体：
     * 运行时写服务 {@code AiResourceWriteService#createConversation} 在 {@code PrincipalContext}
     * 上解析归属，并在同一条 {@code write(...)} 路径里写业务行 + registry + owner ACL + epoch bump。
     * 本方法**不接受**任何归属字段，也**不自己拼授权事实** —— 多一个 {@code userId} 入参只会让人
     * 以为"换个 userId 就能建到别人名下"，而归属根本不看它。
     *
     * <p>空白标题由实现按 {@link #rename} 的同一口径拒绝。
     */
    String create(String title);

    /**
     * 删除会话、消息及对应的 Agent 状态
     */
    void delete(String conversationId, String userId);

    /**
     * 该会话对当前主体**可见**（同租户 + 同成员 + 同用户）？
     *
     * <p><b>为什么批量删除需要这个显式读。</b>{@link #delete} 走的是"按谓词 DELETE"，
     * 会话不存在时 SQL 影响 0 行、**不抛异常**。单资源删除下这是合理的（幂等），
     * 但批量删除必须先回答"整批是否都可访问"——否则一次请求里混进一个别人的 ID，
     * 结果会是"其余都删掉了、那个静默跳过"，即 C4 明文禁止的**部分成功**。
     * 因此批量路径先逐个做可见性判定，再进入事务。
     */
    boolean existsForUser(String conversationId, String userId);

    /**
     * 批量删除，逐条走 delete 保证状态清理不漏。
     *
     * <p><b>调用前置条件（由 {@code ConversationBatchDeleteService} 保证，这里做防御性复核）。</b>
     * 集合非空、已去重、并且**全部资源都已通过可见性判定**。空集合在这里是<b>拒绝</b>而不是
     * 静默返回：静默返回会让客户端把"没删任何东西"当成成功。
     */
    void deleteBatch(List<String> conversationIds, String userId);
}
