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

package com.nageoffer.ai.ragent.agent.dao.entity;

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
 * Agent 会话表，与 rag 的 ai_conversation 系列两套分立
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("ai_agent_conversation")
public class AgentConversationDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String conversationId;

    /**
     * P1.3d：归属（V3 加列、V4 NOT NULL）；权威主体引用是 memberId，userId 仅展示/legacy。
     * 列名 tenant_id/member_id 由 MyBatis-Plus 驼峰映射，不加额外注解
     */
    private String tenantId;

    private String memberId;

    private String userId;

    private String title;

    private Date lastTime;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
