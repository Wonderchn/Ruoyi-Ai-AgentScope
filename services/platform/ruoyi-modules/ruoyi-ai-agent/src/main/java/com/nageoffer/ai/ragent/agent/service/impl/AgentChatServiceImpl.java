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

package com.nageoffer.ai.ragent.agent.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.config.ReActAgentProvider;
import com.nageoffer.ai.ragent.agent.config.ReActAgentProvider.ActiveAgent;
import com.nageoffer.ai.ragent.agent.dto.AgentConfirmSettlement;
import com.nageoffer.ai.ragent.agent.dto.AgentMetaPayload;
import com.nageoffer.ai.ragent.agent.enums.AgentMemoryTriggerType;
import com.nageoffer.ai.ragent.agent.enums.AgentSSEEventType;
import com.nageoffer.ai.ragent.agent.identity.AgentRunScope;
import com.nageoffer.ai.ragent.agent.identity.AgentRunScopeRegistry;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryPipeline;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryProperties;
import com.nageoffer.ai.ragent.agent.memory.AsyncMemoryTaskScope;
import com.nageoffer.ai.ragent.agent.service.AgentChatService;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunGate;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunHandle;
import com.nageoffer.ai.ragent.agent.service.handler.AgentStreamEventBridge;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts;
import com.nageoffer.ai.ragent.agent.trace.AgentTraceContextKeys;
import com.nageoffer.ai.ragent.framework.context.AsyncPrincipalReference;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import com.nageoffer.ai.ragent.agent.tool.AgentMcpMeta;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.mcp.McpMeta;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 流式对话，负责发起提问与用户确认两条入口
 */
