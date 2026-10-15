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

package com.nageoffer.ai.ragent.runtime.config;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F04：模型附件能力的**唯一**事实面——不可变 revision 参数 {@code params_json} 中的保留键
 * {@code attachmentModalities}。
 *
 * <p><b>为什么是保留键而不是第二张表。</b>图片能力必须能随语言模型一起被发布、被 run 绑定、
 * 被受理读取、被执行期读取，且不可原地篡改——这些语义恰好就是
 * {@code ai_runtime_config_revision} 不可变版本的既有语义。新建一张"能力表"会造出
 * 第二权威（两个发布入口、两种撤销语义、两套版本号），因此能力声明只以保留键形态
 * 固化在既有 revision 事实里。
 *
 * <p><b>默认拒图。</b>没有该键 ⇒ 不声明任何附件模态；执行期对图片附件一律拒绝。
 * <b>不得猜模型名</b>（名字里带 "vl"/"vision" 不构成能力事实），也不得从请求参数升级能力。
 *
 * <p><b>严格形态（唯一合法形态 {@code ["image"]}）。</b>非空、去重、逐元素必须是受支持字面量
 * （当前仅 {@code "image"}）的 JSON 字符串数组。字符串/空数组/重复/未知字面量/非数组一律
 * 拒绝：宽松解析会把"配置写错了"变成"能力悄悄升级/悄悄消失"。三处边界共用本类：
 * 发布 API（{@code safeConfigParams}）、发布权威（{@link JdbcConfigRevisionPublisher}）、
 * 绑定读侧（{@link JdbcRunConfigBindingPort}）；执行期能力判定消费的也是同一事实。
 */
public final class AttachmentModalities {

    /** 保留键名；出现在 revision {@code params_json} 的顶层。 */
    public static final String KEY = "attachmentModalities";

    /** 当前唯一受支持的模态字面量。 */
    public static final String IMAGE = "image";

    /** 受支持模态字面量的封闭集合（新增模态必须在 Spec 批准后显式加入，绝不做字符串前缀猜测）。 */
    private static final Set<String> SUPPORTED = Set.of(IMAGE);

    private AttachmentModalities() { }

    /**
     * 严格校验 JSON 形态下的保留键；键缺席是合法形态（返回空集合 = 未声明任何模态）。
     *
     * @throws IllegalArgumentException 形态非法（调用方各自翻译为本层错误类型并 fail-closed）
     */
    public static Set<String> requireValid(JsonNode params) {
        if (params == null || !params.isObject()) {
            throw new IllegalArgumentException("config params must be a JSON object");
        }
        if (!params.has(KEY)) {
            return Set.of();
        }
        JsonNode value = params.get(KEY);
        if (!value.isArray() || value.isEmpty()) {
            throw new IllegalArgumentException(KEY + " must be a non-empty array");
        }
        LinkedHashSet<String> declared = new LinkedHashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || !SUPPORTED.contains(item.asText())) {
                throw new IllegalArgumentException(KEY + " supports only " + SUPPORTED);
            }
            if (!declared.add(item.asText())) {
                throw new IllegalArgumentException(KEY + " must not repeat entries");
            }
        }
        return Set.copyOf(declared);
    }

    /**
     * 严格校验 Map 形态下的保留键（绑定读侧解析后的消费形态）。
     *
     * @throws IllegalArgumentException 形态非法
     */
    public static Set<String> requireValid(Map<String, Object> params) {
        if (params == null) {
            return Set.of();
        }
        Object value = params.get(KEY);
        if (value == null && !params.containsKey(KEY)) {
            return Set.of();
        }
        if (!(value instanceof List<?> list) || list.size() != 1 || !IMAGE.equals(list.get(0))) {
            throw new IllegalArgumentException(KEY + " supports only " + SUPPORTED);
        }
        return Set.of(IMAGE);
    }

    /**
     * 已通过校验的事实上"是否声明了图片模态"。执行期能力判定的单点读取口；
     * 非法/缺失形态返回 false（拒绝），不做宽松解释。
     */
    public static boolean imageDeclared(Map<String, Object> params) {
        return params != null && params.containsKey(KEY) && params.get(KEY) instanceof List<?> list
                && list.size() == 1 && IMAGE.equals(list.get(0));
    }
}
