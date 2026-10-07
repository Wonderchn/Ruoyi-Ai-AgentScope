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

package com.nageoffer.ai.ragent.audit.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.nageoffer.ai.ragent.framework.database.JsonbTypeHandler;
import com.nageoffer.ai.ragent.framework.convention.MergedTableRow;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 业务变更审计日志（统一库 {@code platform.ai_biz_change_log}）。
 *
 * <p>{@code tenant_id}/{@code member_id} 是 V7 的 NOT NULL 平台侧列：写入前由
 * {@code AiDomainWriteIdentity} 从执行主体填充，不靠列默认值——默认值会把不同租户的审计
 * 记录混进同一个桶。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "ai_biz_change_log", autoResultMap = true)
public class BizChangeLogDO implements MergedTableRow {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /** 平台租户列（varchar(64)，NOT NULL）：由执行主体写入。 */
    private String tenantId;

    /** canonical 成员列（varchar(160)，NOT NULL）：{@code platform:<tenantId>:<userId>}。 */
    private String memberId;

    private String bizType;

    private String bizId;

    private String operationType;

    private String actionDesc;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String beforeSnapshot;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String afterSnapshot;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String changeDiff;

    private String operatorId;

    private String operatorName;

    private String operatorRole;

    private Boolean success;

    private String errorMessage;

    private String className;

    private String methodName;

    private String ip;

    private String userAgent;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;
}
