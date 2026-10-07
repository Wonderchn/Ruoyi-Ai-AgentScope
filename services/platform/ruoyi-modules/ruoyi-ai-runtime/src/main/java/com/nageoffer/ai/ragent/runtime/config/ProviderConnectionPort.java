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

import java.net.URI;

/**
 * **连接引导**端口（C1.1 用途② / D16）。
 *
 * <p><b>为什么端点不可能来自 binding。</b>`ai_runtime_config_revision`（V15）**没有** endpoint/url 列，
 * 只有 {@code credential_ref}，且 V15:67 的冻结注释写明「密钥**引用/掩码**；**实际值由外部注入**」。
 * 也就是说：**数据库回答"用哪份配置"（provider/model/params/catalog），连接引导回答"那个提供方
 * 在哪里、用什么凭据"**。把默认端点写进代码或 yml 就等于在权威之外再造一个隐式默认值 ——
 * 因此本端口 **fail-closed 且无任何默认值**：查不到连接即拒绝，绝不"回落到一个已知端点"。
 *
 * <p><b>与 D02 的关系。</b>本端口**不**参与"选哪个模型"：它只在已经由
 * {@link RunConfigBindingPort} 定死 providerId 之后，把该 providerId 的传输地址与凭据取出来。
 * 因此它不构成第二权威。
 *
 * <p><b>实现（{@code ProviderConnections}）从 {@code p2.providers.connections.*} 读引导值</b>，
 * 该属性没有 shipped 默认值：未注入 ⇒ 任何真实提供方调用都以
 * {@link ConfigAuthorityUnavailable} 失败（响亮、可诊断），而不是静默打到一个公共端点。
 */
public interface ProviderConnectionPort {

    /**
     * 解析某个提供方的连接事实。
     *
     * @param providerId    来自 run 绑定（必填）
     * @param credentialRef 来自 run 绑定（可空；若引导侧也声明了引用，两侧不一致即拒绝）
     * @throws ConfigAuthorityUnavailable 查不到该 providerId 的连接、端点缺失或非法、引用不一致
     */
    ProviderConnection requireConnection(String providerId, String credentialRef);

    /**
     * 连接事实。{@code apiKey} 是**运行期注入的凭据**（不落库、不进日志）——
     * {@link #toString()} 因此刻意把密钥掩码，避免任何日志/异常消息把它带出去。
     */
    record ProviderConnection(String providerId, URI endpoint, String credentialRef, String apiKey) {

        /** 掩码形态（可安全进日志/异常）。 */
        public ProviderConnection masked() {
            return new ProviderConnection(providerId, endpoint, credentialRef, apiKey == null || apiKey.isBlank() ? "" : "***");
        }

        @Override
        public String toString() {
            return "ProviderConnection[providerId=" + providerId
                    + ", endpoint=" + endpoint
                    + ", credentialRef=" + credentialRef
                    + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "<empty>" : "***") + "]";
        }
    }
}
