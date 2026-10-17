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

package com.nageoffer.ai.ragent.agent.controller;

import com.nageoffer.ai.ragent.authorization.AiDeliveryPermitConfiguration;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentOptionVO;
import com.nageoffer.ai.ragent.rag.service.AgentOptionReadService;
import com.nageoffer.ai.ragent.runtime.config.ConfigAuthorityUnavailable;
import com.nageoffer.ai.ragent.runtime.config.EngineModelAuthority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
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
import static org.mockito.Mockito.when;

/**
 * F09:4-A 用户端智能体发现面控制器的边界判据（C3.1 + C6 + C1 + 交付 permit 协议）。
 *
 * <p>四层边界各有一条判据，任一层缺口都必须有测试变红：
 * <ul>
 *   <li>缺主体 ⇒ 401；主体在但缺 {@code agent.execute} scope ⇒ 403（内层复核，不靠网关）；</li>
 *   <li>发布权威不可得（{@link ConfigAuthorityUnavailable}）⇒ 503，不回退 YAML、不返回空串；</li>
 *   <li><b>权威 bean 缺席（引擎闭包未装配）⇒ 也是 503，不得静默 200</b>——这是本卡新增的结构性判据
 *       （{@link ObjectProvider} 软取的原因见控制器类注释）；</li>
 *   <li>交付 permit：成功路径必须登记 permit 并回填两个 UUID 回执头（否则网关 GET 字节分支
 *       会以 {@code delivery receipt missing} 收敛为 503）；守卫缺席时显式拒绝而非"看起来成功"。</li>
 * </ul>
 */
@Tag("dev")
class AgentOptionControllerTest {

    private static final String PERMIT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String OPERATION_ID = "22222222-2222-2222-2222-222222222222";

    private AgentOptionReadService agentOptionReadService;
    private EngineModelAuthority modelAuthority;
    private RevocationGuard revocations;
    private AiDeliveryPermitConfiguration.DeliveryPermitHolder deliveryPermits;
    private ExecutionPrincipal previousPrincipal;

    @BeforeEach
    void setUp() {
        agentOptionReadService = mock(AgentOptionReadService.class);
        modelAuthority = mock(EngineModelAuthority.class);
        revocations = mock(RevocationGuard.class);
        deliveryPermits = mock(AiDeliveryPermitConfiguration.DeliveryPermitHolder.class);
        previousPrincipal = PrincipalContext.get();
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.restore(previousPrincipal);
    }

    /** 完整装配（守卫、持有者、权威都在场）的控制器。 */
    private AgentOptionController controller() {
        AgentOptionController controller = new AgentOptionController(
                agentOptionReadService, providerOf(EngineModelAuthority.class, modelAuthority));
        controller.setRevocations(revocations);
        controller.setDeliveryPermits(deliveryPermits);
        return controller;
    }

    private void authenticate(Set<String> scopes) {
        PrincipalContext.set(new ExecutionPrincipal(
                "000000", "1", "platform:000000:1", 3, 4, scopes,
                "jti-option-test", "test", 0L, Long.MAX_VALUE));
    }

    private void publishedModel() {
        when(modelAuthority.requirePublished("agent.execute")).thenReturn(new EngineModelAuthority.PublishedModel(
                "000000", "rev-1", 1L, "bailian", "qwen-max", "catalog-1", "sha256:params",
                "env:DASHSCOPE_API_KEY", "operator-1", Instant.parse("2026-10-06T00:00:00Z")));
    }

    private void guardGranted() {
        when(revocations.enter(any(), eq("agent.execute"), eq("tenant:agent-options")))
                .thenReturn(new RevocationGuard.Operation(revocations, PERMIT_ID, OPERATION_ID));
    }

    private static AgentOptionVO option(String id, boolean executable) {
        return new AgentOptionVO(id, "名字-" + id, "描述-" + id, "", true, executable);
    }

