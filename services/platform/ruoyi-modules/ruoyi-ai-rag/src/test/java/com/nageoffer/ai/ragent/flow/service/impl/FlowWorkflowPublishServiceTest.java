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

package com.nageoffer.ai.ragent.flow.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.flow.controller.request.FlowWorkflowQueryRequest;
import com.nageoffer.ai.ragent.flow.controller.request.FlowWorkflowSaveRequest;
import com.nageoffer.ai.ragent.flow.controller.vo.FlowComponentVO;
import com.nageoffer.ai.ragent.flow.controller.vo.FlowWorkflowVO;
import com.nageoffer.ai.ragent.flow.dao.entity.AiFlowComponentDO;
import com.nageoffer.ai.ragent.flow.dao.entity.AiFlowWorkflowDO;
import com.nageoffer.ai.ragent.flow.dao.mapper.AiFlowComponentMapper;
import com.nageoffer.ai.ragent.flow.dao.mapper.AiFlowWorkflowMapper;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R17 F13-M1：发布/公开面的服务层判据（红先行）。
 *
 * <p><b>公开语义写死</b>：同租户、跨用户、只读。公开搜索/详情的可见性谓词固定为
 * {@code is_public=1 AND is_enable=1 AND is_deleted=0 AND tenant_id=当前租户}，
 * 且<b>没有</b> {@code user_id} 谓词（同租户内跨用户可见）；跨租户由 tenant 谓词排除，
 * 永不因 {@code is_public} 可见。
 *
 * <p><b>写路径</b>（enable/set-public）沿用 {@code update} 的「id + owner + tenant + 未删」
 * 谓词，0 行即失败；公开开关不得顺带改启用标志，启用开关不得顺带改公开标志。
 *
 * <p><b>公开读不写库</b>：searchPublic/getPublic/listPublicComponents 不产生任何 INSERT/UPDATE/DELETE。
 */
@ExtendWith(MockitoExtension.class)
@Tag("dev")
class FlowWorkflowPublishServiceTest {

    private static final String TENANT = "T1";
    private static final String USER = "2101";
    private static final long USER_ID = 2101L;

    @Mock
    private AiFlowWorkflowMapper workflowMapper;

    @Mock
    private AiFlowComponentMapper componentMapper;

