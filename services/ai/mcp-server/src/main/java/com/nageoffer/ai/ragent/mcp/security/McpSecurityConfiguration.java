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

package com.nageoffer.ai.ragent.mcp.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.security.AiRequestIdFilter;
import com.nageoffer.ai.ragent.framework.security.AuthorizationChecker;
import com.nageoffer.ai.ragent.framework.security.DelegatedPrincipalFilter;
import com.nageoffer.ai.ragent.framework.security.P04SecurityProperties;
import com.nageoffer.ai.ragent.framework.security.PlatformAuthorizationClient;
import com.nageoffer.ai.ragent.framework.security.ProductionDelegationProperties;
import com.nageoffer.ai.ragent.framework.security.ProductionReplayGuard;
import com.nageoffer.ai.ragent.framework.security.ServiceIdentityVerifier;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;

/**
 * 9099 独立进程·受保护模式的装配（SPEC-9099 §2.2/§7，T0 2026-10-11）。
 * 单开关 {@code ai.integration.security.enabled=true} 启用，<b>默认关</b>（关时本类全体
 * 不装配，模块行为与现状逐字节一致：{@code /mcp} + 全工具面）。
 *
 * <p><b>复用为主。</b>认证与验签件来自 framework 生产委托链、一行不改：
 * {@link DelegatedPrincipalFilter}（RS256 验签 + 服务身份 + jti 重放 + 租户 epoch +
 * 主体注入）、{@link ServiceIdentityVerifier}、{@link PlatformAuthorizationClient}
 * （逐工具在线判定的 HTTP 事实端口客户端，{@code strictContext=true}）；
 * 最小自建仅三个端口实现与一个逐工具过滤器（见各类 javadoc）。
 *
 * <p><b>fail-fast。</b>公钥缺失 → 过滤器构造即失败；{@code ai.integration.platform-base-url}
 * 与 {@code ai.integration.platform-service-credential} 缺省 → 本类装配失败——
 * 受保护模式没有"没配好也先起着"的降级形态。
 *
 * <p><b>分派范围（与内嵌 MCP 的 R12 卡1 同款，只接管 REQUEST）。</b>jti 单次消费，
 * 若注册 ASYNC/ERROR，streamable-http 的异步收尾/错误分派会让同一令牌二次消费被 401、
 * 打断 SSE；认证只需在进入 servlet 的 REQUEST 分派上闭合一次。
 */
@Configuration
@EnableConfigurationProperties(ProductionDelegationProperties.class)
@ConditionalOnProperty(name = "ai.integration.security.enabled", havingValue = "true")
public class McpSecurityConfiguration {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock mcpSecurityClock() {
        return Clock.systemUTC();
    }

    /** 安全支撑表所用 JdbcTemplate（挂 bit 库数据源；不触碰业务表结构）。 */
    @Bean
    public JdbcTemplate mcpSecurityJdbcTemplate(DataSource bitDataSource) {
        return new JdbcTemplate(bitDataSource);
    }

    /** 幂等建表：FAIL 即启动失败（安全账本缺表不允许带病运行）。 */
    @Bean
    public McpSecuritySchemaInitializer mcpSecuritySchemaInitializer(JdbcTemplate mcpSecurityJdbcTemplate) {
        McpSecuritySchemaInitializer initializer = new McpSecuritySchemaInitializer(mcpSecurityJdbcTemplate);
        initializer.initialize();
        return initializer;
    }

    @Bean
    public ServiceIdentityVerifier mcpServiceIdentityVerifier(ProductionDelegationProperties properties) {
        return new ServiceIdentityVerifier(properties);
    }

    @Bean
    public McpJdbcDelegationReplayStore mcpDelegationReplayStore(JdbcTemplate mcpSecurityJdbcTemplate) {
        return new McpJdbcDelegationReplayStore(mcpSecurityJdbcTemplate);
    }

