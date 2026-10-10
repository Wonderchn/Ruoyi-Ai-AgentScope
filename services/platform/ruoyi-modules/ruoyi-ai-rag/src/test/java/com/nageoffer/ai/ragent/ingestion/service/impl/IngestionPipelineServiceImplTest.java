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

package com.nageoffer.ai.ragent.ingestion.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionPipelineCreateRequest;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionPipelineNodeRequest;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionPipelineUpdateRequest;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionPipelineDO;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionPipelineNodeDO;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionPipelineMapper;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionPipelineNodeMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Tag;

/**
 * 摄取管线服务的写入身份与租户谓词判据（S2-F06-A1 追加）。
 *
 * <p>既有判据保留：更新时物理删除旧节点再重建（顺序钉住）。
 * 追加判据（均为"能红"的反例锚点）：
 * <ul>
 *   <li>create 必须把 tenant/owner_member_id 从执行主体写入（V7 NOT NULL；
 *       缺主体时 fail-closed 拒绝且不触达 mapper）；</li>
 *   <li>读路径（get/update/delete/getDefinition）必须带租户谓词——跨租户 id 与
 *       不存在同外显（包装器上逐字断言 {@code tenant_id} 谓词与主体租户参数）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@Tag("dev")
class IngestionPipelineServiceImplTest {

    private static final String TENANT = "T1";
    private static final String MEMBER = "platform:T1:2101";

    @Mock
    private IngestionPipelineMapper pipelineMapper;

    @Mock
    private IngestionPipelineNodeMapper nodeMapper;

    @Mock
    private BizChangeLogContext bizChangeLogContext;

    private IngestionPipelineServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // 单测无 Spring/MyBatis 启动：lambda wrapper 的列名解析需要实体 TableInfo
        // （与 RW23DashboardTenantScopeTest 同一修法）。
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, IngestionPipelineDO.class);
    }

    @BeforeEach
    void setUp() {
        service = new IngestionPipelineServiceImpl(
                pipelineMapper,
                nodeMapper,
                new ObjectMapper(),
                bizChangeLogContext
        );
        PrincipalContext.set(new ExecutionPrincipal(TENANT, "2101", MEMBER, 1, 1,
                Set.of("ai:config:read", "ai:config:publish"), "test-jti", "platform:test", 1L, 9_999_999_999L));
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    void updatePhysicallyDeletesExistingNodesBeforeReinsertingThem() {
        String pipelineId = "pipeline-1";
        IngestionPipelineDO pipeline = IngestionPipelineDO.builder()
                .id(pipelineId)
                .name("default")
                .build();
        when(pipelineMapper.selectOne(any())).thenReturn(pipeline);
        when(nodeMapper.selectList(any())).thenReturn(List.of());

        IngestionPipelineNodeRequest node = new IngestionPipelineNodeRequest();
        node.setNodeId("fetcher-1");
        node.setNodeType("fetcher");
        IngestionPipelineUpdateRequest request = new IngestionPipelineUpdateRequest();
        request.setNodes(List.of(node));

        service.update(pipelineId, request);

        InOrder order = inOrder(nodeMapper);
        order.verify(nodeMapper).physicalDeleteByPipelineId(pipelineId);
        order.verify(nodeMapper).insert(argThat((IngestionPipelineNodeDO inserted) ->
                pipelineId.equals(inserted.getPipelineId())
                        && "fetcher-1".equals(inserted.getNodeId())
        ));
    }

    @Test
    void createFillsTenantAndOwnerMemberFromTheExecutionPrincipal() {
        IngestionPipelineCreateRequest request = new IngestionPipelineCreateRequest();
        request.setName("f06-pipeline");
        when(pipelineMapper.insert(any(IngestionPipelineDO.class))).thenReturn(1);
        when(nodeMapper.selectList(any())).thenReturn(List.of());

        service.create(request);

        ArgumentCaptor<IngestionPipelineDO> inserted = ArgumentCaptor.forClass(IngestionPipelineDO.class);
        verify(pipelineMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getTenantId())
                .as("tenant_id 是 V7 NOT NULL，必须由执行主体写入（不靠列默认值）")
                .isEqualTo(TENANT);
        assertThat(inserted.getValue().getOwnerMemberId())
                .as("owner_member_id 是 V7 NOT NULL，必须由执行主体写入")
                .isEqualTo(MEMBER);
    }

    @Test
    void createRejectsWithoutAnExecutionPrincipalAndNeverTouchesTheDatabase() {
        PrincipalContext.clear();
        IngestionPipelineCreateRequest request = new IngestionPipelineCreateRequest();
        request.setName("no-principal");

        assertThatThrownBy(() -> service.create(request))
                .as("缺主体必须拒绝（fail-closed），不允许匿名兜底写入")
                .isInstanceOf(ClientException.class);
        verifyNoInteractions(pipelineMapper, nodeMapper);
    }

    @Test
    void singleRowReadsCarryTheTenantPredicateAndRejectWithoutPrincipal() {
        IngestionPipelineDO pipeline = IngestionPipelineDO.builder().id("p-9").name("scoped").build();
        when(pipelineMapper.selectOne(any())).thenReturn(pipeline);
        when(nodeMapper.selectList(any())).thenReturn(List.of());

        service.get("p-9");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<IngestionPipelineDO>> wrapper =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(pipelineMapper).selectOne(wrapper.capture());
        assertThat(wrapper.getValue().getSqlSegment())
                .as("单行读必须带租户谓词（代理主键全局唯一，但访问路径要限域）")
                .contains("tenant_id");
        assertThat(wrapper.getValue().getParamNameValuePairs().values())
                .as("谓词参数取当前主体的 tenantId")
                .contains(TENANT);

        PrincipalContext.clear();
        assertThatThrownBy(() -> service.get("p-9"))
                .as("无主体时读路径同样拒绝，不返回跨域行")
                .isInstanceOf(ClientException.class);
    }
}
