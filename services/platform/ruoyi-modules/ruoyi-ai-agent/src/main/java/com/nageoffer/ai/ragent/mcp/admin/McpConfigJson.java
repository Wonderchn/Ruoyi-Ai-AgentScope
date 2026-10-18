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

package com.nageoffer.ai.ragent.mcp.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code auth_config}/{@code config_json} 的解析、掩码与凭据引用（brief §2.6）。
 *
 * <p><b>只存引用＋掩码</b>：
 * <ul>
 *   <li>{@code headers} 的值支持 {@code env:NAME}——连接时才从环境变量解析；</li>
 *   <li>任何响应/导出都把 headers 的值替换为 {@code "***"}，原始值与解析值永不回传；</li>
 *   <li>解析不了的 JSON 按"整体敏感"处理（掩码为 {@code "***"}），绝不回显原文。</li>
 * </ul>
 */
public final class McpConfigJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private McpConfigJson() {
    }

    /** {@code baseUrl} 原样返回；缺失/非文本/null 返回 null。 */
    public static String baseUrl(String configJson) {
        JsonNode root = parse(configJson);
        JsonNode value = root == null ? null : root.get("baseUrl");
        return value != null && value.isTextual() ? value.asText() : null;
    }

    /** {@code inputSchema} 原样 JSON 对象；缺失或不是对象返回 null。 */
    public static JsonNode inputSchema(String configJson) {
        JsonNode root = parse(configJson);
        JsonNode value = root == null ? null : root.get("inputSchema");
        return value != null && value.isObject() ? value : null;
    }

    /** {@code requiresConfirm}，缺省 false（运行期确认仍由意图树决定）。 */
    public static boolean requiresConfirm(String configJson) {
        JsonNode root = parse(configJson);
        JsonNode value = root == null ? null : root.get("requiresConfirm");
        return value != null && value.asBoolean(false);
    }

    /** 是否存在会被掩码的 headers（用于 {@code authMasked} 展示）。 */
    public static boolean hasHeaders(String configJson) {
        JsonNode root = parse(configJson);
        JsonNode headers = root == null ? null : root.get("headers");
        return headers != null && headers.isObject() && !headers.isEmpty();
    }

    /**
     * 响应/导出用的 config 掩码（<b>严格白名单 + fail-closed</b>）。
     *
     * <p>只回传四个可展示字段：{@code baseUrl / headers(masked) / inputSchema / requiresConfirm}；
     * 其余顶层字段（包括未知的敏感字段）一律丢弃。`headers` 存在但不是对象时整体替换为
     * {@code "***"}（不能把可疑原文回显）；JSON 解析失败同样整体替换为 {@code "***"}。
     *
     * <p>没有 headers 时仍按白名单重建（不回传原文），保证未知字段不外泄。
     */
    public static String maskConfigJson(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return configJson;
        }
        JsonNode root = parse(configJson);
        if (root == null || !root.isObject()) {
            return "***";
        }
        JsonNode headers = root.get("headers");
        if (headers != null && !headers.isObject()) {
            return "***";
        }
        ObjectNode masked = JSON.createObjectNode();
        JsonNode baseUrl = root.get("baseUrl");
        if (baseUrl != null) {
            masked.set("baseUrl", baseUrl);
        }
        if (headers != null) {
            ObjectNode maskedHeaders = JSON.createObjectNode();
            Iterator<String> names = headers.fieldNames();
            while (names.hasNext()) {
                maskedHeaders.put(names.next(), "***");
            }
            masked.set("headers", maskedHeaders);
        }
        JsonNode inputSchema = root.get("inputSchema");
        if (inputSchema != null) {
            masked.set("inputSchema", inputSchema);
        }
        JsonNode requiresConfirm = root.get("requiresConfirm");
        if (requiresConfirm != null) {
            masked.set("requiresConfirm", requiresConfirm);
        }
        try {
            return JSON.writeValueAsString(masked);
        } catch (Exception e) {
            return "***";
        }
    }

    /** auth_config 是整体敏感列：非空即返回 {@code "***"}，空值返回 null。 */
    public static String maskAuthConfig(String authConfig) {
        return authConfig == null || authConfig.isBlank() ? null : "***";
    }

    /**
     * 从 JSON 的 {@code headers} 解析连接头：{@code env:NAME} 取环境变量，其余按字面量。
     *
     * <p>引用不可解析时抛具名 {@link McpAdminError#CREDENTIAL_UNAVAILABLE}，且调用发生在
     * 任何网络调用之前。异常消息只含变量名，不含值。
     */
    public static Map<String, String> resolveHeaders(String configJson) {
        JsonNode root = parse(configJson);
        JsonNode headers = root == null ? null : root.get("headers");
        if (headers == null || !headers.isObject()) {
            return Map.of();
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = headers.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!entry.getValue().isTextual()) {
                continue;
            }
            String value = entry.getValue().asText();
            if (value.startsWith("env:")) {
                String variable = value.substring("env:".length()).trim();
                String fromEnv = variable.isEmpty() ? null : System.getenv(variable);
                if (fromEnv == null || fromEnv.isBlank()) {
                    throw new McpAdminException(McpAdminError.CREDENTIAL_UNAVAILABLE, variable);
                }
                resolved.put(entry.getKey(), fromEnv);
            } else {
                resolved.put(entry.getKey(), value);
            }
        }
        return Map.copyOf(resolved);
    }

    private static JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
