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

import org.ruoyi.aiintegration.config.P04PlatformProperties;
import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.ruoyi.aiintegration.web.ApiResponse;
import org.ruoyi.aiintegration.web.P04ErrorCode;
import org.ruoyi.aiintegration.web.P04Exception;
import org.ruoyi.aiintegration.web.RequestId;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * 生产装配的内部在线授权复核端点（U04/P1.2b）：{@code POST /internal/platform/v1/authorization/check}。
 *
 * <p>与 {@link InternalAuthorizationController}（P0.4 实验，{@code p04.enabled} 门控）
 * 同路径、同协议形状，但由独立开关 {@code ai.integration.enabled} 装配（默认关），
 * 且身份事实来自真实 platform 身份源（admin 侧
 * {@code org.ruoyi.aiidentity.RuoYiPlatformIdentitySource}，经 {@link ObjectProvider}
 * 注入，integration 不反向依赖 system/admin 的具体类）。
 *
 * <p>与 P0.4 的语义差异（P1 冻结口径）：
 * <ul>
 *   <li>六字段（tenantId/subject/membershipId/policyVersion/action/resourceRef）全部必填；</li>
 *   <li>policyVersion <b>精确相等</b>——旧（落后）与未来（超前）都返回 409；</li>
 *   <li>动作 → 平台权限走 {@link AiActionRegistry} 固定映射，未知动作一律拒绝；</li>
 *   <li>scope 无任何通配豁免：超管身份不得产生 AI 资源 ACL 豁免。</li>
 * </ul>
 *
 * <p>服务凭证：复用 P0.4 的凭证机制（独立共享秘密 + 恒时比较），但属性名独立为
 * {@code ai.integration.authorization.service-credential}；缺省时端点不可用（全部 401）。
 * 响应不回显组织信息（不回显 tenant/subject/membership 之外的事实推导）。
 *
 * @author AI-Integration
 */
