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

package com.nageoffer.ai.ragent.rag.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * P3-BIND：Agent 绑定引用（F09:3 权威；F11:3 复用）。
 *
 * <p>只存**引用**（模型 id / skillCode / mcpToolId / 编排码），不复制被绑定资产；
 * 行级 {@code enabled} 支持"暂时停用一条绑定"而不删行。租户事实只来自执行主体。
 *
 * <p>表结构由 V38 提案提供（{@code F09-3-SHARED.patch}，未落盘）：本实体与 Mapper 落在
 * {@code rag.dao.*} 是为了让既有 {@code @MapperScan}（{@code rag.dao.mapper}）直接覆盖，
 * 不为新包改装配面。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("ai_agent_profile_binding")
public class AgentProfileBindingDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /** 租户：只来自执行主体，客户端提交一律忽略。 */
    private String tenantId;

    private String agentProfileId;

    /** MODEL / SKILL / MCP / ORCHESTRATION（闭集，见 {@code AgentProfileBindingService.RefType}）。 */
    private String refType;

    /** 被绑定资产的标识（引用而非副本）。 */
    private String refId;

    private Integer enabled;

    private Integer sortOrder;

    private String createdBy;

    private String updatedBy;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
