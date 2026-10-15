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

package com.nageoffer.ai.ragent.attachment;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F04 真机窗（2026-10-16）抓出的上传信封缺陷回归判据。
 *
 * <p><b>缺陷</b>：{@code POST /api/ai/v1/chat/attachments} 成功时 HTTP=201 但 body.code 恒为 200
 * （{@code RunApiResponses.ok} 写死 200）。本地网关转发对 2xx 响应执行
 * {@code requireSingleJsonObject}：要求 {@code body.code == HTTP status}（仅 202/200 为登记例外）
 * ⇒ 201/200 被判 "missing envelope code" → 客户端实际收到 503「授权服务不可用」，
 * 上传路径在此形态下**不可用**（真机日志锚：ai-gateway upstream-unavailable reason=missing envelope code）。
 *
 * <p><b>契约对齐</b>：本判据钉住 201/201 —— 与前端 {@code attachments.ts}
 * （要求 status===201 且 body.code===201）及网关信封规则同时一致。
 */
@Tag("dev")
class ChatAttachmentUploadEnvelopeTest {

    private static final String TENANT = "000000";

    @AfterEach
    void clear() {
        PrincipalContext.clear();
    }

    @Test
    @DisplayName("上传成功信封：HTTP 201 且 body.code=201（网关 requireSingleJsonObject 口径）")
    void creationResponseKeepsEnvelopeCodeEqualToHttpStatus() throws Exception {
        ChatAttachmentService service = mock(ChatAttachmentService.class);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", "att-" + "0".repeat(32));
        payload.put("filename", "x.txt");
        payload.put("mimeType", "text/plain");
        payload.put("sizeBytes", 5);
        payload.put("sha256", "0".repeat(64));
        payload.put("version", 1);
        when(service.upload(any(), any(), any(), any())).thenReturn(payload);

        ChatAttachmentController controller = new ChatAttachmentController(service,
                mock(DeliveryPermits.class));

        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(5L);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream("hello".getBytes()));
        when(file.getOriginalFilename()).thenReturn("x.txt");
        when(file.getContentType()).thenReturn("text/plain");

        Part part = mock(Part.class);
        when(part.getName()).thenReturn("file");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParts()).thenReturn(List.of(part));

        PrincipalContext.set(new ExecutionPrincipal(TENANT, "2101", "platform:" + TENANT + ":2101",
                1, 1, Set.of("document.upload"), "jti", "platform", 0, Long.MAX_VALUE));

        ResponseEntity<?> response = controller.upload(file, request);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        Object code = ((Map<?, ?>) response.getBody()).get("code");
        assertThat(code).as("body.code 必须是整数 201（HTTP status 与信封码一致，网关据此放行）")
                .isInstanceOf(Number.class);
        assertThat(((Number) code).intValue()).isEqualTo(201);
    }
}
