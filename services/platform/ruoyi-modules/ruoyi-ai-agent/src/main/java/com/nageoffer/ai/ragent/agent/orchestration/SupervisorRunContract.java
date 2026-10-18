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
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.ingest.SyntheticChatGateway;
import com.nageoffer.ai.ragent.runtime.RunAccessService;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.RuntimeActionContract;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.dto.AdmissionRequest;

import java.util.Set;

/**
 * F14:0/1/3 的受理契约：派发键 {@code agent.supervisor}，授权复用 {@code agent.execute}。
 *
 * <p><b>为什么不是 canonical 动作（D2/D3）。</b>canonical 表是共享面，且网关只按
 * {@code POST /runs} 的 {@code run.submit} 放行；把 Supervisor 做成 RuntimeActionContract
 * 就能搭上同一条受理事务（幂等/预算/RunWorker 派发），新增动作表项只会扩大权限面而没有
 * 对应需求。运行期授权在 {@link SupervisorProviderBoundary} 里以 {@code agent.execute}
 * 逐次复核。
 *
 * <p><b>冻结输入（§2.1）。</b>顶层仅 {@code text}/{@code agents}；{@code agents} 2–3 个、
 * alias {@code [a-z][a-z0-9_-]{1,15}} 唯一、instruction 非空 ≤1024；{@code resourceRefs}
 * 必须为空（没有知识库/文档授权面）；{@code agentVersion=supervisor-v1}。
 */
public class SupervisorRunContract implements RuntimeActionContract {

    private final P3Properties properties;
    private final SupervisorLedger ledger;
    private final RunLedgerDao runs;
    private final RunAccessService access;

    public SupervisorRunContract(P3Properties properties, SupervisorLedger ledger, RunLedgerDao runs,
                                 RunAccessService access) {
        this.properties = properties;
        this.ledger = ledger;
        this.runs = runs;
        this.access = access;
    }

    @Override
    public String action() {
        return SupervisorProtocol.ACTION;
    }

    @Override
    public String executionVersion() {
        return SupervisorProtocol.VERSION;
    }

    @Override
    public void validate(ExecutionPrincipal principal, AdmissionRequest request) {
        validateShape(request);
        // D3：Supervisor 的运行期授权复用 agent.execute；缺权限即 403，先于任何写入。
        access.resources().requireFunction(RunAccessService.scoped(principal, Set.of("agent.execute")),
                "agent.execute", "run:new");
    }

    /**
     * 形状白名单（纯函数）：供受理校验与测试直接调用。
     *
     * <p>未知字段、错误 action/agentVersion、非空 resourceRefs、名册越界全部 400。
     */
    public static void validateShape(AdmissionRequest request) {
        if (request == null || !SupervisorProtocol.ACTION.equals(request.action())) {
            throw bad("action must be " + SupervisorProtocol.ACTION);
        }
        if (!SupervisorProtocol.VERSION.equals(request.agentVersion())) {
            throw bad("agentVersion must be " + SupervisorProtocol.VERSION);
        }
        if (request.resourceRefs() != null && !request.resourceRefs().isEmpty()) {
            throw bad("supervisor resourceRefs must be empty");
        }
        SupervisorProtocol.validateInput(request.input());
    }

    /**
     * 受理事务内的唯一绑定行（§2.2）：恰一行 {@code ai_agent_run}。
     *
     * <p>真实模式下模型名来自发布权威（C1.2 第 1 行），权威缺位即拒绝受理；
     * 合成模式（{@code p3.synthetic-model=true}）写标明的合成绑定，与执行器
     * {@link SupervisorModelCall#bound} 同判据，避免兼容门两侧不一致。
     */
    @Override
    public void onAdmitted(ExecutionPrincipal principal, String runId, AdmissionRequest request) {
        var run = runs.findRun(principal.tenantId(), runId).orElseThrow(
                () -> new RunApiException(RunErrorCode.INTERNAL_ERROR, "run readback failed"));
        if (properties.isSyntheticModel()) {
            ledger.admitted(run, request, SyntheticChatGateway.syntheticBinding(run.tenantId(), run.runId()).modelId());
            return;
        }
        var authority = modelAuthority;
        if (authority == null) {
            throw new com.nageoffer.ai.ragent.runtime.config.ConfigAuthorityUnavailable(
                    "engine model authority is not wired; refusing to record an admitted model");
        }
        ledger.admitted(run, request, authority.requirePublished(action()).modelId());
    }

    private com.nageoffer.ai.ragent.runtime.config.EngineModelAuthority modelAuthority;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void configureModelAuthority(com.nageoffer.ai.ragent.runtime.config.EngineModelAuthority authority) {
        this.modelAuthority = authority;
    }

    private static RunApiException bad(String message) {
        return new RunApiException(RunErrorCode.BAD_REQUEST, message);
    }
}
