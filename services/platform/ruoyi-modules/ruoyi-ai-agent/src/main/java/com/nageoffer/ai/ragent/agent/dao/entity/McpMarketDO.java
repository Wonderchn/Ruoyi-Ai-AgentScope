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
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * MCP 市场表（{@code platform.ai_mcp_market}，冻结迁移 V9 已建，本包不改 DDL）。
 *
 * <p>{@code del_flag} 保持原列语义（0 存在 / 2 删除），但本管理面按 brief §2.5
 * 的建议使用<b>物理删除</b>；读取侧仍以 {@code del_flag='0'} 过滤遗留的软删行。
 *
 * <p>{@code auth_config} 是<b>只存不读</b>的敏感列：详情/列表/导出一律掩码
 * （{@code "***"}），连接时才解析其中的 {@code env:NAME} 引用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ai_mcp_market")
public class McpMarketDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    private String url;

    private String description;

    /** 认证配置（JSON；响应永不回传原值）。 */
    private String authConfig;

    /** ENABLED / DISABLED。 */
    private String status;

    private String tenantId;

    private Long createDept;

    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    private Long updateBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    private String delFlag;
}
