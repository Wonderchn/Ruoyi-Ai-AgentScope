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

package com.nageoffer.ai.ragent.agent.controller;

import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.controller.request.ConfirmRequest;
import com.nageoffer.ai.ragent.agent.service.AgentChatService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.framework.validation.ChatQuestion;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 对话入口，仅 ragent.engine.type=agent 时注册；RAG v3 接口不受影响
 *
 * <p><b>路径必须落在内部前缀下（C13.2/C13.3 修正）。</b>本类此前<b>没有类级
 * {@code @RequestMapping}</b>，方法路径直接写 {@code /agent/v1/...}，于是它注册在
 * <b>应用根的公开路径空间</b>里。而网关 {@code AiGatewayController} 的转送目标恒为
 * {@code base + "/internal/ai/v1" + subPath}（{@code AiGatewayController:80/271}），
 * 内层 handler 会在 {@code /internal/ai/v1/agent/v1/...} 上被查找 —— 两者之间
 * <b>没有任何编译期约束</b>，结果是四条端点经 {@code /api/ai/v1/**} <b>必然 404</b>；
 * 同时它们又落在 {@code AiInternalAccessBoundaryFilter} 的关闭范围
 * （{@code /internal/ai/v1/*}）之外，所以也<b>不是"外部不可达"</b>。
 *
 * <p><b>为什么直接改本类而不是另包一层控制器。</b>它与 {@code AgentConversationController}
 * 不同：会话面之所以要包一层 {@code ConversationSurface}
 * （{@code AiEmbeddedAgentConversationConfiguration:207-293}），是因为会话面的身份必须
 * 从 {@code PrincipalContext} 取（内嵌态没有 {@code UserContextInterceptor}）。
 * 引擎面的身份桥由 {@link AgentEngineSurface} 承担，因此这里<b>只做路径归位 + 边界闭合</b>，
 * 与 {@code RunController:49}、{@code RunStreamController:38}、
 * {@code AiResourceController:66}、{@code UploadController:48}、
 * {@code RunAcceptanceController:46}、{@code RevocationBarrierController:50}
 * 六个同类控制器<b>完全同形</b>：类级 {@code @RequestMapping("/internal/ai/v1")} +
 * 方法级相对路径。
 *
 * <p><b>为什么类级注解里写的是字面量而不是共享常量。</b>
 * {@code LocalWhitelistHandlerCoverageTest}（本包缺陷的直接产物）用源码扫描求
 * "白名单路由 ↔ 内层 handler"的交集：它只认类级
 * {@code @RequestMapping("/internal/ai/v1")} 的字面量，或方法级的
 * {@code INTERNAL_PREFIX + "/x"} 常量拼接。写成 {@code @RequestMapping(PREFIX_CONSTANT)}
 * 会让该扫描<b>一条都读不到</b>，于是"放行了但没人接"的护栏直接失效。
 *
 * <p><b>包络形状：JSON 两条必须是整数 {@code code}。</b>{@code stop} 此前返回
 * {@code Result<Void>}，其 {@code code} 是<b>字符串</b> {@code "0"}。网关
 * {@code AiGatewayClient.requireSingleJsonObject} 强制"单 JSON 对象 + <b>整数</b>
 * {@code code} 等于 HTTP 状态"，字符串 code 会被判为"缺少包络 code"并收敛为 503 ——
 * WP-034B 已经因为这个形态丢过一次公开面。故改为
 * {@link ApiEnvelope}（{@code int code}）。SSE 两条不走包络（由帧交付负责）。
 *
 * <p><b>根路径残留（精确范围）。</b>本类改完之后，<b>引擎面这两个控制器</b>
 * （本类与 {@link AgentMetaController}）在 {@code services/platform} 里
 * {@code /agent/v1/**} 字面量归零 —— 无论组件扫描是否覆盖
 * {@code com.nageoffer.ai.ragent.agent.controller}，都不可能再暴露第二份根级公开面。
 * <p><b>这不等于"全仓归零"。</b>{@code AgentConversationController} 仍逐条写着
 * {@code /agent/v1/conversations/**}，这是**有意保留**的，理由见
 * {@code AgentEngineSurfaceRouteTest} 的 allowlist：它在 platform 内嵌形态里不是 bean
 * （扫描根是 {@code org.ruoyi}），可达面是与它同形的
 * {@code AiEmbeddedAgentConversationConfiguration.ConversationSurface}（已在内部前缀下）；
 * 而把它也迁到内部前缀会与 {@code ConversationSurface} 产生**同一路径的重复 handler**，
 * 反而更差。新增判据见 {@code AgentEngineSurfaceRouteTest}，它用 allowlist
 * 把"允许的例外"与"禁止的新增"分开，不做比实际覆盖范围更强的声称。
 */
@RestController
@RequestMapping("/internal/ai/v1")
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentChatController {

    private final AgentChatService agentChatService;
    private final AgentProperties agentProperties;

    /**
     * 对话 SSE 流。scope = {@code run.stream}（C13.4 登记，V5 已播种 {@code ai:run:stream}）。
     *
     * <p>身份桥：{@code AgentChatServiceImpl} 在入口把 {@code UserContext.getUserId()}
     * 读成局部变量，因此"桥接 → 调用 → 清理"是安全的作用域。
     */
    @GetMapping(value = "/agent/v1/chat", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chat(@RequestParam @ChatQuestion String question,
                           @RequestParam(required = false) String conversationId) {
        ExecutionPrincipal principal = AgentEngineSurface.requireScope("run.stream");
        SseEmitter emitter = new SseEmitter(agentProperties.getSseTimeoutMs());
        AgentEngineSurface.asUser(principal, () -> {
            agentChatService.streamChat(question, conversationId, emitter);
            return null;
        });
        return emitter;
    }

    /**
     * 审批确认 SSE。scope = {@code run.approve}（C13.4 登记，V6 已播种 {@code ai:run:approve}）。
     *
     * <p>刻意<b>不</b>复用 {@code run.stream}：审批是与"看流"不同的动作，
     * 复用等于把审批权扩散给只该看流的调用方。
     */
    @PostMapping(value = "/agent/v1/chat/confirm", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter confirm(@RequestBody ConfirmRequest requestParam) {
        ExecutionPrincipal principal = AgentEngineSurface.requireScope("run.approve");
        SseEmitter emitter = new SseEmitter(agentProperties.getSseTimeoutMs());
        AgentEngineSurface.asUser(principal, () -> {
            agentChatService.confirmPendingTool(requestParam.conversationId(), requestParam.messageId(),
                    requestParam.approved(), emitter);
            return null;
        });
        return emitter;
    }

    /**
     * 停止任务。scope = {@code run.cancel}（C13.4 登记，V5 已播种 {@code ai:run:cancel}）。
     *
     * <p>返回 {@link ApiEnvelope} 而不是 {@code Result}：见类注释的包络形状段。
     */
    @PostMapping("/agent/v1/stop")
    public ApiEnvelope<Void> stop(@RequestParam String taskId) {
        ExecutionPrincipal principal = AgentEngineSurface.requireScope("run.cancel");
        AgentEngineSurface.asUser(principal, () -> {
            agentChatService.stopTask(taskId);
            return null;
        });
        return ApiEnvelope.ok(null);
    }
}
