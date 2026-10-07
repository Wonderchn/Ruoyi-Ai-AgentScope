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

package org.ruoyi.aiidentity;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.ruoyi.ai.api.AiExecutionFacts;
import org.ruoyi.ai.api.authz.AiPermitPort;
import org.ruoyi.aiintegration.identity.CurrentPrincipalResolver;
import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * E3/C2：本地身份与许可端口（{@link LocalAiIdentityPort}/{@link LocalAiPermitPort}）
 * 的装配与判定行为（轻量容器，不连 DB/Redis）。
 *
 * <p>覆盖：默认不装配（ai.integration.enabled 默认关）；开关打开后两个端口装配齐全；
 * 身份事实按网关同源口径构造（成员 → 身份 → acl epoch，缺任一即 empty）；
 * 许可判定链逐负例保留（未知动作、缺权限、版本落后、屏障关闭、成员失效），
 * 与原平台 HTTP 端点语义一致。
 */
@Tag("dev")
class E3LocalAiPortsTest {

    private static final String TENANT = "T1";
    private static final String USER = "2101";
    private static final String MEMBER = "platform:T1:2101";

    @Configuration
    @Import({LocalAiIdentityPort.class, LocalAiPermitPort.class})
    static class PortsConfig {

        @Bean
        CurrentPrincipalResolver currentPrincipalResolver() {
            return mock(CurrentPrincipalResolver.class);
        }

        @Bean
        PlatformIdentitySource platformIdentitySource() {
            return mock(PlatformIdentitySource.class);
        }

        @Bean
        JdbcTemplate permitJdbc() {
            return mock(JdbcTemplate.class);
        }

        @Bean
        TransactionTemplate permitTransactions() {
            TransactionTemplate template = mock(TransactionTemplate.class);
            when(template.execute(any())).thenAnswer(invocation ->
                    ((TransactionCallback<?>) invocation.getArgument(0))
                            .doInTransaction(mock(TransactionStatus.class)));
            return template;
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PortsConfig.class);

    // ------------------------------------------------------------------
    // 装配条件
    // ------------------------------------------------------------------

