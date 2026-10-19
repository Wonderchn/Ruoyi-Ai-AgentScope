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

package com.nageoffer.ai.ragent.shortdrama.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 模型结构化输出的解析与引用校验。
 *
 * <p>本类只做"模型说了什么 → 我们允许它说什么"的转换：所有 ID / sceneNo 必须落在
 * 当前项目已授权的集合内；未知引用、缺场景、重复引用、空正文一律按坏输出拒绝，
 * 绝不"按名称猜一个"或部分落库。
 */
public class LlmOutputParser {

    private static final String SCRIPT_DELIMITER = "===SCRIPT===";
    private static final int MAX_PROJECT_NAME = 200;
    private static final int MAX_DESCRIPTION = 5000;
    private static final int MAX_SCRIPT_NAME = 200;
    private static final int MAX_TONE = 100;
    private static final int MAX_OUTLINE = 20000;
    private static final int MAX_SCRIPT = 200000;
    private static final int MAX_ANALYSIS = 4000;
    private static final int MAX_NOTES = 8000;
    private static final int MAX_RULE_JSON = 20000;

    private final ObjectMapper objectMapper;

    public LlmOutputParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析剧本响应：{@code JSON 元信息 + ===SCRIPT=== + 正文}。
     *
     * <p>没有分隔符时退化为"最后一个 {@code }} 之后是正文"；仍没有正文则拒绝。
     * 不尝试把正文塞进 JSON 再反转义——那条路径在长文本上更容易产生坏输出。
     */
    public ScriptEnvelope parseScriptEnvelope(String raw) {
        if (raw == null || raw.isBlank()) {
            throw badOutput("模型未返回内容");
        }
        String jsonPart;
        String scriptPart;
        int delimiter = raw.indexOf(SCRIPT_DELIMITER);
        if (delimiter >= 0) {
            jsonPart = raw.substring(0, delimiter);
            scriptPart = raw.substring(delimiter + SCRIPT_DELIMITER.length());
        } else {
            int end = raw.lastIndexOf('}');
            if (end < 0) {
                throw badOutput("缺少元信息 JSON 与剧本正文");
            }
            jsonPart = raw.substring(0, end + 1);
            scriptPart = raw.substring(end + 1);
        }
        JsonNode root = extractObject(jsonPart, "剧本元信息");
        if (!root.isObject()) {
            throw badOutput("剧本元信息不是 JSON 对象");
        }
        String scriptText = cleanScript(scriptPart);
        if (scriptText.isBlank()) {
            throw badOutput("剧本正文为空");
        }
        if (scriptText.length() > MAX_SCRIPT) {
            throw badOutput("剧本正文超过 " + MAX_SCRIPT + " 字符");
        }
        return new ScriptEnvelope(
                optionalText(root, "projectName", MAX_PROJECT_NAME),
                optionalText(root, "description", MAX_DESCRIPTION),
                optionalText(root, "scriptName", MAX_SCRIPT_NAME),
                optionalText(root, "tone", MAX_TONE),
                optionalText(root, "outlineText", MAX_OUTLINE),
                scriptText);
    }

    /**
     * 解析资产分析：模型只能引用当前项目已存在的角色/场景 ID。
     */
    public Analysis parseAnalysis(String raw,
                                  Set<Long> allowedCharacterIds,
                                  Set<Long> allowedLocationIds) {
        JsonNode root = extractObject(raw, "资产分析");
        if (!root.isObject()) {
            throw badOutput("资产分析不是 JSON 对象");
        }
        List<CharacterAnalysis> characters =
                parseCharacterAnalyses(root.get("characters"), allowedCharacterIds);
        List<LocationAnalysis> locations =
                parseLocationAnalyses(root.get("locations"), allowedLocationIds);
        return new Analysis(characters, locations,
                optionalText(root, "continuityNotes", MAX_NOTES),
                optionalText(root, "styleNotes", MAX_NOTES));
    }

    private List<CharacterAnalysis> parseCharacterAnalyses(JsonNode array, Set<Long> allowed) {
        List<CharacterAnalysis> out = new ArrayList<>();
        if (array == null || array.isNull()) {
            return out;
        }
        if (!array.isArray()) {
            throw badOutput("characters 必须是数组");
        }
        Set<Long> seen = new LinkedHashSet<>();
        for (JsonNode item : array) {
            if (!item.isObject()) {
                throw badOutput("characters 元素必须是对象");
            }
            long id = requireId(item, "id");
            if (!allowed.contains(id)) {
                throw badOutput("角色 ID 不在当前项目授权集合内: " + id);
            }
            if (!seen.add(id)) {
                throw badOutput("角色 ID 重复: " + id);
            }
            out.add(new CharacterAnalysis(id,
                    requireText(item, "analysis", MAX_ANALYSIS),
                    optionalText(item, "visualNotes", MAX_ANALYSIS)));
        }
        return out;
    }

    private List<LocationAnalysis> parseLocationAnalyses(JsonNode array, Set<Long> allowed) {
        List<LocationAnalysis> out = new ArrayList<>();
        if (array == null || array.isNull()) {
            return out;
        }
        if (!array.isArray()) {
            throw badOutput("locations 必须是数组");
        }
        Set<Long> seen = new LinkedHashSet<>();
        for (JsonNode item : array) {
            if (!item.isObject()) {
                throw badOutput("locations 元素必须是对象");
            }
            long id = requireId(item, "id");
            if (!allowed.contains(id)) {
                throw badOutput("场景 ID 不在当前项目授权集合内: " + id);
            }
            if (!seen.add(id)) {
                throw badOutput("场景 ID 重复: " + id);
            }
            out.add(new LocationAnalysis(id,
                    requireText(item, "analysis", MAX_ANALYSIS),
                    optionalText(item, "stagingNotes", MAX_ANALYSIS)));
        }
        return out;
    }

