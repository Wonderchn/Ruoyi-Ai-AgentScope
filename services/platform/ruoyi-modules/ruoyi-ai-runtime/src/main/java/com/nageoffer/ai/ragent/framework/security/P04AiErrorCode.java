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

package com.nageoffer.ai.ragent.framework.security;

/**
 * P0.4 内部端点的符号错误码与 HTTP 状态映射（AI 侧）。
 *
 * <p>与 platform 侧 {@code org.ruoyi.aiintegration.web.P04ErrorCode} <b>逐项镜像</b>：
 * 两个构建根不共享代码，符号码必须保持一致，否则跨服务断言无法对齐。
 *
 * <p>本枚举只服务本实验新增的内部端点，<b>不</b>修改 AI 既有的
 * {@code BaseErrorCode}（共享组件，见 Spec §8.1 的 C6），也不沿用其
 * 「客户端错误返回 HTTP 200」的遗留约定。
 */
public enum P04AiErrorCode {

    /** 请求本身不合法（未知字段、缺必需字段等）。 */
    BAD_REQUEST(400, "请求不合法"),

    /** 缺少访问凭证。 */
    AUTH_REQUIRED(401, "缺少访问凭证"),

    /** 凭证无效：签名、算法、iss/aud/kid、时间窗、缺 sub/jti/time，或 jti 重放。 */
    DELEGATION_INVALID(401, "委托凭证无效"),

    /** 缺少租户/成员上下文，或请求试图自带身份。 */
    TENANT_CONTEXT_MISSING(403, "缺少租户上下文"),

    /** 租户不存在或已停用。 */
    TENANT_DISABLED(403, "租户不可用"),

    /** 成员不属于该租户、已禁用，或 sub 与 mid 不匹配。 */
    MEMBERSHIP_INVALID(403, "成员身份无效"),

    /** 功能权限不足。 */
    FORBIDDEN(403, "权限不足"),

    /** 资源无权访问或不存在；统一返回，避免泄露存在性。 */
    RESOURCE_NOT_FOUND_OR_FORBIDDEN(404, "资源不存在或无权访问"),

    /** 同一 Idempotency-Key 被用于不同请求体。 */
    IDEMPOTENCY_KEY_REUSED(409, "幂等键已用于不同请求"),

    /** 客户端策略版本落后。 */
    POLICY_VERSION_STALE(409, "策略版本过期"),

    /**
     * 资源版本冲突（D10）：请求携带的 {@code expectedVersion} 与当前持久化版本不一致。
     *
     * <p>与 {@link #POLICY_VERSION_STALE} <b>刻意分开</b>：后者是授权/许可 epoch 语义
     * （{@code LocalPlatformPermits} 对其做专门分支），若复用会把"改名并发冲突"
     * 误判成"授权版本过期"。本码只表达**资源自身的乐观锁冲突**。
     */
    RESOURCE_VERSION_CONFLICT(409, "资源版本冲突"),

    /**
     * 资源 ID 冲突（T3 缺口登记 → T0 裁决 (a)）：**跨租户**出现同一个客户端自带资源 ID。
     *
     * <p>语义边界（三分支，缺一不可）：
     * <ol>
     *   <li><b>同租户同 ID</b> → 视为**幂等命中**，返回既有资源，<b>不</b>走本码；</li>
     *   <li><b>跨租户同 ID</b> → 本码（409）；</li>
     *   <li><b>未冲突</b> → 正常创建。</li>
     * </ol>
     *
     * <p>为什么不能被单列主键的驱动异常替代：`ai_knowledge_vector.id` 是单列 PK
     * （V7:654，另有 {@code uk(tenant_id,id)} V7:2076），跨租户撞 ID 时驱动会抛
     * {@code duplicate key}。**不得把驱动异常原样冒给客户端** —— 服务端应先做一次
     * <b>租户限定</b>的存在性查询（{@code WHERE tenant_id=? AND id=?}）命中即拒；
     * 未被该查询拦下的那一支，须把驱动异常**翻译**成本码。
     *
     * <p>与 {@link #RESOURCE_VERSION_CONFLICT} <b>刻意分开</b>：后者是"同一资源的乐观锁版本不符"，
     * 前者是"ID 已被**另一个租户**占用"。混用会让客户端无法区分"重试即可"与"换 ID 即可"。
     * 与平台侧 {@code P04ErrorCode.RESOURCE_ID_CONFLICT} 逐项镜像，两侧不得单方面漂移。
     */
    RESOURCE_ID_CONFLICT(409, "资源 ID 冲突"),

    /** 授权复核依赖不可用（超时/503/坏响应）；不放行。 */
    AUTHORIZATION_UNAVAILABLE(503, "授权服务不可用"),

    /** 未预期的服务内部错误；不得向客户端输出堆栈、SQL 或密钥。 */
    INTERNAL_ERROR(500, "服务内部错误");

    private final int httpStatus;
    private final String message;

    P04AiErrorCode(int httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String message() {
        return message;
    }
}
