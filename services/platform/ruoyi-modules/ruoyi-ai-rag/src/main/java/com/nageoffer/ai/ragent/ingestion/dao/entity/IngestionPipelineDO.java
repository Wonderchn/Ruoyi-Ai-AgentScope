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

package com.nageoffer.ai.ragent.ingestion.dao.entity;

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
 * 数据摄入管道实体（统一库 {@code platform.ai_ingestion_pipeline}）。
 *
 * <p>归属列是 {@code owner_member_id}（V7 NOT NULL）加 {@code tenant_id}：调度与消费路径
 * 按这两列限域，缺身份的行不会被任何租户看到。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("ai_ingestion_pipeline")
public class IngestionPipelineDO implements MergedTableRow {

    /**
     * ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /** 平台租户列（varchar(64)，NOT NULL）：由执行主体写入。 */
    private String tenantId;

    /**
     * canonical 归属成员列（{@code owner_member_id}，varchar(160)，NOT NULL）：
     * {@code platform:<tenantId>:<userId>}。列名与 {@code member_id} 不同，
     * 由 {@link #setMemberId(String)} 承接 {@code AiDomainWriteIdentity} 的统一写入。
     */
    private String ownerMemberId;

    @Override
    public void setMemberId(String memberId) {
        this.ownerMemberId = memberId;
    }

    /**
     * 名称
     */
    private String name;

    /**
     * 描述
     */
    private String description;

    /**
     * 创建人
     */
    private String createdBy;

    /**
     * 更新人
     */
    private String updatedBy;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    /**
     * 是否删除
     */
    @TableLogic
    private Integer deleted;
}
