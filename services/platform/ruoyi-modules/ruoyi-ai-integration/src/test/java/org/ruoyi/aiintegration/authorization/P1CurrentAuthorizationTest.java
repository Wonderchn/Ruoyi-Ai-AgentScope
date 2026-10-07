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

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.ruoyi.aiintegration.web.P04ErrorCode;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U04/P1.2b：生产授权端点行为（stub SPI 驱动，不起 Spring 容器）。
 *
 * <p>覆盖：pv 精确相等（旧/未来都 409）、未知动作拒绝、组织匹配逐候选布尔结果
 * 与无候选空列表、服务凭证缺失/错误统一 401、缺省配置端点不可用。
 */
@Tag("dev")
class P1CurrentAuthorizationTest {

    private static final String CREDENTIAL = "svc-credential-1";

    /**
     * stub 身份源：固定租户 T1 启用 + 成员 sub-u1（pv=3，scope 含 ai:kb:read）。
     */
    static class StubIdentitySource implements PlatformIdentitySource {

        final Map<String, PlatformIdentity> memberships = new LinkedHashMap<>();

        StubIdentitySource() {
            memberships.put("platform:T1:42", new PlatformIdentity("T1", "42", "platform:T1:42", true,
                Set.of("ai:kb:read", "ai:kb:list"), 3));
            memberships.put("platform:T1:43", new PlatformIdentity("T1", "43", "platform:T1:43", false,
                Set.of("ai:kb:read"), 3));
        }

        @Override
        public TenantState tenantState(String tenantId) {
            return "T1".equals(tenantId) ? TenantState.ENABLED : TenantState.UNKNOWN;
        }

        @Override
        public PlatformIdentity membership(String tenantId, String subject, String membershipId) {
            PlatformIdentity identity = memberships.get(membershipId);
            if (identity == null || !identity.tenantId().equals(tenantId) || !identity.subject().equals(subject)) {
                return null;
            }
            return identity;
        }
    }

    /**
     * stub 组织事实源：subject 42 在部门 7（祖先 5, 1）。
     */
    static class StubSubjectMatchSource implements OrganizationMatchController.SubjectMatchSource {

        @Override
        public Optional<SubjectOrgFacts> orgFacts(String tenantId, String subject) {
            if ("42".equals(subject)) {
                return Optional.of(new SubjectOrgFacts(7L, List.of(5L, 1L)));
            }
            return Optional.empty();
        }
    }

