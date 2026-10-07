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

package com.nageoffer.ai.ragent.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地 MinerU V1 API 配置（与云 V4 客户端完全独立；云客户端保持关闭）。
 *
 * <p>冻结值见 U00 Spec §4；token 只从受控 env/secret 注入，不写入仓库与日志。
 */
@ConfigurationProperties(prefix = "mineru.local")
public class LocalMinerUProperties {

    private boolean enabled = false;
    private String baseUrl = "";
    private String token = "";
    /** flash（原生文本快速提取）或 basic（轻量模型）。 */
    private String tier = "flash";
    private String ocrMode = "auto";
    private long pollIntervalMs = 2000;
    private int deadlineSeconds = 600;
    private long maxBytes = 50L * 1024L * 1024L;
    private int connectTimeoutSeconds = 5;
    private int requestTimeoutSeconds = 60;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getTier() {
        return tier;
    }

    public void setTier(String tier) {
        this.tier = tier;
    }

    public String getOcrMode() {
        return ocrMode;
    }

    public void setOcrMode(String ocrMode) {
        this.ocrMode = ocrMode;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public int getDeadlineSeconds() {
        return deadlineSeconds;
    }

    public void setDeadlineSeconds(int deadlineSeconds) {
        this.deadlineSeconds = deadlineSeconds;
    }

    public long getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }
}
