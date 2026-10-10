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

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP 服务端工具面的装配锚点判据（WP-041 / K1 迁入回归门）。
 *
 * <p><b>锚点自证非恒真。</b>工具清单不是"≥ 若干"而是<b>精确集合</b>：无条件工具
 * 恰好是下面这 10 个名字，多一个、少一个、改名都直接红 —— 未来任何人往清单里
 * 加工具，必须同步改这里的精确断言，判据与实现不许静默漂移。
 * 同时条件工具（youcom_search）用"注入 {@code YDC_API_KEY} 后数量 10 → 11 且点名出现"
 * 的前后增量证明条件注册路径真的在评估，而不是从来没走到。
 *
 * <p><b>为什么断言 toolSpec 而不断言 executor 实例。</b>执行器实例存在
 * 不代表其 {@code @Bean} 方法被处理过（{@code @Bean new} 只造实例不解析方法）；
 * {@code McpSyncServer} 收到的 toolSpec 数量才是"工具真的注册了"的证据。
 * {@code McpServerConfig.requireReadOnlyHint} 在装配期就会把漏声明读/写的工具
 * 点名炸掉，因此"上下文能起来 + 清单精确匹配"同时证明了校验链活着。
 *
 * <p><b>R12 卡1 增补的装配锚。</b>{@code /mcp} 鉴权过滤器（{@link McpAuthFilter}）
 * 与 servlet 同门控：禁用态 servlet 与过滤器一起缺席（负向两测），启用态过滤器
 * 恰好挂在 {@code /mcp} + {@code /mcp/*} 且只接管 {@code REQUEST} 分派。
 *
 * <p><b>F12-A1 增补。</b>逐工具授权过滤器（{@link McpToolAuthzFilter}）与鉴权过滤器
 * 同门控、同 URL 模式、只接管 {@code REQUEST}，且顺序必须在鉴权过滤器<b>之后</b>
 * （依赖它写入的认证交接属性）；两个过滤器恰好各 1 个注册，不允许多挂/少挂。
 */
@Tag("dev")
class McpEmbeddedServerConfigurationTest {

    /** 无条件注册的 10 个工具（common 2 + enterprise 8；MeetingRoom 一个执行器出两个工具）。 */
    private static final List<String> UNCONDITIONAL_TOOLS = List.of(
            "current_date",
            "weather_query",
            "asset_query",
            "asset_renewal_submit",
            "leave_submit",
            "leave_query",
            "meeting_room_query",
            "meeting_room_book",
            "ticket_query",
            "sales_query");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(McpEmbeddedServerConfiguration.class);

    @Test
    @DisplayName("默认关闭：内层开关缺位时不装配任何 MCP 服务端 bean，不新增 /mcp 映射与 /mcp 鉴权过滤器")
    void disabledByDefaultAndRegistersNothing() {
        runner.withPropertyValues("ai.integration.enabled=true").run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context).doesNotHaveBean(McpSyncServer.class);
            assertThat(context).doesNotHaveBean(ServletRegistrationBean.class);
            assertThat(context).as("R12 卡1：鉴权过滤器与 servlet 同门控，禁用态必须一起缺席")
                    .doesNotHaveBean(FilterRegistrationBean.class);
            assertThat(context.getBeansOfType(McpServerFeatures.SyncToolSpecification.class))
                    .as("开关未开时连条件工具也不允许注册").isEmpty();
        });
    }

    @Test
    @DisplayName("总门关闭时内层开着也不装配：外层 ai.integration.enabled 缺位即全关")
    void outerGateClosesEverythingEvenIfInnerSwitchIsOn() {
        runner.withPropertyValues("ragent.mcp.server.enabled=true").run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context).doesNotHaveBean(McpSyncServer.class);
            assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
            assertThat(context.getBeansOfType(McpServerFeatures.SyncToolSpecification.class)).isEmpty();
        });
    }

    @Test
    @DisplayName("开启后：server/transport/servlet + 两个过滤器（认证/授权）各恰好 1，工具清单精确等于 10 个且全部声明 readOnlyHint")
    void enabledContextRegistersExactlyTheExpectedToolSet() {
        runner.withPropertyValues("ai.integration.enabled=true", "ragent.mcp.server.enabled=true")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context).hasSingleBean(McpSyncServer.class);
                    assertThat(context).hasSingleBean(HttpServletStreamableServerTransportProvider.class);

                    ServletRegistrationBean<?> servlet =
                            context.getBean(ServletRegistrationBean.class);
                    assertThat(servlet.getUrlMappings())
                            .as("MCP 端点映射必须恰好是 /mcp（与旧服务一致）")
                            .containsExactly("/mcp");

                    var filterRegistrations = context.getBeansOfType(FilterRegistrationBean.class);
                    assertThat(filterRegistrations).as("R12 卡1 + F12-A1：认证与授权过滤器各恰好 1")
                            .hasSize(2);

                    FilterRegistrationBean<?> authFilter = filterOfType(filterRegistrations, McpAuthFilter.class);
                    assertThat(authFilter).as("R12 卡1：/mcp 鉴权过滤器必须装配").isNotNull();
                    assertThat(authFilter.getUrlPatterns())
                            .as("鉴权过滤器必须恰好覆盖 /mcp 与 /mcp/*")
                            .containsExactlyInAnyOrder("/mcp", "/mcp/*");
                    assertThat(authFilter.determineDispatcherTypes())
                            .as("只接管 REQUEST 分派（streamable-http 的异步/SSE 收尾不被打断）")
                            .containsExactly(DispatcherType.REQUEST);

                    FilterRegistrationBean<?> authzFilter = filterOfType(filterRegistrations, McpToolAuthzFilter.class);
                    assertThat(authzFilter).as("F12-A1：/mcp 逐工具授权过滤器必须装配").isNotNull();
                    assertThat(authzFilter.getUrlPatterns())
                            .as("授权过滤器与鉴权过滤器同 URL 模式")
                            .containsExactlyInAnyOrder("/mcp", "/mcp/*");
                    assertThat(authzFilter.determineDispatcherTypes())
                            .as("只接管 REQUEST 分派")
                            .containsExactly(DispatcherType.REQUEST);
                    assertThat(authzFilter.getOrder())
                            .as("授权过滤器必须排在鉴权过滤器之后（依赖 ATTR_AUTH_MODE 交接）")
                            .isGreaterThan(authFilter.getOrder());

                    var toolSpecs = context.getBeansOfType(McpServerFeatures.SyncToolSpecification.class);
                    assertThat(toolSpecs).as("无条件工具必须是精确的 10 个").hasSize(10);
                    assertThat(toolSpecs.values())
                            .extracting(spec -> spec.tool().name())
                            .containsExactlyInAnyOrderElementsOf(UNCONDITIONAL_TOOLS);
                    assertThat(toolSpecs.values())
                            .as("每个工具都必须声明 annotations（requireReadOnlyHint 的运行时证据）")
                            .allSatisfy(spec -> {
                                assertThat(spec.tool().annotations()).isNotNull();
                                assertThat(spec.tool().annotations().readOnlyHint()).isNotNull();
                            });
                });
    }

    /** 按过滤器实例类型从注册集合里取恰好一个；多于/少于一个直接断言失败。 */
    private static FilterRegistrationBean<?> filterOfType(
            java.util.Map<String, FilterRegistrationBean> registrations, Class<?> filterType) {
        return registrations.values().stream()
                .filter(registration -> filterType.isInstance(registration.getFilter()))
                .reduce((first, second) -> {
                    throw new AssertionError("过滤器类型 " + filterType + " 装配了多于 1 个");
                })
                .orElse(null);
    }

    @Test
    @DisplayName("条件注册：注入 YDC_API_KEY 后清单 10 → 11 且 youcom_search 点名出现")
    void youcomToolJoinsOnlyWhenApiKeyPropertyPresent() {
        runner.withPropertyValues(
                        "ai.integration.enabled=true",
                        "ragent.mcp.server.enabled=true",
                        "YDC_API_KEY=test-key-for-assembly-evidence")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    var toolSpecs = context.getBeansOfType(McpServerFeatures.SyncToolSpecification.class);
                    assertThat(toolSpecs).as("条件工具加入后必须是 11 个").hasSize(11);
                    assertThat(toolSpecs.values())
                            .extracting(spec -> spec.tool().name())
                            .contains(UNCONDITIONAL_TOOLS.toArray(new String[0]))
                            .contains("youcom_search");
                });
    }
}
