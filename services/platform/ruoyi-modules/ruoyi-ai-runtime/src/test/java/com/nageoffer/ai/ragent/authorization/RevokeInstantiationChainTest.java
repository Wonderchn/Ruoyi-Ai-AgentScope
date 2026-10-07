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
import com.nageoffer.ai.ragent.authorization.dao.AiResourceMapper.AiResourceRow;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService.Verdict;
import com.nageoffer.ai.ragent.framework.security.StaleVersionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F23-N2（E2）：撤权**即时性**的组合链——"撤权 → 在途 permit 立即失效"。
 *
 * <p><b>为什么单测面已有还要这条。</b>两半各自被钉着：
 * {@code P1PersistentAclTest} 钉"revoke 后 av 必须同事务 bump"，
 * {@code P1RevocationRaceTest} 钉"aclVersion 失配拒绝登记"。但"**同一个在途 permit**
 * 在撤权之后做事实校验会被拒"这条链没人连过——撤权屏障（U10）的即时性正是这条链：
 * 撤权若不能立刻废掉在途 permit，"撤权"就只是"对未来的授权变严"。
 *
 * <p>判据（每条都能失败）：
 * <ol>
 *   <li>撤权前：同 av 的 permit 登记成功（对照——判据能失败的前提）；</li>
 *   <li>revoke：行删除 + epoch bump 发生（撤权不是改状态，是删行 + 版本前进）；</li>
 *   <li>撤权后：握旧 av 的同一 permit 走 acquire ⇒ {@code StaleVersionException} 且零 INSERT
 *       （在途 permit 的下一次事实校验即拒）。</li>
 * </ol>
 *
 * <p>裁定记录（lead，方案甲）：读路径接线判据（N1/N4 的 requireGrant 404）为单行转发、
 * 无独立行为面，降级为**实例窗口真机判据**；本类不含反射测试。
 */
@Tag("dev")
class RevokeInstantiationChainTest {

    private static final String TENANT = "T-REVOKE";
    private static final String MEMBER = "platform:T-REVOKE:2101";
    private static final String OWNER = "platform:T-REVOKE:7777";

    private final AiAclEpochMapper epochMapper = mock(AiAclEpochMapper.class);
    private final NamedParameterJdbcTemplate namedJdbc = mock(NamedParameterJdbcTemplate.class);
    private AiResourceWriteService writeService;
    private int epoch;

