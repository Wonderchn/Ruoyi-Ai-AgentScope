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

package com.nageoffer.ai.ragent.shortdrama.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.shortdrama.controller.ShortDramaController;
import com.nageoffer.ai.ragent.shortdrama.dao.ShortDramaRepository;
import com.nageoffer.ai.ragent.shortdrama.llm.DramaLlmClient;
import com.nageoffer.ai.ragent.shortdrama.service.DramaIdempotencyStore;
import com.nageoffer.ai.ragent.shortdrama.service.ShortDramaAssetService;
import com.nageoffer.ai.ragent.shortdrama.service.ShortDramaProjectService;
import com.nageoffer.ai.ragent.shortdrama.service.ShortDramaScriptService;
import com.nageoffer.ai.ragent.shortdrama.service.ShortDramaStreamService;
import com.nageoffer.ai.ragent.shortdrama.service.ShortDramaWriteService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * F21 短剧面的内嵌装配。
 *
 * <p><b>为什么必须显式装配。</b>{@code com.nageoffer.ai.ragent.shortdrama} 不在
 * platform 的组件扫描根 {@code org.ruoyi} 下；{@code @RestController/@Service}
 * 本身不会生效。本类由 {@code ruoyi-ai-web} 的
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 登记（该行属共享面，由协调者落地）。
 *
 * <p><b>四道门与既有内嵌面一致。</b>{@code ai.integration.enabled=true}、
 * {@code ai.integration.transport=local}、{@code p2.enabled=true}（提供
 * {@code DeliveryPermits}）与 {@code ai.model.enabled=true}（提供
 * {@code LLMService}）。任一门关闭时整面消失，不留半个可用端点。
 *
 * <p><b>不注册第二个 MyBatis 拦截器/元对象填充器。</b>本面使用
 * {@code JdbcTemplate} 显式 SQL（见 {@link ShortDramaRepository}），
 * Mapper 包也没有新增，避免与平台唯一插件链产生第二套权威。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class ShortDramaAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "ai.integration.transport", havingValue = "local")
    static class LocalTransport {

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
        static class RunChainEnabled {

            @Configuration(proxyBeanMethods = false)
            @ConditionalOnProperty(name = "ai.model.enabled", havingValue = "true")
            @Import({
                    ShortDramaController.class,
                    ShortDramaRepository.class,
                    ShortDramaWriteService.class,
                    ShortDramaProjectService.class,
                    ShortDramaScriptService.class,
                    ShortDramaAssetService.class,
                    ShortDramaStreamService.class,
                    DramaIdempotencyStore.class
            })
            static class ModelEnabled {

                @Bean
                public DramaLlmClient dramaLlmClient(LLMService llmService,
                                                     ObjectMapper objectMapper) {
                    return new DramaLlmClient(llmService, objectMapper);
                }
            }
        }
    }
}
