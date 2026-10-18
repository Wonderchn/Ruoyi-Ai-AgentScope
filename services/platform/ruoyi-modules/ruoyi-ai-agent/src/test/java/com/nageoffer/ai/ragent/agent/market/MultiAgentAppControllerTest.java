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

package com.nageoffer.ai.ragent.agent.market;

import com.nageoffer.ai.ragent.authorization.AiDeliveryPermitConfiguration;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F14:2 市场 GET 的边界判据（scope + 交付回执 + 404 语义）。
 *
 * <p>与 F09:4-A 同形：内层复核 {@code agent.execute}（缺主体 401、缺 scope 403）；
 * 成功路径铸造两个 UUID 回执头（网关 GET 字节分支强制校验）；不可见时在铸许可
 * <b>之前</b>返回 404，且私有/停用/软删/跨租户共用同一文案「应用不存在」。
 */
@Tag("dev")
class MultiAgentAppControllerTest {

    private static final String PERMIT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String OPERATION_ID = "22222222-2222-2222-2222-222222222222";
    private static final String APP_ID = "0123456789abcdef0123456789abcdef";
    private static final String AGENTS = "[{\"name\":\"analyst\",\"instruction\":\"analyze\"},"
            + "{\"name\":\"critic\",\"instruction\":\"critique\"}]";

    private MultiAgentAppService service;
    private RevocationGuard revocations;
    private AiDeliveryPermitConfiguration.DeliveryPermitHolder deliveryPermits;
    private ExecutionPrincipal previousPrincipal;

    @BeforeEach
    void setUp() {
        service = mock(MultiAgentAppService.class);
        revocations = mock(RevocationGuard.class);
        deliveryPermits = mock(AiDeliveryPermitConfiguration.DeliveryPermitHolder.class);
        previousPrincipal = PrincipalContext.get();
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.restore(previousPrincipal);
    }

    private MultiAgentAppController controller() {
        MultiAgentAppController controller = new MultiAgentAppController(service);
        controller.setRevocations(revocations);
        controller.setDeliveryPermits(deliveryPermits);
        return controller;
    }

    private void authenticate(Set<String> scopes) {
        PrincipalContext.set(new ExecutionPrincipal(
                "t1", "1", "platform:t1:1", 3, 4, scopes,
                "jti-market-test", "test", 0L, Long.MAX_VALUE));
    }

    private MultiAgentAppService.AppPage page() {
        return new MultiAgentAppService.AppPage(
                List.of(new MultiAgentAppService.AppView(APP_ID, "名册应用", "描述", "SUPERVISOR",
                        List.of(new MultiAgentAppService.AppAgent("analyst", "analyze"),
                                new MultiAgentAppService.AppAgent("critic", "critique")))),
                1L, 1L, 12L);
    }

    private void guardGranted(String ref) {
        when(revocations.enter(any(), eq("agent.execute"), eq(ref)))
                .thenReturn(new RevocationGuard.Operation(revocations, PERMIT_ID, OPERATION_ID));
    }

    @Test
    @DisplayName("搜索成功：整数 envelope + 两个回执头 + 登记 permit + no-store")
    void searchReturnsDeliveryReceipts() {
        authenticate(Set.of("agent.execute"));
        MultiAgentAppService.AppPage page = page();
        when(service.search("t1", "季度", 2, 12)).thenReturn(page);
        guardGranted("multi-agent:public");

        ResponseEntity<ApiEnvelope<MultiAgentAppService.AppPage>> response =
                controller().search(2, 12, "季度");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst("X-AI-Delivery-Permit")).isEqualTo(PERMIT_ID);
        assertThat(response.getHeaders().getFirst("X-AI-Delivery-Operation")).isEqualTo(OPERATION_ID);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ApiEnvelope.SUCCESS_CODE);
        assertThat(response.getBody().data()).isSameAs(page);
        verify(deliveryPermits).register(any(), eq(PERMIT_ID), eq(OPERATION_ID));
        verify(revocations).enter(any(), eq("agent.execute"), eq("multi-agent:public"));
    }

    @Test
    @DisplayName("详情成功：回执 ref 带 uuid，投影只含市场字段")
    void detailReturnsDeliveryReceipts() {
        authenticate(Set.of("agent.execute"));
        MultiAgentAppService.AppView view = page().records().get(0);
        when(service.getPublic("t1", APP_ID)).thenReturn(view);
        guardGranted("multi-agent:public:" + APP_ID);

        ResponseEntity<ApiEnvelope<?>> response = controller().detail(APP_ID);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("X-AI-Delivery-Permit")).isEqualTo(PERMIT_ID);
        assertThat(response.getHeaders().getFirst("X-AI-Delivery-Operation")).isEqualTo(OPERATION_ID);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).isSameAs(view);
    }

    @Test
    @DisplayName("不可见（私有/停用/软删/跨租户同一路径）：404 同文案，铸许可之前返回")
    void invisibleDetailIs404BeforeAnyPermit() {
        authenticate(Set.of("agent.execute"));
        when(service.getPublic("t1", "missing")).thenReturn(null);

        ResponseEntity<ApiEnvelope<?>> response = controller().detail("missing");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst("X-AI-Delivery-Permit")).isNull();
        assertThat(response.getHeaders().getFirst("X-AI-Delivery-Operation")).isNull();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(404);
        assertThat(response.getBody().msg()).isEqualTo("应用不存在");
        assertThat(response.getBody().data())
                .isEqualTo(java.util.Map.of("errorCode", "RESOURCE_NOT_FOUND_OR_FORBIDDEN"));
        verify(revocations, never()).enter(any(), anyString(), anyString());
        verify(deliveryPermits, never()).register(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("缺执行主体：401，且不触碰数据面")
    void missingPrincipalIs401() {
        PrincipalContext.clear();

        assertThatThrownBy(() -> controller().search(null, null, null))
                .isInstanceOf(P04AiException.class)
                .extracting(thrown -> ((P04AiException) thrown).errorCode())
                .isEqualTo(P04AiErrorCode.AUTH_REQUIRED);
        verifyNoInteractions(service);
        verify(revocations, never()).enter(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("缺 agent.execute scope：403，且不触碰数据面")
    void missingScopeIs403() {
        authenticate(Set.of("ai:kb:list"));

        assertThatThrownBy(() -> controller().detail(APP_ID))
                .isInstanceOf(P04AiException.class)
                .extracting(thrown -> ((P04AiException) thrown).errorCode())
                .isEqualTo(P04AiErrorCode.FORBIDDEN);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("守卫缺席：显式拒绝（delivery permit unavailable），不返回看起来成功的 200")
    void absentRevocationGuardIsRejected() {
        authenticate(Set.of("agent.execute"));
        when(service.search("t1", null, null, null)).thenReturn(page());
        MultiAgentAppController controller = new MultiAgentAppController(service);
        controller.setDeliveryPermits(deliveryPermits);

        assertThatThrownBy(() -> controller.search(null, null, null))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("delivery permit unavailable");
        verify(deliveryPermits, never()).register(any(), anyString(), anyString());
    }
}
