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

import com.nageoffer.ai.ragent.authorization.dao.AiAclEpochMapper;
import com.nageoffer.ai.ragent.authorization.dao.AiResourceAclMapper;
import com.nageoffer.ai.ragent.authorization.dao.AiResourceMapper;
import com.nageoffer.ai.ragent.authorization.dao.ResourceSourceRefMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.security.PlatformFactsPort;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R12 卡3 · F-W4-1：dataScope × GRANT 合成语义（定向 ACL 分享生效）。
 *
 * <p><b>为什么必须有这一条。</b>{@code check()} 的 dataScopeAllows 二次过滤问
 * "资源 owner 是否在我的数据范围内"——对显式定向分享（ACL 规则具名授给某 member），
 * 它会把 delegate 的 GRANT 整体压回 DENY（W4 实证：data_scope=5 的成员被授权后仍 404，
 * ACL 机制整体失效）。修复后：**定向（member/role/department）规则命中 ⇒ dataScope
 * 否决不再适用**；而 {@code tenant_all}（隐式全员）与 owner 路径**继续求交**
 * （既有语义与既有判据不动——对照 {@code AuthorizationBatchReadTest}）。
 *
 * <p>本判据把四个分支钉死：定向命中免否决（新语义）/ tenant_all 维持否决 /
 * 过期定向规则不豁免 / 无规则维持 DENY；另有批量路径（filterDataScope 再准入）一条。
 */
@Tag("dev")
class AiResourceTargetedGrantTest {

    private static final String TENANT = "T1";
    private static final String PRINCIPAL_MEMBER = "platform:T1:2101";
    private static final String OUT_OF_SCOPE_OWNER = "platform:T1:9998";

    /** 组装服务：mock Mapper 面 + mock 平台事实（owner=9998 一律"出数据范围"，主体候选一律"当前持有"）。 */
    private static AiResourceAuthorizationService service(AiResourceMapper resources, AiResourceAclMapper acls) {
        AiAclEpochMapper epochs = mock(AiAclEpochMapper.class);
        when(epochs.findVersion(TENANT)).thenReturn(Optional.of(1));
        PlatformFactsPort facts = mock(PlatformFactsPort.class);
        ObjectProvider<PlatformFactsPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(facts);
        when(facts.match(any(), anyList(), eq("kb.read"))).thenAnswer(call -> {
            List<PlatformFactsPort.Candidate> candidates = call.getArgument(1);
            return new PlatformFactsPort.MatchOutcome(1, candidates.stream()
                    .map(candidate -> !candidate.dataScope()
                            || !OUT_OF_SCOPE_OWNER.equals(candidate.ownerMemberId()))
                    .toList(), null);
        });
        var service = new AiResourceAuthorizationService(resources, acls, epochs,
                mock(ResourceSourceRefMapper.class), Clock.systemUTC());
        service.configurePlatformFacts(provider);
        return service;
    }

    private static ExecutionPrincipal principal() {
        return new ExecutionPrincipal(TENANT, "2101", PRINCIPAL_MEMBER, 1, 1,
                Set.of("kb.read"), "jti", "test", 0, Long.MAX_VALUE);
    }

    private static AiResourceMapper.AiResourceRow row(String id) {
        return new AiResourceMapper.AiResourceRow(TENANT, "KB", id, OUT_OF_SCOPE_OWNER, null, null, null, "ACTIVE", 1);
    }

    private static AiResourceAclMapper.AclRow rule(String id, String subjectType, String subjectId, Long expires) {
        return new AiResourceAclMapper.AclRow("acl-" + id, TENANT, "KB", id, subjectType, subjectId, "kb.read", expires, PRINCIPAL_MEMBER);
    }

    private static AiResourceMapper singleResource(String id, AiResourceMapper.AiResourceRow row) {
        AiResourceMapper resources = mock(AiResourceMapper.class);
        when(resources.findByPk(TENANT, "KB", id)).thenReturn(Optional.of(row));
        return resources;
    }

    private static AiResourceAclMapper singleAcls(String id, List<AiResourceAclMapper.AclRow> rules) {
        AiResourceAclMapper acls = mock(AiResourceAclMapper.class);
        when(acls.findByResource(TENANT, "KB", id)).thenReturn(rules);
        return acls;
    }

    @Test
    @DisplayName("定向 member 授权：owner 出数据范围也放行（卡3 新语义；此前被压回 DENY）")
    void targetedMemberGrantOverridesOwnerDataScope() {
        var service = service(singleResource("kb-1", row("kb-1")),
                singleAcls("kb-1", List.of(rule("kb-1", "MEMBER", PRINCIPAL_MEMBER, null))));

        Verdict verdict = service.check(principal(), "kb.read", "kb:kb-1");

        assertThat(verdict).isEqualTo(Verdict.GRANT);
    }

