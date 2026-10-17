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

package com.nageoffer.ai.ragent.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseCreateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBasePageRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeBaseVO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * R17 F05:0：知识库管理面的<b>空授权 fail-closed</b>与<b>不扩为全租户</b>（离线服务层）。
 *
 * <p>判据映射（任务单判据 1）：
 * <ul>
 *   <li>{@link #missingPrincipalRefusesEveryOperationBeforeTouchingBackends}：空/缺主体 → 零后端调用；</li>
 *   <li>{@link #listAndDetailQueriesBindCurrentTenantAndNeverExpand}：列表/详情的查询条件载体带租户谓词
 *       且绑定当前主体租户（没有"全租户"分支）；</li>
 *   <li>{@link #foreignOrCrossTenantIdReadsAsNotFoundWithoutAnyWrite}：别租户 id 与不存在同外显，
 *       且拒绝路径零写入、零清理消息；</li>
 *   <li>{@link #listWithNameFilterStillCarriesTenantPredicate}：加了名字过滤也不能把租户条件过滤掉。</li>
 * </ul>
 *
 * <p>边界（只报告不冒领）：管理面本身不含 ACL 主体类型语义（member/department/role/tenant_all 的
 * grant→read→revoke 在资源面授权服务），本类只钉管理面的租户圈定与 fail-closed；ACL 四类循环见
 * {@code com.nageoffer.ai.ragent.authorization.AiResourceAclSubjectClassCycleTest}。
 */
@ExtendWith(MockitoExtension.class)
@Tag("dev")
class KnowledgeBaseScopeFailClosedTest {

    private static final String TENANT = "T1";
    private static final String USER = "2101";

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;
    @Mock
    private KnowledgeDocumentMapper knowledgeDocumentMapper;
    @Mock
    private VectorStoreAdmin vectorStoreAdmin;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private MessageQueueProducer messageQueueProducer;
    @Mock
    private BizChangeLogContext bizChangeLogContext;

    @InjectMocks
    private KnowledgeBaseServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeBaseDO.class);
    }

    @BeforeEach
    void principal() {
        PrincipalContext.set(new ExecutionPrincipal(TENANT, USER, "platform:" + TENANT + ":" + USER,
                1, 1, Set.of("kb.read", "kb.write", "kb.delete"), "jti", "platform", 0, Long.MAX_VALUE));
    }

    @AfterEach
    void clear() {
        PrincipalContext.clear();
    }

    /** 空授权上下文：六个入口全部在触达任何后端之前拒绝（KB 查询/文件存储/向量空间/MQ 零交互）。 */
    @Test
    void missingPrincipalRefusesEveryOperationBeforeTouchingBackends() {
        PrincipalContext.clear();

        assertThatThrownBy(() -> service.create(createRequest())).isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> service.queryById("kb-1")).isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> service.pageQuery(new KnowledgeBasePageRequest())).isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> service.update(updateRequest())).isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> service.rename("kb-1", updateRequest())).isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> service.delete("kb-1")).isInstanceOf(ClientException.class);

        verifyNoInteractions(knowledgeBaseMapper, knowledgeDocumentMapper, vectorStoreAdmin,
                fileStorageService, messageQueueProducer, bizChangeLogContext);
    }

    /** 租户条件载体：详情与列表的查询都必须带 tenant_id，且绑定的就是当前主体租户。 */
    @Test
    void listAndDetailQueriesBindCurrentTenantAndNeverExpand() {
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(kb("kb-1"));

        KnowledgeBaseVO vo = service.queryById("kb-1");
        assertThat(vo.getId()).isEqualTo("kb-1");

        ArgumentCaptor<LambdaQueryWrapper<KnowledgeBaseDO>> detail = queryCaptor();
        verify(knowledgeBaseMapper).selectOne(detail.capture());
        assertTenantScoped(detail.getValue());

        when(knowledgeBaseMapper.selectPage(any(), any())).thenReturn(new Page<>(1, 10));
        service.pageQuery(new KnowledgeBasePageRequest());

        ArgumentCaptor<LambdaQueryWrapper<KnowledgeBaseDO>> list = queryCaptor();
        verify(knowledgeBaseMapper).selectPage(any(), list.capture());
        assertTenantScoped(list.getValue());
        assertThat(list.getValue().getSqlSegment()).contains("deleted");
    }

    /** 别租户/不存在：同外显"不存在"，且拒绝路径不写库、不发清理消息、不碰存储与向量。 */
    @Test
    void foreignOrCrossTenantIdReadsAsNotFoundWithoutAnyWrite() {
        // 租户谓词会把他租户行挡掉，selectOne 返回 null（与"不存在"同一外显）
        assertThatThrownBy(() -> service.queryById("kb-foreign"))
                .isInstanceOf(ClientException.class).hasMessageContaining("知识库不存在");
        assertThatThrownBy(() -> service.delete("kb-foreign"))
                .isInstanceOf(ClientException.class).hasMessageContaining("知识库不存在");

        verify(knowledgeBaseMapper, never()).delete(any());
        verify(messageQueueProducer, never()).sendInTransaction(any(), any(), any(), any(), any());
        verifyNoInteractions(fileStorageService, vectorStoreAdmin);
    }

    /** 带名字过滤的列表：租户条件仍在，不能因为"加了 where"就把它丢掉。 */
    @Test
    void listWithNameFilterStillCarriesTenantPredicate() {
        KnowledgeBasePageRequest request = new KnowledgeBasePageRequest();
        request.setName("季度");
        when(knowledgeBaseMapper.selectPage(any(), any())).thenReturn(new Page<>(1, 10));

        service.pageQuery(request);

        ArgumentCaptor<LambdaQueryWrapper<KnowledgeBaseDO>> captor = queryCaptor();
        verify(knowledgeBaseMapper).selectPage(any(), captor.capture());
        assertTenantScoped(captor.getValue());
        assertThat(captor.getValue().getSqlSegment()).contains("name");
    }

    // ------------------------------------------------------------------ 夹具与断言

    private static void assertTenantScoped(AbstractWrapper<?, ?, ?> wrapper) {
        String sql = wrapper.getSqlSegment();
        assertThat(sql).as("查询必须带租户谓词，禁止全租户扫描").contains("tenant_id");
        assertThat(wrapper.getParamNameValuePairs()).as("租户必须绑定当前执行主体")
                .containsValue(TENANT);
    }

    private static KnowledgeBaseCreateRequest createRequest() {
        KnowledgeBaseCreateRequest request = new KnowledgeBaseCreateRequest();
        request.setName("季度知识库");
        request.setCollectionName("coll-1");
        request.setEmbeddingModel("qwen3-embedding:8b-fp16");
        return request;
    }

    private static KnowledgeBaseUpdateRequest updateRequest() {
        KnowledgeBaseUpdateRequest request = new KnowledgeBaseUpdateRequest();
        request.setId("kb-1");
        request.setName("季度知识库");
        return request;
    }

    private static KnowledgeBaseDO kb(String kbId) {
        return KnowledgeBaseDO.builder()
                .id(kbId)
                .name("季度知识库")
                .collectionName("coll-1")
                .embeddingModel("qwen3-embedding:8b-fp16")
                .deleted(0)
                .build();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<LambdaQueryWrapper<KnowledgeBaseDO>> queryCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    }
}
