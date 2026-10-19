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

package com.nageoffer.ai.ragent.mcp.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 受控端点 allow-list（brief §2.4）。
 *
 * <p><b>fail-closed</b>：默认空清单 ⇒ 刷新与连接测试不做任何网络调用。
 * 真实窗口的批准目标由协调者在启动 env 注入
 * （例如 {@code AGENT_MCP_ALLOW_LIST_ENDPOINTS_0=scheme://host:port}），
 * 代码与 shipped 配置里<b>不写死任何地址/端口</b>。
 *
 * <p>条目只取 {@code scheme://host[:port]}；端口缺省按 scheme 补 80/443。
 */
@ConfigurationProperties(prefix = "agent.mcp.allow-list")
public class AgentMcpAllowListProperties {

    /**
     * 批准目标清单（例如 {@code http://127.0.0.1:18080}）；默认空 = 全部拒绝。
     */
    private List<String> endpoints = new ArrayList<>();

    public List<String> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(List<String> endpoints) {
        this.endpoints = endpoints == null ? new ArrayList<>() : new ArrayList<>(endpoints);
    }
}
