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

import java.util.Map;

/**
 * P0.4 新受理/委托协议的统一响应包装（AI 侧）。
 *
 * <p>实测事实（Spec §8.1 的 C1）：AI 既有 {@code Result} 的 code 是字符串且成功值为
 * {@code "0"}，与 platform 的 {@code R}（int code，成功 200）本就不同。新协议不复用任一旧包装，
 * 固定为 {@code {int code, String msg, T data}}：成功 HTTP 202 且 code=200；失败时
 * HTTP 状态与 {@code code} 相同，符号错误码放在 {@code data.errorCode}。
 */
public record ApiEnvelope<T>(int code, String msg, T data) {

    /** 成功业务码。 */
    public static final int SUCCESS_CODE = 200;

    public static <T> ApiEnvelope<T> ok(T data) {
        return new ApiEnvelope<>(SUCCESS_CODE, "success", data);
    }

    public static ApiEnvelope<Map<String, Object>> error(int code, String msg, String errorCode) {
        return new ApiEnvelope<>(code, msg, Map.of("errorCode", errorCode));
    }
}
