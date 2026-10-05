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

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Tag;

/** 规范化与 requestHash：同键重试必须稳定，异体必须不同。 */
@Tag("dev")
class CanonicalJsonTest {

    @Test
    void fieldOrderDoesNotChangeHash() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("b", 2);
        first.put("a", 1);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("a", 1);
        second.put("b", 2);
        assertEquals(CanonicalJson.requestHash(first), CanonicalJson.requestHash(second));
    }

    @Test
    void nestedStructuresAreCanonical() {
        Map<String, Object> body = Map.of("action", "rag.chat", "input", Map.of("text", "hello"),
                "resourceRefs", List.of(Map.of("type", "knowledge_base", "id", "kb-1")));
        String canonical = CanonicalJson.canonicalize(CanonicalJson.strictMapper().valueToTree(body));
        assertTrue(canonical.startsWith("{"));
        assertTrue(canonical.contains("\"action\":\"rag.chat\""));
        assertTrue(canonical.contains("\"id\":\"kb-1\""));
        assertTrue(canonical.contains("\"resourceRefs\":[{\"id\":\"kb-1\",\"type\":\"knowledge_base\"}]"));
    }

    @Test
    void differentBodyDifferentHash() {
        assertNotEquals(CanonicalJson.requestHash(Map.of("text", "a")),
                CanonicalJson.requestHash(Map.of("text", "b")));
    }

    @Test
    void hashIsSha256Hex() {
        String hash = CanonicalJson.requestHash(Map.of("x", 1));
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }
}
