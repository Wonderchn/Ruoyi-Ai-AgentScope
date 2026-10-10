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
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.rag.controller.request.RagTraceRunPageRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.RagTraceDetailVO;
import com.nageoffer.ai.ragent.rag.controller.vo.RagTraceNodeVO;
import com.nageoffer.ai.ragent.rag.controller.vo.RagTraceRunVO;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceNodeDO;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceNodeMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceRunMapper;
import com.nageoffer.ai.ragent.rag.trace.RagTraceReadScope;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RW-23（F18 op1）：RAG Trace token 用量三字段契约。
 *
 * <p>号源＝{@code ai_rag_trace_node/ai_rag_trace_run.extra_data} 的
 * {@code prompt_tokens/completion_tokens/total_tokens} 三键（与 R11 夹具形状一致，零迁移）。
 * 三态语义：缺键 / JSON {@code null} / 非数字 / 坏 JSON → {@code null}（未知，不得伪 0）；
 * 显式 {@code 0} → {@code 0}；UNKNOWN / 未对账不在本面表达（对账归 F23/B 面）。
 */
@Tag("dev")
class RagTraceTokenUsageContractTest {

    private static final String TENANT_A = "T-A";

    private static final String MEMBER_A = "platform:T-A:7";

    private final RagTraceRunMapper runMapper = mock(RagTraceRunMapper.class);

    private final RagTraceNodeMapper nodeMapper = mock(RagTraceNodeMapper.class);

    private final UserMapper userMapper = mock(UserMapper.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

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
        service = new RagTraceQueryServiceImpl(runMapper, nodeMapper, userMapper, objectMapper);
    }

    @Test
    @DisplayName("R11 夹具形状：节点两键落值、缺 total_tokens→null；run 无 token 键→全 null")
    void tokensFollowR11FixtureShape() {
        // 逐字对齐 R11 seed-fixtures.sql: node extra_data={"prompt_tokens":11,"completion_tokens":22}
        RagTraceRunDO run = run("trace-a-1", "{\"r11\":\"seed-a1\"}");
        RagTraceNodeDO node = RagTraceNodeDO.builder()
                .traceId("trace-a-1").nodeId("n1").nodeType("LLM")
                .extraData("{\"prompt_tokens\":11,\"completion_tokens\":22}")
                .build();
        when(runMapper.selectOne(any())).thenReturn(run);
        when(nodeMapper.selectList(any())).thenReturn(List.of(node));

        RagTraceDetailVO detail = service.detail("trace-a-1", memberScope());

        RagTraceRunVO runVO = detail.getRun();
        assertNull(runVO.getPromptTokens());
        assertNull(runVO.getCompletionTokens());
        assertNull(runVO.getTotalTokens());
        RagTraceNodeVO nodeVO = detail.getNodes().get(0);
        assertEquals(Integer.valueOf(11), nodeVO.getPromptTokens());
        assertEquals(Integer.valueOf(22), nodeVO.getCompletionTokens());
        assertNull(nodeVO.getTotalTokens(), "夹具无 total_tokens → null，不得由 11+22 推算");
    }

    @Test
    @DisplayName("三键齐：run 与 node 各自解析（detail.run 与 detail.nodes 同级同口径）")
    void allThreeKeysParsedOnRunAndNode() {
        RagTraceRunDO run = run("trace-a-1", "{\"prompt_tokens\":5,\"completion_tokens\":6,\"total_tokens\":11}");
        RagTraceNodeDO node = RagTraceNodeDO.builder()
                .traceId("trace-a-1").nodeId("n1")
                .extraData("{\"prompt_tokens\":100,\"completion_tokens\":200,\"total_tokens\":300}")
                .build();
        when(runMapper.selectOne(any())).thenReturn(run);
        when(nodeMapper.selectList(any())).thenReturn(List.of(node));

        RagTraceDetailVO detail = service.detail("trace-a-1", memberScope());

        assertEquals(Integer.valueOf(5), detail.getRun().getPromptTokens());
        assertEquals(Integer.valueOf(6), detail.getRun().getCompletionTokens());
        assertEquals(Integer.valueOf(11), detail.getRun().getTotalTokens());
        RagTraceNodeVO nodeVO = detail.getNodes().get(0);
        assertEquals(Integer.valueOf(100), nodeVO.getPromptTokens());
        assertEquals(Integer.valueOf(200), nodeVO.getCompletionTokens());
        assertEquals(Integer.valueOf(300), nodeVO.getTotalTokens());
    }

