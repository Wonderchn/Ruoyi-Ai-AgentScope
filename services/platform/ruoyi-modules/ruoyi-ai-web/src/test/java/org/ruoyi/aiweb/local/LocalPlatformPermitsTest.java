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

package org.ruoyi.aiweb.local;

import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.security.PlatformPermitPort;
import com.nageoffer.ai.ragent.framework.security.StaleVersionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.aiintegration.identity.ProductionAuthorizationProvider;
import org.ruoyi.aiintegration.web.P04ErrorCode;
import org.ruoyi.aiintegration.web.P04Exception;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E3：{@link LocalPlatformPermits} 与
 * {@code /internal/platform/v1/authorization/permits/acquire|release} 的对齐
 * （Mockito，无 DB/HTTP）。
 */
@Tag("dev")
class LocalPlatformPermitsTest {

    private static final String TENANT = "T1";
    private static final String MEMBER = "platform:T1:2101";
    private static final String OPERATION = "op-1";

    private final ProductionAuthorizationProvider provider = mock(ProductionAuthorizationProvider.class);
    private final LocalPlatformPermits permits =
            new LocalPlatformPermits(provider(ProductionAuthorizationProvider.class, provider));

    private static <T> ObjectProvider<T> provider(Class<T> type, T instance) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        if (instance != null) {
            factory.registerSingleton("candidate", instance);
        }
        return factory.getBeanProvider(type);
    }

    private static PlatformPermitPort.AcquireRequest acquireRequest() {
        return new PlatformPermitPort.AcquireRequest(TENANT, "2101", MEMBER, 7, 3,
                "kb.read", "kb:kb-1", "a".repeat(64), OPERATION);
    }

    @Test
    @DisplayName("acquire：平台许可提供者的回执逐字段映射（permitId 以平台为准）")
    void acquireMapsProviderGrant() {
        when(provider.acquire(any())).thenReturn(
                new ProductionAuthorizationProvider.PermitGrant("platform-permit-1", 7, OPERATION));

        PlatformPermitPort.AcquireResult result = permits.acquire(acquireRequest());

        assertThat(result.permitId()).isEqualTo("platform-permit-1");
        assertThat(result.policyVersion()).isEqualTo(7);
        assertThat(result.operationId()).isEqualTo(OPERATION);
        verify(provider).acquire(new ProductionAuthorizationProvider.PermitRequest(TENANT, "2101", MEMBER, 7, 3,
                "kb.read", "kb:kb-1", "a".repeat(64), OPERATION));
    }

    @Test
    @DisplayName("acquire：平台 pv 陈旧 → STALE；其余拒绝/不可用 → ServiceException（不放行）")
    void acquireFailureMapping() {
        // 重复打桩用 doThrow：when(...) 会先真实调用 mock，被上一次的 thenThrow 抢先抛出
        doThrow(new P04Exception(P04ErrorCode.POLICY_VERSION_STALE)).when(provider).acquire(any());
        assertThatThrownBy(() -> permits.acquire(acquireRequest()))
                .isInstanceOf(StaleVersionException.class);

        doThrow(new P04Exception(P04ErrorCode.FORBIDDEN)).when(provider).acquire(any());
        assertThatThrownBy(() -> permits.acquire(acquireRequest()))
                .isInstanceOf(ServiceException.class);

        doThrow(new IllegalStateException("db down")).when(provider).acquire(any());
        assertThatThrownBy(() -> permits.acquire(acquireRequest()))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    @DisplayName("acquire/release：无许可提供者一律 ServiceException（fail-closed）")
    void missingProviderFailsClosed() {
        LocalPlatformPermits withoutProvider = new LocalPlatformPermits(
                provider(ProductionAuthorizationProvider.class, null));

        assertThatThrownBy(() -> withoutProvider.acquire(acquireRequest()))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> withoutProvider.release(
                new PlatformPermitPort.ReleaseRequest(TENANT, MEMBER, "p1", OPERATION)))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    @DisplayName("release：委托平台释放，失败不吞（释放未确认）")
    void releaseDelegatesAndPropagatesFailure() {
        permits.release(new PlatformPermitPort.ReleaseRequest(TENANT, MEMBER, "platform-permit-1", OPERATION));
        verify(provider).release(new ProductionAuthorizationProvider.PermitRelease(
                TENANT, MEMBER, "platform-permit-1", OPERATION));

        org.mockito.Mockito.doThrow(new P04Exception(P04ErrorCode.FORBIDDEN))
                .when(provider).release(any());
        assertThatThrownBy(() -> permits.release(
                new PlatformPermitPort.ReleaseRequest(TENANT, MEMBER, "p2", OPERATION)))
                .isInstanceOf(ServiceException.class);
    }
}
