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

import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/** 运行面响应包装：HTTP status == body.code，业务细分放 data.errorCode。 */
public final class RunApiResponses {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private RunApiResponses() {
    }

    public static <T> ResponseEntity<Map<String, Object>> ok(HttpStatus status, T data, String requestId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 200);
        body.put("msg", "success");
        body.put("data", data);
        body.put("requestId", requestId);
        return ResponseEntity.status(status).header(REQUEST_ID_HEADER, requestId).body(body);
    }

    public static <T> ResponseEntity<Map<String, Object>> okWithHeaders(HttpStatus status, T data, String requestId,
                                                                       Map<String, String> headers) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 200);
        body.put("msg", "success");
        body.put("data", data);
        body.put("requestId", requestId);
        var builder = ResponseEntity.status(status).header(REQUEST_ID_HEADER, requestId);
        headers.forEach(builder::header);
        return builder.body(body);
    }

    public static <T> ResponseEntity<Map<String, Object>> accepted(T data, String requestId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 200);
        body.put("msg", "accepted");
        body.put("data", data);
        body.put("requestId", requestId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).header(REQUEST_ID_HEADER, requestId).body(body);
    }

    public static ResponseEntity<Map<String, Object>> fail(RunErrorCode code, String requestId) {
        return fail(code, code.message(), requestId);
    }

    public static ResponseEntity<Map<String, Object>> fail(RunErrorCode code, String message, String requestId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorCode", code.name());
        data.put("retryable", code.retryable());
        data.put("requestId", requestId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code.status().value());
        body.put("msg", message);
        body.put("data", data);
        return ResponseEntity.status(code.status()).header(REQUEST_ID_HEADER, requestId).body(body);
    }
}
