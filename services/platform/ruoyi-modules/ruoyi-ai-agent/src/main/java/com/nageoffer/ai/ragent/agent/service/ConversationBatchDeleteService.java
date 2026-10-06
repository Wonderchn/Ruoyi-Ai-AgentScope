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

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 受控批量删除（C4 / D05）。**这是服务端契约的唯一实现点，不是"前端多选后循环调单删"。**
 *
 * <p><b>为什么不能"循环调单资源删除"。</b>单资源删除逐条授权、逐条提交，满足的是"每条各自合法"，
 * 而批量删除的风险恰恰在**集合**上：① 一次请求能删多少（无界 → 一次请求抹掉整个租户）；
 * ② 中间一条失败时前面已经删掉了（部分成功 → 客户端无法重试、也无法回滚）；
 * ③ 拿一个单资源 permit 去授权 N 个对象（授权覆盖范围 ≠ 实际动作范围）。
 * 本类把这三件事分别钉死。
 *
 * <p><b>契约逐条落点（C4）。</b>
 * <ol>
 *   <li><b>同租户内有界集合，初始上限 100</b>：{@link #MAX_BATCH}；超限<b>拒绝</b>（不是截断）。</li>
 *   <li><b>空集合拒绝</b>：{@code null}/空/全空白一律 400。既有
 *       {@code AgentConversationServiceImpl.deleteBatch} 对空集合是 {@code return}（静默成功），
 *       那会让客户端把"没删任何东西"当成"删成功"—— 这里改为拒绝，并在下层同步收紧。</li>
 *   <li><b>重复 ID 拒绝</b>：既有实现对重复是 {@code distinct()} 静默去重。重复 ID 通常意味着
 *       调用方算错了集合（或把同一个 permit 复用到了两个位置），静默去重把这类错误藏起来，
 *       而"实际动作集合 ≠ 请求集合"正是本包要防的形态。</li>
 *   <li><b>全部资源先校验；任一不可访问则整体失败，无部分成功</b>：可见性检查在**取得任何 permit
 *       之前**全部做完；随后才进入单事务删除。因此失败路径上数据库**一行都没动**。</li>
 *   <li><b>逐资源 permit，按确定顺序取得</b>：资源按字典序排序后逐个 {@code enter(...)}，
 *       而不是"复用一个单资源 permit"——后者会让授权范围与动作范围不一致（D05 明文禁止）。
 *       释放按**取得的逆序**，与获取顺序对称，便于并发死锁分析。</li>
 *   <li><b>复核 epoch</b>：进入前捕获主体的 {@code policyVersion}/{@code aclVersion}，
 *       在真正写入之前**再读一次当前主体**并要求两值不变。若期间发生策略/ACL 版本变更，
 *       则本次批量已经不再是在"当初被授权的那套事实"下执行 → 整体放弃（已取得的 permit 全部释放），
 *       而不是"授权变了一半但照删"。</li>
 * </ol>
 *
 * <p><b>为什么放在服务层而不是控制器层。</b>授权与事务必须与请求路径在**同一边界内闭合**。
 * 把它们写在控制器里，任何新的调用方（后台任务、重试器、内部脚本）都能绕过；
 * 写成服务后，"能不能删"与"删什么"由同一个方法给出，无法只满足一半。
 */
public class ConversationBatchDeleteService {

    /** C4：初始上限 100。超限**拒绝**，不截断（截断会让客户端以为删了全部）。 */
    public static final int MAX_BATCH = 100;

    /** 删除动作名：与网关 `conversation.delete` 路由动作一致（C3/C13.4 口径）。 */
    static final String DELETE_ACTION = "conversation.delete";

    private final AgentConversationService conversations;
    private final ObjectProvider<RevocationGuard> revocations;

    public ConversationBatchDeleteService(AgentConversationService conversations,
                                          ObjectProvider<RevocationGuard> revocations) {
        this.conversations = conversations;
        this.revocations = revocations;
    }

    /**
     * 一次批量删除的结果。
     *
     * @param deletedCount 实际删除的会话数（恒等于**去重后**的请求集合大小，因为重复已被拒绝）
     * @param permitCount  本次取得并释放的 permit 数（= 资源数，用于自证"没有复用一个 permit"）
     */
    public record Outcome(int deletedCount, int permitCount) {
    }

    /**
     * 执行受控批量删除。主体取自 {@link PrincipalContext}（内嵌态由传输层桥接）。
     *
     * @throws P04AiException 400（空集合 / 超限 / 重复 / 空白 ID）、404（资源不可见）、
     *                        503（permit 基础设施不可用）
     */
    public Outcome deleteAll(List<String> conversationIds) {
        ExecutionPrincipal principal = PrincipalContext.get();
        if (principal == null) {
            // 缺主体不得退化成"无用户限定"（C6）
            throw new P04AiException(P04AiErrorCode.AUTH_REQUIRED);
        }

        List<String> ordered = requireWellFormedSet(conversationIds);
        String userId = principal.userId();

        // ④ 先校验全部资源：任一不可见即整体失败，且此时尚未取得 permit、尚未动数据库。
        for (String conversationId : ordered) {
            if (!conversations.existsForUser(conversationId, userId)) {
                throw new P04AiException(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
            }
        }

        // ⑥ 复核 epoch：记录进入时的授权版本
        int policyVersion = principal.policyVersion();
        int aclVersion = principal.aclVersion();

        List<RevocationGuard.Operation> acquired = new ArrayList<>(ordered.size());
        try {
            // ⑤ 按确定顺序逐资源取得 permit
            for (String conversationId : ordered) {
                acquired.add(permit(conversationId));
            }

            // ⑥ 写入前再读一次主体：策略/ACL 版本变了就整体放弃
            ExecutionPrincipal current = PrincipalContext.get();
            if (current == null
                    || current.policyVersion() != policyVersion
                    || current.aclVersion() != aclVersion) {
                throw new P04AiException(P04AiErrorCode.POLICY_VERSION_STALE);
            }

            // 单事务删除：整批要么都删掉，要么一行都不动
            conversations.deleteBatch(ordered, userId);
            return new Outcome(ordered.size(), acquired.size());
        } finally {
            // 逆序释放，与取得顺序对称
            for (int i = acquired.size() - 1; i >= 0; i--) {
                acquired.get(i).close();
            }
        }
    }

    /**
     * 集合形状校验（C4 前三条）。
     *
     * <p>返回**排序后**的列表：顺序确定是"逐资源 permit 可复核"的前提
     * （否则同一集合的两次请求会以不同顺序取 permit，并发图无法比较）。
     */
    static List<String> requireWellFormedSet(List<String> conversationIds) {
        if (conversationIds == null || conversationIds.isEmpty()) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST);
        }
        if (conversationIds.size() > MAX_BATCH) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST);
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String conversationId : conversationIds) {
            if (conversationId == null || conversationId.isBlank()) {
                throw new P04AiException(P04AiErrorCode.BAD_REQUEST);
            }
            if (!seen.add(conversationId)) {
                throw new P04AiException(P04AiErrorCode.BAD_REQUEST);
            }
        }
        // TreeSet 提供确定顺序；重复已在上面被拒绝，所以这里不会静默吞掉任何元素。
        return List.copyOf(new TreeSet<>(seen));
    }

    private RevocationGuard.Operation permit(String conversationId) {
        RevocationGuard guard = revocations.getIfAvailable();
        if (guard == null) {
            // 没有屏障就没有交付/撤权语义：拒绝，不做无屏障的批量删除
            throw new P04AiException(P04AiErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
        return guard.enter(PrincipalContext.get(), DELETE_ACTION, "conv:" + conversationId);
    }
}
