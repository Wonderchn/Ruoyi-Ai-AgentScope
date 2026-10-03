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
@RestControllerAdvice(basePackages = "com.nageoffer.ai.ragent.runtime.web")
public class RunApiExceptionHandler {

    @ExceptionHandler(RunApiException.class)
    public ResponseEntity<Map<String, Object>> handle(RunApiException e) {
        String requestId = "req-" + java.util.UUID.randomUUID().toString().replace("-", "");
        return RunApiResponses.fail(e.errorCode(), e.getMessage(), requestId);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(RuntimeException e) {
        String requestId = "req-" + java.util.UUID.randomUUID().toString().replace("-", "");
        return RunApiResponses.fail(RunErrorCode.INTERNAL_ERROR, requestId);
    }
}
