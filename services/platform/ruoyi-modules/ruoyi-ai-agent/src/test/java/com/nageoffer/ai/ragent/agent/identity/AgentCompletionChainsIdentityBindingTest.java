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

package com.nageoffer.ai.ragent.agent.identity;

import com.nageoffer.ai.ragent.agent.config.ReActAgentProvider;
import com.nageoffer.ai.ragent.agent.config.ReActAgentProvider.ActiveAgent;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentStateMapper;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryPipeline;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryProperties;
import com.nageoffer.ai.ragent.agent.memory.AsyncMemoryTaskScope;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunGate;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunHandle;
import com.nageoffer.ai.ragent.agent.service.handler.AgentStreamEventBridge;
import com.nageoffer.ai.ragent.agent.service.impl.AgentChatServiceImpl;
import com.nageoffer.ai.ragent.agent.state.PgAgentStateStore;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.ResolvedCatalog;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts;
import com.nageoffer.ai.ragent.agent.tool.KnowledgeSearchTool;
import com.nageoffer.ai.ragent.framework.context.AsyncPrincipalReference;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import com.nageoffer.ai.ragent.rag.service.KnowledgeSearchFacade;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.state.State;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.util.JsonUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 完成链三链的身份/租户显式化判据（s2-tenant-cmp-a1，红先行）。
 *
 * <p>背景（真机 r17/f10p3-win/app-6044.log §2 C4/C4.R）：协议层全绿但 3/3 轮次
 * {@code messageStatus=INTERRUPTED}、终答 0 行落库。正常完成一个 agent 轮次时
 * 三条既有链必挂 MyBatis 租户门（{@code tenant.context.missing}）：
 * <ol>
 *   <li>轮末 {@code ReActAgent.saveStateToSession} 在 {@code subscribeOn(boundedElastic)}
 *       上回调 {@code PgAgentStateStore.save}（库回调只剩 (userId, sessionId) 一对键）；</li>
 *   <li>错误路径终答落库 {@code AgentStreamEventBridge.settleAndPersistMessage →
 *       AgentConversationServiceImpl.addAssistantMessage}（流终止/池线程）；</li>
 *   <li>{@code KnowledgeSearchTool} 自身 {@code subscribeOn(boundedElastic)} 后
 *       {@code KnowledgeSearchFacade.search → QueryTermMappingService → ai_query_term_mapping}。</li>
 * </ol>
 *
 * <p>本判据的门 fake 与真链同形（同 P3 {@code AsyncMemoryChainsIdentityBindingTest}）：
 * {@code require()} ＋「当前租户仅在 binder 内非空」，断言串
 * {@code persisted:<tenant>:<principalTenant>:<membershipId>}——只绑身份／只绑租户都红，
 * 双门才绿；执行线程上先铺池化残留身份、清空动态租户，任何"读环境身份"的实现都会停在
 * 身份门或租户门，也绝不允许把残留拷贝绑成本轮身份。
 */
@Tag("dev")
class AgentCompletionChainsIdentityBindingTest {

    private static final String USER_ID = "910101";
    private static final String CONVERSATION_ID = "c-cmp-a1";
    private static final String TASK_ID = "t-cmp-a1";

    private final TenantGate gate = new TenantGate();

    private AgentRunScopeRegistry registry;
    private ReActAgentProvider agentProvider;
    private AgentConversationService conversationService;
    private StreamTaskManager taskManager;
    private AgentRunGate runGate;
    private ReActAgent agent;
    private AgentToolExecutionFacts facts;

    @BeforeAll
    static void initJsonCodec() {
        // PgAgentStateStore 的 payload 编解码依赖 AgentScope 的静态 codec；单测 JVM 显式重置
        JsonUtils.resetToDefault();
    }

