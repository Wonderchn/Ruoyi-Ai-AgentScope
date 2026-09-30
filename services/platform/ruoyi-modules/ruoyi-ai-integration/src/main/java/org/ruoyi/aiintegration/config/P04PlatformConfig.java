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

import org.ruoyi.aiintegration.delegation.DelegationSigningKeys;
import org.ruoyi.aiintegration.web.RequestIdFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * P0.4 platform 侧装配。
 *
 * <p>密钥在进程启动时运行生成（RSA 3072），只存在于内存。时钟显式注入，便于用可控时钟
 * 断言 TTL/nbf/exp 边界而不靠 sleep（Spec §7.1/§7.2）。
 */
@Configuration
@EnableConfigurationProperties(P04PlatformProperties.class)
@ConditionalOnProperty(name = "p04.enabled", havingValue = "true")
public class P04PlatformConfig {

    @Bean
    public DelegationSigningKeys p04DelegationSigningKeys(P04PlatformProperties properties) {
        return DelegationSigningKeys.generate(properties.getKid());
    }

    @Bean
    public Clock p04Clock() {
        return Clock.systemUTC();
    }

    @Bean
    public FilterRegistrationBean<RequestIdFilter> p04RequestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.addUrlPatterns("/*");
        registration.setName("p04RequestIdFilter");
        registration.setOrder(Integer.MIN_VALUE + 100);
        return registration;
    }
}
