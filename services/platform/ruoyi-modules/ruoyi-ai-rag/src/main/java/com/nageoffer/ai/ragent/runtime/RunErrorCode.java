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

package com.nageoffer.ai.ragent.runtime;

import org.springframework.http.HttpStatus;

/**
 * P2 运行面错误码（P0.3 §2.4 契约；HTTP status == body.code，符号码放 data.errorCode）。
 */
public enum RunErrorCode {

    BAD_REQUEST(HttpStatus.BAD_REQUEST, "请求不合法", false),
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "缺少访问凭证", false),
    TENANT_CONTEXT_MISSING(HttpStatus.FORBIDDEN, "缺少租户上下文", false),
    MEMBERSHIP_INVALID(HttpStatus.FORBIDDEN, "成员身份无效", false),
    FORBIDDEN(HttpStatus.FORBIDDEN, "权限不足", false),
    EGRESS_NOT_ALLOWED(HttpStatus.FORBIDDEN, "数据外发未被允许", false),
    RESOURCE_NOT_FOUND_OR_FORBIDDEN(HttpStatus.NOT_FOUND, "资源不存在或无权访问", false),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "幂等键已用于不同请求", false),
    RUN_STATE_CONFLICT(HttpStatus.CONFLICT, "运行状态不允许该操作", false),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "版本或租约代际冲突", true),
    APPROVAL_MISMATCH(HttpStatus.CONFLICT, "审批与持久提案不一致", false),
    APPROVAL_EXPIRED(HttpStatus.CONFLICT, "审批已过期", false),
    RECONCILIATION_REQUIRED(HttpStatus.CONFLICT, "外部动作结果待核对", false),
    CURSOR_EXPIRED(HttpStatus.GONE, "回放游标超出保留期", false),
    BUDGET_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "额度或并发超限", false),
    DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "依赖不可用", true),
    AUTHORIZATION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "授权服务不可用", true),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务内部错误", true);

    private final HttpStatus status;
    private final String message;
    private final boolean retryable;

    RunErrorCode(HttpStatus status, String message, boolean retryable) {
        this.status = status;
        this.message = message;
        this.retryable = retryable;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }

    public boolean retryable() {
        return retryable;
    }
}
