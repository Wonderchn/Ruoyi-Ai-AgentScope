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
 * 发布版本事实 + **embedding 维度**（WP-040 A2）。
 *
 * <p><b>为什么不直接复用 {@link EngineModelAuthority.PublishedModel}。</b>那个记录是受理侧
 * 契约（V15 冻结列），加字段就是改冻结契约；而"维度必须与向量列一致"是 WP-040 新增的
 * 管理面事实，属于本波新增，不宜混进已冻结的 record。因此本类型在 PublishedModel 的全部
 * 字段之外**追加** {@link #dimension()}，并保证与 PublishedModel 的同名字段逐一同义。
 */
public record ConfigRevisionFacts(
        String tenantId,
        String revisionId,
        long revisionNo,
        String providerId,
        String modelId,
        String catalogVersion,
        String paramsHash,
        String credentialRef,
        String operatorId,
        Instant publishedAt,
        int dimension) {
}
