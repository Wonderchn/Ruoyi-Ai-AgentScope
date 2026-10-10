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

package com.nageoffer.ai.ragent.audit.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.audit.controller.request.BizChangeLogPageRequest;
import com.nageoffer.ai.ragent.audit.dao.entity.BizChangeLogDO;
import com.nageoffer.ai.ragent.audit.dao.mapper.BizChangeLogMapper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogReadScope;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RW-23（F18 op2/op5）：业务变更日志读路径必须限域。
 *
 * <p>改前行为：{@code page} 只按业务键筛选（可跨租户全量分页）、{@code get(id)} 直接
 * {@code selectById}（跨租户可读），两处都没有 tenant/member 条件。
 */
@Tag("dev")
class BizChangeLogReadScopeTest {

    private static final String TENANT_A = "T-A";

    private static final String TENANT_B = "T-B";

    private static final String MEMBER_A = "platform:T-A:7";

    private final BizChangeLogMapper mapper = mock(BizChangeLogMapper.class);

    private BizChangeLogServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, BizChangeLogDO.class);
    }

    @BeforeEach
    void setUp() {
        service = new BizChangeLogServiceImpl(mapper);
        PrincipalContext.clear();
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    @DisplayName("分页：带 tenant_id 与 member_id（非 tenant-wide）")
    void pageAppliesTenantAndMemberFilter() {
        when(mapper.selectPage(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.page(new BizChangeLogPageRequest(), memberScope());

        LambdaQueryWrapper<BizChangeLogDO> captured = capturedPageWrapper();
        String sql = captured.getSqlSegment();
        assertTrue(sql.contains("tenant_id"), () -> "缺少 tenant_id 条件: " + sql);
        assertTrue(sql.contains("member_id"), () -> "member 限域应带 member_id: " + sql);
        assertTrue(captured.getParamNameValuePairs().containsValue(TENANT_A));
        assertTrue(captured.getParamNameValuePairs().containsValue(MEMBER_A));
    }

    @Test
    @DisplayName("分页：tenant-wide 只带 tenant_id，不收敛到成员")
    void pageTenantWideOmitsMemberFilter() {
        when(mapper.selectPage(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.page(new BizChangeLogPageRequest(), new BizChangeLogReadScope(TENANT_A, MEMBER_A, true));

        LambdaQueryWrapper<BizChangeLogDO> captured = capturedPageWrapper();
        String sql = captured.getSqlSegment();
        assertTrue(sql.contains("tenant_id"), () -> "缺少 tenant_id 条件: " + sql);
        assertFalse(sql.contains("member_id"), () -> "tenant-wide 不应再收窄到成员: " + sql);
    }

    @Test
    @DisplayName("详情：跨租户 id 与不存在同外显（同一句异常，且查询带本租户条件）")
    void foreignIdLooksMissing() {
        when(mapper.selectOne(any())).thenReturn(null);

        ClientException exception = assertThrows(ClientException.class,
            () -> service.get("log-of-tenant-b", memberScope()));

        assertEquals("变更审计日志不存在", exception.getMessage());
        LambdaQueryWrapper<BizChangeLogDO> captured = capturedGetWrapper();
        String sql = captured.getSqlSegment();
        assertTrue(sql.contains("id"), () -> "sql=" + sql);
        assertTrue(sql.contains("tenant_id"), () -> "详情也必须带 tenant_id: " + sql);
        assertTrue(captured.getParamNameValuePairs().containsValue(TENANT_A));
        assertFalse(captured.getParamNameValuePairs().containsValue(TENANT_B));
    }

    @Test
    @DisplayName("空白 id：不查库直接按不存在处理")
    void blankIdRejectedWithoutQuery() {
        assertThrows(ClientException.class, () -> service.get("  ", memberScope()));
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).selectOne(any());
    }

    @Test
    @DisplayName("无执行主体：拒绝（fail-closed，不降级为全量）")
    void scopeRequiresPrincipal() {
        ClientException exception = assertThrows(ClientException.class, BizChangeLogReadScope::tenantWideCurrent);
        assertTrue(exception.getMessage().contains("principal"), exception.getMessage());
    }

    @Test
    @DisplayName("限域形状非法：空租户/含冒号租户/member-only 缺 member 都拒绝")
    void scopeShapeIsValidated() {
        assertThrows(ClientException.class, () -> new BizChangeLogReadScope(" ", MEMBER_A, false));
        assertThrows(ClientException.class, () -> new BizChangeLogReadScope("T:A", MEMBER_A, false));
        assertThrows(ClientException.class, () -> new BizChangeLogReadScope(TENANT_A, null, false));
        assertEquals(TENANT_A, new BizChangeLogReadScope(TENANT_A, null, true).tenantId());
    }

    @Test
    @DisplayName("由当前主体构造：tenant/member 精确取自 ExecutionPrincipal")
    void scopeComesFromCurrentPrincipal() {
        PrincipalContext.set(new ExecutionPrincipal(TENANT_A, "7", MEMBER_A, 1, 1,
            Set.of("ai:run:read"), "jti-1", "platform", 0L, Long.MAX_VALUE));

        BizChangeLogReadScope memberOnly = BizChangeLogReadScope.memberOnlyCurrent();

        assertEquals(TENANT_A, memberOnly.tenantId());
        assertEquals(MEMBER_A, memberOnly.memberId());
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<BizChangeLogDO> capturedPageWrapper() {
        ArgumentCaptor<LambdaQueryWrapper<BizChangeLogDO>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectPage(any(), captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<BizChangeLogDO> capturedGetWrapper() {
        ArgumentCaptor<LambdaQueryWrapper<BizChangeLogDO>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectOne(captor.capture());
        return captor.getValue();
    }

    private BizChangeLogReadScope memberScope() {
        return new BizChangeLogReadScope(TENANT_A, MEMBER_A, false);
    }
}
