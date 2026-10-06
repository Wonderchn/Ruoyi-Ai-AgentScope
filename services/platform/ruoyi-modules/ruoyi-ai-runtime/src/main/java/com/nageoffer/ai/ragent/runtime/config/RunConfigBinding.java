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

import java.time.Instant;

/**
 * 某个 **run** 受理时绑定的配置版本事实（C1.2 / C1.3）。
 *
 * <p><b>为什么需要"按 run 解析"而不是"取当前最新版本"。</b>C1.2 的表格把两件必须同时成立的事写死了：
 * <ul>
 *   <li>新受理的 run 绑**新**发布版本；</li>
 *   <li>已受理 / 运行中 / 恢复的 run 固定**原**版本，不得被新发布改动。</li>
 * </ul>
 * 这两条只有在"读取入口带 run 身份"时才可能同时成立。若调用方拿的是
 * {@code EngineModelAuthority.requirePublished(...)}（取当前租户最新 PUBLISHED），
 * 那么在 run 存活期间发生一次发布，同一个 run 的前半段和后半段就会用**两份不同的配置**解释 ——
 * 而 run 上记录的 {@code config_revision_id} 还是旧的，账实不符。
 *
 * <p><b>为什么字段这么宽。</b>需要的不只是"模型名"：{@code providerId} 决定端点与凭据引用、
 * {@code paramsHash}/{@code catalogVersion} 决定同一模型名下参数与目录的解释、
 * {@code credentialRef} 是密钥**引用/掩码**（C1.3：不落明文）。把这些一起带出来，
 * 调用方就不必再回头读 YAML 或再查一次库 —— 每一次"回头再读"都是一个可能读到新版本的窗口。
 *
 * @param tenantId      租户
 * @param runId         运行标识
 * @param action        该 run 的动作（诊断用；不参与事实选择）
 * @param revisionId    受理时绑定的发布版本（不可变）
 * @param revisionNo    版本号（租户内单调递增）
 * @param providerId    受理时的提供方
 * @param modelId       受理时的模型
 * @param catalogVersion 受理时的模型目录版本
 * @param paramsHash    受理时的参数哈希
 * @param credentialRef 密钥引用/掩码（**不是**明文）
 * @param operatorId    该版本的发布操作者
 * @param publishedAt   该版本的发布时间
 */
public record RunConfigBinding(
        String tenantId,
        String runId,
        String action,
        String revisionId,
        long revisionNo,
        String providerId,
        String modelId,
        String catalogVersion,
        String paramsHash,
        String credentialRef,
        String operatorId,
        Instant publishedAt) {
}
