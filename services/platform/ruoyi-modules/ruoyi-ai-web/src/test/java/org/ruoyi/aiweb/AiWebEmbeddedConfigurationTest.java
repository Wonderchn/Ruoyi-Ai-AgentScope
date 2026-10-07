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

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ai.api.AiExecutionFacts;
import org.ruoyi.ai.api.identity.AiIdentityPort;
import org.ruoyi.aiintegration.config.AiIntegrationProperties;
import org.ruoyi.aiintegration.web.AiGatewayClient;
import org.ruoyi.aiweb.transport.LocalAiGatewayClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3/C3 内嵌装配条件：默认与 {@code transport=http} 下不装配本地传输；
 * {@code enabled=true + transport=local} 装配齐全；身份端口缺失即启动失败
 * （"无身份源降级运行"被禁止）。
 */
@Tag("dev")
class AiWebEmbeddedConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiWebEmbeddedConfiguration.class, Fixture.class);

    @Test
    void localTransportAssemblesClientAndBoundaryFilter() {
        runner.withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=local")
                .run(context -> {
                    assertThat(context).hasSingleBean(AiGatewayClient.class);
                    assertThat(context.getBean(AiGatewayClient.class)).isInstanceOf(LocalAiGatewayClient.class);
                    assertThat(context).hasBean("aiInternalAccessBoundaryFilter");
                });
    }

    @Test
    void httpTransportAndDefaultDoNotAssemble() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(AiGatewayClient.class);
            assertThat(context).doesNotHaveBean("aiInternalAccessBoundaryFilter");
        });
        runner.withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=http")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AiGatewayClient.class);
                    assertThat(context).doesNotHaveBean("aiInternalAccessBoundaryFilter");
                });
    }

    @Test
    void missingIdentityPortFailsStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(AiWebEmbeddedConfiguration.class, FixtureWithoutIdentityPort.class)
                .withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=local")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    static class Fixture {

        @Bean
        AiIntegrationProperties aiIntegrationProperties() {
            AiIntegrationProperties properties = new AiIntegrationProperties();
            properties.setEnabled(true);
            properties.setTransport("local");
            return properties;
        }

        @Bean
        org.ruoyi.aiweb.transport.AiDeliveryReleaser aiDeliveryReleaser() {
            return (tenantId, memberId, permitId, operationId) -> { };
        }

        @Bean
        AiIdentityPort aiIdentityPort() {
            return () -> Optional.of(new AiExecutionFacts("T1", "2101", "platform:T1:2101",
                    3, 7, Set.of("ai:kb:list")));
        }
    }

    @Configuration
    static class FixtureWithoutIdentityPort {

        @Bean
        AiIntegrationProperties aiIntegrationProperties() {
            AiIntegrationProperties properties = new AiIntegrationProperties();
            properties.setEnabled(true);
            properties.setTransport("local");
            return properties;
        }
    }
}
