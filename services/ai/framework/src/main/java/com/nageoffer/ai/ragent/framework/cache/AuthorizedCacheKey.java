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

package com.nageoffer.ai.ragent.framework.cache;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.exception.ClientException;

/**
 * 授权作用域缓存键（P1.3c）：凡缓存的是"某主体对某资源的内容/判定结果"，
 * 键必须经过本工具构造，禁止调用方自己拼租户键。
 *
 * <p>冻结形状（03 §C08）：
 * <pre>{@code p1:{tenantId}:{memberId}:{action}:{resourceRef}:pv{pv}:av{av}:{docVersion}}</pre>
 * 各段语义：
 * <ul>
 *   <li>{@code tenantId}/{@code memberId}/{@code pv}/{@code av}：来自
 *       {@link ExecutionPrincipal}（canonical membership 形如 {@code platform:T1:2101}，
 *       其冒号是编码的一部分、不是分隔歧义——member 段之后的 action 段被约束为
 *       不含冒号的 canonical 动作名，resourceRef 段自带 {@code kb:1}/{@code doc:2}
 *       前缀，最后一个 docVersion 段不允许冒号，因此固定字段序不会互相冒充）；</li>
 *   <li>{@code action}：本次缓存的判定动作（如 {@code document.download}）；</li>
 *   <li>{@code docVersion}：内容版本段——内容行版本（如资源 resource_version）或
 *       缓存结构版本，由调用方选定并在内容失效时递增，本工具只校验形状。</li>
 * </ul>
 *
 * <p><b>命中不豁免授权。</b>本工具只负责键的形状与归属：缓存命中只能省掉
 * "重新计算内容"，<b>不能</b>省掉授权复核——命中前（或命中后返回前）是否
 * 重新校验主体与授权（当前 epoch 是否仍是 pv/av、资源是否仍 ACTIVE）是
 * <b>调用方的职责</b>。撤权即 epoch bump：只要调用方把 av 放进键并复核版本，
 * 旧键会自然失配；跳过复核的调用方等于把缓存变成了授权的旁路，这是使用错误，
 * 不是本类的语义。
 *
 * <p>本类无状态（无任何字段）：全部校验失败抛 {@link ClientException}，
 * 字段缺失、租户含冒号/超长一律拒绝，绝不"尽力拼接"。
 */
public final class AuthorizedCacheKey {

    /** 键前缀：P1 授权作用域缓存键的保留命名空间。 */
    private static final String PREFIX = "p1";

    private AuthorizedCacheKey() {
    }

    /**
     * 从执行主体构造缓存键（生产入口）。
     *
     * @param principal   当前执行主体（缺失即拒绝）
     * @param action      canonical 动作名；不允许冒号
     * @param resourceRef 资源引用（如 {@code doc:42}）；不允许空白
     * @param docVersion  内容版本段；不允许空白与冒号
     */
    public static String of(ExecutionPrincipal principal, String action,
                            String resourceRef, String docVersion) {
        if (principal == null) {
            throw new ClientException("execution principal is required for an authorized cache key");
        }
        return of(principal.tenantId(), principal.membershipId(), action, resourceRef,
                principal.policyVersion(), principal.aclVersion(), docVersion);
    }

    /**
     * 原始字段构造入口（测试与无主体对象可用的降格形式）；
     * 归属字段必须来自可信来源（已验签主体），业务请求参数不得直接进入本方法。
     */
    public static String of(String tenantId, String memberId, String action, String resourceRef,
                            int policyVersion, int aclVersion, String docVersion) {
        // 租户：与主体契约同一套校验（空白/超长/冒号/首尾空白均拒绝）
        ExecutionPrincipal.requireTenantId(tenantId);
        requireField("memberId", memberId);
        requireField("action", action);
        // action 是字段序的锚点：含冒号会让 action 与 resourceRef 的边界歧义，直接拒绝
        requireNoColon("action", action);
        requireField("resourceRef", resourceRef);
        requireField("docVersion", docVersion);
        requireNoColon("docVersion", docVersion);
        if (policyVersion < 1 || aclVersion < 1) {
            throw new ClientException("policyVersion/aclVersion must be >= 1");
        }
        return PREFIX + ":" + tenantId + ":" + memberId + ":" + action + ":" + resourceRef
                + ":pv" + policyVersion + ":av" + aclVersion + ":" + docVersion;
    }

    private static void requireField(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new ClientException(name + " is required for an authorized cache key");
        }
    }

    private static void requireNoColon(String name, String value) {
        if (value.indexOf(':') >= 0) {
            throw new ClientException(name + " must not contain ':'");
        }
    }
}
