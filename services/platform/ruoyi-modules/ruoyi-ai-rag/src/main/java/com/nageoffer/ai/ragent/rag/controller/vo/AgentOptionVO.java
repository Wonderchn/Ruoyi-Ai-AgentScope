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

package com.nageoffer.ai.ragent.rag.controller.vo;

/**
 * 用户端智能体发现面的最小投影（F09:4-A）。
 *
 * <p><b>为什么是六字段而不是复用管理端 {@code AgentProfileVO}</b>：用户端只需要
 * "能不能选、选了叫什么、当前可否执行"这三件事。管理端的 {@code AgentProfileVO}
 * 带 {@code builtin} / {@code effectiveSlots} / {@code inactiveSlots} / 时间戳等
 * 平台管理事实，把它发给普通成员会把目录管理面的事实一并外泄。因此这里<b>恰好六个字段</b>，
 * 与前端 {@code api/agent/types.ts} 的 {@code AgentVO} 同形。
 *
 * <p><b>{@code executable} 是"当前可否作为对话对象"的唯一判据</b>（真值表见
 * {@code AgentOptionReadServiceImpl#listOptions}）：有激活行时仅激活行为真；
 * 无激活行但租户域有内置行时仅该内置行为真；两域皆无激活无内置时，
 * 模板域内置行（若存在）为真。列表里其它行一律为 {@code false}，前端据此显式停用，
 * 不得静默回落成"看起来可选"。
 *
 * @param id          智能体标识（雪花字符串；前端 key 与当前项判定都用它）
 * @param name        展示名称
 * @param description 展示描述（可为 null，前端回退到 name）
 * @param avatar      头像预设标识（可为 null）
 * @param active      是否为租户当前激活行
 * @param executable  当前是否可作为对话对象（真值表唯一结论）
 */
public record AgentOptionVO(
        String id,
        String name,
        String description,
        String avatar,
        boolean active,
        boolean executable
) {
}