    @BeforeEach
    void setUp() {
        epoch = 3;
        when(epochMapper.findVersion(TENANT)).thenAnswer(inv -> Optional.of(epoch));
        when(epochMapper.bump(TENANT)).thenAnswer(inv -> Optional.of(++epoch));
        when(namedJdbc.update(anyString(), anyMap())).thenReturn(1);
        when(namedJdbc.query(contains("FOR UPDATE"), anyMap(), any(RowMapper.class)))
                .thenAnswer(inv -> List.of(epoch));
        when(namedJdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(0L);
        writeService = writeService();
        PrincipalContext.set(principal(Set.of("kb.acl.manage", "kb.read")));
    }

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    @Test
    @DisplayName("对照：撤权前同 av 的 permit 登记成功（判据能失败的前提）")
    void beforeRevokeTheSameAclVersionIsAccepted() {
        var handle = guard();
        var grant = handle.guard().acquire(new RevocationGuard.PermitRequest(
                TENANT, MEMBER, "kb.read", 7, epoch, "refshash", "op-before-revoke"));
        assertThat(grant.permitId()).isNotBlank();
        assertThat(grant.aclVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("revoke：ACL 行删除 + epoch 同事务 bump（撤权不是改状态，是删行 + 版本前进）")
    void revokeDeletesTheRuleAndBumpsTheEpoch() {
        writeService.revokeAclRule("kb-1",
                new AiResourceWriteService.AclGrant("member", MEMBER, "kb.read", null));

        verify(epochMapper).bump(TENANT);
        assertThat(epoch).as("撤权必须推进 epoch（在途 permit 过期的机制前提）").isEqualTo(4);
    }

    @Test
    @DisplayName("组合链：撤权后握旧 av 的在途 permit 下次 acquire ⇒ StaleVersion 拒绝且零 INSERT")
    void revokeImmediatelyInvalidatesAnInFlightPermit() {
        writeService.revokeAclRule("kb-1",
                new AiResourceWriteService.AclGrant("member", MEMBER, "kb.read", null));
        verify(epochMapper).bump(TENANT);
        assertThat(epoch).isEqualTo(4);

        // 同一在途 permit：撤权前登记（av=3），撤权后的下一次事实校验（acquire）
        var handle = guard();
        assertThatThrownBy(() -> handle.guard().acquire(new RevocationGuard.PermitRequest(
                        TENANT, MEMBER, "kb.read", 7, 3, "refshash", "op-after-revoke")))
                .as("撤权后握旧 av 的在途 permit 必须立即失效（StaleVersion）")
                .isInstanceOf(StaleVersionException.class);
        verify(handle.jdbc(), never()).update(contains("INSERT INTO ai_execution_permit"), any(Object[].class));

        // 推进到新 av（重新受理）后同一动作可登记：拒绝的只是"旧世界的 permit"
        var renewed = handle.guard().acquire(new RevocationGuard.PermitRequest(
                TENANT, MEMBER, "kb.read", 7, epoch, "refshash", "op-after-renew"));
        assertThat(renewed.permitId()).isNotBlank();
    }

    // ---------------------------------------------------------------- 装配辅助

    private record GuardHandle(DefaultRevocationGuard guard, org.springframework.jdbc.core.JdbcTemplate jdbc) {}

    /** 真 DefaultRevocationGuard + mock JDBC：epoch 行随 bump 推进（撤权即时性的机制本体）。 */
    private GuardHandle guard() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(jdbc.query(contains("ai_acl_epoch"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> List.of(epoch));
        when(jdbc.query(contains("ai_tenant_barrier"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        // acquire 的 permit INSERT 必须成功（不 stub 则 update 返回 0 ⇒ "permit 登记未确认"）。
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        var guard = new DefaultRevocationGuard(jdbc);
        return new GuardHandle(guard, jdbc);
    }

    private AiResourceWriteService writeService() {
        var resourceMapper = mock(AiResourceMapper.class);
        when(resourceMapper.findByPk(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new AiResourceRow(TENANT, "KB", "kb-1",
                        OWNER, null, null, null, "ACTIVE", 1L)));
        var aclMapper = mock(AiResourceAclMapper.class);
        // 操作者（2101）持 kb.acl.manage 授权行：否则 validatedRow 的 hasLiveManageGrant
        // 判 false ⇒ revoke 在写事务之前就被 404 拒绝（组合链根本走不到 bump）。
        when(aclMapper.findByResource(anyString(), anyString(), anyString())).thenReturn(List.of(
                new AiResourceAclMapper.AclRow("acl-mgr", TENANT, "KB", "kb-1",
                        "MEMBER", MEMBER, "kb.acl.manage", null, MEMBER)));
        when(aclMapper.listTenantRows(anyString())).thenReturn(List.of());
        doAnswer(inv -> 1).when(aclMapper).deleteRule(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());

        TransactionOperations txOps = mock(TransactionOperations.class);
        doAnswer(inv -> {
            ((Consumer<?>) inv.getArgument(0)).accept(null);
            return null;
        }).when(txOps).executeWithoutResult(any());
        doAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0))
                .doInTransaction(mock(TransactionStatus.class))).when(txOps).execute(any(TransactionCallback.class));

        var guard = mock(RevocationGuard.class);
        when(guard.enter(any(), anyString(), anyString()))
                .thenReturn(new RevocationGuard.Operation(guard, "permit", "op"));
        var resources = mock(com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService.class);
        when(resources.check(any(), anyString(), anyString())).thenReturn(Verdict.GRANT);
        var platform = mock(com.nageoffer.ai.ragent.framework.security.AuthorizationChecker.class);
        when(platform.check(any(), anyString(), anyString()))
                .thenReturn(new com.nageoffer.ai.ragent.framework.security.AuthorizationChecker.AuthorizeResult(true, 7));

        AiResourceWriteService service = new AiResourceWriteService(
                namedJdbc, resourceMapper, aclMapper, epochMapper, txOps);
        service.configureExecution(guard, resources, platform);
        service.setHighRiskEnabled(true);
        return service;
    }

    private static ExecutionPrincipal principal(Set<String> scopes) {
        return new ExecutionPrincipal(TENANT, "2101", MEMBER, 7, 3,
                scopes, "jti-revoke", "platform", 0, Long.MAX_VALUE);
    }
}
