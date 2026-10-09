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

package com.nageoffer.ai.ragent.agent.runtime;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.ingest.ChatGateway;
import com.nageoffer.ai.ragent.ingest.SyntheticChatGateway;
import com.nageoffer.ai.ragent.runtime.RunAccessService;
import com.nageoffer.ai.ragent.runtime.config.ConfigAuthorityUnavailable;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.dto.AdmissionRequest;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R12 卡5 · F-W5-1 第二缺口：受理侧绑定行必须写"执行期将使用的同一份绑定"。
 *
 * <p><b>为什么必须有这一条。</b>{@code AgentRunExecutor.bound()} 在 {@code p3.synthetic-model=true}
 * （无真实 provider 的验收形态）下走 {@link SyntheticChatGateway#syntheticBinding}，而
 * {@code AgentContract.onAdmitted} 原先**恒**写已发布版本模型 ⇒ 执行第一步的兼容门
 * （{@code AgentLedger.isCompatible}）两侧不一致 ⇒ 每个真实提交都在
 * {@code AGENT_CHECKPOINT_INCOMPATIBLE} 被挡死（W5 夹具链手写 {@code synthetic-echo}
 * 正是绕过了这一不对称）。修复后两侧同判据；真实模式（synthetic=false）保持
 * "发布权威缺位即拒绝"不变。
 */
@Tag("dev")
class AgentContractAdmittedBindingTest {

    private static RunRecord run() {
        return new RunRecord("t1", "r1", "platform:t1:1001", "1001", "agent.run", "RUNNING", null, "key",
                "{}", "{}", "p3-core-v1", 1, 1, "[]", 1, 1, 1, 1, null, null, null, null, null, null, null, null, null);
    }

    private static ExecutionPrincipal principal() {
        return new ExecutionPrincipal("t1", "1001", "platform:t1:1001", 1, 1,
                Set.of("agent.execute"), "jti", "test", 0, Long.MAX_VALUE);
    }

    @Test
    @DisplayName("合成模式：受理写绑定行用合成绑定模型（与执行器 bound 同判据），不要求发布权威")
    void syntheticModeRecordsSyntheticBindingModel() {
        P3Properties properties = new P3Properties();
        properties.setSyntheticModel(true);
        AgentLedger ledger = mock(AgentLedger.class);
        RunLedgerDao runs = mock(RunLedgerDao.class);
        when(runs.findRun("t1", "r1")).thenReturn(Optional.of(run()));
        AgentContract contract = new AgentContract(properties, ledger, runs,
                mock(RunAccessService.class), mock(ChatGateway.class));
        AdmissionRequest request = mock(AdmissionRequest.class);

        assertThatCode(() -> contract.onAdmitted(principal(), "r1", request)).doesNotThrowAnyException();

        verify(ledger).admitted(eq(run()), eq(request),
                eq(SyntheticChatGateway.syntheticBinding("t1", "r1").modelId()));
    }

    @Test
    @DisplayName("真实模式：发布权威缺位仍拒绝受理（原语义不变）")
    void realModeStillRequiresAuthority() {
        P3Properties properties = new P3Properties();
        properties.setSyntheticModel(false);
        RunLedgerDao runs = mock(RunLedgerDao.class);
        when(runs.findRun("t1", "r1")).thenReturn(Optional.of(run()));
        AgentContract contract = new AgentContract(properties, mock(AgentLedger.class), runs,
                mock(RunAccessService.class), mock(ChatGateway.class));

        assertThatThrownBy(() -> contract.onAdmitted(principal(), "r1", mock(AdmissionRequest.class)))
                .isInstanceOf(ConfigAuthorityUnavailable.class);
    }
}
