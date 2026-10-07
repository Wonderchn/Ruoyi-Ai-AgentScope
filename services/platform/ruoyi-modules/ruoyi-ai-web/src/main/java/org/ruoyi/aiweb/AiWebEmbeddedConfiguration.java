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

package org.ruoyi.aiweb;

import org.ruoyi.ai.api.identity.AiIdentityPort;
import org.ruoyi.aiintegration.web.AiGatewayClient;
import org.ruoyi.aiweb.security.AiInternalAccessBoundaryFilter;
import org.ruoyi.aiweb.transport.AiDeliveryReleaser;
import org.ruoyi.aiweb.transport.LocalAiGatewayClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

import jakarta.servlet.DispatcherType;

/**
 * AI 公开面内嵌装配（E3/C3，{@code transport=local}）。
 *
 * <p>显式列举、无根包扫描（V2 §3）。仅在 AI 集成开启且传输方式为 {@code local}
 * 时装配，与跨进程 HTTP 装配（{@code ProductionAiIntegrationConfig} 的
 * {@code transport=http} 客户端）互斥：
 *
 * <ul>
 *   <li>{@link LocalAiGatewayClient}：白名单转发的同进程实现；
 *       {@link AiIdentityPort}（platform 侧 {@code LocalAiIdentityPort} 等）缺失即
 *       启动失败——"无身份源降级运行"被禁止；</li>
 *   <li>{@link AiInternalAccessBoundaryFilter}：AI 内部路径对外 404（C3 负例语义），
 *       注册在 requestId 过滤器之后、业务链之前。</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiWebEmbeddedConfiguration {

    @Bean
    @ConditionalOnProperty(name = "ai.integration.transport", havingValue = "local")
    public AiGatewayClient localAiGatewayClient(AiIdentityPort identityPort,
                                                AiDeliveryReleaser deliveryReleaser,
                                                org.ruoyi.aiintegration.config.AiIntegrationProperties properties) {
        return new LocalAiGatewayClient(properties.getForwardTimeoutMillis(), identityPort, deliveryReleaser);
    }

    @Bean
    @ConditionalOnProperty(name = "ai.integration.transport", havingValue = "local")
    public FilterRegistrationBean<AiInternalAccessBoundaryFilter> aiInternalAccessBoundaryFilter() {
        FilterRegistrationBean<AiInternalAccessBoundaryFilter> registration =
                new FilterRegistrationBean<>(new AiInternalAccessBoundaryFilter());
        registration.addUrlPatterns("/internal/ai/v1/*");
        registration.setName("aiInternalAccessBoundaryFilter");
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        // requestId 过滤器（Integer.MIN_VALUE + 50/100）之后，保证关闭响应仍带 X-Request-Id
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }
}
