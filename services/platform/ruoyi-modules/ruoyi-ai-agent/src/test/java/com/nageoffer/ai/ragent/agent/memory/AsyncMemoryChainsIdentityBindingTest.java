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

package com.nageoffer.ai.ragent.agent.memory;

import com.nageoffer.ai.ragent.agent.config.ReActAgentProvider;
import com.nageoffer.ai.ragent.agent.config.ReActAgentProvider.ActiveAgent;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunGate;
import com.nageoffer.ai.ragent.agent.service.impl.AgentChatServiceImpl;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.ResolvedCatalog;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.message.Msg;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F16 op1 管线修复 · 两条链的端到端身份/租户显式化判据（P3 §2/§5，红先行）。
 *
 * <p>背景（真机 r17/f10p1-win/app-6044.log）：长期记忆的「抽取」（轮次收尾后
 * {@code scheduleMemoryExtraction}）与「注入」（{@code AgentUserMemoryMiddleware}
 * 首轮读库）两条链都把阻塞 JDBC 调度到共享 {@code boundedElastic} 上。跳变之后
 * 池线程的主体是创建期继承的<b>残留拷贝</b>（可能属于别的用户）、动态租户为空——
 * 落库链先吃 {@code PrincipalContext.require()}、再吃 MyBatis 租户行拦截器：
 * 抽取侧整批失败（tenant.context.missing），注入侧静默退空快照。
 *
 * <p>本判据的门 fake 与真链同形：{@code require()} ＋「当前租户仅在 binder 内非空」，
 * 断言串 {@code persisted:<tenant>:<principalTenant>:<membershipId>}——
 * 只绑身份／只绑租户都红，双门才绿。释放钩子/订阅点的线程环境被清空，
 * 任何"读环境身份"的实现都会停在身份门或租户门。
 */
@Tag("dev")
class AsyncMemoryChainsIdentityBindingTest {

    private static final String CONVERSATION_ID = "c-f10p3";

    private final TenantGate gate = new TenantGate();

    private ReActAgentProvider agentProvider;
    private AgentConversationService conversationService;
    private StreamTaskManager taskManager;
    private AgentRunGate runGate;
    private ReActAgent agent;

    @BeforeEach
    void setUp() {
        agentProvider = mock(ReActAgentProvider.class);
        conversationService = mock(AgentConversationService.class);
        taskManager = mock(StreamTaskManager.class);
        runGate = mock(AgentRunGate.class);
        agent = mock(ReActAgent.class);
        when(runGate.acquire(anyString(), anyString(), anyString())).thenReturn(() -> {
        });
        when(agentProvider.getAgent()).thenReturn(new ActiveAgent(
                agent, new ResolvedCatalog("知识库工具描述", null, List.of(), List.of(), List.of())));
        when(conversationService.touchConversation(anyString(), anyString(), anyString())).thenReturn("会话标题");
        when(conversationService.addUserMessage(anyString(), anyString(), anyString())).thenReturn("m-3003");
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
        UserContext.clear();
        gate.setCurrentTenant(null);
    }

    // ==================== 链一：抽取（AgentChatServiceImpl 收尾调度） ====================

    /**
     * 释放钩子在"流终止线程"形态（无主体、无租户）上触发；抽取任务体在共享池上执行。
     * 任务体必须看到受理期捕获的身份与租户——环境里两样都没有，任何绕开捕获的实现都红。
     */
    @Test
    void backgroundExtractionMustPersistUnderCapturedIdentityAndTenant() throws Exception {
        CountDownLatch observed = new CountDownLatch(1);
        AtomicReference<String> persisted = new AtomicReference<>();
        AgentMemoryPipeline pipeline = mock(AgentMemoryPipeline.class);
        when(pipeline.extract(anyString(), anyString(), any())).thenAnswer(invocation -> {
            persisted.set(observeAtWritePoint());
            observed.countDown();
            return new AgentMemoryOutcome(AgentMemoryOutcome.Status.BELOW_THRESHOLD, 0, 1, false);
        });
        AgentChatServiceImpl service = newService(pipeline);
        login("910101");
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.never());

        setPrincipal("T-F10P3A", "910101");
        service.streamChat("问题", CONVERSATION_ID, new SseEmitter());
        ArgumentCaptor<Runnable> finalizer = ArgumentCaptor.forClass(Runnable.class);
        verify(taskManager).register(anyString(), anyString(), finalizer.capture());

        // 模拟流终止/取消收尾线程：无主体、无租户（池化残留不得充当可信身份）
        PrincipalContext.clear();
        gate.setCurrentTenant(null);
        finalizer.getValue().run();

