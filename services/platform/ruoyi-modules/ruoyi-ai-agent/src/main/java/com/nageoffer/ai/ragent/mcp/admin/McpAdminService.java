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

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;

/**
 * MCP 管理面服务（brief §1/§2.5/§2.6）。
 *
 * <p>所有查询/写入都带当前 {@code ExecutionPrincipal} 的 tenantId（显式谓词，不依赖全局
 * 拦截器）；跨租户 id 与不存在同形（404）。
 */
public interface McpAdminService {

    // ------------------------------------------------------------------ 市场

    IPage<McpMarketVO> pageMarkets(McpPageRequest request);

    List<McpMarketVO> listMarkets(String keyword, String status);

    McpMarketVO getMarket(long id);

    int deleteMarkets(List<Long> ids);

    void updateMarketStatus(long id, String status);

    /** 导出市场行集合（auth_config 掩码为 "***"）。 */
    String exportMarkets(String keyword, String status);

    // ------------------------------------------------------------------ 市场工具

    IPage<McpMarketToolVO> pageMarketTools(long marketId, long current, long size);

    McpRefreshResult refreshMarket(long id);

    void loadMarketTool(long id);

    int batchLoadMarketTools(List<Long> ids);

    // ------------------------------------------------------------------ 工具

    IPage<McpToolVO> pageTools(McpPageRequest request);

    List<McpToolVO> listTools(String keyword, String type, String status);

    List<McpToolVO> listEnabledTools();

    McpToolVO getTool(long id);

    int deleteTools(List<Long> ids);

    void updateToolStatus(long id, String status);

    /** 导出工具行集合（config_json.headers 掩码为 "***"）。 */
    String exportTools(String keyword, String type, String status);

    McpTestResult testTool(long id);
}
