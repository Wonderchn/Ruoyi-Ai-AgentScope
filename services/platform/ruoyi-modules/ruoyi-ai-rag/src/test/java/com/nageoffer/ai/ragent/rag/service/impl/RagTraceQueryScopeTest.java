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

package com.nageoffer.ai.ragent.rag.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.controller.request.RagTraceRunPageRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.RagTraceNodeVO;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceNodeDO;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceNodeMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceRunMapper;
import com.nageoffer.ai.ragent.rag.trace.RagTraceReadScope;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RW-23（F18 op1/op5）：RAG Trace 读路径必须按 {@code tenant_id}（非 tenant-wide 时再加
 * {@code member_id}）限域，且跨租户 traceId 与"不存在"同外显。
 *
 * <p>改前行为：{@code pageRuns/detail/listNodes} 只按 traceId/conversationId/taskId/status 过滤，
 * 没有任何身份条件 —— 任何能到达端点的人都能列出/读取所有租户的链路。
 */
@Tag("dev")
class RagTraceQueryScopeTest {

    private static final String TENANT_A = "T-A";

    private static final String TENANT_B = "T-B";

    private static final String MEMBER_A = "platform:T-A:7";

    private static final String MEMBER_B = "platform:T-B:9";

    private final RagTraceRunMapper runMapper = mock(RagTraceRunMapper.class);

    private final RagTraceNodeMapper nodeMapper = mock(RagTraceNodeMapper.class);

    private final UserMapper userMapper = mock(UserMapper.class);

