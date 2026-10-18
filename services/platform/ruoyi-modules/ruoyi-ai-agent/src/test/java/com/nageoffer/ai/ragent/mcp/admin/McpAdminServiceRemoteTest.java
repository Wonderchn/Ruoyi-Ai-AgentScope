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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.agent.dao.entity.McpMarketDO;
import com.nageoffer.ai.ragent.agent.dao.entity.McpMarketToolDO;
import com.nageoffer.ai.ragent.agent.dao.entity.McpToolDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.McpMarketMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.McpMarketToolMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.McpToolMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F12-M1 刷新 / 加载 / 连接测试：真实进程内 MCP stub + mock 持久层。
 *
 * <p>覆盖 brief §2.5 的语义：刷新幂等（added/updated）、上游失败零部分写、
 * 非 allow-list 零网络；加载重复 409、批量隔离、is_loaded/local_tool_id 回写；
 * 连接测试四态（可达/不可达/鉴权失败/未知 id）且不写库。
 */
@Tag("dev")
class McpAdminServiceRemoteTest {

    private static final String TENANT = "T1";

    private final McpMarketMapper marketMapper = mock(McpMarketMapper.class);
    private final McpMarketToolMapper marketToolMapper = mock(McpMarketToolMapper.class);
    private final McpToolMapper toolMapper = mock(McpToolMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentMcpAllowListProperties allowProperties = new AgentMcpAllowListProperties();
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private McpAdminServiceImpl service;

    @BeforeEach
    void setUp() {
        McpTestMetadata.ensureLoaded();
        rebuildService();
        PrincipalContext.set(new ExecutionPrincipal(TENANT, "2101", "platform:" + TENANT + ":2101",
                1, 1, Set.of(), "jti", "platform", 0, Long.MAX_VALUE));
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    @DisplayName("刷新幂等：两次刷新 → 第一次新增 2、第二次更新 2；行带 tenant 与完整 tool JSON")
    void refreshIsIdempotentByToolName() throws Exception {
        try (FakeMcpHttpServer server = new FakeMcpHttpServer()) {
            allow(server.baseUri().toString());
            McpMarketDO market = market(5L, server.baseUri().toString());
            when(marketMapper.selectOne(any())).thenReturn(market);

            List<McpMarketToolDO> store = new ArrayList<>();
            AtomicLong ids = new AtomicLong(100L);
            when(marketToolMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(store));
            when(marketToolMapper.insert(any(McpMarketToolDO.class))).thenAnswer(inv -> {
                McpMarketToolDO row = inv.getArgument(0);
                row.setId(ids.incrementAndGet());
                store.add(row);
                return 1;
            });
            when(marketToolMapper.updateById(any(McpMarketToolDO.class))).thenAnswer(inv -> 1);

            McpRefreshResult first = service.refreshMarket(5L);
            assertThat(first.success()).isTrue();
            assertThat(first.addedCount()).isEqualTo(2);
            assertThat(first.updatedCount()).isZero();
            assertThat(server.initializeCount()).isEqualTo(1);
            // SDK 的 initialize() 会预取一次 tools/list（缓存工具表），随后显式 listTools 再取一次
            assertThat(server.toolListCount()).isEqualTo(2);

            McpRefreshResult second = service.refreshMarket(5L);
            assertThat(second.success()).isTrue();
            assertThat(second.addedCount()).isZero();
            assertThat(second.updatedCount()).isEqualTo(2);

            ArgumentCaptor<McpMarketToolDO> inserted = ArgumentCaptor.forClass(McpMarketToolDO.class);
            verify(marketToolMapper, org.mockito.Mockito.times(2)).insert(inserted.capture());
            assertThat(inserted.getAllValues()).allSatisfy(row -> {
                assertThat(row.getTenantId()).isEqualTo(TENANT);
                assertThat(row.getMarketId()).isEqualTo(5L);
                assertThat(row.getIsLoaded()).isZero();
            });
            assertThat(inserted.getAllValues().get(0).getToolName()).isEqualTo("alpha");
            assertThat(inserted.getAllValues().get(0).getToolDescription()).isEqualTo("第一个工具");
            assertThat(inserted.getAllValues().get(0).getToolMetadata()).contains("\"name\":\"alpha\"");
        }
    }

    @Test
    @DisplayName("刷新上游失败：具名失败结果且零部分写（不触碰 market_tool 写面）")
    void refreshFailureWritesNothing() {
        allow("http://127.0.0.1:1");
        when(marketMapper.selectOne(any())).thenReturn(market(6L, "http://127.0.0.1:1"));

        McpRefreshResult result = service.refreshMarket(6L);

        assertThat(result.success()).isFalse();
        assertThat(result.addedCount()).isZero();
        assertThat(result.updatedCount()).isZero();
        assertThat(result.message()).contains("刷新");
        assertThat(result.message()).doesNotContain("127.0.0.1");
        verifyNoInteractions(marketToolMapper);
    }

    @Test
    @DisplayName("非 allow-list 市场刷新：具名 403 且零网络调用")
    void refreshOutsideAllowListNeverConnects() throws Exception {
        try (FakeMcpHttpServer server = new FakeMcpHttpServer()) {
            allow();
            when(marketMapper.selectOne(any())).thenReturn(market(7L, server.baseUri().toString()));

            assertThatThrownBy(() -> service.refreshMarket(7L))
                    .isInstanceOf(McpEndpointNotAllowedException.class)
                    .satisfies(e -> assertThat(((McpEndpointNotAllowedException) e).errorCode())
                            .isEqualTo("MCP_ENDPOINT_NOT_ALLOWED"));
            assertThat(server.requestCount()).isZero();
            verifyNoInteractions(marketToolMapper);
        }
    }

    @Test
    @DisplayName("加载：写 REMOTE 工具行并回写 is_loaded/local_tool_id（同租户/同市场）")
    void loadWritesLocalToolAndBackfills() {
        McpMarketToolDO row = marketTool(21L, 5L, "alpha", 0);
        when(marketToolMapper.selectOne(any())).thenReturn(row);
        when(marketMapper.selectOne(any())).thenReturn(market(5L, "http://127.0.0.1:1"));
        when(toolMapper.insert(any(McpToolDO.class))).thenAnswer(inv -> {
            McpToolDO tool = inv.getArgument(0);
            tool.setId(99L);
            return 1;
        });

        service.loadMarketTool(21L);

        ArgumentCaptor<McpToolDO> created = ArgumentCaptor.forClass(McpToolDO.class);
        verify(toolMapper).insert(created.capture());
        McpToolDO local = created.getValue();
        assertThat(local.getId()).isEqualTo(99L);
        assertThat(local.getName()).isEqualTo("alpha");
        assertThat(local.getType()).isEqualTo("REMOTE");
        assertThat(local.getStatus()).isEqualTo("ENABLED");
        assertThat(local.getTenantId()).isEqualTo(TENANT);
        assertThat(local.getConfigJson()).contains("\"baseUrl\":\"http://127.0.0.1:1\"");

        ArgumentCaptor<McpMarketToolDO> updated = ArgumentCaptor.forClass(McpMarketToolDO.class);
        verify(marketToolMapper).updateById(updated.capture());
        assertThat(updated.getValue().getIsLoaded()).isEqualTo(1);
        assertThat(updated.getValue().getLocalToolId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("重复加载：409 语义且零写入")
    void repeatedLoadIsConflict() {
        when(marketToolMapper.selectOne(any())).thenReturn(marketTool(22L, 5L, "alpha", 1));

        assertThatThrownBy(() -> service.loadMarketTool(22L))
                .isInstanceOf(McpAdminException.class)
                .satisfies(e -> {
                    McpAdminException ex = (McpAdminException) e;
                    assertThat(ex.httpStatus()).isEqualTo(409);
                    assertThat(ex.errorCode()).isEqualTo("MCP_TOOL_ALREADY_LOADED");
                });
        verifyNoInteractions(toolMapper);
        verify(marketToolMapper, never()).updateById(any(McpMarketToolDO.class));
    }

    @Test
    @DisplayName("批量加载：逐条隔离，一条失败不影响其它条，返回成功数")
    void batchLoadIsolatesFailures() {
        McpMarketToolDO first = marketTool(31L, 5L, "alpha", 0);
        McpMarketToolDO second = marketTool(32L, 5L, "beta", 1);
        when(marketToolMapper.selectOne(any())).thenReturn(first, second);
        when(marketMapper.selectOne(any())).thenReturn(market(5L, "http://127.0.0.1:1"));
        when(toolMapper.insert(any(McpToolDO.class))).thenAnswer(inv -> {
            McpToolDO tool = inv.getArgument(0);
            tool.setId(77L);
            return 1;
        });

        int success = service.batchLoadMarketTools(List.of(31L, 32L));

        assertThat(success).isEqualTo(1);
        verify(toolMapper).insert(any(McpToolDO.class));
    }

    @Test
    @DisplayName("连接测试四态：可达 / 不可达 / 鉴权失败 / 未知 id，且不写库")
    void connectionTestFourStates() throws Exception {
        try (FakeMcpHttpServer reachable = new FakeMcpHttpServer()) {
            allow(reachable.baseUri().toString());
            when(toolMapper.selectOne(any()))
                    .thenReturn(tool(41L, reachable.baseUri() + "/mcp"))
                    .thenReturn(tool(42L, "http://127.0.0.1:1/mcp"))
                    .thenReturn(tool(43L, reachable.baseUri() + "/mcp"))
                    .thenReturn(null);

            McpTestResult ok = service.testTool(41L);
            assertThat(ok.success()).isTrue();
            assertThat(ok.toolCount()).isEqualTo(2);
            assertThat(ok.message()).isEqualTo("连接成功");

            allow(reachable.baseUri().toString(), "http://127.0.0.1:1");
            McpTestResult refused = service.testTool(42L);
            assertThat(refused.success()).isFalse();
            assertThat(refused.message()).startsWith("连接失败");
            assertThat(refused.message()).doesNotContain("127.0.0.1");

            reachable.rejectWith(401);
            McpTestResult unauthorized = service.testTool(43L);
            assertThat(unauthorized.success()).isFalse();
            assertThat(unauthorized.message()).isEqualTo("鉴权失败");

            McpTestResult missing = service.testTool(44L);
            assertThat(missing.success()).isFalse();
            assertThat(missing.message()).isEqualTo("工具不存在");

            verify(toolMapper, never()).insert(any(McpToolDO.class));
            verify(toolMapper, never()).updateById(any(McpToolDO.class));
            verify(toolMapper, never()).delete(any());
        }
    }

    @Test
    @DisplayName("连接测试：未批准目标 → 具名 403 且零网络")
    void connectionTestOutsideAllowListNeverConnects() throws Exception {
        try (FakeMcpHttpServer server = new FakeMcpHttpServer()) {
            allow();
            when(toolMapper.selectOne(any())).thenReturn(tool(51L, server.baseUri() + "/mcp"));

            assertThatThrownBy(() -> service.testTool(51L))
                    .isInstanceOf(McpEndpointNotAllowedException.class);
            assertThat(server.requestCount()).isZero();
        }
    }

    // ------------------------------------------------------------------ helpers

    private void rebuildService() {
        allowProperties.setEndpoints(List.of());
        service = new McpAdminServiceImpl(marketMapper, marketToolMapper, toolMapper,
                new McpEndpointAllowList(allowProperties), new McpRemoteCatalogClient(),
                objectMapper, transactionManager);
    }

    private void allow(String... endpoints) {
        allowProperties.setEndpoints(List.of(endpoints));
        service = new McpAdminServiceImpl(marketMapper, marketToolMapper, toolMapper,
                new McpEndpointAllowList(allowProperties), new McpRemoteCatalogClient(),
                objectMapper, transactionManager);
    }

    private static McpMarketDO market(Long id, String url) {
        return McpMarketDO.builder()
                .id(id)
                .name("市场" + id)
                .url(url)
                .status("ENABLED")
                .tenantId(TENANT)
                .delFlag("0")
                .createTime(new Date(1_000L))
                .updateTime(new Date(2_000L))
                .build();
    }

    private static McpMarketToolDO marketTool(Long id, Long marketId, String name, int loaded) {
        return McpMarketToolDO.builder()
                .id(id)
                .marketId(marketId)
                .toolName(name)
                .toolDescription(name + "描述")
                .toolMetadata("{\"name\":\"" + name + "\"}")
                .isLoaded(loaded)
                .tenantId(TENANT)
                .createTime(new Date(1_000L))
                .updateTime(new Date(2_000L))
                .build();
    }

    private static McpToolDO tool(Long id, String baseUrl) {
        String config = "{\"baseUrl\":\"" + baseUrl + "\",\"requiresConfirm\":false}";
        return McpToolDO.builder()
                .id(id)
                .name("tool-" + id)
                .description("desc")
                .type("REMOTE")
                .status("ENABLED")
                .configJson(config)
                .tenantId(TENANT)
                .delFlag("0")
                .createTime(new Date(1_000L))
                .updateTime(new Date(2_000L))
                .build();
    }
}
