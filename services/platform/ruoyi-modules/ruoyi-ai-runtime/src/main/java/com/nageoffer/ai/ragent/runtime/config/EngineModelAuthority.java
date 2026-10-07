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

import java.time.Instant;

/**
 * 运行配置发布权威端口（D02/D03 → C1.1/C1.3）。
 *
 * <p><b>为什么需要这个端口。</b>在它之前，"当前用哪个 provider / model"这件事有多条
 * 互相独立的解释路径：{@code agent.chat.provider}/{@code agent.chat.model}（YAML）、
 * {@code ai.providers.*}（YAML）、{@code AgentEngineConfiguration} 从 YAML 组装模型、
 * {@code RealChatGateway} 里写死的 {@code "deepseek"}/{@code "deepseek-flash"} 与端点 URL。
 * 这些路径**没有一条**是"数据库里那次发布"的事实——于是"管理端发布了新版本"与
 * "运行期实际用的模型"可以同时成立却互不相等（C1.5 判据 2 正是要打这件事）。
 *
 * <p>本端口把事实收敛成一句话：<b>运行期只认数据库里不可变的已发布版本</b>
 * （{@code platform.ai_runtime_config_revision}，V15）。YAML 只剩显式初装导入与连接引导
 * 两种用途（C1.1），不再作为公开面或受理面的事实来源。
 *
 * <p><b>fail-closed，不回退。</b>实现必须在"读不到权威"时抛
 * {@link ConfigAuthorityUnavailable}，<b>不得</b>返回 YAML 值、不得返回缓存的旧值、
 * 不得返回空串让调用方自己兜底。理由：C1.1 明文禁止"数据库读取失败时静默回退 YAML"——
 * 一次静默回退就意味着"管理端撤权/改模型后运行期仍在用被撤掉的配置"，
 * 而那正是本契约要消灭的失效形态。
 */
public interface EngineModelAuthority {

    /**
     * 解析当前租户的权威发布版本。
     *
     * @param action 需要该事实的动作（用于失败信息与审计，不参与事实选择）
     * @throws ConfigAuthorityUnavailable 读不到权威（无发布版本 / 无主体 / 数据库不可用）
     */
    PublishedModel requirePublished(String action);

    /**
     * 一次发布版本绑定的完整事实（C1.3 必须可追溯的字段）。
     *
     * <p>{@code credentialRef} 是**引用/掩码**，不是密钥明文（C1.3：密钥只存引用/掩码）。
     */
    record PublishedModel(
            String tenantId,
            String revisionId,
            long revisionNo,
            String providerId,
            String modelId,
            String catalogVersion,
            String paramsHash,
            String credentialRef,
            String operatorId,
            Instant publishedAt) {
    }
}
