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

import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.runtime.RunAccessService;
import com.nageoffer.ai.ragent.runtime.exec.RunExecution;
import com.nageoffer.ai.ragent.runtime.usage.EgressPolicy;

import java.util.Set;

/**
 * F14:0/1/3 的提供方调用边界：{@code agent.execute} 运行期授权 + Egress + 撤权屏障。
 *
 * <p>不复用 {@code AgentProviderBoundary} 的原因：该类把 {@code kb.read} 一并放进
 * 运行期 scope 集合（单 Agent 的检索面），而 Supervisor 的**子工具为空集**、没有知识库
 * 面。多要一个 scope 会让一个本不存在的权限缺口把合法运行拦死，也会让"子不得提升权限"
 * （§3.3）的边界说不清。这里只要求 {@code agent.execute}（D3）。
 *
 * <p>每次外部调用前：先 fence 自检（{@code commitAtomic}），再走当前授权、Egress 白名单，
 * 最后登记撤权 permit；返回的 {@link RevocationGuard.Operation} 必须在调用结束后关闭。
 */
public class SupervisorProviderBoundary {

    private final RunAccessService access;
    private final RevocationGuard revocation;
    private final EgressPolicy egress;

    public SupervisorProviderBoundary(RunAccessService access, RevocationGuard revocation, EgressPolicy egress) {
        this.access = access;
        this.revocation = revocation;
        this.egress = egress;
    }

    public RevocationGuard.Operation enter(RunExecution execution, String provider) {
        // fence 自检先于任何外部 I/O：旧 Worker 不得继续调用提供方。
        execution.guard().commitAtomic(() -> null);
        var principal = access.current(execution.run(), Set.of("agent.execute"));
        egress.requireAllowed(provider);
        return revocation.enter(principal, "agent.execute", "run:" + execution.runId());
    }
}
