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

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link ProviderConnectionPort} 的连接引导实现：`p2.providers.connections.entries.<providerId>.{endpoint,api-key,credential-ref}`。
 *
 * <p><b>刻意没有 shipped 默认值，也没有"已知公共端点"兜底。</b>C1.5 判据 3 的同类要求是
 * "断 DB 时不得返回 YAML 成功路径"；这条的同构要求是"**连接引导缺失时不得打到某个猜测的端点**"。
 * 因此查不到即抛 {@link ConfigAuthorityUnavailable}，由调用方按失败处理；部署侧用
 * {@code P2_PROVIDERS_CONNECTIONS_ENTRIES_<PROVIDER>_ENDPOINT} / {@code ..._API_KEY} 这类环境变量注入
 * （D16 凭据外注入），而**不是**把它写进 shipped yml。
 *
 * <p><b>为什么 api-key 可以为空。</b>存在合法的无密钥提供方（例如本机 Ollama）。凭据是否必需
 * 由提供方调用路径裁决（`RealChatGateway` 对空密钥显式拒绝），连接引导只负责"**端点必须真实存在**"，
 * 不在这一层替提供方做密钥策略，避免把两件事混成一个开关。
 * endpoint 必须是完整调用地址（例如 /chat/completions），传输层不会追加路径。
 */
@ConfigurationProperties(prefix = "p2.providers.connections")
public class ProviderConnections implements ProviderConnectionPort {

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public Map<String, Entry> getEntries() {
        return entries;
    }

    /** 单个提供方的连接引导项。 */
    public static class Entry {
        private String endpoint;
        private String apiKey;
        private String credentialRef;

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getCredentialRef() {
            return credentialRef;
        }

        public void setCredentialRef(String credentialRef) {
            this.credentialRef = credentialRef;
        }
    }

    @Override
    public ProviderConnection requireConnection(String providerId, String credentialRef) {
        if (providerId == null || providerId.isBlank()) {
            throw new ConfigAuthorityUnavailable("providerId is required to resolve a provider connection");
        }
        Entry entry = entries.get(providerId);
        if (entry == null) {
            // 刻意不回落到"唯一配置项"或"默认端点"：多提供方下那等于把 A 的流量打到 B。
            throw new ConfigAuthorityUnavailable("no connection bootstrap for providerId=" + providerId);
        }
        if (entry.getEndpoint() == null || entry.getEndpoint().isBlank()) {
            throw new ConfigAuthorityUnavailable(
                    "connection bootstrap for providerId=" + providerId + " has no endpoint");
        }
        URI endpoint;
        try {
            endpoint = URI.create(entry.getEndpoint().trim());
        } catch (IllegalArgumentException malformed) {
            throw new ConfigAuthorityUnavailable(
                    "connection bootstrap endpoint for providerId=" + providerId + " is not a valid URI");
        }
        String scheme = endpoint.getScheme();
        if (scheme == null || endpoint.getHost() == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            // 端点必须是绝对 http(s)：相对路径/其它 scheme 会在调用点变成"看起来成功但打错地方"。
            throw new ConfigAuthorityUnavailable(
                    "connection bootstrap endpoint for providerId=" + providerId + " must be an absolute http(s) URL");
        }
        String declaredRef = entry.getCredentialRef();
        if (declaredRef != null && !declaredRef.isBlank()
                && credentialRef != null && !credentialRef.isBlank()
                && !declaredRef.equals(credentialRef)) {
            // 两侧都声明了引用却不一致 = run 绑定的凭据引用与引导项对不上；此时**不挑一个信**。
            throw new ConfigAuthorityUnavailable(
                    "credential reference mismatch for providerId=" + providerId
                            + ": binding=" + credentialRef + " bootstrap=" + declaredRef);
        }
        String effectiveRef = credentialRef == null || credentialRef.isBlank() ? declaredRef : credentialRef;
        return new ProviderConnection(providerId, endpoint, effectiveRef, entry.getApiKey());
    }
}
