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

import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.AuthorizedResourceScope;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService.Verdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class GraphScopeResolverTest {

    private AiResourceAuthorizationService authorization;
    private FakeGraphProjectionRepository repository;
    private GraphScopeResolver resolver;
    private ExecutionPrincipal principal;

    @BeforeEach
    void setUp() {
        authorization = mock(AiResourceAuthorizationService.class);
        repository = new FakeGraphProjectionRepository();
        resolver = new GraphScopeResolver(repository, authorization);
        principal = new ExecutionPrincipal("T1", "2101", "platform:T1:2101", 1, 1,
                Set.of("kb.read", "kb.write", "config.read"), "jti-1", "platform", 1, 9_999_999_999L);
        PrincipalContext.set(principal);
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    private void grantKbs(String... kbIds) {
        when(authorization.resolveScope(any(), eq("kb.read"), any()))
                .thenAnswer(call -> AuthorizedResourceScope.granted(principal, "kb.read",
                        java.util.Arrays.stream(kbIds).map(id -> "kb:" + id).toList(), 1L));
    }

    @Test
    @DisplayName("无显式范围：只取授权 KB 集合，绝不回落全库")
    void withoutExplicitScopeUsesAuthorizedKbs() {
        grantKbs("kb-a", "kb-b");

        GraphScopeResolver.GraphScope scope = resolver.resolve(null, null, null);

        assertEquals(Set.of("kb-a", "kb-b"), scope.kbIds());
        assertEquals(null, scope.docId());
        verify(authorization).resolveScope(principal, "kb.read", List.of());
    }

    @Test
    @DisplayName("空授权集合：返回空范围而不是全库")
    void emptyGrantedScopeStaysEmpty() {
        when(authorization.resolveScope(any(), eq("kb.read"), any()))
                .thenAnswer(call -> AuthorizedResourceScope.empty(principal, "kb.read", 1L));

        GraphScopeResolver.GraphScope scope = resolver.resolve(null, null, null);

        assertTrue(scope.kbIds().isEmpty());
        assertTrue(scope.isEmpty());
    }

    @Test
    @DisplayName("跨租户/未授权 kbId：统一 404，且不查询主库泄露存在性")
    void unauthorizedKbIdIsNotFound() {
        grantKbs("kb-a");
        repository.kbs.put("kb-b", new GraphProjectionRepository.KbRow("kb-b", "B", "kb_b"));

        GraphOperationException failure = assertThrows(GraphOperationException.class,
                () -> resolver.resolve("kb-b", null, null));

        assertEquals(GraphErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, failure.errorCode());
        assertTrue(repository.calls.stream().noneMatch(call -> call.startsWith("kb:T1:kb-b")));
    }

    @Test
    @DisplayName("同名 collection 跨租户：主库 0 行即 404（collection 不是授权边界）")
    void unknownCollectionIsNotFound() {
        grantKbs("kb-a");

        GraphOperationException failure = assertThrows(GraphOperationException.class,
                () -> resolver.resolve(null, "shared-name", null));

        assertEquals(GraphErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, failure.errorCode());
    }

    @Test
    @DisplayName("collection 命中多行（非法重复）同样 404，不猜一个")
    void duplicateCollectionIsNotFound() {
        grantKbs("kb-a", "kb-b");
        repository.kbs.put("kb-a", new GraphProjectionRepository.KbRow("kb-a", "A", "dup"));
        repository.kbs.put("kb-b", new GraphProjectionRepository.KbRow("kb-b", "B", "dup"));

        GraphOperationException failure = assertThrows(GraphOperationException.class,
                () -> resolver.resolve(null, "dup", null));

        assertEquals(GraphErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, failure.errorCode());
    }

    @Test
    @DisplayName("collection 命中唯一行：收窄到该 KB")
    void collectionNarrowsToItsKb() {
        grantKbs("kb-a", "kb-b");
        repository.kbs.put("kb-b", new GraphProjectionRepository.KbRow("kb-b", "B", "kb_b"));

        GraphScopeResolver.GraphScope scope = resolver.resolve(null, "kb_b", null);

        assertEquals(Set.of("kb-b"), scope.kbIds());
    }

    @Test
    @DisplayName("doc 过滤必须同时过 KB 授权与 doc 级 grant")
    void docFilterRequiresDocGrant() {
        grantKbs("kb-a");
        repository.docs.put("doc-1", new GraphProjectionRepository.DocRow(
                "doc-1", "kb-a", "文档", "kb_a", "success", 3));
        when(authorization.check(principal, "kb.read", "doc:doc-1")).thenReturn(Verdict.GRANT);

        GraphScopeResolver.GraphScope scope = resolver.resolve(null, null, "doc-1");

        assertEquals(Set.of("kb-a"), scope.kbIds());
        assertEquals("doc-1", scope.docId());
        verify(authorization).check(principal, "kb.read", "doc:doc-1");
    }

    @Test
    @DisplayName("doc 级 grant 为 DENY：404，不因 KB 已授权而放行")
    void docWithoutGrantIsNotFound() {
        grantKbs("kb-a");
        repository.docs.put("doc-1", new GraphProjectionRepository.DocRow(
                "doc-1", "kb-a", "文档", "kb_a", "success", 3));
        when(authorization.check(principal, "kb.read", "doc:doc-1")).thenReturn(Verdict.DENY);

        GraphOperationException failure = assertThrows(GraphOperationException.class,
                () -> resolver.resolve(null, null, "doc-1"));

        assertEquals(GraphErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, failure.errorCode());
    }

    @Test
    @DisplayName("doc 不存在于当前租户：404（跨租户同形）")
    void foreignDocIsNotFound() {
        grantKbs("kb-a");

        GraphOperationException failure = assertThrows(GraphOperationException.class,
                () -> resolver.resolve(null, null, "doc-foreign"));

        assertEquals(GraphErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, failure.errorCode());
        verify(authorization, never()).check(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("功能级拒绝在资源解析前生效")
    void functionDenialPropagates() {
        doThrow(new P04AiException(P04AiErrorCode.FORBIDDEN))
                .when(authorization).requireFunction(principal, "kb.read", "tenant:graph");

        assertThrows(P04AiException.class, () -> resolver.resolve(null, null, null));
    }
}
