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
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ENGINE-CHAT-ACCOUNTING 切片判据：引擎外发必须过 egress+预占+usage 结算信封；
 * 拒绝即不出站（fail-closed）；完成/错误/取消恰结算一次。
 *
 * <p>R17-B2 生命周期补充判据：未订阅零调用；每次订阅独立预占与结算门；
 * delegate.stream 同步抛错在订阅内 onError；execute 普通运行异常先记账再重抛；
 * 失败路径结算异常以 suppressed 保存原异常（不吞不换主）。
 */
@Tag("dev")
class MeteredEngineModelTransportTest {

    private final HttpTransport delegate = mock(HttpTransport.class);
    private final EgressPolicy egress = mock(EgressPolicy.class);
    private final ProviderSpendEnvelope spend = mock(ProviderSpendEnvelope.class);
    private final AtomicInteger delegateStreamCalls = new AtomicInteger();

    private MeteredEngineModelTransport transport() {
        return new MeteredEngineModelTransport(delegate, egress, spend, "deepseek", "deepseek-flash");
    }

    private static HttpRequest request() {
        return HttpRequest.builder().url("https://api.deepseek.com/chat/completions").method("POST")
                .body("{\"model\":\"deepseek-flash\",\"max_tokens\":64,\"stream\":true}").build();
    }

    /** 只有真实调用才计数：stubbing 自身不执行 Answer。 */
    private void answerStreamWith(Flux<String> flux) {
        when(delegate.stream(any())).thenAnswer(invocation -> {
            delegateStreamCalls.incrementAndGet();
            return flux;
        });
    }

