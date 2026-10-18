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

package com.nageoffer.ai.ragent.agent.orchestration;

import com.nageoffer.ai.ragent.agent.runtime.P3Properties;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.ingest.ChatGateway;
import com.nageoffer.ai.ragent.ingest.SyntheticChatGateway;
import com.nageoffer.ai.ragent.runtime.CanonicalJson;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.config.ConfigAuthorityUnavailable;
import com.nageoffer.ai.ragent.runtime.config.RunConfigBinding;
import com.nageoffer.ai.ragent.runtime.config.RunConfigBindingPort;
import com.nageoffer.ai.ragent.runtime.exec.RunExecution;
import com.nageoffer.ai.ragent.runtime.usage.UsageLedgerService;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * F14:0/1/3 的 run 绑定模型调用：父/子共用同一份受理时固定的发布版本。
 *
 * <p>每次调用的固定顺序（与单 Agent 先例同构）：
 * <ol>
 *   <li>取消检查（持久取消信号，不再外呼）；</li>
 *   <li>已结算调用检查（{@code usage.hasCall} → NEEDS_RECONCILIATION，不自动重放）；</li>
 *   <li>{@code spendStep}（max_steps 预算）；</li>
 *   <li>token 预算剩余检查；transcript 长度检查；</li>
 *   <li>边界（{@code agent.execute} + Egress + 撤权 permit）；</li>
 *   <li>{@code startCall → provider → settle → addTokens}；</li>
 *   <li>取消/未决用量检查后返回原始回复。</li>
 * </ol>
 *
 * <p><b>合成分支仅作确定性夹具（D10）。</b>{@code p3.synthetic-model=true} 时绑定走
 * {@link SyntheticChatGateway#syntheticBinding}，回复由执行器给出的确定性 JSON supplier 提供；
 * 它**不是**真实模型证据。
 */
public class SupervisorModelCall {

    /** 一次调用的事实（步骤提交由执行器按 route/child/final 语义完成）。 */
    public record RawReply(String content, String callId, String provider, String model, String usageJson) {
    }

    private final ChatGateway gateway;
    private final UsageLedgerService usage;
    private final SupervisorLedger ledger;
    private final SupervisorProviderBoundary boundary;
    private final P3Properties properties;
    private final RunConfigBindingPort bindingPort;

    public SupervisorModelCall(ChatGateway gateway, UsageLedgerService usage, SupervisorLedger ledger,
                               SupervisorProviderBoundary boundary, P3Properties properties,
                               RunConfigBindingPort bindingPort) {
        this.gateway = gateway;
        this.usage = usage;
        this.ledger = ledger;
        this.boundary = boundary;
        this.properties = properties;
        this.bindingPort = bindingPort;
    }

    /**
     * 该 run **受理时固定**的发布版本（C1.2）。
     *
     * <p>合成模式用标明的合成绑定（合成的 run 不绑发布版本；与单 Agent 同判据）；
     * 真实模式一律走端口、失败即拒（{@link ConfigAuthorityUnavailable}）。
     */
    public RunConfigBinding bound(RunExecution execution) {
        if (properties.isSyntheticModel()) {
            return SyntheticChatGateway.syntheticBinding(execution.tenantId(), execution.runId());
        }
        if (bindingPort == null) {
            throw new ConfigAuthorityUnavailable("run config binding port is not wired");
        }
        return bindingPort.requireBoundRevision(execution.tenantId(), execution.runId());
    }

    public String model(RunConfigBinding binding) {
        return gateway.model(binding);
    }

    public String provider(RunConfigBinding binding) {
        return gateway.provider(binding);
    }

    /**
     * 执行一次受控模型调用。
     *
     * @param invalidReplyCode 提供方返回非 stop 时收敛的协议错误码（父/子各不同）
     * @param syntheticReply   合成夹具的确定性 JSON（仅在 {@code p3.synthetic-model=true} 时使用）
     */
    public RawReply call(RunExecution execution, String stepId, String systemPrompt, String userPrompt,
                         int maxOutputTokens, String invalidReplyCode, Supplier<String> syntheticReply) {
        var run = execution.run();
        var guard = execution.guard();
        if (guard.isCancelRequested()) {
            throw new SupervisorProtocol.Suspension("CANCELLED");
        }
        if (usage.hasCall(run.tenantId(), run.runId(), stepId)) {
            throw new SupervisorProtocol.Suspension("NEEDS_RECONCILIATION");
        }
        RunConfigBinding binding = bound(execution);
        String model = gateway.model(binding);
        String provider = gateway.provider(binding);
        boolean synthetic = properties.isSyntheticModel();
        if (synthetic && !SyntheticChatGateway.SYNTHETIC_PROVIDER.equals(provider)) {
            throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT,
                    "synthetic supervisor requires the explicit synthetic provider");
        }
        ledger.spendStep(guard);
        var current = ledger.binding(run);
        int remaining = current.maxTokens() - current.tokensUsed();
        if (remaining <= 0) {
            throw new RunApiException(RunErrorCode.BUDGET_EXCEEDED);
        }
        if (userPrompt.length() > SupervisorProtocol.MAX_TRANSCRIPT) {
            throw new RunApiException(RunErrorCode.BUDGET_EXCEEDED);
        }
        List<ChatMessage> prompt = List.of(ChatMessage.system(systemPrompt), ChatMessage.user(userPrompt));
        RevocationGuard.Operation operation = boundary.enter(execution, provider);
        String call;
        try {
            call = guard.commitAtomic(() -> usage.startCall(run.tenantId(), run.runId(), execution.attempt(),
                    stepId, UsageLedgerService.KIND_CHAT, provider, model, CanonicalJson.sha256(userPrompt)));
        } catch (RuntimeException ex) {
            operation.close();
            throw ex;
        }
        ChatGateway.ChatResult result;
        try {
            if (synthetic) {
                String content = syntheticReply.get();
                result = new ChatGateway.ChatResult(content, "synthetic-" + call,
                        Map.of("synthetic", true, "total_tokens", 10), "stop");
            } else {
                result = gateway.stream(binding, prompt, Math.min(maxOutputTokens, remaining), delta -> {
                });
            }
        } catch (RuntimeException ex) {
            boolean cancelled = guard.isCancelRequested();
            guard.commitAtomic(() -> {
                usage.markUnknown(run.tenantId(), call);
                return null;
            });
            throw new SupervisorProtocol.Suspension(cancelled ? "CANCELLED" : "NEEDS_RECONCILIATION");
        } finally {
            operation.close();
        }
        var response = result;
        guard.commitAtomic(() -> {
            usage.settle(run.tenantId(), call, response.providerRequestId(), response.usageRaw());
            return null;
        });
        if (usage.unresolved(run.tenantId(), run.runId(), stepId)) {
            throw new SupervisorProtocol.Suspension("NEEDS_RECONCILIATION");
        }
        Object total = response.usageRaw() == null ? null : response.usageRaw().get("total_tokens");
        if (!(total instanceof Number number) || number.intValue() < 0
                || number.doubleValue() != number.intValue()) {
            throw new SupervisorProtocol.Suspension("NEEDS_RECONCILIATION");
        }
        // 已消耗的 token 先入账，再看取消：取消不得把已发生的用量记成 0。
        guard.commitAtomic(() -> {
            ledger.addTokens(guard, ((Number) total).intValue());
            return null;
        });
        if (guard.isCancelRequested()) {
            throw new SupervisorProtocol.Suspension("CANCELLED");
        }
        if (!"stop".equals(response.finishReason())) {
            throw new SupervisorProtocol.Violation(invalidReplyCode);
        }
        return new RawReply(response.content(), call, provider, model, json(response.usageRaw()));
    }

    private static String json(Object value) {
        try {
            return CanonicalJson.strictMapper().writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new RunApiException(RunErrorCode.INTERNAL_ERROR, "usage serialization failed");
        }
    }
}
