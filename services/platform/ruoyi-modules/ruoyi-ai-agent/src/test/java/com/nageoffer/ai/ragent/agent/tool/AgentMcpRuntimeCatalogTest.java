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

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.agent.dao.entity.McpToolDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.McpToolMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.integration.SaasCapabilityBoundary;
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import com.nageoffer.ai.ragent.mcp.admin.AgentMcpAllowListProperties;
import com.nageoffer.ai.ragent.mcp.admin.McpEndpointAllowList;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F12-M2 运行期受控 MCP 目录（单元级契约，不发起真实网络）。
 *
 * <p>被钉住的语义：
 * <ul>
 *   <li>旧启动连接表命中时直接返回，<b>不</b>查询受管工具表、<b>不</b>触发运行期解析；</li>
 *   <li>受管工具只在当前租户作用域内可见（tenant/status/del_flag 三个谓词都在 SQL 片段里），
 *       跨租户/DISABLED/已删除行一律不可用；</li>
 *   <li>allow-list 未批准、{@code env:} 凭据缺失都在<b>任何连接之前</b>拒绝（零网络）；</li>
 *   <li>同键缓存命中只连一次；TTL 过期重连；超过上限按 LRU 淘汰并关闭连接；</li>
 *   <li>连接/initialize/listTools/工具名不匹配等失败一律返回 null，<b>不抛到对话主链</b>。</li>
 * </ul>
 *
 * <p>网络替换点：{@link AgentMcpRuntimeCatalog.ClientFactory} 是包内可见的测试缝，
 * 记录连接次数/URL/headers；真实协议面由 {@code AgentMcpRuntimeCatalogProtocolTest} 覆盖。
 */
@Tag("dev")
class AgentMcpRuntimeCatalogTest {

    private static final String TENANT = "T1";
    private static final String BASE = "http://127.0.0.1:18080";
    private static final String OTHER_BASE = "http://127.0.0.1:18081";

    private McpToolMapper toolMapper;
    private AgentMcpRuntimeProperties properties;
    private AgentMcpAllowListProperties allowProperties;
    private RecordingBinder binder;

    @BeforeEach
    void setUp() {
        ensureTableMetadata();
        toolMapper = mock(McpToolMapper.class);
        properties = new AgentMcpRuntimeProperties();
        allowProperties = new AgentMcpAllowListProperties();
        binder = new RecordingBinder();
        PrincipalContext.set(new ExecutionPrincipal(TENANT, "2101", "platform:" + TENANT + ":2101",
                1, 1, Set.of(), "jti", "platform", 0, Long.MAX_VALUE));
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    // ------------------------------------------------------------------ 受管工具解析

    @Test
    @DisplayName("受管 ENABLED 行：租户作用域内查库 → allow-list 通过 → 连接一次并缓存")
    void managedEnabledToolResolvesOnceAndIsCached() {
        allow(BASE);
        when(toolMapper.selectList(any())).thenReturn(List.of(row("alpha", BASE)));
        FakeFactory factory = new FakeFactory(clientReturning("alpha"));
        AgentMcpRuntimeCatalog catalog = catalog(factory);

        AgentMcpClients.RemoteTool first = catalog.resolve("alpha");
        AgentMcpClients.RemoteTool second = catalog.resolve("alpha");

        assertThat(first).isNotNull();
        assertThat(first.definition().name()).isEqualTo("alpha");
        assertThat(second).isSameAs(first);
        assertThat(factory.calls.get()).as("同键缓存命中只允许一次连接").isEqualTo(1);
        assertThat(catalog.connectCount()).isEqualTo(1);
        assertThat(catalog.resolveCount()).isEqualTo(2);
        assertThat(catalog.deniedCount()).isZero();
        assertThat(binder.tenant.get()).as("查询必须在租户作用域内执行").isEqualTo(TENANT);
        assertThat(binder.runs.get()).isGreaterThanOrEqualTo(1);
        assertThat(factory.lastUrl).isEqualTo(BASE + "/mcp");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<McpToolDO>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(toolMapper, atLeastOnce()).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment())
                .as("租户/启用/未删除谓词必须出现在 SQL 片段里")
                .contains("tenant_id")
                .contains("name")
                .contains("status")
                .contains("del_flag");
    }

