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

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.security.PlatformFactsPort;
import com.nageoffer.ai.ragent.framework.security.StaleVersionException;
import org.ruoyi.aiintegration.authorization.AiActionRegistry;
import org.ruoyi.aiintegration.authorization.OrganizationMatchController;
import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@link PlatformFactsPort} 的内嵌本地实现（E3）：同进程直接读取 platform 身份源，
 * 不经任何 localhost HTTP。
 *
 * <p>语义与 {@code POST /internal/platform/v1/authorization/subjects/match}
 * （{@link OrganizationMatchController}）逐条对齐：租户启用 → 成员有效 → pv 精确相等
 * → 逐候选匹配（成员精确相等 / 部门子树 / 主体引用存活 / 数据范围 / 仅校验存在）。
 * 差异只在错误通道：HTTP 形态把失败表达为 4xx/409/503 响应，本实现把
 * <b>版本陈旧</b>表达为 {@link StaleVersionException}（AI 侧 STALE 收敛），
 * 其余一律 {@link ServiceException}（AI 侧 UNKNOWN 收敛，绝不默认放行）。
 *
 * <p>未知动作、超限候选、形状非法候选都按"事实不可得"处理（fail-closed），
 * 不返回"未匹配"。
 */
public class LocalPlatformFacts implements PlatformFactsPort {

    /** 候选数量上限（与 HTTP 端点同一常量；超过一律拒绝，绝不扩为全组织）。 */
    public static final int MAX_CANDIDATES = OrganizationMatchController.MAX_CANDIDATES;

    private final ObjectProvider<PlatformIdentitySource> identitySource;
    private final ObjectProvider<OrganizationMatchController.SubjectMatchSource> subjectMatchSource;

    public LocalPlatformFacts(ObjectProvider<PlatformIdentitySource> identitySource,
                              ObjectProvider<OrganizationMatchController.SubjectMatchSource> subjectMatchSource) {
        this.identitySource = identitySource;
        this.subjectMatchSource = subjectMatchSource;
    }

    @Override
    public MatchOutcome match(ExecutionPrincipal principal, List<Candidate> candidates, String action) {
        PlatformIdentitySource source = identitySource.getIfAvailable();
        OrganizationMatchController.SubjectMatchSource matchSource = subjectMatchSource.getIfAvailable();
        if (source == null || matchSource == null) {
            throw new ServiceException("platform facts source unavailable");
        }
        if (principal == null || principal.tenantId() == null || principal.tenantId().isBlank()
                || principal.userId() == null || principal.userId().isBlank()
                || principal.membershipId() == null || principal.membershipId().isBlank()) {
            throw new ServiceException("platform subject context missing");
        }
        if (source.tenantState(principal.tenantId()) != PlatformIdentitySource.TenantState.ENABLED) {
            // 不存在与停用同形：不泄露租户存在性
            throw new ServiceException("platform tenant unavailable");
        }
        PlatformIdentitySource.PlatformIdentity identity =
                source.membership(principal.tenantId(), principal.userId(), principal.membershipId());
        if (identity == null || !identity.enabled()) {
            throw new ServiceException("platform membership invalid");
        }
        if (principal.policyVersion() != identity.policyVersion()) {
            // 旧/未来版本都拒绝：匹配结果不得跨版本拼合
            throw new StaleVersionException("platform policy changed during subject match");
        }
        try {
            AiActionRegistry.requirePermission(action);
        } catch (RuntimeException unknownAction) {
            throw new ServiceException("platform action unknown");
        }

        List<Candidate> checked = candidates == null ? List.of() : candidates;
        if (checked.size() > MAX_CANDIDATES) {
            throw new ServiceException("platform candidate facts invalid");
        }

        Optional<OrganizationMatchController.SubjectMatchSource.SubjectOrgFacts> orgFacts =
                matchSource.orgFacts(principal.tenantId(), principal.userId());
        boolean needsSubjects = checked.stream().anyMatch(candidate -> candidate != null
                && !candidate.dataScope() && !candidate.validateOnly() && candidate.subjectRefs() != null);
        Set<String> currentSubjects = needsSubjects
                ? matchSource.currentSubjects(principal.tenantId(), principal.userId(), action) : Set.of();

        List<Boolean> matches = new ArrayList<>(checked.size());
        for (Candidate candidate : checked) {
            if (candidate == null) {
                throw new ServiceException("platform candidate facts invalid");
            }
            if (candidate.validateOnly()) {
                if (candidate.dataScope() || candidate.ownerMemberId() != null || candidate.ownerDeptId() != null
                        || candidate.subjectRefs() == null || candidate.subjectRefs().size() != 1) {
                    throw new ServiceException("platform candidate facts invalid");
                }
                matches.add(matchSource.subjectExists(principal.tenantId(), candidate.subjectRefs().get(0)));
            } else if (candidate.dataScope()) {
                if (candidate.ownerMemberId() != null
                        && !isCanonicalMembershipOfTenant(candidate.ownerMemberId(), principal.tenantId())
                        || candidate.ownerDeptId() != null && parseDeptId(candidate.ownerDeptId()) == null) {
                    throw new ServiceException("platform candidate facts invalid");
                }
                matches.add(matchSource.withinDataScope(principal.tenantId(), principal.userId(), action,
                        candidate.ownerMemberId(), candidate.ownerDeptId()));
            } else {
                matches.add(matchCandidate(principal, orgFacts, candidate)
                        || (candidate.subjectRefs() != null
                        && candidate.subjectRefs().stream().anyMatch(currentSubjects::contains)));
            }
        }
        String principalDeptId = orgFacts.map(facts -> String.valueOf(facts.deptId())).orElse(null);
        return new MatchOutcome(identity.policyVersion(), matches, principalDeptId);
    }

