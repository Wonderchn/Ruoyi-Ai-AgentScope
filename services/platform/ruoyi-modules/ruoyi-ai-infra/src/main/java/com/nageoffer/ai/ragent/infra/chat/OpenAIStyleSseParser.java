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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Collections;

/**
 * OpenAI 协议风格 SSE 解析器
 * 支持从 delta/message 中提取 content，以及可选的 reasoning_content
 */
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class OpenAIStyleSseParser {

    private static final String DATA_PREFIX = "data:";
    private static final String DONE_MARKER = "[DONE]";

    static ParsedEvent parseLine(String line, Gson gson, boolean reasoningEnabled) {
        if (line == null || line.isBlank()) {
            return ParsedEvent.empty();
        }

        String payload = line.trim();
        if (payload.startsWith(DATA_PREFIX)) {
            payload = payload.substring(DATA_PREFIX.length()).trim();
        }
        if (DONE_MARKER.equalsIgnoreCase(payload)) {
            return ParsedEvent.done();
        }

        JsonObject obj = gson.fromJson(payload, JsonObject.class);
        JsonArray choices = obj.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            return new ParsedEvent(null, null, false, false, providerId(obj), usage(obj));
        }

        JsonObject choice0 = choices.get(0).getAsJsonObject();
        String content = extractText(choice0, "content");
        String reasoning = reasoningEnabled ? extractText(choice0, "reasoning_content") : null;
        boolean completed = hasFinishReason(choice0);

        return new ParsedEvent(content, reasoning, completed, false, providerId(obj), usage(obj));
    }

    private static boolean hasFinishReason(JsonObject choice) {
        if (choice == null || !choice.has("finish_reason")) {
            return false;
        }
        JsonElement finishReason = choice.get("finish_reason");
        return finishReason != null && !finishReason.isJsonNull();
    }

    private static String extractText(JsonObject choice, String fieldName) {
        if (choice == null) {
            return null;
        }
        if (choice.has("delta") && choice.get("delta").isJsonObject()) {
            JsonObject delta = choice.getAsJsonObject("delta");
            if (delta.has(fieldName)) {
                JsonElement value = delta.get(fieldName);
                if (value != null && !value.isJsonNull()) {
                    return value.getAsString();
                }
            }
        }
        if (choice.has("message") && choice.get("message").isJsonObject()) {
            JsonObject message = choice.getAsJsonObject("message");
            if (message.has(fieldName)) {
                JsonElement value = message.get(fieldName);
                if (value != null && !value.isJsonNull()) {
                    return value.getAsString();
                }
            }
        }
        return null;
    }

    static String providerId(JsonObject root) {
        JsonElement value = root == null ? null : root.get("id");
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                && !value.getAsString().isBlank() ? value.getAsString() : null;
    }

    @SuppressWarnings("unchecked")
    static Map<String,Object> usage(JsonObject root) {
        JsonElement value = root == null ? null : root.get("usage");
        return value != null && value.isJsonObject() && value.getAsJsonObject().size() > 0
                ? (Map<String,Object>) rawValue(value) : null;
    }

    private static Object rawValue(JsonElement value) {
        if (value == null || value.isJsonNull()) { return null; }
        if (value.isJsonObject()) {
            Map<String,Object> result = new LinkedHashMap<>();
            value.getAsJsonObject().entrySet().forEach(entry -> result.put(entry.getKey(),rawValue(entry.getValue())));
            return Collections.unmodifiableMap(result);
        }
        if (value.isJsonArray()) {
            List<Object> result = new ArrayList<>();
            value.getAsJsonArray().forEach(item -> result.add(rawValue(item)));
            return Collections.unmodifiableList(result);
        }
        if (value.getAsJsonPrimitive().isBoolean()) { return value.getAsBoolean(); }
        if (value.getAsJsonPrimitive().isNumber()) { return new BigDecimal(value.getAsString()); }
        return value.getAsString();
    }

    record ParsedEvent(String content, String reasoning, boolean completed, boolean terminal,
                       String providerId, Map<String,Object> usage) {

        static ParsedEvent empty() {
            return new ParsedEvent(null, null, false, false, null, null);
        }

        static ParsedEvent done() {
            return new ParsedEvent(null, null, true, true, null, null);
        }

        boolean hasContent() {
            return content != null && !content.isEmpty();
        }

        boolean hasReasoning() {
            return reasoning != null && !reasoning.isEmpty();
        }
    }
}
