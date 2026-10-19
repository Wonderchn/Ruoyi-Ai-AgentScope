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

package com.nageoffer.ai.ragent.agent.config;

import io.agentscope.core.model.Model;

/**
 * F09:3-C：绑定 MODEL 的运行时模型工厂端口。
 *
 * <p><b>验收口径（写死）。</b>MODEL 绑定的消费 = 绑定模型被用于该 Agent 的 **ReAct 主链**
 * （{@code ReActAgent#getModel()} 即本工厂返回的实例）。同 provider 内切换是保底能力；
 * 跨 provider 时若既有费率表（{@code ProviderSpendEnvelope.estimate} 只认 deepseek/dashscope）
 * 不支持，预占阶段 denied 属**既有预算边界**，不是本接口/本包的缺陷。
 *
 * <p><b>fail-closed。</b>解析不到（未知模型、多 provider 歧义、连接配置缺失、端口缺席）
 * 一律抛 {@link AgentModelUnavailableException}；<b>不回落</b>到全局模型。
 *
 * <p><b>凭据。</b>实现只读取既有 {@code ai.providers.*} / {@code p2.providers.connections.entries.*}，
 * 不新增凭据；缓存键与异常文案都不含端点或密钥值。
 */
@FunctionalInterface
public interface AgentModelFactory {

    /**
     * 解析并返回该租户下绑定模型对应的 AgentScope 模型客户端（按 provider+model 缓存复用）。
     *
     * @param tenantId 租户（来自执行主体，显式传参而不是全局上下文）
     * @param modelId  绑定引用 id（与运行目录 {@code model_id} 同空间）
     * @throws AgentModelUnavailableException 不可解析（fail-closed）
     */
    Model create(String tenantId, String modelId);

    /** 绑定模型不可解析的具名失败信号（调用方必须上抛，不得回落全局模型）。 */
    class AgentModelUnavailableException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        public AgentModelUnavailableException(String message) {
            super(message);
        }

        public AgentModelUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
