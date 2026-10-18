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

import com.nageoffer.ai.ragent.agent.market.MultiAgentAppService;
import com.nageoffer.ai.ragent.agent.runtime.P3Properties;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.runtime.CanonicalJson;
import com.nageoffer.ai.ragent.runtime.RunAccessService;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.dto.AdmissionRequest;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F14:2/D2：Supervisor 受理事务内的 appId 复核。
 *
 * <p>判据（任一缺失都必须变红）：
 * <ul>
 *   <li><b>无 appId 旧行为不变</b>：不查市场表、不新增任何拒绝路径；</li>
 *   <li>可见应用 + 名册一致 ⇒ 照常写绑定行；</li>
 *   <li>应用不可见（不存在/跨租户/私有/停用/软删由服务层同一谓词覆盖）⇒ 404
 *       {@link RunErrorCode#RESOURCE_NOT_FOUND_OR_FORBIDDEN}，与市场 GET 同一文案；
 *       校验先于 run 读回与账本写入；</li>
 *   <li>名册不一致 ⇒ 400；</li>
 *   <li>appId 在场但市场服务未装配 ⇒ 503（fail-closed），不得静默放行或静默忽略；</li>
 *   <li>库内名册不可解析 ⇒ 500，不把它伪装成"应用不存在"。</li>
 * </ul>
 */
@Tag("dev")
class SupervisorRunContractAppIdTest {

    private static final String APP_ID = "0123456789abcdef0123456789abcdef";
    private static final String AGENTS = "[{\"name\":\"analyst\",\"instruction\":\"analyze\"},"
            + "{\"name\":\"critic\",\"instruction\":\"critique\"}]";
    private static final String MISMATCHED_AGENTS = "[{\"name\":\"analyst\",\"instruction\":\"other\"},"
            + "{\"name\":\"critic\",\"instruction\":\"critique\"}]";

    private static AdmissionRequest request(String agents) {
        String input = "{\"text\":\"q\",\"agents\":" + agents + ",\"appId\":\"" + APP_ID + "\"}";
        try {
            return CanonicalJson.strictMapper().readValue(
                    "{\"schemaVersion\":1,\"action\":\"agent.supervisor\",\"agentVersion\":\"supervisor-v1\","
                            + "\"input\":" + input + ",\"resourceRefs\":[]}",
                    AdmissionRequest.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static AdmissionRequest oldRequest() {
        try {
            return CanonicalJson.strictMapper().readValue(
                    "{\"schemaVersion\":1,\"action\":\"agent.supervisor\",\"agentVersion\":\"supervisor-v1\","
                            + "\"input\":{\"text\":\"q\",\"agents\":" + AGENTS + "},\"resourceRefs\":[]}",
                    AdmissionRequest.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ExecutionPrincipal principal() {
        return new ExecutionPrincipal("t1", "1001", "platform:t1:1001", 1, 1,
                Set.of("run.submit", "agent.execute"), "jti", "platform", 1, 9999999999L);
    }

    private static RunRecord run() {
        return new RunRecord("t1", "r1", "platform:t1:1001", "1001", "agent.supervisor", "QUEUED",
                "hash", "idem", "{}", "{}", "supervisor-v1", 1, 1, "[]", 1L, 2L, 0, 0L,
                null, null, null, null, null, null, Instant.EPOCH, null, null);
    }

    private static P3Properties synthetic() {
        P3Properties properties = new P3Properties();
        properties.setSyntheticModel(true);
        return properties;
    }

    @Test
    @DisplayName("无 appId：市场服务零交互，旧受理路径不变")
    void withoutAppIdKeepsLegacyBehavior() {
        MultiAgentAppService apps = mock(MultiAgentAppService.class);
        RunLedgerDao runs = mock(RunLedgerDao.class);
        when(runs.findRun("t1", "r1")).thenReturn(Optional.of(run()));
        SupervisorLedger ledger = mock(SupervisorLedger.class);
        SupervisorRunContract contract = new SupervisorRunContract(synthetic(), ledger, runs,
                mock(RunAccessService.class), apps);

        contract.onAdmitted(principal(), "r1", oldRequest());

        verifyNoInteractions(apps);
        verify(ledger).admitted(any(), any(), anyString());
    }

    @Test
    @DisplayName("可见应用 + 名册一致：受理照常写绑定行")
    void visibleAppWithMatchingRosterIsAccepted() {
        MultiAgentAppService apps = mock(MultiAgentAppService.class);
        when(apps.findVisible("t1", APP_ID))
                .thenReturn(Optional.of(new MultiAgentAppService.VisibleApp(APP_ID, AGENTS)));
        RunLedgerDao runs = mock(RunLedgerDao.class);
        when(runs.findRun("t1", "r1")).thenReturn(Optional.of(run()));
        SupervisorLedger ledger = mock(SupervisorLedger.class);
        SupervisorRunContract contract = new SupervisorRunContract(synthetic(), ledger, runs,
                mock(RunAccessService.class), apps);

        contract.onAdmitted(principal(), "r1", request(AGENTS));

        verify(apps).findVisible("t1", APP_ID);
        verify(ledger).admitted(any(), any(), anyString());
    }

    @Test
    @DisplayName("应用不可见：404 同一文案，且先于 run 读回与账本写入")
    void invisibleAppIsNotFoundBeforeAnyWrite() {
        MultiAgentAppService apps = mock(MultiAgentAppService.class);
        when(apps.findVisible("t1", APP_ID)).thenReturn(Optional.empty());
        RunLedgerDao runs = mock(RunLedgerDao.class);
        SupervisorLedger ledger = mock(SupervisorLedger.class);
        SupervisorRunContract contract = new SupervisorRunContract(synthetic(), ledger, runs,
                mock(RunAccessService.class), apps);

        RunApiException denied = assertThrows(RunApiException.class,
                () -> contract.onAdmitted(principal(), "r1", request(AGENTS)));

        assertEquals(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, denied.errorCode());
        assertEquals("应用不存在", denied.getMessage());
        verify(runs, never()).findRun(anyString(), anyString());
        verifyNoInteractions(ledger);
    }

    @Test
    @DisplayName("名册不一致：400，且零账本写入")
    void rosterMismatchIsBadRequest() {
        MultiAgentAppService apps = mock(MultiAgentAppService.class);
        when(apps.findVisible("t1", APP_ID))
                .thenReturn(Optional.of(new MultiAgentAppService.VisibleApp(APP_ID, AGENTS)));
        RunLedgerDao runs = mock(RunLedgerDao.class);
        SupervisorLedger ledger = mock(SupervisorLedger.class);
        SupervisorRunContract contract = new SupervisorRunContract(synthetic(), ledger, runs,
                mock(RunAccessService.class), apps);

        RunApiException mismatch = assertThrows(RunApiException.class,
                () -> contract.onAdmitted(principal(), "r1", request(MISMATCHED_AGENTS)));

        assertEquals(RunErrorCode.BAD_REQUEST, mismatch.errorCode());
        verifyNoInteractions(ledger);
    }

    @Test
    @DisplayName("appId 在场但市场服务未装配：503 fail-closed，不静默忽略")
    void appIdWithoutMarketServiceFailsClosed() {
        RunLedgerDao runs = mock(RunLedgerDao.class);
        SupervisorLedger ledger = mock(SupervisorLedger.class);
        SupervisorRunContract contract = new SupervisorRunContract(synthetic(), ledger, runs,
                mock(RunAccessService.class));

        RunApiException unavailable = assertThrows(RunApiException.class,
                () -> contract.onAdmitted(principal(), "r1", request(AGENTS)));

        assertEquals(RunErrorCode.DEPENDENCY_UNAVAILABLE, unavailable.errorCode());
        verifyNoInteractions(ledger);
    }

    @Test
    @DisplayName("库内名册不可解析：500，不伪装成 404")
    void unreadableStoredRosterIsInternalError() {
        MultiAgentAppService apps = mock(MultiAgentAppService.class);
        when(apps.findVisible("t1", APP_ID))
                .thenReturn(Optional.of(new MultiAgentAppService.VisibleApp(APP_ID, "not-a-roster")));
        RunLedgerDao runs = mock(RunLedgerDao.class);
        SupervisorLedger ledger = mock(SupervisorLedger.class);
        SupervisorRunContract contract = new SupervisorRunContract(synthetic(), ledger, runs,
                mock(RunAccessService.class), apps);

        RunApiException broken = assertThrows(RunApiException.class,
                () -> contract.onAdmitted(principal(), "r1", request(AGENTS)));

        assertEquals(RunErrorCode.INTERNAL_ERROR, broken.errorCode());
        verifyNoInteractions(ledger);
    }
}