    @BeforeEach
    void setUp() {
        registry = new AgentRunScopeRegistry();
        agentProvider = mock(ReActAgentProvider.class);
        conversationService = mock(AgentConversationService.class);
        taskManager = mock(StreamTaskManager.class);
        runGate = mock(AgentRunGate.class);
        agent = mock(ReActAgent.class);
        facts = new AgentToolExecutionFacts(TASK_ID, Clock.systemDefaultZone());
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

    // ==================== 链一：轮末状态保存（框架在共享池上回调 AgentStateStore） ====================

    /**
     * 受理期（请求线程）完成端到端登记；模拟框架轮末存盘线程（池化残留身份＋无动态租户）
     * 触发 {@code store.save}：必须按受理期捕获重绑——只允许捕获身份，绝不允许残留身份。
     */
    @Test
    void roundEndStateSaveMustRunUnderCapturedIdentityAndTenant() {
        AgentStateMapper mapper = mock(AgentStateMapper.class);
        PgAgentStateStore store = newStore(mapper);
        AgentChatServiceImpl service = newService();

        login(USER_ID);
        setPrincipal("T-CMP-A1A", USER_ID);
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.never());
        service.streamChat("问题", CONVERSATION_ID, new SseEmitter());

        // 受理期已登记：键＝库回调会拿到的那一对 (userId, sessionId)
        RuntimeContext runtimeContext = capturedRuntimeContext();
        assertThat(runtimeContext.getUserId()).isEqualTo(USER_ID);
        assertThat(runtimeContext.getSessionId()).isEqualTo(CONVERSATION_ID);
        assertThat(registry.resolve(USER_ID, CONVERSATION_ID)).isNotNull();

        AtomicReference<String> observed = new AtomicReference<>();
        doAnswer(invocation -> {
            observed.set(observeAtWritePoint());
            return null;
        }).when(mapper).upsert(any(), any(), any(), any(), any(), any(), any(), any(), any());

        // 模拟轮末存盘线程：别的身份残留＋没有动态租户（真机 boundedElastic 形态）
        ExecutionPrincipal residue = principal("T-RESIDUE", "920001");
        PrincipalContext.clear();
        gate.setCurrentTenant(null);
        PrincipalContext.restore(residue);

        store.save(USER_ID, CONVERSATION_ID, "agent_state", new PayloadState("v"));

        assertThat(observed.get())
                .as("轮末存盘必须看到受理期捕获的身份与租户（双门），否则真机 tenant.context.missing")
                .isEqualTo("persisted:T-CMP-A1A:T-CMP-A1A:platform:T-CMP-A1A:910101");
        // DAO 收到的键参数必须来自捕获，不许是残留身份（末三参＝写侧 provenance：
        // 本用例 store 未接授权事实端口 ⇒ NULL，故用宽匹配器，语义断言仍只钉键参数）
        verify(mapper).upsert(eq("T-CMP-A1A"), eq("platform:T-CMP-A1A:910101"), eq(USER_ID),
                eq(CONVERSATION_ID), eq("agent_state"), any(), any(), any(), any());
        // finally 复原线程原主体（恢复而非 clear）
        assertThat(PrincipalContext.get()).isSameAs(residue);

        // 收尾后登记必须注销（放闸前已撤，防新轮登记被顶掉）
        ArgumentCaptor<Runnable> finalizer = ArgumentCaptor.forClass(Runnable.class);
        verify(taskManager).register(anyString(), anyString(), finalizer.capture());
        finalizer.getValue().run();
        assertThat(registry.resolve(USER_ID, CONVERSATION_ID)).isNull();
    }

    /**
     * 没有已登记轮次（无捕获形态）时，存盘按原形态直跑：残留身份不得充当可信主体，
     * 停在租户门内失败——不许凭空补身份。
     */
    @Test
    void stateSaveWithoutRegisteredRunStaysFailClosed() {
        AgentStateMapper mapper = mock(AgentStateMapper.class);
        PgAgentStateStore store = newStore(mapper);

        AtomicReference<String> observed = new AtomicReference<>();
        doAnswer(invocation -> {
            observed.set(observeAtWritePoint());
            return null;
        }).when(mapper).upsert(any(), any(), any(), any(), any(), any(), any(), any(), any());

        PrincipalContext.clear();
        gate.setCurrentTenant(null);
        PrincipalContext.restore(principal("T-RESIDUE", "920001"));

        store.save(USER_ID, CONVERSATION_ID, "agent_state", new PayloadState("v"));

        assertThat(observed.get())
                .as("无登记轮次时不得把池化残留当身份，也不许新增默认身份")
                .isEqualTo("failed:tenant.context.missing");
    }

    /**
     * 释放钩子顺序（链一的登记生命周期）：补存盘钩子必须仍在登记期内执行，
     * 注销必须早于放闸（放闸后新轮会重新登记同一键）。
     */
    @Test
    void releaseHooksKeepRegistryUntilBackfillSaveThenDropBeforeGate() {
        AgentChatServiceImpl service = newService();
        AtomicBoolean savedWhileRegistered = new AtomicBoolean(false);
        AtomicBoolean gateSawRegistered = new AtomicBoolean(false);
        when(runGate.acquire(anyString(), anyString(), anyString()))
                .thenReturn(() -> gateSawRegistered.set(registry.resolve(USER_ID, CONVERSATION_ID) != null));
        doAnswer(invocation -> {
            savedWhileRegistered.set(registry.resolve(USER_ID, CONVERSATION_ID) != null);
            return null;
        }).when(agent).saveAgentState(anyString(), anyString());
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(Flux.error(new IllegalStateException("轮末存盘失败")));

        login(USER_ID);
        setPrincipal("T-CMP-A1D", USER_ID);
        service.streamChat("问题", CONVERSATION_ID, new SseEmitter());

        assertThat(savedWhileRegistered.get())
                .as("失败补存盘钩子必须在登记期内执行，否则补存盘又撞租户门")
                .isTrue();
        assertThat(gateSawRegistered.get())
                .as("放闸前必须已注销，防下一轮登记被本轮注销顶掉")
                .isFalse();
        assertThat(registry.resolve(USER_ID, CONVERSATION_ID)).isNull();
    }

