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

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;

import java.util.List;

/**
 * 授权会话上下文<b>读</b>端口（F16 op1）—— 与 {@link ConversationHistoryPort}（写）成对。
 *
 * <h2>为什么是独立读端口</h2>
 * 写端口只证明"本轮问答落了历史"，不能证明"模型真的消费了历史"。canonical
 * {@code rag.chat} 执行器此前只给模型 system + 本轮证据 user，历史留在
 * {@code platform.ai_message} / run 账本里没有任何消费者 ⇒ {@code rag.memory.historyKeepTurns}
 * 形同虚设。本端口是执行器与"可验证的历史来源"之间唯一的读取面。
 *
 * <h2>契约（实现方必须遵守，这是安全边界）</h2>
 * <ul>
 *   <li><b>非对话轮返回空列表</b>：受理载荷没有 conversationId 的 run 天然没有会话上下文，
 *       返回 {@code List.of()}（唯一合法的"空历史"）。</li>
 *   <li><b>带 conversationId 就必须可验证</b>：归属/授权/依赖任一不可用即 fail-closed，
 *       抛可见异常（不得退化成"空历史"——那会把依赖故障伪装成"没有历史"）。</li>
 *   <li><b>身份来自 run 行</b>：tenant/member/subject 是 run 行的权威事实；实现方不得从
 *       请求体/载荷取身份，也不得放宽为无租户/无成员查询。传入的当前主体必须与 run 行
 *       完全一致，不一致即拒绝。</li>
 *   <li><b>有界</b>：只返回最近的有界轮数（由实现按 historyKeepTurns 之类配置确定），
 *       不是全量历史。</li>
 *   <li><b>历史不是本轮证据</b>：返回的消息只用于指代/连贯性；调用方（执行器）负责在
 *       system 提示里声明这一点，回答仍以本轮授权证据为准。</li>
 *   <li><b>只读</b>：本端口不得写入任何状态（不占用量、不推步骤、不改 run）。</li>
 * </ul>
 */
public interface ConversationContextPort {

    /**
     * 载入当前 run 之前的、已授权且有界的会话历史（按时间正序：旧 → 新）。
     *
     * @param currentRun       当前正在执行的 run 行（身份/会话标识的权威来源）
     * @param currentPrincipal 刚刷新过的执行主体（须与 run 行归属完全一致）
     * @return 旧 → 新排列的历史消息（每轮为 user 问题 + assistant 答案）；无会话时为空列表
     * @throws com.nageoffer.ai.ragent.runtime.RunApiException 归属/授权/来源复核失败或依赖不可用
     */
    List<ChatMessage> load(RunRecord currentRun, ExecutionPrincipal currentPrincipal);
}
