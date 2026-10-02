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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 生产装配的组织归属匹配端点（U04/P1.2b）：
 * {@code POST /internal/platform/v1/authorization/subjects/match}。
 *
 * <p>方向是 AI → platform：AI 对「资源 owner 候选集合」逐个询问「当前主体是否就是
 * 该 owner」，用于 AI 侧本地 owner 型 ACL 判定（D2=A 交集的平台事实输入）。
 * 平台只做主体/组织事实匹配，<b>不判定 AI 资源</b>，也<b>不做全组织展开</b>：
 * 无候选 → 空列表；候选超过上限（{@value MAX_CANDIDATES}）→ 400。
 *
 * <p>语义（U04 冻结口径）：
 * <ul>
 *   <li>主体上下文（tenantId/subject/membershipId/policyVersion/action）校验与
 *       {@link ProductionAuthorizationController} 相同：租户启用、成员有效、pv 精确相等
 *       （旧/未来都 409）；action 必须是 {@link AiActionRegistry} 已知动作（未知一律拒绝；
 *       本端点只做归属匹配，不再做 scope 门槛）；</li>
 *   <li>每个候选必须恰好携带 ownerMemberId / ownerDeptId 之一（subjectRefs 可选叠加），
 *       ID 严格校验：格式不合法 → 400（不是 false）；</li>
 *   <li>ownerMemberId：与主体的 canonical membershipId 精确相等才命中；
 *       ownerDeptId：主体所在部门等于该部门或位于其祖先链上（部门子树归属）才命中；
 *       subjectRefs：主体标识出现在候选引用集合中即命中；</li>
 *   <li>响应只含逐候选布尔结果与当前 pv，<b>不回显任何组织信息</b>。</li>
 * </ul>
 *
 * <p>服务凭证门与 {@link ProductionAuthorizationController} 相同：
 * {@code ai.integration.authorization.service-credential}，缺省时端点不可用。
 *
 * @author AI-Integration
 */
