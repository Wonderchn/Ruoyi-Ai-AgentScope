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

package com.nageoffer.ai.ragent.runtime.config;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import org.ruoyi.ai.api.runtime.ChatPort;

import java.util.List;
import java.util.function.Consumer;

/**
 * 模型网关的**运行面窄门**（WP-030/D02 执行期收口）。
 *
 * <p><b>它解决什么。</b>{@code RunScopedChatPort}（run 作用域）之外还存在
 * {@code ChatGateway}/{@code ChatPort} 上的无身份三参 {@code stream(...)} 与
 * 无参 {@code provider()/model()}。G-41 教训：这些"看起来没人调用"的宽门正是
 * 主路径漏接的入口。本窄门**只有** run 作用域形态 —— 执行链（RagChatExecutor 等）
 * 依赖本端口，宽门里的真实方法自然没有调用方，只剩显式拒绝的负例价值。
 *
 * <p><b>为什么 result 复用 {@link ChatPort.ChatResult}</b>：结算字段（usage/finish）
 * 只允许一套解释（与 RunScopedChatPort 同一取舍）。
 */
public interface RuntimeModelGatewayPort {

    /** 该 run 受理时绑定的提供方（fail-closed：绑定缺失即拒绝）。 */
    String provider(RunConfigBinding binding);

    /** 该 run 受理时绑定的模型（fail-closed：绑定缺失即拒绝）。 */
    String model(RunConfigBinding binding);

    /** 以该 run 绑定的配置流式对话（端点/凭据经连接引导，见 RunScopedChatPort 注释）。 */
    ChatPort.ChatResult stream(RunConfigBinding binding, List<ChatMessage> messages,
                               int maxTokens, Consumer<String> onDelta);
}
