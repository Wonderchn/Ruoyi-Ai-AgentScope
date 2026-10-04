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

package com.nageoffer.ai.ragent.runtime;
import com.nageoffer.ai.ragent.runtime.exec.RunDeadline;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RunDeadlineTest {
 @Test void takeoverCannotResetOriginalDeadline(){var start=Instant.parse("2026-10-03T00:00:00Z");assertEquals(5,RunDeadline.remainingSeconds(start,start.plusSeconds(55),60));assertEquals(0,RunDeadline.remainingSeconds(start,start.plusSeconds(61),60));}
 @Test void unstartedAndFutureClockAreBounded(){var now=Instant.parse("2026-10-03T00:00:00Z");assertEquals(60,RunDeadline.remainingSeconds(null,now,60));assertEquals(60,RunDeadline.remainingSeconds(now.plusSeconds(9),now,60));assertEquals(0,RunDeadline.remainingSeconds(now,now,0));}
}
