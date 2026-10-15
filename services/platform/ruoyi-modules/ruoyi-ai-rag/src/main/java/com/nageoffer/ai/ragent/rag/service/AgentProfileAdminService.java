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

package com.nageoffer.ai.ragent.rag.service;

import com.nageoffer.ai.ragent.rag.controller.request.AgentProfileSaveRequest;
import com.nageoffer.ai.ragent.rag.controller.request.AgentPromptSaveRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentProfileListVO;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentProfileVO;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentPromptConfigVO;

public interface AgentProfileAdminService {

    /**
     * 查询全部智能体，内置在前、其余按创建时间
     */
    AgentProfileListVO list();

    /**
     * 读取单个智能体详情（F09:0）。
     *
     * <p>与 {@link #list()} 同一投影（{@code AgentProfileVO}）；不存在（或已软删）即
     * 抛 {@code ClientException}，<b>零副作用</b>（不写任何表、不 bump 任何 epoch）。
     */
    AgentProfileVO detail(String id);

    /**
     * 导出智能体目录（F09:0），返回 NDJSON 文本（每行一个智能体）。
     *
     * <p><b>字段与掩码</b>：只导出目录元数据 + 已配置槽位的 {@code slotKey}（内容一律不出现，
     * 以 {@code "masked":true} 显式标注）；不导出提示词正文、模型、凭据等任何运行配置事实。
     * {@code keyword} 非空时按名称/描述包含过滤（与列表筛选同一口径，大小写不敏感）。
     */
    String exportDefinition(String keyword);

    String create(AgentProfileSaveRequest requestParam);

    void update(String id, AgentProfileSaveRequest requestParam);

    void delete(String id);

    /**
     * 激活指定智能体，全局仅保留一条激活态
     */
    void activate(String id);

    /**
     * 查询该智能体的全部槽位配置，含元数据与当前架构下的生效判定
     */
    AgentPromptConfigVO loadPrompts(String id);

    /**
     * 保存单个槽位，内容留空即恢复回落内置智能体
     */
    void savePrompt(String id, String slotKey, AgentPromptSaveRequest requestParam);

    /**
     * 取内置智能体的该槽位内容，供控制台「从默认复制」
     */
    String defaultPrompt(String slotKey);
}
