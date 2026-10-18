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

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F12-M1 工具管理面：schema/参数展示、headers 掩码、requiresConfirm、导出脱敏、
 * 选项与删除/启停。
 */
@Tag("dev")
class McpAdminServiceToolTest {

    private static final String TENANT = "T1";

    private static final String CONFIG = """
            {"baseUrl":"http://127.0.0.1:18080/mcp",
             "headers":{"Authorization":"env:FAKE_MCP_TOKEN","X-Literal":"literal-secret"},
             "inputSchema":{"type":"object","properties":{"value":{"type":"string"}},"required":["value"]},
             "requiresConfirm":true}
            """;

    private final McpMarketMapper marketMapper = mock(McpMarketMapper.class);
    private final McpMarketToolMapper marketToolMapper = mock(McpMarketToolMapper.class);
    private final McpToolMapper toolMapper = mock(McpToolMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private McpAdminServiceImpl service;

    @BeforeEach
    void setUp() {
        McpTestMetadata.ensureLoaded();
        service = new McpAdminServiceImpl(marketMapper, marketToolMapper, toolMapper,
                new McpEndpointAllowList(new AgentMcpAllowListProperties()),
                new McpRemoteCatalogClient(), objectMapper, mock(PlatformTransactionManager.class));
        PrincipalContext.set(new ExecutionPrincipal(TENANT, "2101", "platform:" + TENANT + ":2101",
                1, 1, Set.of(), "jti", "platform", 0, Long.MAX_VALUE));
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    @DisplayName("详情：inputSchema 原样、baseUrl 原样、headers 掩码、requiresConfirm 展示")
    void detailShowsSchemaAndMasksHeaders() {
        when(toolMapper.selectOne(any())).thenReturn(tool(11L, "alpha", CONFIG));

        McpToolVO vo = service.getTool(11L);

        assertThat(vo.getId()).isEqualTo(11L);
        assertThat(vo.getBaseUrl()).isEqualTo("http://127.0.0.1:18080/mcp");
        assertThat(vo.getInputSchema().path("type").asText()).isEqualTo("object");
        assertThat(vo.getInputSchema().path("properties").path("value").path("type").asText())
                .isEqualTo("string");
        assertThat(vo.isRequiresConfirm()).isTrue();
        assertThat(vo.getAuthMasked()).isEqualTo("***");
        assertThat(vo.getConfigJson())
                .contains("\"Authorization\":\"***\"")
                .contains("\"X-Literal\":\"***\"")
                .doesNotContain("FAKE_MCP_TOKEN")
                .doesNotContain("literal-secret");
        assertThat(objectMapper.valueToTree(vo).toString())
                .doesNotContain("FAKE_MCP_TOKEN")
                .doesNotContain("literal-secret");
    }

    @Test
    @DisplayName("选项：只取 ENABLED 项，查询带 tenant/status 谓词")
    void optionsOnlyListEnabledTools() {
        when(toolMapper.selectList(any())).thenReturn(List.of(tool(1L, "a", CONFIG)));

        List<McpToolVO> options = service.listEnabledTools();

        assertThat(options).hasSize(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<McpToolDO>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(toolMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("tenant_id").contains("status");
    }

    @Test
    @DisplayName("导出：完整行集合 + config 掩码，不含原始头值")
    void exportMasksToolConfig() {
        when(toolMapper.selectList(any())).thenReturn(List.of(tool(2L, "alpha", CONFIG)));

        String export = service.exportTools(null, null, null);

        assertThat(export)
                .contains("\"name\":\"alpha\"")
                .contains("\"baseUrl\":\"http://127.0.0.1:18080/mcp\"")
                .contains("\"requiresConfirm\":true")
                .contains("\"Authorization\":\"***\"")
                .doesNotContain("FAKE_MCP_TOKEN")
                .doesNotContain("literal-secret");
    }

    @Test
    @DisplayName("删除：返回删除计数且带 tenant 谓词（不检查技能/意图引用）")
    void deleteReturnsCount() {
        when(toolMapper.delete(any())).thenReturn(3);

        assertThat(service.deleteTools(List.of(1L, 2L, 3L))).isEqualTo(3);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<McpToolDO>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(toolMapper).delete(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("tenant_id");
    }

    @Test
    @DisplayName("启停：跨租户 → 404 零写入；合法状态才更新")
    void statusIsScopedAndValidated() {
        when(toolMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.updateToolStatus(12L, "DISABLED"))
                .isInstanceOf(McpAdminException.class)
                .satisfies(e -> assertThat(((McpAdminException) e).httpStatus()).isEqualTo(404));
        verify(toolMapper, never()).updateById(any(McpToolDO.class));

        when(toolMapper.selectOne(any())).thenReturn(tool(12L, "alpha", CONFIG));
        service.updateToolStatus(12L, "DISABLED");
        ArgumentCaptor<McpToolDO> captor = ArgumentCaptor.forClass(McpToolDO.class);
        verify(toolMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("DISABLED");
    }

    @Test
    @DisplayName("缺 baseUrl 的工具体：详情不崩，测试态为具名失败")
    void toolWithoutBaseUrlStillRenders() {
        when(toolMapper.selectOne(any())).thenReturn(tool(13L, "local", "{\"requiresConfirm\":false}"));

        McpToolVO vo = service.getTool(13L);
        assertThat(vo.getBaseUrl()).isNull();
        assertThat(vo.isRequiresConfirm()).isFalse();
        assertThat(vo.getConfigJson()).isEqualTo("{\"requiresConfirm\":false}");

        McpTestResult result = service.testTool(13L);
        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("连接失败");
        verify(toolMapper, never()).insert(any(McpToolDO.class));
    }

    private static McpToolDO tool(Long id, String name, String configJson) {
        return McpToolDO.builder()
                .id(id)
                .name(name)
                .description(name + "描述")
                .type("REMOTE")
                .status("ENABLED")
                .configJson(configJson)
                .tenantId(TENANT)
                .delFlag("0")
                .createTime(new Date(1_000L))
                .updateTime(new Date(2_000L))
                .build();
    }
}
