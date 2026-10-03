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

package com.nageoffer.ai.ragent.runtime.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 状态机集合：终态不可 resume；RECOVERING/RETRY_WAIT/CANCEL_REQUESTED 可恢复。 */
class RunStatusTest {

    @Test
    void terminalSetIsExactlyThreeStates() {
        assertTrue(RunStatus.isTerminal(RunStatus.SUCCEEDED));
        assertTrue(RunStatus.isTerminal(RunStatus.FAILED));
        assertTrue(RunStatus.isTerminal(RunStatus.CANCELLED));
        assertFalse(RunStatus.isTerminal(RunStatus.RUNNING));
        assertFalse(RunStatus.isTerminal(RunStatus.WAITING_APPROVAL));
    }

    @Test
    void resumableIsNonTerminalWhitelist() {
        assertTrue(RunStatus.RESUMABLE.contains(RunStatus.RECOVERING));
        assertTrue(RunStatus.RESUMABLE.contains(RunStatus.RETRY_WAIT));
        assertTrue(RunStatus.RESUMABLE.contains(RunStatus.CANCEL_REQUESTED));
        assertFalse(RunStatus.RESUMABLE.contains(RunStatus.SUCCEEDED));
        assertFalse(RunStatus.RESUMABLE.contains(RunStatus.RUNNING));
    }

    @Test
    void claimableOnlyQueuedAndRetryWait() {
        assertTrue(RunStatus.CLAIMABLE.contains(RunStatus.QUEUED));
        assertTrue(RunStatus.CLAIMABLE.contains(RunStatus.RETRY_WAIT));
        assertFalse(RunStatus.CLAIMABLE.contains(RunStatus.CANCEL_REQUESTED));
        assertFalse(RunStatus.CLAIMABLE.contains(RunStatus.NEEDS_RECONCILIATION));
    }
}