    /**
     * 逐候选严格匹配（与 HTTP 端点同一规则）：ID 格式非法 → 事实不可得；
     * 合法但与主体不符 → false。
     */
    private boolean matchCandidate(ExecutionPrincipal principal,
                                   Optional<OrganizationMatchController.SubjectMatchSource.SubjectOrgFacts> orgFacts,
                                   Candidate candidate) {
        boolean hasMember = candidate.ownerMemberId() != null && !candidate.ownerMemberId().isBlank();
        boolean hasDept = candidate.ownerDeptId() != null && !candidate.ownerDeptId().isBlank();
        boolean hasRefs = candidate.subjectRefs() != null && !candidate.subjectRefs().isEmpty();
        if (hasMember && hasDept) {
            throw new ServiceException("platform candidate facts invalid");
        }
        if (!hasMember && !hasDept && !hasRefs) {
            throw new ServiceException("platform candidate facts invalid");
        }

        boolean matched = false;
        if (hasMember) {
            if (!isCanonicalMembershipOfTenant(candidate.ownerMemberId(), principal.tenantId())) {
                throw new ServiceException("platform candidate facts invalid");
            }
            matched = candidate.ownerMemberId().equals(principal.membershipId());
        }
        if (!matched && hasDept) {
            Long ownerDeptId = parseDeptId(candidate.ownerDeptId());
            if (ownerDeptId == null) {
                throw new ServiceException("platform candidate facts invalid");
            }
            matched = orgFacts.map(facts -> matchesDept(facts, ownerDeptId)).orElse(false);
        }
        if (!matched && hasRefs) {
            for (String ref : candidate.subjectRefs()) {
                if (ref == null || ref.isBlank()) {
                    throw new ServiceException("platform candidate facts invalid");
                }
                if (ref.equals(principal.userId())) {
                    matched = true;
                }
            }
        }
        return matched;
    }

    /** 部门子树归属：主体所在部门等于 owner 部门，或其祖先链包含 owner 部门。 */
    private static boolean matchesDept(OrganizationMatchController.SubjectMatchSource.SubjectOrgFacts facts,
                                       Long ownerDeptId) {
        if (ownerDeptId.equals(facts.deptId())) {
            return true;
        }
        return facts.ancestorDeptIds() != null && facts.ancestorDeptIds().contains(ownerDeptId);
    }

    /** canonical membership 严格格式：platform:&lt;tenantId&gt;:&lt;纯数字 userId&gt;，租户段一致。 */
    private static boolean isCanonicalMembershipOfTenant(String ownerMemberId, String tenantId) {
        String prefix = "platform:" + tenantId + ":";
        if (!ownerMemberId.startsWith(prefix)) {
            return false;
        }
        String userId = ownerMemberId.substring(prefix.length());
        return !userId.isBlank() && userId.length() <= 20 && userId.chars().allMatch(Character::isDigit);
    }

    /** 部门 ID 严格解析：纯数字十进制串（≤19 位），否则非法。 */
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
}