    @Test
    @DisplayName("未批准 baseUrl：allow-list 拒绝，零连接尝试且计数为 denied")
    void unapprovedEndpointIsDeniedBeforeAnyConnectAttempt() {
        // allow-list 为空 = fail-closed
        when(toolMapper.selectList(any())).thenReturn(List.of(row("alpha", BASE)));
        FakeFactory factory = new FakeFactory(clientReturning("alpha"));
        AgentMcpRuntimeCatalog catalog = catalog(factory);

        assertThat(catalog.resolve("alpha")).isNull();

        assertThat(factory.calls.get()).as("未批准目标必须在任何连接之前被拒绝").isZero();
        assertThat(catalog.connectCount()).isZero();
        assertThat(catalog.deniedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("DISABLED / 已删 / 跨租户行：即使被持久层返回也不可用（Java 侧再过滤）")
    void disabledDeletedAndCrossTenantRowsAreUnavailable() {
        allow(BASE);
        FakeFactory factory = new FakeFactory(clientReturning("alpha"));
        AgentMcpRuntimeCatalog catalog = catalog(factory);

        when(toolMapper.selectList(any())).thenReturn(List.of(disabled("alpha", BASE)));
        assertThat(catalog.resolve("alpha")).isNull();

        when(toolMapper.selectList(any())).thenReturn(List.of(deleted("alpha", BASE)));
        assertThat(catalog.resolve("alpha")).isNull();

        when(toolMapper.selectList(any())).thenReturn(List.of(otherTenant("alpha", BASE)));
        assertThat(catalog.resolve("alpha")).isNull();

        assertThat(factory.calls.get()).as("不可用行不得触发连接").isZero();
        assertThat(catalog.connectCount()).isZero();
    }

    @Test
    @DisplayName("重名受管行：取 update_time 最新的一条")
    void duplicateRowsChooseLatestUpdateTime() {
        allow(BASE);
        allow(OTHER_BASE);
        McpToolDO older = row("alpha", BASE);
        older.setUpdateTime(new Date(1_000L));
        McpToolDO newer = row("alpha", OTHER_BASE);
        newer.setUpdateTime(new Date(2_000L));
        when(toolMapper.selectList(any())).thenReturn(List.of(older, newer));
        FakeFactory factory = new FakeFactory(clientReturning("alpha"));

        assertThat(catalog(factory).resolve("alpha")).isNotNull();

        assertThat(factory.lastUrl).as("必须使用最新行登记的地址").isEqualTo(OTHER_BASE + "/mcp");
    }

    @Test
    @DisplayName("env: 凭据缺失：连接前拒绝（零连接），错误不含值")
    void missingEnvCredentialIsDeniedBeforeConnecting() {
        allow(BASE);
        String config = "{\"baseUrl\":\"" + BASE
                + "\",\"headers\":{\"Authorization\":\"env:F12_M2_MISSING_CREDENTIAL_20261018\"}}";
        when(toolMapper.selectList(any())).thenReturn(List.of(rowWithConfig("alpha", config)));
        FakeFactory factory = new FakeFactory(clientReturning("alpha"));
        AgentMcpRuntimeCatalog catalog = catalog(factory);

        assertThat(catalog.resolve("alpha")).isNull();

        assertThat(factory.calls.get()).as("凭据不可解析时不得发起连接").isZero();
        assertThat(catalog.connectCount()).isZero();
        assertThat(catalog.deniedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("env: 凭据可解析：解析值进入连接头（不触网，仅交给连接工厂）")
    void resolvedEnvCredentialIsPassedToTheConnection() {
        String path = System.getenv("PATH");
        org.junit.jupiter.api.Assumptions.assumeTrue(path != null && !path.isBlank(),
                "PATH 必须存在才能验证 env: 解析");
        allow(BASE);
        String config = "{\"baseUrl\":\"" + BASE + "\",\"headers\":{\"X-Credential\":\"env:PATH\"}}";
        when(toolMapper.selectList(any())).thenReturn(List.of(rowWithConfig("alpha", config)));
        FakeFactory factory = new FakeFactory(clientReturning("alpha"));

        assertThat(catalog(factory).resolve("alpha")).isNotNull();

        assertThat(factory.lastHeaders).containsEntry("X-Credential", path);
    }

    @Test
    @DisplayName("无主体 / 空白 toolId：直接不可用，不查库不连接")
    void missingPrincipalOrBlankToolIdIsUnavailable() {
        allow(BASE);
        FakeFactory factory = new FakeFactory(clientReturning("alpha"));
        AgentMcpRuntimeCatalog catalog = catalog(factory);

        assertThat(catalog.resolve("  ")).isNull();

        PrincipalContext.clear();
        assertThatCode(() -> catalog.resolve("alpha")).doesNotThrowAnyException();
        assertThat(catalog.resolve("alpha")).isNull();

        verifyNoInteractions(toolMapper);
        assertThat(factory.calls.get()).isZero();
        assertThat(catalog.connectCount()).isZero();
    }

    @Test
    @DisplayName("租户作用域端口缺席（独立应用）：按原样直查，不伪造空结果")
    void absentTenantScopeBinderQueriesDirectly() {
        allow(BASE);
        when(toolMapper.selectList(any())).thenReturn(List.of(row("alpha", BASE)));
        FakeFactory factory = new FakeFactory(clientReturning("alpha"));
        AgentMcpRuntimeCatalog catalog = new AgentMcpRuntimeCatalog(toolMapper, providerOf(allowList()), providerOf(null),
                properties, factory);

        assertThat(catalog.resolve("alpha")).isNotNull();
        assertThat(factory.calls.get()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 缓存与失败面

    @Test
    @DisplayName("TTL 过期：同一键重连（旧连接关闭）")
    void expiredCacheEntryReconnects() throws InterruptedException {
        allow(BASE);
        properties.setCacheTtl(Duration.ofMillis(1));
        when(toolMapper.selectList(any())).thenReturn(List.of(row("alpha", BASE)));
        McpClientWrapper firstClient = clientReturning("alpha");
        McpClientWrapper secondClient = clientReturning("alpha");
        FakeFactory factory = new FakeFactory(firstClient, secondClient);
        AgentMcpRuntimeCatalog catalog = catalog(factory);

        assertThat(catalog.resolve("alpha")).isNotNull();
        Thread.sleep(20L);
        assertThat(catalog.resolve("alpha")).isNotNull();

        assertThat(factory.calls.get()).isEqualTo(2);
        verify(firstClient).close();
    }

    @Test
    @DisplayName("缓存上限：LRU 淘汰并关闭被淘汰连接")
    void maxCachedClientsEvictsAndClosesTheOldest() {
        allow(BASE);
        properties.setMaxCachedClients(1);
        McpToolDO alpha = row("alpha", BASE);
        McpToolDO beta = row("beta", BASE);
        when(toolMapper.selectList(any())).thenReturn(List.of(alpha), List.of(beta));
        McpClientWrapper alphaClient = clientReturning("alpha");
        McpClientWrapper betaClient = clientReturning("beta");
        FakeFactory factory = new FakeFactory(alphaClient, betaClient);
        AgentMcpRuntimeCatalog catalog = catalog(factory);

        assertThat(catalog.resolve("alpha")).isNotNull();
        assertThat(catalog.resolve("beta")).isNotNull();

        verify(alphaClient).close();
        verify(betaClient, never()).close();
    }

    @Test
    @DisplayName("连接/初始化/工具清单失败：WARN 后返回 null，不抛到对话主链")
    void connectAndDiscoveryFailuresReturnNull() {
        allow(BASE);
        when(toolMapper.selectList(any())).thenReturn(List.of(row("alpha", BASE)));

        FakeFactory refused = new FakeFactory();
        refused.failure = new IllegalStateException("connection refused");
        assertThatCode(() -> assertThat(catalog(refused).resolve("alpha")).isNull())
                .doesNotThrowAnyException();

        McpClientWrapper failingInit = mock(McpClientWrapper.class);
        when(failingInit.initialize()).thenReturn(Mono.error(new IllegalStateException("initialize failed")));
        assertThat(catalog(new FakeFactory(failingInit)).resolve("alpha")).isNull();
        verify(failingInit).close();

        McpClientWrapper wrongTools = clientReturning("other");
        assertThat(catalog(new FakeFactory(wrongTools)).resolve("alpha")).isNull();
        verify(wrongTools).close();
    }

    // ------------------------------------------------------------------ AgentMcpClients 委托

    @Test
    @DisplayName("旧启动连接表命中：直接返回，运行期目录一次都不被调用")
    void startupTableHitWinsWithoutRuntimeLookup() throws Exception {
        AgentMcpRuntimeCatalog runtime = mock(AgentMcpRuntimeCatalog.class);
        AgentMcpClients clients = new AgentMcpClients(new AgentMcpProperties(), providerOf((SaasCapabilityBoundary) null));
        setField(clients, "runtimeCatalog", providerOf(runtime));
        AgentMcpClients.RemoteTool startupTool = remote("alpha", mock(McpClientWrapper.class));
        startupTools(clients).put("alpha", startupTool);

        assertThat(clients.get("alpha")).isSameAs(startupTool);
        verifyNoInteractions(runtime);
    }

    @Test
    @DisplayName("运行期目录 Bean 缺席：get 返回 null，行为与旧门关闭时一致")
    void absentRuntimeCatalogProviderReturnsNull() {
        AgentMcpClients clients = new AgentMcpClients(new AgentMcpProperties(), providerOf((SaasCapabilityBoundary) null));

        assertThatCode(() -> assertThat(clients.get("alpha")).isNull()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("旧表未命中：委托运行期目录")
    void startupMissDelegatesToRuntimeCatalog() throws Exception {
        AgentMcpRuntimeCatalog runtime = mock(AgentMcpRuntimeCatalog.class);
        AgentMcpClients.RemoteTool remote = remote("alpha", mock(McpClientWrapper.class));
        when(runtime.resolve("alpha")).thenReturn(remote);
        AgentMcpClients clients = new AgentMcpClients(new AgentMcpProperties(), providerOf((SaasCapabilityBoundary) null));
        setField(clients, "runtimeCatalog", providerOf(runtime));

        assertThat(clients.get("alpha")).isSameAs(remote);
        verify(runtime).resolve("alpha");
    }

    @Test
    @DisplayName("Spring 装配：@Bean 构造的 AgentMcpClients 也会拿到可选运行期目录 provider")
    void springInjectsOptionalRuntimeCatalogProvider() {
        AgentMcpRuntimeCatalog runtime = mock(AgentMcpRuntimeCatalog.class);
        AgentMcpClients.RemoteTool remote = remote("alpha", mock(McpClientWrapper.class));
        when(runtime.resolve("alpha")).thenReturn(remote);

        new ApplicationContextRunner()
                .withUserConfiguration(AgentMcpClients.class)
                .withPropertyValues("ragent.engine.type=agent")
                .withBean(AgentMcpRuntimeCatalog.class, () -> runtime)
                .run(context -> {
                    assertThat(context).hasSingleBean(AgentMcpClients.class);
                    assertThat(context.getBean(AgentMcpClients.class).get("alpha")).isSameAs(remote);
                });
    }

    // ------------------------------------------------------------------ 辅助

    private AgentMcpRuntimeCatalog catalog(FakeFactory factory) {
        return new AgentMcpRuntimeCatalog(toolMapper, providerOf(allowList()), providerOf(binder), properties, factory);
    }

    private McpEndpointAllowList allowList() {
        return new McpEndpointAllowList(allowProperties);
    }

    private void allow(String endpoint) {
        List<String> endpoints = new ArrayList<>(allowProperties.getEndpoints());
        endpoints.add(endpoint);
        allowProperties.setEndpoints(endpoints);
    }

    private static McpToolDO row(String name, String baseUrl) {
        return rowWithConfig(name, "{\"baseUrl\":\"" + baseUrl + "\"}");
    }

    private static McpToolDO rowWithConfig(String name, String configJson) {
        return McpToolDO.builder()
                .name(name)
                .type("REMOTE")
                .status("ENABLED")
                .delFlag("0")
                .tenantId(TENANT)
                .configJson(configJson)
                .updateTime(new Date())
                .build();
    }

    private static McpToolDO disabled(String name, String baseUrl) {
        McpToolDO row = row(name, baseUrl);
        row.setStatus("DISABLED");
        return row;
    }

    private static McpToolDO deleted(String name, String baseUrl) {
        McpToolDO row = row(name, baseUrl);
        row.setDelFlag("2");
        return row;
    }

    private static McpToolDO otherTenant(String name, String baseUrl) {
        McpToolDO row = row(name, baseUrl);
        row.setTenantId("T2");
        return row;
    }

    private static Tool tool(String name) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("value", Map.of("type", "string"));
        return Tool.builder()
                .name(name)
                .description(name + " 描述")
                .inputSchema(new JsonSchema("object", properties, List.of("value"), null, null, null))
                .build();
    }

    private static AgentMcpClients.RemoteTool remote(String name, McpClientWrapper client) {
        return new AgentMcpClients.RemoteTool(tool(name), client);
    }

    private static McpClientWrapper clientReturning(String... toolNames) {
        McpClientWrapper client = mock(McpClientWrapper.class);
        when(client.initialize()).thenReturn(Mono.<Void>empty());
        List<Tool> tools = new ArrayList<>();
        for (String name : toolNames) {
            tools.add(tool(name));
        }
        when(client.listTools()).thenReturn(Mono.just(tools));
        return client;
    }

    private static <T> ObjectProvider<T> providerOf(T value) {
        return new FixedProvider<>(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, AgentMcpClients.RemoteTool> startupTools(AgentMcpClients clients) throws Exception {
        Field field = AgentMcpClients.class.getDeclaredField("tools");
        field.setAccessible(true);
        return (Map<String, AgentMcpClients.RemoteTool>) field.get(clients);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** 纯单元测试里 {@code LambdaQueryWrapper.getSqlSegment()} 需要 MyBatis-Plus 的列缓存。 */
    private static void ensureTableMetadata() {
        if (TableInfoHelper.getTableInfo(McpToolDO.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), McpToolDO.class);
        }
    }

    /** 记录连接尝试与参数的测试缝；{@code supplier} 为空时抛错（模拟连接失败）。 */
    private static final class FakeFactory implements AgentMcpRuntimeCatalog.ClientFactory {

        private final Deque<McpClientWrapper> clients = new ArrayDeque<>();
        private final AtomicInteger calls = new AtomicInteger();
        private volatile RuntimeException failure;
        private volatile String lastUrl;
        private volatile Map<String, String> lastHeaders = Map.of();

        private FakeFactory(McpClientWrapper... next) {
            for (McpClientWrapper client : next) {
                clients.add(client);
            }
        }

        @Override
        public McpClientWrapper connect(String serverName, String url, Map<String, String> headers, Duration timeout) {
            calls.incrementAndGet();
            lastUrl = url;
            lastHeaders = headers == null ? Map.of() : new LinkedHashMap<>(headers);
            if (failure != null) {
                throw failure;
            }
            McpClientWrapper next = clients.poll();
            if (next == null) {
                throw new IllegalStateException("no stub client");
            }
            return next;
        }
    }

    private static final class RecordingBinder implements TenantScopeBinder {

        private final AtomicReference<String> tenant = new AtomicReference<>();
        private final AtomicInteger runs = new AtomicInteger();
        private volatile boolean execute = true;

        @Override
        public void runWithTenant(String tenantId, Runnable handle) {
            tenant.set(tenantId);
            runs.incrementAndGet();
            if (execute) {
                handle.run();
            }
        }
    }

    private static final class FixedProvider<T> implements ObjectProvider<T> {

        private final T value;

        private FixedProvider(T value) {
            this.value = value;
        }

        @Override
        public T getObject() {
            return value;
        }

        @Override
        public T getObject(Object... args) {
            return value;
        }

        @Override
        public T getIfAvailable() {
            return value;
        }

        @Override
        public T getIfUnique() {
            return value;
        }
    }
}
