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

package org.ruoyi.aiintegration.delegation;

import org.ruoyi.aiintegration.web.ApiResponse;
import org.ruoyi.aiintegration.web.P04ErrorCode;
import org.ruoyi.aiintegration.web.P04Exception;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * 测试专用的委托签发与公钥分发端点。
 *
 * <p>这两个端点只服务于 P0.4 的合成靶场（私有端口、非生产接线）：签发端点按合成身份与
 * {@link DelegationVariant} 产出正/负例凭证，公钥端点把受信公钥交给 AI 侧（AI 只持公钥）。
 * 生产的签发路径是登录流程，属 P1。
 */
@RestController
@RequestMapping("/internal/platform/v1")
public class InternalDelegationController {

    private final DelegationIssuer issuer;
    private final DelegationSigningKeys keys;

    public InternalDelegationController(DelegationIssuer issuer, DelegationSigningKeys keys) {
        this.issuer = issuer;
        this.keys = keys;
    }

    /**
     * @param variant 负例变体名；缺省为 {@code NONE}（合法凭证）
     */
    public record IssueRequest(String tenantId, String subject, String membershipId, List<String> scopes,
                              Integer policyVersion, Integer ttlSeconds, String variant) {
    }

    public record IssueResponse(String token, String jti, String kid) {
    }

    public record PublicKeyResponse(String kid, String pem, String algorithm) {
    }

    @PostMapping("/delegations")
    public ApiResponse<IssueResponse> issue(@RequestBody IssueRequest request) {
        DelegationVariant variant = parseVariant(request.variant());
        DelegationIssuer.Issued issued = issuer.issue(variant, request.tenantId(), request.subject(),
                request.membershipId(), request.scopes(), request.policyVersion(), request.ttlSeconds());
        return ApiResponse.ok(new IssueResponse(issued.token(), issued.jti(), issued.kid()));
    }

    @GetMapping("/keys/public")
    public ApiResponse<PublicKeyResponse> publicKey() {
        return ApiResponse.ok(new PublicKeyResponse(keys.kid(), keys.publicKeyPem(), "RS256"));
    }

    private static DelegationVariant parseVariant(String raw) {
        if (raw == null || raw.isBlank()) {
            return DelegationVariant.NONE;
        }
        try {
            return DelegationVariant.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new P04Exception(P04ErrorCode.BAD_REQUEST, "unknown variant");
        }
    }
}
