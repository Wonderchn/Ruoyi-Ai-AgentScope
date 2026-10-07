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

/**
 * AI 侧资源归属与 ACL 判定（D2=A：资源事实由 AI 决定）。
 *
 * <p>实测事实（Spec §8.1 的 C4）：AI 基线<b>没有</b> tenant/ACL 列——{@code t_knowledge_base}
 * 只有 {@code created_by}/{@code deleted}。因此 P0.4 的资源 ACL 由<b>测试替身</b>提供，
 * 真实 ACL 模型与持久化属 P1。本接口因此是可注入的。
 */
public interface AclProvider {

    /** Current tenant ACL revision; an absent revision must fail closed. */
    default int aclVersion(String tenantId) {
        throw new IllegalStateException("ACL version provider is required");
    }

    /**
     * @return 该成员是否可对指定资源执行本动作；资源不存在或无权时必须返回 {@code false}
     *         （对外统一 404，避免泄露存在性）
     */
    boolean canAccess(String tenantId, String membershipId, String action, String resourceRef);
}
