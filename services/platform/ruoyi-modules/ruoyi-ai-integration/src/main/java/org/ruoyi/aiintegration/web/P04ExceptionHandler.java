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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 本实验内部端点的独立异常映射。
 *
 * <p>刻意<b>不</b>复用 platform 既有的响应包装与全局异常处理约定，也刻意<b>不</b>让 AI 侧
 * 沿用「客户端错误返回 HTTP 200 + body code」的遗留约定：本协议的失败响应要求
 * {@code HTTP status == body.code}，符号码放在 {@code data.errorCode}（Spec §8.1 C7）。
 *
 * <p>作用范围限制在本模块包内（{@code basePackages}），避免影响其它模块的既有行为。
 */
@RestControllerAdvice(basePackages = "org.ruoyi.aiintegration")
public class P04ExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(P04ExceptionHandler.class);

    @ExceptionHandler(P04Exception.class)
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> handleP04(P04Exception ex) {
        P04ErrorCode errorCode = ex.errorCode();
        // 只记录符号码与 requestId；不输出堆栈、SQL、密钥或其它租户信息
        log.warn("p04 rejected errorCode={} requestId={}", errorCode.name(), RequestId.currentOrEmpty());
        return ResponseEntity.status(errorCode.httpStatus())
                .header(RequestId.HEADER, RequestId.currentOrEmpty())
                .body(ApiResponse.error(errorCode.httpStatus(), ex.getMessage(), errorCode.name()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> handleUnexpected(Exception ex) {
        log.error("p04 unexpected failure requestId={} type={}", RequestId.currentOrEmpty(), ex.getClass().getName());
        return ResponseEntity.status(P04ErrorCode.INTERNAL_ERROR.httpStatus())
                .header(RequestId.HEADER, RequestId.currentOrEmpty())
                .body(ApiResponse.error(P04ErrorCode.INTERNAL_ERROR.httpStatus(),
                        P04ErrorCode.INTERNAL_ERROR.message(), P04ErrorCode.INTERNAL_ERROR.name()));
    }
}
