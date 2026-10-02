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
import com.nageoffer.ai.ragent.authorization.dao.AiResourceAclMapper.AclRow;
import com.nageoffer.ai.ragent.authorization.dao.AiResourceMapper;
import com.nageoffer.ai.ragent.authorization.dao.AiResourceMapper.AiResourceRow;
import com.nageoffer.ai.ragent.authorization.dao.ResourceSourceRefMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService.Verdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1.3a 持久 ACL 与授权域写路径的行为验收（Mockito 驱动，不连库）。
 *
 * <p>本测试把 framework 的 {@code DefaultResourceAuthorizationService} 与 rag 侧的
 * {@link AiResourceAuthorizationService}（FactPort/SubjectMatchPort 组装）接在一起，
 * 用 mock 的三个 Mapper 驱动，逐条钉住不得放宽的拒绝语义：
 * <ul>
 *   <li>owner 有 ACL 仍受"主体存活"约束——owner 身份不豁免任何一侧；</li>
 *   <li>空 ACL ≠ 公开；TENANT_ALL 是显式 grant 且 subject_id 必空；</li>
 *   <li>expires_at 过期按时间判定即拒，不等定时清理；</li>
 *   <li>tombstone（含父层）优先于任何 ACL；</li>
 *   <li>epoch 缺失 → UNKNOWN（503 语义），绝不默认 1；</li>
 *   <li>grant/revoke 后 av 必须同事务 bump（用事务模板的调用顺序断言）；</li>
 *   <li>跨租户 subject 的 ACL 行不得创建；缺主体一律拒绝。</li>
 * </ul>
 */
class P1PersistentAclTest {

    private static final String TENANT_A = "p1t1";
    private static final String TENANT_B = "p1t2";
    private static final String MEMBER_A = "platform:p1t1:2101";
    private static final String MEMBER_C = "platform:p1t1:3001";
    private static final String MEMBER_B = "platform:p1t2:9999";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final long NOW_SECONDS = NOW.getEpochSecond();

    private final AiResourceMapper resourceMapper = mock(AiResourceMapper.class);
    private final AiResourceAclMapper aclMapper = mock(AiResourceAclMapper.class);
    private final AiAclEpochMapper epochMapper = mock(AiAclEpochMapper.class);
    private final ResourceSourceRefMapper sourceRefMapper = mock(ResourceSourceRefMapper.class);

    private final AiResourceAuthorizationService service = new AiResourceAuthorizationService(
            resourceMapper, aclMapper, epochMapper, sourceRefMapper, Clock.fixed(NOW, java.time.ZoneOffset.UTC));

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    private static ExecutionPrincipal principalOf(String tenantId, String userId, Set<String> scopes) {
        String membership = "platform:" + tenantId + ":" + userId;
        return new ExecutionPrincipal(tenantId, userId, membership, 7, 3,
                scopes, "jti-" + tenantId, "platform", 1_700_000_000L, 1_700_000_060L);
    }

    /** 默认主体：T1 成员 2101，av=3，持 kb.acl.manage（ACL 管理动作）。 */
    private ExecutionPrincipal principal() {
        return principalOf(TENANT_A, "2101", Set.of("kb.acl.manage"));
    }

    private static AiResourceRow kbRow(String kbId, String ownerMemberId, String status) {
        return new AiResourceRow(TENANT_A, "KB", kbId, ownerMemberId, null, null, null, status, 1L);
    }

    private static AiResourceRow docRow(String docId, String parentKbId, String status) {
        return new AiResourceRow(TENANT_A, "DOCUMENT", docId, MEMBER_A, null,
                "KB", parentKbId, status, 1L);
    }

    private static AclRow rule(String resourceType, String resourceId, String subjectType,
                               String subjectId, String action, Long expiresAt) {
        return new AclRow("acl-1", TENANT_A, resourceType, resourceId,
                subjectType, subjectId, action, expiresAt, MEMBER_A);
    }

    private void stubEpoch() {
        when(epochMapper.findVersion(TENANT_A)).thenReturn(Optional.of(3));
    }

