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

package com.nageoffer.ai.ragent.mcp.admin;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * MCP 工具展示对象（brief §2.6）。
 *
 * <p>{@code inputSchema}/{@code baseUrl} 原样返回（前端/schema 展示所需）；
 * {@code configJson} 已把 headers 值掩码为 {@code "***"}；
 * {@code authMasked} 在存在 headers 时恒为 {@code "***"}，否则 null。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpToolVO {

    private Long id;

    private String name;

    private String description;

    private String type;

    private String status;

    /** 连接地址原样（来自 config_json.baseUrl）。 */
    private String baseUrl;

    /** 工具 schema 原样 JSON 对象；缺省 null。 */
    private JsonNode inputSchema;

    /** 仅管理面展示/存储；运行期确认仍由意图树 requireConfirm 决定。 */
    private boolean requiresConfirm;

    /** 已掩码的 config_json（headers 值恒为 "***"）。 */
    private String configJson;

    private String authMasked;

    private Date createTime;

    private Date updateTime;
}
