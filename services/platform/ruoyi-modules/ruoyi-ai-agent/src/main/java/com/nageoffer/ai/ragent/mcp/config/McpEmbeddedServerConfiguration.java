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

import com.nageoffer.ai.ragent.mcp.executor.common.CurrentDateMcpExecutor;
import com.nageoffer.ai.ragent.mcp.executor.common.WeatherMcpExecutor;
import com.nageoffer.ai.ragent.mcp.executor.common.YouComSearchMcpExecutor;
import com.nageoffer.ai.ragent.mcp.executor.enterprise.AssetMcpExecutor;
import com.nageoffer.ai.ragent.mcp.executor.enterprise.AssetRenewalMcpExecutor;
import com.nageoffer.ai.ragent.mcp.executor.enterprise.LeaveApplyMcpExecutor;
import com.nageoffer.ai.ragent.mcp.executor.enterprise.LeaveMcpExecutor;
import com.nageoffer.ai.ragent.mcp.executor.enterprise.MeetingRoomMcpExecutor;
import com.nageoffer.ai.ragent.mcp.executor.enterprise.SalesMcpExecutor;
import com.nageoffer.ai.ragent.mcp.executor.enterprise.TicketMcpExecutor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * WP-041 / K1：services/ai/mcp-server 非 bit 执行器（13 文件）迁入平台后的内嵌装配入口。
 *
 * <p><b>为什么用 {@code @Import} 而不是 {@code @Bean} 显式 {@code new}。</b>
 * 内嵌装配的既有惯例（{@code AiEmbeddedAgentEngineConfiguration} 等）是 {@code @Bean}
 * 显式 {@code new}，那适合"一个类就是一个 bean"的组件。本组的执行器不同：
 * 它们是 {@code @Component} 且<b>靠自己的 {@code @Bean} 方法</b>向容器提供
 * {@code McpServerFeatures.SyncToolSpecification}（{@code McpServerConfig} 再把
 * 全部 toolSpec 收进 {@code McpSyncServer}）。{@code @Bean new XxxExecutor()} 只注册
 * 实例、<b>不会</b>触发该实例类上的 {@code @Bean} 方法 —— 工具会"装配了执行器却一个
 * 都没注册"。{@code @Import} 把这些类作为（lite）配置类处理，{@code @Bean} 方法才会
 * 真正生效。这也是判据 {@code McpEmbeddedServerConfigurationTest} 直接断言
 * toolSpec 清单而不是断言 executor 实例存在的原因：实例存在 ≠ 工具注册。
 *
 * <p><b>两级门控，默认关闭。</b>外层 {@code ai.integration.enabled=true} 与内嵌装配家族
 * 同一总门；内层 {@code ragent.mcp.server.enabled=true} 是本服务面的独立开关，<b>默认关</b>
 * —— K1 只要求"迁入可装配"，不改变当前 embedded 部署的启动行为（含不新增
 * {@code /mcp} servlet 映射）。上线时机由部署方显式打开。
 *
 * <p><b>迁入范围（K1 裁决，T4 实测口径）。</b>仅非 bit 执行器与 非 bit config：
 * 装配后无条件注册 <b>10</b> 个工具（{@code current_date}/{@code weather_query} +
 * {@code asset_query}/{@code asset_renewal_submit}/{@code leave_submit}/{@code leave_query}/
 * {@code meeting_room_query}/{@code meeting_room_book}/{@code ticket_query}/{@code sales_query}），
 * 外加条件注册的 {@code youcom_search}（仅当环境变量 {@code YDC_API_KEY} 存在，
 * {@code YouComSearchMcpExecutor} 类上的 {@code @ConditionalOnProperty}）。共 11 个 toolSpec。
 * bit 电商执行器（17 文件，硬依赖电商 dao，dao import 实测 51 处）、电商 dao（27）、
 * bit config（3，含第二 DataSource 与启动期建表）与独立启动类按 K1 留在 services/ai 原地。
 * 平台内不因此新增任何 DataSource / TransactionManager / SqlSessionFactory /
 * 启动期建库建表 —— 由 {@code McpPersistenceSingularityTest} 作模块级回归门（M7 同形）。
 *
 * <p><b>注册位置。</b>本类经
 * {@code ruoyi-ai-web/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 显式登记（与 {@code RocketMQOutboxTransportAutoConfiguration} 等位于非 org.ruoyi 包的
 * 自动配置同款）；platform 不做根包扫描，登记缺位 = 本类从来不是 bean。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class McpEmbeddedServerConfiguration {

    /**
     * MCP 服务端工具面：一个 {@code McpServerConfig}（transport + /mcp servlet + 同步 server）
     * 加十个执行器。执行器类上的 {@code @ConditionalOnProperty}（youcom）在 {@code @Import}
     * 处理时照常评估，条件不满足时只是少一个 toolSpec，不影响其余装配。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "ragent.mcp.server.enabled", havingValue = "true")
    @Import({
            McpServerConfig.class,
            CurrentDateMcpExecutor.class,
            WeatherMcpExecutor.class,
            YouComSearchMcpExecutor.class,
            AssetMcpExecutor.class,
            AssetRenewalMcpExecutor.class,
            LeaveApplyMcpExecutor.class,
            LeaveMcpExecutor.class,
            MeetingRoomMcpExecutor.class,
            SalesMcpExecutor.class,
            TicketMcpExecutor.class
    })
    static class McpServerEnabled {
    }
}
