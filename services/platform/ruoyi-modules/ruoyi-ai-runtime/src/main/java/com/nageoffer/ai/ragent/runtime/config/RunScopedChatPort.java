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
 * **run 作用域**的对话端口（D02 / C1.2 / C1.4 / C1.6）。
 *
 * <p><b>为什么必须带上 {@link RunConfigBinding}。</b>C1.2 要求"新受理的 run 绑新版本"与
 * "已受理 / 运行中 / 恢复的 run 固定原版本"**同时成立**。这两件事只有在**读取入口带 run 身份**
 * 时才可能同时成立：若这里改成"取当前最新版本"，那么 run 存活期间发生一次发布，同一个 run 的
 * 前半段与后半段就会用**两份不同的配置**解释，而 run 上记录的 {@code config_revision_id} 还是旧的
 * —— 账实不符。绑定事实由调用方（`RagChatExecutor` / `AgentModelAdapter`，二者的 run 身份都在
 * 自己的方法签名里）经 {@link RunConfigBindingPort} 解析后**显式传入**。
 *
 * <p><b>为什么不放在 {@code org.ruoyi.ai.api.runtime.ChatPort} 上。</b>该模块（`ruoyi-ai-api`）
 * 的 pom 明文规定"必须保持零依赖"，而 {@link RunConfigBinding} 位于本模块（`ruoyi-ai-runtime`）。
 * 把绑定事实放进零依赖模块会同时造成两件事：① 契约模块反向依赖实现模块；② 在 {@code api} 里
 * **复制一份绑定事实** —— 那正是 D02 要消灭的第二权威形态。因此**本接口定义在 runtime 侧**，
 * `ChatGateway`（rag 侧，已依赖 runtime）继承它；`RagChatExecutor`（runtime 侧）只依赖本接口，
 * 不依赖 rag —— 依赖方向不反转。
 *
 * <p><b>结果类型复用 {@link ChatPort.ChatResult}</b>，刻意不新建第二个 result 类型：
 * 两个结构相同的类型并存会让"结算字段"出现两套解释。
 *
 * <p><b>无 run 身份的面必须显式拒绝。</b>继承链上仍存在 {@link ChatPort} 的无身份三参
 * {@code stream(...)} 与无参 {@code provider()/model()} —— 真实实现在这些方法上**必须抛异常**
 * （{@link ConfigAuthorityUnavailable}），不得回退任何默认模型（D02 硬约束 2）。
 * 唯一允许无身份返回的是**显式的合成 test double**（`SyntheticChatGateway`，
 * 标明的合成样本，不查库、也永不被当作真实提供方）。
 */
public interface RunScopedChatPort {

    /**
     * 该 run 受理时绑定的提供方。
     *
     * @param binding 该 run 的绑定事实（**不得**为 null；null 视为调用方缺陷，实现拒绝）
     */
    String provider(RunConfigBinding binding);

    /**
     * 该 run 受理时绑定的模型。
     *
     * @param binding 该 run 的绑定事实（**不得**为 null）
     */
    String model(RunConfigBinding binding);

    /**
     * 以该 run 绑定的配置流式对话。
     *
     * <p>端点与凭据**不在** {@code binding} 里：`ai_runtime_config_revision` 只有
     * {@code credential_ref}（引用/掩码），实际端点与密钥由**连接引导**提供（C1.1 用途②
     * 「连接引导」/ D16「凭据外注入」），见 {@link ProviderConnectionPort}。
     */
    ChatPort.ChatResult stream(RunConfigBinding binding, List<ChatMessage> messages,
                               int maxTokens, Consumer<String> onDelta);
}
