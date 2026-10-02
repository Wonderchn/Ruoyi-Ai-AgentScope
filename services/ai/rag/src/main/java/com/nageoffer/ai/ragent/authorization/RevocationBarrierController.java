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

package com.nageoffer.ai.ragent.authorization;

import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.framework.security.ServiceIdentityVerifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 屏障控制端点（U10）：仅平台 service 身份可调，浏览器/委托主体一律不可达。
 *
 * <p>{@code POST /internal/ai/v1/authorization/barriers}，body 为嵌套 DTO：
 * {@code {action: CLOSE|STATUS|OPEN|UNKNOWN, tenantId, barrierId, targetAclVersion, reason}}。
 * <ul>
 *   <li>CLOSE：置 PENDING（拒绝新 permit），返回当前活跃 permit 数供平台等待 drain；</li>
 *   <li>STATUS：只读返回状态与活跃数；</li>
 *   <li>OPEN：drain 闭合且平台确认安全后的解除；</li>
 *   <li>UNKNOWN：可证实不安全/失联时保持关闭并要求处置——<b>不由本端点做"过期即成功"的推断</b>。</li>
 * </ul>
 * 不装配时（无 integration 开关）该路径不存在（404）。
 */
@Slf4j
@RestController
@RequestMapping("/internal/ai/v1")
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class RevocationBarrierController {

    private final ObjectProvider<RevocationGuard> guard;
    private final ObjectProvider<ServiceIdentityVerifier> serviceIdentity;

    public RevocationBarrierController(ObjectProvider<RevocationGuard> guard,
                                       ObjectProvider<ServiceIdentityVerifier> serviceIdentity) {
        this.guard = guard;
        this.serviceIdentity = serviceIdentity;
    }

    @PostMapping("/authorization/barriers")
    public ResponseEntity<Map<String, Object>> barriers(@RequestBody BarrierRequest request,
            @RequestHeader(value = ServiceIdentityVerifier.SERVICE_CREDENTIAL_HEADER, required = false) String credential) {
        // 服务身份先行：没有 verifier（生产装配缺失）即拒绝，不接受"免检"调用
        ServiceIdentityVerifier verifier = serviceIdentity.getIfAvailable();
        if (verifier == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", 401, "msg", "服务身份校验失败"));
        }
        try {
            verifier.verify(credential);
        } catch (RuntimeException e) {
            // 缺凭据/错凭据统一 401：不向调用方区分两种失败
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", 401, "msg", "服务身份校验失败"));
        }
        RevocationGuard revocations = guard.getIfAvailable();
        if (revocations == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("code", 503, "msg", "屏障执行器不可用"));
        }
        if (request.tenantId() == null || request.tenantId().isBlank() || request.action() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("code", 400, "msg", "tenantId/action 必填"));
        }

        switch (request.action()) {
            case "CLOSE" -> revocations.setBarrierState(request.tenantId(),
                    RevocationGuard.BarrierState.PENDING, request.barrierId(),
                    request.targetAclVersion(), request.reason());
            case "OPEN" -> revocations.setBarrierState(request.tenantId(),
                    RevocationGuard.BarrierState.OPEN, request.barrierId(),
                    request.targetAclVersion(), request.reason());
            case "UNKNOWN" -> revocations.setBarrierState(request.tenantId(),
                    RevocationGuard.BarrierState.UNKNOWN, request.barrierId(),
                    request.targetAclVersion(), request.reason());
            case "STATUS" -> {
                // 只读
            }
            default -> {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("code", 400, "msg", "未知动作"));
            }
        }

        RevocationGuard.BarrierState state = revocations.barrierState(request.tenantId());
        long active = revocations.activePermitCount(request.tenantId());
        log.info("屏障端点调用 action={}, tenantId={}, state={}, activePermits={}",
                request.action(), request.tenantId(), state, active);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "status", state.name(),
                "activePermits", active,
                "barrierId", request.barrierId() == null ? "" : request.barrierId()));
    }

    /** 嵌套 DTO：serviceCredential 为服务身份凭据（不落日志）。 */
    record BarrierRequest(String action, String tenantId, String barrierId,
                          Integer targetAclVersion, String reason, String serviceCredential) {
    }
}
