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

package com.nageoffer.ai.ragent.agent.tool;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * F12-M2 运行期受控 MCP 连接器配置（{@code agent.mcp.runtime.*}）。
 *
 * <p>与 F12-M1 的 {@code agent.mcp.allow-list} 是两套东西：allow-list 决定"能不能去"，
 * 本组决定"连上以后保留多久、最多保留几条、单次连接最多等多久"。
 * 缺省值与 brief §3 一致；ship 配置只写这三项，不写任何地址/凭据。
 */
@Data
@ConfigurationProperties(prefix = "agent.mcp.runtime")
public class AgentMcpRuntimeProperties {

    /** 连接缓存 TTL；到期后下一次解析重新连接。默认 5 分钟。 */
    private Duration cacheTtl = Duration.ofMinutes(5);

    /** 单工具连接/初始化/工具发现的阻塞上限。默认 10 秒；不得无限等待。 */
    private Duration connectTimeout = Duration.ofSeconds(10);

    /** 进程内最多缓存的连接数；超出按 LRU 淘汰并关闭。默认 32。 */
    private int maxCachedClients = 32;
}
