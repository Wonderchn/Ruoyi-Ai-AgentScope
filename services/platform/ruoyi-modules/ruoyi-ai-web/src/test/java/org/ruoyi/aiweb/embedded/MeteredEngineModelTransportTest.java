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

import com.nageoffer.ai.ragent.runtime.usage.EgressPolicy;
import com.nageoffer.ai.ragent.runtime.usage.ProviderSpendEnvelope;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ENGINE-CHAT-ACCOUNTING 切片判据：引擎外发必须过 egress+预占+usage 结算信封；
 * 拒绝即不出站（fail-closed）；完成/错误恰结算一次。
 */
@Tag("dev")
class MeteredEngineModelTransportTest {

    private final HttpTransport delegate = mock(HttpTransport.class);
    private final EgressPolicy egress = mock(EgressPolicy.class);
    private final ProviderSpendEnvelope spend = mock(ProviderSpendEnvelope.class);

    private MeteredEngineModelTransport transport() {
        return new MeteredEngineModelTransport(delegate, egress, spend, "deepseek", "deepseek-flash");
    }

    private static HttpRequest request() {
        return HttpRequest.builder().url("https://api.deepseek.com/chat/completions").method("POST")
                .body("{\"model\":\"deepseek-flash\",\"max_tokens\":64,\"stream\":true}").build();
    }

    @Test
    @DisplayName("流式：预占带嗅探 max_tokens；完成后按末个 usage 与首个 id 恰结算一次")
    void streamReservesAndSettlesWithSniffedUsage() {
        when(spend.reserve(eq("deepseek"), eq("deepseek-flash"), anyLong(), anyInt())).thenReturn("spend-1");
        when(delegate.stream(any())).thenReturn(Flux.just(
                "data: {\"id\":\"chatcmpl-1\",\"choices\":[{\"delta\":{\"content\":\"The\"}}]}",
                "data: {\"id\":\"chatcmpl-1\",\"choices\":[{\"delta\":{\"content\":\" user\"}}]}",
                "data: {\"id\":\"chatcmpl-1\",\"choices\":[],\"usage\":{\"prompt_tokens\":31,\"completion_tokens\":8}}",
                "data: [DONE]"));

        transport().stream(request()).collectList().block();

        verify(egress).requireAllowed("deepseek");
        verify(spend).reserve(eq("deepseek"), eq("deepseek-flash"), anyLong(), eq(64));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> usage = ArgumentCaptor.forClass(Map.class);
        verify(spend).received(eq("spend-1"), eq("chatcmpl-1"), usage.capture());
        assertThat(usage.getValue()).containsEntry("prompt_tokens", 31).containsEntry("completion_tokens", 8);
    }

    @Test
    @DisplayName("egress 拒绝：不出站（delegate 零调用），错误沿流上行")
    void egressDenialNeverReachesTheWire() {
        doThrow(new IllegalStateException("egress denied")).when(egress).requireAllowed(anyString());

        assertThatThrownBy(() -> transport().stream(request()).collectList().block())
                .hasMessageContaining("egress denied");

        verify(delegate, never()).stream(any());
        verify(spend, never()).reserve(anyString(), anyString(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("同步 execute：从响应体结算 usage 与 provider 请求号")
    void executeSettlesFromResponseBody() throws HttpTransportException {
        when(spend.reserve(eq("deepseek"), eq("deepseek-flash"), anyLong(), anyInt())).thenReturn("spend-2");
        when(delegate.execute(any())).thenReturn(HttpResponse.builder().statusCode(200)
                .body("{\"id\":\"chatcmpl-2\",\"choices\":[{\"message\":{\"content\":\"ok\"}}],"
                        + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":1}}").build());

        transport().execute(request());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> usage = ArgumentCaptor.forClass(Map.class);
        verify(spend).received(eq("spend-2"), eq("chatcmpl-2"), usage.capture());
        assertThat(usage.getValue()).containsEntry("prompt_tokens", 5);
    }

    @Test
    @DisplayName("错误路径：结算带状态说明（usage 缺失按契约不伪造数值）")
    void errorSettlesWithStatusNote() {
        when(spend.reserve(eq("deepseek"), eq("deepseek-flash"), anyLong(), anyInt())).thenReturn("spend-3");
        when(delegate.stream(any())).thenReturn(Flux.error(new RuntimeException("connection reset")));

        assertThatThrownBy(() -> transport().stream(request()).collectList().block())
                .hasMessageContaining("connection reset");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> usage = ArgumentCaptor.forClass(Map.class);
        verify(spend).received(eq("spend-3"), eq(null), usage.capture());
        assertThat(usage.getValue().get("status")).asString().contains("error");
    }
}