    // ------------------------------------------------------------------ 授权判定语义

    @Test
    @DisplayName("基线：owner 持有效 ACL 行且主体存活 → GRANT（证明后续 DENY 不是接线断线）")
    void ownerWithLiveGrantIsGranted() {
        stubEpoch();
        when(resourceMapper.findByPk(TENANT_A, "KB", "kb-1")).thenReturn(Optional.of(kbRow("kb-1", MEMBER_A, "ACTIVE")));
        when(aclMapper.findByResource(TENANT_A, "KB", "kb-1"))
                .thenReturn(List.of(rule("KB", "kb-1", "MEMBER", MEMBER_A, "kb.read", null)));
        when(aclMapper.listTenantRows(TENANT_A))
                .thenReturn(List.of(rule("KB", "kb-1", "MEMBER", MEMBER_A, "kb.read", null)));

        Verdict verdict = service.check(principal(), "kb.read", "kb:kb-1");

        assertThat(verdict).isEqualTo(Verdict.GRANT);
    }

    @Test
    @DisplayName("owner 有 ACL 仍受主体存活约束：owner 仅剩过期行时不豁免，一律 DENY")
    void ownerIsNotExemptFromLiveGrantRequirement() {
        stubEpoch();
        // owner 自己拥有 kb-1，但他的 ACL 行已过期（SubjectMatchPort 因此不认他）
        when(resourceMapper.findByPk(TENANT_A, "KB", "kb-1")).thenReturn(Optional.of(kbRow("kb-1", MEMBER_A, "ACTIVE")));
        when(aclMapper.findByResource(TENANT_A, "KB", "kb-1"))
                .thenReturn(List.of(rule("KB", "kb-1", "MEMBER", MEMBER_A, "kb.read", NOW_SECONDS - 1)));
        when(aclMapper.listTenantRows(TENANT_A))
                .thenReturn(List.of(rule("KB", "kb-1", "MEMBER", MEMBER_A, "kb.read", NOW_SECONDS - 1)));

        Verdict verdict = service.check(principal(), "kb.read", "kb:kb-1");

        // owner 是"允许规则的一部分"，不是 platform 判定的替代品：行过期 → 主体不存活 → 拒绝
        assertThat(verdict).isEqualTo(Verdict.DENY);
    }

    @Test
    @DisplayName("空 ACL ≠ 公开：非 owner 主体在没有任何 ACL 行时被拒绝")
    void emptyAclIsNotPublic() {
        stubEpoch();
        when(resourceMapper.findByPk(TENANT_A, "KB", "kb-1")).thenReturn(Optional.of(kbRow("kb-1", MEMBER_B, "ACTIVE")));
        when(aclMapper.findByResource(TENANT_A, "KB", "kb-1")).thenReturn(List.of());
        when(aclMapper.listTenantRows(TENANT_A)).thenReturn(List.of());

        Verdict verdict = service.check(principal(), "kb.read", "kb:kb-1");

        assertThat(verdict).isEqualTo(Verdict.DENY);
    }

    @Test
    @DisplayName("TENANT_ALL 是显式 grant：写入行 subject_id 必空，且对本租户任何成员生效")
    void tenantAllGrantRequiresEmptySubjectIdAndGrantsWholeTenant() {
        stubEpoch();
        // 授权侧：TENANT_ALL 落库时 subject_id 必空
        ExecutionPrincipal manager = principal();
        PrincipalContext.set(manager);
        when(resourceMapper.findByPk(TENANT_A, "KB", "kb-1")).thenReturn(Optional.of(kbRow("kb-1", MEMBER_A, "ACTIVE")));
        AiResourceWriteService writeService = writeService();

        writeService.grantAclRule("kb-1", new AiResourceWriteService.AclGrant("tenant_all", null, "kb.read", null));

        ArgumentCaptor<AclRow> captor = ArgumentCaptor.forClass(AclRow.class);
        verify(aclMapper).insert(captor.capture());
        assertThat(captor.getValue().subjectType()).isEqualTo("TENANT_ALL");
        assertThat(captor.getValue().subjectId()).as("TENANT_ALL 的 subject_id 必空").isNull();

        // 判定侧：非 owner 的本租户成员经 TENANT_ALL 显式 grant 放行
        when(aclMapper.findByResource(TENANT_A, "KB", "kb-1"))
                .thenReturn(List.of(rule("KB", "kb-1", "TENANT_ALL", null, "kb.read", null)));
        when(aclMapper.listTenantRows(TENANT_A))
                .thenReturn(List.of(rule("KB", "kb-1", "TENANT_ALL", null, "kb.read", null)));
        PrincipalContext.set(principalOf(TENANT_A, "3001", Set.of()));

        Verdict verdict = service.check(PrincipalContext.require(), "kb.read", "kb:kb-1");

        assertThat(verdict).isEqualTo(Verdict.GRANT);
    }

