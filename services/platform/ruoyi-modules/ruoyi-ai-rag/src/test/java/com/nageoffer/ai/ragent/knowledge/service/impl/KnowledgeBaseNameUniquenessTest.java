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

import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseCreateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RW-04：知识库名称唯一性（新增 / 重命名）——判重口径必须与入库口径一致。
 *
 * <p>原实现只把入参去空白、却把入参原样入库，于是「季度 报告」与「季度报告」
 * 在库里是两行而判重互相查不到：同一个名字能被建两次。这里钉住修复后的口径。
 */
@ExtendWith(MockitoExtension.class)
@Tag("dev")
class KnowledgeBaseNameUniquenessTest {

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

    /** Lambda 列名解析依赖 MyBatis-Plus 的 lambda 缓存，纯单元测试里需显式装配。 */
    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeBaseDO.class);
    }

    @BeforeEach
    void principal() {
        PrincipalContext.set(new ExecutionPrincipal("T1", "2101", "platform:T1:2101",
                1, 1, Set.of("kb.write"), "jti", "platform", 0, Long.MAX_VALUE));
    }

    @AfterEach
    void clear() {
        PrincipalContext.clear();
    }

    @Test
    void createShouldRejectNameThatOnlyDiffersByWhitespace() {
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(kb("1800000000000000001", "季度 报告")));

        KnowledgeBaseCreateRequest request = new KnowledgeBaseCreateRequest();
        request.setName("季度报告");
        request.setEmbeddingModel("qwen3-embedding:8b-fp16");
        request.setCollectionName("kb-new");

        ServiceException ex = assertThrows(ServiceException.class, () -> service.create(request));

        assertEquals("知识库名称已存在：季度报告", ex.getErrorMessage());
        verify(knowledgeBaseMapper, never()).insert(any(KnowledgeBaseDO.class));
    }

    @Test
    void createShouldAcceptGenuinelyNewNameAndKeepCallerSpelling() {
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(kb("1800000000000000001", "季度 报告")));
        when(knowledgeBaseMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            invocation.getArgument(0, KnowledgeBaseDO.class).setId("1800000000000000009");
            return 1;
        }).when(knowledgeBaseMapper).insert(any(KnowledgeBaseDO.class));

        KnowledgeBaseCreateRequest request = new KnowledgeBaseCreateRequest();
        request.setName("年 度报告");
        request.setEmbeddingModel("qwen3-embedding:8b-fp16");
        request.setCollectionName("kb-new");

        String id = service.create(request);

        assertEquals("1800000000000000009", id);
        ArgumentCaptor<KnowledgeBaseDO> captor = ArgumentCaptor.forClass(KnowledgeBaseDO.class);
        verify(knowledgeBaseMapper).insert(captor.capture());
        // 判定归一化，但落库保留调用方给出的原始写法：不改用户数据
        assertEquals("年 度报告", captor.getValue().getName());
    }

    @Test
    void renameShouldAllowKeepingOwnNameDespiteWhitespace() {
        when(knowledgeBaseMapper.selectOne(any()))
                .thenReturn(kb("1800000000000000001", "季度 报告"));
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(
                kb("1800000000000000001", "季度 报告"),
                kb("1800000000000000002", "别的库")));

        KnowledgeBaseUpdateRequest request = new KnowledgeBaseUpdateRequest();
        request.setName("季度报告");

        service.rename("1800000000000000001", request);

        verify(knowledgeBaseMapper).update(any(), any());
    }

    @Test
    void renameShouldRejectNameCollidingWithAnotherKnowledgeBase() {
        when(knowledgeBaseMapper.selectOne(any()))
                .thenReturn(kb("1800000000000000001", "季度 报告"));
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(
                kb("1800000000000000001", "季度 报告"),
                kb("1800000000000000002", "别 的库")));

        KnowledgeBaseUpdateRequest request = new KnowledgeBaseUpdateRequest();
        request.setName("别的库");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.rename("1800000000000000001", request));

        assertEquals("知识库名称已存在：别的库", ex.getErrorMessage());
        verify(knowledgeBaseMapper, never()).update(any(), any());
    }

    private static KnowledgeBaseDO kb(String id, String name) {
        return KnowledgeBaseDO.builder()
                .id(id)
                .tenantId("T1")
                .name(name)
                .collectionName("kb-" + id)
                .embeddingModel("qwen3-embedding:8b-fp16")
                .deleted(0)
                .build();
    }
}
