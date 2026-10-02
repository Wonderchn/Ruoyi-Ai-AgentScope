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

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.EnumSet;

/**
 * 生产委托链（U06/P1.2c）AI 侧装配：由独立开关
 * {@code ai.integration.security.enabled=true} 启用（默认关，与 P0.4 的
 * {@code p04.enabled} 实验装配完全独立）。
 *
 * <p>装配内容：
 * <ul>
 *   <li>{@link ServiceIdentityVerifier}：平台→AI 服务身份（常量时间凭证比较）；</li>
 *   <li>{@link DelegatedPrincipalFilter}：注册到 {@code /internal/ai/v1/*}，
 *       REQUEST/FORWARD/ASYNC/ERROR 四种分派全接管，并把
 *       {@link ProductionReplayGuard}（rag 的持久实现）与
 *       {@link DelegatedPrincipalFilter.AclVersionSource}（rag 的 epoch 读取）
 *       经 {@link ObjectProvider} 注入——缺实现时过滤器按 503 fail-closed，
 *       本装配自身不提供默认实现。</li>
 * </ul>
 *
 * <p>时钟显式注入（可被测试覆盖）；公钥缺失时过滤器构造即失败（fail-fast），
 * 不允许"无钥降级运行"。
 */
@Configuration
@EnableConfigurationProperties(ProductionDelegationProperties.class)
@ConditionalOnProperty(name = "ai.integration.security.enabled", havingValue = "true")
public class ProductionSecurityConfig {

    @Bean
    public FilterRegistrationBean<AiRequestIdFilter> productionAiRequestIdFilter() {
        var registration = new FilterRegistrationBean<>(new AiRequestIdFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Integer.MIN_VALUE + 50);
        registration.setName("productionAiRequestIdFilter");
        return registration;
    }

    @Bean
    public AuthorizationChecker productionAuthorizationChecker(
            @org.springframework.beans.factory.annotation.Value("${ai.integration.platform-base-url:}") String url,
            @org.springframework.beans.factory.annotation.Value("${ai.integration.platform-service-credential:}") String credential) {
        if (url.isBlank() || credential.isBlank()) { throw new IllegalStateException("platform authorization configuration required"); }
        P04SecurityProperties configuration = new P04SecurityProperties();
        configuration.getPlatform().setAuthorizationUrl(url + "/internal/platform/v1/authorization/check");
        configuration.getPlatform().setServiceCredential(credential);
        return new PlatformAuthorizationClient(java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(2)).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build(),
                new com.fasterxml.jackson.databind.ObjectMapper(), configuration, true);
    }

    @Bean
    public DelegatedPrincipalFilter.AclVersionSource productionAclVersionSource(
            ObjectProvider<ResourceAuthorizationService> resources) {
        return tenantId -> {
            ResourceAuthorizationService service = resources.getIfAvailable();
            if (service == null) { throw new com.nageoffer.ai.ragent.framework.exception.ServiceException("ACL source missing"); }
            return service.currentAclVersion(tenantId);
        };
    }

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock productionSecurityClock() {
        return Clock.systemUTC();
    }

    @Bean
    public ServiceIdentityVerifier productionServiceIdentityVerifier(ProductionDelegationProperties properties) {
        return new ServiceIdentityVerifier(properties);
    }

    @Bean
    public DelegatedPrincipalFilter productionDelegatedPrincipalFilter(
            ProductionDelegationProperties properties, Clock clock,
            ObjectProvider<ProductionReplayGuard> replayGuard,
            ObjectProvider<DelegatedPrincipalFilter.AclVersionSource> aclVersionSource) {
        return new DelegatedPrincipalFilter(properties, clock, replayGuard, aclVersionSource);
    }

    @Bean
    public FilterRegistrationBean<DelegatedPrincipalFilter> productionDelegatedPrincipalFilterRegistration(
            DelegatedPrincipalFilter filter) {
        FilterRegistrationBean<DelegatedPrincipalFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns(DelegatedPrincipalFilter.PROTECTED_PREFIX + "/*");
        // 全接管：普通入口、内部转发、异步收尾、错误页分派都要"验主体 → 链路 → finally 清理"
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST, DispatcherType.FORWARD,
                DispatcherType.ASYNC, DispatcherType.ERROR));
        registration.setName("productionDelegatedPrincipalFilter");
        // 尽早执行：先于业务链路确立主体（requestId 过滤器仍在其之前）
        registration.setOrder(Integer.MIN_VALUE + 200);
        return registration;
    }
}
