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
 * 携带 {@link P04ErrorCode} 的受控异常；由 {@link P04ExceptionHandler} 映射为
 * 「HTTP 状态 == body code」的失败响应。
 */
public class P04Exception extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final P04ErrorCode errorCode;

    public P04Exception(P04ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    /**
     * @param message 对外消息；<b>不得</b>包含 SQL、密钥、堆栈或其他租户信息
     */
    public P04Exception(P04ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public P04ErrorCode errorCode() {
        return errorCode;
    }
}