    // ==================== 链二：终答落库（流终止/池线程） ====================

    /**
     * 错误路径终答落库在无主体、无租户的流终止线程上触发：必须看到受理期捕获（双门），
     * 否则助手消息丢失（真机 C4.R：3 会话 0 助手行）。
     */
    @Test
    void errorPathFinalPersistMustRunUnderCapturedIdentityAndTenant() {
        RuntimeContext runtimeContext = RuntimeContext.builder().userId(USER_ID).sessionId("c-cmp-final").build();
        runtimeContext.put(AgentRunScope.RUNTIME_CONTEXT_KEY, captureScope("T-CMP-A1B", USER_ID));
        AgentRunHandle runHandle = new AgentRunHandle(TASK_ID, mock(SseEmitterSender.class),
                taskManager, facts, runtimeContext);
        AgentStreamEventBridge bridge = AgentStreamEventBridge.builder()
                .runHandle(runHandle)
                .conversationService(conversationService)
                .catalog(new ResolvedCatalog("知识库工具描述", null, List.of(), List.of(), List.of()))
                .conversationId("c-cmp-final")
                .userId(USER_ID)
                .title("会话标题")
                .replyToMessageId("m-3003")
                .clock(Clock.systemDefaultZone())
                .facts(facts)
                .build();
        AtomicReference<String> observed = new AtomicReference<>();
        when(conversationService.addAssistantMessage(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    observed.set(observeAtWritePoint());
                    return "m-assistant";
                });

        // 流终止线程形态：无主体、无租户
        PrincipalContext.clear();
        gate.setCurrentTenant(null);
        bridge.onError(new IllegalStateException("轮末存盘失败"));

        assertThat(observed.get())
                .as("错误路径终答落库必须看到捕获的身份与租户（双门），否则助手消息丢失")
                .isEqualTo("persisted:T-CMP-A1B:T-CMP-A1B:platform:T-CMP-A1B:910101");
        // finally 复原：桥执行后线程回到无主体形态
        assertThat(PrincipalContext.get()).isNull();
    }

    // ==================== 链三：知识检索工具（工具自身 subscribeOn(boundedElastic)） ====================

    /**
     * 检索工具在共享池上执行：RAG 管线的 DB 读（ai_query_term_mapping 等）必须看到
     * 受理期捕获（双门）；工具执行上下文（RuntimeContext）是唯一可信载体。
     */
    @Test
    void knowledgeToolSearchMustRunUnderCapturedIdentityAndTenant() {
        KnowledgeSearchFacade facade = mock(KnowledgeSearchFacade.class);
        AtomicReference<String> observed = new AtomicReference<>();
        when(facade.search("报销流程")).thenAnswer(invocation -> {
            observed.set(observeAtWritePoint());
            return "报销需审批";
        });
        KnowledgeSearchTool tool = new KnowledgeSearchTool("检索企业知识库", facade);
        RuntimeContext runtimeContext = RuntimeContext.builder().userId(USER_ID).sessionId("c-cmp-tool").build();
        runtimeContext.put(AgentRunScope.RUNTIME_CONTEXT_KEY, captureScope("T-CMP-A1C", USER_ID));
        ToolCallParam param = ToolCallParam.builder()
                .input(Map.of("query", "报销流程"))
                .runtimeContext(runtimeContext)
                .build();

        // 工具执行线程形态：无主体、无租户
        PrincipalContext.clear();
        gate.setCurrentTenant(null);
        ToolResultBlock result = tool.callAsync(param).block();

        assertThat(result).isNotNull();
        assertThat(result.getState()).isEqualTo(ToolResultState.SUCCESS);
        assertThat(observed.get())
                .as("工具检索链的 DB 读必须看到捕获的身份与租户（双门），否则真机 ai_query_term_mapping 撞门")
                .isEqualTo("persisted:T-CMP-A1C:T-CMP-A1C:platform:T-CMP-A1C:910101");
        assertThat(PrincipalContext.get()).isNull();
    }

