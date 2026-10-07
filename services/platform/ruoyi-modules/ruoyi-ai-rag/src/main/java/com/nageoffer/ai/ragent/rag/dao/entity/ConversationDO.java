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
import com.nageoffer.ai.ragent.framework.convention.MergedTableRow;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 会话行（统一库 {@code platform.ai_conversation}）。
 *
 * <p>{@code id} 是统一表主键，{@code conversationId} 是<b>公开会话标识</b>——两者不等价，
 * 消息按 {@code conversation_id + tenant_id + member_id} 关联到本行（V7
 * {@code fk_message_conversation}）。{@code tenantId}/{@code memberId} 是平台侧身份列，
 * 没有数据库默认值来源，只能由 {@code AiDomainWriteIdentity} 从执行主体写入。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("ai_conversation")
public class ConversationDO implements MergedTableRow {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /** 公开会话标识（varchar(20)）：不是主键，消息与摘要按它关联。 */
    private String conversationId;

    /** 平台租户列（varchar(64)，NOT NULL）：由执行主体写入，不接受请求体取值。 */
    private String tenantId;

    /** canonical 成员列（varchar(160)，NOT NULL）：{@code platform:<tenantId>:<userId>}。 */
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
