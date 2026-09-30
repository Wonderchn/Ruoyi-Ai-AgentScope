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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 规范请求哈希与严格请求体校验（Spec §7.2 与阻断修复 Spec §3.1）。
 *
 * <p>冻结口径：按固定字段顺序重建对象后再序列化，因此<b>对象字段的输入顺序不影响 hash</b>；
 * 数组顺序保留；不得悄悄裁剪 {@code text}。传输用的 {@code requestId}、token/{@code jti}
 * <b>不</b>参与业务 hash。
 *
 * <p><b>严格化（B2 修复）</b>：本类不再对任何字段做静默兜底——
 * {@code schemaVersion} 必须精确为 1、{@code action} 必须精确为 {@code rag.chat}、
 * {@code input.text} 必须是字符串、{@code resourceRefs} 必须是非空字符串数组且<b>不得为空数组</b>；
 * 顶层与 {@code input} 内的未知字段、以及重复键一律拒绝。校验先于规范化哈希。
 *
 * <p>本组件默认<b>不</b>装配：需要 {@code p04.enabled=true}（见阻断修复 Spec §2）。
 */
@Component
@ConditionalOnProperty(name = "p04.enabled", havingValue = "true")
public class RequestHasher {

    /** 本实验只允许的动作。 */
    public static final String EXPECTED_ACTION = "rag.chat";

    /** 本实验只允许的 schema 版本。 */
    public static final int EXPECTED_SCHEMA_VERSION = 1;

    /** 允许出现在受理请求体顶层的字段。 */
    private static final List<String> ALLOWED_FIELDS = List.of("schemaVersion", "action", "input", "resourceRefs");

    /** {@code input} 内允许的字段。 */
    private static final List<String> ALLOWED_INPUT_FIELDS = List.of("text");

    /** 身份字段：只能来自委托凭证；出现在请求体里就是伪造尝试 → 403（N05）。 */
    private static final List<String> IDENTITY_FIELDS =
            List.of("tid", "tenantId", "userId", "mid", "membershipId", "sub");

    /** 身份字段清单（供守卫与负例断言引用）。 */
    public static List<String> identityFields() {
        return IDENTITY_FIELDS;
    }

    private final ObjectMapper objectMapper;

    /**
     * 严格解析用的私有副本：启用重复键检测。用副本而不是改共享 {@code ObjectMapper}，
     * 避免影响其它组件的解析行为。
     */
    private final ObjectMapper strictMapper;

    public RequestHasher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        JsonFactory strictFactory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        this.strictMapper = new ObjectMapper(strictFactory);
    }

    /**
     * 解析并严格校验请求体。校验通过后才允许计算业务哈希。
     *
     * @throws P04AiException 403 携带身份字段；404 {@code resourceRefs} 为空数组；400 其余结构问题
     */
    public JsonNode parse(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw bad("empty request body");
        }

        JsonNode root;
        try {
            // 严格解析：重复键在此处即失败，而不是静默保留最后一个
            root = strictMapper.readTree(rawBody);
        } catch (Exception e) {
            throw bad("malformed request body or duplicate field");
        }
        if (root == null || !root.isObject()) {
            throw bad("request body must be an object");
        }

        for (String name : fieldNames(root)) {
            if (IDENTITY_FIELDS.contains(name)) {
                // 身份只能来自委托凭证；不得靠请求体声明，也不得回落默认租户
                throw new P04AiException(P04AiErrorCode.TENANT_CONTEXT_MISSING);
            }
            if (!ALLOWED_FIELDS.contains(name)) {
                throw bad("unknown field: " + name);
            }
        }

        JsonNode schemaVersion = root.get("schemaVersion");
        if (schemaVersion == null || !schemaVersion.isIntegralNumber()
                || schemaVersion.asInt() != EXPECTED_SCHEMA_VERSION) {
            throw bad("schemaVersion must be exactly " + EXPECTED_SCHEMA_VERSION);
        }

        JsonNode action = root.get("action");
        if (action == null || !action.isTextual() || !EXPECTED_ACTION.equals(action.asText())) {
            throw bad("action must be exactly " + EXPECTED_ACTION);
        }

        JsonNode input = root.get("input");
        if (input == null || !input.isObject()) {
            throw bad("input must be an object");
        }
        for (String name : fieldNames(input)) {
            if (!ALLOWED_INPUT_FIELDS.contains(name)) {
                throw bad("unknown field in input: " + name);
            }
        }
        JsonNode text = input.get("text");
        if (text == null || !text.isTextual()) {
            throw bad("input.text must be a string");
        }

        JsonNode refs = root.get("resourceRefs");
        if (refs == null) {
            throw bad("resourceRefs is required");
        }
        if (!refs.isArray()) {
            throw bad("resourceRefs must be an array of strings");
        }
        for (JsonNode ref : refs) {
            if (!ref.isTextual() || ref.asText().isBlank()) {
                throw bad("resourceRefs elements must be non-blank strings");
            }
        }
        if (refs.isEmpty()) {
            // 本实验只限 rag.chat，不存在"无资源动作"：空的有效授权集合一律拒绝
            throw new P04AiException(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        }
        return root;
    }

    /** 已通过 {@link #parse} 校验的请求体动作。 */
    public String action(JsonNode root) {
        return root.get("action").asText();
    }

    /** 已通过 {@link #parse} 校验的资源引用（保证非空数组、元素为非空字符串）。 */
    public List<String> resourceRefs(JsonNode root) {
        List<String> refs = new ArrayList<>();
        for (JsonNode item : root.get("resourceRefs")) {
            refs.add(item.asText());
        }
        return refs;
    }

    /**
     * 固定字段顺序 + UTF-8 + SHA-256（小写十六进制）。只接受已校验的请求体。
     */
    public String hash(JsonNode root) {
        ObjectNode canonical = objectMapper.createObjectNode();
        canonical.put("schemaVersion", root.get("schemaVersion").asInt());
        canonical.put("action", root.get("action").asText());
        canonical.put("inputText", root.get("input").get("text").asText());
        ArrayNode refs = canonical.putArray("resourceRefs");
        for (String ref : resourceRefs(root)) {
            refs.add(ref);
        }
        try {
            return sha256Hex(objectMapper.writeValueAsBytes(canonical));
        } catch (Exception e) {
            throw new P04AiException(P04AiErrorCode.INTERNAL_ERROR);
        }
    }

    private static P04AiException bad(String message) {
        return new P04AiException(P04AiErrorCode.BAD_REQUEST, message);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
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
}
