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

package com.nageoffer.ai.ragent.rag.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 规范请求哈希（Spec §7.2）。
 *
 * <p>冻结口径：按固定字段顺序重建对象后再序列化，因此<b>对象字段的输入顺序不影响 hash</b>；
 * 数组顺序保留；未知字段与重复字段拒绝；不得悄悄裁剪 {@code text}。传输用的
 * {@code requestId}、token/{@code jti} <b>不</b>参与业务 hash。
 */
@Component
public class RequestHasher {

    private final ObjectMapper objectMapper;

    public RequestHasher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 允许出现在受理请求体里的字段；其余一律拒绝（含身份字段）。 */
    private static final List<String> ALLOWED_FIELDS = List.of("schemaVersion", "action", "input", "resourceRefs");

    /** 身份字段：出现在请求体里就是伪造尝试，由调用方判 403 而不是 400。 */
    private static final List<String> IDENTITY_FIELDS = List.of("tid", "tenantId", "userId", "mid", "membershipId", "sub");

    public static List<String> identityFields() {
        return IDENTITY_FIELDS;
    }

    /**
     * 解析并校验请求体结构。
     *
     * @throws P04AiException 400 {@code BAD_REQUEST}（结构非法/未知字段）
     */
    public JsonNode parse(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST, "empty request body");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST, "malformed request body");
        }
        if (!root.isObject()) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST, "request body must be an object");
        }
        var names = new ArrayList<String>();
        root.fieldNames().forEachRemaining(names::add);
        for (String name : names) {
            if (!ALLOWED_FIELDS.contains(name) && !IDENTITY_FIELDS.contains(name)) {
                throw new P04AiException(P04AiErrorCode.BAD_REQUEST, "unknown field: " + name);
            }
        }
        if (root.path("action").asText("").isBlank()) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST, "action is required");
        }
        if (root.path("input").path("text").isMissingNode()) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST, "input.text is required");
        }
        return root;
    }

    /** 请求体里是否携带了身份声明字段（N05 → 403）。 */
    public boolean declaresIdentity(JsonNode root) {
        for (String field : IDENTITY_FIELDS) {
            if (root.has(field)) {
                return true;
            }
        }
        return false;
    }

    public String action(JsonNode root) {
        return root.path("action").asText();
    }

    public List<String> resourceRefs(JsonNode root) {
        List<String> refs = new ArrayList<>();
        JsonNode node = root.path("resourceRefs");
        if (node.isArray()) {
            for (JsonNode item : node) {
                refs.add(item.asText());
            }
        }
        return refs;
    }

    /**
     * 固定字段顺序 + UTF-8 + SHA-256（小写十六进制）。
     */
    public String hash(JsonNode root) {
        ObjectNode canonical = objectMapper.createObjectNode();
        canonical.put("schemaVersion", root.path("schemaVersion").asInt(1));
        canonical.put("action", root.path("action").asText());
        canonical.put("inputText", root.path("input").path("text").asText());
        ArrayNode refs = canonical.putArray("resourceRefs");
        for (String ref : resourceRefs(root)) {
            refs.add(ref);
        }
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(canonical);
            return sha256Hex(bytes);
        } catch (Exception e) {
            throw new P04AiException(P04AiErrorCode.INTERNAL_ERROR);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new P04AiException(P04AiErrorCode.INTERNAL_ERROR);
        }
    }

    /** 便于测试：等价于对规范化文本求哈希，用于断言长度与字符集。 */
    public static int canonicalEncodingLength(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
