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

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * F12-M1 内层控制器判据：方法级路径、整数信封、GET 双回执头、写面不铸回执、
 * 具名 4xx 映射。
 */
@Tag("dev")
class McpAdminControllerTest {

    private static final String PREFIX = "/internal/ai/v1/mcp";

    private final McpAdminService service = mock(McpAdminService.class);
    private final DeliveryPermits permits = mock(DeliveryPermits.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new McpAdminController(service, permits)).build();
        PrincipalContext.set(new ExecutionPrincipal("T1", "2101", "platform:T1:2101",
                1, 1, Set.of(), "jti", "platform", 0, Long.MAX_VALUE));
        when(permits.enter(any(), anyString(), anyString()))
                .thenReturn(new DeliveryPermits.Permit("permit-1", "op-1", null));
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    @DisplayName("市场列表 GET：整数码信封 + X-AI-Delivery-Permit / X-AI-Delivery-Operation（动作 mcp.list）")
    void marketPageCarriesDeliveryReceipts() throws Exception {
        Page<McpMarketVO> page = new Page<>(1, 10);
        page.setRecords(List.of(McpMarketVO.builder().id(1L).name("市场").status("ENABLED").build()));
        page.setTotal(1);
        when(service.pageMarkets(any())).thenReturn(page);

        MockHttpServletResponse response = mvc.perform(get(PREFIX + "/markets"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("X-AI-Delivery-Permit")).isEqualTo("permit-1");
        assertThat(response.getHeader("X-AI-Delivery-Operation")).isEqualTo("op-1");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":200").contains("\"name\":\"市场\"");

        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        verify(permits).enter(any(), action.capture(), anyString());
        assertThat(action.getValue()).isEqualTo("mcp.list");
    }

