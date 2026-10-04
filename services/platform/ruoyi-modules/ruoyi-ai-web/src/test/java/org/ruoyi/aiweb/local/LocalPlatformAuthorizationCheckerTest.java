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

import com.nageoffer.ai.ragent.framework.security.AuthorizationChecker;
import com.nageoffer.ai.ragent.framework.security.DelegatedPrincipal;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * E3：{@link LocalPlatformAuthorizationChecker} 与
 * {@code /internal/platform/v1/authorization/check} 的判定对齐（Mockito，无 DB/HTTP）。
 */
@Tag("dev")
class LocalPlatformAuthorizationCheckerTest {

    private static final String TENANT = "T1";
    private static final String USER = "2101";
    private static final String MEMBER = "platform:T1:2101";
    private static final int PV = 7;

    private final PlatformIdentitySource identitySource = mock(PlatformIdentitySource.class);
    private final LocalPlatformAuthorizationChecker checker =
            new LocalPlatformAuthorizationChecker(provider(PlatformIdentitySource.class, identitySource));

    private static <T> ObjectProvider<T> provider(Class<T> type, T instance) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        if (instance != null) {
            factory.registerSingleton("candidate", instance);
        }
        return factory.getBeanProvider(type);
    }

    private static DelegatedPrincipal principal(int policyVersion) {
        return new DelegatedPrincipal("platform:local", TENANT, USER, MEMBER, policyVersion,
                Set.of("kb.list"), "jti-local");
    }

    private void stubIdentity(boolean enabled, Set<String> scopes, int policyVersion) {
        when(identitySource.tenantState(TENANT)).thenReturn(PlatformIdentitySource.TenantState.ENABLED);
        when(identitySource.membership(TENANT, USER, MEMBER)).thenReturn(new PlatformIdentitySource.PlatformIdentity(
                TENANT, USER, MEMBER, enabled, scopes, policyVersion));
    }

    @Test
    @DisplayName("放行：租户启用 + 成员有效 + pv 相等 + 权限显式持有")
    void allowsWhenEveryFactHolds() {
        stubIdentity(true, Set.of("ai:kb:list"), PV);

        AuthorizationChecker.AuthorizeResult result = checker.check(principal(PV), "kb.list", "tenant:resources");

        assertThat(result.allowed()).isTrue();
        assertThat(result.policyVersion()).isEqualTo(PV);
    }

    @Test
    @DisplayName("权限缺失：无通配豁免，超管范围字符串不产生 AI 资源放行")
    void missingPermissionIsForbidden() {
        stubIdentity(true, Set.of("*:*:*"), PV);

        P04AiException rejected = catchThrowableOfType(
                () -> checker.check(principal(PV), "kb.list", "tenant:resources"), P04AiException.class);

        assertThat(rejected.errorCode()).isEqualTo(P04AiErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("策略版本陈旧：旧/未来版本都拒绝（409 语义）")
    void stalePolicyVersionIsRejected() {
        stubIdentity(true, Set.of("ai:kb:list"), PV + 1);

        P04AiException rejected = catchThrowableOfType(
                () -> checker.check(principal(PV), "kb.list", "tenant:resources"), P04AiException.class);

        assertThat(rejected.errorCode()).isEqualTo(P04AiErrorCode.POLICY_VERSION_STALE);
    }

    @Test
    @DisplayName("未知动作：映射表外一律拒绝（403），不默认允许")
    void unknownActionIsForbidden() {
        stubIdentity(true, Set.of("ai:kb:list"), PV);

        P04AiException rejected = catchThrowableOfType(
                () -> checker.check(principal(PV), "not-an-action", "tenant:resources"), P04AiException.class);

        assertThat(rejected.errorCode()).isEqualTo(P04AiErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("租户不可用/成员无效/身份源缺失：各自符号码拒绝，不放行")
    void unavailableFactsAreRejected() {
        when(identitySource.tenantState(TENANT)).thenReturn(PlatformIdentitySource.TenantState.DISABLED);
        assertThat(catchThrowableOfType(() -> checker.check(principal(PV), "kb.list", "tenant:resources"),
                P04AiException.class).errorCode()).isEqualTo(P04AiErrorCode.TENANT_DISABLED);

        stubIdentity(false, Set.of("ai:kb:list"), PV);
        assertThat(catchThrowableOfType(() -> checker.check(principal(PV), "kb.list", "tenant:resources"),
                P04AiException.class).errorCode()).isEqualTo(P04AiErrorCode.MEMBERSHIP_INVALID);

        LocalPlatformAuthorizationChecker withoutSource = new LocalPlatformAuthorizationChecker(
                provider(PlatformIdentitySource.class, null));
        assertThat(catchThrowableOfType(() -> withoutSource.check(principal(PV), "kb.list", "tenant:resources"),
                P04AiException.class).errorCode()).isEqualTo(P04AiErrorCode.AUTHORIZATION_UNAVAILABLE);
    }

    @Test
    @DisplayName("形状非法：缺租户上下文/缺成员/缺资源引用按符号码拒绝")
    void malformedRequestsAreRejected() {
        stubIdentity(true, Set.of("ai:kb:list"), PV);

        assertThat(catchThrowableOfType(() -> checker.check(
                new DelegatedPrincipal("platform:local", "", USER, MEMBER, PV, Set.of("kb.list"), "j"),
                "kb.list", "tenant:resources"), P04AiException.class).errorCode())
                .isEqualTo(P04AiErrorCode.TENANT_CONTEXT_MISSING);

        assertThat(catchThrowableOfType(() -> checker.check(principal(PV), "kb.list", " "),
                P04AiException.class).errorCode()).isEqualTo(P04AiErrorCode.BAD_REQUEST);

        assertThatThrownBy(() -> checker.check(null, "kb.list", "tenant:resources"))
                .isInstanceOf(P04AiException.class);
    }
}
