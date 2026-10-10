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

package com.nageoffer.ai.ragent.runtime;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F12-A1：运行面限域（RunAccessService 四元守卫）的负例判据 —— 零改码，只钉既有守卫。
 *
 * <p>运行面对"这个主体能不能看见这个 run"的判定是四元全等：tenantId + membershipId +
 * policyVersion + aclVersion（{@code RunAccessService.ownerAndVersions}），任一不等一律
 * 404 {@code RESOURCE_NOT_FOUND_OR_FORBIDDEN}（不泄露资源是否存在）；可见性还叠加
 * resourceRefs 守卫。本判据把四元逐项失配 + 可见性守卫 + "授权事实源缺位不静默放行"
 * 钉成可回归的负例，与 {@code RunControllerDenyContractTest}（HTTP 形状）互补。
 *
 * <p>形状仿 {@code RunCitationVisibilityTest} / {@code RunControllerDenyContractTest}：
 * 直构 service + 替身 ObjectProvider，不启 Spring、不碰 DB。
 */
@Tag("dev")
class RunAccessServiceFourTupleDenyTest {

    private static final String TENANT = "t1";
    private static final String USER = "1001";
    private static final String MEMBER = "platform:t1:1001";

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static RunAccessService service() {
        return new RunAccessService(provider(null), provider(null), provider(null), provider(null));
    }

    private static ExecutionPrincipal principal(String tenant, String userId, int policyVersion, int aclVersion) {
        return new ExecutionPrincipal(tenant, userId, "platform:" + tenant + ":" + userId,
                policyVersion, aclVersion, Set.of("run.get"), "jti-1", "platform", 1L, 9999999999L);
    }

    /** run 的属主主体（tenant t1 / user 1001）。 */
    private static ExecutionPrincipal owner() {
        return principal(TENANT, USER, 1, 1);
    }

    private static RunRecord run(String refsJson) {
        return new RunRecord(TENANT, "run-1", MEMBER, USER, "custom.noop", "SUCCEEDED", "hash", "key",
                "{}", "{}", "v1", 1, 1, refsJson, 1L, 1L, 1, 1L, null, null,
                null, null, null, null, java.time.Instant.EPOCH, java.time.Instant.EPOCH, java.time.Instant.EPOCH);
    }

    private static RunApiException assertFourTupleDenied(RunAccessService service, ExecutionPrincipal principal,
                                                         RunRecord run) {
        return assertThrows(RunApiException.class, () -> service.visible(principal, run));
    }

    @Test
    @DisplayName("四元·租户失配 ⇒ 404 RESOURCE_NOT_FOUND_OR_FORBIDDEN")
    void tenantMismatchDenied() {
        RunApiException denied = assertFourTupleDenied(service(),
                principal("t2", USER, 1, 1), run("[]"));
        assertEquals(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, denied.errorCode());
    }

    @Test
    @DisplayName("四元·成员失配（同租户他成员）⇒ 404，不泄露存在性")
    void membershipMismatchDenied() {
        RunApiException denied = assertFourTupleDenied(service(),
                principal(TENANT, "1002", 1, 1), run("[]"));
        assertEquals(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, denied.errorCode());
    }

    @Test
    @DisplayName("四元·策略版本失配 ⇒ 404（版本滞后/超前都不是可读信号）")
    void policyVersionMismatchDenied() {
        RunApiException denied = assertFourTupleDenied(service(),
                principal(TENANT, USER, 2, 1), run("[]"));
        assertEquals(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, denied.errorCode());
    }

    @Test
    @DisplayName("四元·ACL 版本失配 ⇒ 404")
    void aclVersionMismatchDenied() {
        RunApiException denied = assertFourTupleDenied(service(),
                principal(TENANT, USER, 1, 2), run("[]"));
        assertEquals(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, denied.errorCode());
    }

    @Test
    @DisplayName("四元全等 ⇒ 通过属主守卫（action 无引用面，不再要求 ledger/documents）")
    void fourTupleEqualPassesOwnerGuard() {
        assertDoesNotThrow(() -> service().visible(owner(), run("[]")));
    }

    @Test
    @DisplayName("可见性守卫：四元全等但 resourceRefsJson 缺位 ⇒ 404（不得当作空引用放行）")
    void missingResourceRefsDenied() {
        RunApiException denied = assertFourTupleDenied(service(), owner(), run(null));
        assertEquals(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, denied.errorCode());
    }

    @Test
    @DisplayName("授权事实源缺位：current() 不得静默放行 ⇒ AUTHORIZATION_UNAVAILABLE（503 语义）")
    void missingFactsClientIsUnavailable() {
        RunApiException denied = assertThrows(RunApiException.class, () ->
                service().current(run("[]"), Set.of("run.get")));
        assertEquals(RunErrorCode.AUTHORIZATION_UNAVAILABLE, denied.errorCode());
    }

    @Test
    @DisplayName("scope 收窄只换 actions：scoped() 保留四元身份，不放大授权面")
    void scopedPreservesIdentityAndReplacesScopes() {
        ExecutionPrincipal wide = owner();
        ExecutionPrincipal narrowed = RunAccessService.scoped(wide, Set.of("kb.read"));
        assertThat(narrowed.tenantId()).isEqualTo(wide.tenantId());
        assertThat(narrowed.userId()).isEqualTo(wide.userId());
        assertThat(narrowed.membershipId()).isEqualTo(wide.membershipId());
        assertThat(narrowed.policyVersion()).isEqualTo(wide.policyVersion());
        assertThat(narrowed.aclVersion()).isEqualTo(wide.aclVersion());
        assertThat(narrowed.scopes()).containsExactly("kb.read");
    }
}
