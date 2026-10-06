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

package com.nageoffer.ai.ragent.mcp.config;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.TransactionManager;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * M7 同形回归门（模块级）：K1 迁入的 MCP 装配<b>不得</b>引入第二个持久化权威。
 *
 * <p>断言四件套（值语义与维护者 M7 相同）：{@code DataSource} / {@code TransactionManager} /
 * {@code SqlSessionFactory} 各<b>恰好 1</b>，且 {@code dynamicDataSource} = <b>0</b>。
 *
 * <p><b>形态与边界，不许误读。</b>本类在 ai-agent 模块内用
 * {@link ApplicationContextRunner} 拉起"持久化自动配置 + MCP 迁入装配 + 单一 mock
 * DataSource"的最小上下文，验证的是<b>迁入物自身</b>不会多注册数据源/事务管理器/
 * 会话工厂 —— mock DataSource 是夹具锚点（没有它，"恰好 1"是空集合恒真的假绿）。
 * 它<b>不是</b>内嵌全上下文（ruoyi-admin 全量装配）的复跑：那需要真实实例窗口，
 * 本轮标 {@code NOT_RUN}，由 T0 安排真实形态复跑。两层证据合起来才是 M7 的完整闭环。
 *
 * <p><b>计数器自检（变异对照，防"恒 1"假绿）。</b>专门有一个用例往上下文里放
 * <b>两个</b>夹具 DataSource，断言计数真的变成 2 —— 证明"恰好 1"的采集方式
 * 数的是真东西，而不是怎么数都返回常数的摆设（空集合恒真族事故的针对性反证）。
 */
@Tag("dev")
class McpPersistenceSingularityTest {

    /** M7 判据探测的动态数据源类型；不在 classpath 本身就是"未引入"的形态之一。 */
    private static final String DYNAMIC_DATA_SOURCE =
            "com.baomidou.dynamic.datasource.DynamicRoutingDataSource";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceTransactionManagerAutoConfiguration.class,
                    MybatisPlusAutoConfiguration.class))
            .withUserConfiguration(McpEmbeddedServerConfiguration.class, MockDataSourceConfig.class)
            .withPropertyValues("ai.integration.enabled=true", "ragent.mcp.server.enabled=true");

    @Test
    @DisplayName("M7 同形：MCP 装配加入后 dataSource/transactionManager/sqlSessionFactory 各恰好 1，dynamicDataSource=0")
    void migratedMcpAssemblyIntroducesNoSecondPersistenceAuthority() {
        runner.run(context -> {
            assertThat(context.getStartupFailure())
                    .as("上下文必须真的装配成功，否则下面的计数断言是对空上下文的空转")
                    .isNull();

            assertThat(context.getBeansOfType(DataSource.class))
                    .as("DataSource 必须恰好 1（夹具这一个，MCP 装配不得再加）")
                    .hasSize(1);
            assertThat(context.getBeansOfType(TransactionManager.class))
                    .as("TransactionManager 必须恰好 1")
                    .hasSize(1);
            assertThat(context.getBeansOfType(SqlSessionFactory.class))
                    .as("SqlSessionFactory 必须恰好 1")
                    .hasSize(1);
            assertNoDynamicDataSource(context);
        });
    }

    @Test
    @DisplayName("计数器自检：两个夹具 DataSource 时计数必须显示 2（判据能响，不是恒 1）")
    void dataSourceCounterActuallyCounts() {
        new ApplicationContextRunner()
                .withUserConfiguration(MockDataSourceConfig.class, SecondDataSourceConfig.class)
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context.getBeansOfType(DataSource.class))
                            .as("两个夹具都在场时计数必须是 2 —— 证明上面的『恰好 1』在数真东西")
                            .hasSize(2);
                });
    }

    /**
     * dynamicDataSource=0 的两种等价形态都断言：类型在 classpath 上时计数必须为 0
     * 且唯一 DataSource 不是它的实例；类型不在 classpath 上时"未引入"本身就是结果，
     * 用显式分支记录而不是让断言静默消失。
     */
    private static void assertNoDynamicDataSource(ApplicationContext context) {
        Class<?> dynamicType;
        try {
            dynamicType = Class.forName(DYNAMIC_DATA_SOURCE, false,
                    McpPersistenceSingularityTest.class.getClassLoader());
        } catch (ClassNotFoundException absent) {
            dynamicType = null;
        }
        if (dynamicType != null) {
            assertThat(context.getBeansOfType(dynamicType))
                    .as("dynamicDataSource 必须为 0")
                    .isEmpty();
            assertThat(context.getBean(DataSource.class))
                    .as("唯一的 DataSource 不得是动态数据源实例")
                    .isNotInstanceOf(dynamicType);
        } else {
            // dynamic-datasource 连类型都没进 test classpath：更强的"未引入"证据。
            // 显式记录该分支，防止将来引入后这条检查无声消失。
            assertThat(classForNameAbsent(DYNAMIC_DATA_SOURCE))
                    .as("DynamicRoutingDataSource 当前不在 ai-agent 测试 classpath；若此断言变红说明依赖面变化，需补真实计数断言")
                    .isTrue();
        }
    }

    private static boolean classForNameAbsent(String type) {
        try {
            Class.forName(type, false, McpPersistenceSingularityTest.class.getClassLoader());
            return false;
        } catch (ClassNotFoundException absent) {
            return true;
        }
    }

    /** 夹具：唯一的 DataSource（mock，不连库 —— 本判据只数 bean，不碰连接）。 */
    @Configuration(proxyBeanMethods = false)
    static class MockDataSourceConfig {

        @Bean
        DataSource dataSource() {
            return mock(DataSource.class);
        }
    }

    /** 变异夹具：第二个 DataSource，仅用于"计数器自检"用例证明判据会响。 */
    @Configuration(proxyBeanMethods = false)
    static class SecondDataSourceConfig {

        @Bean
        DataSource anotherDataSource() {
            return mock(DataSource.class);
        }
    }
}