        assertThat(observed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(persisted.get())
                .as("抽取任务体必须看到受理期捕获的身份与租户（双门），否则真机 tenant.context.missing")
                .isEqualTo("persisted:T-F10P3A:T-F10P3A:platform:T-F10P3A:910101");
    }

    /**
     * 两个用户、两条流先后受理（各自捕获），收尾交错触发：
     * 每个抽取任务必须带<b>本轮</b>捕获的身份——静态/共享单一捕获会被后受理者顶掉。
     */
    @Test
    void interleavedRunsKeepEachRunsCapturedIdentity() throws Exception {
        CountDownLatch observed = new CountDownLatch(2);
        Map<String, String> persisted = new ConcurrentHashMap<>();
        AgentMemoryPipeline pipeline = mock(AgentMemoryPipeline.class);
        when(pipeline.extract(anyString(), anyString(), any())).thenAnswer(invocation -> {
            String userId = invocation.getArgument(0);
            persisted.put(userId, observeAtWritePoint());
            observed.countDown();
            return new AgentMemoryOutcome(AgentMemoryOutcome.Status.BELOW_THRESHOLD, 0, 1, false);
        });
        AgentChatServiceImpl service = newService(pipeline);
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.never());
        ArgumentCaptor<Runnable> finalizers = ArgumentCaptor.forClass(Runnable.class);

        login("910101");
        setPrincipal("T-F10P3A", "910101");
        service.streamChat("问题A", "c-a", new SseEmitter());
        login("910102");
        setPrincipal("T-F10P3B", "910102");
        service.streamChat("问题B", "c-b", new SseEmitter());
        verify(taskManager, times(2)).register(anyString(), anyString(), finalizers.capture());

        PrincipalContext.clear();
        gate.setCurrentTenant(null);
        finalizers.getAllValues().get(0).run();
        finalizers.getAllValues().get(1).run();

        assertThat(observed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(persisted.get("910101"))
                .as("A 的抽取只能带 A 的捕获身份")
                .isEqualTo("persisted:T-F10P3A:T-F10P3A:platform:T-F10P3A:910101");
        assertThat(persisted.get("910102"))
                .as("B 的抽取只能带 B 的捕获身份")
                .isEqualTo("persisted:T-F10P3B:T-F10P3B:platform:T-F10P3B:910102");
    }

    /**
     * 无主体形态（受理期就没有可信主体）：直跑、不新增默认身份、不因捕获为空抛错。
     */
    @Test
    void extractionWithoutTrustedSubjectRunsDirectlyWithoutInventedIdentity() throws Exception {
        CountDownLatch observed = new CountDownLatch(1);
        AtomicReference<String> persisted = new AtomicReference<>();
        AgentMemoryPipeline pipeline = mock(AgentMemoryPipeline.class);
        when(pipeline.extract(anyString(), anyString(), any())).thenAnswer(invocation -> {
            persisted.set(observeAtWritePoint());
            observed.countDown();
            return new AgentMemoryOutcome(AgentMemoryOutcome.Status.BELOW_THRESHOLD, 0, 1, false);
        });
        AgentChatServiceImpl service = newService(pipeline);
        login("910103");
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.never());

        PrincipalContext.clear();
        service.streamChat("问题", CONVERSATION_ID, new SseEmitter());
        ArgumentCaptor<Runnable> finalizer = ArgumentCaptor.forClass(Runnable.class);
        verify(taskManager).register(anyString(), anyString(), finalizer.capture());
        finalizer.getValue().run();

        assertThat(observed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(persisted.get())
                .as("无捕获时直跑并停在身份门，不许凭空补身份")
                .startsWith("failed:");
    }

    // ==================== 链二：注入（AgentUserMemoryMiddleware 首轮读库） ====================

