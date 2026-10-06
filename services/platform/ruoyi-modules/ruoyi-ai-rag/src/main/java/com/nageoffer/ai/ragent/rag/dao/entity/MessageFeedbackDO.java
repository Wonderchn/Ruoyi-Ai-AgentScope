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
 * 会话消息反馈实体类
 * 用于存储用户对助手消息的点赞/踩反馈（统一库 {@code platform.ai_message_feedback}）。
 *
 * <p>{@code tenantId}/{@code memberId} 是 V7 的 NOT NULL 平台侧列，没有默认值来源：
 * 写路径必须先用 {@code AiDomainWriteIdentity} 从执行主体填充本对象，否则插入会被数据库
 * 拒绝——反馈不允许落到无身份的桶里。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("ai_message_feedback")
public class MessageFeedbackDO implements MergedTableRow {

    /**
     * 主键 ID，采用雪花算法生成
     */
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /** 平台租户列（varchar(64)，NOT NULL）：由执行主体写入，不接受请求体取值。 */
    private String tenantId;

    /** canonical 成员列（varchar(160)，NOT NULL）：{@code platform:<tenantId>:<userId>}。 */
    private String memberId;

    /**
     * 消息 ID，关联到会话消息
     */
    private String messageId;

    /**
     * 会话 ID
     */
    private String conversationId;

    /**
     * 用户 ID
     */
    private String userId;

    /**
     * 反馈值：1=点赞，-1=点踩
     */
    private Integer vote;

    /**
     * 反馈原因（可选）
     */
    private String reason;

    /**
     * 补充说明（可选）
     */
    private String comment;

    /**
     * 创建时间，自动填充
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    /**
     * 更新时间，插入和更新时自动填充
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    /**
     * 删除标识，逻辑删除字段
     */
    @TableLogic
    private Integer deleted;
}