    @Bean
    public DelegatedPrincipalFilter.AclVersionSource mcpTenantEpochSource(
            JdbcTemplate mcpSecurityJdbcTemplate) {
        return new McpTenantEpochSource(mcpSecurityJdbcTemplate);
    }

    /**
     * 逐工具授权的事实端口：平台在线判定端点（HTTP，不共库）。
     * 配置缺省 = 装配失败（fail-fast）；运行期不可达 = 503 拒绝（fail-closed）。
     */
    @Bean
    public AuthorizationChecker mcpPlatformAuthorizationChecker(
            @Value("${ai.integration.platform-base-url:}") String platformBaseUrl,
            @Value("${ai.integration.platform-service-credential:}") String platformServiceCredential) {
        if (platformBaseUrl.isBlank() || platformServiceCredential.isBlank()) {
            throw new IllegalStateException("ai.integration.platform-base-url and "
                    + "ai.integration.platform-service-credential are required when "
                    + "ai.integration.security.enabled=true");
        }
        P04SecurityProperties configuration = new P04SecurityProperties();
        configuration.getPlatform().setAuthorizationUrl(
                platformBaseUrl + "/internal/platform/v1/authorization/check");
        configuration.getPlatform().setServiceCredential(platformServiceCredential);
        return new PlatformAuthorizationClient(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(2))
                        .followRedirects(HttpClient.Redirect.NEVER).build(),
                new ObjectMapper(), configuration, true);
    }

    @Bean
    public DelegatedPrincipalFilter mcpDelegatedPrincipalFilter(
            ProductionDelegationProperties properties, Clock clock, ListableBeanFactory beanFactory) {
        return new DelegatedPrincipalFilter(properties, clock,
                beanFactory.getBeanProvider(ProductionReplayGuard.class),
                beanFactory.getBeanProvider(DelegatedPrincipalFilter.AclVersionSource.class));
    }

    /** 请求标识（与生产装配同款，顺序在其余过滤器之前）。 */
    @Bean
    public FilterRegistrationBean<AiRequestIdFilter> mcpAiRequestIdFilterRegistration() {
        FilterRegistrationBean<AiRequestIdFilter> registration =
                new FilterRegistrationBean<>(new AiRequestIdFilter());
        registration.addUrlPatterns("/*");
        registration.setName("mcpAiRequestIdFilter");
        registration.setOrder(Integer.MIN_VALUE + 50);
        return registration;
    }

    /** 委托验签过滤器：受保护前缀，先行确立主体（只接管 REQUEST）。 */
    @Bean
    public FilterRegistrationBean<DelegatedPrincipalFilter> mcpDelegationFilterRegistration(
            DelegatedPrincipalFilter filter) {
        FilterRegistrationBean<DelegatedPrincipalFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns(DelegatedPrincipalFilter.PROTECTED_PREFIX + "/*");
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST));
        registration.setName("mcpDelegationFilterRegistration");
        registration.setOrder(Integer.MIN_VALUE + 200);
        return registration;
    }

    /** 逐工具授权过滤器：排在验签之后（读主体），只接管 REQUEST。 */
    @Bean
    public McpToolAuthzFilter mcpToolAuthzFilter(AuthorizationChecker mcpPlatformAuthorizationChecker) {
        return new McpToolAuthzFilter(mcpPlatformAuthorizationChecker);
    }

    @Bean
    public FilterRegistrationBean<McpToolAuthzFilter> mcpToolAuthzFilterRegistration(
            McpToolAuthzFilter mcpToolAuthzFilter) {
        FilterRegistrationBean<McpToolAuthzFilter> registration =
                new FilterRegistrationBean<>(mcpToolAuthzFilter);
        registration.addUrlPatterns(DelegatedPrincipalFilter.PROTECTED_PREFIX + "/*");
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST));
        registration.setName("mcpToolAuthzFilterRegistration");
        registration.setOrder(Integer.MIN_VALUE + 300);
        return registration;
    }
}
