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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.aiintegration.authorization.OrganizationMatchController;
import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E3：{@link LocalPlatformFacts} 的本地事实匹配语义（Mockito，无 DB/HTTP）。
 *
 * <p>逐条钉住与 {@code /authorization/subjects/match} 的对齐点与收敛差异：
 * pv 陈旧 → STALE；事实不可得/形状非法/未知动作 → ServiceException（UNKNOWN，不放行）；
 * 成员相等、主体引用存活、部门子树、数据范围、仅校验存在五类候选各自走对事实方法。
 */
@Tag("dev")
class LocalPlatformFactsTest {

    private static final String TENANT = "T1";
    private static final String USER = "2101";
    private static final String MEMBER = "platform:T1:2101";
    private static final String MEMBER_REF = "member:" + MEMBER;
    private static final int PV = 7;

    private final PlatformIdentitySource identitySource = mock(PlatformIdentitySource.class);
    private final OrganizationMatchController.SubjectMatchSource matchSource =
            mock(OrganizationMatchController.SubjectMatchSource.class);
    private final LocalPlatformFacts facts = new LocalPlatformFacts(
            provider(PlatformIdentitySource.class, identitySource),
            provider(OrganizationMatchController.SubjectMatchSource.class, matchSource));

    private static <T> ObjectProvider<T> provider(Class<T> type, T instance) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        if (instance != null) {
            factory.registerSingleton("candidate", instance);
        }
        return factory.getBeanProvider(type);
    }

    private static ExecutionPrincipal principal() {
        return new ExecutionPrincipal(TENANT, USER, MEMBER, PV, 3, Set.of("kb.read"),
                "jti-local", "platform:local", 1_700_000_000L, 1_700_000_300L);
    }

    private void stubIdentity(int policyVersion, boolean enabled) {
        when(identitySource.tenantState(TENANT)).thenReturn(PlatformIdentitySource.TenantState.ENABLED);
        when(identitySource.membership(TENANT, USER, MEMBER)).thenReturn(new PlatformIdentitySource.PlatformIdentity(
                TENANT, USER, MEMBER, enabled, Set.of("ai:kb:read"), policyVersion));
    }

    @Test
    @DisplayName("主体引用候选：经 currentSubjects 存活集合命中（引用编码 member:<membershipId>）")
    void subjectRefsCandidateMatchesViaCurrentSubjects() {
        stubIdentity(PV, true);
        when(matchSource.currentSubjects(TENANT, USER, "kb.read"))
                .thenReturn(Set.of(MEMBER_REF, "tenant_all:" + TENANT));

        var outcome = facts.match(principal(), List.of(
                PlatformFactsPort.Candidate.subjectRefs(List.of(MEMBER_REF)),
                PlatformFactsPort.Candidate.subjectRefs(List.of("role:9101"))), "kb.read");

        assertThat(outcome.policyVersion()).isEqualTo(PV);
        assertThat(outcome.matches()).containsExactly(true, false);
    }

    @Test
    @DisplayName("仅校验存在候选：经 subjectExists 判定，缺失主体不因格式合法而放行")
    void validateOnlyCandidateUsesSubjectExists() {
        stubIdentity(PV, true);
        when(matchSource.subjectExists(TENANT, "role:9101")).thenReturn(true);
        when(matchSource.subjectExists(TENANT, "department:404")).thenReturn(false);

        var outcome = facts.match(principal(), List.of(
                PlatformFactsPort.Candidate.validateOnly("role:9101"),
                PlatformFactsPort.Candidate.validateOnly("department:404")), "kb.acl.manage");

        assertThat(outcome.matches()).containsExactly(true, false);
        verify(matchSource, never()).currentSubjects(any(), any(), any());
    }

    @Test
    @DisplayName("数据范围候选：经 withinDataScope 判定，owner 事实不进入本地推断")
    void dataScopeCandidateUsesWithinDataScope() {
        stubIdentity(PV, true);
        when(matchSource.withinDataScope(TENANT, USER, "kb.read", MEMBER, null)).thenReturn(true);
        when(matchSource.withinDataScope(TENANT, USER, "kb.read", null, "1102")).thenReturn(false);

        var outcome = facts.match(principal(), List.of(
                PlatformFactsPort.Candidate.dataScope(MEMBER, null),
                PlatformFactsPort.Candidate.dataScope(null, "1102")), "kb.read");

        assertThat(outcome.matches()).containsExactly(true, false);
    }

    @Test
    @DisplayName("owner 成员候选：canonical membership 精确相等才命中")
    void ownerMemberCandidateRequiresExactMembership() {
        stubIdentity(PV, true);

        var outcome = facts.match(principal(), List.of(
                new PlatformFactsPort.Candidate(MEMBER, null, null, false, false),
                new PlatformFactsPort.Candidate("platform:T1:9999", null, null, false, false)), "kb.read");

        assertThat(outcome.matches()).containsExactly(true, false);
    }

    @Test
    @DisplayName("策略版本陈旧：旧版本一律 STALE，不返回任何匹配结果")
    void stalePolicyVersionIsRejected() {
        stubIdentity(PV + 1, true);

        assertThatThrownBy(() -> facts.match(principal(), List.of(
                PlatformFactsPort.Candidate.subjectRefs(List.of(MEMBER))), "kb.read"))
                .isInstanceOf(StaleVersionException.class);
    }

    @Test
    @DisplayName("事实不可得：租户停用/成员无效/未知动作/无事实源一律 ServiceException（不放行）")
    void unavailableFactsFailClosed() {
        when(identitySource.tenantState(TENANT)).thenReturn(PlatformIdentitySource.TenantState.DISABLED);
        assertThatThrownBy(() -> facts.match(principal(), List.of(), "kb.read"))
                .isInstanceOf(ServiceException.class);

        stubIdentity(PV, false);
        assertThatThrownBy(() -> facts.match(principal(), List.of(), "kb.read"))
                .isInstanceOf(ServiceException.class);

        stubIdentity(PV, true);
        assertThatThrownBy(() -> facts.match(principal(), List.of(), "not-an-action"))
                .isInstanceOf(ServiceException.class);

        LocalPlatformFacts withoutSource = new LocalPlatformFacts(
                provider(PlatformIdentitySource.class, null),
                provider(OrganizationMatchController.SubjectMatchSource.class, matchSource));
        assertThatThrownBy(() -> withoutSource.match(principal(), List.of(), "kb.read"))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    @DisplayName("形状非法：部门 ID 非十进制、空候选元素、超限候选都拒绝（不返回 false）")
    void malformedCandidatesFailClosed() {
        stubIdentity(PV, true);

        assertThatThrownBy(() -> facts.match(principal(), List.of(
                PlatformFactsPort.Candidate.dataScope(null, "abc")), "kb.read"))
                .isInstanceOf(ServiceException.class);

        List<PlatformFactsPort.Candidate> withNull = new ArrayList<>();
        withNull.add(null);
        assertThatThrownBy(() -> facts.match(principal(), withNull, "kb.read"))
                .isInstanceOf(ServiceException.class);

        List<PlatformFactsPort.Candidate> tooMany = new ArrayList<>();
        for (int i = 0; i < LocalPlatformFacts.MAX_CANDIDATES + 1; i++) {
            tooMany.add(PlatformFactsPort.Candidate.dataScope(MEMBER, null));
        }
        assertThatThrownBy(() -> facts.match(principal(), tooMany, "kb.read"))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    @DisplayName("部门子树：主体部门等于 owner 部门或其祖先链包含 owner 部门才命中")
    void deptSubtreeMatch() {
        stubIdentity(PV, true);
        when(matchSource.orgFacts(TENANT, USER)).thenReturn(Optional.of(
                new OrganizationMatchController.SubjectMatchSource.SubjectOrgFacts(1102L, List.of(1100L, 1000L))));

        var outcome = facts.match(principal(), List.of(
                new PlatformFactsPort.Candidate(null, "1102", null, false, false),
                new PlatformFactsPort.Candidate(null, "1000", null, false, false),
                new PlatformFactsPort.Candidate(null, "9999", null, false, false)), "kb.read");

        assertThat(outcome.matches()).containsExactly(true, true, false);
        assertThat(outcome.principalDeptId()).isEqualTo("1102");
    }
}
