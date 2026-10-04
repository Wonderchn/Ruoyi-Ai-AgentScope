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

package org.ruoyi.aiintegration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 生产 AI 集成（U06/P1.2c）platform 侧配置，前缀 {@code ai.integration}。
 *
 * <p>与 {@code p04.*}（{@link P04PlatformProperties}，实验期）完全独立：生产装配用
 * 独立开关 {@code ai.integration.enabled}（默认关）。AI 网关的签发私钥路径不在此处
 * （属敏感材料，经 {@code ai.integration.delegation.private-key-path} 注入，
 * 见 {@code ProductionAiIntegrationConfig}）。
 */
@ConfigurationProperties(prefix = "ai.integration")
public class AiIntegrationProperties {

    /** 生产集成总开关（与装配条件同名同值，默认关）。 */
    private boolean enabled = false;

    /** AI 服务基地址（如 {@code http://127.0.0.1:8080}），不带路径。 */
    private String aiBaseUrl;

    /**
     * 平台↔AI 共享的服务凭证（与 AI 侧 {@code ai.integration.security.service-credential}
     * 同值注入）。本模块内它只作为部署面配置存在，网关转发用委托凭证而不复用它；
     * 只经进程环境注入，不落盘、不进日志。
     */
    private String serviceCredential;

    /** 转发到 AI 的单请求超时（毫秒）；超时一律判为上游不可用，不放行。 */
    private int forwardTimeoutMillis = 2000;

    /** 转发请求体的最大字节数；超限直接拒绝，不缓冲不转发。 */
    private long maxForwardBodyBytes = 2L * 1024 * 1024;

    /** 专用 SSE 流：建连超时（毫秒）。 */
    private int sseConnectTimeoutMillis = 5000;

    /** 专用 SSE 流：空闲超时（毫秒），须 ≥ 心跳间隔的 2 倍（心跳 15s）。 */
    private int sseIdleTimeoutMillis = 120000;

    /** 专用 SSE 流：单连接总时长上限（毫秒）。 */
    private int sseMaxDurationMillis = 1800000;

    /** 专用上传传输：单请求上限（字节，默认 20MB，与 AI 首期一致）。 */
    private long uploadMaxBytes = 20L * 1024L * 1024L;

    /**
     * AI 侧传输方式（E3/C3）：
     * <ul>
     *   <li>{@code http}（默认）：跨进程 HTTP 转发，需要 {@code ai-base-url}、签名密钥与服务凭证；</li>
     *   <li>{@code local}：内嵌同进程转送（servlet forward，非 localhost HTTP）。
     *       委托凭证不铸造、服务凭证不要求；AI 侧内部控制器在同一进程内装配，
     *       身份由 {@code AiIdentityPort} 经 {@code PrincipalContext} 桥接。</li>
     * </ul>
     */
    private String transport = "http";

    public boolean isLocalTransport() {
        return "local".equalsIgnoreCase(transport);
    }

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAiBaseUrl() {
        return aiBaseUrl;
    }

    public void setAiBaseUrl(String aiBaseUrl) {
        this.aiBaseUrl = aiBaseUrl;
    }

    public String getServiceCredential() {
        return serviceCredential;
    }

    public void setServiceCredential(String serviceCredential) {
        this.serviceCredential = serviceCredential;
    }

    public int getForwardTimeoutMillis() {
        return forwardTimeoutMillis;
    }

    public void setForwardTimeoutMillis(int forwardTimeoutMillis) {
        this.forwardTimeoutMillis = forwardTimeoutMillis;
    }

    public long getMaxForwardBodyBytes() {
        return maxForwardBodyBytes;
    }

    public void setMaxForwardBodyBytes(long maxForwardBodyBytes) {
        this.maxForwardBodyBytes = maxForwardBodyBytes;
    }

    public int getSseConnectTimeoutMillis() {
        return sseConnectTimeoutMillis;
    }

    public void setSseConnectTimeoutMillis(int sseConnectTimeoutMillis) {
        this.sseConnectTimeoutMillis = sseConnectTimeoutMillis;
    }

    public int getSseIdleTimeoutMillis() {
        return sseIdleTimeoutMillis;
    }

    public void setSseIdleTimeoutMillis(int sseIdleTimeoutMillis) {
        this.sseIdleTimeoutMillis = sseIdleTimeoutMillis;
    }

    public int getSseMaxDurationMillis() {
        return sseMaxDurationMillis;
    }

    public void setSseMaxDurationMillis(int sseMaxDurationMillis) {
        this.sseMaxDurationMillis = sseMaxDurationMillis;
    }

    public long getUploadMaxBytes() {
        return uploadMaxBytes;
    }

    public void setUploadMaxBytes(long uploadMaxBytes) {
        this.uploadMaxBytes = uploadMaxBytes;
    }
}