    /**
     * 载体缺席（无捕获形态或旧形态调用方）：工具按原形态直跑，停在门内失败，
     * 不许凭空补身份；失败经工具体包装为可见的 ERROR 工具结果。
     */
    @Test
    void knowledgeToolWithoutCapturedScopeStaysFailClosed() {
        KnowledgeSearchFacade facade = mock(KnowledgeSearchFacade.class);
        AtomicReference<String> observed = new AtomicReference<>();
        when(facade.search("报销流程")).thenAnswer(invocation -> {
            observed.set(observeAtWritePoint());
            throw new ClientException("tenant.context.missing");
        });
        KnowledgeSearchTool tool = new KnowledgeSearchTool("检索企业知识库", facade);
        ToolCallParam param = ToolCallParam.builder()
                .input(Map.of("query", "报销流程"))
                .runtimeContext(RuntimeContext.builder().userId(USER_ID).sessionId("c-cmp-tool-2").build())
                .build();

        PrincipalContext.clear();
        gate.setCurrentTenant(null);
        ToolResultBlock result = tool.callAsync(param).block();

        assertThat(result).isNotNull();
        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(observed.get())
                .as("无捕获时直跑并停在门内，不许凭空补身份")
                .startsWith("failed:");
    }

    // ==================== 共用重绑语义（三链据此共用 AsyncMemoryTaskScope 已解析端口入口） ====================

    /**
     * 已解析端口入口语义与 P3 形状一致：非配对 restore 只授 holder、不授同步起源（出站闸保持
     * fail-closed）；**finally 必须复原线程原主体**（共享池线程，泄漏＝跨轮错绑）。
     */
    @Test
    void runScopeRebindRestoresPreviousPrincipalAndNeverGrantsOrigin() {
        ExecutionPrincipal original = principal("T-CMP-ORIG", "930001");
        PrincipalContext.set(original);
        try {
            AtomicReference<Boolean> originInside = new AtomicReference<>();
            AtomicReference<String> observed = new AtomicReference<>();
            String result = AsyncMemoryTaskScope.callWithResolvedCapture(
                    AsyncPrincipalReference.from(principal("T-CMP-A1E", "910105")), "T-CMP-A1E",
                    gate.binder(), () -> {
                        originInside.set(PrincipalContext.isSynchronousOrigin());
                        observed.set(observeAtWritePoint());
                        return "ok";
                    });

            assertThat(result).isEqualTo("ok");
            assertThat(originInside.get())
                    .as("非配对 restore 不得把捕获身份升级成同步起源（出站门保持 fail-closed）")
                    .isFalse();
            assertThat(observed.get())
                    .isEqualTo("persisted:T-CMP-A1E:T-CMP-A1E:platform:T-CMP-A1E:910105");
            assertThat(PrincipalContext.get())
                    .as("finally 必须复原线程原主体（恢复而非 clear）")
                    .isSameAs(original);
        } finally {
            PrincipalContext.clear();
        }
    }

    // ==================== 夹具 ====================

    private AgentChatServiceImpl newService() {
        AgentMemoryProperties properties = new AgentMemoryProperties();
        // 本判据只盯完成链三处；抽取链有独立判据（AsyncMemoryChainsIdentityBindingTest）
        properties.setLongTermEnabled(false);
        AgentMemoryPipeline pipeline = mock(AgentMemoryPipeline.class);
        return new AgentChatServiceImpl(agentProvider, conversationService, taskManager, runGate,
                properties, pipeline, noPermits(), binderProvider(gate.binder()),
                // s2-f16egress-a1：抽取出站重建端口缺席＝退回捕获形态（完成链判据不涉出站）
                absentFacts(), registry);
    }

    private PgAgentStateStore newStore(AgentStateMapper mapper) {
        PgAgentStateStore store = new PgAgentStateStore(mapper);
        store.configureRunScope(registryProvider(registry), binderProvider(gate.binder()));
        return store;
    }

    private RuntimeContext capturedRuntimeContext() {
        ArgumentCaptor<RuntimeContext> captor = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(agent).streamEvents(any(Msg.class), captor.capture());
        return captor.getValue();
    }

    /** 受理期捕获（请求线程、同步起源），捕获后还原测试线程环境。 */
    private AgentRunScope captureScope(String tenantId, String userId) {
        PrincipalContext.set(principal(tenantId, userId));
        try {
            return AgentRunScope.capture(binderProvider(gate.binder()));
        } finally {
            PrincipalContext.clear();
        }
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
                1, 1, Set.of(), "cmp-a1-chain-test", "test", 1, Long.MAX_VALUE);
    }

    /** Jackson 可序列化的最小 State 实现，只为走通 payload 编解码。 */
    private record PayloadState(String value) implements State {
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
    private static ObjectProvider<AgentRunScopeRegistry> registryProvider(AgentRunScopeRegistry registry) {
        ObjectProvider<AgentRunScopeRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<com.nageoffer.ai.ragent.runtime.web.DeliveryPermits> noPermits() {
        ObjectProvider<com.nageoffer.ai.ragent.runtime.web.DeliveryPermits> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<com.nageoffer.ai.ragent.agent.memory.MemoryEgressSubjectFacts> absentFacts() {
        ObjectProvider<com.nageoffer.ai.ragent.agent.memory.MemoryEgressSubjectFacts> provider =
                mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
