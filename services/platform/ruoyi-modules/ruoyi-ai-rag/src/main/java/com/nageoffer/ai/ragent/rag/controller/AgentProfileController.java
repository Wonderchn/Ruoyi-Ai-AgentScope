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

package com.nageoffer.ai.ragent.rag.controller;

import com.nageoffer.ai.ragent.authorization.AiDeliveryPermitConfiguration;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.rag.controller.request.AgentProfileSaveRequest;
import com.nageoffer.ai.ragent.rag.controller.request.AgentPromptSaveRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentProfileListVO;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentPromptConfigVO;
import com.nageoffer.ai.ragent.rag.service.AgentProfileAdminService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 智能体管理控制器
 *
 * <p>W4-9：本控制器只作为**内层** handler 存在（{@code /internal/ai/v1/agent-catalog/**}）。
 * 公开面是网关白名单（{@code AiGatewayController.ROUTES} 的 {@code /agent-catalog/**}），
 * 授权在网关按 scope 强制（{@code AiGatewayController:274-277}），且其中的目录动作还要过
 * **平台管理身份**第二道门（A2-ter），因此本层**不加** {@code @SaCheckPermission}
 * ——与 {@code AiResourceController} / {@code AgentChatController} 同形。
 *
 * <p>返回类型由 ragent {@code Result}（字符串 code）改为 {@link ApiEnvelope}（{@code int code}）：
 * 内嵌传输对非空响应体要求"单 JSON 对象 + 整数 code 与状态一致"
 * （{@code LocalAiGatewayClient:97/146/164-165/175}），保持 {@code Result} 会被 fail-closed。
 * 同族先例见 {@code AgentChatController:135}。
 *
 * <p><b>交付 permit 协议（W4-9 / task-19）</b>：网关的字节分支条件含
 * {@code servletResponse != null && method.equals("GET")}
 * （{@code AiGatewayController:362-363}）⇒ **任何带 servlet 响应的 GET** 都走
 * {@code client.forwardBytes(...)}，而 {@code LocalAiGatewayClient:163-170} 在 200/206 时
 * **强制校验** {@code X-AI-Delivery-Permit} / {@code X-AI-Delivery-Operation} 两个 UUID 回执头，
 * 缺失即 {@code delivery receipt missing} ⇒ 503。故本控制器的每个 handler 都经
 * {@link #reply(String, String, Object)} 登记交付 permit 并回填两个头。
 * 8 个 handler 统一走 {@code reply} 是安全的：{@code AgentProfileAdminServiceImpl} **不参与**
 * permit/撤权链（对 {@code RevocationGuard|permit|enter(|PrincipalContext} 零引用）、
 * 也就**不会 bump {@code ai_acl_epoch}**，因此不存在 {@code AiResourceController:190-197}
 * 记录的"写入后旧 aclVersion ⇒ 409 提交成功响应失败"那条风险。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/ai/v1/agent-catalog")
public class AgentProfileController {

    private static final Logger log = LoggerFactory.getLogger(AgentProfileController.class);

    /**
     * 一次性告警开关：交付 permit 持有者缺席时只打一次 error，避免每个 AI 读请求刷屏。
     * 照 {@code AiResourceController:95/:128-141} 的 {@code PERMIT_HOLDER_MISSING} 写法。
     */
    private static final AtomicBoolean PERMIT_HOLDER_MISSING = new AtomicBoolean();

    private final AgentProfileAdminService agentProfileAdminService;

    /**
     * 撤权/租户屏障守卫（W4-9 / task-19）。内嵌形态下它是 bean：
     * {@code AiEmbeddedRagConfiguration:172-176} 的 {@code @Bean defaultRevocationGuard(...)}
     * （外层 {@code ai.integration.enabled=true}、内层 {@code ai.integration.transport=local}；
     * 内嵌 profile 在 {@code application-embedded.yml:25} 设 transport=local），
     * 经 {@code AutoConfiguration.imports:2} 加载。
     * 用 {@code @Autowired(required=false)} setter：不改构造签名，切片里没有守卫也不炸
     * （缺席时由 {@link #reply} 显式拒绝，不静默放行）。
     */
    private RevocationGuard revocations;

    /**
     * 交付 permit 请求级持有者：成功路径的 permit 交给它在 afterCompletion 单点释放。
     * bean 由 {@code AiDeliveryPermitConfiguration}（{@code @AutoConfiguration}，**无门控**，
     * {@code :39-46} 的 {@code @Bean deliveryPermitHolder()}）提供，经
     * {@code AutoConfiguration.imports:13} 加载。
     */
    private AiDeliveryPermitConfiguration.DeliveryPermitHolder deliveryPermits;

    @Autowired(required = false)
    public void setRevocations(RevocationGuard revocations) {
        this.revocations = revocations;
    }

    @Autowired(required = false)
    public void setDeliveryPermits(AiDeliveryPermitConfiguration.DeliveryPermitHolder deliveryPermits) {
        this.deliveryPermits = deliveryPermits;
    }

    /**
     * 交付 permit 协议入口（W4-9）：登记一个交付 permit 并把两个回执头放进响应，
     * 使网关的字节分支（GET 必经）能通过 {@code LocalAiGatewayClient:163-170} 的回执校验。
     * 照 {@code AiResourceController:123-147} 同形；失败路径 {@code operation.close()} 幂等。
     */
    private <T> ResponseEntity<ApiEnvelope<T>> reply(String action, String ref, T data) {
        if (revocations == null) {
            // fail-closed：没有守卫就不能登记 permit ⇒ 明确拒绝，不返回"看起来成功"的 200。
            throw new ServiceException("delivery permit unavailable");
        }
        var operation = revocations.enter(PrincipalContext.require(), action, ref);
        if (deliveryPermits == null) {
            if (PERMIT_HOLDER_MISSING.compareAndSet(false, true)) {
                log.error("交付 permit 持有者未装配：成功路径的 permit 不会被释放"
                        + "（检查 ruoyi-ai-web 的 AutoConfiguration.imports 是否仍登记 "
                        + "AiDeliveryPermitConfiguration）action={} ref={}", action, ref);
            }
        } else {
            deliveryPermits.register(operation, operation.permitId(), operation.operationId());
        }
        try {
            return ResponseEntity.ok().header("Cache-Control", "no-store")
                    .header("X-AI-Delivery-Permit", operation.permitId())
                    .header("X-AI-Delivery-Operation", operation.operationId())
                    .body(ApiEnvelope.ok(data));
        } catch (RuntimeException e) {
            operation.close();
            throw e;
        }
    }

    /**
     * 查询智能体列表
     */
    @GetMapping("/agents")
    public ResponseEntity<ApiEnvelope<AgentProfileListVO>> list() {
        return reply("agent.list", "agent-catalog:list", agentProfileAdminService.list());
    }

    /**
     * 创建智能体
     */
    @PostMapping("/agents")
    public ResponseEntity<ApiEnvelope<String>> create(@RequestBody AgentProfileSaveRequest requestParam) {
        return reply("agent.write", "agent-catalog:create", agentProfileAdminService.create(requestParam));
    }

    /**
     * 更新智能体名称与描述
     */
    @PutMapping("/agents/{id}")
    public ResponseEntity<ApiEnvelope<Void>> update(@PathVariable String id,
                                                    @RequestBody AgentProfileSaveRequest requestParam) {
        agentProfileAdminService.update(id, requestParam);
        return reply("agent.write", "agent-catalog:" + id, null);
    }

    /**
     * 删除智能体
     */
    @DeleteMapping("/agents/{id}")
    public ResponseEntity<ApiEnvelope<Void>> delete(@PathVariable String id) {
        agentProfileAdminService.delete(id);
        return reply("agent.delete", "agent-catalog:" + id, null);
    }

    /**
     * 激活智能体，立即对全部会话生效
     */
    @PostMapping("/agents/{id}/activate")
    public ResponseEntity<ApiEnvelope<Void>> activate(@PathVariable String id) {
        agentProfileAdminService.activate(id);
        return reply("agent.activate", "agent-catalog:" + id, null);
    }

    /**
     * 查询该智能体的槽位配置，含槽位元数据与当前架构下的生效判定
     */
    @GetMapping("/agents/{id}/prompts")
    public ResponseEntity<ApiEnvelope<AgentPromptConfigVO>> prompts(@PathVariable String id) {
        return reply("agent.read", "agent-catalog:" + id + ":prompts",
                agentProfileAdminService.loadPrompts(id));
    }

    /**
     * 保存单个槽位，内容留空即恢复回落内置智能体
     */
    @PutMapping("/agents/{id}/prompts/{slotKey}")
    public ResponseEntity<ApiEnvelope<Void>> savePrompt(@PathVariable String id,
                                                        @PathVariable String slotKey,
                                                        @RequestBody AgentPromptSaveRequest requestParam) {
        agentProfileAdminService.savePrompt(id, slotKey, requestParam);
        return reply("agent.write", "agent-catalog:" + id + ":prompts:" + slotKey, null);
    }

    /**
     * 查询内置智能体的槽位内容，供「从默认复制」
     */
    @GetMapping("/agents/prompt-slots/{slotKey}/default")
    public ResponseEntity<ApiEnvelope<String>> defaultPrompt(@PathVariable String slotKey) {
        return reply("agent.read", "agent-catalog:slot:" + slotKey + ":default",
                agentProfileAdminService.defaultPrompt(slotKey));
    }
}
