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

package com.nageoffer.ai.ragent.runtime.exec;

import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService;
import org.ruoyi.ai.api.runtime.DocumentPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Tag;

/**
 * R01 确定性控制组：引用复核把"授权/依赖瞬时不可用"与"真实来源变化/撤权"分开表达。
 *
 * <p>历史失败 r-193656622066470b8f1601884a278216（来源最终 ACTIVE/同版本）表明旧的
 * currentCitations 把授权异常静默折叠成引用数不匹配 → SOURCE_CHANGED。修复后授权不可用
 * 必须抛 {@link RagChatExecutor.SourceCheckUnavailableException}（执行器映射为
 * AUTHORIZATION_UNAVAILABLE，仍 fail-closed），只有真实的 tombstone/版本前进/拒绝才允许
 * 剔除引用。
 */
@org.junit.jupiter.api.Tag("dev")
@Tag("dev")
class RagChatExecutorCitationRecheckTest {

    private static final String TENANT = "t1";

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static RagChatExecutor executor(DocumentPort documents,
                                            com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService resources) {
        // 仅 currentCitations 路径被触达：其余协作者给 null/替身即可（不参与该方法）。
        return new RagChatExecutor(documents,
                mock(com.nageoffer.ai.ragent.ingest.EmbeddingGateway.class),
                // D02：模型出口是 run 作用域端口（provider/model 只能来自 run 绑定的发布版本）
                mock(com.nageoffer.ai.ragent.runtime.config.RunScopedChatPort.class),
                mock(com.nageoffer.ai.ragent.runtime.usage.EgressPolicy.class),
                mock(com.nageoffer.ai.ragent.runtime.usage.UsageLedgerService.class),
                provider(null), provider(resources), provider(null),
                mock(org.springframework.jdbc.core.JdbcTemplate.class),
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private static ExecutionPrincipal principal() {
        return new ExecutionPrincipal(TENANT, "1001", "platform:t1:1001", 1, 1,
                Set.of("kb.read", "document.read"), "j", "platform", 1, 9999999999L);
    }

    private static DocumentPort.RetrievedChunk chunk(String docId, String versionId) {
        return new DocumentPort.RetrievedChunk(docId, versionId, "c-" + docId, 0, "synthetic", 1, 1, 0.5);
    }

    private static DocumentPort.DocumentRow doc(String docId, String publishedVersionId, Instant tombstonedAt) {
        return new DocumentPort.DocumentRow(docId, "kb1", "synthetic-doc", "platform:t1:1001",
                publishedVersionId, tombstonedAt, null);
    }

    @Test
    void stableSourceWithGrantKeepsEveryCitation() {
        var documents = mock(DocumentPort.class);
        var resources = mock(AiResourceAuthorizationService.class);
        when(documents.findDocument(TENANT, "doc1")).thenReturn(Optional.of(doc("doc1", "ver1", null)));
        when(resources.check(any(), eq("document.read"), eq("doc:doc1")))
                .thenReturn(ResourceAuthorizationService.Verdict.GRANT);
        var citations = executor(documents, resources).currentCitations(principal(), List.of(chunk("doc1", "ver1")));
        assertEquals(1, citations.size());
        assertEquals("doc1", citations.get(0).get("docId"));
    }

    @Test
    void authorizationServiceMissingFailsClosedAsUnavailable_notSourceChanged() {
        var documents = mock(DocumentPort.class);
        when(documents.findDocument(TENANT, "doc1")).thenReturn(Optional.of(doc("doc1", "ver1", null)));
        var executor = executor(documents, null);
        assertThrows(RagChatExecutor.SourceCheckUnavailableException.class,
                () -> executor.currentCitations(principal(), List.of(chunk("doc1", "ver1"))));
    }

    @Test
    void authorizationCheckFailureFailsClosedAsUnavailable_notSourceChanged() {
        var documents = mock(DocumentPort.class);
        var resources = mock(AiResourceAuthorizationService.class);
        when(documents.findDocument(TENANT, "doc1")).thenReturn(Optional.of(doc("doc1", "ver1", null)));
        when(resources.check(any(), eq("document.read"), eq("doc:doc1")))
                .thenThrow(new com.nageoffer.ai.ragent.framework.security.P04AiException(
                        com.nageoffer.ai.ragent.framework.security.P04AiErrorCode.AUTHORIZATION_UNAVAILABLE));
        var executor = executor(documents, resources);
        var thrown = assertThrows(RagChatExecutor.SourceCheckUnavailableException.class,
                () -> executor.currentCitations(principal(), List.of(chunk("doc1", "ver1"))));
        assertNotNull(thrown.getCause());
    }

    @Test
    void tombstonedDocumentIsDroppedAsGenuineSourceChange() {
        var documents = mock(DocumentPort.class);
        var resources = mock(AiResourceAuthorizationService.class);
        when(documents.findDocument(TENANT, "doc1"))
                .thenReturn(Optional.of(doc("doc1", "ver1", Instant.parse("2026-10-04T00:00:00Z"))));
        var citations = executor(documents, resources).currentCitations(principal(), List.of(chunk("doc1", "ver1")));
        assertTrue(citations.isEmpty());
    }

    @Test
    void supersededPublishedVersionIsDroppedAsGenuineSourceChange() {
        var documents = mock(DocumentPort.class);
        var resources = mock(AiResourceAuthorizationService.class);
        when(documents.findDocument(TENANT, "doc1")).thenReturn(Optional.of(doc("doc1", "ver2", null)));
        var citations = executor(documents, resources).currentCitations(principal(), List.of(chunk("doc1", "ver1")));
        assertTrue(citations.isEmpty());
    }

    @Test
    void currentDenialIsDroppedAsGenuineRevocation() {
        var documents = mock(DocumentPort.class);
        var resources = mock(AiResourceAuthorizationService.class);
        when(documents.findDocument(TENANT, "doc1")).thenReturn(Optional.of(doc("doc1", "ver1", null)));
        when(resources.check(any(), eq("document.read"), eq("doc:doc1")))
                .thenReturn(ResourceAuthorizationService.Verdict.DENY);
        var citations = executor(documents, resources).currentCitations(principal(), List.of(chunk("doc1", "ver1")));
        assertTrue(citations.isEmpty());
    }
}
