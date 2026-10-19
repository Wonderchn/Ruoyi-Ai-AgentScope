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

package com.nageoffer.ai.ragent.rag.core.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService.Verdict;
import com.nageoffer.ai.ragent.rag.config.GraphProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class GraphProjectionServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FakeGraphProjectionRepository repository;
    private AiResourceAuthorizationService authorization;
    private LightRagClient client;
    private GraphProperties properties;
    private GraphProjectionService service;
    private ExecutionPrincipal principal;

    @BeforeEach
    void setUp() {
        repository = new FakeGraphProjectionRepository();
        authorization = mock(AiResourceAuthorizationService.class);
        client = mock(LightRagClient.class);
        properties = new GraphProperties();
        ObjectProvider<LightRagClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        TransactionOperations transactions = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
        service = new GraphProjectionService(repository, provider, properties, authorization, transactions);
        principal = new ExecutionPrincipal("T1", "2101", "platform:T1:2101", 1, 1,
                Set.of("kb.write"), "jti-1", "platform", 1, 9_999_999_999L);
        PrincipalContext.set(principal);
        when(authorization.check(principal, "kb.write", "kb:kb")).thenReturn(Verdict.GRANT);
        repository.kbs.put("kb", new GraphProjectionRepository.KbRow("kb", "KB", "kb"));
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    private GraphProjectionRepository.DocRow doc(String id, String status) {
        GraphProjectionRepository.DocRow row = new GraphProjectionRepository.DocRow(
                id, "kb", "文档" + id, "kb", status, 2);
        repository.docs.put(id, row);
        repository.texts.put(id, "正文" + id);
        return row;
    }

    private static LightRagClient.DocumentState state(LightRagClient.DocumentStatus status) {
        return new LightRagClient.DocumentState(status, "lightrag-" + status, status.name().toLowerCase());
    }

    @Test
    @DisplayName("入参二选一：都为空 400")
    void bothSelectorsMissingIsBadRequest() {
        GraphOperationException failure = assertThrows(GraphOperationException.class,
                () -> service.rebuild(null, null));
        assertEquals(GraphErrorCode.GRAPH_BAD_REQUEST, failure.errorCode());
    }

    @Test
    @DisplayName("跨租户/不存在：404，不调用 LightRAG")
    void unknownDocumentIsNotFound() throws Exception {
        GraphOperationException failure = assertThrows(GraphOperationException.class,
                () -> service.rebuild(null, "doc-foreign"));
        assertEquals(GraphErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, failure.errorCode());
        verify(client, never()).documentState(anyString(), anyString());
    }

    @Test
    @DisplayName("未授权 KB：404，不调用 LightRAG")
    void unauthorizedKbIsNotFound() throws Exception {
        when(authorization.check(principal, "kb.write", "kb:kb")).thenReturn(Verdict.DENY);

        GraphOperationException failure = assertThrows(GraphOperationException.class,
                () -> service.rebuild("kb", null));

        assertEquals(GraphErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, failure.errorCode());
        verify(client, never()).documentState(anyString(), anyString());
    }

    @Test
    @DisplayName("R7 未入图：先 insertTextOrThrow 再 409 NOT_READY，不写入投影")
    void missingDocumentTriggersIngestAndNotReady() throws Exception {
        doc("d1", "success");
        when(client.documentState("kb_d1", "d1")).thenReturn(state(LightRagClient.DocumentStatus.MISSING));

        GraphOperationException failure = assertThrows(GraphOperationException.class,
                () -> service.rebuild(null, "d1"));

        assertEquals(GraphErrorCode.GRAPH_DOCUMENT_NOT_READY, failure.errorCode());
        verify(client).insertTextOrThrow("正文d1", "kb_d1");
        assertTrue(repository.replacedDocs.isEmpty());
    }

    @Test
    @DisplayName("R7 processing/pending：409 NOT_READY，且不重复写入")
    void pendingDocumentIsNotReadyWithoutIngest() throws Exception {
        doc("d1", "success");
        when(client.documentState("kb_d1", "d1")).thenReturn(state(LightRagClient.DocumentStatus.PENDING));

        GraphOperationException failure = assertThrows(GraphOperationException.class,
                () -> service.rebuild(null, "d1"));

        assertEquals(GraphErrorCode.GRAPH_DOCUMENT_NOT_READY, failure.errorCode());
        verify(client, never()).insertTextOrThrow(anyString(), anyString());
    }

    @Test
    @DisplayName("R7 failed：409 GRAPH_DOCUMENT_FAILED")
    void failedDocumentIsReported() throws Exception {
        doc("d1", "success");
        when(client.documentState("kb_d1", "d1")).thenReturn(state(LightRagClient.DocumentStatus.FAILED));

        GraphOperationException failure = assertThrows(GraphOperationException.class,
                () -> service.rebuild(null, "d1"));

        assertEquals(GraphErrorCode.GRAPH_DOCUMENT_FAILED, failure.errorCode());
    }

    @Test
    @DisplayName("R7 截断：409 TRUNCATED，旧投影完全不动")
    void truncatedGraphNeverOverwritesProjection() throws Exception {
        doc("d1", "success");
        when(client.documentState("kb_d1", "d1")).thenReturn(state(LightRagClient.DocumentStatus.PROCESSED));
        when(client.fetchGraphStrict("*", properties.getProjection().getMaxDepth(),
                properties.getProjection().getMaxNodes()))
                .thenReturn(MAPPER.readTree("{\"nodes\":[],\"edges\":[],\"is_truncated\":true}"));

        GraphOperationException failure = assertThrows(GraphOperationException.class,
                () -> service.rebuild(null, "d1"));

        assertEquals(GraphErrorCode.GRAPH_PROJECTION_TRUNCATED, failure.errorCode());
        assertTrue(repository.replacedDocs.isEmpty());
        assertTrue(repository.deletedKbs.isEmpty(), "截断不得触碰任何投影行");
    }

    @Test
    @DisplayName("R8/R9 多来源拆行、边来源派生、按文档 replace，重复调用计数一致")
    void graphIsSplitPerDocumentAndReplaceIsIdempotent() throws Exception {
        doc("100", "success");
        doc("101", "success");
        repository.rebuildDocuments = List.of(repository.docs.get("100"), repository.docs.get("101"));
        when(client.documentState("kb_100", "100")).thenReturn(state(LightRagClient.DocumentStatus.PROCESSED));
        when(client.documentState("kb_101", "101")).thenReturn(state(LightRagClient.DocumentStatus.PROCESSED));
        when(client.fetchGraphStrict(anyString(), anyInt(), anyInt())).thenReturn(MAPPER.readTree("""
                {"nodes":[
                  {"id":"n1","labels":["PERSON"],"properties":{
                    "entity_id":"Alice","entity_type":"PERSON",
                    "description":"第一段<SEP>第二段","file_path":"kb_100<SEP>kb_101"}},
                  {"id":"n2","labels":["PERSON"],"properties":{
                    "entity_id":"Bob","entity_type":"PERSON","description":"Bob","file_path":"kb_100"}}
                ],"edges":[
                  {"id":"e1","source":"n1","target":"n2","type":"knows",
                   "properties":{"keywords":"朋友<SEP>同事","description":"关系"}}
                ],"is_truncated":false}
                """));

        GraphProjectionService.RebuildResult first = service.rebuild("kb", null);
        GraphProjectionService.RebuildResult second = service.rebuild("kb", null);

        assertEquals(first, second);
        assertEquals(3, first.entities(), "n1 跨两文档各一行 + n2 一行");
        assertEquals(2, first.relations(), "边无 file_path，继承两端来源 → 两文档各一行");
        assertEquals(2, first.documents());
        assertEquals(2, repository.replacedDocs.size() / 2);
        assertTrue(repository.insertedRelations.stream().allMatch(row -> row.sourceFilePath().isEmpty()),
                "派生边以空 file_path 持久化，读侧据此标记 sourcesDerived");
        assertEquals(Set.of("100", "101"), Set.copyOf(repository.replacedDocs));
        assertEquals(2, repository.deletedKbs.size());
    }

    @Test
    @DisplayName("R10 kb 模式超过 max-docs 上限：400，不启动重建")
    void tooManyDocumentsIsRejected() {
        properties.getProjection().setMaxDocs(1);
        repository.rebuildDocuments = List.of(
                new GraphProjectionRepository.DocRow("d1", "kb", "A", "kb", "success", 1),
                new GraphProjectionRepository.DocRow("d2", "kb", "B", "kb", "success", 1));

        GraphOperationException failure = assertThrows(GraphOperationException.class,
                () -> service.rebuild("kb", null));

        assertEquals(GraphErrorCode.GRAPH_DOCUMENT_LIMIT_EXCEEDED, failure.errorCode());
    }

    @Test
    @DisplayName("R10 文本超过 max-chars：400")
    void tooMuchTextIsRejected() {
        properties.getProjection().setMaxChars(3);
        doc("d1", "success");

        GraphOperationException failure = assertThrows(GraphOperationException.class,
                () -> service.rebuild(null, "d1"));

        assertEquals(GraphErrorCode.GRAPH_TEXT_TOO_LARGE, failure.errorCode());
    }

    @Test
    @DisplayName("R10 文档未成功/无分块：409 GRAPH_DOCUMENT_EMPTY")
    void unsuccessfulDocumentIsEmpty() {
        GraphProjectionRepository.DocRow row = new GraphProjectionRepository.DocRow(
                "d1", "kb", "A", "kb", "running", 0);
        repository.docs.put("d1", row);
        repository.texts.put("d1", "");

        GraphOperationException failure = assertThrows(GraphOperationException.class,
                () -> service.rebuild(null, "d1"));

        assertEquals(GraphErrorCode.GRAPH_DOCUMENT_EMPTY, failure.errorCode());
    }
}
