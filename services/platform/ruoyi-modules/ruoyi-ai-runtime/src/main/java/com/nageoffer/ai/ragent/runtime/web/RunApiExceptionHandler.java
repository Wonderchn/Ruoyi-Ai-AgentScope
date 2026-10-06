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

package com.nageoffer.ai.ragent.runtime.web;

import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 运行面异常映射；不泄露 SQL/密钥/堆栈/其他租户标识。 */
@RestControllerAdvice(basePackages = {"com.nageoffer.ai.ragent.runtime.web","com.nageoffer.ai.ragent.ingest"})
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class RunApiExceptionHandler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RunApiExceptionHandler.class);

    @ExceptionHandler(com.nageoffer.ai.ragent.framework.security.P04AiException.class)
    public ResponseEntity<?> handleSecurity(com.nageoffer.ai.ragent.framework.security.P04AiException e) {
        var code=e.errorCode();
        return ResponseEntity.status(code.httpStatus()).body(com.nageoffer.ai.ragent.framework.security.ApiEnvelope.error(
                code.httpStatus(),code.message(),code.name()));
    }

    @ExceptionHandler(RunApiException.class)
    public ResponseEntity<Map<String, Object>> handle(RunApiException e) {
        String requestId = "req-" + java.util.UUID.randomUUID().toString().replace("-", "");
        // §6.1-11（W3-T0-11 家族，W4 落地）：fail-closed 的 5xx 拒绝必须有**服务端可归因**日志，
        // 否则"拒绝"与"崩了"在证据面不可分（本 handler 原先零日志，正是 W3-2 两轮"零 reason 行"成因）。
        // 口径（T2r 租约方条件）：只 log.warn、不动响应体/状态/契约；requestId 与客户端所见同值；
        // reason 用**已经进客户端响应体 msg 的同一串**（下一行 fail(..., e.getMessage(), ...)），不新开披露面。
        if (e.errorCode().status().is5xxServerError()) {
            log.warn("run request rejected type={} code={} httpStatus={} requestId={} reason={}",
                    e.getClass().getSimpleName(), e.errorCode().name(), e.errorCode().status().value(),
                    requestId, e.getMessage());
        }
        return RunApiResponses.fail(e.errorCode(), e.getMessage(), requestId);
    }

    @ExceptionHandler({org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.multipart.MaxUploadSizeExceededException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String,Object>> invalidInput(Exception e) {
        return RunApiResponses.fail(RunErrorCode.BAD_REQUEST,"req-"+java.util.UUID.randomUUID());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(RuntimeException e) {
        log.warn("run request rejected type={} cause={}", e.getClass().getSimpleName(),
                e.getCause() == null ? "none" : e.getCause().getClass().getSimpleName());
        String requestId = "req-" + java.util.UUID.randomUUID().toString().replace("-", "");
        return RunApiResponses.fail(RunErrorCode.INTERNAL_ERROR, requestId);
    }
}