    @Test
    @DisplayName("TENANT_ALL 携带 subject_id：写服务拒绝（约束 ck_ai_resource_acl_subject）")
    void tenantAllWithSubjectIdIsRejected() {
        stubEpoch();
        PrincipalContext.set(principal());
        when(resourceMapper.findByPk(TENANT_A, "KB", "kb-1")).thenReturn(Optional.of(kbRow("kb-1", MEMBER_A, "ACTIVE")));
        AiResourceWriteService writeService = writeService();

        P04AiException rejected = catchThrowableOfType(() -> writeService.grantAclRule("kb-1",
                        new AiResourceWriteService.AclGrant("tenant_all", "someone", "kb.read", null)),
                P04AiException.class);

        assertThat(rejected.errorCode()).isEqualTo(P04AiErrorCode.BAD_REQUEST);
        verify(aclMapper, never()).insert(any());
    }

    @Test
    @DisplayName("expires_at 过期按时间判定即拒：边界（=now）也算过期，未来时间仍有效")
    void expiredGrantIsRefusedByTimeJudgement() {
        stubEpoch();
        String kbId = "kb-1";
        // 刚好在 now 过期：拒绝
        when(resourceMapper.findByPk(TENANT_A, "KB", kbId)).thenReturn(Optional.of(kbRow(kbId, MEMBER_B, "ACTIVE")));
        when(aclMapper.findByResource(TENANT_A, "KB", kbId))
                .thenReturn(List.of(rule("KB", kbId, "MEMBER", MEMBER_A, "kb.read", NOW_SECONDS)));
        when(aclMapper.listTenantRows(TENANT_A))
                .thenReturn(List.of(rule("KB", kbId, "MEMBER", MEMBER_A, "kb.read", NOW_SECONDS)));

        assertThat(service.check(principal(), "kb.read", "kb:" + kbId)).isEqualTo(Verdict.DENY);

        // 60 秒后过期：有效（固定时钟下按时间判定，不依赖任何清理任务）
        when(aclMapper.findByResource(TENANT_A, "KB", kbId))
                .thenReturn(List.of(rule("KB", kbId, "MEMBER", MEMBER_A, "kb.read", NOW_SECONDS + 60)));
        when(aclMapper.listTenantRows(TENANT_A))
                .thenReturn(List.of(rule("KB", kbId, "MEMBER", MEMBER_A, "kb.read", NOW_SECONDS + 60)));

        assertThat(service.check(principal(), "kb.read", "kb:" + kbId)).isEqualTo(Verdict.GRANT);
    }

    @Test
    @DisplayName("tombstone 优先于 ACL：资源本身已删除时，任何有效 grant 都不给放行")
    void tombstonedResourceDeniesDespiteLiveGrant() {
        stubEpoch();
        when(resourceMapper.findByPk(TENANT_A, "KB", "kb-1"))
                .thenReturn(Optional.of(kbRow("kb-1", MEMBER_B, "TOMBSTONED")));
        when(aclMapper.findByResource(TENANT_A, "KB", "kb-1"))
                .thenReturn(List.of(rule("KB", "kb-1", "MEMBER", MEMBER_A, "kb.read", null)));
        when(aclMapper.listTenantRows(TENANT_A))
                .thenReturn(List.of(rule("KB", "kb-1", "MEMBER", MEMBER_A, "kb.read", null)));

        assertThat(service.check(principal(), "kb.read", "kb:kb-1")).isEqualTo(Verdict.DENY);
    }

