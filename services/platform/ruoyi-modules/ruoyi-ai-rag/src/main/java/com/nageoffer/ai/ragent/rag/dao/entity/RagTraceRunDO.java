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
 * RAG Trace 运行记录（统一库 {@code platform.ai_rag_trace_run}）。
 *
 * <p>{@code tenant_id}/{@code member_id} 是 V7 的 NOT NULL 平台侧列：写入前由
 * {@code AiDomainWriteIdentity} 从执行主体填充；运营/Trace 读路径按这两列限域，
 * 因此缺身份的行对任何人都不可见——不能靠默认值糊过去。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("ai_rag_trace_run")
public class RagTraceRunDO implements MergedTableRow {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /** 平台租户列（varchar(64)，NOT NULL）：由执行主体写入。 */
    private String tenantId;

    /** canonical 成员列（varchar(160)，NOT NULL）：{@code platform:<tenantId>:<userId>}。 */
    private String memberId;

    /**
     * 全局链路ID
     */
    private String traceId;

    /**
     * 链路名称
     */
    private String traceName;

    /**
     * 触发入口方法
     */
    private String entryMethod;

    private String conversationId;

    private String taskId;

    private String userId;

    /**
     * RUNNING / SUCCESS / ERROR
     */
    private String status;

    private String errorMessage;

    private Date startTime;

    private Date endTime;

    private Long durationMs;

    /**
     * 预留扩展字段（JSON字符串）
     */
    private String extraData;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
