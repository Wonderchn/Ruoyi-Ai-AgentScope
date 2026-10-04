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

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Tag;

@Tag("dev")
class ProviderSpendEnvelopeTest {
    @Test void closedOrInvalidCapNeverTouchesDatabase() {
        var jdbc=mock(JdbcTemplate.class);var tx=mock(PlatformTransactionManager.class);
        assertThrows(RuntimeException.class,()->new ProviderSpendEnvelope(jdbc,tx,false,"test",new BigDecimal("20")).reserve("deepseek","deepseek-flash",10,100));
        assertThrows(RuntimeException.class,()->new ProviderSpendEnvelope(jdbc,tx,true,"test",new BigDecimal("21")).reserve("deepseek","deepseek-flash",10,100));
        verifyNoInteractions(jdbc,tx);
    }
    @Test void estimatesCountUtf8BytesAndOutputUpperBound() {
        assertEquals(new BigDecimal("0.030340"),ProviderSpendEnvelope.estimate("deepseek",10,1000));
        assertEquals(new BigDecimal("0.010000"),ProviderSpendEnvelope.estimate("dashscope",10,0));
        assertThrows(RuntimeException.class,()->ProviderSpendEnvelope.estimate("unapproved",10,0));
        assertThrows(RuntimeException.class,()->ProviderSpendEnvelope.estimate("deepseek",10,8193));
    }
}
