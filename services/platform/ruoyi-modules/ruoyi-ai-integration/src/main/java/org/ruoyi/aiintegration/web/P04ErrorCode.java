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

package org.ruoyi.aiintegration.web;

/**
 * P0.4 内部端点的符号错误码与 HTTP 状态映射。
 *
 * <p>本枚举只服务本实验新增的内部端点，<b>不</b>修改任何共享错误码定义
 * （见 Spec §8.1 的 C6）。失败响应遵循同一口径：HTTP 状态 == body code，
 * 符号码放在 {@code data.errorCode}。
 */
public enum P04ErrorCode {

    /** 请求本身不合法。 */
    BAD_REQUEST(400, "请求不合法"),

    /** 未携带任何凭证。 */
    AUTH_REQUIRED(401, "缺少访问凭证"),

    /** 凭证存在但校验不通过（签名、算法、iss/aud/kid、过期、缺 claim 等统一对外为同一码，避免泄露细节）。 */
    DELEGATION_INVALID(401, "委托凭证无效"),

    /** 缺少租户/成员上下文，或请求体/请求头试图自带身份。 */
    TENANT_CONTEXT_MISSING(403, "缺少租户上下文"),

    /** 租户不存在或已停用。 */
    TENANT_DISABLED(403, "租户不可用"),

    /** 成员不属于该租户、已禁用，或 sub 与 mid 不匹配。 */
    MEMBERSHIP_INVALID(403, "成员身份无效"),

    /** 功能权限不足。 */
    FORBIDDEN(403, "权限不足"),

    /** 资源无权访问或不存在；两种情况统一返回，避免泄露存在性。 */
    RESOURCE_NOT_FOUND_OR_FORBIDDEN(404, "资源不存在或无权访问"),

    /** 同一 Idempotency-Key 被用于不同请求体。 */
    IDEMPOTENCY_KEY_REUSED(409, "幂等键已用于不同请求"),

    /** 客户端策略版本落后。 */
    POLICY_VERSION_STALE(409, "策略版本过期"),

    /** 授权复核依赖不可用（超时/503/坏响应）；不放行。 */
    AUTHORIZATION_UNAVAILABLE(503, "授权服务不可用"),

    /** 未预期的服务内部错误；不得向客户端输出堆栈或 SQL。 */
    INTERNAL_ERROR(500, "服务内部错误");

    private final int httpStatus;
    private final String message;

    P04ErrorCode(int httpStatus, String message) {
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