@RestController
@RequestMapping("/internal/platform/v1")
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class OrganizationMatchController {

    /** 候选数量上限；超过一律 400，绝不扩为全组织。 */
    public static final int MAX_CANDIDATES = 200;

    /** 部门归属事实端口：由 admin 侧身份源实现（integration 不依赖 system/admin 具体类）。 */
    public interface SubjectMatchSource {

        /**
         * @param tenantId 租户编号
         * @param subject  主体标识（用户 ID 十进制串）
         * @return 主体的部门归属事实；主体未知或无部门时 empty
         */
        Optional<SubjectOrgFacts> orgFacts(String tenantId, String subject);

        /**
         * 主体部门归属事实。
         *
         * @param deptId          主体所在部门
         * @param ancestorDeptIds 该部门的祖先部门链（不含自身）
         */
        record SubjectOrgFacts(Long deptId, List<Long> ancestorDeptIds) {
        }
    }

    /**
     * owner 候选：ownerMemberId（成员）/ ownerDeptId（部门子树）二选一，subjectRefs 可选。
     */
    public record MatchCandidate(String ownerMemberId, String ownerDeptId, List<String> subjectRefs) {
    }

    /**
     * @param policyVersion 调用方持有的 platform 策略版本（必填）
     * @param action        AI canonical 动作（必填，须为已知动作）
     * @param candidates    owner 候选集合；null/空 → 空结果
     */
    public record MatchRequest(String tenantId, String subject, String membershipId, Integer policyVersion,
                               String action, List<MatchCandidate> candidates) {
    }

    /**
     * @param policyVersion platform 当前策略版本（与请求 pv 精确相等）
     * @param matches       与候选顺序一一对应的布尔匹配结果
     */
    public record MatchResponse(int policyVersion, List<Boolean> matches) {
    }

    private final ObjectProvider<PlatformIdentitySource> identitySource;
    private final ObjectProvider<SubjectMatchSource> subjectMatchSource;
    private final String serviceCredential;

    public OrganizationMatchController(ObjectProvider<PlatformIdentitySource> identitySource,
                                       ObjectProvider<SubjectMatchSource> subjectMatchSource,
                                       @Value("${ai.integration.authorization.service-credential:}")
                                       String serviceCredential) {
        this.identitySource = identitySource;
        this.subjectMatchSource = subjectMatchSource;
        this.serviceCredential = serviceCredential;
    }

    @PostMapping("/authorization/subjects/match")
    public ResponseEntity<? extends ApiResponse<?>> match(
            @RequestHeader(value = InternalAuthorizationController.SERVICE_CREDENTIAL_HEADER, required = false)
            String credential,
            @RequestBody MatchRequest request) {
        try {
            requireServiceCredential(credential);
            PlatformIdentitySource source = requireIdentitySource();
            SubjectMatchSource matchSource = requireSubjectMatchSource();

            if (request.tenantId() == null || request.tenantId().isBlank()) {
                throw new P04Exception(P04ErrorCode.TENANT_CONTEXT_MISSING);
            }
            if (request.subject() == null || request.subject().isBlank()
                    || request.membershipId() == null || request.membershipId().isBlank()) {
                throw new P04Exception(P04ErrorCode.MEMBERSHIP_INVALID);
            }
            if (request.policyVersion() == null) {
                throw new P04Exception(P04ErrorCode.BAD_REQUEST);
            }

            PlatformIdentitySource.TenantState tenantState = source.tenantState(request.tenantId());
            if (tenantState != PlatformIdentitySource.TenantState.ENABLED) {
                throw new P04Exception(P04ErrorCode.TENANT_DISABLED);
            }
            PlatformIdentitySource.PlatformIdentity identity =
                    source.membership(request.tenantId(), request.subject(), request.membershipId());
            if (identity == null || !identity.enabled()) {
                throw new P04Exception(P04ErrorCode.MEMBERSHIP_INVALID);
            }
            if (request.policyVersion() != identity.policyVersion()) {
                throw new P04Exception(P04ErrorCode.POLICY_VERSION_STALE);
            }
            // 未知动作一律拒绝（本端点不做 scope 门槛，只做归属匹配）
            AiActionRegistry.requirePermission(request.action());

            List<MatchCandidate> candidates = request.candidates() == null ? List.of() : request.candidates();
            if (candidates.size() > MAX_CANDIDATES) {
                throw new P04Exception(P04ErrorCode.BAD_REQUEST);
            }

            // 仅在需要部门判定时取一次主体部门事实（未知主体 → 部门候选一律不命中）
            Optional<SubjectMatchSource.SubjectOrgFacts> orgFacts = Optional.empty();
            if (candidates.stream().anyMatch(candidate -> candidate != null && candidate.ownerDeptId() != null)) {
                orgFacts = matchSource.orgFacts(request.tenantId(), request.subject());
            }

            List<Boolean> matches = new ArrayList<>(candidates.size());
            for (MatchCandidate candidate : candidates) {
                if (candidate == null) {
                    throw new P04Exception(P04ErrorCode.BAD_REQUEST);
                }
                matches.add(matchCandidate(request, orgFacts, candidate));
            }

            return ResponseEntity.ok()
                    .header(RequestId.HEADER, RequestId.currentOrEmpty())
                    .body(ApiResponse.ok(new MatchResponse(identity.policyVersion(), matches)));
        } catch (P04Exception ex) {
            return fail(ex.errorCode());
        }
    }

    /**
     * 逐候选严格匹配：ID 格式不合法 → 400；合法但与主体不符 → false。
     */
    private boolean matchCandidate(MatchRequest request,
                                   Optional<SubjectMatchSource.SubjectOrgFacts> orgFacts,
                                   MatchCandidate candidate) {
        boolean hasMember = candidate.ownerMemberId() != null && !candidate.ownerMemberId().isBlank();
        boolean hasDept = candidate.ownerDeptId() != null && !candidate.ownerDeptId().isBlank();
        boolean hasRefs = candidate.subjectRefs() != null && !candidate.subjectRefs().isEmpty();
        if (hasMember && hasDept) {
            throw new P04Exception(P04ErrorCode.BAD_REQUEST);
        }
        if (!hasMember && !hasDept && !hasRefs) {
            throw new P04Exception(P04ErrorCode.BAD_REQUEST);
        }

        boolean matched = false;
        if (hasMember) {
            // 严格格式：canonical membership（platform:<tenantId>:<userId>）且租户段与请求一致
            if (!isCanonicalMembershipOfTenant(candidate.ownerMemberId(), request.tenantId())) {
                throw new P04Exception(P04ErrorCode.BAD_REQUEST);
            }
            // 主体就是该 owner 成员：canonical membershipId 精确相等
            matched = candidate.ownerMemberId().equals(request.membershipId());
        }
        if (!matched && hasDept) {
            Long ownerDeptId = parseDeptId(candidate.ownerDeptId());
            if (ownerDeptId == null) {
                throw new P04Exception(P04ErrorCode.BAD_REQUEST);
            }
            matched = orgFacts.map(facts -> matchesDept(facts, ownerDeptId)).orElse(false);
        }
        if (!matched && hasRefs) {
            for (String ref : candidate.subjectRefs()) {
                if (ref == null || ref.isBlank()) {
                    // 引用集合里的空白项属于构造错误，严格拒绝而不是静默跳过
                    throw new P04Exception(P04ErrorCode.BAD_REQUEST);
                }
                if (ref.equals(request.subject())) {
                    matched = true;
                }
            }
        }
        return matched;
    }

    /**
     * 部门子树归属：主体所在部门等于 owner 部门，或其祖先链包含 owner 部门。
     */
    private static boolean matchesDept(SubjectMatchSource.SubjectOrgFacts facts, Long ownerDeptId) {
        if (ownerDeptId.equals(facts.deptId())) {
            return true;
        }
        return facts.ancestorDeptIds() != null && facts.ancestorDeptIds().contains(ownerDeptId);
    }

    /**
     * canonical membership 严格格式校验：platform:&lt;tenantId&gt;:&lt;纯数字 userId&gt;，
     * 且 tenantId 段与请求租户一致。
     */
    private static boolean isCanonicalMembershipOfTenant(String ownerMemberId, String tenantId) {
        String prefix = "platform:" + tenantId + ":";
        if (!ownerMemberId.startsWith(prefix)) {
            return false;
        }
        String userId = ownerMemberId.substring(prefix.length());
        return !userId.isBlank() && userId.length() <= 20 && userId.chars().allMatch(Character::isDigit);
    }

    /**
     * 部门 ID 严格解析：纯数字十进制串（≤19 位），否则视为非法 ID。
     */
    private static Long parseDeptId(String ownerDeptId) {
        if (ownerDeptId.isBlank() || ownerDeptId.length() > 19
                || !ownerDeptId.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Long.parseLong(ownerDeptId);
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    private PlatformIdentitySource requireIdentitySource() {
        PlatformIdentitySource source = identitySource.getIfAvailable();
        if (source == null) {
            throw new P04Exception(P04ErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
        return source;
    }

    private SubjectMatchSource requireSubjectMatchSource() {
        SubjectMatchSource source = subjectMatchSource.getIfAvailable();
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
            throw new P04Exception(P04ErrorCode.DELEGATION_INVALID);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 失败响应遵循本协议口径：HTTP status == body.code，符号码放 {@code data.errorCode}。
     */
    private static ResponseEntity<ApiResponse<Map<String, Object>>> fail(P04ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.httpStatus())
                .header(RequestId.HEADER, RequestId.currentOrEmpty())
                .body(ApiResponse.error(errorCode.httpStatus(), errorCode.message(), errorCode.name()));
    }

}
