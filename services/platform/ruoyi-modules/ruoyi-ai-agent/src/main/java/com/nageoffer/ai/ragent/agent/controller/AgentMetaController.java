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
import com.nageoffer.ai.ragent.agent.controller.vo.AgentMetaVO;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog;
import com.nageoffer.ai.ragent.authorization.AiDeliveryPermitConfiguration;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.runtime.config.ConfigAuthorityUnavailable;
import com.nageoffer.ai.ragent.runtime.config.EngineModelAuthority;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 引擎探活与身份，前端进入聊天页先拉一次点亮徽标，绝不带密钥
 *
 * <p><b>路径必须落在内部前缀下（C13.2/C13.3 修正）。</b>与
 * {@link AgentChatController} 同一缺陷、同一修法：类级
 * {@code @RequestMapping("/internal/ai/v1")} + 方法级相对路径。原实现没有类级前缀，
 * 方法路径直接写 {@code /agent/v1/meta}，于是既<b>经网关必然 404</b>
 * （网关转送目标恒为 {@code base + "/internal/ai/v1" + subPath}），又**不在**
 * {@code AiInternalAccessBoundaryFilter} 的 {@code /internal/ai/v1/*} 关闭范围内 ——
 * 既不可达、也非"外部不可达"。
 *
 * <p><b>包络：整数 {@code code}。</b>本方法此前返回 {@code Result<AgentMetaVO>}，其
 * {@code code} 是字符串 {@code "0"}。网关 {@code AiGatewayClient.requireSingleJsonObject}
 * 只接受"单 JSON 对象 + <b>整数</b> code 等于 HTTP 状态"，字符串 code 会以
 * "missing envelope code" 收敛为 503。故改为 {@link ApiEnvelope}（{@code int code}）。
 *
 * <p><b>scope = {@code agent.execute}</b>（C13.4 登记，V6 已播种 {@code ai:agent:execute}）。
 * 该端点返回引擎能力清单、迭代上限与<b>当前模型名</b>，是"能否使用 Agent 引擎"的发现面；
 * 用"可执行"权限门控它，比新增一个只为读的权限行更保守（且不触碰
 * {@code P1CurrentAuthorizationTest:245/:252} 的护栏：旧 34 条逐项保留 + flow.* 4 条 = 38）。
 * （注：本条注释曾写作 {@code 26}、后写作 {@code 34} 并引用过期行号 205/209，均为过期信息；
 *   权威值见该测试 245/:252；该过期数字已造成误导，故按实测更正并保留说明。）
 *
 * <p><b>C13.6 / C1.4：{@code model} 字段是"第二权威"消费面。</b>
 * 原实现对外声明的模型名取自 {@code agentProperties.getChat().getModel()}，
 * 该值来自 YAML（{@code agent.chat.model}），正是 D02/C1.1 要求取消的
 * "YAML 在运行期压过数据库"形态。现在改为经 {@link EngineModelAuthority} 解析
 * <b>数据库不可变已发布版本</b>（{@code platform.ai_runtime_config_revision}，V15）：
 * 这是 D02 唯一运行权威。{@code AgentProperties} 仍保留 YAML 绑定，但只剩下两处合法用途
 * （显式初装导入与连接引导，C1.1），不再作为公开面的事实来源。
 *
 * <p><b>读不到权威时响亮拒绝，不回退 YAML（C1.1/C1.5-3）。</b>
 * {@link ConfigAuthorityUnavailable} 一律映射为 503
 * （{@link P04AiErrorCode#AUTHORIZATION_UNAVAILABLE}），不返回 YAML 模型名、
 * 不返回空串、不返回缓存旧值。否则"管理端撤权后前端仍显示旧模型"会静默成立，
 * 而这正是本契约要消灭的失效形态。
 *
 * <p>{@code maxIters} 仍取 {@code AgentProperties}：它是**引擎循环上限**（本次部署的运行
 * 参数），不是"用哪个模型"的配置事实，不属于 D02 要收敛的对象。
 */
@RestController
@RequestMapping("/internal/ai/v1")
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentMetaController {

    private static final Logger log = LoggerFactory.getLogger(AgentMetaController.class);

    /** 交付 permit 持有者缺席时只打一次 error，避免每个探活请求刷屏。 */
    private static final AtomicBoolean PERMIT_HOLDER_MISSING = new AtomicBoolean();

    private final AgentProperties agentProperties;
    private final AgentToolCatalog toolCatalog;
    private final EngineModelAuthority modelAuthority;

    /**
     * 撤权/租户屏障守卫。内嵌形态下它是 bean；用 setter 软取，
     * 与 {@code AgentOptionController} 同一口径：缺席时显式拒绝，不返回"看起来成功"的 200。
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
     * 引擎探活/身份。
     *
     * <p><b>交付回执必须回填</b>：网关的 GET 字节分支（{@code LocalAiGatewayClient:163-170}）
     * 在 200/206 时强制校验 {@code X-AI-Delivery-Permit} / {@code X-AI-Delivery-Operation}
     * 两个 UUID 头，缺失即 {@code delivery receipt missing} ⇒ 503。原先本方法直接返回
     * {@link ApiEnvelope}，网关因此把成功的探活收敛成 503（2026-10-19 VM 实测）。
     * 修法与同族 {@code AgentOptionController.reply} 逐句一致。
     */
    @GetMapping("/agent/v1/meta")
    public ApiEnvelope<AgentMetaVO> meta(HttpServletResponse response) {
        AgentEngineSurface.requireScope("agent.execute");
        boolean mcpConfigured = toolCatalog.mcpToolCount() > 0;
        // 能力清单随实况增删，否则会与 mcpConfigured 各说各话，前端只能自己对齐
        List<String> capabilities = new ArrayList<>(List.of("react", "knowledge-base"));
        if (mcpConfigured) {
            capabilities.add("mcp-tools");
        }
        String model = publishedModel();
        return reply("agent.execute", "tenant:agent-meta", new AgentMetaVO(
                "AgentScope ReAct",
                model,
                agentProperties.getMaxIters(),
                List.copyOf(capabilities),
                mcpConfigured ? "native + mcp" : "native",
                mcpConfigured), response);
    }

    /**
     * 交付 permit 协议入口：登记 permit 并把两个回执头写进响应。
     *
     * <p>逐句照 {@code AgentOptionController.reply}（含持有者缺席时的一次性 error 日志）：
     * 守卫缺席 ⇒ fail-closed 显式拒绝；持有者缺席 ⇒ 回执仍回填，交付不因释放件缺席而失效。
     */
    private <T> ApiEnvelope<T> reply(String action, String ref, T data, HttpServletResponse response) {
        if (revocations == null) {
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
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-AI-Delivery-Permit", operation.permitId());
            response.setHeader("X-AI-Delivery-Operation", operation.operationId());
            return ApiEnvelope.ok(data);
        } catch (RuntimeException e) {
            operation.close();
            throw e;
        }
    }

    /**
     * 解析权威模型名；不可得即拒绝。
     *
     * <p><b>刻意不把 {@code ConfigAuthorityUnavailable} 直接抛给容器。</b>
     * 它是 {@code ruoyi-ai-runtime} 的运行时异常，没有对应的异常解析器；
     * 直接抛出会以 500 落地，而契约语义是 <b>503 授权/权威不可用</b>（可重试的依赖失败），
     * 两者对客户端的重试决策完全不同。这里显式做映射，并在异常消息里保留原因分类
     * （供日志与证据使用）而不是把它塞进响应体（避免泄露租户是否存在发布版本）。
     */
    private String publishedModel() {
        try {
            return modelAuthority.requirePublished("agent.execute").modelId();
        } catch (ConfigAuthorityUnavailable unavailable) {
            throw new P04AiException(P04AiErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
    }
}
