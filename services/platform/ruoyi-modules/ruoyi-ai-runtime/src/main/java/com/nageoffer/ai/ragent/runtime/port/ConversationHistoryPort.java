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

package com.nageoffer.ai.ragent.runtime.port;

/**
 * 会话历史（{@code platform.ai_message}）追加端口 —— RW-01-CHATHIST。
 *
 * <h2>为什么需要这个端口（而不是直接调服务）</h2>
 * 缺陷是"写入表与读取表不是同一张"：{@code RagChatExecutor} 只写 {@code ai_chat_message}
 * （run 作用域，{@code (tenant_id, run_id, sequence)} 唯一，供恢复时回读答案），
 * 而 {@code GET /api/ai/v1/conversations/{id}/messages} 读 {@code ai_message}
 * （会话作用域，按 {@code conversation_id + user_id} 过滤）⇒ 受理成功但刷新看不到该轮。
 *
 * <p>直觉修法是让执行器直接调会话消息服务，但那会**成环**：会话消息服务在
 * {@code ruoyi-ai-rag}，而 {@code ruoyi-ai-rag} 依赖 {@code ruoyi-ai-runtime}
 * （已实测：在 ai-runtime 里直接 import {@code com.nageoffer.ai.ragent.rag.service.*}
 * 编译失败）。因此按本仓既有的端口约定（{@code RuntimeModelGatewayPort}、
 * {@code RunConfigBindingPort}、{@code PlatformFactsPort}）反向声明：
 * <b>执行器依赖端口，实现留在 ai-rag</b>。这样依赖方向不变，且"没接线"在执行器使用点
 * fail-closed 可见，而不是静默不落历史。
 *
 * <h2>身份契约（安全边界，实现方必须遵守）</h2>
 * 三个身份参数是<b>run 行的权威事实</b>（受理时由真实主体写入的
 * {@code tenant_id}/{@code member_id}/{@code subject}），由执行器传入；
 * 实现方<b>不得</b>从请求体/载荷里取身份，也不得为空时"退化成无租户/无成员查询"。
 * 成员标识须与 {@code ExecutionPrincipal} 的 canonical 形式一致
 * （{@code platform:<tenantId>:<userId>}），否则统一库 {@code ai_message.member_id}
 * 会与 {@code fk_message_conversation} 对不上。
 *
 * <h2>幂等</h2>
 * 端口<b>不</b>承诺幂等：调用方（执行器）用 run 步骤栅栏保证
 * "同一次执行只追加一轮"。实现方若自行去重，须以 {@code (tenantId, conversationId, 内容哈希)}
 * 之类稳定键为据，不得用随机键。
 */
public interface ConversationHistoryPort {

    /**
     * 把一轮问答追加进会话历史：先用户消息，再助手消息（助手消息回指用户消息）。
     *
     * @param tenantId       租户标识（run 行权威事实）
     * @param memberId       canonical 成员标识（run 行权威事实）
     * @param userId         用户标识（run 行权威事实）
     * @param conversationId 会话标识（受理载荷携带；本端口不校验其归属，归属由实现方按
     *                       tenant+user 限域校验，查不到即拒绝，不得跨租户写入）
     * @param question       用户问题原文
     * @param answer         助手回答原文
     * @throws IllegalStateException 归属校验不通过或写入失败（fail-closed，不静默跳过）
     */
    void appendTurn(String tenantId, String memberId, String userId, String conversationId,
                    String question, String answer);
}
