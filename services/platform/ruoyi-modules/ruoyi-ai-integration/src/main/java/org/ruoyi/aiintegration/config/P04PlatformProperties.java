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
 * P0.4 platform 侧配置。
 *
 * <p>服务凭证只经进程环境注入（{@code P04_SERVICE_CREDENTIAL}），不落盘、不进证据。
 */
@ConfigurationProperties(prefix = "p04")
public class P04PlatformProperties {

    /** AI → platform 授权复核所用的受控服务凭证（两侧共享的合成值）。 */
    private String serviceCredential;

    /** 签发密钥标识（kid）。 */
    private String kid = "p04-platform-k1";

    public String getServiceCredential() {
        return serviceCredential;
    }

    public void setServiceCredential(String serviceCredential) {
        this.serviceCredential = serviceCredential;
    }

    public String getKid() {
        return kid;
    }

    public void setKid(String kid) {
        this.kid = kid;
    }
}