    @Test
    @DisplayName("父层 tombstone 传播：KB 删除后其下文档即使 grant 有效也不可见")
    void parentTombstonePropagatesToChild() {
        stubEpoch();
        // 文档行 ACTIVE 且带有效 grant，但父 KB 已 tombstone
        when(resourceMapper.findByPk(TENANT_A, "DOCUMENT", "doc-100"))
                .thenReturn(Optional.of(docRow("doc-100", "kb-1", "ACTIVE")));
        when(aclMapper.findByResource(TENANT_A, "DOCUMENT", "doc-100"))
                .thenReturn(List.of(rule("DOCUMENT", "doc-100", "MEMBER", MEMBER_A, "kb.read", null)));
        when(resourceMapper.findByPk(TENANT_A, "KB", "kb-1"))
                .thenReturn(Optional.of(kbRow("kb-1", MEMBER_A, "TOMBSTONED")));
        when(aclMapper.listTenantRows(TENANT_A))
                .thenReturn(List.of(rule("DOCUMENT", "doc-100", "MEMBER", MEMBER_A, "kb.read", null)));

        Verdict verdict = service.check(principal(), "kb.read", "doc:doc-100");

        assertThat(verdict).as("父授权不能让删除链上的子资源复活").isEqualTo(Verdict.DENY);
    }

    @Test
    @DisplayName("epoch 缺失 → UNKNOWN（503 语义），currentAclVersion 抛 ServiceException，不默认 1")
    void missingEpochIsUnknownNotDefaultOne() {
        when(epochMapper.findVersion(TENANT_A)).thenReturn(Optional.empty());

        assertThat(service.check(principal(), "kb.read", "kb:kb-1")).isEqualTo(Verdict.UNKNOWN);
        assertThatThrownBy(() -> service.currentAclVersion(TENANT_A))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("refusing to default");
        verify(resourceMapper, never()).findByPk(anyString(), anyString(), anyString());
    }

    // ------------------------------------------------------------------ 写路径与事务边界

