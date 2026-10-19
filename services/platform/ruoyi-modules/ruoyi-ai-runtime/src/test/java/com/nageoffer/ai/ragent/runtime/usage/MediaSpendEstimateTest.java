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

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * F19:5 媒体计价扩展：单位 = 张数；复用同一信封/cap；与文本 token 估计不混加。
 */
@Tag("dev")
class MediaSpendEstimateTest {

    @Test
    void mediaEstimateUsesWorstCaseUnitCostAndKeepsEnvelopeBounds() {
        assertEquals(new BigDecimal("0.500000"),
                ProviderSpendEnvelope.estimateMedia("dashscope", 1, new BigDecimal("0.50")));
        assertEquals(new BigDecimal("1.000000"),
                ProviderSpendEnvelope.estimateMedia("dashscope", 2, new BigDecimal("0.50")));
        assertEquals(new BigDecimal("0.010000"),
                ProviderSpendEnvelope.estimateMedia("dashscope", 1, new BigDecimal("0.000001")));

        assertThrows(RuntimeException.class,
                () -> ProviderSpendEnvelope.estimateMedia("deepseek", 1, new BigDecimal("0.50")));
        assertThrows(RuntimeException.class,
                () -> ProviderSpendEnvelope.estimateMedia("dashscope", 0, new BigDecimal("0.50")));
        assertThrows(RuntimeException.class,
                () -> ProviderSpendEnvelope.estimateMedia("dashscope", 11, new BigDecimal("0.50")));
        assertThrows(RuntimeException.class,
                () -> ProviderSpendEnvelope.estimateMedia("dashscope", 1, BigDecimal.ZERO));
        assertThrows(RuntimeException.class,
                () -> ProviderSpendEnvelope.estimateMedia("dashscope", 1, new BigDecimal("5.01")));
    }

    @Test
    void closedMediaEnvelopeNeverTouchesDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);

        assertThrows(RuntimeException.class, () -> new ProviderSpendEnvelope(jdbc, tx, false, "test",
                new BigDecimal("2")).reserveMedia("dashscope", "wanx-test", 1, new BigDecimal("0.50")));
        assertThrows(RuntimeException.class, () -> new ProviderSpendEnvelope(jdbc, tx, true, "test",
                new BigDecimal("21")).reserveMedia("dashscope", "wanx-test", 1, new BigDecimal("0.50")));
        verifyNoInteractions(jdbc, tx);
    }

    @Test
    void textEstimateContractIsUnchanged() {
        assertEquals(new BigDecimal("0.030340"), ProviderSpendEnvelope.estimate("deepseek", 10, 1000));
        assertEquals(new BigDecimal("0.010000"), ProviderSpendEnvelope.estimate("dashscope", 10, 0));
    }

    @Test
    void mediaUnitsAreImageCountsNeverTokens() {
        assertEquals(1L, UsageLedgerService.mediaUnits(Map.of("images", 1)));
        assertEquals(3L, UsageLedgerService.mediaUnits(Map.of("images", 3)));
        assertNull(UsageLedgerService.mediaUnits(Map.of("total_tokens", 100)));
        assertNull(UsageLedgerService.mediaUnits(Map.of("images", -1)));
        assertNull(UsageLedgerService.mediaUnits(null));
    }
}
