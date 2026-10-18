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
import com.nageoffer.ai.ragent.framework.database.JsonbTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * MCP 市场工具关联表（{@code platform.ai_mcp_market_tool}，冻结迁移 V9 已建）。
 *
 * <p>该表<b>没有 del_flag</b>：删除市场时先物理删除其下工具行，再删除市场本身。
 * {@code tool_metadata} 是 {@code jsonb}，以 {@link JsonbTypeHandler} 搬运不透明 JSON。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ai_mcp_market_tool")
public class McpMarketToolDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long marketId;

    private String toolName;

    private String toolDescription;

    private String toolVersion;

    /** 完整 MCP Tool JSON（jsonb）。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String toolMetadata;

    /** smallint：0 未加载 / 1 已加载。 */
    private Integer isLoaded;

    private Long localToolId;

    private String tenantId;

    private Long createDept;

    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    private Long updateBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;
}
