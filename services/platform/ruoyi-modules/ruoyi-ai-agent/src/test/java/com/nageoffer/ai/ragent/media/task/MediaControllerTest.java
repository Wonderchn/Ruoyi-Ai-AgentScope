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

package com.nageoffer.ai.ragent.media.task;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * F19 媒体控制器契约：JSON 信封、GET 交付回执、私有取流/Range、错误映射与身份缺失。
 */
@Tag("dev")
class MediaControllerTest {

    private MediaTaskService service;
    private MockMvc mvc;
    private ExecutionPrincipal principal;
    private ExecutionPrincipal previous;

    @BeforeEach
    void setUp() {
        service = mock(MediaTaskService.class);
        DeliveryPermits permits = new DeliveryPermits(guardProvider());
        mvc = MockMvcBuilders.standaloneSetup(new MediaController(service, permits)).build();
        principal = new ExecutionPrincipal("T1", "1001", "platform:T1:1001", 1, 1,
                Set.of("run.submit", "run.get"), "jti-" + UUID.randomUUID(), "test", 0, Long.MAX_VALUE);
        previous = PrincipalContext.set(principal);
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.restore(previous);
    }

    @Test
    void submitImageReturnsServerTaskEnvelope() throws Exception {
        when(service.submitImage(eq(principal), any())).thenReturn(view("mt-1", "pending", null));

        MvcResult result = mvc.perform(post("/internal/ai/v1/media/image")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"wanx-test\",\"prompt\":\"a red fox\",\"size\":\"1024*1024\","
                                + "\"seed\":7,\"requestId\":\"req-1\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("\"code\":200", "\"id\":\"mt-1\"", "\"status\":\"pending\"");
        // POST 走 JSON 分支：内层不铸交付回执（铸造 = 永久 ACTIVE 许可泄漏）。
        assertThat(result.getResponse().getHeader("X-AI-Delivery-Permit")).isNull();
    }

    @Test
    void predictionAndTaskListCarryDeliveryReceipts() throws Exception {
        when(service.query(eq(principal), eq("mt-1"))).thenReturn(view("mt-1", "completed", "image/png"));
        when(service.list(eq(principal), anyInt()))
                .thenReturn(new MediaTaskView.Page(List.of(view("mt-1", "completed", "image/png"))));

        MvcResult prediction = mvc.perform(get("/internal/ai/v1/media/prediction")
                        .param("model", "wanx-test").param("predictionId", "mt-1"))
                .andReturn();
        assertThat(prediction.getResponse().getStatus()).isEqualTo(200);
        assertThat(prediction.getResponse().getHeader("X-AI-Delivery-Permit"))
                .matches("[0-9a-f-]{36}");
        assertThat(prediction.getResponse().getHeader("X-AI-Delivery-Operation"))
                .matches("[0-9a-f-]{36}");
        assertThat(prediction.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":200", "\"id\":\"mt-1\"");

        MvcResult tasks = mvc.perform(get("/internal/ai/v1/media/tasks").param("limit", "5"))
                .andReturn();
        assertThat(tasks.getResponse().getStatus()).isEqualTo(200);
        assertThat(tasks.getResponse().getHeader("X-AI-Delivery-Permit")).matches("[0-9a-f-]{36}");
        assertThat(tasks.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"items\"", "\"id\":\"mt-1\"");
    }

    @Test
    void contentStreamsPrivateBytesAndHonoursSingleRange() throws Exception {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
        when(service.content(eq(principal), eq("mt-1")))
                .thenReturn(new MediaTaskService.MediaContent(png, "image/png"));

        MvcResult full = mvc.perform(get("/internal/ai/v1/media/content")
                        .param("model", "wanx-test").param("predictionId", "mt-1"))
                .andReturn();
        assertThat(full.getResponse().getStatus()).isEqualTo(200);
        assertThat(full.getResponse().getContentType()).startsWith("image/png");
        assertThat(full.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(full.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(full.getResponse().getHeader("X-AI-Delivery-Permit")).matches("[0-9a-f-]{36}");
        assertThat(full.getResponse().getHeader("Content-Disposition")).contains("inline", "mt-1.png");
        assertThat(full.getResponse().getContentAsByteArray()).isEqualTo(png);

        MvcResult partial = mvc.perform(get("/internal/ai/v1/media/content")
                        .param("predictionId", "mt-1").header("Range", "bytes=0-2"))
                .andReturn();
        assertThat(partial.getResponse().getStatus()).isEqualTo(206);
        assertThat(partial.getResponse().getHeader("Content-Range")).isEqualTo("bytes 0-2/11");
        assertThat(partial.getResponse().getContentAsByteArray())
                .isEqualTo(new byte[]{png[0], png[1], png[2]});
    }

    @Test
    void contentForAnotherOwnerIsOpaque404WithoutPermit() throws Exception {
        when(service.content(any(), eq("mt-x")))
                .thenThrow(new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, "媒体结果不可用"));

        MvcResult result = mvc.perform(get("/internal/ai/v1/media/content").param("predictionId", "mt-x"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getHeader("X-AI-Delivery-Permit")).isNull();
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":404", "RESOURCE_NOT_FOUND_OR_FORBIDDEN");
    }

    @Test
    void retryConflictIs409AndNeverMintsPermit() throws Exception {
        when(service.retry(eq(principal), eq("mt-1"), anyString()))
                .thenThrow(new RunApiException(RunErrorCode.RUN_STATE_CONFLICT, "当前任务状态不允许重试"));

        MvcResult result = mvc.perform(post("/internal/ai/v1/media/tasks/mt-1/retry")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"req-2\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getHeader("X-AI-Delivery-Permit")).isNull();
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":409", "RUN_STATE_CONFLICT");
    }

    @Test
    void missingPrincipalIs401() throws Exception {
        PrincipalContext.clear();

        MvcResult result = mvc.perform(post("/internal/ai/v1/media/image")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"p\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":401", "AUTH_REQUIRED");
    }

    private static MediaTaskView view(String id, String status, String mime) {
        return new MediaTaskView(id, "image", "Tongyiwanx", status, "wanx-test", "a red fox", mime,
                null, null, 1, null, null, "2026-10-19T00:00:00Z", "2026-10-19T00:00:00Z");
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RevocationGuard> guardProvider() {
        RevocationGuard guard = new RevocationGuard() {
            @Override
            public PermitGrant acquire(PermitRequest request) {
                return new PermitGrant(UUID.randomUUID().toString(), request.aclVersion());
            }

            @Override
            public void release(String permitId, String operationId) {
            }

            @Override
            public BarrierState barrierState(String tenantId) {
                return BarrierState.NO_ROW;
            }

            @Override
            public long activePermitCount(String tenantId) {
                return 0;
            }

            @Override
            public void setBarrierState(String tenantId, BarrierState state, String barrierId,
                                        Integer targetAclVersion, String reason) {
            }
        };
        ObjectProvider<RevocationGuard> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(guard);
        return provider;
    }
}
