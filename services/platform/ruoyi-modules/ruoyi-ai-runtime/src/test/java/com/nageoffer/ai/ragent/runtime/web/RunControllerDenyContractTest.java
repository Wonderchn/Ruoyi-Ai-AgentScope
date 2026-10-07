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

import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.runtime.P2RuntimeProperties;
import com.nageoffer.ai.ragent.runtime.RunAdmissionService;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.RunLifecycleService;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Set;

import static org.hamcrest.Matchers.isA;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 运行面生命周期端点的**拒绝契约**判据（rag.chat / agent.run 共用的 {@code /runs/**}）。
 *
 * <p>背景（RW-01 实测的缺陷）：{@link RunController#cancel} / {@link RunController#resume}
 * 此前把平台功能级判定抛出的 {@link P04AiException} 与真正的服务端故障一起收进
 * {@code catch (RuntimeException)} ⇒ 一律 500 {@code INTERNAL_ERROR}。
 * 于是"有 scope 但功能/资源级不通过"（应为 403）、"策略版本过期"（应为 409）、
 * "授权事实源不可用"（应为 503）在客户端与"服务端崩了"完全同形 —— 与同类的
 * {@code submit} 分支（显式 rethrow 给全局映射器）以及
 * {@code AiResourceController} 的同一判定口径都不一致。
 *
 * <p>本判据钉住修好之后的形状：<b>HTTP status == body.code（整数）</b>，
 * 符号码放 {@code data.errorCode}，且各拒绝原因彼此可区分。
 */
@Tag("dev")
class RunControllerDenyContractTest {

    private static final String TENANT = "t1";
    private static final String MEMBER = "platform:t1:1001";
    private static final String USER = "1001";

    private RunAdmissionService admission;
    private RunLifecycleService lifecycle;
    private AiResourceAuthorizationService authorization;
    private MockMvc mvc;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        admission = mock(RunAdmissionService.class);
        lifecycle = mock(RunLifecycleService.class);
        authorization = mock(AiResourceAuthorizationService.class);
        ObjectProvider<AiResourceAuthorizationService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(authorization);
        mvc = MockMvcBuilders.standaloneSetup(
                        new RunController(admission, lifecycle, new P2RuntimeProperties(), provider))
                .setControllerAdvice(new RunApiExceptionHandler())
                .build();
        principal(Set.of("run.cancel", "run.resume", "run.submit"));
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    private static void principal(Set<String> scopes) {
        PrincipalContext.set(new ExecutionPrincipal(TENANT, USER, MEMBER, 1, 1, scopes, "jti", "platform",
                Instant.now().getEpochSecond(), 9999999999L));
    }

    private void platformDeniesWith(P04AiErrorCode code) {
        doAnswer(invocation -> {
            throw new P04AiException(code);
        }).when(authorization).requireFunction(any(), anyString(), anyString());
    }

    private static RunRecord run() {
        return new RunRecord(TENANT, "run-1", MEMBER, USER, "rag.chat", "RUNNING", "hash", "key",
                "{}", "{}", "p2-v1", 1, 1, "[]", 3L, 4L, 1, 7L,
                "worker-1", null, null, null, null, null, Instant.EPOCH, Instant.EPOCH, null);
    }

    // ------------------------------------------------------------ 成功路径

    @Test
    @DisplayName("resume 成功：200 + 整数 code=200 + data.version（客户端据此继续 CAS）")
    void resumeSuccessEnvelope() throws Exception {
        when(lifecycle.resume(any(), eq("run-1"), eq(3L))).thenReturn(run());
        mvc.perform(post("/internal/ai/v1/runs/run-1/resume")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", isA(Integer.class)))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.runId").value("run-1"))
                .andExpect(jsonPath("$.data.version").value(3));
    }

    @Test
    @DisplayName("cancel 成功：200 + 整数 code=200 + 运行快照")
    void cancelSuccessEnvelope() throws Exception {
        when(lifecycle.cancel(any(), eq("run-1"), eq(3L))).thenReturn(run());
        mvc.perform(post("/internal/ai/v1/runs/run-1/cancel")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("RUNNING"));
    }

    // ------------------------------------------------------------ 修复判据：平台拒绝不得收敛成 500

    @Test
    @DisplayName("修复判据：cancel 平台功能级拒绝 ⇒ 403 FORBIDDEN（原为 500 INTERNAL_ERROR）")
    void cancelForbiddenNotServerError() throws Exception {
        platformDeniesWith(P04AiErrorCode.FORBIDDEN);
        mvc.perform(post("/internal/ai/v1/runs/run-1/cancel")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", isA(Integer.class)))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.data.errorCode").value("FORBIDDEN"));
        verify(lifecycle, never()).cancel(any(), anyString(), any());
    }

    @Test
    @DisplayName("修复判据：resume 平台功能级拒绝 ⇒ 403 FORBIDDEN（原为 500 INTERNAL_ERROR）")
    void resumeForbiddenNotServerError() throws Exception {
        platformDeniesWith(P04AiErrorCode.FORBIDDEN);
        mvc.perform(post("/internal/ai/v1/runs/run-1/resume")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.data.errorCode").value("FORBIDDEN"));
        verify(lifecycle, never()).resume(any(), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("修复判据：策略版本过期 ⇒ 409 POLICY_VERSION_STALE（原为 500）")
    void stalePolicyVersionIsConflict() throws Exception {
        platformDeniesWith(P04AiErrorCode.POLICY_VERSION_STALE);
        mvc.perform(post("/internal/ai/v1/runs/run-1/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.data.errorCode").value("POLICY_VERSION_STALE"));
    }

    @Test
    @DisplayName("修复判据：授权事实源不可用 ⇒ 503 AUTHORIZATION_UNAVAILABLE（原为 500）")
    void authorizationUnavailableIsServiceUnavailable() throws Exception {
        platformDeniesWith(P04AiErrorCode.AUTHORIZATION_UNAVAILABLE);
        mvc.perform(post("/internal/ai/v1/runs/run-1/resume").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503))
                // P04AiException 走全局映射器（ApiEnvelope.error）：形状是 {code,msg,data.errorCode}，
                // 与运行面自有的 RunApiResponses.fail（多一个 data.retryable）**刻意不同** ——
                // 本判据只钉住两者共有的部分，不为统一形状而改动既有公开面。
                .andExpect(jsonPath("$.data.errorCode").value("AUTHORIZATION_UNAVAILABLE"));
    }

    // ------------------------------------------------------------ 身份 / scope / 状态

    @Test
    @DisplayName("无身份 ⇒ 401 AUTH_REQUIRED")
    void missingPrincipal() throws Exception {
        PrincipalContext.clear();
        mvc.perform(post("/internal/ai/v1/runs/run-1/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.data.errorCode").value("AUTH_REQUIRED"));
        verifyNoInteractions(lifecycle);
    }

    @Test
    @DisplayName("无 scope ⇒ 403 FORBIDDEN，且不查平台判定、不动生命周期")
    void missingScope() throws Exception {
        principal(Set.of("run.stream"));
        mvc.perform(post("/internal/ai/v1/runs/run-1/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("FORBIDDEN"));
        verify(authorization, never()).requireFunction(any(), anyString(), anyString());
        verifyNoInteractions(lifecycle);
    }

    @Test
    @DisplayName("跨租户/不存在 ⇒ 404 RESOURCE_NOT_FOUND_OR_FORBIDDEN")
    void crossTenantIsNotFound() throws Exception {
        when(lifecycle.cancel(any(), eq("run-x"), any()))
                .thenThrow(new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        mvc.perform(post("/internal/ai/v1/runs/run-x/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.data.errorCode").value("RESOURCE_NOT_FOUND_OR_FORBIDDEN"));
    }

    @Test
    @DisplayName("版本/租约代际冲突 ⇒ 409 VERSION_CONFLICT（可重试）")
    void versionConflict() throws Exception {
        when(lifecycle.cancel(any(), eq("run-1"), eq(2L)))
                .thenThrow(new RunApiException(RunErrorCode.VERSION_CONFLICT));
        mvc.perform(post("/internal/ai/v1/runs/run-1/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.data.errorCode").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.data.retryable").value(true));
    }

    @Test
    @DisplayName("resume 缺 expectedVersion ⇒ 400（不得默认 0 / 静默放行）")
    void resumeWithoutExpectedVersion() throws Exception {
        mvc.perform(post("/internal/ai/v1/runs/run-1/resume").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.data.errorCode").value("BAD_REQUEST"));
        verifyNoInteractions(lifecycle);
    }

    @Test
    @DisplayName("submit 无身份 ⇒ 401，且不进入受理")
    void submitWithoutPrincipal() throws Exception {
        PrincipalContext.clear();
        mvc.perform(post("/internal/ai/v1/runs").header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schemaVersion\":1,\"action\":\"rag.chat\",\"input\":{\"text\":\"x\"}}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.data.errorCode").value("AUTH_REQUIRED"));
        verifyNoInteractions(admission);
    }
}
