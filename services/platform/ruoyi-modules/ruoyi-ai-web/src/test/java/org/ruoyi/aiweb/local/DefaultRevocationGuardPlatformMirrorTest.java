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

import com.nageoffer.ai.ragent.authorization.DefaultRevocationGuard;
import com.nageoffer.ai.ragent.framework.security.PlatformPermitPort;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.framework.security.StaleVersionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.aiintegration.identity.ProductionAuthorizationProvider;
import org.ruoyi.aiintegration.web.P04ErrorCode;
import org.ruoyi.aiintegration.web.P04Exception;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E3：{@link DefaultRevocationGuard} 的 platform 层许可镜像改走
 * {@link LocalPlatformPermits}（同进程），不再有任何 localhost HTTP。
 *
 * <p>钉住三件事：平台 permitId 成为权威标识（本层行与平台行同 id）；
 * 平台判定陈旧在写入本层行<b>之前</b>终止（不产生单边 permit）；
 * 平台端口缺席时保持"仅本层 permit"的独立运行形态（迁移前语义）。
 */
@Tag("dev")
class DefaultRevocationGuardPlatformMirrorTest {

    private static final String HASH = "b".repeat(64);
    private static final String OPERATION = "op-1";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ProductionAuthorizationProvider provider = mock(ProductionAuthorizationProvider.class);
    private final DefaultRevocationGuard guard = new DefaultRevocationGuard(jdbc);

    @BeforeEach
    void wirePlatformPort() {
        // 走真实适配链：DefaultRevocationGuard → LocalPlatformPermits → ProductionAuthorizationProvider
        LocalPlatformPermits localPermits = new LocalPlatformPermits(
                provider(ProductionAuthorizationProvider.class, provider));
        guard.configurePlatformPermits(provider(PlatformPermitPort.class, localPermits));
    }

    private static <T> ObjectProvider<T> provider(Class<T> type, T instance) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        if (instance != null) {
            factory.registerSingleton("candidate", instance);
        }
        return factory.getBeanProvider(type);
    }

    private void stubLocalPermitWrites() {
        when(jdbc.query(contains("ai_acl_epoch"), any(RowMapper.class), any(Object[].class))).thenReturn(List.of(3));
        when(jdbc.query(contains("ai_tenant_barrier"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of("OPEN"));
        when(jdbc.update(contains("INSERT INTO ai_execution_permit"), any(Object[].class))).thenReturn(1);
    }

    private static RevocationGuard.PermitRequest request() {
        return new RevocationGuard.PermitRequest("T1", "platform:T1:2101", "kb.read", 7, 3,
                HASH, OPERATION, "kb:kb-1");
    }

    @Test
    @DisplayName("acquire：平台许可先登记，permitId 以平台为准，本层行与平台行同 id")
    void acquireMirrorsPlatformPermit() {
        stubLocalPermitWrites();
        when(provider.acquire(any())).thenReturn(
                new ProductionAuthorizationProvider.PermitGrant("platform-permit-1", 7, OPERATION));

        RevocationGuard.PermitGrant grant = guard.acquire(request());

        assertThat(grant.permitId()).isEqualTo("platform-permit-1");
        assertThat(grant.aclVersion()).isEqualTo(3);
        verify(provider).acquire(new ProductionAuthorizationProvider.PermitRequest("T1", "2101",
                "platform:T1:2101", 7, 3, "kb.read", "kb:kb-1", HASH, OPERATION));
        verify(jdbc).update(contains("INSERT INTO ai_execution_permit"), any(Object[].class));
    }

    @Test
    @DisplayName("acquire：平台 pv 陈旧在本层写入之前终止（不产生单边 permit）")
    void platformStaleStopsBeforeLocalWrite() {
        stubLocalPermitWrites();
        when(provider.acquire(any())).thenThrow(new P04Exception(P04ErrorCode.POLICY_VERSION_STALE));

        assertThatThrownBy(() -> guard.acquire(request())).isInstanceOf(StaleVersionException.class);

        verify(jdbc, never()).update(contains("INSERT INTO ai_execution_permit"), any(Object[].class));
    }

    @Test
    @DisplayName("release：本层释放后镜像平台释放，身份三元组取自本层行")
    void releaseMirrorsPlatformRelease() {
        when(jdbc.update(contains("SET status = 'RELEASED'"), any(Object[].class))).thenReturn(1);
        when(jdbc.query(contains("SELECT tenant_id,member_id FROM ai_execution_permit"),
                any(RowMapper.class), any(Object[].class))).thenReturn(List.of(
                Map.of("tenantId", "T1", "membershipId", "platform:T1:2101",
                        "permitId", "platform-permit-1", "operationId", OPERATION)));

        guard.release("platform-permit-1", OPERATION);

        verify(provider).release(new ProductionAuthorizationProvider.PermitRelease(
                "T1", "platform:T1:2101", "platform-permit-1", OPERATION));
    }

    @Test
    @DisplayName("端口缺席：保持仅本层 permit 的独立运行形态（不调平台、不伪造平台回执）")
    void missingPortKeepsLocalOnlyShape() {
        DefaultRevocationGuard localOnly = new DefaultRevocationGuard(jdbc);
        stubLocalPermitWrites();

        RevocationGuard.PermitGrant grant = localOnly.acquire(request());

        assertThat(grant.permitId()).isNotBlank();
        verify(provider, never()).acquire(any());
    }
}
