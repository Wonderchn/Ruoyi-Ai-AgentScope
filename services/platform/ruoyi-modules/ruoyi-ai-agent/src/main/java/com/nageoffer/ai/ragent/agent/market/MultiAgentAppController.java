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

package com.nageoffer.ai.ragent.agent.market;

import com.nageoffer.ai.ragent.authorization.AiDeliveryPermitConfiguration;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * F14:2 多 Agent 应用市场的内层只读面（{@code /internal/ai/v1/multi-agent/public/**}）。
 *
 * <p><b>公开面是网关白名单，不是本类</b>：公开路径 {@code GET /api/ai/v1/multi-agent/public/**}
 * 由 {@code AiGatewayController.ROUTES} 登记（动作复用 {@code agent.execute}，D3：canonical
 * 零新增）；内层 handler 对外 404。逐条登记、不用通配，与同族 {@code /agent/v1/agents} 一致。
 *
 * <p><b>授权复核不靠网关</b>（C3.1）：内层再取主体并复核 {@code agent.execute}
 * （缺主体 401、缺 scope 403）。同族 helper {@code AgentEngineSurface} 是包内私有，
 * 这里按同一条边界逐句复核（判据见 {@code MultiAgentAppControllerTest}）。
 *
 * <p><b>交付 permit 协议</b>：两条 GET 在网关走字节分支（{@code LocalAiGatewayClient}
 * 在 200/206 强制校验两个 UUID 回执头，缺失即 503）。成功路径铸造并回填
 * {@code X-AI-Delivery-Permit}/{@code X-AI-Delivery-Operation}；失败路径（如 404）
 * 在铸许可<b>之前</b>返回，不留 ACTIVE 许可。
 *
 * <p><b>404 语义</b>：不存在/跨租户/私有/停用/软删一律 404 + 同一文案「应用不存在」，
 * 不区分「不存在」与「存在但不可见」，不泄露存在性。
 */
@RestController
@RequestMapping("/internal/ai/v1")
public class MultiAgentAppController {

    private final MultiAgentAppService market;

    /** 撤权/租户屏障守卫；缺席时显式拒绝（不返回"看起来成功"的 200）。 */
    private RevocationGuard revocations;

    /** 交付 permit 请求级持有者；成功路径的 permit 交给它在 afterCompletion 单点释放。 */
    private AiDeliveryPermitConfiguration.DeliveryPermitHolder deliveryPermits;

    public MultiAgentAppController(MultiAgentAppService market) {
        this.market = market;
    }

    @Autowired(required = false)
    public void setRevocations(RevocationGuard revocations) {
        this.revocations = revocations;
    }

    @Autowired(required = false)
    public void setDeliveryPermits(AiDeliveryPermitConfiguration.DeliveryPermitHolder deliveryPermits) {
        this.deliveryPermits = deliveryPermits;
    }

    /** 公开列表：同租户、跨用户、只读；动作复用 {@code agent.execute}。 */
    @GetMapping("/multi-agent/public/search")
    public ResponseEntity<ApiEnvelope<MultiAgentAppService.AppPage>> search(
            @RequestParam(value = "current", required = false) Integer current,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "keyword", required = false) String keyword) {
        ExecutionPrincipal principal = requireScope("agent.execute");
        MultiAgentAppService.AppPage page = market.search(principal.tenantId(), keyword, current, size);
        return reply("agent.execute", "multi-agent:public", page);
    }

    /** 公开详情：与列表同一组可见性谓词；不可见时 404 同一文案。 */
    @GetMapping("/multi-agent/public/{uuid}")
    public ResponseEntity<ApiEnvelope<?>> detail(@PathVariable("uuid") String uuid) {
        ExecutionPrincipal principal = requireScope("agent.execute");
        MultiAgentAppService.AppView view = market.getPublic(principal.tenantId(), uuid);
        if (view == null) {
            return ResponseEntity.status(404)
                    .header("Cache-Control", "no-store")
                    .body(ApiEnvelope.error(404, "应用不存在", "RESOURCE_NOT_FOUND_OR_FORBIDDEN"));
        }
        return asWildcard(reply("agent.execute", "multi-agent:public:" + uuid, view));
    }

    /**
     * 详情必须能同时返回成功体（{@code ApiEnvelope<AppView>}）与 404 体
     * （{@code ApiEnvelope<Map>}），所以对外签名是通配符；成功路径仍复用 {@link #reply}。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ResponseEntity<ApiEnvelope<?>> asWildcard(ResponseEntity<? extends ApiEnvelope<?>> response) {
        return (ResponseEntity) response;
    }

    /** 与 {@code AgentEngineSurface.requireScope} 同一判据（该 helper 为 controller 包私有）。 */
    private static ExecutionPrincipal requireScope(String action) {
        ExecutionPrincipal principal = PrincipalContext.get();
        if (principal == null) {
            throw new P04AiException(P04AiErrorCode.AUTH_REQUIRED);
        }
        if (!principal.hasScope(action)) {
            throw new P04AiException(P04AiErrorCode.FORBIDDEN);
        }
        return principal;
    }

    /** 交付 permit 协议入口（逐句照 {@code AgentOptionController.reply}）。 */
    private <T> ResponseEntity<ApiEnvelope<T>> reply(String action, String ref, T data) {
        if (revocations == null) {
            throw new ServiceException("delivery permit unavailable");
        }
        var operation = revocations.enter(PrincipalContext.require(), action, ref);
        if (deliveryPermits != null) {
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
