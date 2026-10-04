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

package org.ruoyi.aiidentity;

import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E3/§4.6：{@link LocalAiBarrierPort} —— 屏障端点的内嵌本地实现
 * （替换 {@link HttpAiBarrierClient} 的 HTTP 通道，Mockito，无 DB/HTTP）。
 *
 * <p>覆盖：装配条件（默认关/http 不装配、local 装配）；CLOSE 置 PENDING 并回报活跃数；
 * 状态不符/事实不可读一律 empty（"无法证明已停"）；OPEN 仅在活跃数归零时确认，
 * 否则"解除未确认"；guard 缺失同样 fail-closed。
 */
@Tag("dev")
class LocalAiBarrierPortTest {

    private static final String TENANT = "T1";
    private static final String BARRIER = "barrier-1";

    private final RevocationGuard guard = mock(RevocationGuard.class);
    private final LocalAiBarrierPort port = new LocalAiBarrierPort(provider(RevocationGuard.class, guard));

    private static <T> ObjectProvider<T> provider(Class<T> type, T instance) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        if (instance != null) {
            factory.registerSingleton("candidate", instance);
        }
        return factory.getBeanProvider(type);
    }

    @Test
    @DisplayName("装配条件：默认关与 http 不装配；local 装配且单实例")
    void assemblyConditions() {
        new ApplicationContextRunner()
                .withUserConfiguration(LocalAiBarrierPort.class)
                .run(context -> assertThat(context).doesNotHaveBean(LocalAiBarrierPort.class));
        new ApplicationContextRunner()
                .withUserConfiguration(LocalAiBarrierPort.class)
                .withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=http")
                .run(context -> assertThat(context).doesNotHaveBean(LocalAiBarrierPort.class));
        new ApplicationContextRunner()
                .withUserConfiguration(LocalAiBarrierPort.class)
                .withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=local")
                .run(context -> assertThat(context).hasSingleBean(LocalAiBarrierPort.class));
    }

    @Test
    @DisplayName("CLOSE：置 PENDING 并回报节点活跃 permit 数")
    void closeSetsPendingAndReturnsActiveCount() {
        when(guard.barrierState(TENANT)).thenReturn(RevocationGuard.BarrierState.PENDING);
        when(guard.activePermitCount(TENANT)).thenReturn(2L);

        Optional<Long> active = port.close(TENANT, BARRIER, "policy mutation");

        assertThat(active).contains(2L);
        verify(guard).setBarrierState(TENANT, RevocationGuard.BarrierState.PENDING, BARRIER, null, "policy mutation");
    }

    @Test
    @DisplayName("CLOSE/STATUS：状态不可读或非 PENDING 一律 empty（不虚假回报）")
    void closeOrStatusFailClosed() {
        when(guard.barrierState(TENANT)).thenReturn(RevocationGuard.BarrierState.OPEN);
        assertThat(port.close(TENANT, BARRIER, "r")).isEmpty();

        doThrow(new IllegalStateException("db down")).when(guard).barrierState(TENANT);
        assertThat(port.activePermitCount(TENANT)).isEmpty();
        assertThat(port.close(TENANT, BARRIER, "r")).isEmpty();

        LocalAiBarrierPort withoutGuard = new LocalAiBarrierPort(provider(RevocationGuard.class, null));
        assertThat(withoutGuard.activePermitCount(TENANT)).isEmpty();
        assertThat(withoutGuard.close(TENANT, BARRIER, "r")).isEmpty();
    }

    @Test
    @DisplayName("OPEN：活跃数归零才确认；非零或异常一律\"解除未确认\"")
    void openRequiresZeroActivePermits() {
        when(guard.activePermitCount(TENANT)).thenReturn(0L);
        port.open(TENANT, BARRIER);
        verify(guard).setBarrierState(TENANT, RevocationGuard.BarrierState.OPEN, BARRIER, null, "released");

        when(guard.activePermitCount(TENANT)).thenReturn(1L);
        assertThatThrownBy(() -> port.open(TENANT, BARRIER)).isInstanceOf(IllegalStateException.class);

        doThrow(new IllegalStateException("epoch missing")).when(guard)
                .setBarrierState(eq(TENANT), any(), any(), any(), any());
        assertThatThrownBy(() -> port.open(TENANT, BARRIER)).isInstanceOf(IllegalStateException.class);

        LocalAiBarrierPort withoutGuard = new LocalAiBarrierPort(provider(RevocationGuard.class, null));
        assertThatThrownBy(() -> withoutGuard.open(TENANT, BARRIER)).isInstanceOf(IllegalStateException.class);
    }
}
