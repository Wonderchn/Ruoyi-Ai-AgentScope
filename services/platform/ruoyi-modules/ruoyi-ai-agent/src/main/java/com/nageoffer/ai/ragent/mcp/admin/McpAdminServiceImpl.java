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

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.agent.dao.entity.McpMarketDO;
import com.nageoffer.ai.ragent.agent.dao.entity.McpMarketToolDO;
import com.nageoffer.ai.ragent.agent.dao.entity.McpToolDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.McpMarketMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.McpMarketToolMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.McpToolMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MCP 管理面实现（F12-M1）。
 *
 * <p><b>租户</b>：每个公开方法先 {@link PrincipalContext#require()}，所有查询/写入显式带
 * {@code tenant_id = ?}；跨租户 id 与不存在同形（404）。agent 线程上下文缺失时
 * {@code require()} 直接失败关闭。
 *
 * <p><b>刷新（brief §2.5）</b>：先做 allow-list 校验（未批准 ⇒ 零网络），再解析 headers
 * （{@code env:NAME} 引用），再做 MCP discover（initialize + tools/list）。发现失败 ⇒
 * 具名失败结果且<b>零数据库写</b>；写入阶段在一个事务里完成（{@link TransactionTemplate}），
 * 任一写失败整体回滚并返回具名失败结果（不抛 500）。以 {@code tool_name} 为幂等键。
 *
 * <p><b>连接测试</b>：只读 + 只连，不写库；四态为可达 / 连接失败 / 鉴权失败 / 工具不存在，
 * 未批准目标抛 {@link McpEndpointNotAllowedException}（控制器映射 403）。
 *
 * <p><b>加载</b>：市场工具行 → {@code ai_mcp_tool}（{@code type=REMOTE}，
 * {@code config_json={"baseUrl": <market.url>}}），回写 {@code is_loaded=1/local_tool_id}；
 * 重复加载 409 且零部分写。批量逐条隔离，一条失败不影响其它条。
 *
 * <p><b>删除</b>：市场与工具都走<b>物理删除</b>（{@code del_flag} 列保留，读取侧仍按
 * {@code del_flag='0'} 过滤遗留软删行）；删市场先删其下市场工具行。删除工具不检查
 * 技能/意图引用（超出 F12 行），返回删除计数、不脆断。
 */
@Slf4j
@Service
public class McpAdminServiceImpl implements McpAdminService {

    private static final Set<String> STATUSES = Set.of("ENABLED", "DISABLED");
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    /** 鉴权失败的识别：只看异常链上的状态码/单词，不做地址推断。 */
    private static final Pattern AUTH_FAILURE = Pattern.compile(
            "(?i)(\\b401\\b|\\b403\\b|unauthorized|forbidden)");

    private final McpMarketMapper marketMapper;
    private final McpMarketToolMapper marketToolMapper;
    private final McpToolMapper toolMapper;
    private final McpEndpointAllowList allowList;
    private final McpRemoteCatalogClient remoteClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public McpAdminServiceImpl(McpMarketMapper marketMapper,
                               McpMarketToolMapper marketToolMapper,
                               McpToolMapper toolMapper,
                               McpEndpointAllowList allowList,
                               McpRemoteCatalogClient remoteClient,
                               ObjectMapper objectMapper,
                               PlatformTransactionManager transactionManager) {
        this.marketMapper = marketMapper;
        this.marketToolMapper = marketToolMapper;
        this.toolMapper = toolMapper;
        this.allowList = allowList;
        this.remoteClient = remoteClient;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------ 市场

    @Override
    public IPage<McpMarketVO> pageMarkets(McpPageRequest request) {
        String tenant = tenantId();
        String keyword = trimToNull(request == null ? null : request.getKeyword());
        String status = trimToNull(request == null ? null : request.getStatus());
        Page<McpMarketDO> page = new Page<>(current(request), size(request));
        IPage<McpMarketDO> result = marketMapper.selectPage(page,
                Wrappers.lambdaQuery(McpMarketDO.class)
                        .eq(McpMarketDO::getTenantId, tenant)
                        .eq(McpMarketDO::getDelFlag, "0")
                        .eq(status != null, McpMarketDO::getStatus, status)
                        .and(keyword != null, wrapper -> wrapper
                                .like(McpMarketDO::getName, keyword)
                                .or()
                                .like(McpMarketDO::getDescription, keyword))
                        .orderByDesc(McpMarketDO::getUpdateTime));
        return result.convert(McpAdminServiceImpl::toMarketVO);
    }

    @Override
    public List<McpMarketVO> listMarkets(String keyword, String status) {
        String tenant = tenantId();
        String trimmedKeyword = trimToNull(keyword);
        String trimmedStatus = trimToNull(status);
        List<McpMarketDO> rows = marketMapper.selectList(marketQuery(tenant, trimmedKeyword, trimmedStatus));
        return rows.stream().map(McpAdminServiceImpl::toMarketVO).toList();
    }

    @Override
    public McpMarketVO getMarket(long id) {
        return toMarketVO(loadMarket(id, tenantId()));
    }

    @Override
    public int deleteMarkets(List<Long> ids) {
        String tenant = tenantId();
        List<Long> targets = requireIds(ids);
        // 先删市场下工具行（该表没有 del_flag），再删市场；两者都显式限租户
        marketToolMapper.delete(Wrappers.lambdaQuery(McpMarketToolDO.class)
                .eq(McpMarketToolDO::getTenantId, tenant)
                .in(McpMarketToolDO::getMarketId, targets));
        return marketMapper.delete(Wrappers.lambdaQuery(McpMarketDO.class)
                .eq(McpMarketDO::getTenantId, tenant)
                .eq(McpMarketDO::getDelFlag, "0")
                .in(McpMarketDO::getId, targets));
    }

    @Override
    public void updateMarketStatus(long id, String status) {
        String validated = requireStatus(status);
        McpMarketDO market = loadMarket(id, tenantId());
        market.setStatus(validated);
        marketMapper.updateById(market);
    }

    @Override
    public String exportMarkets(String keyword, String status) {
        String tenant = tenantId();
        List<McpMarketDO> rows = marketMapper.selectList(
                marketQuery(tenant, trimToNull(keyword), trimToNull(status)));
        List<Map<String, Object>> export = new ArrayList<>();
        for (McpMarketDO row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("name", row.getName());
            item.put("url", row.getUrl());
            item.put("description", row.getDescription());
            item.put("status", row.getStatus());
            item.put("tenantId", row.getTenantId());
            item.put("authConfig", McpConfigJson.maskAuthConfig(row.getAuthConfig()));
            item.put("createTime", row.getCreateTime());
            item.put("updateTime", row.getUpdateTime());
            export.add(item);
        }
        return writeJson(export);
    }

    // ------------------------------------------------------------------ 市场工具

    @Override
    public IPage<McpMarketToolVO> pageMarketTools(long marketId, long current, long size) {
        String tenant = tenantId();
        loadMarket(marketId, tenant);
        Page<McpMarketToolDO> page = new Page<>(normalizeCurrent(current), normalizeSize(size));
        IPage<McpMarketToolDO> result = marketToolMapper.selectPage(page,
                Wrappers.lambdaQuery(McpMarketToolDO.class)
                        .eq(McpMarketToolDO::getTenantId, tenant)
                        .eq(McpMarketToolDO::getMarketId, marketId)
                        .orderByDesc(McpMarketToolDO::getCreateTime));
        return result.convert(McpAdminServiceImpl::toMarketToolVO);
    }

    @Override
    public McpRefreshResult refreshMarket(long id) {
        String tenant = tenantId();
        McpMarketDO market = loadMarket(id, tenant);

        URI endpoint = URI.create(McpRemoteCatalogClient.normalizeEndpoint(market.getUrl()));
        // 未批准 ⇒ 零网络（requireAllowed 在任何连接建立之前抛出）
        allowList.requireAllowed(endpoint);
        Map<String, String> headers = McpConfigJson.resolveHeaders(market.getAuthConfig());

        List<Tool> discovered;
        try {
            discovered = remoteClient.discover(endpoint.toString(), headers);
        } catch (RuntimeException e) {
            log.warn("mcp market refresh discovery failed, marketId={}, errorType={}",
                    id, e.getClass().getName());
            return new McpRefreshResult(false, 0, 0, "刷新市场工具列表失败: " + e.getClass().getSimpleName());
        }

        try {
            McpRefreshResult result = transactions.execute(status -> applyRefresh(market, tenant, discovered));
            return result == null ? new McpRefreshResult(false, 0, 0, "刷新市场工具列表失败") : result;
        } catch (RuntimeException e) {
            // 事务模板已回滚：写阶段任一失败都不留部分写
            log.warn("mcp market refresh commit failed, marketId={}, errorType={}",
                    id, e.getClass().getName());
            return new McpRefreshResult(false, 0, 0, "刷新市场工具列表失败: " + e.getClass().getSimpleName());
        }
    }

    private McpRefreshResult applyRefresh(McpMarketDO market, String tenant, List<Tool> discovered) {
        List<McpMarketToolDO> existing = marketToolMapper.selectList(
                Wrappers.lambdaQuery(McpMarketToolDO.class)
                        .eq(McpMarketToolDO::getTenantId, tenant)
                        .eq(McpMarketToolDO::getMarketId, market.getId()));
        Map<String, McpMarketToolDO> byName = new LinkedHashMap<>();
        for (McpMarketToolDO row : existing) {
            byName.put(row.getToolName(), row);
        }

        int added = 0;
        int updated = 0;
        for (Tool tool : discovered) {
            String metadata = writeJson(tool);
            String description = tool.description();
            String version = versionOf(tool);
            McpMarketToolDO row = byName.get(tool.name());
            if (row == null) {
                row = McpMarketToolDO.builder()
                        .marketId(market.getId())
                        .toolName(tool.name())
                        .toolDescription(description)
                        .toolVersion(version)
                        .toolMetadata(metadata)
                        .isLoaded(0)
                        .tenantId(tenant)
                        .build();
                marketToolMapper.insert(row);
                byName.put(tool.name(), row);
                added++;
            } else {
                row.setToolDescription(description);
                row.setToolVersion(version);
                row.setToolMetadata(metadata);
                marketToolMapper.updateById(row);
                updated++;
            }
        }
        return new McpRefreshResult(true, added, updated, "刷新成功");
    }

    @Override
    public void loadMarketTool(long id) {
        String tenant = tenantId();
        McpMarketToolDO row = marketToolMapper.selectOne(
                Wrappers.lambdaQuery(McpMarketToolDO.class)
                        .eq(McpMarketToolDO::getId, id)
                        .eq(McpMarketToolDO::getTenantId, tenant));
        if (row == null) {
            throw new McpAdminException(McpAdminError.NOT_FOUND);
        }
        if (row.getIsLoaded() != null && row.getIsLoaded() != 0) {
            throw new McpAdminException(McpAdminError.TOOL_ALREADY_LOADED);
        }
        McpMarketDO market = loadMarket(row.getMarketId(), tenant);
        // 市场 auth_config 的头引用原样搬运（env:NAME 不解析、不落明文）；
        // 非对象/不可解析 ⇒ 不写 headers（保持零部分写语义）
        Map<String, Object> localConfig = new LinkedHashMap<>();
        localConfig.put("baseUrl", market.getUrl());
        Map<String, String> headerRefs = McpConfigJson.rawHeaders(market.getAuthConfig());
        if (!headerRefs.isEmpty()) {
            localConfig.put("headers", headerRefs);
        }

        transactions.executeWithoutResult(status -> {
            McpToolDO localTool = McpToolDO.builder()
                    .name(row.getToolName())
                    .description(row.getToolDescription())
                    .type("REMOTE")
                    .status("ENABLED")
                    .configJson(writeJson(localConfig))
                    .tenantId(tenant)
                    .delFlag("0")
                    .build();
            toolMapper.insert(localTool);
            row.setIsLoaded(1);
            row.setLocalToolId(localTool.getId());
            marketToolMapper.updateById(row);
        });
    }

    @Override
    public int batchLoadMarketTools(List<Long> ids) {
        List<Long> targets = requireIds(ids);
        int success = 0;
        for (Long id : targets) {
            try {
                loadMarketTool(id);
                success++;
            } catch (McpEndpointNotAllowedException e) {
                throw e;
            } catch (RuntimeException e) {
                // 逐条隔离：一条失败不影响其它条；只记类型，不记凭据/地址
                log.warn("mcp market tool load failed, marketToolId={}, errorType={}",
                        id, e.getClass().getName());
            }
        }
        return success;
    }

    // ------------------------------------------------------------------ 工具

    @Override
    public IPage<McpToolVO> pageTools(McpPageRequest request) {
        String tenant = tenantId();
        String keyword = trimToNull(request == null ? null : request.getKeyword());
        String status = trimToNull(request == null ? null : request.getStatus());
        String type = trimToNull(request == null ? null : request.getType());
        Page<McpToolDO> page = new Page<>(current(request), size(request));
        IPage<McpToolDO> result = toolMapper.selectPage(page, toolQuery(tenant, keyword, type, status));
        return result.convert(this::toToolVO);
    }

    @Override
    public List<McpToolVO> listTools(String keyword, String type, String status) {
        String tenant = tenantId();
        List<McpToolDO> rows = toolMapper.selectList(
                toolQuery(tenant, trimToNull(keyword), trimToNull(type), trimToNull(status)));
        return rows.stream().map(this::toToolVO).toList();
    }

    @Override
    public List<McpToolVO> listEnabledTools() {
        String tenant = tenantId();
        List<McpToolDO> rows = toolMapper.selectList(
                Wrappers.lambdaQuery(McpToolDO.class)
                        .eq(McpToolDO::getTenantId, tenant)
                        .eq(McpToolDO::getDelFlag, "0")
                        .eq(McpToolDO::getStatus, "ENABLED")
                        .orderByAsc(McpToolDO::getName));
        return rows.stream().map(this::toToolVO).toList();
    }

    @Override
    public McpToolVO getTool(long id) {
        return toToolVO(loadTool(id, tenantId()));
    }

    @Override
    public int deleteTools(List<Long> ids) {
        String tenant = tenantId();
        List<Long> targets = requireIds(ids);
        // 不检查 ai_agent_skill/意图节点引用（超出 F12 行）：返回计数，不脆断
        return toolMapper.delete(Wrappers.lambdaQuery(McpToolDO.class)
                .eq(McpToolDO::getTenantId, tenant)
                .eq(McpToolDO::getDelFlag, "0")
                .in(McpToolDO::getId, targets));
    }

    @Override
    public void updateToolStatus(long id, String status) {
        String validated = requireStatus(status);
        McpToolDO tool = loadTool(id, tenantId());
        tool.setStatus(validated);
        toolMapper.updateById(tool);
    }

    @Override
    public String exportTools(String keyword, String type, String status) {
        String tenant = tenantId();
        List<McpToolDO> rows = toolMapper.selectList(
                toolQuery(tenant, trimToNull(keyword), trimToNull(type), trimToNull(status)));
        List<Map<String, Object>> export = new ArrayList<>();
        for (McpToolDO row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("name", row.getName());
            item.put("description", row.getDescription());
            item.put("type", row.getType());
            item.put("status", row.getStatus());
            item.put("tenantId", row.getTenantId());
            item.put("baseUrl", McpConfigJson.baseUrl(row.getConfigJson()));
            item.put("inputSchema", McpConfigJson.inputSchema(row.getConfigJson()));
            item.put("requiresConfirm", McpConfigJson.requiresConfirm(row.getConfigJson()));
            item.put("configJson", maskedConfigNode(row.getConfigJson()));
            item.put("createTime", row.getCreateTime());
            item.put("updateTime", row.getUpdateTime());
            export.add(item);
        }
        return writeJson(export);
    }

    @Override
    public McpTestResult testTool(long id) {
        String tenant = tenantId();
        McpToolDO tool = toolMapper.selectOne(Wrappers.lambdaQuery(McpToolDO.class)
                .eq(McpToolDO::getId, id)
                .eq(McpToolDO::getTenantId, tenant)
                .eq(McpToolDO::getDelFlag, "0"));
        if (tool == null) {
            return new McpTestResult(false, null, "工具不存在");
        }
        String baseUrl = McpConfigJson.baseUrl(tool.getConfigJson());
        if (baseUrl == null || baseUrl.isBlank()) {
            return new McpTestResult(false, null, "连接失败");
        }
        URI endpoint = URI.create(McpRemoteCatalogClient.normalizeEndpoint(baseUrl));
        // 未批准 ⇒ 零网络 + 具名 403
        allowList.requireAllowed(endpoint);
        Map<String, String> headers = McpConfigJson.resolveHeaders(tool.getConfigJson());
        try {
            List<Tool> tools = remoteClient.discover(endpoint.toString(), headers);
            return new McpTestResult(true, tools.size(), "连接成功");
        } catch (RuntimeException e) {
            // MCP SDK 对非 2xx 只给通用消息；失败路径再用同一已批准目标探一次状态码，
            // 以便把 401/403 单独归类为"鉴权失败"（成功路径没有这次探针）。
            if (isAuthFailure(e) || isAuthStatus(remoteClient.probeStatus(endpoint.toString(), headers))) {
                return new McpTestResult(false, null, "鉴权失败");
            }
            log.warn("mcp tool connection test failed, toolId={}, errorType={}",
                    id, e.getClass().getName());
            return new McpTestResult(false, null, "连接失败 (" + rootCauseType(e) + ")");
        }
    }

    // ------------------------------------------------------------------ helpers

    private String tenantId() {
        ExecutionPrincipal principal = PrincipalContext.require();
        return principal.tenantId();
    }

    private McpMarketDO loadMarket(long id, String tenant) {
        McpMarketDO market = marketMapper.selectOne(Wrappers.lambdaQuery(McpMarketDO.class)
                .eq(McpMarketDO::getId, id)
                .eq(McpMarketDO::getTenantId, tenant)
                .eq(McpMarketDO::getDelFlag, "0"));
        if (market == null) {
            throw new McpAdminException(McpAdminError.NOT_FOUND);
        }
        return market;
    }

    private McpToolDO loadTool(long id, String tenant) {
        McpToolDO tool = toolMapper.selectOne(Wrappers.lambdaQuery(McpToolDO.class)
                .eq(McpToolDO::getId, id)
                .eq(McpToolDO::getTenantId, tenant)
                .eq(McpToolDO::getDelFlag, "0"));
        if (tool == null) {
            throw new McpAdminException(McpAdminError.NOT_FOUND);
        }
        return tool;
    }

    private static LambdaQueryWrapper<McpMarketDO> marketQuery(
            String tenant, String keyword, String status) {
        return Wrappers.lambdaQuery(McpMarketDO.class)
                .eq(McpMarketDO::getTenantId, tenant)
                .eq(McpMarketDO::getDelFlag, "0")
                .eq(status != null, McpMarketDO::getStatus, status)
                .and(keyword != null, wrapper -> wrapper
                        .like(McpMarketDO::getName, keyword)
                        .or()
                        .like(McpMarketDO::getDescription, keyword))
                .orderByDesc(McpMarketDO::getUpdateTime);
    }

    private static LambdaQueryWrapper<McpToolDO> toolQuery(
            String tenant, String keyword, String type, String status) {
        return Wrappers.lambdaQuery(McpToolDO.class)
                .eq(McpToolDO::getTenantId, tenant)
                .eq(McpToolDO::getDelFlag, "0")
                .eq(type != null, McpToolDO::getType, type)
                .eq(status != null, McpToolDO::getStatus, status)
                .and(keyword != null, wrapper -> wrapper
                        .like(McpToolDO::getName, keyword)
                        .or()
                        .like(McpToolDO::getDescription, keyword))
                .orderByDesc(McpToolDO::getUpdateTime);
    }

    private McpToolVO toToolVO(McpToolDO tool) {
        return McpToolVO.builder()
                .id(tool.getId())
                .name(tool.getName())
                .description(tool.getDescription())
                .type(tool.getType())
                .status(tool.getStatus())
                .baseUrl(McpConfigJson.baseUrl(tool.getConfigJson()))
                .inputSchema(McpConfigJson.inputSchema(tool.getConfigJson()))
                .requiresConfirm(McpConfigJson.requiresConfirm(tool.getConfigJson()))
                .configJson(McpConfigJson.maskConfigJson(tool.getConfigJson()))
                .authMasked(McpConfigJson.hasHeaders(tool.getConfigJson()) ? "***" : null)
                .createTime(tool.getCreateTime())
                .updateTime(tool.getUpdateTime())
                .build();
    }

    private static McpMarketVO toMarketVO(McpMarketDO market) {
        return McpMarketVO.builder()
                .id(market.getId())
                .name(market.getName())
                .url(market.getUrl())
                .description(market.getDescription())
                .status(market.getStatus())
                .authConfig(McpConfigJson.maskAuthConfig(market.getAuthConfig()))
                .createTime(market.getCreateTime())
                .updateTime(market.getUpdateTime())
                .build();
    }

    private static McpMarketToolVO toMarketToolVO(McpMarketToolDO row) {
        return McpMarketToolVO.builder()
                .id(row.getId())
                .marketId(row.getMarketId())
                .toolName(row.getToolName())
                .toolDescription(row.getToolDescription())
                .toolVersion(row.getToolVersion())
                .isLoaded(row.getIsLoaded() != null && row.getIsLoaded() != 0)
                .localToolId(row.getLocalToolId())
                .createTime(row.getCreateTime())
                .updateTime(row.getUpdateTime())
                .build();
    }

    private JsonNode maskedConfigNode(String configJson) {
        String masked = McpConfigJson.maskConfigJson(configJson);
        if (masked == null) {
            return null;
        }
        try {
            return objectMapper.readTree(masked);
        } catch (Exception e) {
            return objectMapper.getNodeFactory().textNode(masked);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize MCP admin payload", e);
        }
    }

    private static String versionOf(Tool tool) {
        Map<String, Object> meta = tool.meta();
        if (meta == null) {
            return null;
        }
        Object version = meta.get("version");
        return version == null ? null : String.valueOf(version);
    }

    private static boolean isAuthFailure(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            String simpleName = cursor.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            if (simpleName.contains("unauthorized") || simpleName.contains("forbidden")) {
                return true;
            }
            String message = cursor.getMessage();
            if (message != null && AUTH_FAILURE.matcher(message).find()) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private static boolean isAuthStatus(int status) {
        return status == 401 || status == 403;
    }

    private static String rootCauseType(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor.getClass().getSimpleName();
    }

    private static String requireStatus(String status) {
        String trimmed = trimToNull(status);
        if (trimmed == null || !STATUSES.contains(trimmed.toUpperCase(Locale.ROOT))) {
            throw new McpAdminException(McpAdminError.INVALID_STATUS);
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }

    private static List<Long> requireIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new McpAdminException(McpAdminError.INVALID_ARGUMENT);
        }
        Set<Long> unique = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id == null) {
                throw new McpAdminException(McpAdminError.INVALID_ARGUMENT);
            }
            unique.add(id);
        }
        return List.copyOf(unique);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static long current(McpPageRequest request) {
        return normalizeCurrent(request == null ? 1L : request.getCurrent());
    }

    private static long size(McpPageRequest request) {
        return normalizeSize(request == null ? DEFAULT_PAGE_SIZE : request.getSize());
    }

    private static long normalizeCurrent(long current) {
        return current < 1 ? 1 : current;
    }

    private static long normalizeSize(long size) {
        if (size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
