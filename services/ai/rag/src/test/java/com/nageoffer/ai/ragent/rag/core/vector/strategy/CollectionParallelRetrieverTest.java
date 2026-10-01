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

package com.nageoffer.ai.ragent.rag.core.vector.strategy;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.security.AuthorizedResourceScope;
import com.nageoffer.ai.ragent.rag.core.retrieval.AuthorizedRetrievalScope;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.vector.VectorRetrieverService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollectionParallelRetrieverTest {

    @Test
    @DisplayName("fan-out全库检索时同一个子问题只生成一次Query向量")
    void fanOutGlobalRetrievalEmbedSubQuestionOnce() {
        VectorRetrieverService retrieverService = mock(VectorRetrieverService.class);
        when(retrieverService.embedAndNormalize(any(AuthorizedRetrievalScope.class), eq("报销流程")))
                .thenReturn(new float[]{0.6F, 0.8F});
        when(retrieverService.retrieveByVector(any(AuthorizedRetrievalScope.class),
                any(float[].class), any(RetrieveRequest.class))).thenReturn(List.of());

        CollectionParallelRetriever retriever = new CollectionParallelRetriever(retrieverService, Runnable::run);
        retriever.executeParallelRetrieval(grantedScope(), "报销流程", List.of("kb-finance", "kb-policy"), 7);

        verify(retrieverService, times(1)).embedAndNormalize(any(AuthorizedRetrievalScope.class), eq("报销流程"));
        verify(retrieverService, times(2)).retrieveByVector(any(AuthorizedRetrievalScope.class),
                any(float[].class), any(RetrieveRequest.class));
    }

    /**
     * 构造生产语义的授权作用域：本用例的两个逻辑库都在授权集合内（空集合会被判为空授权而直接短路）
     */
    private static AuthorizedRetrievalScope grantedScope() {
        List<String> collections = List.of("kb-finance", "kb-policy");
        ExecutionPrincipal principal = new ExecutionPrincipal("tenant-1", "1001",
                ExecutionPrincipal.canonicalMembershipId("tenant-1", "1001"),
                1, 1, Set.of(), "jti-test-1", "test-issuer", 0L, 0L);
        AuthorizedResourceScope resourceScope = AuthorizedResourceScope.granted(
                principal, "kb.retrieve", collections, 0L);
        return AuthorizedRetrievalScope.of(resourceScope, collections, List.of(), List.of(), collections);
    }
}
