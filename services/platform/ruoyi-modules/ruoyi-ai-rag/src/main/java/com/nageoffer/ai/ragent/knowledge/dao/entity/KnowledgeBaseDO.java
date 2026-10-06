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

package com.nageoffer.ai.ragent.knowledge.dao.entity;

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
 * 知识库实体（统一库 {@code platform.ai_knowledge_base}）。
 *
 * <p>归属列是 {@code owner_member_id}（V7 NOT NULL）加 {@code tenant_id}：创建走
 * {@code AiResourceWriteService}（原始 JDBC，参数来自 {@link com.nageoffer.ai.ragent.framework.context.PrincipalContext}），
 * 实体路径则由 {@code AiDomainWriteIdentity} 填充。{@code createdBy} 是历史展示列，
 * <b>不是</b>归属权威。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("ai_knowledge_base")
public class KnowledgeBaseDO implements MergedTableRow {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /** 平台租户列（varchar(64)，NOT NULL）：由执行主体写入。 */
    private String tenantId;

    /**
     * canonical 归属成员列（{@code owner_member_id}，varchar(160)，NOT NULL）：
     * {@code platform:<tenantId>:<userId>}。列名与 {@code ai_conversation.member_id} 不同，
     * 因此这里显式命名为 {@code ownerMemberId}，并由 {@link #setMemberId(String)} 承接
     * {@code AiDomainWriteIdentity} 的统一写入（不靠命名巧合）。
     */
    private String ownerMemberId;

    @Override
    public void setMemberId(String memberId) {
        this.ownerMemberId = memberId;
    }

    /**
     * 知识库名称
     */
    private String name;

    /**
     * 嵌入模型标识，如：qwen3-embedding:8b-fp16
     */
    private String embeddingModel;

    /**
     * Milvus Collection 名称（创建后禁止修改）
     */
    private String collectionName;

    /**
     * 创建人
     */
    private String createdBy;

    /**
     * 修改人
     */
    private String updatedBy;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    /**
     * 是否删除：0-正常，1-删除
     */
    @TableLogic
    private Integer deleted;
}

