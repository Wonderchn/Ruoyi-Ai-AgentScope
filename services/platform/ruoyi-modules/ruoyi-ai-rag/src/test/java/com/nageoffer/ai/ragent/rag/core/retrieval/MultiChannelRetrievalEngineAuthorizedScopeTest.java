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

package com.nageoffer.ai.ragent.rag.core.retrieval;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.AuthorizedResourceScope;
import com.nageoffer.ai.ragent.framework.security.AuthorizedRetrievalScopeResolver;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.RetrievalScope;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.RetrievalScopeResolver;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BD-2 · 多通道检索引擎授权作用域接线判据（红先行）。
 *
 * <p>真机形态（r17/bd1 窗 2026-10-14）：{@code SearchContext.authorizedScope} 在 main 全史无生产者，
 * {@code VectorSearchChannel} 对空（=denied）作用域在 embedding 前交空卷（防「空授权→查全库」回落，
 * 行为本身正确）⇒ 检索链向量通道结构性不可达。维护者裁决（2026-10-14）：接入既有授权器——
 * 由可信主体、在线权限和候选资源生成检索作用域；区分动作 {@code kb.retrieve} 与权限串
 * {@code ai:kb:retrieve}；保留空授权拒绝。
 *
 * <p>判据四类（维护者明令）：检索正例（授权作用域流入 SearchContext）、无权限（denied 保持在门内）、
 * 跨租户（授权器看到的就是当前线程主体）、撤权在线性（第二次调用看到收紧后的事实，无缓存）。
 * 门 fake 与家庭同形：{@link RetrievalScopeAuthorizer#of} ＋ mock 的授权服务/投影器。
 */
@Tag("dev")
class MultiChannelRetrievalEngineAuthorizedScopeTest {

    private final List<SearchContext> captured = new ArrayList<>();
    private final List<String> seenActions = new CopyOnWriteArrayList<>();
    private final List<String> seenTenants = new CopyOnWriteArrayList<>();
    private final AtomicInteger resolveCalls = new AtomicInteger();

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    /**
     * 检索正例：有主体、有 kb.retrieve 授权 → 授权作用域（含已授权 collection）流入 SearchContext。
     * 修复前：authorizedScope 无生产者 → 通道拿到的恒为 denied ⇒ 红。
     */
    @Test
    void authorizedScopeMustFlowIntoSearchContext() {
        ExecutionPrincipal principal = principal("T-BD2A", "910101");
        PrincipalContext.set(principal);
        SearchChannel channel = capturingChannel();
        RetrievalScopeAuthorizer authorizer = authorizer(List.of("kb:bd2a"), Set.of("coll-bd2a"));

        engine(channel, authorizer).retrieveKnowledgeChannels(
                new SubQuestionIntent("r17bd2 该怎么用", List.of()), new RetrievalBudget(3, 3, 3));

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).getAuthorizedScope().isEmpty())
                .as("授权作用域必须流入 SearchContext（P1.3b 装配点接线，BD-2）")
                .isFalse();
        assertThat(captured.get(0).getAuthorizedScope().authorizedCollections())
                .as("已授权 collection 来自在线投影（P2 published chunk 库）")
                .containsExactly("coll-bd2a");
    }

    /**
     * 无权限负例：授权服务返回空授权集 → 投影 denied → 通道拿到空作用域（空授权拒绝语义保留）。
     */
    @Test
    void noPermissionStaysDenied() {
        ExecutionPrincipal principal = principal("T-BD2A", "910101");
        PrincipalContext.set(principal);
        SearchChannel channel = capturingChannel();
        RetrievalScopeAuthorizer authorizer = authorizer(List.of(), Set.of());

        engine(channel, authorizer).retrieveKnowledgeChannels(
                new SubQuestionIntent("r17bd2 该怎么用", List.of()), new RetrievalBudget(3, 3, 3));

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).getAuthorizedScope().isEmpty())
                .as("无权限：空授权拒绝，绝不回落全库")
                .isTrue();
    }

    /**
     * 跨租户：授权器看到的就是当前线程主体（T2）——作用域按 T2 的在线事实解析，
     * 绝不见 T1 的库；且传入授权服务的是<b>动作名</b> kb.retrieve（权限串映射归授权服务内部）。
     */
    @Test
    void crossTenantPrincipalAndActionNameReachAuthorizer() {
        PrincipalContext.set(principal("T-BD2B", "910102"));
        SearchChannel channel = capturingChannel();
        RetrievalScopeAuthorizer authorizer = authorizer(List.of("kb:bd2b"), Set.of("coll-bd2b"));

        engine(channel, authorizer).retrieveKnowledgeChannels(
                new SubQuestionIntent("r17bd2 该怎么用", List.of()), new RetrievalBudget(3, 3, 3));

        assertThat(seenTenants)
                .as("授权器按当前线程主体解析（T2），不读池化残留")
                .containsExactly("T-BD2B");
        assertThat(seenActions)
                .as("传入授权服务的是动作名 kb.retrieve（RetrievalScopeAuthorizer.ACTION_KB_RETRIEVE），"
                    + "不是权限串 ai:kb:retrieve——动作→权限映射归授权服务内部")
                .containsExactly("kb.retrieve");
    }

    /**
     * 撤权在线性：同一主体两次检索，授权事实在两次调用之间收紧（撤权/epoch bump）→
     * 第二次调用拿到 denied——授权作用域每次在线解析，绝不缓存。
     */
    @Test
    void revocationIsSeenOnNextCallOnline() {
        ExecutionPrincipal principal = principal("T-BD2A", "910101");
        PrincipalContext.set(principal);
        SearchChannel channel = capturingChannel();
        RetrievalScopeAuthorizer authorizer = authorizer(List.of("kb:bd2a"), Set.of("coll-bd2a"));

        var engine = engine(channel, authorizer);
        engine.retrieveKnowledgeChannels(new SubQuestionIntent("q1", List.of()), new RetrievalBudget(3, 3, 3));
        // 撤权发生（fake：第一次调用之后授权集清空）
        engine.retrieveKnowledgeChannels(new SubQuestionIntent("q2", List.of()), new RetrievalBudget(3, 3, 3));

        assertThat(captured).hasSize(2);
        assertThat(captured.get(0).getAuthorizedScope().isEmpty())
                .as("撤权前：授权作用域在门内")
                .isFalse();
        assertThat(captured.get(1).getAuthorizedScope().isEmpty())
                .as("撤权后：第二次调用在线解析到收紧后的事实（无缓存）")
                .isTrue();
    }

    // ==================== 夹具 ====================

    private SearchChannel capturingChannel() {
        SearchChannel channel = mock(SearchChannel.class);
        when(channel.getName()).thenReturn("vector");
        when(channel.getType()).thenReturn(SearchChannelType.VECTOR);
        when(channel.isEnabled(any(SearchContext.class))).thenReturn(true);
        when(channel.search(any(SearchContext.class))).thenAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.VECTOR)
                    .channelName("vector")
                    .chunks(List.of())
                    .latencyMs(1L)
                    .build();
        });
        return channel;
    }

    /**
     * 授权器 fake（真 RetrievalScopeAuthorizer.of ＋ mock 授权服务/投影器）：
     * 第一次 resolveScope 返回 grantedRefs，之后（模拟撤权）返回空集；
     * 投影器把非空授权集映成带 collections 的检索作用域、空集映成 denied。
     */
    private RetrievalScopeAuthorizer authorizer(List<String> grantedRefs, Set<String> collections) {
        ResourceAuthorizationService service = mock(ResourceAuthorizationService.class);
        when(service.resolveScope(any(ExecutionPrincipal.class), anyString(), anyCollection()))
                .thenAnswer(invocation -> {
                    ExecutionPrincipal principal = invocation.getArgument(0);
                    seenActions.add(invocation.getArgument(1));
                    seenTenants.add(principal == null ? null : principal.tenantId());
                    List<String> refs = resolveCalls.incrementAndGet() == 1 ? grantedRefs : List.of();
                    return AuthorizedResourceScope.granted(principal, invocation.getArgument(1),
                            refs, 1_000L);
                });
        AuthorizedRetrievalScopeResolver<AuthorizedRetrievalScope> resolver =
                mock(AuthorizedRetrievalScopeResolver.class);
        Mockito.when(resolver.toRetrievalScope(any(AuthorizedResourceScope.class))).thenAnswer(invocation -> {
            AuthorizedResourceScope scope = invocation.getArgument(0);
            if (scope.authorizedRefs().isEmpty()) {
                return AuthorizedRetrievalScope.denied();
            }
            return AuthorizedRetrievalScope.of(scope, scope.authorizedRefs(), List.of(), List.of(), collections);
        });
        return RetrievalScopeAuthorizer.of(service, resolver);
    }

    private MultiChannelRetrievalEngine engine(SearchChannel channel, RetrievalScopeAuthorizer authorizer) {
        RetrievalScopeResolver scopeResolver = mock(RetrievalScopeResolver.class);
        when(scopeResolver.resolve(anyList()))
                .thenReturn(RetrievalScope.global(0.0, List.of("coll-bd2a")));
        return new MultiChannelRetrievalEngine(
                List.of(channel), List.of(), scopeResolver, providerOf(authorizer), Runnable::run,
                new SearchChannelProperties());
    }

    private static <T> org.springframework.beans.factory.ObjectProvider<T> providerOf(T value) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }

    private static ExecutionPrincipal principal(String tenantId, String userId) {
        return new ExecutionPrincipal(tenantId, userId, "platform:" + tenantId + ":" + userId,
                1, 1, Set.of(), "bd2-scope-test", "test", 1, Long.MAX_VALUE);
    }
}