    @Test
    @DisplayName("流式：预占带嗅探 max_tokens；完成后按末个 usage 与首个 id 恰结算一次")
    void streamReservesAndSettlesWithSniffedUsage() {
        when(spend.reserve(eq("deepseek"), eq("deepseek-flash"), anyLong(), anyInt())).thenReturn("spend-1");
        answerStreamWith(Flux.just(
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
        answerStreamWith(Flux.error(new RuntimeException("connection reset")));

        assertThatThrownBy(() -> transport().stream(request()).collectList().block())
                .hasMessageContaining("connection reset");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> usage = ArgumentCaptor.forClass(Map.class);
        verify(spend).received(eq("spend-3"), eq(null), usage.capture());
        assertThat(usage.getValue().get("status")).asString().contains("error");
    }

    @Test
    @DisplayName("未订阅：零 egress 校验/零预占/零 delegate 调用（构造期也不触 provider）")
    void streamWithoutSubscriptionNeverTouchesProviderOrEnvelope() {
        Flux<String> flux = transport().stream(request());

        assertThat(flux).isNotNull();
        verifyNoInteractions(delegate, egress, spend);
        assertThat(delegateStreamCalls).hasValue(0);
    }

    @Test
    @DisplayName("delegate.stream 同步抛错：构造期不抛，订阅内 onError 且恰结算一次错误事实")
    void delegateStreamSynchronousThrowBecomesInStreamError() {
        when(spend.reserve(eq("deepseek"), eq("deepseek-flash"), anyLong(), anyInt())).thenReturn("spend-sync");
        when(delegate.stream(any())).thenThrow(new IllegalStateException("sync boom"));

        // 构造期不触 provider、不抛：错误必须在订阅内出现
        Flux<String> flux = transport().stream(request());

        assertThatThrownBy(() -> flux.collectList().block()).hasMessageContaining("sync boom");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> usage = ArgumentCaptor.forClass(Map.class);
        verify(spend).received(eq("spend-sync"), eq(null), usage.capture());
        assertThat(usage.getValue().get("status")).asString().contains("error");
    }

    @Test
    @DisplayName("两次订阅 = 两次真实调用：各自预占/回执，不共享一次 settled")
    void twoSubscriptionsAreIndependentCalls() {
        when(spend.reserve(eq("deepseek"), eq("deepseek-flash"), anyLong(), anyInt()))
                .thenReturn("spend-a", "spend-b");
        Deque<Flux<String>> responses = new ArrayDeque<>(List.of(
                Flux.just("data: {\"id\":\"chatcmpl-a\",\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2}}"),
                Flux.just("data: {\"id\":\"chatcmpl-b\",\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":4}}")));
        when(delegate.stream(any())).thenAnswer(invocation -> {
            delegateStreamCalls.incrementAndGet();
            return responses.poll();
        });

        Flux<String> flux = transport().stream(request());
        flux.collectList().block();
        flux.collectList().block();

        assertThat(delegateStreamCalls).hasValue(2);
        verify(egress, times(2)).requireAllowed("deepseek");
        verify(spend).received(eq("spend-a"), eq("chatcmpl-a"), any());
        verify(spend).received(eq("spend-b"), eq("chatcmpl-b"), any());
    }

    @Test
    @DisplayName("流中取消：同一订阅恰一次 received（cancelled，不退款/不补回）")
    void cancelMidStreamSettlesOnceWithoutRefund() {
        when(spend.reserve(eq("deepseek"), eq("deepseek-flash"), anyLong(), anyInt())).thenReturn("spend-cancel");
        answerStreamWith(Flux.just(
                "data: {\"id\":\"chatcmpl-c1\",\"choices\":[{\"delta\":{\"content\":\"x\"}}]}",
                "data: {\"id\":\"chatcmpl-c1\",\"choices\":[],\"usage\":{\"prompt_tokens\":9}}"));

        List<String> first = transport().stream(request()).take(1).collectList().block();

        assertThat(first).hasSize(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> usage = ArgumentCaptor.forClass(Map.class);
        verify(spend, times(1)).received(eq("spend-cancel"), eq("chatcmpl-c1"), usage.capture());
        assertThat(usage.getValue().get("status")).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("取消（未收到任何数据）：仍恰一次 received，状态 cancelled")
    void disposeWithoutDataSettlesOnceAsCancelled() {
        when(spend.reserve(eq("deepseek"), eq("deepseek-flash"), anyLong(), anyInt())).thenReturn("spend-never");
        answerStreamWith(Flux.never());

        Disposable subscription = transport().stream(request()).subscribe();
        subscription.dispose();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> usage = ArgumentCaptor.forClass(Map.class);
        verify(spend, times(1)).received(eq("spend-never"), eq(null), usage.capture());
        assertThat(usage.getValue().get("status")).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("execute 普通运行异常：先记错误/未知事实再重抛原异常")
    void executePlainRuntimeExceptionSettlesThenRethrows() {
        when(spend.reserve(eq("deepseek"), eq("deepseek-flash"), anyLong(), anyInt())).thenReturn("spend-e1");
        when(delegate.execute(any())).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> transport().execute(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> usage = ArgumentCaptor.forClass(Map.class);
        verify(spend).received(eq("spend-e1"), eq(null), usage.capture());
        assertThat(usage.getValue().get("status")).asString().contains("error");
    }

    @Test
    @DisplayName("execute HttpTransportException：原行为保留（transport-error 结算 + 原异常重抛）")
    void executeTransportExceptionKeepsTransportErrorStatus() {
        when(spend.reserve(eq("deepseek"), eq("deepseek-flash"), anyLong(), anyInt())).thenReturn("spend-e2");
        when(delegate.execute(any())).thenThrow(new HttpTransportException("upstream 500"));

        assertThatThrownBy(() -> transport().execute(request()))
                .isInstanceOf(HttpTransportException.class)
                .hasMessage("upstream 500");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> usage = ArgumentCaptor.forClass(Map.class);
        verify(spend).received(eq("spend-e2"), eq(null), usage.capture());
        assertThat(usage.getValue().get("status")).asString().contains("transport-error");
    }

    @Test
    @DisplayName("execute egress 拒绝：preflight 不进 delegate，零 delegate/零预占")
    void executeEgressDenialNeverReachesDelegate() {
        doThrow(new IllegalStateException("egress denied")).when(egress).requireAllowed(anyString());

        assertThatThrownBy(() -> transport().execute(request())).hasMessageContaining("egress denied");

        verify(delegate, never()).execute(any());
        verify(spend, never()).reserve(anyString(), anyString(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("execute 预占拒绝：不出站（delegate 零调用），异常原样上抛")
    void executeReserveDenialNeverReachesDelegate() {
        when(spend.reserve(anyString(), anyString(), anyLong(), anyInt()))
                .thenThrow(new IllegalStateException("reserve denied"));

        assertThatThrownBy(() -> transport().execute(request())).hasMessageContaining("reserve denied");

        verify(delegate, never()).execute(any());
    }

    @Test
    @DisplayName("stream 预占拒绝：订阅内 onError，delegate 零调用")
    void streamReserveDenialKeepsDelegateUntouched() {
        when(spend.reserve(anyString(), anyString(), anyLong(), anyInt()))
                .thenThrow(new IllegalStateException("reserve denied"));

        assertThatThrownBy(() -> transport().stream(request()).collectList().block())
                .hasMessageContaining("reserve denied");

        verify(delegate, never()).stream(any());
    }

    @Test
    @DisplayName("失败路径结算自身异常：原异常仍为主异常，结算异常以 suppressed 保存")
    void settleFailureOnExecuteFailurePathPreservesOriginalException() {
        when(spend.reserve(eq("deepseek"), eq("deepseek-flash"), anyLong(), anyInt())).thenReturn("spend-x");
        when(delegate.execute(any())).thenThrow(new IllegalStateException("boom"));
        doThrow(new IllegalStateException("settle failed")).when(spend).received(eq("spend-x"), any(), any());

        Throwable thrown = catchThrowable(() -> transport().execute(request()));

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage("boom");
        assertThat(thrown.getSuppressed()).extracting(Throwable::getMessage).containsExactly("settle failed");
    }

    @Test
    @DisplayName("stream 失败路径结算自身异常：原异常仍为主异常，结算异常 suppressed")
    void settleFailureOnStreamErrorPathPreservesOriginalException() {
        when(spend.reserve(eq("deepseek"), eq("deepseek-flash"), anyLong(), anyInt())).thenReturn("spend-y");
        answerStreamWith(Flux.error(new IllegalStateException("async boom")));
        doThrow(new IllegalStateException("settle failed")).when(spend).received(eq("spend-y"), any(), any());

        Throwable thrown = catchThrowable(() -> transport().stream(request()).collectList().block());

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage("async boom");
        // block() 会附加自带的 "#block terminated with an error" 标记，这里只断言结算异常被保留
        assertThat(thrown.getSuppressed()).extracting(Throwable::getMessage).contains("settle failed");
    }

    @Test
    @DisplayName("完成路径结算异常不静默成功：订阅以 onError 结束")
    void settleFailureOnStreamCompletionIsNotSilentSuccess() {
        when(spend.reserve(eq("deepseek"), eq("deepseek-flash"), anyLong(), anyInt())).thenReturn("spend-z");
        answerStreamWith(Flux.just("data: [DONE]"));
        doThrow(new IllegalStateException("settle failed")).when(spend).received(eq("spend-z"), any(), any());

        assertThatThrownBy(() -> transport().stream(request()).collectList().block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("settle failed");
    }

    @Test
    @DisplayName("无 usage 完成：按契约以 completed-without-usage 状态结算（不伪造数值）")
    void streamCompletionWithoutUsageSettlesWithContractStatus() {
        when(spend.reserve(eq("deepseek"), eq("deepseek-flash"), anyLong(), anyInt())).thenReturn("spend-noUsage");
        answerStreamWith(Flux.just("data: [DONE]"));

        transport().stream(request()).collectList().block();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> capture = ArgumentCaptor.forClass(Map.class);
        verify(spend).received(eq("spend-noUsage"), eq(null), capture.capture());
        assertThat(capture.getValue()).containsEntry("status", "completed-without-usage");
    }
}
