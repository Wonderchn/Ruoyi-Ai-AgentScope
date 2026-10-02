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

package com.nageoffer.ai.ragent.framework.integration;

import com.nageoffer.ai.ragent.framework.security.AiRequestIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * 产品边界装配（P1.2a）。
 *
 * <p>本配置位于正常产品组件扫描内、{@code framework.security}（P04 专用扫描）之外，
 * 不依赖测试 provider、数据库或任何客户事实。
 *
 * <p>两件事：
 * <ol>
 *   <li>注册 requestId 前置过滤器与入口边界过滤器（顺序：requestId → 入口边界 → 旧
 *       SaToken 拦截器）。op 前缀的 property 是
 *       {@code ai.integration.*}，缺属性等价于关闭。</li>
 *   <li>启动自检：产品配置里出现任何未批准能力的 {@code true}（{@code p04.enabled}、
 *       {@code ai.integration.enabled}、{@code ai.integration.customer-api.enabled}、
 *       {@code ai.integration.legacy-listeners-enabled}）时<b>拒绝启动</b>，
 *       在 <i>ApplicationReadyEvent</i> 之后给出脱敏配置错误而非静默降级。</li>
 * </ol>
 */
@Configuration
@EnableConfigurationProperties(SaasBoundaryProperties.class)
public class SaasBoundaryConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SaasBoundaryConfiguration.class);

    /** 未批准能力 → 配置属性名；顺序固定便于断言与排障。 */
    private static final String[] UNAPPROVED_CAPABILITY_FLAGS = {
            "p04.enabled",
            "ai.integration.enabled",
            "ai.integration.customer-api.enabled",
            "ai.integration.legacy-listeners-enabled"
    };

    /**
     * 旧自动能力关闭判定。声明为 Bean 以便旧消费者/回查/调度/初始化器通过
     * {@code ObjectProvider} 可选注入——它们在这些组件不存在时仍必须可用。
     */
    @Bean
    public SaasCapabilityBoundary saasCapabilityBoundary(SaasBoundaryProperties properties) {
        return new SaasCapabilityBoundary(properties);
    }

    /**
     * 入口边界过滤器。
     *
     * <p>只在 {@code REQUEST} 与 {@code FORWARD} 分派上注册：{@code ASYNC}（SSE 收尾）
     * 与 {@code ERROR}（错误页）分派必须越过边界，否则错误页会被自身拦成递归 404。
     */
    @Bean
    public FilterRegistrationBean<SaasEntryFilter> saasEntryFilterRegistration(Environment environment) {
        FilterRegistrationBean<SaasEntryFilter> registration =
                new FilterRegistrationBean<>(new SaasEntryFilter(
                        environment.getProperty("ai.integration.enabled", Boolean.class, false)
                        && environment.getProperty("ai.integration.security.enabled", Boolean.class, false)));
        registration.addUrlPatterns("/*");
        registration.setName("saasEntryFilter");
        registration.setDispatcherTypes(jakarta.servlet.DispatcherType.REQUEST,
                jakarta.servlet.DispatcherType.FORWARD);
        // 比 P04 的 requestId 过滤器晚一档，保证关闭响应仍带 X-Request-Id
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    /**
     * 产品 requestId 过滤器。
     *
     * <p>P04 的同类注册只在 {@code p04.enabled=true} 时存在，产品默认关闭时不能因此缺失：
     * 每个请求（含被拒绝的请求）都要有 {@code X-Request-Id}。
     */
    @Bean
    @ConditionalOnMissingBean(name = "p04AiRequestIdFilter")
    public FilterRegistrationBean<AiRequestIdFilter> saasAiRequestIdFilter() {
        FilterRegistrationBean<AiRequestIdFilter> registration =
                new FilterRegistrationBean<>(new AiRequestIdFilter());
        registration.addUrlPatterns("/*");
        registration.setName("saasAiRequestIdFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * 启动自检监听器。用监听器而非 {@code ApplicationRunner}：它属于 context 生命周期、
     * 早于任何业务 {@code ApplicationRunner}，且不依赖 web 自动装配。
     */
    @Bean
    public ApplicationListener<ApplicationReadyEvent> saasBoundaryStartupValidation(
            SaasBoundaryProperties properties, Environment environment,
            ObjectProvider<SaasCapabilityBoundary> boundaryProvider) {
        return event -> validateStartup(properties, environment, boundaryProvider.getIfAvailable());
    }

    private static void validateStartup(SaasBoundaryProperties properties, Environment environment,
                                        SaasCapabilityBoundary boundary) {
        if (!properties.isStartupValidationEnabled()) {
            log.warn("SaaS boundary startup validation disabled by configuration; "
                    + "this must not be used for a product deployment");
            return;
        }
        for (String flag : UNAPPROVED_CAPABILITY_FLAGS) {
            if (flag.equals("ai.integration.enabled")
                    && environment.getProperty("ai.integration.security.enabled", Boolean.class, false)
                    && !environment.getProperty("p04.enabled", Boolean.class, false)) { continue; }
            if (environment.getProperty(flag, Boolean.class, Boolean.FALSE)) {
                throw new IllegalStateException("unapproved capability enabled: " + flag
                        + "; this capability is not approved for a product deployment");
            }
        }
        // 专用 P04 测试应用（独立 test source set、只扫 security/runtime、p04ai profile）
        // 继续用 p04.enabled=true 做它自己的合成实验，故上面那条对该 profile 不适用。
        if (environment.acceptsProfiles(Profiles.of("p04ai"))) {
            return;
        }
        if (boundary == null) {
            throw new IllegalStateException("SaaS capability boundary bean is missing; "
                    + "legacy capability closure cannot be verified");
        }
        if (boundary.isLegacyOpen()) {
            throw new IllegalStateException("legacy capability closure cannot be verified: "
                    + "ai.integration.legacy-listeners-enabled must stay false");
        }
    }
}