    @InjectMocks
    private FlowWorkflowServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // LambdaQueryWrapper 需要列名元数据；两个实体各初始化一次。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AiFlowWorkflowDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AiFlowComponentDO.class);
    }

    @BeforeEach
    void principal() {
        PrincipalContext.set(new ExecutionPrincipal(TENANT, USER, "platform:" + TENANT + ":" + USER,
                1, 1, Set.of("ai:flow:list", "ai:flow:read", "ai:flow:publish"),
                "jti", "platform", 0, Long.MAX_VALUE));
    }

    @AfterEach
    void clear() {
        PrincipalContext.clear();
    }

    // ------------------------------------------------------------------ 公开搜索

    @Test
    @DisplayName("公开搜索：同租户 + is_public=1 + is_enable=1 + is_deleted=0，且没有 owner 谓词")
    void searchPublicBindsTenantPublicEnabledDeletedAndNeverOwner() {
        FlowWorkflowQueryRequest request = new FlowWorkflowQueryRequest();
        Page<AiFlowWorkflowDO> page = new Page<>(1, 10);
        page.setRecords(List.of(workflow("wf-public", USER_ID, 1, 1, 0)));
        when(workflowMapper.selectPage(any(), any())).thenReturn(page);

        IPage<FlowWorkflowVO> result = service.searchPublic(request);

        ArgumentCaptor<LambdaQueryWrapper<AiFlowWorkflowDO>> captor = queryCaptor();
        verify(workflowMapper).selectPage(any(), captor.capture());
        assertPublicVisibilityPredicates(captor.getValue());
        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().get(0).getUuid()).isEqualTo("wf-public");
        assertThat(result.getRecords().get(0).getUserId())
                .as("公开只读面不暴露所有者 id")
                .isNull();
    }

    @Test
    @DisplayName("公开搜索：keyword 过滤不能放宽可见性谓词")
    void searchPublicKeywordFilterNeverDropsVisibilityPredicates() {
        FlowWorkflowQueryRequest request = new FlowWorkflowQueryRequest();
        request.setKeyword("季度");
        Page<AiFlowWorkflowDO> page = new Page<>(1, 10);
        when(workflowMapper.selectPage(any(), any())).thenReturn(page);

        service.searchPublic(request);

        ArgumentCaptor<LambdaQueryWrapper<AiFlowWorkflowDO>> captor = queryCaptor();
        verify(workflowMapper).selectPage(any(), captor.capture());
        assertPublicVisibilityPredicates(captor.getValue());
        assertThat(captor.getValue().getSqlSegment()).contains("title");
    }

    @Test
    @DisplayName("公开搜索：调用方的 isEnable 入参不得替换/叠加公开启用条件（恒为 is_enable=1）")
    void searchPublicIgnoresCallerEnableFilterAndAppliesExactlyOneEnabledPredicate() {
        FlowWorkflowQueryRequest request = new FlowWorkflowQueryRequest();
        request.setIsEnable(0); // 客户端试图搜索停用工作流：公开面必须无视
        Page<AiFlowWorkflowDO> page = new Page<>(1, 10);
        when(workflowMapper.selectPage(any(), any())).thenReturn(page);

        service.searchPublic(request);

        ArgumentCaptor<LambdaQueryWrapper<AiFlowWorkflowDO>> captor = queryCaptor();
        verify(workflowMapper).selectPage(any(), captor.capture());
        assertThat(occurrences(captor.getValue().getSqlSegment(), "is_enable"))
                .as("公开搜索只能有唯一的启用条件（=1）；客户端入参 0 不能参与")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ 公开详情

    @Test
    @DisplayName("公开详情：不存在/停用/私有/软删/跨租户同形 null，谓词固定且无 owner")
    void getPublicBindsPublicVisibilityPredicatesAndReturnsNullWhenInvisible() {
        when(workflowMapper.selectOne(any())).thenReturn(null);

        assertThat(service.getPublic("wf-hidden")).isNull();

        ArgumentCaptor<LambdaQueryWrapper<AiFlowWorkflowDO>> captor = queryCaptor();
        verify(workflowMapper).selectOne(captor.capture());
        assertPublicVisibilityPredicates(captor.getValue());
    }

    @Test
    @DisplayName("公开详情：视图保留公开字段但把 userId 置空（不暴露所有者 id）")
    void getPublicScrubsOwnerIdFromTheView() {
        AiFlowWorkflowDO row = workflow("wf-public", USER_ID, 1, 1, 0);
        row.setRemark("public-remark");
        Date created = new Date(1_700_000_000_000L);
        Date updated = new Date(1_700_000_100_000L);
        row.setCreateTime(created);
        row.setUpdateTime(updated);
        when(workflowMapper.selectOne(any())).thenReturn(row);

        FlowWorkflowVO vo = service.getPublic("wf-public");

        assertThat(vo).isNotNull();
        assertThat(vo.getUuid()).isEqualTo("wf-public");
        assertThat(vo.getTitle()).isEqualTo("f13m1-workflow");
        assertThat(vo.getRemark()).isEqualTo("public-remark");
        assertThat(vo.getIsPublic()).isEqualTo(1);
        assertThat(vo.getIsEnable()).isEqualTo(1);
        assertThat(vo.getCreateTime()).isEqualTo(created);
        assertThat(vo.getUpdateTime()).isEqualTo(updated);
        assertThat(vo.getUserId()).as("公开只读面不暴露所有者 id").isNull();
    }

    // ------------------------------------------------------------------ set-public

    @Test
    @DisplayName("set-public：只改 is_public，谓词含 id + owner + tenant + 未删")
    void setPublicOnlyTouchesPublicFlagWithOwnerTenantPredicates() {
        when(workflowMapper.selectOne(any())).thenReturn(workflow("wf-1", USER_ID, 0, 1, 0));
        when(workflowMapper.update(any(), any())).thenReturn(1);

        service.setPublic("wf-1", true);

        ArgumentCaptor<AiFlowWorkflowDO> patch = ArgumentCaptor.forClass(AiFlowWorkflowDO.class);
        ArgumentCaptor<LambdaQueryWrapper<AiFlowWorkflowDO>> wrapper = queryCaptor();
        verify(workflowMapper).update(patch.capture(), wrapper.capture());
        assertThat(patch.getValue().getIsPublic()).isEqualTo(1);
        assertThat(patch.getValue().getIsEnable())
                .as("公开/取消公开不得改变启用标志")
                .isNull();
        assertWritePredicates(wrapper.getValue());
    }

    @Test
    @DisplayName("set-public(false)：写 0，不触碰启用标志")
    void unsetPublicWritesZeroAndKeepsEnableFlagUntouched() {
        when(workflowMapper.selectOne(any())).thenReturn(workflow("wf-1", USER_ID, 1, 1, 0));
        when(workflowMapper.update(any(), any())).thenReturn(1);

        service.setPublic("wf-1", false);

        ArgumentCaptor<AiFlowWorkflowDO> patch = ArgumentCaptor.forClass(AiFlowWorkflowDO.class);
        verify(workflowMapper).update(patch.capture(), any());
        assertThat(patch.getValue().getIsPublic()).isZero();
        assertThat(patch.getValue().getIsEnable()).isNull();
    }

    @Test
    @DisplayName("set-public 越权：非 owner 直接拒绝且不写库")
    void setPublicRejectsNonOwnerBeforeAnyWrite() {
        when(workflowMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.setPublic("wf-foreign", true))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("工作流不存在");

        verify(workflowMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("set-public 0 行：业务失败，不静默成功")
    void setPublicFailsWhenNoRowWasUpdated() {
        when(workflowMapper.selectOne(any())).thenReturn(workflow("wf-1", USER_ID, 0, 1, 0));
        when(workflowMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.setPublic("wf-1", true))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("工作流公开状态更新未生效");
    }

    // ------------------------------------------------------------------ enable

    @Test
    @DisplayName("enable：只改 is_enable，谓词含 id + owner + tenant + 未删")
    void enableOnlyTouchesEnableFlagWithOwnerTenantPredicates() {
        when(workflowMapper.selectOne(any())).thenReturn(workflow("wf-1", USER_ID, 1, 1, 0));
        when(workflowMapper.update(any(), any())).thenReturn(1);

        service.enable("wf-1", false);

        ArgumentCaptor<AiFlowWorkflowDO> patch = ArgumentCaptor.forClass(AiFlowWorkflowDO.class);
        ArgumentCaptor<LambdaQueryWrapper<AiFlowWorkflowDO>> wrapper = queryCaptor();
        verify(workflowMapper).update(patch.capture(), wrapper.capture());
        assertThat(patch.getValue().getIsEnable()).isZero();
        assertThat(patch.getValue().getIsPublic())
                .as("启用/停用不得改变公开标志")
                .isNull();
        assertWritePredicates(wrapper.getValue());
    }

    @Test
    @DisplayName("enable 越权：非 owner 直接拒绝且不写库")
    void enableRejectsNonOwnerBeforeAnyWrite() {
        when(workflowMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.enable("wf-foreign", true))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("工作流不存在");

        verify(workflowMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("enable 0 行：业务失败，不静默成功")
    void enableFailsWhenNoRowWasUpdated() {
        when(workflowMapper.selectOne(any())).thenReturn(workflow("wf-1", USER_ID, 1, 1, 0));
        when(workflowMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.enable("wf-1", true))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("工作流启用状态更新未生效");
    }

    // ------------------------------------------------------------------ 公开组件库

    @Test
    @DisplayName("公开组件库：当前租户 + is_enable=1 + is_deleted=0，返回组件视图")
    void listPublicComponentsBindsTenantEnabledDeletedAndMapsView() {
        when(componentMapper.selectList(any())).thenReturn(List.of(component("comp-1")));

        List<FlowComponentVO> out = service.listPublicComponents();

        ArgumentCaptor<LambdaQueryWrapper<AiFlowComponentDO>> captor = queryCaptor();
        verify(componentMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertThat(sql).contains("tenant_id", "is_enable", "is_deleted");
        assertThat(sql).doesNotContain("user_id");
        assertThat(captor.getValue().getParamNameValuePairs()).containsValue(TENANT);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getUuid()).isEqualTo("comp-1");
        assertThat(out.get(0).getName()).isEqualTo("Start");
        assertThat(out.get(0).getIsEnable()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 只读/不改变新建语义

    @Test
    @DisplayName("公开读三条路径零写库")
    void publicReadPathsNeverWrite() {
        Page<AiFlowWorkflowDO> page = new Page<>(1, 10);
        when(workflowMapper.selectPage(any(), any())).thenReturn(page);
        when(workflowMapper.selectOne(any())).thenReturn(null);
        when(componentMapper.selectList(any())).thenReturn(List.of());

        service.searchPublic(new FlowWorkflowQueryRequest());
        service.getPublic("wf-hidden");
        service.listPublicComponents();

        verify(workflowMapper, never()).insert(any(AiFlowWorkflowDO.class));
        verify(workflowMapper, never()).update(any(), any());
        verify(workflowMapper, never()).delete(any());
        verify(componentMapper, never()).insert(any(AiFlowComponentDO.class));
        verify(componentMapper, never()).update(any(), any());
        verify(componentMapper, never()).delete(any());
    }

    @Test
    @DisplayName("create 仍写 is_public=0：发布开关只属于 set-public")
    void createStillCreatesWorkflowsPrivate() {
        when(workflowMapper.insert(any(AiFlowWorkflowDO.class))).thenReturn(1);
        when(workflowMapper.selectOne(any())).thenReturn(workflow("wf-new", USER_ID, 0, 1, 0));
        FlowWorkflowSaveRequest request = new FlowWorkflowSaveRequest();
        request.setTitle("new-flow");
        request.setRemark("r");

        service.create(request);

        ArgumentCaptor<AiFlowWorkflowDO> inserted = ArgumentCaptor.forClass(AiFlowWorkflowDO.class);
        verify(workflowMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getIsPublic())
                .as("新建工作流默认不公开")
                .isZero();
    }

    // ------------------------------------------------------------------ 夹具与断言

    private static void assertPublicVisibilityPredicates(AbstractWrapper<?, ?, ?> wrapper) {
        String sql = wrapper.getSqlSegment();
        assertThat(sql)
                .as("公开可见性谓词：tenant + is_public + is_enable + is_deleted 缺一不可")
                .contains("tenant_id", "is_public", "is_enable", "is_deleted");
        assertThat(sql)
                .as("公开=同租户跨用户：不得出现 owner 谓词")
                .doesNotContain("user_id");
        assertThat(wrapper.getParamNameValuePairs())
                .as("租户必须绑定当前执行主体；跨租户永不因 is_public 可见")
                .containsValue(TENANT);
    }

    private static void assertWritePredicates(AbstractWrapper<?, ?, ?> wrapper) {
        String sql = wrapper.getSqlSegment();
        assertThat(sql).contains("user_id", "tenant_id", "is_deleted");
        assertThat(wrapper.getParamNameValuePairs())
                .as("写路径必须绑定当前主体 user 与 tenant")
                .containsValues(USER_ID, TENANT);
    }

    private static AiFlowWorkflowDO workflow(String uuid, long userId, int isPublic, int isEnable, int isDeleted) {
        AiFlowWorkflowDO row = new AiFlowWorkflowDO();
        row.setId(41L);
        row.setUuid(uuid);
        row.setTitle("f13m1-workflow");
        row.setUserId(userId);
        row.setIsPublic(isPublic);
        row.setIsEnable(isEnable);
        row.setIsDeleted(isDeleted);
        row.setTenantId(TENANT);
        return row;
    }

    private static AiFlowComponentDO component(String uuid) {
        AiFlowComponentDO row = new AiFlowComponentDO();
        row.setId(71L);
        row.setUuid(uuid);
        row.setName("Start");
        row.setTitle("f13m1-component");
        row.setRemark("");
        row.setDisplayOrder(0);
        row.setIsEnable(1);
        row.setIsDeleted(0);
        row.setTenantId(TENANT);
        return row;
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> ArgumentCaptor<LambdaQueryWrapper<T>> queryCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    }
}
