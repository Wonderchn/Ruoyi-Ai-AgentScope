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
 * 一轮问答在会话历史里的补充事实（F03 op3 写侧收口，2026-10-12）。
 *
 * <p><b>为什么单独成 record 而不是继续给 appendTurn 加参数。</b>
 * {@link ConversationHistoryPort#appendTurn} 的身份/正文参数是<b>契约主干</b>
 * （run 行权威身份 + 问答原文，安全边界与幂等语义都在那里）；而本组字段是
 * "读取面已声明、写入面此前为 NULL"的展示事实 —— 模型绑定 / 逐消息用量 / 消息耗时。
 * 分组后：只关心"追加问答"的老调用方继续用 6 参重载（接口默认实现忽略本对象），
 * 执行器在模型步成功后带值调用 7 参重载。
 *
 * <p><b>字段语义与列（{@code platform.ai_message}）逐项对应：</b>
 * <ul>
 *   <li>{@code modelName} → {@code model_name}：该 run 受理时绑定的模型
 *       （来自 {@code RunConfigBinding.modelId()}，不是"某个默认模型"）；</li>
 *   <li>{@code totalTokens} → {@code total_tokens}：提供方 usage 的 total_tokens；
 *       拿不到就是 {@code null} —— "没有记录"和"0 tokens"是两件事，不编 0；</li>
 *   <li>{@code durationMs} → {@code thinking_duration}：本轮模型步墙钟毫秒。
 *       普通聊天 {@code thinking} 是 disabled（无 {@code thinking_content}），本列在 F03
 *       详情读取面（{@code thinkingDuration} + 前端 {@code formatThinkingDuration}）
 *       承载该消息的"耗时"；agent 面写的是真实思考耗时 —— 同一列两种语义的来源
 *       已在切片报告登记（列名是冻结形状的既有命名，本包不改迁移）。</li>
 * </ul>
 *
 * @param modelName   模型绑定（可空：无模型调用的轮次）
 * @param totalTokens 逐消息用量（可空：提供方未返回 usage）
 * @param durationMs  模型步耗时毫秒（可空：无模型调用的轮次）
 */
public record ConversationTurnMeta(String modelName, Integer totalTokens, Integer durationMs) {

    /** 无补充事实（例如"证据不足"的兜底回答：没有模型调用，就没有模型名/用量/耗时）。 */
    public static final ConversationTurnMeta EMPTY = new ConversationTurnMeta(null, null, null);
}