    private static ProductionAuthorizationController checkController() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("stubIdentitySource", new StubIdentitySource());
        return new ProductionAuthorizationController(
            beanFactory.getBeanProvider(PlatformIdentitySource.class), CREDENTIAL);
    }

    private static OrganizationMatchController matchController() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("stubIdentitySource", new StubIdentitySource());
        beanFactory.addBean("stubSubjectMatchSource", new StubSubjectMatchSource());
        return new OrganizationMatchController(
            beanFactory.getBeanProvider(PlatformIdentitySource.class),
            beanFactory.getBeanProvider(OrganizationMatchController.SubjectMatchSource.class),
            CREDENTIAL);
    }

    private static ProductionAuthorizationController.CheckRequest checkRequest(
            String tenantId, String subject, String membershipId, Integer pv, String action) {
        return new ProductionAuthorizationController.CheckRequest(
            tenantId, subject, membershipId, pv, action, "KB-A");
    }

    private static int status(ResponseEntity<? extends org.ruoyi.aiintegration.web.ApiResponse<?>> response) {
        return response.getStatusCode().value();
    }

    @SuppressWarnings("unchecked")
    private static String errorCode(ResponseEntity<? extends org.ruoyi.aiintegration.web.ApiResponse<?>> response) {
        Map<String, Object> data = (Map<String, Object>) response.getBody().data();
        return data == null ? null : String.valueOf(data.get("errorCode"));
    }

    @Test
    void equalPolicyVersionIsAllowedAndEchoesCurrentVersion() {
        var response = checkController().check(CREDENTIAL,
            checkRequest("T1", "42", "platform:T1:42", 3, "kb.read"));
        assertEquals(200, status(response));
        var body = (org.ruoyi.aiintegration.web.ApiResponse<ProductionAuthorizationController.CheckResponse>)
            response.getBody();
        assertEquals(Boolean.TRUE, body.data().allowed());
        assertEquals(3, body.data().policyVersion());
        assertEquals("kb.read", body.data().action());
    }

    @Test
    void staleAndFuturePolicyVersionsAreBothRejectedWith409() {
        var controller = checkController();
        for (Integer pv : new Integer[]{2, 4}) {
            var response = controller.check(CREDENTIAL, checkRequest("T1", "42", "platform:T1:42", pv, "kb.read"));
            assertEquals(409, status(response), () -> "pv=" + pv);
            assertEquals(P04ErrorCode.POLICY_VERSION_STALE.name(), errorCode(response));
        }
    }

    @Test
    void unknownActionsAreRejectedIndependentlyOfScope() {
        var controller = checkController();
        for (String action : new String[]{null, "", "unknown", "rag.chat", "KB.READ", "kb.read "}) {
            var response = controller.check(CREDENTIAL, checkRequest("T1", "42", "platform:T1:42", 3, action));
            assertEquals(403, status(response), () -> "action=" + action);
            assertEquals(P04ErrorCode.FORBIDDEN.name(), errorCode(response));
        }
    }

    @Test
    void missingActionPermissionIsForbidden() {
        var response = checkController().check(CREDENTIAL, checkRequest("T1", "42", "platform:T1:42", 3, "kb.delete"));
        assertEquals(403, status(response));
        assertEquals(P04ErrorCode.FORBIDDEN.name(), errorCode(response));
    }

    @Test
    void missingOrWrongCredentialIsAUnifiedServiceError() {
        var controller = checkController();
        // 缺凭据 → 401
        var missing = controller.check(null, checkRequest("T1", "42", "platform:T1:42", 3, "kb.read"));
        assertEquals(401, status(missing));
        assertEquals(P04ErrorCode.AUTH_REQUIRED.name(), errorCode(missing));
        // 错凭据 → 401（统一服务错误）
        var wrong = controller.check("nope", checkRequest("T1", "42", "platform:T1:42", 3, "kb.read"));
        assertEquals(401, status(wrong));
        assertEquals(P04ErrorCode.DELEGATION_INVALID.name(), errorCode(wrong));
    }

    @Test
    void endpointIsUnavailableWhenCredentialIsNotConfigured() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("stubIdentitySource", new StubIdentitySource());
        var controller = new ProductionAuthorizationController(
            beanFactory.getBeanProvider(PlatformIdentitySource.class), "");
        var response = controller.check(CREDENTIAL, checkRequest("T1", "42", "platform:T1:42", 3, "kb.read"));
        assertEquals(401, status(response));
    }

    @Test
    void disabledMembershipIsRejected() {
        var response = checkController().check(CREDENTIAL, checkRequest("T1", "43", "M-DISABLED", 3, "kb.read"));
        assertEquals(403, status(response));
        assertEquals(P04ErrorCode.MEMBERSHIP_INVALID.name(), errorCode(response));
    }

    @Test
    void missingIdentitySourceFailsClosed() {
        var controller = new ProductionAuthorizationController(
            new StaticListableBeanFactory().getBeanProvider(PlatformIdentitySource.class), CREDENTIAL);
        var response = controller.check(CREDENTIAL, checkRequest("T1", "42", "platform:T1:42", 3, "kb.read"));
        assertEquals(503, status(response));
        assertEquals(P04ErrorCode.AUTHORIZATION_UNAVAILABLE.name(), errorCode(response));
    }

    @Test
    void registryCoversTheFrozenActionTable() {
        // P1 13 + P2 7 + approved P3 4 fixed actions = 24; WP-034A added the two F03
        // conversation-write actions (rename/delete), so the frozen table became 26.
        // W4-9 added the five F09 agent-catalog actions (agent.list/read/write/delete/activate),
        // so the frozen table is now 31. F11 (skill.*) is deliberately not landed in this batch
        // (its runtime closure is not assembled yet — task-13 / W4-T0-46).
        // Runtime authority administration adds three separately permissioned actions.
        assertEquals(34, AiActionRegistry.knownActions().size());
        assertEquals(Optional.of("ai:config:read"), AiActionRegistry.permissionOf("config.read"));
        assertEquals(Optional.of("ai:config:publish"), AiActionRegistry.permissionOf("config.publish"));
        assertEquals(Optional.of("ai:config:revoke"), AiActionRegistry.permissionOf("config.revoke"));
        assertEquals(Optional.of("ai:conversation:write"), AiActionRegistry.permissionOf("conversation.rename"));
        assertEquals(Optional.of("ai:conversation:delete"), AiActionRegistry.permissionOf("conversation.delete"));
        assertEquals(Optional.of("ai:kb:acl"), AiActionRegistry.permissionOf("kb.acl.manage"));
        assertEquals(Optional.of("ai:run:event:read"), AiActionRegistry.permissionOf("run.events"));
        assertEquals(Optional.of("ai:run:submit"), AiActionRegistry.permissionOf("run.submit"));
        assertEquals(Optional.of("ai:run:stream"), AiActionRegistry.permissionOf("run.stream"));
        assertEquals(Optional.of("ai:document:upload"), AiActionRegistry.permissionOf("document.upload"));
        assertEquals(Optional.of("ai:document:ingest"), AiActionRegistry.permissionOf("document.ingest"));
        assertEquals(Optional.of("ai:document:read"), AiActionRegistry.permissionOf("document.list"));
        assertEquals(Optional.of("ai:agent:execute"), AiActionRegistry.permissionOf("agent.execute"));
        assertEquals(Optional.of("ai:run:approve"), AiActionRegistry.permissionOf("run.approve"));
        assertEquals(Optional.of("ai:run:reconcile"), AiActionRegistry.permissionOf("run.reconcile"));
        assertEquals(Optional.of("ai:tool:sandbox:write"), AiActionRegistry.permissionOf("tool.sandbox.write"));
        assertTrue(AiActionRegistry.permissionOf("tool.sandbox.admin").isEmpty());
        assertTrue(AiActionRegistry.permissionOf("mcp.custom.write").isEmpty());
        assertTrue(AiActionRegistry.permissionOf("rag.chat").isEmpty());
        assertTrue(AiActionRegistry.permissionOf(null).isEmpty());
    }

    @Test
    void matchReturnsPerCandidateBooleansWithSamePolicyVersion() {
        var memberOwner = new OrganizationMatchController.MatchCandidate("platform:T1:42", null, null);
        var memberOther = new OrganizationMatchController.MatchCandidate("platform:T1:43", null, null);
        var deptSelf = new OrganizationMatchController.MatchCandidate(null, "7", null);
        var deptAncestor = new OrganizationMatchController.MatchCandidate(null, "5", null);
        var deptUnrelated = new OrganizationMatchController.MatchCandidate(null, "9", null);
        var refMatch = new OrganizationMatchController.MatchCandidate(null, null, List.of("someone", "42"));

        var response = matchController().match(CREDENTIAL, new OrganizationMatchController.MatchRequest(
            "T1", "42", "platform:T1:42", 3, "kb.read",
            List.of(memberOwner, memberOther, deptSelf, deptAncestor, deptUnrelated, refMatch)));

        assertEquals(200, status(response));
        var body = (org.ruoyi.aiintegration.web.ApiResponse<OrganizationMatchController.MatchResponse>)
            response.getBody();
        assertEquals(3, body.data().policyVersion());
        assertEquals(List.of(true, false, true, true, false, true), body.data().matches());
    }

    @Test
    void matchWithNoCandidatesReturnsAnEmptyList() {
        var response = matchController().match(CREDENTIAL, new OrganizationMatchController.MatchRequest(
            "T1", "42", "platform:T1:42", 3, "kb.read", null));
        assertEquals(200, status(response));
        var body = (org.ruoyi.aiintegration.web.ApiResponse<OrganizationMatchController.MatchResponse>)
            response.getBody();
        assertEquals(List.of(), body.data().matches());
    }

    @Test
    void mixedCandidatesUseCurrentFactsWithoutSharingThemAcrossRequests() {
        class CurrentFacts extends StubSubjectMatchSource {
            int subjectReads;
            int orgReads;
            Set<String> subjects = Set.of("member:platform:T1:42", "role:8", "department:7");
            @Override public Set<String> currentSubjects(String tenant, String subject, String action) {
                subjectReads++;
                return subjects;
            }
            @Override public Optional<SubjectOrgFacts> orgFacts(String tenant, String subject) {
                orgReads++;
                return super.orgFacts(tenant, subject);
            }
        }
        var facts = new CurrentFacts();
        var beans = new StaticListableBeanFactory();
        beans.addBean("identity", new StubIdentitySource());
        beans.addBean("facts", facts);
        var controller = new OrganizationMatchController(beans.getBeanProvider(PlatformIdentitySource.class),
                beans.getBeanProvider(OrganizationMatchController.SubjectMatchSource.class), CREDENTIAL);
        var candidates = List.of("member:platform:T1:42", "role:8", "department:7", "member:platform:T2:42").stream()
                .map(ref -> new OrganizationMatchController.MatchCandidate(null, null, List.of(ref))).toList();
        var request = new OrganizationMatchController.MatchRequest("T1", "42", "platform:T1:42", 3, "kb.read", candidates);
        var response = controller.match(CREDENTIAL, request);
        assertEquals(200, status(response));
        assertEquals(List.of(true, true, true, false), ((OrganizationMatchController.MatchResponse) response.getBody().data()).matches());
        assertEquals(1, facts.subjectReads);
        assertEquals(1, facts.orgReads);
        facts.subjects = Set.of("member:platform:T1:42");
        response = controller.match(CREDENTIAL, request);
        assertEquals(List.of(true, false, false, false), ((OrganizationMatchController.MatchResponse) response.getBody().data()).matches());
        assertEquals(2, facts.subjectReads);
        assertEquals(2, facts.orgReads);
    }

    @Test
    void matchRejectsMalformedCandidateIdsStrictly() {
        var controller = matchController();
        // ownerMemberId 格式不合法 → 400（不是 false）
        var malformedMember = new OrganizationMatchController.MatchCandidate("platform:T1:42x", null, null);
        var crossTenant = new OrganizationMatchController.MatchCandidate("platform:T2:42", null, null);
        var malformedDept = new OrganizationMatchController.MatchCandidate(null, "7a", null);
        var bothOwners = new OrganizationMatchController.MatchCandidate("platform:T1:42", "7", null);
        var blankRef = new OrganizationMatchController.MatchCandidate(null, null, List.of(" "));
        for (var candidate : List.of(malformedMember, crossTenant, malformedDept, bothOwners, blankRef)) {
            var response = controller.match(CREDENTIAL, new OrganizationMatchController.MatchRequest(
                "T1", "42", "platform:T1:42", 3, "kb.read", List.of(candidate)));
            assertEquals(400, status(response), () -> "candidate=" + candidate);
        }
        // 候选超限 → 400，绝不扩为全组织
        var tooMany = new OrganizationMatchController.MatchCandidate(null, null, null);
        java.util.List<OrganizationMatchController.MatchCandidate> candidates =
            java.util.stream.IntStream.range(0, OrganizationMatchController.MAX_CANDIDATES + 1)
                .mapToObj(i -> tooMany).toList();
        var overLimit = controller.match(CREDENTIAL, new OrganizationMatchController.MatchRequest(
            "T1", "42", "platform:T1:42", 3, "kb.read", candidates));
        assertEquals(400, status(overLimit));
    }

    @Test
    void matchIsGatedByTheSameServiceCredential() {
        var controller = matchController();
        var missing = controller.match(null, new OrganizationMatchController.MatchRequest(
            "T1", "42", "platform:T1:42", 3, "kb.read", null));
        assertEquals(401, status(missing));
        var wrong = controller.match("nope", new OrganizationMatchController.MatchRequest(
            "T1", "42", "platform:T1:42", 3, "kb.read", null));
        assertEquals(401, status(wrong));
        // 未知动作同样拒绝
        var unknownAction = controller.match(CREDENTIAL, new OrganizationMatchController.MatchRequest(
            "T1", "42", "platform:T1:42", 3, "unknown", null));
        assertEquals(403, status(unknownAction));
    }

}
