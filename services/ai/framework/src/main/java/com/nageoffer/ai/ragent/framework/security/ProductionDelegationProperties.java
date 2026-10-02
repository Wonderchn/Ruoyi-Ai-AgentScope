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

package com.nageoffer.ai.ragent.framework.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 生产委托链（U06/P1.2c）AI 侧配置，前缀 {@code ai.integration.security}。
 *
 * <p>与 P0.4 的 {@code p04.*}（{@link P04SecurityProperties}）完全独立：
 * 生产装配用独立开关 {@code ai.integration.security.enabled}（默认关），
 * 不与实验装配互抢属性。issuer/audience/kid 的默认值与 platform 侧生产签发方
 * （{@code org.ruoyi.aiintegration}）的冻结契约逐项镜像，两侧不得单方面漂移。
 *
 * <p>服务凭证只经进程环境注入，不落盘、不进证据；公钥来自 platform 运行期
 * 签发私钥对应的公钥 PEM 文件（AI 侧永远只持公钥）。
 */
@ConfigurationProperties(prefix = "ai.integration.security")
public class ProductionDelegationProperties {

    /** 生产委托链总开关（与装配条件同名同值，默认关）。 */
    private boolean enabled = false;

    /** platform 公钥 PEM 文件路径（X.509 SubjectPublicKeyInfo）。 */
    private String publicKeyPath;

    /** 受信签发方白名单（与 platform 生产签发的 iss 精确相等）。 */
    private String issuer = "platform";

    /** 受信受众白名单（与 platform 生产签发的 aud 精确相等）。 */
    private String audience = "ai";

    /** 受信密钥标识白名单（与 platform 生产签发的 kid 精确相等）。 */
    private String kid = "platform-prod-k1";

    /** 允许的时钟偏差（秒），与 P0.4 冻结口径一致。 */
    private long clockSkewSeconds = 30;

    /** 委托凭证 TTL 上限（秒），与 Spec §7.2「TTL 60 秒」一致（不含 skew）。 */
    private long ttlCeilingSeconds = DelegationVerifier.TTL_CEILING_SECONDS;

    /**
     * 平台→AI 的服务间共享凭证（{@code X-P04-Service-Credential}）；
     * 与委托凭证分开，缺省时空白 = 全部拒绝（见 {@link ServiceIdentityVerifier}）。
     */
    private String serviceCredential;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPublicKeyPath() {
        return publicKeyPath;
    }

    public void setPublicKeyPath(String publicKeyPath) {
        this.publicKeyPath = publicKeyPath;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public String getKid() {
        return kid;
    }

    public void setKid(String kid) {
        this.kid = kid;
    }

    public long getClockSkewSeconds() {
        return clockSkewSeconds;
    }

    public void setClockSkewSeconds(long clockSkewSeconds) {
        this.clockSkewSeconds = clockSkewSeconds;
    }

    public long getTtlCeilingSeconds() {
        return ttlCeilingSeconds;
    }

    public void setTtlCeilingSeconds(long ttlCeilingSeconds) {
        this.ttlCeilingSeconds = ttlCeilingSeconds;
    }

    public String getServiceCredential() {
        return serviceCredential;
    }

    public void setServiceCredential(String serviceCredential) {
        this.serviceCredential = serviceCredential;
    }
}
