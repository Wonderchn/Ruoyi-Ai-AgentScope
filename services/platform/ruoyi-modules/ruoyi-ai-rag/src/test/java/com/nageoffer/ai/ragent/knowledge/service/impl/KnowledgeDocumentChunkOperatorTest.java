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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.core.ingest.IngestionKernel;
import com.nageoffer.ai.ragent.core.ingest.sink.ChunkIndexWriter;
import com.nageoffer.ai.ragent.core.parser.registry.ParserRegistry;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionPipelineMapper;
import com.nageoffer.ai.ragent.ingestion.engine.IngestionEngine;
import com.nageoffer.ai.ragent.ingestion.service.IngestionPipelineService;
import com.nageoffer.ai.ragent.knowledge.config.KnowledgeScheduleProperties;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentChunkLogMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.handler.RemoteFileFetcher;
import com.nageoffer.ai.ragent.knowledge.mq.event.KnowledgeDocumentChunkEvent;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeChunkService;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentScheduleService;
import com.nageoffer.ai.ragent.knowledge.support.IngestionSpecCodec;
import com.nageoffer.ai.ragent.knowledge.support.VectorTargetResolver;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F22:2 真机第六缺口：内嵌传输下 {@code UserContext} 恒空，chunk 事件的 operator 变成 null，
 * 消费端 {@code RelationalChunkSink} 批量 insert 触发 {@code ai_knowledge_chunk.created_by}
 * NOT NULL 违约（真机 {@code app-6044-f222-lrmq5.log:3074-3080}）。
 *
 * <p>口径与 KB 创建一致：缺少旧用户名时回退到已验签主体的 {@code userId}；旧用户名存在时
 * 仍优先（保持独立应用行为）。本测试只 mock mapper/producer，直接断言事件携带的 operator。
 */
@Tag("dev")
class KnowledgeDocumentChunkOperatorTest {

    @Test
    @DisplayName("UserContext 空时：事件 operator 回退到 PrincipalContext 的验签 userId")
    void fallsBackToPrincipalUserIdWhenUserContextIsBlank() {
        AtomicReference<Object> captured = new AtomicReference<>();
        KnowledgeDocumentServiceImpl service = newService(captured);
        try {
            UserContext.clear();
            PrincipalContext.set(principal("000000", "1"));

            service.startChunk("doc-1");

            KnowledgeDocumentChunkEvent event = (KnowledgeDocumentChunkEvent) captured.get();
            assertThat(event).isNotNull();
            assertThat(event.getTenantId()).isEqualTo("000000");
            assertThat(event.getOperator())
                    .as("created_by/updated_by 是 NOT NULL；内嵌下必须回退到主体验签 userId")
                    .isEqualTo("1");
        } finally {
            PrincipalContext.clear();
            UserContext.clear();
        }
    }

    @Test
    @DisplayName("UserContext 有值时优先使用旧用户名（独立应用行为不变）")
    void keepsUserContextUsernameWhenPresent() {
        AtomicReference<Object> captured = new AtomicReference<>();
        KnowledgeDocumentServiceImpl service = newService(captured);
        try {
            UserContext.clear();
            UserContext.set(LoginUser.builder().username("legacy-user").build());
            PrincipalContext.set(principal("000000", "1"));

            service.startChunk("doc-1");

            KnowledgeDocumentChunkEvent event = (KnowledgeDocumentChunkEvent) captured.get();
            assertThat(event).isNotNull();
            assertThat(event.getOperator()).isEqualTo("legacy-user");
        } finally {
            PrincipalContext.clear();
            UserContext.clear();
        }
    }

    private static KnowledgeDocumentServiceImpl newService(AtomicReference<Object> captured) {
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeDocumentDO document = new KnowledgeDocumentDO();
        document.setId("doc-1");
        document.setKbId("kb-1");
        document.setDocName("doc");
        when(documentMapper.selectOne(any())).thenReturn(document);

        MessageQueueProducer producer = mock(MessageQueueProducer.class);
        doAnswer(invocation -> {
            // sendInTransaction(topic, keys, bizDesc, body, localTransaction)
            captured.set(invocation.getArgument(3));
            return null;
        }).when(producer).sendInTransaction(any(), any(), any(), any(), any());

        return new KnowledgeDocumentServiceImpl(
                mock(KnowledgeBaseMapper.class),
                documentMapper,
                mock(ParserRegistry.class),
                mock(IngestionKernel.class),
                mock(ChunkIndexWriter.class),
                mock(IngestionSpecCodec.class),
                mock(FileStorageService.class),
                mock(VectorStoreService.class),
                mock(KnowledgeChunkService.class),
                new ObjectMapper(),
                mock(KnowledgeDocumentScheduleService.class),
                mock(IngestionPipelineService.class),
                mock(IngestionPipelineMapper.class),
                mock(IngestionEngine.class),
                mock(KnowledgeDocumentChunkLogMapper.class),
                mock(KnowledgeChunkMapper.class),
                mock(TransactionOperations.class),
                producer,
                new KnowledgeScheduleProperties(),
                mock(RemoteFileFetcher.class),
                mock(VectorTargetResolver.class),
                mock(BizChangeLogContext.class));
    }

    private static ExecutionPrincipal principal(String tenantId, String userId) {
        return new ExecutionPrincipal(
                tenantId, userId, ExecutionPrincipal.canonicalMembershipId(tenantId, userId),
                1, 1, Set.of(), "jti-test", "platform:local", 1_000L, 2_000L);
    }
}
