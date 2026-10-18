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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/** 市场工具行（{@code ai_mcp_market_tool}）的分页展示对象。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpMarketToolVO {

    private Long id;

    private Long marketId;

    private String toolName;

    private String toolDescription;

    private String toolVersion;

    /** 是否已加载到本地（is_loaded != 0）。 */
    private Boolean isLoaded;

    private Long localToolId;

    private Date createTime;

    private Date updateTime;
}
