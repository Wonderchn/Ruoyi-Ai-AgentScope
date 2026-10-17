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

package com.nageoffer.ai.ragent.authorization;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.runtime.config.ConfigRevisionFacts;
import com.nageoffer.ai.ragent.runtime.config.ConfigRevisionPublisher;
import com.nageoffer.ai.ragent.runtime.config.ProviderConnectionPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F04：发布 API 端的保留键形态门（{@code safeConfigParams}）。
 *
 * <p>红基线针对旧树：旧实现只允许数字采样参数，任何 {@code attachmentModalities}
 * 出现（包括合法 {@code ["image"]}）都在 API 层被拒 ⇒ 第一条断言失败（行为红）。
 * 修正后要求：<b>合法形态唯一通路</b>（交给既有唯一发布权威，不新增配置面），
 * 所有非法形态（值域、重复、类型、空数组、非数组、与其他键混杂）一律拒绝，
 * 且拒绝发生在 publisher 之前。
 */
@Tag("dev")
class AttachmentModalitiesPublishApiTest {

    private final AiResourceAuthorizationService authorization = mock(AiResourceAuthorizationService.class);
    private final ConfigRevisionPublisher publisher = mock(ConfigRevisionPublisher.class);
    private final ProviderConnectionPort connections = mock(ProviderConnectionPort.class);
    private final AiResourceController controller =
            new AiResourceController(authorization, mock(AiResourceWriteService.class));
    private final ExecutionPrincipal principal = new ExecutionPrincipal("T1", "2101", "platform:T1:2101", 1, 7,
            Set.of("config.publish", "config.revoke", "config.read"), "jti", "test", 0, Long.MAX_VALUE);
    private final ConfigRevisionFacts facts = new ConfigRevisionFacts("T1", "rev-new", 9, "deepseek",
            "deepseek-flash", "catalog", "hash", "env:deepseek", "2101", Instant.now(), 1536);

    @BeforeEach
    void setup() {
        PrincipalContext.set(principal);
        controller.configureRuntimeConfig(publisher, connections);
        when(connections.requireConnection(anyString(), any())).thenReturn(
                new ProviderConnectionPort.ProviderConnection("deepseek",
                        URI.create("http://localhost/chat/completions"), "env:deepseek", null));
        when(publisher.publish(any())).thenReturn(facts);
    }

    @AfterEach
    void cleanup() {
        PrincipalContext.clear();
    }

    private AiResourceController.RuntimeConfigRequest request(Map<String, Object> params) {
        return new AiResourceController.RuntimeConfigRequest("deepseek", "deepseek-flash", "catalog",
                "env:deepseek", 1536, params);
    }

    @Test
    @DisplayName("合法声明 [\"image\"] 透传为不可变 revision 参数（随同一发布权威，无第二配置面）")
    void declaredImageIsAcceptedAndFrozenIntoTheRevisionParams() {
        var response = controller.publishRuntimeConfig(request(Map.of(
                "temperature", 0.7, "attachmentModalities", List.of("image"))));

        assertThat(response.getBody().data()).isEqualTo(facts);
        var capture = ArgumentCaptor.forClass(ConfigRevisionPublisher.ConfigRevisionCommand.class);
        verify(publisher).publish(capture.capture());
        assertThat(capture.getValue().paramsJson())
                .isEqualTo("{\"attachmentModalities\":[\"image\"],\"temperature\":0.7}");
    }

    @Test
    @DisplayName("非法形态逐条在 publisher 之前拒绝：值域/重复/类型/空数组/非数组/混杂")
    void malformedReservedKeyShapesNeverReachThePublisher() {
        for (Object bad : List.of(
                "image",
                List.of(),
                List.of("video"),
                List.of("image", "image"),
                List.of("image", 1),
                Map.of("image", true),
                List.of("image", "video"))) {
            assertThatThrownBy(() -> controller.publishRuntimeConfig(
                    request(Map.of("attachmentModalities", bad))))
                    .as(String.valueOf(bad))
                    .isInstanceOf(P04AiException.class);
        }
        verifyNoInteractions(publisher);
    }
}
