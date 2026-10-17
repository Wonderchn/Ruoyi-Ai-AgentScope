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
import com.nageoffer.ai.ragent.framework.security.AuthorizationChecker;
import com.nageoffer.ai.ragent.framework.security.PlatformFactsPort;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService.Verdict;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R17 F05:0：资源面 ACL <b>四类主体</b>（member/department/role/tenant_all）的
 * grant → read → revoke → 撤销后不可达（离线服务层，Mockito 驱动，不连库）。
 *
 * <p>判据映射（任务单判据 2、3）：
 * <ul>
 *   <li>{@link #memberGrantReadRevokeCycle}、{@link #departmentGrantReadRevokeCycle}、
 *       {@link #roleGrantReadRevokeCycle}、{@link #tenantAllGrantReadRevokeCycle}：每类一条完整循环——
 *       写入坐标（DB 主体类型/主体 id）、读判定 GRANT、撤销删同一坐标、撤销后同一路径 DENY；</li>
 *   <li>{@link #emptyAclIsNeverExpandedToWholeTenant}（判据 1 读侧）：空 ACL 且主体持 kb.read、
 *       同租户非 owner ⇒ DENY，且按资源 ref 精确取数（没有"全租户可见"分支）；</li>
 *   <li>{@link #crossTenantReaderGetsNothing}：别租户主体取不到本租户资源，取数租户来自主体。</li>
 * </ul>
 *
 * <p>边界（只报告不冒领）：主体<b>匹配</b>（某 role/department 是否属于当前主体）由平台事实端口
 * （{@code PlatformFactsPort}）决定，离线用替身；本类钉的是 AI 侧 ACL 行坐标、写入/撤销与判定装配，
 * 真实匹配语义属真机腿。既有覆盖（{@code P1PersistentAclTest}/{@code AiResourceTargetedGrantTest}）
 * 未含 department/role 的 grant→revoke 循环，本类补齐四类闭环。
 *
 * <p>放在 rag 模块测试树：runtime 产品代码零改动（{@code AiResourceController} 只读参照）。
 */
@Tag("dev")
class AiResourceAclSubjectClassCycleTest {

    private static final String TENANT = "T1";
    private static final String OTHER_TENANT = "T2";
    private static final String KB_ID = "kb-1";
    /** 资源 owner（非读取者）：owner 路径不得成为"撤销后仍可读"的后门。 */
    private static final String OWNER_MEMBER = "platform:T1:2101";
    /** ACL 的读取主体（非 owner）。 */
    private static final String READER_MEMBER = "platform:T1:3001";

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    private final AiResourceMapper resourceMapper = mock(AiResourceMapper.class);
    private final AiResourceAclMapper aclMapper = mock(AiResourceAclMapper.class);
    private final AiAclEpochMapper epochMapper = mock(AiAclEpochMapper.class);

    @AfterEach
    void clear() {
        PrincipalContext.clear();
    }

    // ------------------------------------------------------------------ 判据 2 / 3：四类逐类闭环

    @Test
    @DisplayName("member 类：grant→read GRANT→revoke 删同坐标→撤销后 DENY")
    void memberGrantReadRevokeCycle() {
        assertCycle("member", READER_MEMBER, "MEMBER", READER_MEMBER);
    }

    @Test
    @DisplayName("department 类：grant→read GRANT→revoke 删同坐标→撤销后 DENY")
    void departmentGrantReadRevokeCycle() {
        assertCycle("department", "1102", "DEPT", "1102");
    }

    @Test
    @DisplayName("role 类：grant→read GRANT→revoke 删同坐标→撤销后 DENY")
    void roleGrantReadRevokeCycle() {
        assertCycle("role", "9101", "ROLE", "9101");
    }

    @Test
    @DisplayName("tenant_all 类：subject_id 必空、grant→read GRANT→revoke→撤销后 DENY")
    void tenantAllGrantReadRevokeCycle() {
        assertCycle("tenant_all", null, "TENANT_ALL", null);
    }

    // ------------------------------------------------------------------ 判据 1（读侧）：空授权不扩全租户

    @Test
    @DisplayName("空 ACL ≠ 全租户可见：同租户非 owner 主体即使在册也 DENY，且按资源 ref 精确取数")
    void emptyAclIsNeverExpandedToWholeTenant() {
        when(epochMapper.findVersion(TENANT)).thenReturn(Optional.of(1));
        when(resourceMapper.findByPk(TENANT, "KB", KB_ID)).thenReturn(Optional.of(kbRow()));
        when(aclMapper.findByResource(TENANT, "KB", KB_ID)).thenReturn(List.of());
        when(aclMapper.listTenantRows(TENANT)).thenReturn(List.of());

        Verdict verdict = authz().check(reader(TENANT, READER_MEMBER), "kb.read", "kb:" + KB_ID);

        assertThat(verdict).as("空授权集合必须拒绝，不得回落为全租户可见").isEqualTo(Verdict.DENY);
        verify(aclMapper).findByResource(TENANT, "KB", KB_ID);
    }

    @Test
    @DisplayName("别租户主体：本租户资源取不到，且取数租户来自主体而非资源")
    void crossTenantReaderGetsNothing() {
        when(epochMapper.findVersion(OTHER_TENANT)).thenReturn(Optional.of(1));
        when(resourceMapper.findByPk(OTHER_TENANT, "KB", KB_ID)).thenReturn(Optional.empty());
        when(aclMapper.findByResource(OTHER_TENANT, "KB", KB_ID)).thenReturn(List.of());
        when(aclMapper.listTenantRows(OTHER_TENANT)).thenReturn(List.of());

        Verdict verdict = authz().check(reader(OTHER_TENANT, "platform:T2:7777"), "kb.read", "kb:" + KB_ID);

        assertThat(verdict).isEqualTo(Verdict.DENY);
        verify(resourceMapper).findByPk(OTHER_TENANT, "KB", KB_ID);
    }

    // ------------------------------------------------------------------ 循环本体

    /**
     * 一类主体的完整闭环：写入坐标 → 在册时 GRANT → 撤销删除同一坐标 → 撤销后同一路径 DENY。
     *
     * @param grantSubjectType 提交侧主体类型（framework 编码：member/department/role/tenant_all）
     * @param grantSubjectId   提交侧主体 id（tenant_all 必空）
     * @param expectedDbType   期望落库的 DB 主体类型（MEMBER/DEPT/ROLE/TENANT_ALL）
     * @param expectedSubjectId 期望落库的主体 id（tenant_all 为 null）
     */
    private void assertCycle(String grantSubjectType, String grantSubjectId,
                             String expectedDbType, String expectedSubjectId) {
        PrincipalContext.set(manager());
        when(epochMapper.findVersion(TENANT)).thenReturn(Optional.of(1));
        when(resourceMapper.findByPk(TENANT, "KB", KB_ID)).thenReturn(Optional.of(kbRow()));
        AiResourceWriteService writes = writeService();

        // grant：落库坐标必须与提交的主体类型/id 一一对应
        writes.grantAclRule(KB_ID, new AiResourceWriteService.AclGrant(
                grantSubjectType, grantSubjectId, "kb.read", null));

        ArgumentCaptor<AclRow> inserted = ArgumentCaptor.forClass(AclRow.class);
        verify(aclMapper).insert(inserted.capture());
        assertThat(inserted.getValue().subjectType()).isEqualTo(expectedDbType);
        assertThat(inserted.getValue().subjectId()).as("subject_id 必须与主体类型匹配（tenant_all 必空）")
                .isEqualTo(expectedSubjectId);
        assertThat(inserted.getValue().action()).isEqualTo("kb.read");

        // read：规则在册 → 非 owner 的读取主体 GRANT（证明后续 DENY 不是接线断线）
        AclRow liveRule = new AclRow("acl-1", TENANT, "KB", KB_ID,
                expectedDbType, expectedSubjectId, "kb.read", null, OWNER_MEMBER);
        when(aclMapper.findByResource(TENANT, "KB", KB_ID)).thenReturn(List.of(liveRule));
        when(aclMapper.listTenantRows(TENANT)).thenReturn(List.of(liveRule));
        assertThat(authz().check(reader(TENANT, READER_MEMBER), "kb.read", "kb:" + KB_ID))
                .as("授权在册时应可达").isEqualTo(Verdict.GRANT);

        // revoke：删除同一坐标
        writes.revokeAclRule(KB_ID, new AiResourceWriteService.AclGrant(
                grantSubjectType, grantSubjectId, "kb.read", null));
        verify(aclMapper).deleteRule(TENANT, "KB", KB_ID, expectedDbType, expectedSubjectId, "kb.read");

        // 撤销后：同一路径不可达
        when(aclMapper.findByResource(TENANT, "KB", KB_ID)).thenReturn(List.of());
        when(aclMapper.listTenantRows(TENANT)).thenReturn(List.of());
        assertThat(authz().check(reader(TENANT, READER_MEMBER), "kb.read", "kb:" + KB_ID))
                .as("撤销后同一路径必须不可达").isEqualTo(Verdict.DENY);
    }

    // ------------------------------------------------------------------ 主体与装配辅助

    private static ExecutionPrincipal manager() {
        return new ExecutionPrincipal(TENANT, "2101", OWNER_MEMBER, 1, 1,
                Set.of("kb.acl.manage", "kb.write"), "jti-manager", "platform", 0, Long.MAX_VALUE);
    }

    private static ExecutionPrincipal reader(String tenantId, String membershipId) {
        return new ExecutionPrincipal(tenantId, membershipId.substring(membershipId.lastIndexOf(':') + 1),
                membershipId, 1, 1, Set.of("kb.read"), "jti-reader", "platform", 0, Long.MAX_VALUE);
    }

    private static AiResourceRow kbRow() {
        return new AiResourceRow(TENANT, "KB", KB_ID, OWNER_MEMBER, null, null, null, "ACTIVE", 1L);
    }

    /** 判定服务：平台事实替身把每个主体候选都判为"当前持有"（真实匹配语义属真机腿）。 */
    private AiResourceAuthorizationService authz() {
        PlatformFactsPort facts = mock(PlatformFactsPort.class);
        ObjectProvider<PlatformFactsPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(facts);
        when(facts.match(any(), anyList(), eq("kb.read"))).thenAnswer(call -> {
            List<PlatformFactsPort.Candidate> candidates = call.getArgument(1);
            return new PlatformFactsPort.MatchOutcome(1,
                    candidates.stream().map(candidate -> Boolean.TRUE).toList(), null);
        });
        AiResourceAuthorizationService service = new AiResourceAuthorizationService(resourceMapper, aclMapper,
                epochMapper, mock(ResourceSourceRefMapper.class), Clock.fixed(NOW, ZoneOffset.UTC));
        service.configurePlatformFacts(provider);
        return service;
    }

    /** 写服务：事务模板同步执行；授权/平台/守卫替身一律放行（写坐标才是本类要钉的）。 */
    private AiResourceWriteService writeService() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        TransactionOperations txOps = mock(TransactionOperations.class);
        // void/default 方法不能用 when(...)（javac: void 类型不允许出现在此处），统一 doAnswer
        doAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(mock(TransactionStatus.class)))
                .when(txOps).execute(any(TransactionCallback.class));
        doAnswer(inv -> {
            ((Consumer<TransactionStatus>) inv.getArgument(0)).accept(mock(TransactionStatus.class));
            return null;
        }).when(txOps).executeWithoutResult(any());
        when(jdbc.update(anyString(), anyMap())).thenReturn(1);
        // 写路径的 epoch 版本来自 FOR UPDATE 读（不是 epochMapper.findVersion）：必须与主体 aclVersion 一致
        when(jdbc.query(contains("FOR UPDATE"), anyMap(), any(RowMapper.class))).thenReturn(List.of(1));
        when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(0L);
        when(aclMapper.insert(any(AclRow.class))).thenReturn(1);
        when(aclMapper.deleteRule(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);
        when(epochMapper.bump(anyString())).thenReturn(Optional.of(2));

        RevocationGuard guard = mock(RevocationGuard.class);
        when(guard.enter(any(), anyString(), anyString()))
                .thenReturn(new RevocationGuard.Operation(guard, "permit", "op"));
        ResourceAuthorizationService resources = mock(ResourceAuthorizationService.class);
        when(resources.check(any(), anyString(), anyString())).thenReturn(Verdict.GRANT);
        AuthorizationChecker platform = mock(AuthorizationChecker.class);
        when(platform.check(any(), anyString(), anyString()))
                .thenReturn(new AuthorizationChecker.AuthorizeResult(true, 7));

        AiResourceWriteService service = new AiResourceWriteService(jdbc, resourceMapper, aclMapper, epochMapper, txOps);
        service.configureExecution(guard, resources, platform);
        service.setHighRiskEnabled(true);
        return service;
    }
}
