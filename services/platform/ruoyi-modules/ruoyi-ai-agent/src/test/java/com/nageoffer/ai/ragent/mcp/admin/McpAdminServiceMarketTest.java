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
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.agent.dao.entity.McpMarketDO;
import com.nageoffer.ai.ragent.agent.dao.entity.McpMarketToolDO;
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
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F12-M1 市场管理面：分页/详情/删除/启停/导出脱敏/跨租户 404。
 *
 * <p>Mapper 是 mock，但每个查询都捕获 wrapper 并断言其中确实带了当前主体的
 * {@code tenant_id} 谓词——"依赖全局拦截器"不算数。
 */
@Tag("dev")
class McpAdminServiceMarketTest {

    private static final String TENANT = "T1";

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
    @DisplayName("分页：current/size 钳制（current<1→1；size>100→100），查询带显式 tenant_id")
    void pageClampsAndScopesTenant() {
        when(marketMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<McpMarketDO> page = invocation.getArgument(0);
            page.setRecords(List.of(market(7L, "市场A", "{\"token\":\"secret\"}")));
            page.setTotal(1);
            return page;
        });

        McpPageRequest request = new McpPageRequest();
        request.setCurrent(0);
        request.setSize(1000);
        var result = service.pageMarkets(request);

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().get(0).getName()).isEqualTo("市场A");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Page<McpMarketDO>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<McpMarketDO>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(marketMapper).selectPage(pageCaptor.capture(), wrapperCaptor.capture());
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(100);
        assertThat(wrapperCaptor.getValue().getSqlSegment()).contains("tenant_id");
        assertThat(((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) wrapperCaptor.getValue())
                .getParamNameValuePairs().values()).contains(TENANT);
    }

    @Test
    @DisplayName("详情：跨租户（selecOne 为空）→ 具名 404，不泄露存在性")
    void detailOfOtherTenantIsNotFound() {
        when(marketMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.getMarket(9L))
                .isInstanceOf(McpAdminException.class)
                .satisfies(e -> {
                    McpAdminException ex = (McpAdminException) e;
                    assertThat(ex.httpStatus()).isEqualTo(404);
                    assertThat(ex.errorCode()).isEqualTo("MCP_NOT_FOUND");
                });
    }

    @Test
    @DisplayName("详情：返回 auth 掩码而不是原始 auth_config")
    void detailMasksAuthConfig() {
        when(marketMapper.selectOne(any())).thenReturn(market(3L, "市场", "{\"token\":\"top-secret\"}"));

        McpMarketVO vo = service.getMarket(3L);

        assertThat(vo.getId()).isEqualTo(3L);
        assertThat(vo.getAuthConfig()).isEqualTo("***");
        assertThat(objectMapper.valueToTree(vo).toString()).doesNotContain("top-secret");
    }

    @Test
    @DisplayName("导出：auth_config 敏感值替换为 ***，其余行字段保留")
    void exportMasksAuthConfig() {
        when(marketMapper.selectList(any())).thenReturn(List.of(
                market(1L, "有凭据", "{\"token\":\"top-secret\"}"),
                market(2L, "无凭据", null)));

        String export = service.exportMarkets(null, null);

        assertThat(export).contains("\"authConfig\":\"***\"")
                .contains("\"name\":\"有凭据\"")
                .doesNotContain("top-secret");
        assertThat(export).contains("\"authConfig\":null");
    }

    @Test
    @DisplayName("删除：先删市场下工具行，再删市场；返回删除计数")
    void deleteRemovesChildrenFirstAndReturnsCount() {
        when(marketToolMapper.delete(any())).thenReturn(4);
        when(marketMapper.delete(any())).thenReturn(2);

        int removed = service.deleteMarkets(List.of(1L, 2L));

        assertThat(removed).isEqualTo(2);
        InOrder order = inOrder(marketToolMapper, marketMapper);
        order.verify(marketToolMapper).delete(any());
        order.verify(marketMapper).delete(any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<McpMarketToolDO>> childCaptor =
                ArgumentCaptor.forClass(Wrapper.class);
        verify(marketToolMapper).delete(childCaptor.capture());
        assertThat(childCaptor.getValue().getSqlSegment()).contains("tenant_id").contains("market_id");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<McpMarketDO>> marketCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(marketMapper).delete(marketCaptor.capture());
        assertThat(marketCaptor.getValue().getSqlSegment()).contains("tenant_id");
    }

    @Test
    @DisplayName("启停：状态白名单外 → 400；合法状态 → 更新同一行")
    void statusUpdateIsValidated() {
        when(marketMapper.selectOne(any())).thenReturn(market(5L, "市场", null));

        assertThatThrownBy(() -> service.updateMarketStatus(5L, "HACKED"))
                .isInstanceOf(McpAdminException.class)
                .satisfies(e -> assertThat(((McpAdminException) e).errorCode())
                        .isEqualTo("MCP_INVALID_STATUS"));
        verify(marketMapper, never()).updateById(any(McpMarketDO.class));

        service.updateMarketStatus(5L, "DISABLED");
        ArgumentCaptor<McpMarketDO> captor = ArgumentCaptor.forClass(McpMarketDO.class);
        verify(marketMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(5L);
        assertThat(captor.getValue().getStatus()).isEqualTo("DISABLED");
    }

    @Test
    @DisplayName("启停：跨租户 id（selectOne 为空）→ 404 且零写入")
    void statusUpdateOfOtherTenantIsNotFound() {
        when(marketMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.updateMarketStatus(42L, "DISABLED"))
                .isInstanceOf(McpAdminException.class)
                .satisfies(e -> assertThat(((McpAdminException) e).httpStatus()).isEqualTo(404));
        verify(marketMapper, never()).updateById(any(McpMarketDO.class));
    }

    private static McpMarketDO market(Long id, String name, String authConfig) {
        return McpMarketDO.builder()
                .id(id)
                .name(name)
                .url("http://127.0.0.1:1/mcp")
                .description(name + "描述")
                .authConfig(authConfig)
                .status("ENABLED")
                .tenantId(TENANT)
                .delFlag("0")
                .createTime(new Date(1_000L))
                .updateTime(new Date(2_000L))
                .build();
    }
}