    /**
     * 解析摄影/表演规则的 {@code rules} 数组。
     *
     * @param fieldName {@code photographyRules} 或 {@code actingNotes}
     * @return sceneNo → 规则对象（按 sceneNo 升序）
     */
    public Map<Integer, JsonNode> parseSceneRules(String raw,
                                                  Set<Integer> allowedScenes,
                                                  String fieldName) {
        JsonNode root = extractObject(raw, "分镜规则");
        if (!root.isObject() || !root.has("rules") || !root.get("rules").isArray()) {
            throw badOutput("缺少 rules 数组");
        }
        Map<Integer, JsonNode> out = new LinkedHashMap<>();
        for (JsonNode item : root.get("rules")) {
            if (!item.isObject()) {
                throw badOutput("rules 元素必须是对象");
            }
            JsonNode sceneNode = item.get("sceneNo");
            if (sceneNode == null || !sceneNode.canConvertToInt()) {
                throw badOutput("sceneNo 缺失或不是整数");
            }
            int sceneNo = sceneNode.asInt();
            if (!allowedScenes.contains(sceneNo)) {
                throw badOutput("未知 sceneNo: " + sceneNo);
            }
            if (out.containsKey(sceneNo)) {
                throw badOutput("sceneNo 重复: " + sceneNo);
            }
            JsonNode rules = item.get(fieldName);
            if (rules == null || rules.isNull()) {
                throw badOutput("sceneNo " + sceneNo + " 缺少 " + fieldName);
            }
            if (rules.isTextual()) {
                rules = extractObject(rules.asText(), fieldName);
            }
            if (!rules.isObject()) {
                throw badOutput("sceneNo " + sceneNo + " 的 " + fieldName + " 不是 JSON 对象");
            }
            if (rules.toString().length() > MAX_RULE_JSON) {
                throw badOutput("sceneNo " + sceneNo + " 的 " + fieldName + " 过大");
            }
            out.put(sceneNo, rules);
        }
        Set<Integer> covered = new TreeSet<>(out.keySet());
        Set<Integer> expected = new TreeSet<>(allowedScenes);
        if (!covered.equals(expected)) {
            Set<Integer> missing = new TreeSet<>(expected);
            missing.removeAll(covered);
            throw badOutput("缺少场次: " + missing);
        }
        return out;
    }

    /** 从任意文本中截取第一个完整 JSON 对象（容忍 Markdown 代码围栏与前后说明）。 */
    public JsonNode extractObject(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            throw badOutput(label + " 为空");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw badOutput(label + " 不是 JSON 对象");
        }
        try {
            JsonNode node = objectMapper.readTree(raw.substring(start, end + 1));
            if (node == null || node.isMissingNode()) {
                throw badOutput(label + " 解析为空");
            }
            return node;
        } catch (P04AiException e) {
            throw e;
        } catch (Exception e) {
            throw badOutput(label + " JSON 解析失败");
        }
    }

    private static String cleanScript(String scriptPart) {
        String text = scriptPart == null ? "" : scriptPart.strip();
        text = text.replaceFirst("^```[a-zA-Z]*\\s*", "");
        text = text.replaceFirst("^[\\s\\-=]+", "");
        if (text.endsWith("```")) {
            text = text.substring(0, text.length() - 3).strip();
        }
        return text;
    }

    private static long requireId(JsonNode item, String field) {
        JsonNode node = item.get(field);
        if (node == null || node.isNull()) {
            throw badOutput(field + " 缺失");
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isTextual()) {
            try {
                return Long.parseLong(node.asText().strip());
            } catch (NumberFormatException ignored) {
                // 落入统一拒绝
            }
        }
        throw badOutput(field + " 不是整数");
    }

    private static String requireText(JsonNode item, String field, int maxLength) {
        String value = optionalText(item, field, maxLength);
        if (value == null || value.isBlank()) {
            throw badOutput(field + " 不能为空");
        }
        return value;
    }

    private static String optionalText(JsonNode item, String field, int maxLength) {
        JsonNode node = item.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw badOutput(field + " 必须是字符串");
        }
        String value = node.asText().strip();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > maxLength) {
            throw badOutput(field + " 超过 " + maxLength + " 字符");
        }
        return value;
    }

    private static P04AiException badOutput(String detail) {
        // 只回报结构原因，不回显模型原文（可能含用户数据/提示注入内容）。
        return new P04AiException(P04AiErrorCode.INTERNAL_ERROR, "模型输出格式不合法：" + detail);
    }

    public record ScriptEnvelope(String projectName,
                                 String description,
                                 String scriptName,
                                 String tone,
                                 String outlineText,
                                 String scriptText) {
    }

    public record CharacterAnalysis(long id,
                                    String analysis,
                                    String visualNotes) {
    }

    public record LocationAnalysis(long id,
                                   String analysis,
                                   String stagingNotes) {
    }

    public record Analysis(List<CharacterAnalysis> characters,
                           List<LocationAnalysis> locations,
                           String continuityNotes,
                           String styleNotes) {
    }
}
