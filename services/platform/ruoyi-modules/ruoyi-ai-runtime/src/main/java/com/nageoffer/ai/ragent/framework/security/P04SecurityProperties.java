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
 * P0.4 AI 侧配置。
 *
 * <p>AI 只持<b>公钥</b>（由 platform 在运行期生成并由编排脚本写入文件）；服务凭证只经进程环境
 * 注入，不落盘、不进证据。
 */
@ConfigurationProperties(prefix = "p04")
public class P04SecurityProperties {

    private final Delegation delegation = new Delegation();
    private final Platform platform = new Platform();

    public Delegation getDelegation() {
        return delegation;
    }

    public Platform getPlatform() {
        return platform;
    }

    /** 委托验签设置。 */
    public static class Delegation {

        /** platform 公钥 PEM 文件路径。 */
        private String publicKeyPath;

        /** 受信签发方白名单。 */
        private String issuer = "platform";

        /** 受信受众白名单。 */
        private String audience = "ai";

        /** 受信密钥标识白名单。 */
        private String kid = "p04-platform-k1";

        /** 允许的时钟偏差（秒）。 */
        private long clockSkewSeconds = 30;

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
    }

    /** AI → platform 在线授权复核设置。 */
    public static class Platform {

        /** 授权复核端点（F1 路径）。 */
        private String authorizationUrl = "http://127.0.0.1:18082/internal/platform/v1/authorization/check";

        /** 独立的服务凭证（与委托凭证分开，不复用浏览器 token）。 */
        private String serviceCredential;

        /** 客户端超时；超时一律判为不可用，不放行。 */
        private int timeoutMillis = 2000;

        public String getAuthorizationUrl() {
            return authorizationUrl;
        }

        public void setAuthorizationUrl(String authorizationUrl) {
            this.authorizationUrl = authorizationUrl;
        }

        public String getServiceCredential() {
            return serviceCredential;
        }

        public void setServiceCredential(String serviceCredential) {
            this.serviceCredential = serviceCredential;
        }

        public int getTimeoutMillis() {
            return timeoutMillis;
        }

        public void setTimeoutMillis(int timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
        }
    }
}
