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

package com.nageoffer.ai.ragent.runtime.config;

import com.nageoffer.ai.ragent.authorization.TenantBarrierReconciler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **类型唯一性**判据（F-3 / G-39 同族）。
 *
 * <p>`EngineModelAuthority` 有**两个**注册点，靠"显式补集"互斥：
 * <ul>
 *   <li>{@code AiEmbeddedAgentEngineConfiguration}（`ruoyi-ai-web`）：仅
 *       {@code ragent.engine.type=agent} 时激活；</li>
 *   <li>{@link RuntimeAuthorityConfiguration}（本模块）：仅
 *       {@code ragent.engine.type != agent} 时激活。</li>
 * </ul>
 * 本测试固定**本模块这一侧的**行为：run 组形态**恰好 1 个**实现；agent 引擎形态**0 个**
 * （留给另一个注册点）。两个断言方向相反 ⇒ 若补集条件被写反/被删掉，必有一条失败
 * （不是"加了断言"，而是**断言能响**）。
 *
 * <p><b>锚点</b>：两个用例都先断言"本配置确实加载了"（{@code RunConfigBindingPort} 命中），
 * 否则"0 个"可能只是"整个配置没装配"的空集合恒真。
 */
@Tag("dev")
class EngineModelAuthorityExclusivityTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RuntimeAuthorityConfiguration.class, JdbcFixture.class)
            .withPropertyValues("ai.integration.enabled=true");

    @Test
    void runGroupFormProvidesExactlyOneEngineModelAuthority() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RunConfigBindingPort.class);
            assertThat(context).hasSingleBean(ProviderConnectionPort.class);
            assertThat(context.getBeansOfType(EngineModelAuthority.class)).hasSize(1);
            assertThat(context.getBean(EngineModelAuthority.class)).isInstanceOf(PublishedModelAuthority.class);
            // G-55c 的锚点：生产形态下屏障对账组件**必须存在**。它刻意**没有** @ConditionalOnBean
            // （顺序依赖条件会让它在生产上静默缺失 ⇒ 写路径 finally 只能记 ERROR ⇒ (2) 白落）；
            // 只要本配置声明的 JDBC 基础设施在，它就必然注册 —— 本切片因此必须提供那套基础设施。
            assertThat(context).hasSingleBean(TenantBarrierReconciler.class);
        });
    }

    @Test
    void agentEngineFormLeavesTheSingleRegistrationPointToTheAgentConfiguration() {
        runner.withPropertyValues("ragent.engine.type=agent").run(context -> {
            assertThat(context).hasNotFailed();
            // 锚点：同一配置在本形态下仍然加载 ⇒ 下面的 0 不是"配置没装配"
            assertThat(context).hasSingleBean(RunConfigBindingPort.class);
            // 同上：本形态跳过的是 EngineModelAuthority（留给 agent 配置），不是 JDBC 依赖的 bean
            assertThat(context).hasSingleBean(TenantBarrierReconciler.class);
            assertThat(context.getBeansOfType(EngineModelAuthority.class)).isEmpty();
        });
    }

    @Test
    void authorityIsNotRegisteredWhenAiIntegrationIsDisabled() {
        runner.withPropertyValues("ai.integration.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(EngineModelAuthority.class)).isEmpty();
            assertThat(context.getBeansOfType(RunConfigBindingPort.class)).isEmpty();
        });
    }

    /**
     * 本切片加载的是 {@link RuntimeAuthorityConfiguration} 的**真实** bean 定义，
     * 因此必须提供该配置声明的**全部**基础设施依赖。**这是切片准备不足，不是生产条件写宽了**
     * —— 两者修法方向相反，这里选"补齐切片"，理由如下：
     * <ul>
     *   <li>{@code runConfigBindingPort} 需要 {@code JdbcTemplate}；</li>
     *   <li>{@code tenantBarrierReconciler}（G-55c）需要 {@code NamedParameterJdbcTemplate}
     *       + {@code PlatformTransactionManager}。</li>
     * </ul>
     *
     * <p><b>为什么生产形态下 {@code tenantBarrierReconciler} 必然存在（不是"应该会"）</b>：
     * ① 它没有 {@code @ConditionalOnBean} —— 本配置经 {@code AutoConfiguration.imports} 列举，
     * 顺序不确定，若用 {@code @ConditionalOnBean} 就可能"处理本配置时 JDBC 尚未注册 ⇒ 条件为假
     * ⇒ 生产上静默缺失"；
     * ② 普通注入参数不受顺序影响：**所有 bean 定义先注册、后实例化**；
     * ③ 只要生产上下文里存在这两个类型，它就能建出来 —— 而它们必然存在：
     * {@code AiResourceWriteService} 自身的构造就要求 {@code NamedParameterJdbcTemplate}
     * （`AiEmbeddedRagConfiguration` 传入），{@code RunAdmissionService} 要求
     * {@code PlatformTransactionManager}。⇒ **"写服务在" ⟹ "对账组件在"**。
     *
     * <p>只用不连接的 DataSource（本切片不查库）；{@code JdbcTemplate.afterPropertiesSet()}
     * 要求 dataSource 非空。
     */
    @Configuration(proxyBeanMethods = false)
    static class JdbcFixture {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource dataSource) {
            return new NamedParameterJdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
