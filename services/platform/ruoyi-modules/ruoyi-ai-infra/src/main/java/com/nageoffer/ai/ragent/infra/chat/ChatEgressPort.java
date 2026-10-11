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

package com.nageoffer.ai.ragent.infra.chat;

import java.net.URI;
import java.util.Map;

/** Fail-closed provider boundary. Runtime authorization and durable budget live behind this port. */
public interface ChatEgressPort {
    record CallSpec(String provider, String model, URI endpoint, long inputBytes, int maxOutputTokens) {}

    interface Lease {
        /** Revalidate current identity facts immediately before the actual send. */
        void beforeSend();
        /** Raw provider facts only; unknown/cancelled calls keep their reservation. */
        void received(String providerRequestId, Map<String, Object> usage);
    }

    Lease authorize(CallSpec call);

    ChatEgressPort DENY = call -> { throw new ChatEgressDeniedException("chat boundary unavailable"); };
}
