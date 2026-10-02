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
import org.mockito.MockedStatic;
import org.ruoyi.aiintegration.authorization.OrganizationMatchController;
import org.ruoyi.aiintegration.authorization.ProductionAuthorizationController;
import org.ruoyi.aiintegration.identity.CurrentPrincipalResolver;
import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.system.aiidentity.CurrentAiMembershipService;
import org.ruoyi.system.mapper.SysDeptMapper;
import org.ruoyi.system.mapper.SysTenantMapper;
import org.ruoyi.system.mapper.SysUserMapper;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * U04/P1.2b：真实身份提供方的装配与解析行为（轻量容器，不连 DB/Redis）。
 *
 * <p>覆盖：默认 profile 下生产授权端点与身份源<b>不装配</b>（ai.integration.enabled
 * 默认关）；开关打开时装配齐全（正向对照，证明条件探测有效）；注入 mock 的
 * {@link CurrentAiMembershipService} 后，{@link RuoYiCurrentPrincipalResolver}
 * 输出 canonical membership（platform:&lt;tenantId&gt;:&lt;userId&gt;），
 * 跨租户用户返回 empty。
 */
@Tag("dev")
class P1IdentityAssemblyTest {

    @Configuration
    @Import({ProductionAuthorizationController.class, OrganizationMatchController.class,
        RuoYiPlatformIdentitySource.class, RuoYiCurrentPrincipalResolver.class})
    static class AssemblyConfig {
    }

    @Configuration
    static class MockFactsConfig {

        @Bean
        CurrentAiMembershipService currentAiMembershipService() {
            return mock(CurrentAiMembershipService.class);
        }

        @Bean
        SysTenantMapper sysTenantMapper() {
            return mock(SysTenantMapper.class);
        }

        @Bean
        SysUserMapper sysUserMapper() {
            return mock(SysUserMapper.class);
        }

        @Bean
        SysDeptMapper sysDeptMapper() {
            return mock(SysDeptMapper.class);
        }
    }

    private ApplicationContextRunner assemblyRunner() {
        return new ApplicationContextRunner().withUserConfiguration(AssemblyConfig.class);
    }

    @Test
    void defaultProfileDoesNotAssembleProductionAuthorizationSurface() {
        assemblyRunner()
            // resolver 无条件装配，其成员事实依赖以 mock 提供（本测试聚焦生产装配开关）
            .withUserConfiguration(MockFactsConfig.class)
            .run(context -> {
                assertThat(context).doesNotHaveBean(ProductionAuthorizationController.class);
                assertThat(context).doesNotHaveBean(OrganizationMatchController.class);
                assertThat(context).doesNotHaveBean(PlatformIdentitySource.class);
                // 解析 SPI 本身与生产开关无关，默认装配
                assertThat(context).hasSingleBean(RuoYiCurrentPrincipalResolver.class);
            });
    }

    @Test
    void enablingTheSwitchAssemblesTheProductionAuthorizationSurface() {
        assemblyRunner()
            .withUserConfiguration(MockFactsConfig.class)
            .withPropertyValues("ai.integration.enabled=true")
            .run(context -> {
                assertThat(context).hasSingleBean(ProductionAuthorizationController.class);
                assertThat(context).hasSingleBean(OrganizationMatchController.class);
                assertThat(context).hasSingleBean(PlatformIdentitySource.class);
                assertThat(context).hasSingleBean(RuoYiCurrentPrincipalResolver.class);
            });
    }

    @Test
    void resolverOutputsCanonicalMembershipForTenantMember() {
        assemblyRunner().withUserConfiguration(MockFactsConfig.class).run(context -> {
            CurrentAiMembershipService facts = context.getBean(CurrentAiMembershipService.class);
            when(facts.describe("T1", 42L)).thenReturn(new CurrentAiMembershipService.CurrentAiMembership(
                "T1", 42L, true, true, Set.of(), Set.of(), true, Set.of(), 3));

            RuoYiCurrentPrincipalResolver resolver = context.getBean(RuoYiCurrentPrincipalResolver.class);
            try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
                login.when(LoginHelper::getUserId).thenReturn(42L);
                login.when(LoginHelper::getTenantId).thenReturn("T1");

                Optional<CurrentPrincipalResolver.CurrentMember> resolved = resolver.resolveCurrentMember();
                assertThat(resolved).isPresent();
                assertThat(resolved.get().tenantId()).isEqualTo("T1");
                assertThat(resolved.get().userId()).isEqualTo("42");
                assertThat(resolved.get().membershipId()).isEqualTo("platform:T1:42");
            }
        });
    }

    @Test
    void resolverReturnsEmptyForUserNotBelongingToTheTenant() {
        assemblyRunner().withUserConfiguration(MockFactsConfig.class).run(context -> {
            CurrentAiMembershipService facts = context.getBean(CurrentAiMembershipService.class);
            when(facts.describe("T1", 43L)).thenReturn(null);

            RuoYiCurrentPrincipalResolver resolver = context.getBean(RuoYiCurrentPrincipalResolver.class);
            try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
                login.when(LoginHelper::getUserId).thenReturn(43L);
                login.when(LoginHelper::getTenantId).thenReturn("T1");
                assertThat(resolver.resolveCurrentMember()).isEmpty();
            }
        });
    }

    @Test
    void resolverReturnsEmptyWithoutLoginOrTenantContext() {
        assemblyRunner().withUserConfiguration(MockFactsConfig.class).run(context -> {
            RuoYiCurrentPrincipalResolver resolver = context.getBean(RuoYiCurrentPrincipalResolver.class);
            try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
                // 未登录：userId 为 null；缺租户上下文：tenantId 为空
                login.when(LoginHelper::getUserId).thenReturn(null);
                assertThat(resolver.resolveCurrentMember()).isEmpty();

                login.when(LoginHelper::getUserId).thenReturn(42L);
                login.when(LoginHelper::getTenantId).thenReturn("");
                assertThat(resolver.resolveCurrentMember()).isEmpty();
            }
        });
    }

}
