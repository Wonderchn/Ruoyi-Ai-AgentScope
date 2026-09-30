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
 * 携带 {@link P04AiErrorCode} 的受控异常。
 *
 * <p>只由本实验新增的内部端点抛出，由 {@link P04AiExceptionHandler} 映射为
 * {@code HTTP status == body.code} 的失败响应。
 */
public class P04AiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final P04AiErrorCode errorCode;

    public P04AiException(P04AiErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    /**
     * @param message 对外消息；<b>不得</b>包含 SQL、密钥、堆栈或其他租户信息
     */
    public P04AiException(P04AiErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public P04AiErrorCode errorCode() {
        return errorCode;
    }
}