    @Test
    @DisplayName("市场导出 GET：动作 mcp.read 且带回执头")
    void marketExportCarriesReceiptsWithReadAction() throws Exception {
        when(service.exportMarkets(null, null)).thenReturn("[]");

        MockHttpServletResponse response = mvc.perform(get(PREFIX + "/markets/export"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("X-AI-Delivery-Permit")).isEqualTo("permit-1");
        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        verify(permits).enter(any(), action.capture(), anyString());
        assertThat(action.getValue()).isEqualTo("mcp.read");
    }

    @Test
    @DisplayName("工具详情 GET：路径被映射，动作 mcp.read")
    void toolDetailCarriesReceiptsWithReadAction() throws Exception {
        when(service.getTool(3L)).thenReturn(McpToolVO.builder().id(3L).name("alpha").build());

        MockHttpServletResponse response = mvc.perform(get(PREFIX + "/tools/3"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("X-AI-Delivery-Operation")).isEqualTo("op-1");
        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        verify(permits).enter(any(), action.capture(), anyString());
        assertThat(action.getValue()).isEqualTo("mcp.read");
    }

    @Test
    @DisplayName("工具列表/全部/选项 GET：动作 mcp.list，三条路径互不遮蔽")
    void toolListRoutesAreMapped() throws Exception {
        Page<McpToolVO> emptyPage = new Page<>(1, 10);
        when(service.pageTools(any())).thenReturn(emptyPage);
        when(service.listTools(null, null, null)).thenReturn(List.of());
        when(service.listEnabledTools()).thenReturn(List.of());

        assertThat(mvc.perform(get(PREFIX + "/tools")).andReturn().getResponse().getStatus())
                .isEqualTo(200);
        assertThat(mvc.perform(get(PREFIX + "/tools/all")).andReturn().getResponse().getStatus())
                .isEqualTo(200);
        assertThat(mvc.perform(get(PREFIX + "/tools/options")).andReturn().getResponse().getStatus())
                .isEqualTo(200);

        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        verify(permits, org.mockito.Mockito.times(3)).enter(any(), action.capture(), anyString());
        assertThat(action.getAllValues()).containsOnly("mcp.list");
    }

    @Test
    @DisplayName("写面：状态/测试/删除/加载/批量加载都返回整数信封且不铸交付回执")
    void writeRoutesReturnEnvelopeWithoutReceipts() throws Exception {
        when(service.testTool(8L)).thenReturn(new McpTestResult(true, 2, "连接成功"));
        when(service.batchLoadMarketTools(any())).thenReturn(1);
        when(service.deleteMarkets(any())).thenReturn(2);
        when(service.deleteTools(any())).thenReturn(3);
        when(service.refreshMarket(4L)).thenReturn(new McpRefreshResult(true, 1, 0, "刷新成功"));

        MockHttpServletResponse status = mvc.perform(post(PREFIX + "/tools/8/status")
                        .param("status", "DISABLED"))
                .andReturn().getResponse();
        assertThat(status.getStatus()).isEqualTo(200);
        assertThat(status.getContentAsString(StandardCharsets.UTF_8)).contains("\"code\":200");
        verify(service).updateToolStatus(8L, "DISABLED");

        MockHttpServletResponse tested = mvc.perform(post(PREFIX + "/tools/8/test"))
                .andReturn().getResponse();
        assertThat(tested.getContentAsString(StandardCharsets.UTF_8))
                .contains("\"success\":true").contains("\"toolCount\":2");

        MockHttpServletResponse deleted = mvc.perform(delete(PREFIX + "/markets/1,2"))
                .andReturn().getResponse();
        assertThat(deleted.getContentAsString(StandardCharsets.UTF_8)).contains("\"data\":2");
        verify(service).deleteMarkets(List.of(1L, 2L));

        MockHttpServletResponse deletedTools = mvc.perform(delete(PREFIX + "/tools/3,4,5"))
                .andReturn().getResponse();
        assertThat(deletedTools.getContentAsString(StandardCharsets.UTF_8)).contains("\"data\":3");
        verify(service).deleteTools(List.of(3L, 4L, 5L));

        mvc.perform(post(PREFIX + "/market-tools/21/load")).andReturn();
        verify(service).loadMarketTool(21L);

        MockHttpServletResponse batch = mvc.perform(post(PREFIX + "/market-tools/batch-load")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[21,22]}"))
                .andReturn().getResponse();
        assertThat(batch.getContentAsString(StandardCharsets.UTF_8)).contains("\"loaded\":1");
        verify(service).batchLoadMarketTools(List.of(21L, 22L));

        MockHttpServletResponse refreshed = mvc.perform(post(PREFIX + "/markets/4/refresh"))
                .andReturn().getResponse();
        assertThat(refreshed.getContentAsString(StandardCharsets.UTF_8)).contains("\"addedCount\":1");

        assertThat(status.getHeader("X-AI-Delivery-Permit")).isNull();
        assertThat(tested.getHeader("X-AI-Delivery-Operation")).isNull();
    }

    @Test
    @DisplayName("详情 404：取数失败在铸许可之前，返回具名 4xx 而不是 5xx")
    void notFoundIsNamedAndDoesNotMintPermit() throws Exception {
        when(service.getTool(9L)).thenThrow(new McpAdminException(McpAdminError.NOT_FOUND));

        MockHttpServletResponse response = mvc.perform(get(PREFIX + "/tools/9"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":404").contains("MCP_NOT_FOUND");
        verify(permits, never()).enter(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("未批准端点：刷新/连接测试 403 MCP_ENDPOINT_NOT_ALLOWED（不落 5xx）")
    void endpointNotAllowedMapsTo403() throws Exception {
        when(service.refreshMarket(1L)).thenThrow(new McpEndpointNotAllowedException("blocked"));
        when(service.testTool(2L)).thenThrow(new McpEndpointNotAllowedException("blocked"));

        MockHttpServletResponse refresh = mvc.perform(post(PREFIX + "/markets/1/refresh"))
                .andReturn().getResponse();
        assertThat(refresh.getStatus()).isEqualTo(403);
        assertThat(refresh.getContentAsString(StandardCharsets.UTF_8))
                .contains("MCP_ENDPOINT_NOT_ALLOWED");

        MockHttpServletResponse tested = mvc.perform(post(PREFIX + "/tools/2/test"))
                .andReturn().getResponse();
        assertThat(tested.getStatus()).isEqualTo(403);
        assertThat(tested.getContentAsString(StandardCharsets.UTF_8))
                .contains("MCP_ENDPOINT_NOT_ALLOWED");
        verify(permits, never()).enter(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("重复加载：409 MCP_TOOL_ALREADY_LOADED")
    void repeatedLoadMapsTo409() throws Exception {
        org.mockito.Mockito.doThrow(new McpAdminException(McpAdminError.TOOL_ALREADY_LOADED))
                .when(service).loadMarketTool(anyLong());

        MockHttpServletResponse response = mvc.perform(post(PREFIX + "/market-tools/31/load"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("MCP_TOOL_ALREADY_LOADED");
    }
}