    @Test
    @DisplayName("成功路径：最小投影 + 登记 permit + 两个 UUID 回执头 + Cache-Control no-store")
    void returnsMinimalProjectionWithDeliveryReceiptsAndRegisteredPermit() {
        authenticate(Set.of("agent.execute"));
        publishedModel();
        guardGranted();
        List<AgentOptionVO> options = List.of(option("a-1", true), option("a-2", false));
        when(agentOptionReadService.listOptions()).thenReturn(options);

        ResponseEntity<ApiEnvelope<List<AgentOptionVO>>> response = controller().list();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst("X-AI-Delivery-Permit")).isEqualTo(PERMIT_ID);
        assertThat(response.getHeaders().getFirst("X-AI-Delivery-Operation")).isEqualTo(OPERATION_ID);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ApiEnvelope.SUCCESS_CODE);
        assertThat(response.getBody().data()).containsExactlyElementsOf(options);
        // protect 的许可交给持有者登记，才能在请求收尾单点释放
        verify(deliveryPermits).register(any(), eq(PERMIT_ID), eq(OPERATION_ID));
        verify(revocations).enter(any(), eq("agent.execute"), eq("tenant:agent-options"));
    }

    @Test
    @DisplayName("C6：缺执行主体一律 401，且不触碰数据面")
    void missingPrincipalIsRejectedAs401() {
        PrincipalContext.clear();

        assertThatThrownBy(() -> controller().list())
                .isInstanceOf(P04AiException.class)
                .extracting(thrown -> ((P04AiException) thrown).errorCode())
                .isEqualTo(P04AiErrorCode.AUTH_REQUIRED);
        verify(agentOptionReadService, never()).listOptions();
        verify(revocations, never()).enter(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("C3.1：主体在但缺 agent.execute scope 即 403，且不触碰数据面")
    void missingAgentExecuteScopeIsRejectedAs403() {
        authenticate(Set.of("ai:kb:list"));

        assertThatThrownBy(() -> controller().list())
                .isInstanceOf(P04AiException.class)
                .extracting(thrown -> ((P04AiException) thrown).errorCode())
                .isEqualTo(P04AiErrorCode.FORBIDDEN);
        verify(agentOptionReadService, never()).listOptions();
        verify(revocations, never()).enter(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("C1：发布权威读不到 ⇒ 503 AUTHORIZATION_UNAVAILABLE，不回退、不返回空串")
    void configAuthorityUnavailableIsMappedTo503() {
        authenticate(Set.of("agent.execute"));
        when(modelAuthority.requirePublished("agent.execute"))
                .thenThrow(new ConfigAuthorityUnavailable("no published config revision"));

        assertThatThrownBy(() -> controller().list())
                .isInstanceOf(P04AiException.class)
                .extracting(thrown -> ((P04AiException) thrown).errorCode())
                .isEqualTo(P04AiErrorCode.AUTHORIZATION_UNAVAILABLE);
        verify(agentOptionReadService, never()).listOptions();
        verify(revocations, never()).enter(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("权威 bean 缺席（引擎未装配）⇒ 503，不得静默 200（结构性判据）")
    void absentEngineAuthorityBeanIsRejectedAs503NotSilent200() {
        authenticate(Set.of("agent.execute"));
        AgentOptionController controller = new AgentOptionController(
                agentOptionReadService, providerOf(EngineModelAuthority.class, null));
        controller.setRevocations(revocations);
        controller.setDeliveryPermits(deliveryPermits);

        assertThatThrownBy(controller::list)
                .isInstanceOf(P04AiException.class)
                .extracting(thrown -> ((P04AiException) thrown).errorCode())
                .isEqualTo(P04AiErrorCode.AUTHORIZATION_UNAVAILABLE);
        verify(agentOptionReadService, never()).listOptions();
        verify(revocations, never()).enter(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("守卫缺席 ⇒ 显式拒绝（不登记 permit、不回执），不得静默 200")
    void absentRevocationGuardIsRejectedNotSilent200() {
        authenticate(Set.of("agent.execute"));
        publishedModel();
        AgentOptionController controller = new AgentOptionController(
                agentOptionReadService, providerOf(EngineModelAuthority.class, modelAuthority));
        controller.setDeliveryPermits(deliveryPermits);

        assertThatThrownBy(controller::list)
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("delivery permit unavailable");
        verify(revocations, never()).enter(any(), anyString(), anyString());
        verify(deliveryPermits, never()).register(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("持有者缺席：回执仍由本方法回填（与 reply 原型逐句一致），交付不因释放件缺席而失效")
    void absentPermitHolderStillDeliversReceipts() {
        authenticate(Set.of("agent.execute"));
        publishedModel();
        guardGranted();
        when(agentOptionReadService.listOptions()).thenReturn(List.of(option("a-1", true)));
        AgentOptionController controller = new AgentOptionController(
                agentOptionReadService, providerOf(EngineModelAuthority.class, modelAuthority));
        controller.setRevocations(revocations);

        ResponseEntity<ApiEnvelope<List<AgentOptionVO>>> response = controller.list();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("X-AI-Delivery-Permit")).isEqualTo(PERMIT_ID);
        assertThat(response.getHeaders().getFirst("X-AI-Delivery-Operation")).isEqualTo(OPERATION_ID);
    }

    /** 极简 ObjectProvider：只服务"在场/缺席"两种情形的判据（缺席时 getIfAvailable() 返回 null）。 */
    private static <T> ObjectProvider<T> providerOf(Class<T> type, T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }
        };
    }
}
