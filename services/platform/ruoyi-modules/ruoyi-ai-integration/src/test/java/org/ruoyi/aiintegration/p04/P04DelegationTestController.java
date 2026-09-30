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

import org.ruoyi.aiintegration.delegation.DelegationIssuer;
import org.ruoyi.aiintegration.delegation.DelegationSigningKeys;
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
 * <b>测试专用</b>的委托签发与公钥分发端点（阻断修复 Spec §2.3）。
 *
 * <p>此前与签发核心同在主源集，且带 {@code variant} 参数——那等于把"任意身份 + 负例凭证"
 * 的铸造能力一起带进生产代码路径。现在它只在测试源集存在：合法签发仍走主源集的
 * {@link DelegationIssuer}，负例由 {@link NegativeDelegationMinter} 构造。
 */
@RestController
@RequestMapping("/internal/platform/v1")
public class P04DelegationTestController {

    private final NegativeDelegationMinter minter;
    private final DelegationSigningKeys keys;

    public P04DelegationTestController(NegativeDelegationMinter minter, DelegationSigningKeys keys) {
        this.minter = minter;
        this.keys = keys;
    }

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
        NegativeDelegationMinter.Minted minted = minter.mint(variant, request.tenantId(), request.subject(),
                request.membershipId(), request.scopes(), request.policyVersion(), request.ttlSeconds());
        return ApiResponse.ok(new IssueResponse(minted.token(), minted.jti(), minted.kid()));
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
