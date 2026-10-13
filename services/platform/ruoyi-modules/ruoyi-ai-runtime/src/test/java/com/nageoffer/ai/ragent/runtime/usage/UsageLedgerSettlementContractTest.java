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

package com.nageoffer.ai.ragent.runtime.usage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F23 op4 收口（P1-1A 第三包）：{@code UsageLedgerService} 结算/异常路径的定向判据。
 *
 * <p><b>为什么加这组判据。</b>op4 的裁决缺口是「真实 usage/提供方 requestId/
 * PENDING_RECONCILIATION」。真机证据面已分两条腿推进：{@code ai_provider_spend}
 * 侧由 R14-A1/C2 窗口取得真实 usage+requestId 逐键对账；而 {@code ai_model_call}
 * 侧的**结算状态机**（STARTED→SETTLED 恰一次；未知用量/缺回执不得按 0 结算；
 * provider id 碰撞转 PENDING_RECONCILIATION/PROVIDER_ID_COLLISION）此前**没有任何
 * 定向测试**（GAP-ASSESSMENT T-10；唯一相关的 {@code TerminalUsageTest} 只覆盖
 * 终态封口与待核对不释放）。本类把这些语义钉成可证伪判据（含变异击杀记录，
 * 见切片报告 {@code r17/f23res/}）。
 *
 * <p><b>口径。</b>断言分两层：①交给 SQL 的**参数**（state/usage/provider id 取值）；
 * ②交给 SQL 的**谓词**（{@code WHERE ... AND state='STARTED'} 幂等门、
 * 碰撞分支的 {@code PROVIDER_ID_COLLISION} 与提前返回）。判据对"Java 侧接线"
 * 敏感——这正是本类要防的漂移面；SQL 自身的条件更新语义由真机窗口清单覆盖。
 */
@Tag("dev")
class UsageLedgerSettlementContractTest {

    private static final String TENANT = "t-usage";
    private static final String CALL = "call-1";
    private static final String RUN = "r1";

    private JdbcTemplate jdbc;
    private UsageLedgerService ledger;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        ledger = new UsageLedgerService(jdbc);
    }

    /** 同一 provider requestId 的并发结算必须先取 advisory 锁（防重复计量竞态）。 */
    private void stubNoCollision() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(), any(), any(), any()))
                .thenReturn(false);
    }

    @Test
    @DisplayName("结算主路径：真实 usage + provider requestId → SETTLED，且更新带 STARTED 幂等门")
    void realUsageWithProviderRequestIdSettlesBehindStartedStateGuard() {
        stubNoCollision();

        ledger.settle(TENANT, CALL, "req-1",
                Map.of("prompt_tokens", 1000, "completion_tokens", 500, "total_tokens", 1500));

        verify(jdbc).queryForList(anyString(), eq("p2-usage:t-usage:req-1"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> usageJson = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(UsageLedgerService.STATE_SETTLED), eq("req-1"),
                usageJson.capture(), eq(TENANT), eq(CALL), eq(UsageLedgerService.STATE_STARTED));
        assertThat(sql.getValue())
                .contains("provider_request_id=?")
                .contains("state=?");
        assertThat(usageJson.getValue()).contains("1500");
    }

    @Test
    @DisplayName("未知用量不按 0 结算：usage=null → PENDING_RECONCILIATION 且不落 usage_raw")
    void unknownUsageNeverSettlesAsZero() {
        ledger.settle(TENANT, CALL, null, null);

        verify(jdbc, never()).queryForList(anyString(), anyString());
        verify(jdbc).update(anyString(), eq(UsageLedgerService.STATE_PENDING_RECONCILIATION),
                isNull(), isNull(), eq(TENANT), eq(CALL), eq(UsageLedgerService.STATE_STARTED));
    }

    @Test
    @DisplayName("非合成调用缺 provider requestId → PENDING_RECONCILIATION（不得当已结算计量）")
    void nonSyntheticUsageWithoutProviderRequestIdStaysPending() {
        stubNoCollision();

        ledger.settle(TENANT, CALL, "", Map.of("prompt_tokens", 10, "completion_tokens", 5));

        verify(jdbc).update(anyString(), eq(UsageLedgerService.STATE_PENDING_RECONCILIATION),
                any(), any(), eq(TENANT), eq(CALL), eq(UsageLedgerService.STATE_STARTED));
    }

    @Test
    @DisplayName("合成用量（无真实 provider 回执）→ SETTLED，且不经 provider id 锁")
    void syntheticUsageSettlesWithoutProviderRequestId() {
        ledger.settle(TENANT, CALL, null, Map.of("synthetic", true, "batchSize", 1));

        verify(jdbc, never()).queryForList(anyString(), anyString());
        verify(jdbc).update(anyString(), eq(UsageLedgerService.STATE_SETTLED), isNull(),
                any(), eq(TENANT), eq(CALL), eq(UsageLedgerService.STATE_STARTED));
    }

    @Test
    @DisplayName("provider requestId 碰撞 → PENDING_RECONCILIATION/PROVIDER_ID_COLLISION，且不做第二次结算")
    void providerRequestIdCollisionMarksPendingWithoutSettlement() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(), any(), any(), any()))
                .thenReturn(true);

        ledger.settle(TENANT, CALL, "req-dup", Map.of("total_tokens", 10));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(), eq(TENANT), eq(CALL));
        assertThat(sql.getValue())
                .contains("PENDING_RECONCILIATION")
                .contains("PROVIDER_ID_COLLISION");
        verify(jdbc, never()).update(anyString(), eq(UsageLedgerService.STATE_SETTLED),
                any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("终态结算=已确认调用用量之和（跨行累加，不是最后一行的值）")
    void terminalSettlementSumsConfirmedUnitsAcrossCalls() {
        when(jdbc.queryForList(anyString(), eq(TENANT), eq(RUN)))
                .thenReturn(List.of(Map.of("state", UsageLedgerService.STATE_SETTLED, "n", 2L)));
        when(jdbc.query(anyString(), any(RowMapper.class), eq(TENANT), eq(RUN)))
                .thenReturn(List.of("{\"prompt_tokens\":1000,\"completion_tokens\":500}",
                        "{\"total_tokens\":1}"));

        ledger.finalizeReservation(TENANT, RUN);

        // 折算：(1000+500+999)/1000 = 2；(1+999)/1000 = 1；两行合计 = 3
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(3L), eq(TENANT), eq(RUN));
        assertThat(sql.getValue()).contains("'SETTLED'").contains("'RESERVED'");
    }

    @Test
    @DisplayName("未决判定只认 STARTED/PENDING_RECONCILIATION；hasCall 认一切状态（防再自动调用）")
    void unresolvedCoversPendingReconciliationWhileHasCallSeesEveryState() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(TENANT), eq(RUN), eq("model")))
                .thenReturn(true);

        assertTrue(ledger.unresolved(TENANT, RUN, "model"));
        assertTrue(ledger.hasCall(TENANT, RUN, "model"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).queryForObject(sql.capture(), eq(Boolean.class), eq(TENANT), eq(RUN), eq("model"));
        assertThat(sql.getAllValues().get(0))
                .contains("'STARTED'")
                .contains("'PENDING_RECONCILIATION'");
        assertThat(sql.getAllValues().get(1)).doesNotContain("state IN");
    }
}