@RestController
@RequestMapping("/internal/platform/v1")
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class ProductionAuthorizationController {

    /** 本端点所需的平台身份源（无可用实现时拒绝为 503，不放行）。 */
    private final ObjectProvider<PlatformIdentitySource> identitySource;

    /** AI → platform 服务凭证；缺省（空白）时端点不可用。 */
    private final String serviceCredential;
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<org.ruoyi.aiintegration.identity.ProductionAuthorizationProvider> permitProvider;

    @PostMapping("/authorization/permits/acquire")
    public ResponseEntity<?> acquire(@RequestHeader(value = InternalAuthorizationController.SERVICE_CREDENTIAL_HEADER,
            required = false) String credential,
            @RequestBody org.ruoyi.aiintegration.identity.ProductionAuthorizationProvider.PermitRequest request) {
        try {
            requireServiceCredential(credential);
            var provider = permitProvider.getIfAvailable();
            if (provider == null) { throw new P04Exception(P04ErrorCode.AUTHORIZATION_UNAVAILABLE); }
            return ResponseEntity.ok(ApiResponse.ok(provider.acquire(request)));
        } catch (P04Exception e) { return fail(e.errorCode()); }
        catch (RuntimeException e) { return fail(P04ErrorCode.AUTHORIZATION_UNAVAILABLE); }
    }

    @PostMapping("/authorization/permits/release")
    public ResponseEntity<?> release(@RequestHeader(value = InternalAuthorizationController.SERVICE_CREDENTIAL_HEADER,
            required = false) String credential,
            @RequestBody org.ruoyi.aiintegration.identity.ProductionAuthorizationProvider.PermitRelease request) {
        try {
            requireServiceCredential(credential);
            var provider = permitProvider.getIfAvailable();
            if (provider == null) { throw new P04Exception(P04ErrorCode.AUTHORIZATION_UNAVAILABLE); }
            provider.release(request);
            return ResponseEntity.noContent().build();
        } catch (P04Exception e) { return fail(e.errorCode()); }
        catch (RuntimeException e) { return fail(P04ErrorCode.AUTHORIZATION_UNAVAILABLE); }
    }

    public ProductionAuthorizationController(ObjectProvider<PlatformIdentitySource> identitySource,
                                             @Value("${ai.integration.authorization.service-credential:}")
                                             String serviceCredential) {
        this.identitySource = identitySource;
        this.serviceCredential = serviceCredential;
    }

    /**
     * @param policyVersion 调用方持有的 platform 策略版本（必填）
     * @param action        AI canonical 动作（必填，见 {@link AiActionRegistry}）
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
    public ResponseEntity<? extends ApiResponse<?>> check(
            @RequestHeader(value = InternalAuthorizationController.SERVICE_CREDENTIAL_HEADER, required = false)
            String credential,
            @RequestBody CheckRequest request) {
        try {
            requireServiceCredential(credential);
            PlatformIdentitySource source = requireIdentitySource();

            if (request.tenantId() == null || request.tenantId().isBlank()) {
                throw new P04Exception(P04ErrorCode.TENANT_CONTEXT_MISSING);
            }
            if (request.subject() == null || request.subject().isBlank()
                    || request.membershipId() == null || request.membershipId().isBlank()) {
                throw new P04Exception(P04ErrorCode.MEMBERSHIP_INVALID);
            }
            if (request.policyVersion() == null || request.resourceRef() == null || request.resourceRef().isBlank()) {
                throw new P04Exception(P04ErrorCode.BAD_REQUEST);
            }

            PlatformIdentitySource.TenantState tenantState = source.tenantState(request.tenantId());
            if (tenantState != PlatformIdentitySource.TenantState.ENABLED) {
                // 不存在与停用都对外表现为同一码，避免泄露租户存在性
                throw new P04Exception(P04ErrorCode.TENANT_DISABLED);
            }

            PlatformIdentitySource.PlatformIdentity identity =
                    source.membership(request.tenantId(), request.subject(), request.membershipId());
            if (identity == null || !identity.enabled()) {
                throw new P04Exception(P04ErrorCode.MEMBERSHIP_INVALID);
            }

            // P1 冻结口径：旧（落后）与未来（超前）的 policyVersion 都拒绝
            if (request.policyVersion() != identity.policyVersion()) {
                throw new P04Exception(P04ErrorCode.POLICY_VERSION_STALE);
            }

            Set<String> required = Set.of(AiActionRegistry.requirePermission(request.action()));
            if (!identity.scopes().containsAll(required)) {
                throw new P04Exception(P04ErrorCode.FORBIDDEN);
            }

            return ResponseEntity.ok()
                    .header(RequestId.HEADER, RequestId.currentOrEmpty())
                    .body(ApiResponse.ok(new CheckResponse(true, identity.policyVersion(), request.action(),
                            request.resourceRef())));
        } catch (P04Exception ex) {
            return fail(ex.errorCode());
        }
    }

    private PlatformIdentitySource requireIdentitySource() {
        PlatformIdentitySource source = identitySource.getIfAvailable();
        if (source == null) {
            throw new P04Exception(P04ErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
        return source;
    }

    private void requireServiceCredential(String credential) {
        if (credential == null || credential.isBlank()) {
            throw new P04Exception(P04ErrorCode.AUTH_REQUIRED);
        }
        if (serviceCredential == null || serviceCredential.isBlank()
                || !constantTimeEquals(serviceCredential, credential)) {
            // 不区分「未配置」与「不匹配」的对外表现；缺省配置时端点整体不可用
            throw new P04Exception(P04ErrorCode.DELEGATION_INVALID);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 失败响应遵循本协议口径：HTTP status == body.code，符号码放 {@code data.errorCode}。
     * （生产装配不依赖 {@code p04.enabled} 门控的 {@code P04ExceptionHandler}，自包含映射。）
     */
    private static ResponseEntity<ApiResponse<Map<String, Object>>> fail(P04ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.httpStatus())
                .header(RequestId.HEADER, RequestId.currentOrEmpty())
                .body(ApiResponse.error(errorCode.httpStatus(), errorCode.message(), errorCode.name()));
    }

}