@Slf4j
@Service
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentChatServiceImpl implements AgentChatService {

    private final ReActAgentProvider agentProvider;
    private final AgentConversationService conversationService;
    private final StreamTaskManager taskManager;
    private final AgentRunGate runGate;
    private final AgentMemoryProperties memoryProperties;
    private final AgentMemoryPipeline memoryPipeline;
    /**
     * 交付许可（ENV-B5b 许可接线，2026-10-11）：内嵌形态帧经本地/远程网关传输，
     * 含数据帧必须带 {@code : ai-delivery} 元数据（fail-closed"unprotected stream frame"）；
     * 端口缺席（独立应用）按原形态裸帧发送。
     */
    private final ObjectProvider<DeliveryPermits> deliveryPermits;
    /**
     * 租户作用域绑定端口（F16 op1 管线修复，2026-10-12）：轮次结束后台抽取经
     * {@code subscribeOn(boundedElastic)} 跳到共享池线程——池线程上没有动态租户，
     * MyBatis 租户行拦截器对记忆表 fail-closed（真机 tenant.context.missing）。
     * 受理期捕获的租户经本端口在任务体内重绑；端口缺席（独立应用）按原形态直跑。
     */
    private final ObjectProvider<TenantScopeBinder> tenantScopeBinder;
    /**
     * 轮次身份作用域登记表（s2-tenant-cmp-a1，2026-10-12）：轮末状态保存由框架在共享池上
     * 回调 {@code AgentStateStore}，闭包只剩 {@code (userId, sessionId)}；受理期捕获登记到
     * 同一对键上，存储实现在 DAO 前重绑（形状同 F16 op1 抽取链）。释放钩子第 2 位注销
     * （补存盘之后、放闸之前）。
     */
    private final AgentRunScopeRegistry runScopeRegistry;

    @Override
    public void streamChat(String question, String conversationId, SseEmitter emitter) {
        String userId = UserContext.getUserId();
        String actualConversationId = StrUtil.isBlank(conversationId)
                ? IdUtil.getSnowflakeNextIdStr()
                : conversationId;
        String taskId = IdUtil.getSnowflakeNextIdStr();

        Runnable releaseGate = runGate.acquire(userId, taskId, actualConversationId);
        try {
            if (StrUtil.isNotBlank(conversationId)
                    && conversationService.hasPendingConfirm(actualConversationId, userId)) {
                throw new ClientException("上一步操作还在等你确认，请先确认或取消");
            }
            startRun(question, userId, actualConversationId, taskId, emitter, releaseGate);
        } catch (RuntimeException | Error e) {
            cleanupFailedStart(taskId, releaseGate);
            throw e;
        }
    }

    @Override
    public void confirmPendingTool(String conversationId, String messageId, boolean approved, SseEmitter emitter) {
        String userId = UserContext.getUserId();
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(messageId)) {
            throw new ClientException("确认参数不完整");
        }
        String taskId = IdUtil.getSnowflakeNextIdStr();

        Runnable releaseGate = runGate.acquire(userId, taskId, conversationId);
        try {
            startConfirmRun(userId, conversationId, messageId, approved, taskId, emitter, releaseGate);
        } catch (RuntimeException | Error e) {
            cleanupFailedStart(taskId, releaseGate);
            throw e;
        }
    }

    /**
     * 只在启动失败时走：闸门不还，这个用户要被锁到 TTL 到期才能再提问
     * 启动成功后闸门归运行句柄的释放钩子管，这里不碰
     * 两步各自兜异常，前一步失败不能连累后一步
     */
    private void cleanupFailedStart(String taskId, Runnable releaseGate) {
        try {
            releaseGate.run();
        } catch (Exception e) {
            log.error("Agent 启动失败后释放闸门失败, taskId: {}", taskId, e);
        }
        try {
            taskManager.unregister(taskId);
        } catch (Exception e) {
            log.error("Agent 启动失败后注销任务失败, taskId: {}", taskId, e);
        }
    }

    private void startRun(String question, String userId, String conversationId, String taskId,
                          SseEmitter emitter, Runnable releaseGate) {
        ActiveAgent activeAgent = agentProvider.getAgent();

        String title = conversationService.touchConversation(conversationId, userId, question);
        // 必须在 addUserMessage 之前建立基线，否则本轮消息会被划进历史、漏抽
        memoryPipeline.ensureExtractionBaseline(userId);
        String questionMessageId = conversationService.addUserMessage(conversationId, userId, question);

        launchStream(new UserMessage(question), activeAgent, RunScope.builder()
                .emitter(emitter)
                .userId(userId)
                .conversationId(conversationId)
                .taskId(taskId)
                .title(title)
                .replyToMessageId(questionMessageId)
                .releaseGate(releaseGate)
                .build());
    }

    /**
     * 用户确认/拒绝后继续执行：先只读校验，启动准备完成后才结算卡片
     */
    private void startConfirmRun(String userId, String conversationId, String messageId, boolean approved,
                                 String taskId, SseEmitter emitter, Runnable releaseGate) {
        ActiveAgent activeAgent = agentProvider.getAgent();
        AgentConfirmSettlement settlement = conversationService.getPendingConfirm(conversationId, userId, messageId);
        List<ConfirmResult> confirmResults = resolveConfirmResultsOrExpire(activeAgent, userId, conversationId, messageId, approved);

        // 空正文消息仅携带确认/拒绝结果，框架不会把它并进对话上下文
        Msg resumeMsg = UserMessage.builder()
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, confirmResults))
                .build();
        launchStream(resumeMsg, activeAgent, RunScope.builder()
                .emitter(emitter)
                .userId(userId)
                .conversationId(conversationId)
                .taskId(taskId)
                .title(settlement.title())
                .replyToMessageId(settlement.replyToMessageId())
                .confirmMessageId(messageId)
                .confirmApproved(approved)
                .releaseGate(releaseGate)
                .build());
    }

    /**
     * 工具入参从 Agent 状态取，前端只传同意/拒绝，防止篡改
     */
    private List<ConfirmResult> resolveConfirmResultsOrExpire(ActiveAgent activeAgent, String userId,
                                                              String conversationId, String messageId, boolean approved) {
        @SuppressWarnings("resource")
        ReActAgent agent = activeAgent.agent();
        List<ToolUseBlock> asking = askingToolCalls(agent.getAgentState(userId, conversationId).getContext());
        if (asking.isEmpty()) {
            // 状态里没有待确认工具了，先结算卡片再报错，否则会话会一直卡住
            conversationService.expirePendingConfirm(conversationId, userId, messageId);
            throw new ClientException("待确认的操作已失效，请重新提问");
        }
        return asking.stream().map(toolCall -> new ConfirmResult(approved, toolCall)).toList();
    }

    /**
     * 待批工具只在上下文最后一条 assistant 消息上，与框架内部判据一致
     */
    private static List<ToolUseBlock> askingToolCalls(List<Msg> context) {
        for (int i = context.size() - 1; i >= 0; i--) {
            Msg msg = context.get(i);
            if (msg.getRole() != MsgRole.ASSISTANT) {
                continue;
            }
            return msg.getContent().stream()
                    .filter(ToolUseBlock.class::isInstance)
                    .map(ToolUseBlock.class::cast)
                    .filter(toolCall -> toolCall.getState() == ToolCallState.ASKING)
                    .toList();
        }
        return List.of();
    }

    /**
     * 首问与确认后继续执行共用的流式启动逻辑
     */
    private void launchStream(Msg input, ActiveAgent activeAgent, RunScope scope) {
        String userId = scope.userId();
        String conversationId = scope.conversationId();
        String taskId = scope.taskId();
        ReActAgent agent = activeAgent.agent();

        // 事实源：桥、上下文与句柄共用，ID 与时刻只在这里产生
        Clock clock = Clock.systemDefaultZone();
        AgentToolExecutionFacts facts = new AgentToolExecutionFacts(taskId, clock);
        // 受理期（请求线程、同步起源）一次捕获，完成链三处共用：轮末状态保存（框架在共享池上
        // 回调 AgentStateStore，只回传 userId/sessionId）、终答落库（流终止/池线程）、知识检索
        // 工具（工具自身 subscribeOn 起跳）。池化残留不是可信在场主体，捕获为空＝各链按原形态直跑。
        AgentRunScope runScope = AgentRunScope.capture(tenantScopeBinder);
        // 上下文传到底，中断时句柄从这里取根 span；捕获同时挂在工具执行上下文上供工具链读
        RuntimeContext runtimeContext = buildRuntimeContext(scope, facts, runScope);
        // 许可接线：请求线程可取到执行主体；缺席（独立应用）退回裸帧形态
        DeliveryPermits permits = deliveryPermits.getIfAvailable();
        ExecutionPrincipal permitPrincipal = PrincipalContext.get();
        SseEmitterSender sender = (permits == null || permitPrincipal == null)
                ? new SseEmitterSender(scope.emitter())
                : new SseEmitterSender(scope.emitter(), permits, permitPrincipal, "run.stream", "chat:" + taskId);
        AgentRunHandle runHandle = new AgentRunHandle(taskId, sender, taskManager, facts, runtimeContext);
        bindReleaseHooks(runHandle, agent, scope, runScope);
        // 轮末框架存盘在流终止之前完成；登记放在订阅之前，注销是释放钩子第 2 位
        runScopeRegistry.register(userId, conversationId, runScope);
        try {
            bindEmitterLifecycle(scope.emitter(), runHandle, taskId);
            sender.sendEvent(AgentSSEEventType.META.value(), new AgentMetaPayload(conversationId, taskId));
            AgentStreamEventBridge bridge = buildBridge(activeAgent, scope, runHandle, facts, clock);
            taskManager.register(taskId, userId, bridge::finishCancelledStream);
            // 预埋取消标记会让 register 当场跑完收尾，此时不再启动 Agent
            if (runHandle.isSettled()) {
                return;
            }

            Flux<AgentEvent> events = agent.streamEvents(input, runtimeContext)
                    // 让 runHandle 知道框架流已结束，取消时不必再强行断流
                    .doFinally(signal -> runHandle.markUpstreamTerminated());
            runHandle.start(() -> {
                settleConfirmCard(scope);
                Disposable disposable = events.subscribe(bridge::onEvent, bridge::onError, bridge::onComplete);
                runHandle.bindStream(disposable, () -> agent.interrupt(userId, conversationId));
                taskManager.bindHandle(taskId, runHandle::interruptUpstream);
            });
        } catch (RuntimeException | Error e) {
            // 启动失败时释放钩子不会执行：注销本轮登记＋清掉缓存里的坏状态
            runScopeRegistry.unregister(userId, conversationId);
            agent.clearStateCache(userId, conversationId);
            throw e;
        }
    }

    /**
     * 登记流结束后的三件事，注册顺序就是执行顺序，换顺序会出问题
     *
     * <p>s2-tenant-cmp-a1 插入第 2 位注销：轮末框架存盘与补存盘都发生在登记期内，
     * 注销必须排在补存盘钩子之后、放闸之前——放闸后同一 (user, 会话) 的新轮会重新登记
     * 同一键，注销若排最后会把新轮的登记顶掉。
     */
    private void bindReleaseHooks(AgentRunHandle runHandle, ReActAgent agent, RunScope scope,
                                  AgentRunScope runScope) {
        String userId = scope.userId();
        String conversationId = scope.conversationId();
        runHandle.onRelease(() -> {
            // 错误路径与强制断流框架都来不及存盘，驱逐前补存一次，否则本轮工具执行结果会丢
            // 优雅中断已由框架中断分支存盘，不重复保存
            if (runHandle.isStateSaveRequired()) {
                try {
                    agent.saveAgentState(userId, conversationId);
                } catch (Exception e) {
                    log.error("Agent 失败收尾补存盘失败, conversationId: {}", conversationId, e);
                }
            }
            // 只清本次流实际使用的实例，避免重建后旧流误清新 Agent
            agent.clearStateCache(userId, conversationId);
        });
        // 本轮登记期到此为止（补存盘已在上个钩子里完成）；新轮重新登记前先撤，防跨轮错绑
        runHandle.onRelease(() -> runScopeRegistry.unregister(userId, conversationId));
        // 最后再放行同一用户的下一轮，避免新流加载状态后被本轮收尾清掉
        runHandle.onRelease(scope.releaseGate());
        // 抽取链复用同一份受理期捕获（launchStream 请求线程、同步起源处捕获，经参数传入；
        // 捕获点只是从本方法内上移一行，语义不变）：释放钩子可能跑在流终止线程或
        // redisson 监听线程上，那里的主体是池化残留、租户为空——在钩子执行时才读就晚了
        // （F16 op1 真机根因）。只认同步起源：残留拷贝不是可信在场主体，绝不作为绑定来源。
        AsyncPrincipalReference extractionPrincipal = runScope == null ? null : runScope.principal();
        // tenantId 从同一捕获派生，两处来源不许各自读一遍（漂移＝跨租户写归属）
        String extractionTenantId = runScope == null ? null : runScope.tenantId();
        // 放在释放并发锁之后，确保记忆抽取时名额已归还
        runHandle.onRelease(() -> scheduleMemoryExtraction(userId, conversationId,
                extractionPrincipal, extractionTenantId));
    }

    /**
     * 组装事件，agent 和 catalog 从同一个 ActiveAgent 取出，保证展示名一致
     */
    private AgentStreamEventBridge buildBridge(ActiveAgent activeAgent, RunScope scope, AgentRunHandle runHandle,
                                               AgentToolExecutionFacts facts, Clock clock) {
        return AgentStreamEventBridge.builder()
                .runHandle(runHandle)
                .conversationService(conversationService)
                .catalog(activeAgent.catalog())
                .conversationId(scope.conversationId())
                .userId(scope.userId())
                .title(scope.title())
                .replyToMessageId(scope.replyToMessageId())
                .clock(clock)
                .facts(facts)
                .build();
    }

    /**
     * 把确认卡片改写成终态，首问路径没有卡片可结算，直接跳过
     * 只能在启动互斥区里做：先结算后订阅，取消才不会漏掉这次工具执行
     */
    private void settleConfirmCard(RunScope scope) {
        if (scope.confirmMessageId() == null) {
            return;
        }
        conversationService.settlePendingConfirm(scope.conversationId(), scope.userId(),
                scope.confirmMessageId(), scope.confirmApproved());
    }

    /**
     * 事实源在构建时就位，中间件从上下文取它；其余 key 建完再 put——底层 ConcurrentHashMap 不接受 null
     *
     * <p>受理期捕获的身份作用域一并挂上（s2-tenant-cmp-a1）：工具链在池线程上从
     * {@code ToolCallParam.getRuntimeContext()} 取它重绑；无捕获形态不挂、工具按原形态直跑。
     */
    private static RuntimeContext buildRuntimeContext(RunScope scope, AgentToolExecutionFacts facts,
                                                      AgentRunScope runScope) {
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId(scope.userId())
                .sessionId(scope.conversationId())
                .put(McpMeta.class, new McpMeta(AgentMcpMeta.ofUser(scope.userId())))
                .put(AgentToolExecutionFacts.RUNTIME_CONTEXT_KEY, facts)
                .build();
        if (runScope != null) {
            runtimeContext.put(AgentRunScope.RUNTIME_CONTEXT_KEY, runScope);
        }
        runtimeContext.put(AgentTraceContextKeys.TASK_ID, scope.taskId());
        runtimeContext.put(AgentTraceContextKeys.REPLY_TO_MESSAGE_ID, scope.replyToMessageId());
        runtimeContext.put(AgentTraceContextKeys.CONFIRM_MESSAGE_ID, scope.confirmMessageId());
        return runtimeContext;
    }

    /**
     * 单次运行的上下文参数，在 startRun/startConfirmRun 与 launchStream 之间传递
     */
    @Builder
    private record RunScope(SseEmitter emitter, String userId, String conversationId,
                            String taskId, String title, String replyToMessageId, String confirmMessageId,
                            boolean confirmApproved, Runnable releaseGate) {
    }

    /**
     * 轮次结束后异步抽取长期记忆
     *
     * <p>抽取跳上共享 boundedElastic 线程后，落库链要先过 {@code PrincipalContext.require()}
     * 再过 MyBatis 租户行拦截器两道门——两道门都只认受理期捕获的事实：任务体用捕获的
     * 身份引用（非配对 restore，不授出站起源）与同源派生的租户重绑，finally 恢复线程原主体。
     * 无捕获（无主体形态）按原形态直跑，不新增默认身份。
     */
    private void scheduleMemoryExtraction(String userId, String conversationId,
                                          AsyncPrincipalReference principalRef, String tenantId) {
        if (!memoryProperties.isLongTermEnabled()) {
            return;
        }
        Mono.fromCallable(() -> AsyncMemoryTaskScope.callWithCapture(principalRef, tenantId, tenantScopeBinder,
                        () -> memoryPipeline.extract(userId, conversationId, AgentMemoryTriggerType.BACKGROUND)))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(outcome -> {
                            if (outcome.idle()) {
                                return;
                            }
                            log.info("轮次结束后台记忆抽取完成, userId: {}, conversationId: {}, 状态: {}, 落库: {}",
                                    userId, conversationId, outcome.status(), outcome.applied());
                        },
                        // 用户已拿到答复，失败只记日志
                        e -> log.error("轮次结束后台记忆抽取异常, userId: {}, conversationId: {}",
                                userId, conversationId, e));
    }

    /**
     * SSE 断开（关页/断网/超时）时触发取消，否则 ReAct 循环会空跑到迭代上限
     */
    private void bindEmitterLifecycle(SseEmitter emitter, AgentRunHandle runHandle, String taskId) {
        AtomicBoolean recycled = new AtomicBoolean(false);
        Runnable recycleUpstream = () -> {
            // 已结算的不再取消，避免往 Redis 留死标记
            if (!runHandle.isSettled() && recycled.compareAndSet(false, true)) {
                taskManager.cancel(taskId);
            }
        };
        emitter.onTimeout(recycleUpstream);
        emitter.onError(e -> recycleUpstream.run());
        // 用户关闭页面走 completeWithError，容器不触发 onTimeout/onError，只有 onCompletion 能兜住
        emitter.onCompletion(recycleUpstream);
    }

    @Override
    public void stopTask(String taskId) {
        taskManager.cancelByUser(taskId);
    }
}