    @Test
    void portsAbsentByDefault() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(LocalAiIdentityPort.class);
            assertThat(context).doesNotHaveBean(LocalAiPermitPort.class);
        });
    }

    @Test
    void portsAssembledWhenIntegrationEnabled() {
        runner.withPropertyValues("ai.integration.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(LocalAiIdentityPort.class);
            assertThat(context).hasSingleBean(LocalAiPermitPort.class);
        });
    }

    // ------------------------------------------------------------------
    // 身份端口
    // ------------------------------------------------------------------

    @Test
    void identityFactsBuiltFromMemberAndIdentityAndAclEpoch() {
        runner.withPropertyValues("ai.integration.enabled=true").run(context -> {
            CurrentPrincipalResolver resolver = context.getBean(CurrentPrincipalResolver.class);
            PlatformIdentitySource identitySource = context.getBean(PlatformIdentitySource.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            when(resolver.resolveCurrentMember())
                    .thenReturn(Optional.of(new CurrentPrincipalResolver.CurrentMember(TENANT, USER, MEMBER)));
            when(identitySource.membership(TENANT, USER, MEMBER)).thenReturn(new PlatformIdentitySource
                    .PlatformIdentity(TENANT, USER, MEMBER, true, Set.of("ai:run:submit"), 3));
            stubAclEpoch(jdbc, TENANT, 7);

            Optional<AiExecutionFacts> facts = context.getBean(LocalAiIdentityPort.class).currentFacts();
            assertThat(facts).isPresent();
            assertThat(facts.get().tenantId()).isEqualTo(TENANT);
            assertThat(facts.get().userId()).isEqualTo(USER);
            assertThat(facts.get().membershipId()).isEqualTo(MEMBER);
            assertThat(facts.get().policyVersion()).isEqualTo(3);
            assertThat(facts.get().aclVersion()).isEqualTo(7);
            assertThat(facts.get().hasScope("ai:run:submit")).isTrue();
        });
    }

    @Test
    void identityFactsEmptyWhenAclEpochMissing() {
        runner.withPropertyValues("ai.integration.enabled=true").run(context -> {
            CurrentPrincipalResolver resolver = context.getBean(CurrentPrincipalResolver.class);
            PlatformIdentitySource identitySource = context.getBean(PlatformIdentitySource.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            when(resolver.resolveCurrentMember())
                    .thenReturn(Optional.of(new CurrentPrincipalResolver.CurrentMember(TENANT, USER, MEMBER)));
            when(identitySource.membership(TENANT, USER, MEMBER)).thenReturn(new PlatformIdentitySource
                    .PlatformIdentity(TENANT, USER, MEMBER, true, Set.of("ai:run:submit"), 3));
            when(jdbc.query(contains("ai_acl_epoch"), ArgumentMatchers.<RowMapper<Integer>>any(), eq(TENANT)))
                    .thenReturn(List.of());

            // 契约：取不到权威 acl 版本即 empty，不默认 0/1
            assertThat(context.getBean(LocalAiIdentityPort.class).currentFacts()).isEmpty();
        });
    }

    @Test
    void identityFactsEmptyWhenMembershipDisabled() {
        runner.withPropertyValues("ai.integration.enabled=true").run(context -> {
            CurrentPrincipalResolver resolver = context.getBean(CurrentPrincipalResolver.class);
            PlatformIdentitySource identitySource = context.getBean(PlatformIdentitySource.class);
            when(resolver.resolveCurrentMember())
                    .thenReturn(Optional.of(new CurrentPrincipalResolver.CurrentMember(TENANT, USER, MEMBER)));
            when(identitySource.membership(TENANT, USER, MEMBER)).thenReturn(new PlatformIdentitySource
                    .PlatformIdentity(TENANT, USER, MEMBER, false, Set.of("ai:run:submit"), 3));

            assertThat(context.getBean(LocalAiIdentityPort.class).currentFacts()).isEmpty();
        });
    }

    @Test
    void identityFactsEmptyWhenNotLoggedIn() {
        runner.withPropertyValues("ai.integration.enabled=true").run(context -> {
            CurrentPrincipalResolver resolver = context.getBean(CurrentPrincipalResolver.class);
            when(resolver.resolveCurrentMember()).thenReturn(Optional.empty());

            assertThat(context.getBean(LocalAiIdentityPort.class).currentFacts()).isEmpty();
        });
    }

    // ------------------------------------------------------------------
    // 许可端口
    // ------------------------------------------------------------------

    @Test
    void permitAcquiredForKnownActionWithScope() {
        runner.withPropertyValues("ai.integration.enabled=true").run(context -> {
            PlatformIdentitySource identitySource = context.getBean(PlatformIdentitySource.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            stubPolicyVersion(jdbc, TENANT, 3);
            stubBarriers(jdbc, TENANT, List.of());
            when(identitySource.membership(TENANT, USER, MEMBER)).thenReturn(new PlatformIdentitySource
                    .PlatformIdentity(TENANT, USER, MEMBER, true, Set.of("ai:run:submit"), 3));
            when(jdbc.update(contains("INSERT INTO sys_ai_execution_permit"), any(Object[].class)))
                    .thenReturn(1);

            AiExecutionFacts facts = facts(3, 7, "ai:run:submit");
            Optional<AiPermitPort.Permit> permit =
                    context.getBean(LocalAiPermitPort.class).acquire(facts, "run.submit");
            assertThat(permit).isPresent();
            assertThat(permit.get().tenantId()).isEqualTo(TENANT);
            assertThat(permit.get().memberId()).isEqualTo(MEMBER);
            assertThat(permit.get().lease()).isEqualTo(LocalAiPermitPort.LEASE);
            // 平台层许可不携带运行时 fence（由运行时层 ai_acl_epoch 产生）
            assertThat(permit.get().fence()).isZero();
        });
    }

    @Test
    void permitRejectedForUnknownAction() {
        runner.withPropertyValues("ai.integration.enabled=true").run(context -> {
            AiExecutionFacts facts = facts(3, 7, "ai:run:submit");
            assertThat(context.getBean(LocalAiPermitPort.class).acquire(facts, "nonsense.action"))
                    .isEmpty();
        });
    }

    @Test
    void permitRejectedWithoutScope() {
        runner.withPropertyValues("ai.integration.enabled=true").run(context -> {
            AiExecutionFacts facts = facts(3, 7, "ai:kb:list");
            assertThat(context.getBean(LocalAiPermitPort.class).acquire(facts, "run.submit"))
                    .isEmpty();
        });
    }

    @Test
    void permitRejectedWhenPolicyVersionStale() {
        runner.withPropertyValues("ai.integration.enabled=true").run(context -> {
            PlatformIdentitySource identitySource = context.getBean(PlatformIdentitySource.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            stubPolicyVersion(jdbc, TENANT, 4);
            stubBarriers(jdbc, TENANT, List.of());
            when(identitySource.membership(TENANT, USER, MEMBER)).thenReturn(new PlatformIdentitySource
                    .PlatformIdentity(TENANT, USER, MEMBER, true, Set.of("ai:run:submit"), 3));

            assertThat(context.getBean(LocalAiPermitPort.class).acquire(facts(3, 7, "ai:run:submit"), "run.submit"))
                    .isEmpty();
        });
    }

    @Test
    void permitRejectedWhenBarrierClosed() {
        runner.withPropertyValues("ai.integration.enabled=true").run(context -> {
            PlatformIdentitySource identitySource = context.getBean(PlatformIdentitySource.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            stubPolicyVersion(jdbc, TENANT, 3);
            stubBarriers(jdbc, TENANT, List.of("CLOSED"));
            when(identitySource.membership(TENANT, USER, MEMBER)).thenReturn(new PlatformIdentitySource
                    .PlatformIdentity(TENANT, USER, MEMBER, true, Set.of("ai:run:submit"), 3));

            assertThat(context.getBean(LocalAiPermitPort.class).acquire(facts(3, 7, "ai:run:submit"), "run.submit"))
                    .isEmpty();
        });
    }

    @Test
    void barrierStateOpenWhenNoRow() {
        runner.withPropertyValues("ai.integration.enabled=true").run(context -> {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            stubBarriers(jdbc, TENANT, List.of());
            assertThat(context.getBean(LocalAiPermitPort.class).barrierState(TENANT))
                    .isEqualTo(AiPermitPort.BarrierState.OPEN);
        });
    }

    @Test
    void barrierStateUnknownOnCorruptValue() {
        runner.withPropertyValues("ai.integration.enabled=true").run(context -> {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            stubBarriers(jdbc, TENANT, List.of("WEIRD"));
            assertThat(context.getBean(LocalAiPermitPort.class).barrierState(TENANT))
                    .isEqualTo(AiPermitPort.BarrierState.UNKNOWN);
        });
    }

    @Test
    void stillAuthorizedFalseWhenUnavailable() {
        runner.withPropertyValues("ai.integration.enabled=true").run(context -> {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Integer>>any(), any(Object[].class)))
                    .thenThrow(new IllegalStateException("db down"));

            // 状态不可得按拒绝处理，不降级放行
            assertThat(context.getBean(LocalAiPermitPort.class)
                    .stillAuthorized(facts(3, 7, "ai:run:submit"))).isFalse();
        });
    }

    @Test
    void stillAuthorizedFalseWhenVersionBehind() {
        runner.withPropertyValues("ai.integration.enabled=true").run(context -> {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            stubPolicyVersion(jdbc, TENANT, 4);
            stubBarriers(jdbc, TENANT, List.of());

            assertThat(context.getBean(LocalAiPermitPort.class)
                    .stillAuthorized(facts(3, 7, "ai:run:submit"))).isFalse();
        });
    }

    @Test
    void stillAuthorizedTrueWhenAllFactsHold() {
        runner.withPropertyValues("ai.integration.enabled=true").run(context -> {
            PlatformIdentitySource identitySource = context.getBean(PlatformIdentitySource.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            stubPolicyVersion(jdbc, TENANT, 3);
            stubBarriers(jdbc, TENANT, List.of());
            when(identitySource.membership(TENANT, USER, MEMBER)).thenReturn(new PlatformIdentitySource
                    .PlatformIdentity(TENANT, USER, MEMBER, true, Set.of("ai:run:submit"), 3));

            assertThat(context.getBean(LocalAiPermitPort.class)
                    .stillAuthorized(facts(3, 7, "ai:run:submit"))).isTrue();
        });
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static AiExecutionFacts facts(int policyVersion, int aclVersion, String scope) {
        return new AiExecutionFacts(TENANT, USER, MEMBER, policyVersion, aclVersion, Set.of(scope));
    }

    private static void stubAclEpoch(JdbcTemplate jdbc, String tenantId, int version) {
        when(jdbc.query(contains("ai_acl_epoch"), ArgumentMatchers.<RowMapper<Integer>>any(), eq(tenantId)))
                .thenReturn(List.of(version));
    }

    private static void stubPolicyVersion(JdbcTemplate jdbc, String tenantId, int version) {
        when(jdbc.query(contains("sys_ai_policy_revision"), ArgumentMatchers.<RowMapper<Integer>>any(), eq(tenantId)))
                .thenReturn(List.of(version));
    }

    private static void stubBarriers(JdbcTemplate jdbc, String tenantId, List<String> statuses) {
        when(jdbc.query(contains("sys_ai_tenant_barrier"), ArgumentMatchers.<RowMapper<String>>any(), eq(tenantId)))
                .thenReturn(statuses);
    }
}
