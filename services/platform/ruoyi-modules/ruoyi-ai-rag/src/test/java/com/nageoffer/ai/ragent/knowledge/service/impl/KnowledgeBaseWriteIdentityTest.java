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

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseCreateRequest;
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

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * S2-F05-A2：知识库创建路径的写身份判据（真机 503 缺陷修复）。
 *
 * <p>真机实证（ENV-BATCH-3 P0，`pg-log-create-503.txt`）：{@code INSERT ai_knowledge_base}
 * 缺 {@code created_by}/{@code updated_by}/{@code owner_member_id} 三列 ⇒ PG NOT NULL 违约
 * ⇒ 网关收敛为 503 {@code DEPENDENCY_UNAVAILABLE}。根因双锚：
 * <ul>
 *   <li>内嵌传输下 {@code UserContextInterceptor} 对 {@code /internal/ai/v1/} + PrincipalContext 跳过
 *       {@code UserContext} 填充（本地传输恒命中）⇒ {@code UserContext.getUsername()} 恒为 null；</li>
 *   <li>{@code KnowledgeBaseServiceImpl.create} 未调用 {@code AiDomainWriteIdentity.apply}。</li>
 * </ul>
 *
 * <p>判据（红先行，均为"能红"的反例锚点）：
 * <ul>
 *   <li>create 必须把 {@code tenant_id}/{@code owner_member_id} 从执行主体写入
 *       （V7 NOT NULL；经 {@code AiDomainWriteIdentity.apply}），
 *       审计列 {@code created_by}/{@code updated_by} 同取主体（V7 {@code created_by} NOT NULL 无默认值）；</li>
 *   <li>缺主体时 fail-closed 拒绝（{@code ClientException}）且<b>不触达任何 mapper/存储</b>。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@Tag("dev")
class KnowledgeBaseWriteIdentityTest {

    private static final String TENANT = "T1";
    private static final String USER = "2101";
    private static final String MEMBER = "platform:T1:2101";

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

    /** Lambda wrapper 的列名解析需要实体 TableInfo（与 F06-A1 判据同一修法）。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeBaseDO.class);
    }

    @BeforeEach
    void principal() {
        PrincipalContext.set(new ExecutionPrincipal(TENANT, USER, MEMBER,
                1, 1, Set.of("kb.write"), "test-jti", "platform:test", 1L, 9_999_999_999L));
    }

    @AfterEach
    void clear() {
        PrincipalContext.clear();
    }

    @Test
    void createFillsTenantOwnerMemberAndAuditColumnsFromTheExecutionPrincipal() {
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of());
        when(knowledgeBaseMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            invocation.getArgument(0, KnowledgeBaseDO.class).setId("kb-a2-1");
            return 1;
        }).when(knowledgeBaseMapper).insert(any(KnowledgeBaseDO.class));

        KnowledgeBaseCreateRequest request = new KnowledgeBaseCreateRequest();
        request.setName("f05-a2-kb");
        request.setEmbeddingModel("qwen3-embedding:8b-fp16");
        request.setCollectionName("f05_a2_coll");

        assertThat(service.create(request)).isEqualTo("kb-a2-1");

        ArgumentCaptor<KnowledgeBaseDO> inserted = ArgumentCaptor.forClass(KnowledgeBaseDO.class);
        verify(knowledgeBaseMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getTenantId())
                .as("tenant_id 是 V7 NOT NULL：必须由执行主体经 AiDomainWriteIdentity 写入（不靠列默认值）")
                .isEqualTo(TENANT);
        assertThat(inserted.getValue().getOwnerMemberId())
                .as("owner_member_id 是 V7 NOT NULL：真机 503 根因之一")
                .isEqualTo(MEMBER);
        assertThat(inserted.getValue().getCreatedBy())
                .as("created_by 是 V7 NOT NULL 且无默认值；内嵌态 UserContext 恒空，只能取执行主体")
                .isEqualTo(USER);
        assertThat(inserted.getValue().getUpdatedBy())
                .as("updated_by 与 created_by 同一 fail-closed 口径")
                .isEqualTo(USER);
    }

    @Test
    void createRejectsWithoutAnExecutionPrincipalAndNeverTouchesTheDatabase() {
        PrincipalContext.clear();
        KnowledgeBaseCreateRequest request = new KnowledgeBaseCreateRequest();
        request.setName("no-principal");
        request.setEmbeddingModel("qwen3-embedding:8b-fp16");
        request.setCollectionName("no_principal_coll");

        assertThatThrownBy(() -> service.create(request))
                .as("缺主体必须拒绝（fail-closed），不允许匿名兜底写入")
                .isInstanceOf(ClientException.class);
        verifyNoInteractions(knowledgeBaseMapper, knowledgeDocumentMapper, fileStorageService, vectorStoreAdmin);
    }
}
