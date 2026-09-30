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

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

/**
 * P0.4 AI 侧装配：验签器所需的公钥与时钟、平台授权客户端、requestId 过滤器。
 *
 * <p>不引入任何新的基础设施依赖；公钥来自 platform 运行期生成后由编排脚本落盘的文件。
 */
@Configuration
@EnableConfigurationProperties(P04SecurityProperties.class)
@ConditionalOnProperty(name = "p04.enabled", havingValue = "true")
public class P04SecurityConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock p04Clock() {
        return Clock.systemUTC();
    }

    @Bean
    public HttpClient p04HttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Bean
    public FilterRegistrationBean<AiRequestIdFilter> p04AiRequestIdFilter() {
        FilterRegistrationBean<AiRequestIdFilter> registration =
                new FilterRegistrationBean<>(new AiRequestIdFilter());
        registration.addUrlPatterns("/*");
        registration.setName("p04AiRequestIdFilter");
        registration.setOrder(Integer.MIN_VALUE + 100);
        return registration;
    }
}
