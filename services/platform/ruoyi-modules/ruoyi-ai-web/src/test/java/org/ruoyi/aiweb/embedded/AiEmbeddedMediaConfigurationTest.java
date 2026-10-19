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
import com.nageoffer.ai.ragent.ingest.PrivateObjectStore;
import com.nageoffer.ai.ragent.media.audio.DashScopeAudioGenerationService;
import com.nageoffer.ai.ragent.media.image.TongYiWanxImageGenerationService;
import com.nageoffer.ai.ragent.media.task.MediaController;
import com.nageoffer.ai.ragent.media.task.MediaTaskService;
import com.nageoffer.ai.ragent.media.video.DashScopeVideoGenerationService;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * F19 媒体装配门控：master/transport/p2/media 四个开关逐层关闭时整组不装配；
 * 打开时控制器与服务成对出现（放行与有人接成对）。
 */
@Tag("dev")
class AiEmbeddedMediaConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AiEmbeddedMediaConfiguration.class))
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(PrivateObjectStore.class, () -> mock(PrivateObjectStore.class))
            .withBean(DeliveryPermits.class, () -> mock(DeliveryPermits.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void enabledLocalP2AssemblesMediaSurface() {
        runner.withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=local",
                        "p2.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MediaController.class);
                    assertThat(context).hasSingleBean(MediaTaskService.class);
                    assertThat(context).hasSingleBean(TongYiWanxImageGenerationService.class);
                    assertThat(context).hasSingleBean(DashScopeAudioGenerationService.class);
                    assertThat(context).hasSingleBean(DashScopeVideoGenerationService.class);
                    assertThat(context).hasBean("mediaTaskRepository");
                    assertThat(context).hasBean("mediaContentStore");
                    assertThat(context).hasBean("mediaUsageMeter");
                });
    }

    @Test
    void mediaFlagClosesTheWholeSurface() {
        runner.withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=local",
                        "p2.enabled=true", "ai.embedded.media.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(MediaController.class);
                    assertThat(context).doesNotHaveBean(MediaTaskService.class);
                    assertThat(context).doesNotHaveBean(DashScopeAudioGenerationService.class);
                    assertThat(context).doesNotHaveBean(DashScopeVideoGenerationService.class);
                });
    }

    @Test
    void p2DisabledClosesTheWholeSurface() {
        runner.withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=local")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(MediaController.class);
                    assertThat(context).doesNotHaveBean(TongYiWanxImageGenerationService.class);
                });
    }

    @Test
    void httpTransportDoesNotRegisterLocalMediaBeans() {
        runner.withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=http",
                        "p2.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(MediaController.class);
                    assertThat(context).doesNotHaveBean(MediaTaskService.class);
                });
    }

    @Test
    void missingMasterSwitchKeepsMediaClosed() {
        runner.withPropertyValues("ai.integration.transport=local", "p2.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(MediaController.class);
                });
    }
}
