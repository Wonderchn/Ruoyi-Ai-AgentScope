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
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.prompt.ContextFormatter;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BD-1 · RAG 检索链（{@code rag_context_executor_*}）身份/租户显式化判据（红先行）。
 *
 * <p>真机形态（r17/p2win logs/sys-console.log 02:55:39 起）：检索链任务体在
 * rag_context_executor 池线程上 {@code ai_knowledge_base} 撞 MyBatis 租户行拦截器
 * fail-closed（{@code KbCollectionProvider.listActiveCollections:46} →
 * {@code tenant.context.missing}），子问题上下文降级为空 → eval hasKb=false。
 *
 * <p>门 fake 与 fac67 绑定测试同形：{@code require()} ＋「当前租户仅在 binder 内非空」，
 * 断言串 {@code retrieved:<gateTenant>:<principalTenant>:<membershipId>}。
 * 检索链纯读（无 LLM 出站）：capture 形（非配对 restore）——任务体内<b>不</b>授予
 * 同步起源（C2 fail-closed 语义保持），这是与意图链（重建式）的关键差别。
 */
@Tag("dev")
class RetrievalEngineIdentityBindingTest {

    private static final String QUESTION = "r17bd1 该怎么用";

    private final TenantGate gate = new TenantGate();

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
        gate.setCurrentTenant(null);
    }

    /**
     * 正例：受理线程捕获身份/租户 → 池线程任务体见捕获串；
     * 纯读链 capture 形：任务体内无同步起源、主体 scopes 恒空（身份引用）。
     * 修复前：任务体在池线程上无身份无租户 → 停门内 ⇒ 红。
     */
    @Test
    void retrievalMustBindCapturedTenantOnPoolThread() throws Exception {
        ExecutorService pool = pool();
        Observed observed = observeRetrieval();
        PrincipalContext.set(principal("T-BD1A", "910101"));

        engine(pool, observed).retrieve(List.of(new SubQuestionIntent(QUESTION, List.of())));

        assertThat(observed.serial)
                .as("池线程任务体必须绑定受理期捕获身份/租户（双门），否则检索空转（BD-1）")
                .isEqualTo("retrieved:T-BD1A:T-BD1A:platform:T-BD1A:910101");
        assertThat(observed.syncOrigin)
                .as("检索链纯读：capture 形不授同步起源（C2 fail-closed 语义保持）")
                .isFalse();
        assertThat(observed.scopes).as("身份引用恢复体 scopes 恒空集").isEmpty();
    }

    /**
     * 跨租户：T2 轮的任务体只见 T2——池线程残留的 T1 身份绝不成为本轮 DAO 键。
     */
    @Test
    void retrievalCrossTenantTaskNeverBindsForeignTenant() throws Exception {
        ExecutorService pool = pool();
        Observed observed = observeRetrieval();
        seedResidue(pool);
        PrincipalContext.set(principal("T-BD1B", "910102"));

        engine(pool, observed).retrieve(List.of(new SubQuestionIntent(QUESTION, List.of())));

        assertThat(observed.serial)
                .as("T2 轮任务体绑定的是 T2 捕获，不是池线程残留")
                .isEqualTo("retrieved:T-BD1B:T-BD1B:platform:T-BD1B:910102");
        assertThat(gate.boundTenants)
                .as("T2 轮的 DAO 租户键永不出现 T1/残留租户")
                .containsOnly("T-BD1B");
    }

    /**
     * 缺主体拒绝（负例，守 fail-closed）：受理线程干净无主体 → 直跑停门内；
     * 池线程残留拷贝（非配对 restore）不得成为本轮身份。
     */
    @Test
    void retrievalWithoutCaptureStaysFailClosed() throws Exception {
        ExecutorService pool = pool();

        Observed clean = observeRetrieval();
        PrincipalContext.clear();
        engine(pool, clean).retrieve(List.of(new SubQuestionIntent(QUESTION, List.of())));
        assertThat(clean.serial)
                .as("无主体形态：直跑停门内，不新增默认身份")
                .startsWith("failed:");
        assertThat(gate.boundTenants).isEmpty();

        Observed residueCaller = observeRetrieval();
        PrincipalContext.clear();
        PrincipalContext.restore(principal("T-RESIDUE", "920001"));
        engine(pool, residueCaller).retrieve(List.of(new SubQuestionIntent(QUESTION, List.of())));
        assertThat(residueCaller.serial)
                .as("调用线程的非配对残留不是同步起源，不得捕获为受理主体")
                .startsWith("failed:");
        assertThat(gate.boundTenants).isEmpty();
        PrincipalContext.clear();
    }

    /**
     * 线程复用隔离：捕获身份必须压过池线程残留拷贝；任务返回后线程复原到任务前状态。
     */
    @Test
    void retrievalResidueCopyMustNotBecomeIdentityAndThreadRestored() throws Exception {
        ExecutorService pool = pool();
        Observed round1 = observeRetrieval();
        seedResidue(pool);
        PrincipalContext.set(principal("T-BD1A", "910101"));

        engine(pool, round1).retrieve(List.of(new SubQuestionIntent(QUESTION, List.of())));

        assertThat(round1.serial)
                .as("捕获身份必须压过池线程残留拷贝（残留绝不成为本轮身份）")
                .isEqualTo("retrieved:T-BD1A:T-BD1A:platform:T-BD1A:910101");

        PrincipalContext.clear();
        assertThat(probePoolThread(pool))
                .as("任务返回后池线程复原：残留原样保留，不带走本轮身份，不授同步起源")
                .isEqualTo("T-RESIDUE|origin=false");
    }

    // ==================== 夹具 ====================

    private RetrievalEngine engine(ExecutorService pool, Observed observed) {
        MultiChannelRetrievalEngine multiChannel = mock(MultiChannelRetrievalEngine.class);
        when(multiChannel.retrieveKnowledgeChannels(any(SubQuestionIntent.class), any(RetrievalBudget.class)))
                .thenAnswer(invocation -> observeAtRetrieval(observed));
        var authzService = mock(com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService.class);
        // rebuild fake：身份原样返回（判定主体可用），让任务体到达被观测的检索内层
        org.mockito.Mockito.when(authzService.rebuildForAction(
                org.mockito.ArgumentMatchers.any(ExecutionPrincipal.class),
                org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
        var authorizer = com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalScopeAuthorizer.of(
                authzService,
                mock(com.nageoffer.ai.ragent.framework.security.AuthorizedRetrievalScopeResolver.class));
        return new RetrievalEngine(new SearchChannelProperties(),
                mock(ContextFormatter.class),
                mock(PromptTemplateLoader.class),
                multiChannel,
                pool,
                providerOf(gate.binder()),
                providerOf(authorizer));
    }

    private static <T> ObjectProvider<T> providerOf(T value) {
        return new ObjectProvider<>() {
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

    private Observed observeRetrieval() {
        return new Observed();
    }

    private KnowledgeRetrievalResult observeAtRetrieval(Observed observed) {
        observed.syncOrigin = PrincipalContext.isSynchronousOrigin();
        try {
            ExecutionPrincipal principal = PrincipalContext.require();
            String tenant = gate.currentTenant();
            if (tenant == null || tenant.isBlank()) {
                throw new ClientException("tenant.context.missing");
            }
            observed.serial = "retrieved:" + tenant + ":" + principal.tenantId() + ":" + principal.membershipId();
            observed.scopes = principal.scopes();
            return KnowledgeRetrievalResult.empty();
        } catch (Exception e) {
            observed.serial = "failed:" + e.getMessage();
            return KnowledgeRetrievalResult.empty();
        }
    }

    private void seedResidue(ExecutorService pool) throws Exception {
        pool.submit(() -> {
            PrincipalContext.clear();
            PrincipalContext.restore(principal("T-RESIDUE", "920001"));
        }).get(5, TimeUnit.SECONDS);
    }

    private String probePoolThread(ExecutorService pool) throws Exception {
        return pool.submit(() -> {
            ExecutionPrincipal current = PrincipalContext.get();
            return (current == null ? "none" : current.tenantId())
                    + "|origin=" + PrincipalContext.isSynchronousOrigin();
        }).get(5, TimeUnit.SECONDS);
    }

    private ExecutorService pool() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "rag-context-bd1-test");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static ExecutionPrincipal principal(String tenantId, String userId) {
        return new ExecutionPrincipal(tenantId, userId, "platform:" + tenantId + ":" + userId,
                1, 1, Set.of(), "bd1-chain-test", "test", 1, Long.MAX_VALUE);
    }

    private static final class Observed {
        volatile String serial = "not-invoked";
        volatile boolean syncOrigin;
        volatile Set<String> scopes;
    }

    /**
     * 租户门 fake（TenantHelper.dynamic 的等价镜像），与既有 binding 测试同形。
     */
    private static final class TenantGate {

        private final ThreadLocal<String> current = new ThreadLocal<>();
        private final List<String> boundTenants = new CopyOnWriteArrayList<>();

        private String currentTenant() {
            return current.get();
        }

        private void setCurrentTenant(String tenantId) {
            if (tenantId == null) {
                current.remove();
            } else {
                current.set(tenantId);
            }
        }

        private TenantScopeBinder binder() {
            return new TenantScopeBinder() {
                @Override
                public void runWithTenant(String tenantId, Runnable handle) {
                    boundTenants.add(tenantId);
                    String previous = current.get();
                    current.set(tenantId);
                    try {
                        handle.run();
                    } finally {
                        if (previous == null) {
                            current.remove();
                        } else {
                            current.set(previous);
                        }
                    }
                }
            };
        }
    }
}
