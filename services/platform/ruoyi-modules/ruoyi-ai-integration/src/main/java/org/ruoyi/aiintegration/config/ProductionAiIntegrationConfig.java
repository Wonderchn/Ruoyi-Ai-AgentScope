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

import org.ruoyi.aiintegration.delegation.ProductionSigningKeySource;
import org.ruoyi.aiintegration.web.AiGatewayClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 生产 AI 集成（U06/P1.2c）platform 侧装配：由独立开关
 * {@code ai.integration.enabled=true} 启用（默认关，与 P0.4 的 {@code p04.enabled}
 * 实验装配完全独立）。
 *
 * <p>装配内容：
 * <ul>
 *   <li>{@link AiIntegrationProperties}（{@code ai.integration.*}）；</li>
 *   <li>{@link ProductionSigningKeySource}：从
 *       {@code ai.integration.delegation.private-key-path} 加载 PKCS#8 PEM 私钥，
 *       材料缺失在构造期即失败（fail-fast，"无钥降级运行"被禁止）；</li>
 *   <li>{@link AiGatewayClient}：JDK HttpClient，不跟随重定向，超时/体长取自配置，
 *       {@code ai.integration.ai-base-url} 缺失时启动失败。</li>
 * </ul>
 *
 * <p>{@code AiGatewayController} 由自身 {@code @ConditionalOnProperty} 同开关装配
 * （与 {@code ProductionAuthorizationController} 的既有装配模式一致），依赖
 * admin 侧的真实 {@code CurrentPrincipalResolver} 与 {@code PlatformIdentitySource}
 * 实现（经 {@code ObjectProvider}，integration 不反向依赖 system/admin）。
 *
 * <p>与 {@code p04.enabled} 同开时：时钟等通用 bean 用
 * {@code @ConditionalOnMissingBean} 防冲突；但生产口径要求两开关<b>互斥</b>——
 * 实验装配使用内存随机密钥，与生产 PEM 密钥链不兼容。
 */
@Configuration
@EnableConfigurationProperties(AiIntegrationProperties.class)
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class ProductionAiIntegrationConfig {

    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<org.ruoyi.aiintegration.web.RequestIdFilter> productionRequestIdFilter() {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(new org.ruoyi.aiintegration.web.RequestIdFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Integer.MIN_VALUE + 50);
        registration.setName("productionRequestIdFilter");
        return registration;
    }

    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter> productionInternalServiceFilter(
            @Value("${ai.integration.authorization.service-credential:}") String credential) {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter>();
        registration.setFilter((request, response, chain) -> {
            var req = (jakarta.servlet.http.HttpServletRequest) request;
            var res = (jakarta.servlet.http.HttpServletResponse) response;
            String path = req.getRequestURI().substring(req.getContextPath().length());
            var paths = java.util.Set.of("/internal/platform/v1/authorization/check",
                    "/internal/platform/v1/authorization/current",
                    "/internal/platform/v1/authorization/subjects/match",
                    "/internal/platform/v1/authorization/permits/acquire",
                    "/internal/platform/v1/authorization/permits/release");
            if (paths.contains(path) && "POST".equals(req.getMethod())) {
                String presented = req.getHeader("X-P04-Service-Credential");
                if (credential.isBlank() || presented == null || !java.security.MessageDigest.isEqual(
                        credential.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        presented.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                    res.setStatus(401);
                    res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"code\":401,\"data\":{\"errorCode\":\"AUTH_REQUIRED\"}}");
                    return;
                }
                req.setAttribute("ai.service.authenticated", Boolean.TRUE);
            }
            chain.doFilter(request, response);
        });
        registration.addUrlPatterns("/internal/platform/v1/*");
        registration.setOrder(Integer.MIN_VALUE + 100);
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock aiIntegrationClock() {
        return Clock.systemUTC();
    }

    @Bean
    public ProductionSigningKeySource productionSigningKeySource(
            @Value("${ai.integration.delegation.private-key-path:}") String privateKeyPath,
            Clock clock) {
        // 材料缺失/格式错误在构造期抛出 → 应用启动失败（fail-fast）
        return new ProductionSigningKeySource(privateKeyPath, clock);
    }

    /**
     * 跨进程 HTTP 转发客户端。仅 {@code transport=http}（默认）装配；
     * {@code transport=local}（E3/C3 内嵌同进程转送）时由 ruoyi-ai-web 的
     * {@code LocalAiGatewayClient} 取代（同进程 servlet 转送，不经 localhost HTTP）。
     */
    @Bean
    @ConditionalOnProperty(name = "ai.integration.transport", havingValue = "http", matchIfMissing = true)
    public AiGatewayClient productionAiGatewayClient(AiIntegrationProperties properties) {
        if (properties.getAiBaseUrl() == null || properties.getAiBaseUrl().isBlank()) {
            throw new IllegalStateException("ai.integration.ai-base-url is required when enabled=true");
        }
        return new AiGatewayClient(properties.getForwardTimeoutMillis());
    }
}
