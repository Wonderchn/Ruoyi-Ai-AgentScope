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
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.flow.controller.vo.FlowPublicInputsVO;
import com.nageoffer.ai.ragent.flow.dao.entity.AiFlowComponentDO;
import com.nageoffer.ai.ragent.flow.dao.entity.AiFlowNodeDO;
import com.nageoffer.ai.ragent.flow.dao.entity.AiFlowWorkflowDO;
import com.nageoffer.ai.ragent.flow.dao.mapper.AiFlowComponentMapper;
import com.nageoffer.ai.ragent.flow.dao.mapper.AiFlowEdgeMapper;
import com.nageoffer.ai.ragent.flow.dao.mapper.AiFlowNodeMapper;
import com.nageoffer.ai.ragent.flow.dao.mapper.AiFlowWorkflowMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
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

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R17 F13:4：公开工作流 start 输入 schema 的服务层判据（红先行）。
 *
 * <p><b>可见性写死</b>：{@code tenant=当前 AND uuid=? AND is_public=1 AND is_enable=1
 * AND is_deleted=0}；<b>没有</b> owner 谓词。不可见（私有/停用/软删/跨租户/不存在）
 * 一律返回 null，由控制器映射为同一个 404 文案。
 *
 * <p><b>响应白名单</b>：只回传 {@code uuid/title/inputs/nodes}；nodes 只含
 * {@code uuid/title/type}，<b>不得</b>携带 node_config / prompt / owner / tenant。
 *
 * <p><b>输入 schema 兼容</b>：优先 {@code input_config.user_inputs}，兼容
 * {@code input_config.params}；type 归一（1 文本 / 2 数字 / 4 文件 / 5 布尔 /
 * 字符串 string|number|boolean|file）；解析失败 → {@code inputs=[]}，不 500。
 */
@ExtendWith(MockitoExtension.class)
@Tag("dev")
class FlowPublicInputsServiceTest {

    private static final String TENANT = "T1";
    private static final String USER = "2101";

    @Mock
    private AiFlowWorkflowMapper workflowMapper;

    @Mock
    private AiFlowNodeMapper nodeMapper;

    @Mock
    private AiFlowEdgeMapper edgeMapper;

    @Mock
    private AiFlowComponentMapper componentMapper;

