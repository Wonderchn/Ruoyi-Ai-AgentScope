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

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.stream.RunEventStreamService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/** 统一 SSE 订阅端点（P0.3 §3.3；afterSeq / Last-Event-ID 游标）。 */
@RestController
@RequestMapping("/internal/ai/v1")
@ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
@ConditionalOnProperty(name = "p04.enabled", havingValue = "false", matchIfMissing = true)
public class RunStreamController {

    private final RunEventStreamService streamService;

    public RunStreamController(RunEventStreamService streamService) {
        this.streamService = streamService;
    }

    @GetMapping("/runs/{runId}/events")
    public void events(@PathVariable String runId,
                       @RequestParam(value = "afterSeq", required = false) Long afterSeq,
                       @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                       HttpServletResponse response) throws IOException {
        if (!PrincipalContext.hasPrincipal()) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"data\":{\"errorCode\":\"AUTH_REQUIRED\",\"retryable\":false}}");
            return;
        }
        ExecutionPrincipal principal = PrincipalContext.require();
        long cursor = afterSeq != null ? afterSeq : parseCursor(lastEventId);
        if (cursor < 0) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "invalid cursor");
        }
        streamService.stream(principal, runId, cursor, response);
    }

    private long parseCursor(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(lastEventId.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
