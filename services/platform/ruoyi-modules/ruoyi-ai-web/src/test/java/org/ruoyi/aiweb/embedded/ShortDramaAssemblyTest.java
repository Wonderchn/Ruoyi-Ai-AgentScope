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

package org.ruoyi.aiweb.embedded;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import com.nageoffer.ai.ragent.shortdrama.config.ShortDramaAutoConfiguration;
import com.nageoffer.ai.ragent.shortdrama.controller.ShortDramaController;
import com.nageoffer.ai.ragent.shortdrama.service.ShortDramaAssetService;
import com.nageoffer.ai.ragent.shortdrama.service.ShortDramaProjectService;
import com.nageoffer.ai.ragent.shortdrama.service.ShortDramaScriptService;
import com.nageoffer.ai.ragent.shortdrama.service.ShortDramaStreamService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * F21 装配判据：四道门全开时短剧面整体注册，任一关闭时整面消失。
 *
 * <p>本判据直接 {@code register(ShortDramaAutoConfiguration.class)}，不依赖
 * {@code AutoConfiguration.imports}（该行属共享面，由协调者落地）。它证明的是：
 * 一旦 imports 行落地，闭包本身是完整的（控制器/服务/仓库/LLM 门面都能构造），
 * 且条件门确实能把整个面摘掉，而不是留半个可用端点。
 */
@Tag("dev")
class ShortDramaAssemblyTest {

    @Test
    @DisplayName("四道门全开 → 控制器与四个服务全部注册")
    void surfaceIsRegisteredWhenAllGatesOpen() {
        try (AnnotationConfigApplicationContext context = context(true, true)) {
            assertThat(context.getBean(ShortDramaController.class)).isNotNull();
            assertThat(context.getBean(ShortDramaProjectService.class)).isNotNull();
            assertThat(context.getBean(ShortDramaScriptService.class)).isNotNull();
            assertThat(context.getBean(ShortDramaAssetService.class)).isNotNull();
            assertThat(context.getBean(ShortDramaStreamService.class)).isNotNull();
        }
    }

    @Test
    @DisplayName("模型门关闭 → 短剧面整体消失（不注册半个控制器）")
    void surfaceIsAbsentWhenModelGateClosed() {
        try (AnnotationConfigApplicationContext context = context(true, false)) {
            assertThat(context.getBeanNamesForType(ShortDramaController.class)).isEmpty();
            assertThat(context.getBeanNamesForType(ShortDramaProjectService.class)).isEmpty();
        }
    }

    private static AnnotationConfigApplicationContext context(boolean p2Enabled,
                                                              boolean modelEnabled) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("f21-test", Map.of(
                "ai.integration.enabled", "true",
                "ai.integration.transport", "local",
                "p2.enabled", Boolean.toString(p2Enabled),
                "ai.model.enabled", Boolean.toString(modelEnabled))));
        context.registerBean("llmService", LLMService.class, () -> mock(LLMService.class));
        context.registerBean("jdbcTemplate", JdbcTemplate.class, () -> mock(JdbcTemplate.class));
        context.registerBean("deliveryPermits", DeliveryPermits.class, () -> mock(DeliveryPermits.class));
        context.registerBean("objectMapper", ObjectMapper.class, () -> new ObjectMapper());
        context.register(ShortDramaAutoConfiguration.class);
        context.refresh();
        return context;
    }
}
