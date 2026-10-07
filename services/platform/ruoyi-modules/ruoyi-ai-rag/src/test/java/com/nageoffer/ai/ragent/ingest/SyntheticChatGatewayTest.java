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

package com.nageoffer.ai.ragent.ingest;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Tag;

/** 合成问答 test double：流式增量、marker 引用、无证据声明、usage 标记 synthetic。 */
@Tag("dev")
class SyntheticChatGatewayTest {

    private final SyntheticChatGateway gateway = new SyntheticChatGateway(0);

    @Test
    void streamsDeltasThatJoinToFullAnswer() {
        List<String> deltas = new ArrayList<>();
        ChatGateway.ChatResult result = gateway.stream(List.of(
                ChatMessage.system("证据：\n[1] the marker code is P2-MARKER-XYZZY."),
                ChatMessage.user("what is the marker code for project phase two?")), 2000, deltas::add);
        assertEquals(result.content(), String.join("", deltas));
        assertTrue(result.content().contains("P2-MARKER-XYZZY"));
        assertTrue(deltas.size() > 1);
    }

    @Test
    void emptyContextDeclaresInsufficientEvidence() {
        ChatGateway.ChatResult result = gateway.stream(List.of(
                ChatMessage.system(""), ChatMessage.user("unknown question")), 2000, delta -> {
        });
        assertTrue(result.content().contains("evidence insufficient"));
        assertFalse(result.content().contains("P2-MARKER-XYZZY"));
    }

    @Test
    void usageIsMarkedSynthetic() {
        ChatGateway.ChatResult result = gateway.stream(List.of(ChatMessage.user("q")), 2000, delta -> {
        });
        assertNotNull(result.usageRaw());
        assertEquals(Boolean.TRUE, result.usageRaw().get("synthetic"));
        assertEquals("synthetic", gateway.provider());
    }
}
