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

package org.ruoyi.aiintegration.authorization;

import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.ruoyi.aiintegration.web.ApiResponse;
import org.ruoyi.aiintegration.web.P04ErrorCode;
import org.ruoyi.aiintegration.web.P04Exception;
import org.ruoyi.aiintegration.web.RequestId;
import org.ruoyi.aiintegration.config.P04PlatformProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 内部在线授权复核端点（F1 冻结的路径）：{@code POST /internal/platform/v1/authorization/check}。
 *
 * <p>方向是 AI → platform：AI 用<b>独立的服务凭证</b>调用本端点，复核租户/成员/功能/策略版本
 * （Spec §7.2）。资源 ACL 由 AI 侧本地判定（D2=A 交集），本端点不判定 AI 资源。
 *
 * <p>失败统一遵守 {@code HTTP status == body.code}，符号码在 {@code data.errorCode}。
 */
@RestController
@RequestMapping("/internal/platform/v1")
public class InternalAuthorizationController {

    /** AI → platform 的服务凭证头。 */
    public static final String SERVICE_CREDENTIAL_HEADER = "X-P04-Service-Credential";

    /** 测试专用故障头；无测试实现时被忽略。 */
    public static final String FAULT_HEADER = "X-P04-Platform-Fault";

    private final PlatformIdentitySource identitySource;
    private final P04PlatformProperties properties;
    private final ObjectProvider<PlatformFaultInjector> faultInjector;

    public InternalAuthorizationController(PlatformIdentitySource identitySource,
                                           P04PlatformProperties properties,
                                           ObjectProvider<PlatformFaultInjector> faultInjector) {
        this.identitySource = identitySource;
        this.properties = properties;
        this.faultInjector = faultInjector;
    }

    /**
     * @param policyVersion 调用方持有的 platform 策略版本
     * @param action        动作标识（如 {@code rag.chat}）
     * @param resourceRef   资源引用；由 AI 侧判定归属，本端点只回显用于证据关联
     */
    public record CheckRequest(String tenantId, String subject, String membershipId, Integer policyVersion,
                               String action, String resourceRef) {
    }

    /**
     * @param policyVersion platform 当前策略版本
     */
    public record CheckResponse(boolean allowed, int policyVersion, String action, String resourceRef) {
    }

    @PostMapping("/authorization/check")
    public ApiResponse<CheckResponse> check(
            @RequestHeader(value = SERVICE_CREDENTIAL_HEADER, required = false) String credential,
            @RequestHeader(value = FAULT_HEADER, required = false) String fault,
            @RequestBody CheckRequest request) {

        requireServiceCredential(credential);
        faultInjector.ifAvailable(injector -> injector.beforeAuthorization(fault));

        if (request.tenantId() == null || request.tenantId().isBlank()) {
            throw new P04Exception(P04ErrorCode.TENANT_CONTEXT_MISSING);
        }
        if (request.subject() == null || request.subject().isBlank()
                || request.membershipId() == null || request.membershipId().isBlank()) {
            throw new P04Exception(P04ErrorCode.MEMBERSHIP_INVALID);
        }

        PlatformIdentitySource.TenantState tenantState = identitySource.tenantState(request.tenantId());
        if (tenantState != PlatformIdentitySource.TenantState.ENABLED) {
            // 不存在与停用都对外表现为同一码，避免泄露租户存在性
            throw new P04Exception(P04ErrorCode.TENANT_DISABLED);
        }

        PlatformIdentitySource.PlatformIdentity identity =
                identitySource.membership(request.tenantId(), request.subject(), request.membershipId());
        if (identity == null || !identity.enabled()) {
            throw new P04Exception(P04ErrorCode.MEMBERSHIP_INVALID);
        }

        if (request.policyVersion() != null && request.policyVersion() < identity.policyVersion()) {
            throw new P04Exception(P04ErrorCode.POLICY_VERSION_STALE);
        }

        Set<String> required = requiredScopes(request.action());
        if (!identity.scopes().containsAll(required)) {
            throw new P04Exception(P04ErrorCode.FORBIDDEN);
        }

        return ApiResponse.ok(new CheckResponse(true, identity.policyVersion(), request.action(),
                request.resourceRef()));
    }

    private static Set<String> requiredScopes(String action) {
        if ("rag.chat".equals(action)) {
            return Set.of("rag.chat.submit");
        }
        return Set.of();
    }

    private void requireServiceCredential(String credential) {
        String expected = properties.getServiceCredential();
        if (credential == null || credential.isBlank()) {
            throw new P04Exception(P04ErrorCode.AUTH_REQUIRED);
        }
        if (expected == null || expected.isBlank() || !constantTimeEquals(expected, credential)) {
            // 不区分「未配置」与「不匹配」的对外表现
            throw new P04Exception(P04ErrorCode.DELEGATION_INVALID);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 便于证据关联：当前请求的 requestId。 */
    public static String currentRequestId() {
        return RequestId.currentOrEmpty();
    }
}