    /**
     * 订阅发生在请求线程（launchStream 同步订阅＝捕获点），读库跳共享池。
     * 读库任务体必须看到捕获的身份与租户，否则静默降级为空快照。
     */
    @Test
    void firstRoundMemoryLoadMustRunUnderCapturedIdentityAndTenant() throws Exception {
        CountDownLatch observed = new CountDownLatch(1);
        AtomicReference<String> persisted = new AtomicReference<>();
        AgentMemoryRepository repository = mock(AgentMemoryRepository.class);
        when(repository.loadSnapshot(anyString())).thenAnswer(invocation -> {
            persisted.set(observeAtWritePoint());
            observed.countDown();
            return AgentMemorySnapshot.empty();
        });
        AgentUserMemoryMiddleware middleware = new AgentUserMemoryMiddleware(repository, binderProvider(gate.binder()));

        setPrincipal("T-F10P3C", "910103");
        RuntimeContext runtimeContext = RuntimeContext.builder().userId("910103").sessionId("c-inject").build();
        middleware.onReasoning(null, runtimeContext, emptyReasoningInput(), reasoningInput -> Flux.empty())
                .blockLast();

        PrincipalContext.clear();
        gate.setCurrentTenant(null);
        assertThat(observed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(persisted.get())
                .as("注入链读库必须看到捕获的身份与租户，否则读失败静默退空快照")
                .isEqualTo("persisted:T-F10P3C:T-F10P3C:platform:T-F10P3C:910103");
    }

    /**
     * 订阅线程上只有非同步起源的身份持有（restore 拷贝）或什么都没有时：
     * 绝不把池化残留/线程环境身份当成本轮身份，直跑不新增默认身份。
     */
    @Test
    void firstRoundMemoryLoadWithoutTrustedSubjectRunsDirectlyWithoutInventedIdentity() throws Exception {
        CountDownLatch observed = new CountDownLatch(1);
        AtomicReference<String> persisted = new AtomicReference<>();
        AgentMemoryRepository repository = mock(AgentMemoryRepository.class);
        when(repository.loadSnapshot(anyString())).thenAnswer(invocation -> {
            persisted.set(observeAtWritePoint());
            observed.countDown();
            return AgentMemorySnapshot.empty();
        });
        AgentUserMemoryMiddleware middleware = new AgentUserMemoryMiddleware(repository, binderProvider(gate.binder()));

        // 非配对 restore：只授 holder、不授同步起源（P2 (a′) 语义）——不是可信在场主体
        PrincipalContext.clear();
        PrincipalContext.restore(principal("T-RESIDUE", "920001"));
        RuntimeContext runtimeContext = RuntimeContext.builder().userId("910103").sessionId("c-inject").build();
        middleware.onReasoning(null, runtimeContext, emptyReasoningInput(), reasoningInput -> Flux.empty())
                .blockLast();

        PrincipalContext.clear();
        assertThat(observed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(persisted.get())
                .as("非同步起源的持有者不是可信主体：直跑并停在门内，不许绑成捕获身份")
                .startsWith("failed:");
    }

    // ==================== 夹具 ====================

    private AgentChatServiceImpl newService(AgentMemoryPipeline pipeline) {
        AgentMemoryProperties properties = new AgentMemoryProperties();
        properties.setLongTermEnabled(true);
        return new AgentChatServiceImpl(agentProvider, conversationService, taskManager, runGate,
                properties, pipeline, noPermits(), binderProvider(gate.binder()));
    }

    private static ReasoningInput emptyReasoningInput() {
        return new ReasoningInput(List.of(), List.of(), null);
    }

    /**
     * 落库点双门（与真链同形）：第一道 {@code require()}，第二道"当前租户仅在 binder 内非空"。
     */
    private String observeAtWritePoint() {
        try {
            ExecutionPrincipal principal = PrincipalContext.require();
            String tenant = gate.currentTenant();
            if (tenant == null || tenant.isBlank()) {
                throw new ClientException("tenant.context.missing");
            }
            return "persisted:" + tenant + ":" + principal.tenantId() + ":" + principal.membershipId();
        } catch (Exception e) {
            return "failed:" + e.getMessage();
        }
    }

    private static void setPrincipal(String tenantId, String userId) {
        PrincipalContext.set(principal(tenantId, userId));
    }

    private static void login(String userId) {
        UserContext.set(LoginUser.builder().userId(userId).username("tester").build());
    }

    private static ExecutionPrincipal principal(String tenantId, String userId) {
        return new ExecutionPrincipal(tenantId, userId, "platform:" + tenantId + ":" + userId,
                1, 1, Set.of(), "f10p3-chain-test", "test", 1, Long.MAX_VALUE);
    }

    /**
     * 租户门 fake（TenantHelper.dynamic 的等价镜像）：动态租户按线程保存，仅 binder 内非空。
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

        private void runWithTenant(String tenantId, Runnable handle) {
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

        private TenantScopeBinder binder() {
            return this::runWithTenant;
        }
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<TenantScopeBinder> binderProvider(TenantScopeBinder binder) {
        ObjectProvider<TenantScopeBinder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(binder);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<com.nageoffer.ai.ragent.runtime.web.DeliveryPermits> noPermits() {
        ObjectProvider<com.nageoffer.ai.ragent.runtime.web.DeliveryPermits> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
