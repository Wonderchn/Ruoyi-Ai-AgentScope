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

package com.nageoffer.ai.ragent.framework.web;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ENV-B5b 许可接线判据：带许可的 sender 必须给每个数据帧打
 * {@code : ai-delivery <permitId> <operationId>} 元数据（本地/远程网关传输对
 * 裸数据帧 fail-closed："unprotected stream frame"）；无许可形态逐字保持旧形状。
 */
@Tag("dev")
class SseEmitterSenderTest {

    private final SseEmitter emitter = mock(SseEmitter.class);
    private final DeliveryPermits permits = mock(DeliveryPermits.class);

    @Test
    @DisplayName("带许可发送：帧头出现 : ai-delivery <permit> <op>，事件名与数据原样保留")
    void licensedSendCarriesDeliveryMetadataAheadOfTheFrame() throws Exception {
        when(permits.enter(any(), eq("run.stream"), eq("chat:t-1")))
                .thenReturn(new DeliveryPermits.Permit("p-1", "op-1", null));

        new SseEmitterSender(emitter, permits, principal(), "run.stream", "chat:t-1")
                .sendEvent("message", "{\"delta\":\"2\"}");

        ArgumentCaptor<SseEmitter.SseEventBuilder> captured =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter).send(captured.capture());
        String rendered = render(captured.getValue());
        assertThat(rendered).as("帧必须带交付元数据（本地传输按行前缀解析 ': ai-delivery '）")
                .contains(": ai-delivery p-1 op-1");
        assertThat(rendered).contains("event:message");
        assertThat(rendered).contains("data:{\"delta\":\"2\"}");
    }

    @Test
    @DisplayName("无许可发送：形状与旧实现逐字一致（无 : ai-delivery 行）")
    void unlicensedSendKeepsLegacyShape() throws Exception {
        new SseEmitterSender(emitter).sendEvent("message", "x");

        ArgumentCaptor<SseEmitter.SseEventBuilder> captured =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter).send(captured.capture());
        String rendered = render(captured.getValue());
        assertThat(rendered).doesNotContain("ai-delivery");
        assertThat(rendered).contains("event:message");
    }

    private static ExecutionPrincipal principal() {
        return new ExecutionPrincipal("000000", "910101", "platform:000000:910101", 1, 1,
                Set.of("run.stream"), "sender-test", "test", 1, Long.MAX_VALUE);
    }

    /** 递归摊平 SseEventBuilder（含嵌套片段）为可断言文本。 */
    private static String render(SseEmitter.SseEventBuilder builder) {
        StringBuilder sb = new StringBuilder();
        for (SseEmitter.DataWithMediaType part : builder.build()) {
            appendPiece(sb, part.getData());
        }
        return sb.toString();
    }

    private static void appendPiece(StringBuilder sb, Object data) {
        if (data instanceof SseEmitter.SseEventBuilder inner) {
            for (SseEmitter.DataWithMediaType part : inner.build()) {
                appendPiece(sb, part.getData());
            }
        } else {
            sb.append(data);
        }
    }
}