    @Test
    @DisplayName("创建 KB：t_knowledge_base + registry + owner ACL + epoch bump 在同一事务内按序落盘")
    void createKnowledgeBaseWritesEverythingInsideOneTransaction() {
        PrincipalContext.set(principal());
        List<String> events = new ArrayList<>();
        AiResourceWriteService writeService = writeService(events);

        String kbId = writeService.createKnowledgeBase(new AiResourceWriteService.KnowledgeBaseDraft(
                "kb-name", "embedding-x", "collection-x"));

        assertThat(kbId).isNotBlank();
        assertThat(events).containsExactly(
                "tx-begin", "kb-insert", "registry-insert", "acl-insert", "epoch-bump", "tx-commit");

        // 归属只来自主体：kb 元数据与 registry 行都必须写 canonical membershipId
        ArgumentCaptor<AiResourceRow> registry = ArgumentCaptor.forClass(AiResourceRow.class);
        verify(resourceMapper).insert(registry.capture(), eq(MEMBER_A));
        assertThat(registry.getValue().tenantId()).isEqualTo(TENANT_A);
        assertThat(registry.getValue().ownerMemberId()).isEqualTo(MEMBER_A);
        assertThat(registry.getValue().resourceType()).isEqualTo("KB");
        assertThat(registry.getValue().status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("创建 KB 时 epoch 缺失：bump 返回 empty 即拒绝，事务不得提交")
    void createRefusesWhenEpochRowMissing() {
        PrincipalContext.set(principal());
        List<String> events = new ArrayList<>();
        AiResourceWriteService writeService = writeService(events);
        // doReturn 而不是 when(...)：工厂桩已把 bump 接到事件记录上，
        // when() 重打桩会先触发一次旧答案，把事件列表污染出一个假 epoch-bump
        doReturn(Optional.empty()).when(epochMapper).bump(TENANT_A);

        assertThatThrownBy(() -> writeService.createKnowledgeBase(
                new AiResourceWriteService.KnowledgeBaseDraft("n", "m", "c")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("refusing to default");

        assertThat(events).as("epoch 缺失必须让整个事务回滚（无 tx-commit）")
                .endsWith("acl-insert").doesNotContain("epoch-bump", "tx-commit");
    }

    @Test
    @DisplayName("grant 后 av 必须 bump：ACL 写入与 epoch 更新在同一事务边界内先后发生")
    void grantBumpsEpochInsideSameTransaction() {
        stubEpoch();
        PrincipalContext.set(principal());
        when(resourceMapper.findByPk(TENANT_A, "KB", "kb-1")).thenReturn(Optional.of(kbRow("kb-1", MEMBER_A, "ACTIVE")));
        List<String> events = new ArrayList<>();
        AiResourceWriteService writeService = writeService(events);

        writeService.grantAclRule("kb-1",
                new AiResourceWriteService.AclGrant("member", MEMBER_C, "kb.read", NOW_SECONDS + 60));

        assertThat(events).containsExactly("tx-begin", "acl-insert", "epoch-bump", "tx-commit");
        inOrder(aclMapper, epochMapper).verify(aclMapper).insert(any(AclRow.class));
        inOrder(aclMapper, epochMapper).verify(epochMapper).bump(TENANT_A);

        ArgumentCaptor<AclRow> row = ArgumentCaptor.forClass(AclRow.class);
        verify(aclMapper).insert(row.capture());
        assertThat(row.getValue().subjectId()).isEqualTo(MEMBER_C);
        assertThat(row.getValue().expiresAtEpochSecond()).isEqualTo(NOW_SECONDS + 60);
    }

    @Test
    @DisplayName("revoke 后 av 必须 bump：撤权即删行 + 原子 bump；幂等撤销（0 行）不制造版本噪声")
    void revokeBumpsEpochInsideSameTransaction() {
        stubEpoch();
        PrincipalContext.set(principal());
        when(resourceMapper.findByPk(TENANT_A, "KB", "kb-1")).thenReturn(Optional.of(kbRow("kb-1", MEMBER_A, "ACTIVE")));
        List<String> events = new ArrayList<>();
        AiResourceWriteService writeService = writeService(events);

        writeService.revokeAclRule("kb-1", new AiResourceWriteService.AclGrant("member", MEMBER_C, "kb.read", null));

        assertThat(events).containsExactly("tx-begin", "acl-delete", "epoch-bump", "tx-commit");
        inOrder(aclMapper, epochMapper).verify(aclMapper).deleteRule(
                TENANT_A, "KB", "kb-1", "MEMBER", MEMBER_C, "kb.read");
        inOrder(aclMapper, epochMapper).verify(epochMapper).bump(TENANT_A);

        // 幂等撤销：行不存在（0 行）→ 只删不 bump，不制造版本噪声
        events.clear();
        doAnswer(inv -> {
            events.add("acl-delete");
            return 0;
        }).when(aclMapper).deleteRule(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        writeService.revokeAclRule("kb-1", new AiResourceWriteService.AclGrant("member", MEMBER_C, "kb.read", null));
        assertThat(events).containsExactly("tx-begin", "acl-delete", "tx-commit");
    }

    @Test
    @DisplayName("跨租户 subject：ACL 行不得创建，epoch 不得 bump（写服务拒绝）")
    void crossTenantSubjectIsRejected() {
        stubEpoch();
        PrincipalContext.set(principal());
        when(resourceMapper.findByPk(TENANT_A, "KB", "kb-1")).thenReturn(Optional.of(kbRow("kb-1", MEMBER_A, "ACTIVE")));
        AiResourceWriteService writeService = writeService();

        assertThatThrownBy(() -> writeService.grantAclRule("kb-1",
                new AiResourceWriteService.AclGrant("member", MEMBER_B, "kb.read", null)))
                .as("subject 属他租户（platform:p1t2:...）不得写入本租户 ACL")
                .isInstanceOf(P04AiException.class)
                .hasMessageContaining("does not belong to current tenant");

        verify(aclMapper, never()).insert(any(AclRow.class));
        verify(epochMapper, never()).bump(anyString());
    }

    @Test
    @DisplayName("动作未授权：凭证不含 kb.acl.manage 时 ACL 管理 403 语义拒绝")
    void aclManageRequiresScope() {
        stubEpoch();
        PrincipalContext.set(principalOf(TENANT_A, "2101", Set.of()));
        AiResourceWriteService writeService = writeService();

        P04AiException rejected = catchThrowableOfType(() -> writeService.grantAclRule("kb-1",
                        new AiResourceWriteService.AclGrant("member", MEMBER_C, "kb.read", null)),
                P04AiException.class);

        assertThat(rejected.errorCode()).as("动作未授权是 403 语义").isEqualTo(P04AiErrorCode.FORBIDDEN);
        verify(aclMapper, never()).insert(any(AclRow.class));
    }

    @Test
    @DisplayName("缺主体：写路径一律拒绝，不降级、不落任何盘")
    void missingPrincipalIsRefusedEverywhere() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        TransactionOperations txOps = mock(TransactionOperations.class);
        AiResourceWriteService writeService = new AiResourceWriteService(
                jdbc, resourceMapper, aclMapper, epochMapper, txOps);

        assertThatThrownBy(() -> writeService.createKnowledgeBase(
                new AiResourceWriteService.KnowledgeBaseDraft("n", "m", "c")))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> writeService.tombstoneKnowledgeBase("kb-1"))
                .isInstanceOf(ClientException.class);

        verify(jdbc, never()).update(anyString(), anyMap());
        verify(resourceMapper, never()).insert(any(AiResourceRow.class), anyString());
        verify(txOps, never()).execute(any(TransactionCallback.class));
    }

    // ------------------------------------------------------------------ 装配辅助

    /** 无事件记录需求的用例使用本重载。 */
    private AiResourceWriteService writeService() {
        return writeService(new ArrayList<>());
    }

    /**
     * 构造写服务：事务模板用 mock 驱动——回调被同步执行并在前后记录 tx-begin/tx-commit，
     * 使"epoch 更新与 ACL 写在同一事务边界"成为可断言的调用顺序，而不是注释约定。
     */
    private AiResourceWriteService writeService(List<String> events) {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        TransactionOperations txOps = mock(TransactionOperations.class);
        doAnswer(inv -> {
            events.add("tx-begin");
            Object result = ((TransactionCallback<?>) inv.getArgument(0))
                    .doInTransaction(mock(TransactionStatus.class));
            events.add("tx-commit");
            return result;
        }).when(txOps).execute(any(TransactionCallback.class));
        doAnswer(inv -> {
            events.add("tx-begin");
            ((Consumer<TransactionStatus>) inv.getArgument(0)).accept(mock(TransactionStatus.class));
            events.add("tx-commit");
            return null;
        }).when(txOps).executeWithoutResult(any());

        when(jdbc.update(anyString(), anyMap())).thenAnswer(inv -> {
            events.add("kb-insert");
            return 1;
        });
        when(resourceMapper.insert(any(AiResourceRow.class), anyString())).thenAnswer(inv -> {
            events.add("registry-insert");
            return 1;
        });
        when(aclMapper.insert(any(AclRow.class))).thenAnswer(inv -> {
            events.add("acl-insert");
            return 1;
        });
        when(aclMapper.deleteRule(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    events.add("acl-delete");
                    return 1;
                });
        when(epochMapper.bump(anyString())).thenAnswer(inv -> {
            events.add("epoch-bump");
            return Optional.of(4);
        });
        return new AiResourceWriteService(jdbc, resourceMapper, aclMapper, epochMapper, txOps);
    }
}
