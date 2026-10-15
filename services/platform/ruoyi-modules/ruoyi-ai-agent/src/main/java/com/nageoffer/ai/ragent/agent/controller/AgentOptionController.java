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

import com.nageoffer.ai.ragent.authorization.AiDeliveryPermitConfiguration;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentOptionVO;
import com.nageoffer.ai.ragent.rag.service.AgentOptionReadService;
import com.nageoffer.ai.ragent.runtime.config.ConfigAuthorityUnavailable;
import com.nageoffer.ai.ragent.runtime.config.EngineModelAuthority;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 用户端智能体发现面（F09:4-A）：{@code GET /internal/ai/v1/agent/v1/agents}。
 *
 * <p><b>公开面是网关白名单，不是本类</b>：内层 handler 落在
 * {@code /internal/ai/v1/agent/v1/agents}（{@code AiInternalAccessBoundaryFilter} 对外 404），
 * 公开路径 {@code GET /api/ai/v1/agent/v1/agents} 由 {@code AiGatewayController.ROUTES}
 * 登记（动作复用 {@code agent.execute} → {@code ai:agent:execute}，
 * {@code AiCanonicalAction:55}；**不新增 canonical 动作、不新增权限行、不新增迁移**）。
 * 逐条登记、不用通配，与同族 {@code /agent/v1/meta} 一致。
 *
 * <p><b>为什么内层还要复核 scope</b>：C3.1 要求"功能权限 + 动作 scope + 资源授权 + 租户边界"
 * 四层同时成立；内嵌形态下内层主体由本地桥接构造，"网关已校验"不构成内层免检的理由。
 * 这里经同包 {@link AgentEngineSurface#requireScope(String)} 取主体并复核（缺主体 401、
 * 缺 scope 403），与 {@code AgentMetaController:89} 同一条边界路径。
 *
 * <p><b>发布权威（D02/C1）经 {@code ObjectProvider} 软取</b>：本端点对外声明"能否执行"，
 * 必须以数据库已发布运行版本为权威；但 {@link EngineModelAuthority} 的 bean 只在
 * {@code AiEmbeddedAgentEngineConfiguration.EngineEnabled}（{@code ragent.engine.type=agent}
 * 门内）提供，而本控制器所在的内嵌装配（{@code AiEmbeddedAgentCatalogConfiguration}）只过
 * {@code ai.integration.enabled} 一道门。<b>若把权威做成必填构造依赖，"引擎关"的形态
 * 会在容器启动期直接失败</b>——那是比"端点不可用"更坏的失效形态。故：
 * <ul>
 *   <li>bean 在场 ⇒ 走 {@link EngineModelAuthority#requirePublished(String)}，
 *       {@link ConfigAuthorityUnavailable} 映射为 503 {@code AUTHORIZATION_UNAVAILABLE}
 *       （照 {@code AgentMetaController:115-121}，不返回 YAML 值、不返回空串）；</li>
 *   <li>bean 缺席（引擎未装配）⇒ 同样是 503 {@code AUTHORIZATION_UNAVAILABLE}，
 *       <b>不静默返回 200</b>：无运行权威时"当前可否执行"不可得，返回成功等于编造可用性。</li>
 * </ul>
 *
 * <p><b>交付 permit 协议（照 {@code AgentProfileController.reply():121-145} 逐句）</b>：
 * 网关的字节分支条件含 {@code servletResponse != null && method.equals("GET")} ⇒ 任何带 servlet
 * 响应的 GET 都走 {@code client.forwardBytes(...)}，而 {@code LocalAiGatewayClient:163-170}
 * 在 200/206 时强制校验 {@code X-AI-Delivery-Permit} / {@code X-AI-Delivery-Operation}
 * 两个 UUID 回执头，缺失即 {@code delivery receipt missing} ⇒ 503。
 * 故本 handler 经 {@link #reply(String, String, Object)} 登记交付 permit 并回填两个头。
 * 手工构造（单元测试）或切片里没有守卫时它显式拒绝（不发"看起来成功"的 200）。
 */
@RestController
@RequestMapping("/internal/ai/v1")
@RequiredArgsConstructor
public class AgentOptionController {

    private static final Logger log = LoggerFactory.getLogger(AgentOptionController.class);

    /**
     * 一次性告警开关：交付 permit 持有者缺席时只打一次 error，避免每个读请求刷屏。
     * 照 {@code AgentProfileController:83} 的 {@code PERMIT_HOLDER_MISSING} 写法。
     */
    private static final AtomicBoolean PERMIT_HOLDER_MISSING = new AtomicBoolean();

    private final AgentOptionReadService agentOptionReadService;

    /**
     * 发布权威：见类注释"为什么经 {@code ObjectProvider} 软取"。
     * 用 provider 而不是直接依赖，是为了让"引擎关"的部署仍能正常启动（届时本端点 503）。
     */
    private final ObjectProvider<EngineModelAuthority> modelAuthorityProvider;

    /**
     * 撤权/租户屏障守卫。内嵌形态下它是 bean（{@code AiEmbeddedRagConfiguration} 的
     * {@code @Bean defaultRevocationGuard(...)}）；用 {@code @Autowired(required=false)} setter：
     * 不改构造签名，切片里没有守卫也不炸（缺席时由 {@link #reply} 显式拒绝，不静默放行）。
     */
    private RevocationGuard revocations;

    /** 交付 permit 请求级持有者：成功路径的 permit 交给它在 afterCompletion 单点释放。 */
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
     * 用户端智能体列表（最小投影）。
     *
     * <p>顺序即门槛：先主体/scope（401/403），再发布权威（503），然后才读数据；
     * 回执由 {@link #reply} 在返回前铸造。前三层不成立时数据面一次都不触碰；
     * 守卫/持有者缺席时不铸回执——守卫缺席即显式拒绝，绝不返回"看起来成功"的 200。
     */
    @GetMapping("/agent/v1/agents")
    public ResponseEntity<ApiEnvelope<List<AgentOptionVO>>> list() {
        AgentEngineSurface.requireScope("agent.execute");
        requirePublishedModel();
        return reply("agent.execute", "tenant:agent-options", agentOptionReadService.listOptions());
    }

    /**
     * 发布权威在场性与可得性检查；不可得即 503 拒绝。
     *
     * <p><b>刻意不把 {@code ConfigAuthorityUnavailable} 直接抛给容器</b>：它是
     * {@code ruoyi-ai-runtime} 的运行时异常，直接抛出会以 500 落地，而契约语义是
     * <b>503 授权/权威不可用</b>（可重试的依赖失败），两者对客户端的重试决策完全不同。
     * 与 {@code AgentMetaController.publishedModel()} 同一映射口径。
     */
    private void requirePublishedModel() {
        EngineModelAuthority authority = modelAuthorityProvider.getIfAvailable();
        if (authority == null) {
            // 引擎闭包未装配 ⇒ 没有任何已发布运行权威可用 ⇒ 与"读不到发布版本"同一语义（503）。
            throw new P04AiException(P04AiErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
        try {
            authority.requirePublished("agent.execute");
        } catch (ConfigAuthorityUnavailable unavailable) {
            throw new P04AiException(P04AiErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
    }

    /**
     * 交付 permit 协议入口：登记一个交付 permit 并把两个回执头放进响应，
     * 使网关的字节分支（GET 必经）能通过 {@code LocalAiGatewayClient:163-170} 的回执校验。
     * 逐句照 {@code AgentProfileController.reply():121-145}（含持有者缺席时的一次性 error 日志，
     * 该分支按原型保持"仍返回 200"——回执头由本方法回填，交付不因释放件缺席而失效）。
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
}
