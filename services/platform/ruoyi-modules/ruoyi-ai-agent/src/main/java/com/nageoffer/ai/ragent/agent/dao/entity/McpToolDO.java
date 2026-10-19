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
 * MCP 工具表（{@code platform.ai_mcp_tool}，冻结迁移 V9 已建）。
 *
 * <p>{@code config_json} 允许字段：
 * {@code {"baseUrl": "...", "headers": {"Name": "env:VAR"|"literal"},
 * "inputSchema": {...}, "requiresConfirm": true|false}}。
 * headers 值只存引用/字面量，响应一律掩码；运行期确认仍由意图树
 * {@code requireConfirm} 决定（本包只做管理面展示，不改 {@code McpToolProxy}）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ai_mcp_tool")
public class McpToolDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    private String description;

    /** LOCAL / REMOTE / BUILTIN。 */
    private String type;

    /** ENABLED / DISABLED。 */
    private String status;

    /** 连接/展示配置（JSON；headers 值永不回传）。 */
    private String configJson;

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
