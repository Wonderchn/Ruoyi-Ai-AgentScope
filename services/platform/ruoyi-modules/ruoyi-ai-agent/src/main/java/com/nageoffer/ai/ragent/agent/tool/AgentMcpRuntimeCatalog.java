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

package com.nageoffer.ai.ragent.agent.tool;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.dao.entity.McpToolDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.McpToolMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import com.nageoffer.ai.ragent.mcp.admin.McpAdminException;
import com.nageoffer.ai.ragent.mcp.admin.McpConfigJson;
import com.nageoffer.ai.ragent.mcp.admin.McpEndpointAllowList;
import com.nageoffer.ai.ragent.mcp.admin.McpEndpointNotAllowedException;
import com.nageoffer.ai.ragent.mcp.admin.McpRemoteCatalogClient;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * F12-M2 运行期受控 MCP 工具目录：让 F12-M1 管理的 {@code ai_mcp_tool} 行在 Agent 运行期可用。
 *
 * <p><b>为什么是"按需惰性"</b>：旧启动期连接（{@link AgentMcpClients#init()}）由
 * {@code SaasCapabilityBoundary.AGENT_MCP_STARTUP} 恒关，本类<b>不改变</b>该总门；
 * 只有当意图树节点点名了某个 {@code mcpToolId}、而旧启动表里没有时，才在 Agent 线程上
 * 为该工具解析一次受管行并按需连接。
 *
 * <p><b>安全边界（顺序即证据）</b>：
 * <ol>
 *   <li>先查当前租户的受管行（{@code tenant_id=? AND name=? AND status='ENABLED' AND del_flag='0'}），
 *       查不到就不可用；</li>
 *   <li>{@code baseUrl} 必须经 {@link McpEndpointAllowList#requireAllowed(URI)} 批准——
 *       未批准时<b>零网络</b>（不建连、不发 initialize）；</li>
 *   <li>{@code headers} 里的 {@code env:NAME} 在连接前解析；变量缺失同样<b>零网络</b>；</li>
 *   <li>连接器禁用自动重定向，凭据头不会被带到非白名单主机；</li>
 *   <li>任何异常（连接/初始化/发现失败、租户拦截器拒绝）都 WARN 后返回 {@code null}，
 *       <b>不抛到对话主链</b>；上层 {@code AgentToolCatalog} 会把它记入
 *       {@code unavailableToolIds}。</li>
 * </ol>
 *
 * <p><b>缓存</b>：键为 {@code (tenantId, toolId, baseUrl, headersHash)}；TTL 默认 5 分钟，
 * 上限默认 32 条，超出按 LRU 淘汰并关闭连接，进程销毁时统一关闭。
 *
 * <p><b>可观测</b>：日志键 {@value #METRIC_RESOLVE}（每次解析的结果）、
 * {@value #METRIC_CONNECT}（真实连接尝试）、{@value #METRIC_DENIED}（连接前的策略拒绝）。
 * 日志只记 toolId 与符号码，<b>不</b>输出 headers、凭据值或完整 URL 查询串。
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@EnableConfigurationProperties(AgentMcpRuntimeProperties.class)
public class AgentMcpRuntimeCatalog {

    static final String METRIC_RESOLVE = "mcp_runtime_resolve";
    static final String METRIC_CONNECT = "mcp_runtime_connect";
    static final String METRIC_DENIED = "mcp_runtime_denied";

    private static final String STATUS_ENABLED = "ENABLED";
    private static final String NOT_DELETED = "0";
    private static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(5);
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final int DEFAULT_MAX_CACHED_CLIENTS = 32;
    private static final String CLIENT_NAME = "agent-mcp-runtime";

    private final McpToolMapper toolMapper;
    private final ObjectProvider<McpEndpointAllowList> allowList;
    private final ObjectProvider<TenantScopeBinder> tenantScopeBinder;
    private final AgentMcpRuntimeProperties properties;
    private final ClientFactory clientFactory;

    private final AtomicLong resolveCount = new AtomicLong();
    private final AtomicLong connectCount = new AtomicLong();
    private final AtomicLong deniedCount = new AtomicLong();

    private final Map<String, CachedTool> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedTool> eldest) {
            if (size() <= maxCachedClients()) {
                return false;
            }
            closeQuietly(eldest.getValue().remote().client());
            return true;
        }
    };

    @Autowired
    public AgentMcpRuntimeCatalog(McpToolMapper toolMapper,
                                  ObjectProvider<McpEndpointAllowList> allowList,
                                  ObjectProvider<TenantScopeBinder> tenantScopeBinder,
                                  AgentMcpRuntimeProperties properties) {
        this(toolMapper, allowList, tenantScopeBinder, properties,
                (name, url, headers, timeout) -> connectDefault(url, headers, timeout));
    }

    /** 测试缝：把"真实连接"替换成可观测的工厂；生产构造器始终走 {@link McpClientBuilder}。 */
    AgentMcpRuntimeCatalog(McpToolMapper toolMapper,
                           ObjectProvider<McpEndpointAllowList> allowList,
                           ObjectProvider<TenantScopeBinder> tenantScopeBinder,
                           AgentMcpRuntimeProperties properties,
                           ClientFactory clientFactory) {
        this.toolMapper = toolMapper;
        this.allowList = allowList;
        this.tenantScopeBinder = tenantScopeBinder;
        this.properties = properties == null ? new AgentMcpRuntimeProperties() : properties;
        this.clientFactory = clientFactory;
    }

    /**
     * 解析运行期可用的远端工具。
     *
     * @return 可直接挂载的 {@link AgentMcpClients.RemoteTool}；任何不可用/失败都返回 {@code null}
     */
    public AgentMcpClients.RemoteTool resolve(String toolId) {
        resolveCount.incrementAndGet();
        String normalizedToolId = toolId == null ? null : toolId.trim();
        if (StrUtil.isBlank(normalizedToolId)) {
            log.debug("{} toolId=<blank> outcome=UNAVAILABLE", METRIC_RESOLVE);
            return null;
        }
        try {
            String tenantId = currentTenantId();
            if (tenantId == null) {
                return deny("NO_TENANT", normalizedToolId);
            }
            McpToolDO managed = loadManagedTool(tenantId, normalizedToolId);
            if (managed == null) {
                log.debug("{} toolId={} outcome=NOT_MANAGED", METRIC_RESOLVE, normalizedToolId);
                return null;
            }
            String baseUrl = McpConfigJson.baseUrl(managed.getConfigJson());
            if (StrUtil.isBlank(baseUrl)) {
                return deny("NO_ENDPOINT", normalizedToolId);
            }
            URI endpoint;
            try {
                endpoint = URI.create(McpRemoteCatalogClient.normalizeEndpoint(baseUrl));
            } catch (IllegalArgumentException e) {
                return deny("INVALID_ENDPOINT", normalizedToolId);
            }
            McpEndpointAllowList allow = allowList == null ? null : allowList.getIfAvailable();
            if (allow == null) {
                return deny("ALLOW_LIST_ABSENT", normalizedToolId);
            }
            try {
                allow.requireAllowed(endpoint);
            } catch (McpEndpointNotAllowedException e) {
                return deny(e.errorCode(), normalizedToolId);
            }
            Map<String, String> headers;
            try {
                headers = McpConfigJson.resolveHeaders(managed.getConfigJson());
            } catch (McpAdminException e) {
                // 消息只含变量名（McpConfigJson 保证），不含解析值
                return deny(e.errorCode(), normalizedToolId);
            }
            String cacheKey = cacheKey(tenantId, normalizedToolId, baseUrl, headers);
            AgentMcpClients.RemoteTool cached = cached(cacheKey, normalizedToolId);
            if (cached != null) {
                return cached;
            }
            return connect(normalizedToolId, endpoint.toString(), headers, cacheKey);
        } catch (Exception e) {
            // 对话主链不得因为一次工具解析失败而中断（brief §3 第 6 条）
            log.warn("{} toolId={} outcome=FAILED errorType={}",
                    METRIC_RESOLVE, normalizedToolId, e.getClass().getSimpleName());
            return null;
        }
    }

    @PreDestroy
    public void close() {
        synchronized (cache) {
            cache.values().forEach(cached -> closeQuietly(cached.remote().client()));
            cache.clear();
        }
    }

    // ------------------------------------------------------------------ 解析步骤

    private static String currentTenantId() {
        ExecutionPrincipal principal = PrincipalContext.get();
        if (principal == null) {
            return null;
        }
        String tenantId = principal.tenantId();
        return StrUtil.isBlank(tenantId) ? null : tenantId;
    }

    /**
     * 当前租户作用域内读取受管行。租户条件同时在 SQL 与 Java 侧生效：
     * SQL 是查询口径，Java 过滤是"持久层返回了不该返回的行也拒绝"的兜底。
     */
    private McpToolDO loadManagedTool(String tenantId, String toolId) {
        LambdaQueryWrapper<McpToolDO> query = Wrappers.lambdaQuery(McpToolDO.class)
                .eq(McpToolDO::getTenantId, tenantId)
                .eq(McpToolDO::getName, toolId)
                .eq(McpToolDO::getStatus, STATUS_ENABLED)
                .eq(McpToolDO::getDelFlag, NOT_DELETED)
                .orderByDesc(McpToolDO::getUpdateTime);
        List<McpToolDO> rows = queryInTenantScope(tenantId, query);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        List<McpToolDO> eligible = rows.stream()
                .filter(row -> row != null
                        && toolId.equals(row.getName())
                        && STATUS_ENABLED.equals(row.getStatus())
                        && NOT_DELETED.equals(row.getDelFlag())
                        && tenantId.equals(row.getTenantId()))
                .toList();
        if (eligible.isEmpty()) {
            return null;
        }
        if (eligible.size() > 1) {
            log.warn("{} toolId={} duplicateRows={} keepingLatest=true",
                    METRIC_RESOLVE, toolId, eligible.size());
        }
        return eligible.stream()
                .max(Comparator.comparing(McpToolDO::getUpdateTime,
                        Comparator.nullsFirst(Comparator.<Date>naturalOrder())))
                .orElse(null);
    }

    /**
     * Agent 线程没有登录主体：有 {@link TenantScopeBinder} 时必须把查询包进租户作用域；
     * 端口缺席（独立应用无拦截器）按原样直跑。端口"没执行"不冒充空结果——上抛由外层收敛成 null。
     */
    private List<McpToolDO> queryInTenantScope(String tenantId, LambdaQueryWrapper<McpToolDO> query) {
        TenantScopeBinder binder = tenantScopeBinder == null ? null : tenantScopeBinder.getIfAvailable();
        if (binder == null) {
            return toolMapper.selectList(query);
        }
        AtomicReference<List<McpToolDO>> holder = new AtomicReference<>();
        binder.runWithTenant(tenantId, () -> holder.set(toolMapper.selectList(query)));
        if (holder.get() == null) {
            throw new IllegalStateException("tenant scope binder did not execute the MCP tool query");
        }
        return holder.get();
    }

    private AgentMcpClients.RemoteTool cached(String cacheKey, String toolId) {
        synchronized (cache) {
            CachedTool entry = cache.get(cacheKey);
            if (entry == null) {
                return null;
            }
            if (System.nanoTime() < entry.expiresAtNanos()) {
                log.debug("{} toolId={} outcome=CACHE_HIT", METRIC_RESOLVE, toolId);
                return entry.remote();
            }
            cache.remove(cacheKey);
            closeQuietly(entry.remote().client());
            return null;
        }
    }

    private AgentMcpClients.RemoteTool connect(String toolId, String url, Map<String, String> headers,
                                               String cacheKey) {
        connectCount.incrementAndGet();
        Duration timeout = connectTimeout();
        McpClientWrapper client = null;
        try {
            client = clientFactory.connect(CLIENT_NAME, url, headers, timeout);
            client.initialize().block(timeout);
            List<Tool> discovered = client.listTools().block(timeout);
            Tool definition = discovered == null ? null : discovered.stream()
                    .filter(tool -> toolId.equals(tool.name()))
                    .findFirst()
                    .orElse(null);
            if (definition == null) {
                log.warn("{} toolId={} outcome=TOOL_NOT_DISCOVERED", METRIC_RESOLVE, toolId);
                closeQuietly(client);
                return null;
            }
            AgentMcpClients.RemoteTool remote = new AgentMcpClients.RemoteTool(definition, client);
            synchronized (cache) {
                cache.put(cacheKey, new CachedTool(remote, System.nanoTime() + cacheTtlNanos()));
            }
            log.info("{} toolId={} tools={}", METRIC_CONNECT, toolId, discovered.size());
            return remote;
        } catch (Exception e) {
            closeQuietly(client);
            log.warn("{} toolId={} outcome=CONNECT_FAILED errorType={}",
                    METRIC_RESOLVE, toolId, e.getClass().getSimpleName());
            return null;
        }
    }

    // ------------------------------------------------------------------ 配置/缓存辅助

    private Duration connectTimeout() {
        Duration timeout = properties.getConnectTimeout();
        return timeout == null || timeout.isZero() || timeout.isNegative()
                ? DEFAULT_CONNECT_TIMEOUT : timeout;
    }

    private long cacheTtlNanos() {
        Duration ttl = properties.getCacheTtl();
        if (ttl == null) {
            ttl = DEFAULT_CACHE_TTL;
        }
        return Math.max(0L, ttl.toNanos());
    }

    private int maxCachedClients() {
        int max = properties.getMaxCachedClients();
        return max <= 0 ? DEFAULT_MAX_CACHED_CLIENTS : max;
    }

    private static String cacheKey(String tenantId, String toolId, String baseUrl, Map<String, String> headers) {
        return tenantId + '\n' + toolId + '\n' + baseUrl + '\n' + headersHash(headers);
    }

    /**
     * 头名+头值的有序摘要：只参与缓存键相等性，<b>不写日志</b>，避免把凭据派生值落盘。
     */
    private static String headersHash(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return "none";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            headers.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                        digest.update((byte) 0);
                        String value = entry.getValue();
                        if (value != null) {
                            digest.update(value.getBytes(StandardCharsets.UTF_8));
                        }
                        digest.update((byte) 0);
                    });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            return "sha-256-unavailable";
        }
    }

    private AgentMcpClients.RemoteTool deny(String reasonCode, String toolId) {
        deniedCount.incrementAndGet();
        log.warn("{} toolId={} reason={}", METRIC_DENIED, toolId, reasonCode);
        return null;
    }

    private static void closeQuietly(McpClientWrapper client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (Exception e) {
            log.warn("{} outcome=CLOSE_FAILED errorType={}", METRIC_RESOLVE, e.getClass().getSimpleName());
        }
    }

    private static McpClientWrapper connectDefault(String url, Map<String, String> headers, Duration timeout) {
        McpClientBuilder builder = McpClientBuilder.create(CLIENT_NAME)
                .streamableHttpTransport(url)
                .customizeStreamableHttpClient(http -> http.followRedirects(HttpClient.Redirect.NEVER))
                .timeout(timeout)
                .initializationTimeout(timeout);
        if (headers != null && !headers.isEmpty()) {
            builder.headers(headers);
        }
        return builder.buildSync();
    }

    // ------------------------------------------------------------------ 计数（测试/诊断）

    long resolveCount() {
        return resolveCount.get();
    }

    long connectCount() {
        return connectCount.get();
    }

    long deniedCount() {
        return deniedCount.get();
    }

    @FunctionalInterface
    interface ClientFactory {
        McpClientWrapper connect(String serverName, String url, Map<String, String> headers, Duration timeout);
    }

    private record CachedTool(AgentMcpClients.RemoteTool remote, long expiresAtNanos) {
    }
}
