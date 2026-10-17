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

package com.nageoffer.ai.ragent.rag.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * 智能体视图对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentProfileVO {

    private String id;
    private String name;
    private String description;

    /**
     * 头像预设标识
     */
    private String avatar;

    /**
     * 内置智能体不可编辑不可删除
     */
    private Boolean builtin;

    private Boolean active;

    /**
     * 自身已填写、且在当前架构下会被读取的槽位数，其余槽位回落内置
     */
    private Integer effectiveSlots;

    /**
     * 已填写但当前架构读不到的槽位数，切换 ragent.engine.type 后才会生效
     */
    private Integer inactiveSlots;

    private Date createTime;
    private Date updateTime;

    /**
     * P3-BIND：该智能体的绑定引用（F09:3 读侧回显）。
     *
     * <p>语义（与保存契约 {@code AgentProfileSaveRequest.bindings} 对称）：
     * <ul>
     *   <li>{@code []} = 确无绑定（页面可安全地按替换语义发空集 = 无操作）；</li>
     *   <li>非空 = 当前绑定集合；</li>
     *   <li>{@code null} = 绑定权威缺席、读不出（页面**不得**据此替换绑定，保持"本次不改"）。</li>
     * </ul>
     */
    private List<BindingRef> bindings;

    /** 一条绑定引用（线路形状：type=MODEL/SKILL/MCP/ORCHESTRATION，refId=资产标识）。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BindingRef {
        private String type;
        private String refId;
    }
}
