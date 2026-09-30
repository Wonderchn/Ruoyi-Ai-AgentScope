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

package org.ruoyi.aiintegration.p04;

import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.ruoyi.aiintegration.web.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * <b>测试专用</b>控制面：在靶场运行期调整合成身份状态。
 *
 * <p>只为 N07 这类"状态在场景之间变化"的断言服务（旧策略版本、成员停用）；它修改的也是
 * 合成替身，不触达任何真实身份数据。只存在于测试源集。
 */
@RestController
public class P04TestControlController {

    private final SyntheticPlatformIdentitySource identities;
    private final P04PlatformFaultInjector faultInjector;

    public P04TestControlController(SyntheticPlatformIdentitySource identities,
                                    P04PlatformFaultInjector faultInjector) {
        this.identities = identities;
        this.faultInjector = faultInjector;
    }

    public record FaultRequest(String fault, int seconds) {
    }

    public record ScopesRequest(String tenantId, String subject, String membershipId, java.util.List<String> scopes) {
    }

    /**
     * G4 证据：返回授权端点在 AI→platform 这一跳上实际观察到的 requestId（新的在前）。
     * 用于断言"客户端 requestId 经 AI 透传到 platform"，而不是只看 AI 自己的回显。
     */
    @org.springframework.web.bind.annotation.GetMapping("/p04/control/seen-request-ids")
    public ApiResponse<Map<String, Object>> seenRequestIds() {
        java.util.List<String> ids = faultInjector.seenRequestIds();
        return ApiResponse.ok(Map.of("count", ids.size(), "requestIds", ids));
    }

    /** 清空观察记录，便于按场景取差集。 */
    @PostMapping("/p04/control/clear-seen-request-ids")
    public ApiResponse<Map<String, Object>> clearSeenRequestIds() {
        faultInjector.clearSeenRequestIds();
        return ApiResponse.ok(Map.of("cleared", true));
    }

    /**
     * 覆盖某成员的功能 scope。N04「缺 submit 功能权限」必须改 platform 侧的身份事实，
     * 而不是只改 token 里的 scope 声明——功能权限的权威在 platform（D2=A）。
     */
    @PostMapping("/p04/control/scopes")
    public ApiResponse<Map<String, Object>> setScopes(@RequestBody ScopesRequest request) {
        java.util.Set<String> scopes = request.scopes() == null
                ? java.util.Set.of()
                : new java.util.LinkedHashSet<>(request.scopes());
        identities.setScopes(request.tenantId(), request.subject(), request.membershipId(), scopes);
        return ApiResponse.ok(Map.of("scopes", scopes));
    }

    /**
     * 布防/清除授权端点故障。AI→platform 的调用不能逐请求携带故障头，故由控制面布防；
     * {@code seconds<=0} 或空故障名表示清除。
     */
    @PostMapping("/p04/control/fault")
    public ApiResponse<Map<String, Object>> setFault(@RequestBody FaultRequest request) {
        if (request.fault() == null || request.fault().isBlank() || request.seconds() <= 0) {
            faultInjector.disarm();
            return ApiResponse.ok(Map.of("armed", false));
        }
        faultInjector.arm(request.fault(), request.seconds());
        return ApiResponse.ok(Map.of("armed", true, "fault", request.fault(), "seconds", request.seconds()));
    }

    public record PolicyVersionRequest(String tenantId, String subject, String membershipId, int policyVersion) {
    }

    public record MembershipEnabledRequest(String tenantId, String subject, String membershipId, boolean enabled) {
    }

    public record TenantStateRequest(String tenantId, String state) {
    }

    @PostMapping("/p04/control/policy-version")
    public ApiResponse<Map<String, Object>> setPolicyVersion(@RequestBody PolicyVersionRequest request) {
        identities.setPolicyVersion(request.tenantId(), request.subject(), request.membershipId(),
                request.policyVersion());
        return ApiResponse.ok(Map.of("policyVersion", request.policyVersion()));
    }

    @PostMapping("/p04/control/membership-enabled")
    public ApiResponse<Map<String, Object>> setMembershipEnabled(@RequestBody MembershipEnabledRequest request) {
        identities.setEnabled(request.tenantId(), request.subject(), request.membershipId(), request.enabled());
        return ApiResponse.ok(Map.of("enabled", request.enabled()));
    }

    @PostMapping("/p04/control/tenant-state")
    public ApiResponse<Map<String, Object>> setTenantState(@RequestBody TenantStateRequest request) {
        PlatformIdentitySource.TenantState state =
                PlatformIdentitySource.TenantState.valueOf(request.state().trim().toUpperCase(java.util.Locale.ROOT));
        identities.setTenantState(request.tenantId(), state);
        return ApiResponse.ok(Map.of("state", state.name()));
    }
}
