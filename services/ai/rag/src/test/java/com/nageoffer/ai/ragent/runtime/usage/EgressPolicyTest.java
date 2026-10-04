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

import com.nageoffer.ai.ragent.runtime.RunApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 外发白名单：空名单/未启用必须拒绝（0 提供方调用），名单内才允许。 */
class EgressPolicyTest {

    @Test
    void emptyWhitelistRefuses() {
        EgressPolicy policy = new EgressPolicy();
        policy.setEnabled(true);
        policy.setAllowedProviders("");
        assertFalse(policy.allows("deepseek"));
        assertThrows(RunApiException.class, () -> policy.requireAllowed("deepseek"));
    }

    @Test
    void disabledRefusesEvenWithNames() {
        EgressPolicy policy = new EgressPolicy();
        policy.setEnabled(false);
        policy.setAllowedProviders("deepseek");
        assertThrows(RunApiException.class, () -> policy.requireAllowed("deepseek"));
    }

    @Test
    void unlistedProviderRefuses() {
        EgressPolicy policy = new EgressPolicy();
        policy.setEnabled(true);
        policy.setAllowedProviders("deepseek");
        assertThrows(RunApiException.class, () -> policy.requireAllowed("bailian"));
    }

    @Test
    void listedProviderAllowed() {
        EgressPolicy policy = new EgressPolicy();
        policy.setEnabled(true);
        policy.setAllowedProviders("deepseek, other");
        assertTrue(policy.allows("deepseek"));
        assertDoesNotThrow(() -> policy.requireAllowed("other"));
    }
}
