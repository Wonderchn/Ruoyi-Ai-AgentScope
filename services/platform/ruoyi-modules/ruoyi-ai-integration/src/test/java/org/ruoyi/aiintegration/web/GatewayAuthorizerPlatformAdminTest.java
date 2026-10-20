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

package org.ruoyi.aiintegration.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.aiintegration.authorization.AiActionRegistry;
import org.ruoyi.aiintegration.config.AiIntegrationProperties;
import org.ruoyi.aiintegration.identity.CurrentPrincipalResolver;
import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F11:4 集成：专用流通道（技能包导入）必须与通用网关同一道平台管理第二道门。
 *
 * <p>仅持 {@code skill.write} scope 的租户管理员不得经专用通道导入技能包；
 * 平台管理身份才放行。非平台管理动作（如 {@code document.upload}）不额外受限。
 */
@Tag("dev")
class GatewayAuthorizerPlatformAdminTest {

    private static final String TENANT = "000000";
    private static final String USER = "910101";
    private static final String MEMBER = "platform:000000:910101";

    private final CurrentPrincipalResolver resolver = mock(CurrentPrincipalResolver.class);
    private final PlatformIdentitySource source = mock(PlatformIdentitySource.class);
    private final AiIntegrationProperties properties = mock(AiIntegrationProperties.class);

    @SuppressWarnings("unchecked")
    private GatewayAuthorizer authorizer(boolean platformAdmin) {
        when(resolver.resolveCurrentMember())
                .thenReturn(Optional.of(new CurrentPrincipalResolver.CurrentMember(TENANT, USER, MEMBER)));
        when(resolver.isPlatformAdmin()).thenReturn(platformAdmin);
        when(properties.isLocalTransport()).thenReturn(true);
        ObjectProvider<PlatformIdentitySource> sources = mock(ObjectProvider.class);
        when(sources.getIfAvailable()).thenReturn(source);
        ObjectProvider<org.ruoyi.aiintegration.delegation.ProductionSigningKeySource> keys =
                mock(ObjectProvider.class);
        when(keys.getIfAvailable()).thenReturn(null);
        return new GatewayAuthorizer(resolver, sources, keys, properties);
    }

    private static HttpServletRequest request() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(GatewayAuthorizer.LOGIN_EVIDENCE_HEADER)).thenReturn("Bearer test-token");
        return request;
    }

    private void identityWith(String action) {
        when(source.membership(TENANT, USER, MEMBER)).thenReturn(
                new PlatformIdentitySource.PlatformIdentity(TENANT, USER, MEMBER, true,
                        Set.of(AiActionRegistry.requirePermission(action)), 3));
    }

    @Test
    void nonPlatformAdminWithSkillScopeIsForbiddenOnStreamChannel() {
        identityWith("skill.write");
        GatewayAuthorizer authorizer = authorizer(false);

        assertThatThrownBy(() -> authorizer.authorize(request(), "skill.write"))
                .isInstanceOf(P04Exception.class)
                .extracting(e -> ((P04Exception) e).errorCode())
                .isEqualTo(P04ErrorCode.FORBIDDEN);
    }

    @Test
    void platformAdminWithSkillScopeIsAuthorized() {
        identityWith("skill.write");
        GatewayAuthorizer authorizer = authorizer(true);

        GatewayAuthorizer.Authorized authorized = authorizer.authorize(request(), "skill.write");

        assertThat(authorized.action()).isEqualTo("skill.write");
        assertThat(authorized.member().membershipId()).isEqualTo(MEMBER);
    }

    @Test
    void tenantCatalogSelfServiceLetsScopedTenantAdminThroughWithoutPlatformIdentity() {
        identityWith("skill.write");
        when(properties.isTenantCatalogSelfService()).thenReturn(true);
        GatewayAuthorizer authorizer = authorizer(false);

        GatewayAuthorizer.Authorized authorized = authorizer.authorize(request(), "skill.write");

        assertThat(authorized.action()).isEqualTo("skill.write");
        assertThat(authorized.member().membershipId()).isEqualTo(MEMBER);
    }

    @Test
    void nonPlatformActionIsNotGatedByPlatformAdmin() {
        identityWith("document.upload");
        GatewayAuthorizer authorizer = authorizer(false);

        GatewayAuthorizer.Authorized authorized = authorizer.authorize(request(), "document.upload");

        assertThat(authorized.action()).isEqualTo("document.upload");
    }
}
