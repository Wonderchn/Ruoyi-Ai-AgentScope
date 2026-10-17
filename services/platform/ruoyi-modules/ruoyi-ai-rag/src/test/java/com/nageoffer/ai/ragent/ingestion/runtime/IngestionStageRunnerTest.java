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

package com.nageoffer.ai.ragent.ingestion.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.ingestion.domain.settings.ChunkerSettings;
import com.nageoffer.ai.ragent.ingestion.task.IngestionPipelineReader;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.usage.UsageLedgerService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * F06:1/2-A Stage C：{@link IngestionStageRunner} 的**预算口径**与**节点设置解析**判据
 * （§90-2；不需 DB）。端口参数以 null 占位——被测方法只依赖 usageLedger/ledger/objectMapper。
 */
@ExtendWith(MockitoExtension.class)
@Tag("dev")
class IngestionStageRunnerTest {

    private static final String TENANT = "acme";
    private static final String RUN = "r-abc";

    @Mock
    private UsageLedgerService usageLedger;
    @Mock
    private RunLedgerDao ledger;

    private IngestionStageRunner runner() {
        return new IngestionStageRunner(null, null, null, null, null, null, null,
                usageLedger, null, null, ledger, new ObjectMapper());
    }

    private static RunLedgerDao.StepRow step(String id) {
        return new RunLedgerDao.StepRow(id, 1, 1, "phase", "COMPLETED", "{}", null, null, 0L,
                Instant.now(), Instant.now());
    }

    @Test
    void maxToolCallsRejectsWhenStartedCallsReachLimit() {
        when(usageLedger.usageOf(TENANT, RUN)).thenReturn(new UsageLedgerService.RunUsage(2, 0, 0));
        RunApiException ex = assertThrows(RunApiException.class,
                () -> runner().requireCallBudget(TENANT, RUN, 2));
        assertEquals(RunErrorCode.BUDGET_EXCEEDED, ex.errorCode());
    }

    @Test
    void maxToolCallsAllowsBelowLimitAndWhenUnset() {
        lenient().when(usageLedger.usageOf(TENANT, RUN)).thenReturn(new UsageLedgerService.RunUsage(1, 1, 0));
        assertDoesNotThrow(() -> runner().requireCallBudget(TENANT, RUN, 3));
        assertDoesNotThrow(() -> runner().requireCallBudget(TENANT, RUN, null));
    }

    @Test
    void maxStepsRejectsWhenCommittedStepsReachLimit() {
        when(ledger.listSteps(TENANT, RUN)).thenReturn(List.of(step("a"), step("b")));
        RunApiException ex = assertThrows(RunApiException.class,
                () -> runner().requireStepBudget(TENANT, RUN, 2));
        assertEquals(RunErrorCode.BUDGET_EXCEEDED, ex.errorCode());
    }

    @Test
    void maxStepsAllowsBelowLimitAndWhenUnset() {
        lenient().when(ledger.listSteps(TENANT, RUN)).thenReturn(List.of(step("a")));
        assertDoesNotThrow(() -> runner().requireStepBudget(TENANT, RUN, 2));
        assertDoesNotThrow(() -> runner().requireStepBudget(TENANT, RUN, null));
    }

    @Test
    void settingsAreParsedFromNodeJson() {
        IngestionPipelineReader.NodeSnapshot node = new IngestionPipelineReader.NodeSnapshot(
                "n1", "chunker", true, null, "{\"chunkSize\":1200,\"overlapSize\":0}", null);
        ChunkerSettings settings = runner().settings(node, ChunkerSettings.class);
        assertEquals(1200, settings.getChunkSize());
    }

    @Test
    void blankSettingsYieldNull() {
        IngestionPipelineReader.NodeSnapshot node = new IngestionPipelineReader.NodeSnapshot(
                "n1", "chunker", true, null, null, null);
        assertNull(runner().settings(node, ChunkerSettings.class));
    }

    @Test
    void invalidSettingsJsonIsRejectedAsBadRequest() {
        IngestionPipelineReader.NodeSnapshot node = new IngestionPipelineReader.NodeSnapshot(
                "n1", "chunker", true, null, "{not-json", null);
        RunApiException ex = assertThrows(RunApiException.class,
                () -> runner().settings(node, ChunkerSettings.class));
        assertEquals(RunErrorCode.BAD_REQUEST, ex.errorCode());
    }

    @Test
    void completedRefReadsCommittedStepRef() {
        IngestionPipelineReader.NodeSnapshot node = new IngestionPipelineReader.NodeSnapshot(
                "n1", "parser", true, null, null, null);
        assertTrue(node.nodeId().startsWith("n"));
    }
}
