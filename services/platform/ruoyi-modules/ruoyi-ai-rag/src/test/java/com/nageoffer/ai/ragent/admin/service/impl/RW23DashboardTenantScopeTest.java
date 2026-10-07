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

package com.nageoffer.ai.ragent.admin.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceRunMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RW-23-R1（F18 op1/op6）：运营 Dashboard 的统计查询必须按租户限域，且缺主体 fail-closed。
 *
 * <p>改前行为（可复现）：{@code DashboardServiceImpl} 的每处查询都是全局计数
 * （{@code userMapper.selectCount(Wrappers.lambdaQuery(UserDO.class))}、
 * {@code conversationMapper.selectCount(...)}、{@code messageMapper.selectCount/selectMaps(...)}、
 * {@code traceRunMapper.selectCount/selectMaps/selectObjs(...)}），均无 tenant 条件 ——
 * 任何租户管理员都会看到全平台统计。改后：每次查询发出前注入 {@code tenant_id = 当前主体租户}，
 * 且 {@code ai_legacy_user}（无租户列）不再参与统计。
 *
 * <p>本测试不逐个 mock 断言，而是把**本次调用发出的所有 wrapper** 收进一个列表统一校验：
 * 这样任何一处漏加租户条件都会被抓住（包括将来新加的查询）。
 */
@Tag("dev")
class RW23DashboardTenantScopeTest {

    private static final String TENANT_A = "T-A";

    private static final String TENANT_B = "T-B";

    private final UserMapper users = mock(UserMapper.class);

    private final ConversationMapper sessions = mock(ConversationMapper.class);

    private final ConversationMessageMapper messages = mock(ConversationMessageMapper.class);

    private final RagTraceRunMapper traces = mock(RagTraceRunMapper.class);

    private final DashboardServiceImpl service = new DashboardServiceImpl(users, sessions, messages, traces);

    private final List<AbstractWrapper<?, ?, ?>> issued = new ArrayList<>();

    @BeforeAll
    static void initTableInfo() {
        // 单测无 Spring/MyBatis 启动：lambda wrapper 的列名解析需要实体 TableInfo
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ConversationDO.class);
        TableInfoHelper.initTableInfo(assistant, ConversationMessageDO.class);
        TableInfoHelper.initTableInfo(assistant, RagTraceRunDO.class);
    }

    @BeforeEach
    void setUp() {
        when(sessions.selectCount(any())).thenAnswer(invocation -> recorded(invocation.getArgument(0), 1L));
        when(sessions.selectMaps(any())).thenAnswer(invocation -> recorded(invocation.getArgument(0), List.of()));
        when(messages.selectCount(any())).thenAnswer(invocation -> recorded(invocation.getArgument(0), 1L));
        when(messages.selectMaps(any()))
            .thenAnswer(invocation -> recorded(invocation.getArgument(0), List.of(Map.of("cnt", 1L))));
        when(traces.selectCount(any())).thenAnswer(invocation -> recorded(invocation.getArgument(0), 1L));
        when(traces.selectMaps(any())).thenAnswer(invocation -> recorded(invocation.getArgument(0), List.of()));
        when(traces.selectObjs(any())).thenAnswer(invocation -> recorded(invocation.getArgument(0), List.of()));
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
        issued.clear();
    }

    @Test
    @DisplayName("概览：发出的每一次查询都带本租户条件")
    void overviewQueriesAreTenantScoped() {
        useTenant(TENANT_A);

        service.loadOverview("24h");

        assertEveryIssuedQueryIsTenantScoped("概览");
        // 用户口径已不再读 ai_legacy_user（无租户列，无法归属租户）
        verify(users, never()).selectCount(any());
    }

    @Test
    @DisplayName("性能：追踪样本查询带本租户条件")
    void performanceQueriesAreTenantScoped() {
        useTenant(TENANT_A);

        service.loadPerformance("24h");

        assertEveryIssuedQueryIsTenantScoped("性能");
    }

    @Test
    @DisplayName("趋势：按天/按小时分桶查询同样带本租户条件")
    void trendQueriesAreTenantScoped() {
        useTenant(TENANT_A);

        service.loadTrends("sessions", "24h", "hour");

        assertEveryIssuedQueryIsTenantScoped("趋势");
    }

    @Test
    @DisplayName("两租户负例：T-A 主体发出的查询里不出现 T-B（彼此看不到对方统计）")
    void tenantBStatisticsNeverAppearInTenantAQueries() {
        useTenant(TENANT_A);

        service.loadOverview("24h");
        service.loadPerformance("24h");
        service.loadTrends("sessions", "24h", "hour");

        assertFalse(issued.isEmpty(), "应至少发出一次查询");
        for (AbstractWrapper<?, ?, ?> wrapper : issued) {
            String sql = wrapper.getSqlSegment();
            assertFalse(sql.contains("T-B"), () -> "SQL 不应出现对方租户: " + sql);
            assertFalse(wrapper.getParamNameValuePairs().containsValue(TENANT_B),
                () -> "参数不应出现对方租户: " + wrapper.getParamNameValuePairs());
        }
    }

    @Test
    @DisplayName("缺执行主体：三个入口都拒绝（fail-closed，不降级为全平台统计）")
    void missingPrincipalIsRejected() {
        PrincipalContext.clear();

        assertThrows(ClientException.class, () -> service.loadOverview("24h"));
        assertThrows(ClientException.class, () -> service.loadPerformance("24h"));
        assertThrows(ClientException.class, () -> service.loadTrends("sessions", "24h", "hour"));
        assertTrue(issued.isEmpty(), "无主体时不得发出任何统计查询");
    }

    private <T> T recorded(AbstractWrapper<?, ?, ?> wrapper, T result) {
        issued.add(wrapper);
        return result;
    }

    private void assertEveryIssuedQueryIsTenantScoped(String what) {
        assertFalse(issued.isEmpty(), () -> what + "：应至少发出一次查询");
        for (AbstractWrapper<?, ?, ?> wrapper : issued) {
            String sql = wrapper.getSqlSegment();
            assertTrue(sql.contains("tenant_id"), () -> what + " 查询缺少 tenant_id: " + sql);
            assertTrue(wrapper.getParamNameValuePairs().containsValue(TENANT_A),
                () -> what + " 查询参数未带本租户: " + wrapper.getParamNameValuePairs());
        }
    }

    private void useTenant(String tenantId) {
        PrincipalContext.set(new ExecutionPrincipal(tenantId, "7", "platform:" + tenantId + ":7", 1, 1,
            Set.of("ai:run:read"), "jti-1", "test", 0L, Long.MAX_VALUE));
    }
}
