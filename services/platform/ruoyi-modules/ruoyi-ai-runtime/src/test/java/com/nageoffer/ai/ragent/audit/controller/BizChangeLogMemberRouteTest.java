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

package com.nageoffer.ai.ragent.audit.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.audit.controller.request.BizChangeLogPageRequest;
import com.nageoffer.ai.ragent.audit.controller.vo.BizChangeLogVO;
import com.nageoffer.ai.ragent.audit.service.BizChangeLogService;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogReadScope;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F18:2 成员端读面：{@code GET /biz-change-logs/mine} 与 {@code /mine/{id}}。
 *
 * <p>本测试钉住三件行为：
 * <ol>
 *   <li>mine 两条路由必须以 {@link BizChangeLogReadScope#memberOnlyCurrent()} 调用服务
 *       （tenantWide=false 且 memberId 取自执行主体）；</li>
 *   <li>管理端两条路由必须保持 tenantWide（不被新路由改写语义）；</li>
 *   <li>缺执行主体时必须 fail-closed（抛 {@link ClientException}，不降级为全量）。</li>
 * </ol>
 * 改前：{@code BizChangeLogController} 没有 mine 映射，请求 404，断言失败。
 */
@Tag("dev")
class BizChangeLogMemberRouteTest {

    private static final String TENANT = "tenant-a";
    private static final String USER = "42";
    private static final String MEMBER = "platform:tenant-a:42";

    private BizChangeLogService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(BizChangeLogService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new BizChangeLogController(service)).build();
        PrincipalContext.clear();
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    @DisplayName("GET /biz-change-logs/mine → memberOnlyCurrent（tenantWide=false，member 取主体）")
    void minePageUsesMemberOnlyScope() throws Exception {
        when(service.page(any(), any())).thenReturn(new Page<>());
        PrincipalContext.set(principal());

        mockMvc.perform(get("/internal/ai/v1/biz-change-logs/mine")).andExpect(status().isOk());

        BizChangeLogReadScope scope = capturePageScope();
        assertThat(scope.tenantWide()).as("mine 列表不得降级为租户全量").isFalse();
        assertThat(scope.tenantId()).isEqualTo(TENANT);
        assertThat(scope.memberId()).isEqualTo(MEMBER);
    }

    @Test
    @DisplayName("GET /biz-change-logs/mine/{id} → memberOnlyCurrent（tenantWide=false）")
    void mineDetailUsesMemberOnlyScope() throws Exception {
        when(service.get(anyString(), any())).thenReturn(mock(BizChangeLogVO.class));
        PrincipalContext.set(principal());

        mockMvc.perform(get("/internal/ai/v1/biz-change-logs/mine/log-1")).andExpect(status().isOk());

        BizChangeLogReadScope scope = captureGetScope();
        assertThat(scope.tenantWide()).as("mine 详情不得降级为租户全量").isFalse();
        assertThat(scope.tenantId()).isEqualTo(TENANT);
        assertThat(scope.memberId()).isEqualTo(MEMBER);
        verify(service).get(eq("log-1"), any());
    }

    @Test
    @DisplayName("管理端 GET /biz-change-logs 与 /{id} 仍为 tenantWide")
    void adminRoutesRemainTenantWide() throws Exception {
        when(service.page(any(), any())).thenReturn(new Page<>());
        when(service.get(anyString(), any())).thenReturn(mock(BizChangeLogVO.class));
        PrincipalContext.set(principal());

        mockMvc.perform(get("/internal/ai/v1/biz-change-logs")).andExpect(status().isOk());
        mockMvc.perform(get("/internal/ai/v1/biz-change-logs/log-1")).andExpect(status().isOk());

        assertThat(capturePageScope().tenantWide()).isTrue();
        assertThat(captureGetScope().tenantWide()).isTrue();
    }

    @Test
    @DisplayName("无执行主体：mine 两条都 fail-closed，不调用服务（不降级全量）")
    void mineRoutesFailClosedWithoutPrincipal() {
        assertThatThrownBy(() -> mockMvc.perform(get("/internal/ai/v1/biz-change-logs/mine")))
                .as("缺主体必须抛 ClientException，而不是返回全量列表")
                .hasRootCauseInstanceOf(ClientException.class);
        assertThatThrownBy(() -> mockMvc.perform(get("/internal/ai/v1/biz-change-logs/mine/log-1")))
                .hasRootCauseInstanceOf(ClientException.class);
        verifyNoInteractions(service);
    }

    @SuppressWarnings("unchecked")
    private BizChangeLogReadScope capturePageScope() {
        ArgumentCaptor<BizChangeLogReadScope> captor = ArgumentCaptor.forClass(BizChangeLogReadScope.class);
        verify(service).page(any(BizChangeLogPageRequest.class), captor.capture());
        return captor.getValue();
    }

    private BizChangeLogReadScope captureGetScope() {
        ArgumentCaptor<BizChangeLogReadScope> captor = ArgumentCaptor.forClass(BizChangeLogReadScope.class);
        verify(service).get(anyString(), captor.capture());
        return captor.getValue();
    }

    private static ExecutionPrincipal principal() {
        return new ExecutionPrincipal(TENANT, USER, MEMBER, 1, 1,
                Set.of("ai:run:read"), "f18-2-jti", "platform", 0L, Long.MAX_VALUE);
    }
}
