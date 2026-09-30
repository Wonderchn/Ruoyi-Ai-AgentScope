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

import java.util.Map;

/**
 * P0.4 新受理/委托协议的统一响应包装。
 *
 * <p>口径（见 P0.4 v1.0 Spec §8.1 的 C1/C7）：本协议<b>不复用</b> platform 既有的
 * {@code org.ruoyi.common.core.domain.R}，也不复用 AI 侧的 {@code Result}
 * （后者 code 是字符串且成功值为 {@code "0"}）。本协议固定为
 * {@code {int code, String msg, T data}}：成功时 HTTP 202 且 code=200；
 * 失败时 HTTP 状态与 {@code code} 相同，符号错误码放在 {@code data.errorCode}。
 *
 * @param code 业务码；成功固定 200，失败与 HTTP 状态一致
 * @param msg  人类可读消息（不得包含 SQL、密钥、堆栈或其他租户信息）
 * @param data 业务数据
 */
public record ApiResponse<T>(int code, String msg, T data) {

    /** 成功业务码。 */
    public static final int SUCCESS_CODE = 200;

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(SUCCESS_CODE, "success", data);
    }

    /**
     * 构造失败响应；{@code data.errorCode} 承载符号错误码。
     */
    public static ApiResponse<Map<String, Object>> error(int code, String msg, String errorCode) {
        return new ApiResponse<>(code, msg, Map.of("errorCode", errorCode));
    }
}
