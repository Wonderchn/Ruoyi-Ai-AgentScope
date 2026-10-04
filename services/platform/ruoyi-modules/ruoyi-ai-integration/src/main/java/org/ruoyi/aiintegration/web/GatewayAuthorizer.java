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
import org.ruoyi.aiintegration.authorization.AiActionRegistry;
import org.ruoyi.aiintegration.delegation.ProductionSigningKeySource;
import org.ruoyi.aiintegration.identity.CurrentPrincipalResolver;
import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 网关委托链（普通转发与专用流式/上传传输共用）：
 * 登录证据 → 当前成员 → 策略版本/最小 scope → 签发委托。
 *
 * <p>身份只来自 SaToken 会话与真实身份源，不解析 body/header 中的身份字段。
 */
@Component
public class GatewayAuthorizer {

    static final String LOGIN_EVIDENCE_HEADER = "Authorization";

    private final CurrentPrincipalResolver principalResolver;
    private final ObjectProvider<PlatformIdentitySource> identitySource;
    private final ProductionSigningKeySource signingKeys;

    public GatewayAuthorizer(CurrentPrincipalResolver principalResolver,
                             ObjectProvider<PlatformIdentitySource> identitySource,
                             ProductionSigningKeySource signingKeys) {
        this.principalResolver = principalResolver;
        this.identitySource = identitySource;
        this.signingKeys = signingKeys;
    }

    public record Authorized(CurrentPrincipalResolver.CurrentMember member, String action, String delegationToken) {
    }

    public Authorized authorize(HttpServletRequest request, String action) {
        String loginEvidence = request.getHeader(LOGIN_EVIDENCE_HEADER);
        if (loginEvidence == null || loginEvidence.isBlank()) {
            throw new P04Exception(P04ErrorCode.AUTH_REQUIRED);
        }
        CurrentPrincipalResolver.CurrentMember member = principalResolver.resolveCurrentMember()
                .orElseThrow(() -> new P04Exception(P04ErrorCode.TENANT_CONTEXT_MISSING));
        PlatformIdentitySource source = identitySource.getIfAvailable();
        if (source == null) {
            throw new P04Exception(P04ErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
        PlatformIdentitySource.PlatformIdentity identity =
                source.membership(member.tenantId(), member.userId(), member.membershipId());
        if (identity == null || !identity.enabled()) {
            throw new P04Exception(P04ErrorCode.MEMBERSHIP_INVALID);
        }
        String requiredPermission = AiActionRegistry.requirePermission(action);
        if (!identity.scopes().contains(requiredPermission)) {
            throw new P04Exception(P04ErrorCode.FORBIDDEN);
        }
        ProductionSigningKeySource.Issued issued = signingKeys.issue(
                member.tenantId(), member.userId(), member.membershipId(),
                List.of(action), identity.policyVersion(), null);
        return new Authorized(member, action, issued.token());
    }
}
