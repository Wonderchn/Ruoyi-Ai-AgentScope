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

package com.nageoffer.ai.ragent.rag.core.intent;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.runtime.scope.RetrievalEgressSubjectFacts;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BD-1 · 意图分类链（{@code intent_classify_executor_*}）身份/租户显式化判据（红先行）。
 *
 * <p>真机形态（r17/p2win logs/sys-console.log 02:55:39 起）：eval 面
 * （{@code GET /internal/ai/v1/rag/eval} → {@link IntentResolver#resolve}）在请求线程
 * {@code supplyAsync} 到 intent_classify_executor 池线程，任务体里
 * {@code ai_intent_node} 撞 MyBatis 租户行拦截器 fail-closed
 * （{@code tenant.context.missing}），意图分类恒降级为空意图。
 *
 * <p>门 fake 与 fac67 绑定测试同形：{@code require()} ＋「当前租户仅在 binder 内非空」，
 * 断言串 {@code classified:<gateTenant>:<principalTenant>:<membershipId>}。
 * 修复前本类必须红（任务体无绑定）、修复后绿（重绑＋线程复原）。
 * 意图分类含 LLM 出站：任务体按重建式授予同步起源（flush 同形），断言 origin/scopes/jti。
 */
@Tag("dev")
class IntentResolverIdentityBindingTest {

    private static final String QUESTION = "r17bd1 该怎么用";

    private final TenantGate gate = new TenantGate();

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
        gate.setCurrentTenant(null);
    }

    /**
     * 正例：受理线程（同步起源）捕获身份/租户 → 池线程任务体见捕获串；
     * 意图分类含 LLM 出站 → 重建式（origin 授予、scopes 恰为声明集、合成 jti）。
     * 修复前：任务体在池线程上无身份无租户 → 停门内 ⇒ 红。
     */
    @Test
    void intentClassifyMustBindCapturedIdentityOnPoolThread() throws Exception {
        ExecutorService pool = pool();
        Observed observed = observeClassify();
        PrincipalContext.set(principal("T-BD1A", "910101"));

        List<SubQuestionIntent> result = resolver(pool, observed)
                .resolve(new RewriteResult(QUESTION, List.of(QUESTION)));

        assertThat(observed.serial)
                .as("池线程任务体必须绑定受理期捕获身份/租户（双门），否则意图分类恒降级（BD-1）")
                .isEqualTo("classified:T-BD1A:T-BD1A:platform:T-BD1A:910101");
        assertThat(observed.syncOrigin)
                .as("意图分类含 LLM 出站：重建式在任务体内授予同步起源（flush 同形）")
                .isTrue();
        assertThat(observed.scopes).as("重建主体 scopes 恰为 canonical action 名（出站门消费动作名）").containsExactly("kb.retrieve");
        assertThat(observed.issuer).as("重建主体 issuer 固定为 platform").isEqualTo("platform");
        assertThat(observed.jti).as("重建主体 jti 为合成标记，不可当 bearer 重放").startsWith("retrieval-egress:");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).nodeScores()).as("意图分类不再降级为空").isNotEmpty();
    }

    /**
     * 跨租户：T2 轮的任务体只见 T2——池线程残留的 T1 身份绝不成为本轮 DAO 键。
     */
    @Test
    void intentClassifyCrossTenantTaskNeverBindsForeignTenant() throws Exception {
        ExecutorService pool = pool();
        Observed observed = observeClassify();
        seedResidue(pool);
        PrincipalContext.set(principal("T-BD1B", "910102"));

        resolver(pool, observed).resolve(new RewriteResult(QUESTION, List.of(QUESTION)));

        assertThat(observed.serial)
                .as("T2 轮任务体绑定的是 T2 捕获，不是池线程残留")
                .isEqualTo("classified:T-BD1B:T-BD1B:platform:T-BD1B:910102");
        assertThat(gate.boundTenants)
                .as("T2 轮的 DAO 租户键永不出现 T1/残留租户")
                .containsOnly("T-BD1B");
    }

    /**
     * 缺主体拒绝（负例，守 fail-closed）：
     * 3a 受理线程干净无主体、3b 受理线程只有非配对残留拷贝——两种「无捕获形态」
     * 都按原形态直跑停在门内，不凭空补身份、不认残留为受理主体。
     */
    @Test
    void intentClassifyWithoutCaptureStaysFailClosed() throws Exception {
        ExecutorService pool = pool();

        Observed clean = observeClassify();
        PrincipalContext.clear();
        resolver(pool, clean).resolve(new RewriteResult(QUESTION, List.of(QUESTION)));
        assertThat(clean.serial)
                .as("无主体形态：直跑停门内，不新增默认身份")
                .startsWith("failed:");
        assertThat(gate.boundTenants).isEmpty();

        Observed residueCaller = observeClassify();
        PrincipalContext.clear();
        PrincipalContext.restore(principal("T-RESIDUE", "920001"));
        resolver(pool, residueCaller).resolve(new RewriteResult(QUESTION, List.of(QUESTION)));
        assertThat(residueCaller.serial)
                .as("调用线程的非配对残留不是同步起源，不得捕获为受理主体")
                .startsWith("failed:");
        assertThat(gate.boundTenants).isEmpty();
        PrincipalContext.clear();
    }

    /**
     * 线程复用隔离：捕获身份必须压过池线程残留拷贝；任务返回后线程复原到任务前状态
     * （残留原样保留、不带走本轮身份、不授同步起源）。
     */
    @Test
    void intentClassifyResidueCopyMustNotBecomeIdentityAndThreadRestored() throws Exception {
        ExecutorService pool = pool();
        Observed round1 = observeClassify();
        seedResidue(pool);
        PrincipalContext.set(principal("T-BD1A", "910101"));

        resolver(pool, round1).resolve(new RewriteResult(QUESTION, List.of(QUESTION)));

        assertThat(round1.serial)
                .as("捕获身份必须压过池线程残留拷贝（残留绝不成为本轮身份）")
                .isEqualTo("classified:T-BD1A:T-BD1A:platform:T-BD1A:910101");

        PrincipalContext.clear();
        assertThat(probePoolThread(pool))
                .as("任务返回后池线程复原：残留原样保留，不带走本轮身份，不授同步起源")
                .isEqualTo("T-RESIDUE|origin=false");
    }

    // ==================== 夹具 ====================

    private IntentResolver resolver(ExecutorService pool, Observed observed) {
        IntentClassifier classifier = mock(IntentClassifier.class);
        when(classifier.classifyTargets(anyString()))
                .thenAnswer(invocation -> observeAtClassify(observed));
        return new IntentResolver(classifier, pool, providerOf(gate.binder()), providerOf(facts()));
    }

    /**
     * 出站事实 fake：租户 ENABLED、成员当前启用且持有声明权限、ACL 版本 1——
     * 与真机嵌入实现（PlatformIdentitySource＋currentAclVersion）回答的形状一致。
     */
    private RetrievalEgressSubjectFacts facts() {
        return new RetrievalEgressSubjectFacts() {
            @Override
            public TenantState tenantState(String tenantId) {
                return TenantState.ENABLED;
            }

            @Override
            public CurrentSubject currentSubject(String tenantId, String userId, String membershipId) {
                return new CurrentSubject(tenantId, userId, "platform:" + tenantId + ":" + userId,
                        true, Set.of("ai:kb:retrieve"), 1);
            }

            @Override
            public int currentAclVersion(String tenantId) {
                return 1;
            }

            @Override
            public java.util.Set<String> canonicalActionsOf(java.util.Collection<String> permissions) {
                // 白名单精确反查（镜像嵌入适配器）：ai:kb:retrieve -> kb.retrieve
                if (permissions.size() == 1 && permissions.contains("ai:kb:retrieve")) {
                    return java.util.Set.of("kb.retrieve");
                }
                throw new com.nageoffer.ai.ragent.framework.exception.ClientException("no mapping");
            }
        };
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

    private Observed observeClassify() {
        return new Observed();
    }

    private List<NodeScore> observeAtClassify(Observed observed) {
        observed.syncOrigin = PrincipalContext.isSynchronousOrigin();
        try {
            ExecutionPrincipal principal = PrincipalContext.require();
            String tenant = gate.currentTenant();
            if (tenant == null || tenant.isBlank()) {
                throw new ClientException("tenant.context.missing");
            }
            observed.serial = "classified:" + tenant + ":" + principal.tenantId() + ":" + principal.membershipId();
            observed.scopes = principal.scopes();
            observed.issuer = principal.issuer();
            observed.jti = principal.jti();
            return List.of(score());
        } catch (Exception e) {
            observed.serial = "failed:" + e.getMessage();
            return List.of();
        }
    }

    private NodeScore score() {
        return NodeScore.builder()
                .node(IntentNode.builder().id("KB_A").build())
                .score(0.9)
                .build();
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
            Thread thread = new Thread(r, "intent-classify-bd1-test");
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
        volatile String issuer;
        volatile String jti;
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