    @Test
    @DisplayName("tenant_all 授权：维持 owner 数据范围求交（隐式全员非定向分享，语义不变）")
    void tenantAllGrantStillObeysOwnerDataScope() {
        var service = service(singleResource("kb-2", row("kb-2")),
                singleAcls("kb-2", List.of(rule("kb-2", "TENANT_ALL", null, null))));

        Verdict verdict = service.check(principal(), "kb.read", "kb:kb-2");

        assertThat(verdict).isEqualTo(Verdict.DENY);
    }

    @Test
    @DisplayName("已过期的定向授权：不豁免（过期即不生效，不等定时清理）")
    void expiredTargetedGrantDoesNotExempt() {
        long past = Clock.systemUTC().instant().getEpochSecond() - 60;
        var service = service(singleResource("kb-3", row("kb-3")),
                singleAcls("kb-3", List.of(rule("kb-3", "MEMBER", PRINCIPAL_MEMBER, past))));

        Verdict verdict = service.check(principal(), "kb.read", "kb:kb-3");

        assertThat(verdict).isEqualTo(Verdict.DENY);
    }

    @Test
    @DisplayName("无任何授权的他人资源：维持 DENY（越权方向不放宽）")
    void noGrantStaysDeny() {
        var service = service(singleResource("kb-4", row("kb-4")), singleAcls("kb-4", List.of()));

        Verdict verdict = service.check(principal(), "kb.read", "kb:kb-4");

        assertThat(verdict).isEqualTo(Verdict.DENY);
    }

    @Test
    @DisplayName("批量路径同口径：定向分享再准入、tenant_all 仍被数据范围否决")
    void batchPathReAdmitsTargetedGrantOnly() {
        AiResourceMapper resources = mock(AiResourceMapper.class);
        AiResourceAclMapper acls = mock(AiResourceAclMapper.class);
        when(resources.findByIds(eq(TENANT), anyMap()))
                .thenReturn(new ArrayList<>(List.of(row("kb-1"), row("kb-2"))));
        when(acls.findByIds(eq(TENANT), anyMap())).thenReturn(new ArrayList<>(List.of(
                rule("kb-1", "MEMBER", PRINCIPAL_MEMBER, null),
                rule("kb-2", "TENANT_ALL", null, null))));
        var service = service(resources, acls);

        var verdicts = service.checkBatch(principal(), "kb.read", List.of("kb:kb-1", "kb:kb-2"));

        assertThat(verdicts.get("kb:kb-1")).as("定向分享在批量路径同样生效").isEqualTo(Verdict.GRANT);
        assertThat(verdicts.get("kb:kb-2")).as("tenant_all 维持数据范围求交").isEqualTo(Verdict.DENY);
    }

    @Test
    @DisplayName("折叠后的单 ref 路径同口径：恰 1 条定向授权经 filterDataScope 单决不得被丢（复核问题1）")
    void singleGrantAfterFoldStillReAdmitted() {
        AiResourceMapper resources = mock(AiResourceMapper.class);
        AiResourceAclMapper acls = mock(AiResourceAclMapper.class);
        when(resources.findByIds(eq(TENANT), anyMap()))
                .thenReturn(new ArrayList<>(List.of(row("kb-1"), row("kb-2"))));
        when(acls.findByIds(eq(TENANT), anyMap())).thenReturn(new ArrayList<>(List.of(
                rule("kb-1", "MEMBER", PRINCIPAL_MEMBER, null))));
        // 折叠后的单决分支按 dataScopeAllows/targetedGrantAllows 的单读事实路径取数
        when(resources.findByPk(TENANT, "KB", "kb-1")).thenReturn(Optional.of(row("kb-1")));
        when(acls.findByResource(TENANT, "KB", "kb-1")).thenReturn(List.of(rule("kb-1", "MEMBER", PRINCIPAL_MEMBER, null)));
        var service = service(resources, acls);

        // 入参 2 个，但 delegate 只授 1 个 ⇒ filterDataScope 走 refs.size()==1 的单决分支
        var verdicts = service.checkBatch(principal(), "kb.read", List.of("kb:kb-1", "kb:kb-2"));

        assertThat(verdicts.get("kb:kb-1")).as("单决分支必须与批量/check 同口径再准入").isEqualTo(Verdict.GRANT);
        assertThat(verdicts.get("kb:kb-2")).as("无授权维持 DENY").isEqualTo(Verdict.DENY);
    }
}
