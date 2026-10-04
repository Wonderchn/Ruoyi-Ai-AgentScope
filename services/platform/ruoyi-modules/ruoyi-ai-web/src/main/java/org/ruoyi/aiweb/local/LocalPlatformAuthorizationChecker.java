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
import org.ruoyi.aiintegration.authorization.AiActionRegistry;
import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link AuthorizationChecker} 的内嵌本地实现（E3）：同进程直接读取 platform 身份源，
 * 不经任何 localhost HTTP。
 *
 * <p>判定链与 {@code POST /internal/platform/v1/authorization/check}
 * （{@code ProductionAuthorizationController}）逐条对齐：
 * 租户启用 → 成员有效 → policyVersion 精确相等（旧/未来都拒绝）→ 动作经
 * {@link AiActionRegistry} 映射到平台权限且身份显式持有（无任何通配豁免）。
 * 失败按 AI 侧符号码抛出（{@link P04AiException}），由 AI 侧异常解析器映射为
 * HTTP status == body.code 的包络。
 */
public class LocalPlatformAuthorizationChecker implements AuthorizationChecker {

    private final ObjectProvider<PlatformIdentitySource> identitySource;

    public LocalPlatformAuthorizationChecker(ObjectProvider<PlatformIdentitySource> identitySource) {
        this.identitySource = identitySource;
    }

    @Override
    public AuthorizeResult check(DelegatedPrincipal principal, String action, String resourceRef) {
        PlatformIdentitySource source = identitySource.getIfAvailable();
        if (source == null) {
            throw new P04AiException(P04AiErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
        if (principal == null || principal.tenantId() == null || principal.tenantId().isBlank()) {
            throw new P04AiException(P04AiErrorCode.TENANT_CONTEXT_MISSING);
        }
        if (principal.subject() == null || principal.subject().isBlank()
                || principal.membershipId() == null || principal.membershipId().isBlank()) {
            throw new P04AiException(P04AiErrorCode.MEMBERSHIP_INVALID);
        }
        if (resourceRef == null || resourceRef.isBlank()) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST);
        }
        if (source.tenantState(principal.tenantId()) != PlatformIdentitySource.TenantState.ENABLED) {
            // 不存在与停用同形，避免泄露租户存在性
            throw new P04AiException(P04AiErrorCode.TENANT_DISABLED);
        }
        PlatformIdentitySource.PlatformIdentity identity =
                source.membership(principal.tenantId(), principal.subject(), principal.membershipId());
        if (identity == null || !identity.enabled()) {
            throw new P04AiException(P04AiErrorCode.MEMBERSHIP_INVALID);
        }
        if (principal.policyVersion() != identity.policyVersion()) {
            throw new P04AiException(P04AiErrorCode.POLICY_VERSION_STALE);
        }
        String required;
        try {
            required = AiActionRegistry.requirePermission(action);
        } catch (RuntimeException unknownAction) {
            throw new P04AiException(P04AiErrorCode.FORBIDDEN);
        }
        if (!identity.scopes().contains(required)) {
            throw new P04AiException(P04AiErrorCode.FORBIDDEN);
        }
        return new AuthorizeResult(true, identity.policyVersion());
    }
}
