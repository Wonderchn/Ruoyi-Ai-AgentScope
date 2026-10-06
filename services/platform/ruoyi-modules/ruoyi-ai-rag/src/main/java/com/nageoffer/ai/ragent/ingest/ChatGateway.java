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

package com.nageoffer.ai.ragent.ingest;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;

import java.util.List;
import java.util.function.Consumer;

/**
 * 问答模型网关：真实提供方与显式合成 test double 互斥（p2.chat.mode）。
 *
 * <p><b>本接口同时是两种面，二者语义**刻意相反**：</b>
 * <ul>
 *   <li>{@link org.ruoyi.ai.api.runtime.ChatPort}（模块间中性契约，位于零依赖的
 *       {@code ruoyi-ai-api}）：**无 run 身份**。真实实现（{@link RealChatGateway}）
 *       在这一面**必须显式抛异常**（D02 约束 2：「无 run 身份必须显式拒绝 —— 若保留
 *       3 参重载，它必须抛异常或不存在」）。只有**显式的合成 test double**
 *       （{@link SyntheticChatGateway}）允许返回常量身份：它是标明的合成样本，
 *       不查库、不参与真实模型权威。</li>
 *   <li>{@link com.nageoffer.ai.ragent.runtime.config.RunScopedChatPort}（D02/C1.2）：
 *       provider/model **来自该 run 受理时绑定的发布版本**（
 *       {@link com.nageoffer.ai.ragent.runtime.config.RunConfigBinding}），由调用方
 *       （{@code RagChatExecutor} / {@code AgentModelAdapter}）显式传入。</li>
 * </ul>
 *
 * <p><b>为什么 run 作用域面不定义在 {@code ChatPort} 上。</b>见
 * {@link com.nageoffer.ai.ragent.runtime.config.RunScopedChatPort} 的类注释：把绑定事实
 * 放进零依赖契约模块会同时造成反向依赖与"复制一份事实"，后者正是 D02 要消灭的第二权威。
 */
public interface ChatGateway extends org.ruoyi.ai.api.runtime.ChatPort<ChatMessage>,
        com.nageoffer.ai.ragent.runtime.config.RunScopedChatPort {

    /** 无 run 身份面：真实实现**必须**抛异常（合成 double 除外）。 */
    @Override
    String provider();

    /** 无 run 身份面：真实实现**必须**抛异常（合成 double 除外）。 */
    @Override
    String model();

    /**
     * 流式问答（无 run 身份面）。providerRequestId/usageRaw 为 null 表示提供方未返回 →
     * 上层记待核对。真实实现**必须**抛异常，防止任何调用点绕过 run 绑定的发布版本。
     */
    @Override
    ChatResult stream(List<ChatMessage> messages, int maxTokens, Consumer<String> onDelta);
}
