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

/**
 * 当前 PUBLISHED 版本的**完整事实**（含 embedding 维度；WP-040 A2 受理侧维度门）。
 *
 * <p><b>为什么不是把 {@code dimension} 塞进 {@link EngineModelAuthority}。</b>
 * {@code EngineModelAuthority.PublishedModel} 是 V15 冻结受理契约（C1.3 字段清单），
 * 加字段 = 改冻结契约。本端口是同一份事实的**超集视图**（返回
 * {@link ConfigRevisionFacts}），实现与 {@link EngineModelAuthority} 同源
 * （{@link PublishedModelAuthority} 同时实现两者），读同一张表同一行 —— 不构成第二权威。
 *
 * <p><b>消费方只有受理侧维度门</b>（{@code RunAdmissionService}）：维度错误
 * ⇒ 响亮拒绝受理（DB 零增量）。端口缺席（旧装配）时门自动跳过 —— 维度不是
 * V15 受理契约的一部分。
 */
public interface PublishedModelFactsPort {

    /** 与冻结迁移一致：统一向量列物理维度固定 1536（V15/V7）。 */
    int REQUIRED_DIMENSION = 1536;

    /**
     * 当前租户 PUBLISHED 中 {@code revision_no} 最大一行的完整事实。
     *
     * @return 事实；当前租户没有 PUBLISHED 版本或读取失败时返回 {@code null}
     *        （受理侧先经 {@link EngineModelAuthority#requirePublished} 做过拒绝判定，
     *         本端口的 null 分支只是防御）
     */
    ConfigRevisionFacts currentPublishedFacts();
}
