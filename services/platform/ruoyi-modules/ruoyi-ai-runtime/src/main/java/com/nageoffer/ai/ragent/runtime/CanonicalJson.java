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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 规范化 JSON 与 requestHash：字段排序、无多余空白、数值形态稳定。
 *
 * <p>同键重试必须产生相同 hash；因此规范化不得包含时间戳、随机量或 map 迭代顺序。
 */
public final class CanonicalJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CanonicalJson() {
    }

    public static String canonicalize(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        write(node, sb);
        return sb.toString();
    }

    private static void write(JsonNode node, StringBuilder sb) {
        if (node == null || node.isNull()) {
            sb.append("null");
        } else if (node.isObject()) {
            List<String> names = new ArrayList<>();
            for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
                names.add(it.next());
            }
            names.sort(String::compareTo);
            sb.append('{');
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(quote(names.get(i))).append(':');
                write(node.get(names.get(i)), sb);
            }
            sb.append('}');
        } else if (node.isArray()) {
            sb.append('[');
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(array.get(i), sb);
            }
            sb.append(']');
        } else if (node.isTextual()) {
            sb.append(quote(node.textValue()));
        } else if (node.isNumber()) {
            sb.append(node.asText());
        } else if (node.isBoolean()) {
            sb.append(node.booleanValue());
        } else {
            sb.append(quote(node.asText()));
        }
    }

    private static String quote(String value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("json string encoding failed", e);
        }
    }

    public static String requestHash(Object body) {
        try {
            JsonNode node = MAPPER.valueToTree(body);
            return sha256(canonicalize(node));
        } catch (Exception e) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "request body is not canonicalizable");
        }
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 供测试与调试：读写严格模式 ObjectMapper 副本。 */
    public static ObjectMapper strictMapper() {
        return new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public static ObjectNode emptyObject() {
        return MAPPER.createObjectNode();
    }
    public static JsonNode inputOf(String inputJson, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        try {
            return mapper.readTree(inputJson == null ? "{}" : inputJson);
        } catch (Exception e) {
            throw new RunApiException(RunErrorCode.INTERNAL_ERROR, "run input is invalid");
        }
    }
}