    private RagTraceQueryServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // 单测无 Spring/MyBatis 启动：LambdaQueryWrapper 解析列名需要实体的 TableInfo
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, RagTraceRunDO.class);
        TableInfoHelper.initTableInfo(assistant, RagTraceNodeDO.class);
    }

    @BeforeEach
    void setUp() {
        service = new RagTraceQueryServiceImpl(runMapper, nodeMapper, userMapper, new ObjectMapper());
        PrincipalContext.clear();
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    @DisplayName("分页：查询条件带 tenant_id 与 member_id（非 tenant-wide）")
    void pageRunsAppliesTenantAndMemberFilter() {
        when(runMapper.selectPage(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.pageRuns(new RagTraceRunPageRequest(), memberScope());

        LambdaQueryWrapper<RagTraceRunDO> captured = capturedPageWrapper();
        String sql = captured.getSqlSegment();
        assertTrue(sql.contains("tenant_id"), () -> "缺少 tenant_id 条件: " + sql);
        assertTrue(sql.contains("member_id"), () -> "member 限域应带 member_id: " + sql);
        assertTrue(captured.getParamNameValuePairs().containsValue(TENANT_A));
        assertTrue(captured.getParamNameValuePairs().containsValue(MEMBER_A));
    }

    @Test
    @DisplayName("分页：tenant-wide 只带 tenant_id，不带 member_id（运维面租户内全量）")
    void pageRunsTenantWideOmitsMemberFilter() {
        when(runMapper.selectPage(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.pageRuns(new RagTraceRunPageRequest(), new RagTraceReadScope(TENANT_A, MEMBER_A, true));

        LambdaQueryWrapper<RagTraceRunDO> captured = capturedPageWrapper();
        String sql = captured.getSqlSegment();
        assertTrue(sql.contains("tenant_id"), () -> "缺少 tenant_id 条件: " + sql);
        assertFalse(sql.contains("member_id"), () -> "tenant-wide 不应再收窄到成员: " + sql);
        assertFalse(captured.getParamNameValuePairs().containsValue(MEMBER_A));
    }

    @Test
    @DisplayName("跨租户 traceId 与不存在同外显：detail 返回 null 且查询带本租户条件")
    void foreignTraceDetailLooksMissing() {
        // 限域后 DB 命中不了别的租户的行 → mapper 返回 null（与真不存在同一分支）
        when(runMapper.selectOne(any())).thenReturn(null);

        assertNull(service.detail("trace-of-tenant-b", memberScope()));

        LambdaQueryWrapper<RagTraceRunDO> captured = capturedRunWrapper();
        // 先生成 SQL 片段：MyBatis-Plus 的 lambda→列名解析与参数落表都在这一步完成
        String sql = captured.getSqlSegment();
        assertTrue(sql.contains("trace_id"), () -> "sql=" + sql);
        assertTrue(sql.contains("tenant_id"), () -> "缺少 tenant_id 条件: " + sql);
        assertTrue(sql.contains("member_id"), () -> "member 限域应带 member_id: " + sql);
        assertTrue(captured.getParamNameValuePairs().containsValue(TENANT_A),
            () -> "params=" + captured.getParamNameValuePairs());
        assertFalse(captured.getParamNameValuePairs().containsValue(TENANT_B));
        assertFalse(captured.getParamNameValuePairs().containsValue(MEMBER_B));
    }

    @Test
    @DisplayName("跨租户 traceId 的节点查询不发节点 SQL（节点表无租户列，必须靠父 run 限域）")
    void foreignTraceNodesNeverQueryNodeTable() {
        when(runMapper.selectOne(any())).thenReturn(null);

        List<RagTraceNodeVO> nodes = service.listNodes("trace-of-tenant-b", memberScope());

        assertTrue(nodes.isEmpty());
        verify(nodeMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("在域内的 traceId：返回节点")
    void inScopeTraceReturnsNodes() {
        RagTraceRunDO run = RagTraceRunDO.builder()
            .traceId("trace-a-1").tenantId(TENANT_A).memberId(MEMBER_A).status("SUCCESS").build();
        when(runMapper.selectOne(any())).thenReturn(run);
        RagTraceNodeDO node = RagTraceNodeDO.builder().traceId("trace-a-1").nodeId("n1").build();
        when(nodeMapper.selectList(any())).thenReturn(List.of(node));

        List<RagTraceNodeVO> nodes = service.listNodes("trace-a-1", memberScope());

        assertEquals(1, nodes.size());
        assertEquals("n1", nodes.get(0).getNodeId());
        verify(nodeMapper).selectList(any());
    }

    @Test
    @DisplayName("无执行主体：拒绝（fail-closed，不降级为全量）")
    void scopeRequiresPrincipal() {
        ClientException exception = assertThrows(ClientException.class, RagTraceReadScope::tenantWideCurrent);
        assertTrue(exception.getMessage().contains("principal"), exception.getMessage());
    }

    @Test
    @DisplayName("限域形状非法：空租户/含冒号租户/member-only 缺 member 都拒绝")
    void scopeShapeIsValidated() {
        assertThrows(ClientException.class, () -> new RagTraceReadScope(" ", MEMBER_A, false));
        assertThrows(ClientException.class, () -> new RagTraceReadScope("T:A", MEMBER_A, false));
        assertThrows(ClientException.class, () -> new RagTraceReadScope(TENANT_A, null, false));
        assertThrows(ClientException.class, () -> new RagTraceReadScope(" T-A", MEMBER_A, false));
        // tenant-wide 可以不要求 member（但 tenant 必须合法）
        assertEquals(TENANT_A, new RagTraceReadScope(TENANT_A, null, true).tenantId());
    }

    @Test
    @DisplayName("由当前主体构造：tenant/member 精确取自 ExecutionPrincipal")
    void scopeComesFromCurrentPrincipal() {
        PrincipalContext.set(principal());

        RagTraceReadScope memberOnly = RagTraceReadScope.memberOnlyCurrent();
        RagTraceReadScope tenantWide = RagTraceReadScope.tenantWideCurrent();

        assertEquals(TENANT_A, memberOnly.tenantId());
        assertEquals(MEMBER_A, memberOnly.memberId());
        assertFalse(memberOnly.tenantWide());
        assertEquals(TENANT_A, tenantWide.tenantId());
        assertTrue(tenantWide.tenantWide());
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<RagTraceRunDO> capturedRunWrapper() {
        ArgumentCaptor<LambdaQueryWrapper<RagTraceRunDO>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(runMapper).selectOne(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<RagTraceRunDO> capturedPageWrapper() {
        ArgumentCaptor<LambdaQueryWrapper<RagTraceRunDO>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(runMapper).selectPage(any(), captor.capture());
        return captor.getValue();
    }

    private RagTraceReadScope memberScope() {
        return new RagTraceReadScope(TENANT_A, MEMBER_A, false);
    }

    private ExecutionPrincipal principal() {
        return new ExecutionPrincipal(TENANT_A, "7", MEMBER_A, 1, 1,
            Set.of("ai:run:read"), "jti-1", "platform", 0L, Long.MAX_VALUE);
    }
}
