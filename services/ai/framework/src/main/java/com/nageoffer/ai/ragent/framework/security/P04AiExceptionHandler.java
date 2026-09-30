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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 本实验内部端点的独立异常映射（AI 侧）。
 *
 * <p>为什么不能复用 AI 既有的 {@code GlobalExceptionHandler}：它按产品约定把客户端错误映射成
 * <b>HTTP 200 + body code</b>，而本协议要求 {@code HTTP status == body.code}
 * （Spec §8.1 的 C7）。作用范围用 {@code basePackages} 限定在新内部端点，既有行为不变。
 */
@RestControllerAdvice(basePackages = {
        "com.nageoffer.ai.ragent.rag.runtime",
        "com.nageoffer.ai.ragent.framework.security"
})
public class P04AiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(P04AiExceptionHandler.class);

    @ExceptionHandler(P04AiException.class)
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> handleP04(P04AiException ex) {
        P04AiErrorCode errorCode = ex.errorCode();
        // 只记录符号码与 requestId；绝不输出堆栈、SQL、密钥或其它租户信息
        log.warn("p04 rejected errorCode={} requestId={}", errorCode.name(), AiRequestIdFilter.currentOrEmpty());
        return ResponseEntity.status(errorCode.httpStatus())
                .header(AiRequestIdFilter.HEADER, AiRequestIdFilter.currentOrEmpty())
                .body(ApiEnvelope.error(errorCode.httpStatus(), ex.getMessage(), errorCode.name()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> handleUnexpected(Exception ex) {
        log.error("p04 unexpected failure requestId={} type={}",
                AiRequestIdFilter.currentOrEmpty(), ex.getClass().getName());
        return ResponseEntity.status(P04AiErrorCode.INTERNAL_ERROR.httpStatus())
                .header(AiRequestIdFilter.HEADER, AiRequestIdFilter.currentOrEmpty())
                .body(ApiEnvelope.error(P04AiErrorCode.INTERNAL_ERROR.httpStatus(),
                        P04AiErrorCode.INTERNAL_ERROR.message(), P04AiErrorCode.INTERNAL_ERROR.name()));
    }
}