    @Test
    @DisplayName("显式 0 → 0（不是 null）：provider 真实报了 0 才算 0")
    void explicitZeroStaysZero() {
        RagTraceRunDO run = run("trace-a-1", null);
        RagTraceNodeDO node = RagTraceNodeDO.builder()
                .traceId("trace-a-1").nodeId("n1")
                .extraData("{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0}")
                .build();
        when(runMapper.selectOne(any())).thenReturn(run);
        when(nodeMapper.selectList(any())).thenReturn(List.of(node));

        RagTraceNodeVO nodeVO = service.detail("trace-a-1", memberScope()).getNodes().get(0);

        assertEquals(Integer.valueOf(0), nodeVO.getPromptTokens());
        assertEquals(Integer.valueOf(0), nodeVO.getCompletionTokens());
        assertEquals(Integer.valueOf(0), nodeVO.getTotalTokens());
    }

    @Test
    @DisplayName("JSON null / 非数字值 → null（未知不得伪 0，不猜不转义）")
    void nullAndNonNumericValuesStayNull() {
        RagTraceRunDO run = run("trace-a-1", null);
        RagTraceNodeDO node = RagTraceNodeDO.builder()
                .traceId("trace-a-1").nodeId("n1")
                .extraData("{\"prompt_tokens\":null,\"completion_tokens\":\"22\",\"total_tokens\":true}")
                .build();
        when(runMapper.selectOne(any())).thenReturn(run);
        when(nodeMapper.selectList(any())).thenReturn(List.of(node));

        RagTraceNodeVO nodeVO = service.detail("trace-a-1", memberScope()).getNodes().get(0);

        assertNull(nodeVO.getPromptTokens());
        assertNull(nodeVO.getCompletionTokens());
        assertNull(nodeVO.getTotalTokens());
    }

    @Test
    @DisplayName("坏 JSON → 全 null 且不抛（run/node 读路径宽容，不因脏数据 500）")
    void badJsonYieldsNullWithoutThrowing() {
        RagTraceRunDO run = run("trace-a-1", "{\"prompt_tokens\":12,");
        RagTraceNodeDO node = RagTraceNodeDO.builder()
                .traceId("trace-a-1").nodeId("n1")
                .extraData("not-json")
                .build();
        when(runMapper.selectOne(any())).thenReturn(run);
        when(nodeMapper.selectList(any())).thenReturn(List.of(node));

        RagTraceDetailVO detail = service.detail("trace-a-1", memberScope());

        assertNull(detail.getRun().getPromptTokens());
        assertNull(detail.getRun().getCompletionTokens());
        assertNull(detail.getRun().getTotalTokens());
        RagTraceNodeVO nodeVO = detail.getNodes().get(0);
        assertNull(nodeVO.getPromptTokens());
        assertNull(nodeVO.getCompletionTokens());
        assertNull(nodeVO.getTotalTokens());
    }

    @Test
    @DisplayName("列表面（pageRuns）同样解析 run extra_data 三键")
    void listFaceParsesRunTokens() {
        RagTraceRunPageRequest request = new RagTraceRunPageRequest();
        RagTraceRunDO run = run("trace-a-1", "{\"prompt_tokens\":7,\"completion_tokens\":8,\"total_tokens\":15}");
        request.setRecords(List.of(run));
        when(runMapper.selectPage(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeMapper.selectList(any())).thenReturn(List.of());

        IPage<RagTraceRunVO> page = service.pageRuns(request, memberScope());

        RagTraceRunVO runVO = page.getRecords().get(0);
        assertEquals(Integer.valueOf(7), runVO.getPromptTokens());
        assertEquals(Integer.valueOf(8), runVO.getCompletionTokens());
        assertEquals(Integer.valueOf(15), runVO.getTotalTokens());
    }

    private RagTraceRunDO run(String traceId, String extraData) {
        return RagTraceRunDO.builder()
                .traceId(traceId).tenantId(TENANT_A).memberId(MEMBER_A).status("SUCCESS")
                .extraData(extraData)
                .build();
    }

    private RagTraceReadScope memberScope() {
        return new RagTraceReadScope(TENANT_A, MEMBER_A, false);
    }
}