    @InjectMocks
    private FlowGraphServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AiFlowWorkflowDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AiFlowNodeDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AiFlowComponentDO.class);
    }

    @BeforeEach
    void principal() {
        PrincipalContext.set(new ExecutionPrincipal(TENANT, USER, "platform:" + TENANT + ":" + USER,
                1, 1, Set.of("ai:flow:list", "ai:flow:read"),
                "jti", "platform", 0, Long.MAX_VALUE));
    }

    @AfterEach
    void clear() {
        PrincipalContext.clear();
    }

    // ------------------------------------------------------------------ 正例

    @Test
    @DisplayName("公开+启用：返回 START 输入 schema 与三节点视图，且不泄露 node_config/prompt/owner/tenant")
    void publicEnabledWorkflowReturnsStartInputsAndScrubbedNodeViews() throws Exception {
        when(workflowMapper.selectOne(any())).thenReturn(workflow("wf-public", 1, 1, 0));
        when(nodeMapper.selectList(any())).thenReturn(List.of(
                node("n-start", "开始", "{\"type\":\"START\"}",
                        "{\"user_inputs\":["
                                + "{\"uuid\":\"i-1\",\"name\":\"var_user_input\",\"title\":\"用户输入\","
                                + "\"type\":1,\"required\":true,\"max_length\":1000},"
                                + "{\"uuid\":\"i-2\",\"name\":\"count\",\"title\":\"数量\","
                                + "\"type\":2,\"required\":false},"
                                + "{\"uuid\":\"i-3\",\"name\":\"flag\",\"title\":\"开关\","
                                + "\"type\":5,\"required\":false,\"limit\":1}]}"),
                node("n-llm", "生成回答", "{\"type\":\"LLM\",\"prompt\":\"secret {input}\"}", "{}"),
                node("n-end", "结束", "{\"type\":\"END\"}", "{}")));

        FlowPublicInputsVO vo = service.getPublicStartInputs("wf-public");

        assertThat(vo).isNotNull();
        assertThat(vo.getUuid()).isEqualTo("wf-public");
        assertThat(vo.getTitle()).isEqualTo("f13-4-workflow");
        assertThat(vo.getInputs()).hasSize(3);
        assertThat(vo.getInputs().get(0).getName()).isEqualTo("var_user_input");
        assertThat(vo.getInputs().get(0).getTitle()).isEqualTo("用户输入");
        assertThat(vo.getInputs().get(0).getType()).isEqualTo(1);
        assertThat(vo.getInputs().get(0).getRequired()).isTrue();
        assertThat(vo.getInputs().get(0).getLimit()).isEqualTo(1000);
        assertThat(vo.getInputs().get(1).getType()).isEqualTo(2);
        assertThat(vo.getInputs().get(2).getType()).isEqualTo(5);
        assertThat(vo.getInputs().get(2).getLimit()).isEqualTo(1);
        assertThat(vo.getNodes()).extracting(FlowPublicInputsVO.Node::getType)
                .containsExactly("START", "LLM", "END");
        assertThat(vo.getNodes()).extracting(FlowPublicInputsVO.Node::getUuid)
                .containsExactly("n-start", "n-llm", "n-end");
        assertThat(vo.getNodes()).extracting(FlowPublicInputsVO.Node::getTitle)
                .containsExactly("开始", "生成回答", "结束");

        String json = new ObjectMapper().writeValueAsString(vo);
        assertThat(json)
                .as("公开输入 schema 不得回传执行配置/提示词/所有者/租户")
                .doesNotContain("nodeConfig")
                .doesNotContain("node_config")
                .doesNotContain("prompt")
                .doesNotContain("secret")
                .doesNotContain("userId")
                .doesNotContain("tenantId");

        verify(componentMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("可见性谓词：tenant + is_public + is_enable + is_deleted，且没有 owner 谓词")
    void visibilityPredicatesAreTenantPublicEnabledDeletedAndNeverOwner() {
        when(workflowMapper.selectOne(any())).thenReturn(null);

        assertThat(service.getPublicStartInputs("wf-hidden")).isNull();

        ArgumentCaptor<LambdaQueryWrapper<AiFlowWorkflowDO>> captor = queryCaptor();
        verify(workflowMapper).selectOne(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertThat(sql)
                .as("四条件缺一不可")
                .contains("tenant_id", "is_public", "is_enable", "is_deleted");
        assertThat(sql)
                .as("公开=同租户跨用户：不得出现 owner 谓词")
                .doesNotContain("user_id");
        assertThat(captor.getValue().getParamNameValuePairs())
                .as("租户必须绑定当前执行主体")
                .containsValue(TENANT);
    }

    @Test
    @DisplayName("params 兼容形态：字符串 type 归一为 2/5/4")
    void paramsFallbackIsAcceptedAndStringTypesAreNormalised() {
        when(workflowMapper.selectOne(any())).thenReturn(workflow("wf-public", 1, 1, 0));
        when(nodeMapper.selectList(any())).thenReturn(List.of(
                node("n-start", "开始", "{\"type\":\"START\"}",
                        "{\"params\":["
                                + "{\"name\":\"n1\",\"title\":\"数字\",\"type\":\"number\"},"
                                + "{\"name\":\"b1\",\"title\":\"布尔\",\"type\":\"boolean\"},"
                                + "{\"name\":\"f1\",\"title\":\"文件\",\"type\":\"file\"}]}"),
                node("n-llm", "回答", "{\"type\":\"LLM\",\"prompt\":\"{input}\"}", "{}"),
                node("n-end", "结束", "{\"type\":\"END\"}", "{}")));

        FlowPublicInputsVO vo = service.getPublicStartInputs("wf-public");

        assertThat(vo.getInputs()).extracting(FlowPublicInputsVO.Input::getType)
                .containsExactly(2, 5, 4);
        assertThat(vo.getInputs()).extracting(FlowPublicInputsVO.Input::getName)
                .containsExactly("n1", "b1", "f1");
    }

    @Test
    @DisplayName("组件名识别：node_config.type 缺失时按组件名 Start/Answer/End 解析类型")
    void componentNameStartIsRecognisedWhenNodeConfigTypeIsMissing() {
        when(workflowMapper.selectOne(any())).thenReturn(workflow("wf-public", 1, 1, 0));
        AiFlowNodeDO start = node("n-start", "开始", null,
                "{\"user_inputs\":[{\"name\":\"q\",\"title\":\"问题\",\"type\":1}]}");
        start.setWorkflowComponentId(71L);
        AiFlowNodeDO llm = node("n-llm", "回答", "{\"type\":\"LLM\",\"prompt\":\"{input}\"}", "{}");
        AiFlowNodeDO end = node("n-end", "结束", "{\"type\":\"END\"}", "{}");
        when(nodeMapper.selectList(any())).thenReturn(List.of(start, llm, end));
        when(componentMapper.selectList(any())).thenReturn(List.of(component(71L, "Start")));

        FlowPublicInputsVO vo = service.getPublicStartInputs("wf-public");

        assertThat(vo).isNotNull();
        assertThat(vo.getNodes()).extracting(FlowPublicInputsVO.Node::getType)
                .containsExactly("START", "LLM", "END");
        assertThat(vo.getInputs()).extracting(FlowPublicInputsVO.Input::getName)
                .containsExactly("q");
    }

    @Test
    @DisplayName("input_config 解析失败：inputs=[]，不抛 500；节点视图仍按可识别类型返回")
    void malformedInputConfigYieldsEmptyInputsInsteadOfError() {
        when(workflowMapper.selectOne(any())).thenReturn(workflow("wf-public", 1, 1, 0));
        when(nodeMapper.selectList(any())).thenReturn(List.of(
                node("n-start", "开始", "{\"type\":\"START\"}", "not-json"),
                node("n-llm", "回答", "{\"type\":\"LLM\"}", "{}"),
                node("n-end", "结束", "not-json", "{}")));

        FlowPublicInputsVO vo = service.getPublicStartInputs("wf-public");

        assertThat(vo).isNotNull();
        assertThat(vo.getInputs()).isEmpty();
        assertThat(vo.getNodes()).extracting(FlowPublicInputsVO.Node::getType)
                .containsExactly("START", "LLM");
    }

    @Test
    @DisplayName("没有可识别 START：inputs=[]，不臆造输入项")
    void noStartNodeYieldsEmptyInputs() {
        when(workflowMapper.selectOne(any())).thenReturn(workflow("wf-public", 1, 1, 0));
        when(nodeMapper.selectList(any())).thenReturn(List.of(
                node("n-llm", "回答", "{\"type\":\"LLM\",\"prompt\":\"{input}\"}", "{}"),
                node("n-end", "结束", "{\"type\":\"END\"}", "{}")));

        FlowPublicInputsVO vo = service.getPublicStartInputs("wf-public");

        assertThat(vo.getInputs()).isEmpty();
        assertThat(vo.getNodes()).extracting(FlowPublicInputsVO.Node::getType)
                .containsExactly("LLM", "END");
    }

    // ------------------------------------------------------------------ 夹具

    private static AiFlowWorkflowDO workflow(String uuid, int isPublic, int isEnable, int isDeleted) {
        AiFlowWorkflowDO row = new AiFlowWorkflowDO();
        row.setId(41L);
        row.setUuid(uuid);
        row.setTitle("f13-4-workflow");
        row.setUserId(2101L);
        row.setIsPublic(isPublic);
        row.setIsEnable(isEnable);
        row.setIsDeleted(isDeleted);
        row.setTenantId(TENANT);
        return row;
    }

    private static AiFlowNodeDO node(String uuid, String title, String nodeConfig, String inputConfig) {
        AiFlowNodeDO row = new AiFlowNodeDO();
        row.setId((long) Math.abs(uuid.hashCode()));
        row.setUuid(uuid);
        row.setWorkflowId(41L);
        row.setUserId(2101L);
        row.setTitle(title);
        row.setNodeConfig(nodeConfig);
        row.setInputConfig(inputConfig);
        row.setIsDeleted(0);
        row.setTenantId(TENANT);
        return row;
    }

    private static AiFlowComponentDO component(Long id, String name) {
        AiFlowComponentDO row = new AiFlowComponentDO();
        row.setId(id);
        row.setUuid("comp-" + id);
        row.setName(name);
        row.setTitle(name);
        row.setRemark("");
        row.setDisplayOrder(0);
        row.setIsEnable(1);
        row.setIsDeleted(0);
        row.setTenantId(TENANT);
        return row;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> ArgumentCaptor<LambdaQueryWrapper<T>> queryCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    }
}
