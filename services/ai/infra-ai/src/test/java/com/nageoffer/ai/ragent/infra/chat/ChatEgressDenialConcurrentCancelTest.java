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

package com.nageoffer.ai.ragent.infra.chat;

import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.trace.RagStreamTraceSupport;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.model.ModelHealthStore;
import com.nageoffer.ai.ragent.infra.model.ModelRoutingExecutor;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-C2-8 判据：安全拒绝不得被并发取消吞掉。
 * <p>
 * 竞态以可达状态确定性构造：worker 线程在 beforeSend() 抛 {@link ChatEgressDeniedException}
 * 之前先经取消句柄把 cancelled 置位，等价于"用户取消恰在拒绝之后落地"的窗口。
 * 基线行为：{@code doStream} 的 catch 守卫 {@code !cancelled.get()} 为假 ⇒ 拒绝被吞 ⇒
 * 桥接探针超时 ⇒ 路由层按普通失败处理（记 provider 健康失败 + 触发下一候选）。
 * 修复后端态：拒绝到达路由层 {@code find(e)} 守卫 ⇒ 终止、不换候选、不记健康失败。
 */
class ChatEgressDenialConcurrentCancelTest {

    @Test
    void denialUnderConcurrentCancelReachesRoutingTerminalWithoutFallback() throws Exception {
        ModelSelector selector = mock(ModelSelector.class);
        ModelHealthStore health = mock(ModelHealthStore.class);
        ModelTarget first = target("first", "deepseek", 500L);
        ModelTarget second = target("second", "bailian", 500L);
        when(selector.selectChatCandidates(false)).thenReturn(List.of(first, second));
        ModelHealthStore.CallPermit permit = new ModelHealthStore.CallPermit(first.id(), 0L);
        when(health.allowCall(first.id())).thenReturn(permit);
        when(health.allowCall(second.id())).thenReturn(new ModelHealthStore.CallPermit(second.id(), 0L));

        List<Runnable> jobs = new CopyOnWriteArrayList<>();
        ScriptedPort port = new ScriptedPort();
        port.cancelInsideBeforeSend = true;
        port.beforeSendThrow = new ChatEgressDeniedException("denied-after-cancel");
        HandleCapturingClient primary = new HandleCapturingClient(jobs::add, port);

        AtomicInteger fallbackCalls = new AtomicInteger();
        ChatClient alternate = new ChatClient() {
            @Override
            public String provider() {
                return "bailian";
            }

            @Override
            public String chat(ChatRequest request, ModelTarget target) {
                throw new UnsupportedOperationException();
            }

            @Override
            public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
                fallbackCalls.incrementAndGet();
                callback.onContent("fallback");
                return StreamCancellationHandles.noop();
            }
        };

        RoutingLLMService routing = new RoutingLLMService(
                selector, health, new ModelRoutingExecutor(health), new LlmFirstPacketProbe(), List.of(primary, alternate));

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                routing.streamChat(request(512), silentCallback());
            } catch (Throwable t) {
                thrown.set(t);
            }
        }, "routing-caller");
        caller.start();

        assertThat(primary.submitted.await(5, TimeUnit.SECONDS)).as("worker submitted").isTrue();
        assertThat(jobs).hasSize(1);
        // worker：取消先置位，随后 beforeSend 抛拒绝（竞态可达状态，与线程调度无关）
        jobs.get(0).run();
        caller.join(5000);
        assertThat(caller.isAlive()).as("routing caller finished").isFalse();

        assertThat(port.beforeSends).hasValue(1);
        assertThat(primary.sends).as("拒绝零出站").hasValue(0);
        assertThat(fallbackCalls).as("不触发下一候选").hasValue(0);
        verify(health, times(1)).allowCall(first.id());
        verify(health, never()).allowCall(second.id());
        verify(health, never()).markFailure(first.id());
        verify(health, times(1)).releaseHalfOpenPermit(permit);
        assertThat(thrown.get())
                .as("拒绝必须到达终止语义（find(e) 命中并上抛）")
                .isInstanceOf(ChatEgressDeniedException.class)
                .hasMessage("denied-after-cancel");
    }

    @Test
    void cancelLandedWithoutDenialKeepsWorkerSilent() {
        List<Runnable> jobs = new CopyOnWriteArrayList<>();
        ScriptedPort port = new ScriptedPort();
        FixtureClient client = new FixtureClient(jobs::add, port);
        RecordingCallback callback = new RecordingCallback();

        StreamCancellationHandle handle = client.streamChat(request(512), callback, target("deepseek", "deepseek", null));
        port.handleRef.set(handle);
        port.cancelInsideBeforeSend = true;
        assertThat(jobs).hasSize(1);
        jobs.get(0).run();

        assertThat(callback.errors).isEmpty();
        assertThat(callback.completed).hasValue(0);
        assertThat(callback.contents).isEmpty();
        assertThat(client.sends).hasValue(0);
        assertThat(port.beforeSends).hasValue(1);
    }

    @Test
    void ordinaryErrorWithoutCancelStillReportsExactlyOnce() {
        List<Runnable> jobs = new CopyOnWriteArrayList<>();
        ScriptedPort port = new ScriptedPort();
        port.beforeSendThrow = new IllegalStateException("boom");
        FixtureClient client = new FixtureClient(jobs::add, port);
        RecordingCallback callback = new RecordingCallback();

        client.streamChat(request(512), callback, target("deepseek", "deepseek", null));
        assertThat(jobs).hasSize(1);
        jobs.get(0).run();

        assertThat(callback.errors).hasSize(1);
        assertThat(callback.errors.get(0)).isInstanceOf(IllegalStateException.class).hasMessage("boom");
        assertThat(callback.completed).hasValue(0);
        assertThat(client.sends).hasValue(0);
    }

    @Test
    void denialWithoutCancelStillReportsExactlyOnce() {
        List<Runnable> jobs = new CopyOnWriteArrayList<>();
        ScriptedPort port = new ScriptedPort();
        port.beforeSendThrow = new ChatEgressDeniedException("denied");
        FixtureClient client = new FixtureClient(jobs::add, port);
        RecordingCallback callback = new RecordingCallback();

        client.streamChat(request(512), callback, target("deepseek", "deepseek", null));
        assertThat(jobs).hasSize(1);
        jobs.get(0).run();

        assertThat(callback.errors).hasSize(1);
        assertThat(callback.errors.get(0)).isInstanceOf(ChatEgressDeniedException.class).hasMessage("denied");
        assertThat(callback.completed).hasValue(0);
        assertThat(client.sends).hasValue(0);
    }

    // ==================== 夹具 ====================

    static ChatRequest request(Integer max) {
        return ChatRequest.builder().maxTokens(max).build();
    }

    static ModelTarget target(String id, String provider, Long timeoutMs) {
        AIModelProperties.ModelCandidate model = new AIModelProperties.ModelCandidate();
        model.setProvider(provider);
        model.setModel("approved-model");
        AIModelProperties.ProviderConfig cfg = new AIModelProperties.ProviderConfig();
        cfg.setApiKey("test-only");
        cfg.setUrl("https://api.deepseek.com");
        cfg.setEndpoints(Map.of("chat", "/chat/completions"));
        return new ModelTarget(id, model, cfg, timeoutMs);
    }

    static StreamCallback silentCallback() {
        return new StreamCallback() {
            @Override
            public void onContent(String content) {
            }

            @Override
            public void onComplete() {
            }

            @Override
            public void onError(Throwable error) {
            }
        };
    }

    static final class RecordingCallback implements StreamCallback {
        final List<String> contents = new CopyOnWriteArrayList<>();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final AtomicInteger completed = new AtomicInteger();

        @Override
        public void onContent(String content) {
            contents.add(content);
        }

        @Override
        public void onComplete() {
            completed.incrementAndGet();
        }

        @Override
        public void onError(Throwable error) {
            errors.add(error);
        }
    }

    /** 端口：beforeSend 先执行取消（可开关），再抛预设异常（可开关）。 */
    static final class ScriptedPort implements ChatEgressPort {
        final AtomicInteger authorizes = new AtomicInteger();
        final AtomicInteger beforeSends = new AtomicInteger();
        final AtomicInteger receipts = new AtomicInteger();
        final AtomicReference<StreamCancellationHandle> handleRef = new AtomicReference<>();
        volatile boolean cancelInsideBeforeSend;
        volatile RuntimeException beforeSendThrow;

        @Override
        public Lease authorize(CallSpec call) {
            authorizes.incrementAndGet();
            return new Lease() {
                @Override
                public void beforeSend() {
                    beforeSends.incrementAndGet();
                    if (cancelInsideBeforeSend) {
                        Objects.requireNonNull(handleRef.get(), "handle must be captured before beforeSend").cancel();
                    }
                    if (beforeSendThrow != null) {
                        throw beforeSendThrow;
                    }
                }

                @Override
                public void received(String providerId, Map<String, Object> usage) {
                    receipts.incrementAndGet();
                }
            };
        }
    }

    static class FixtureClient extends DeepSeekChatClient {
        final ScriptedPort port;
        final AtomicInteger sends = new AtomicInteger();

        FixtureClient(Executor executor, ScriptedPort port) {
            this.port = port;
            OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
                sends.incrementAndGet();
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK").body(ResponseBody.create("{}", MediaType.get("application/json"))).build();
            }).build();
            ReflectionTestUtils.setField(this, "syncHttpClient", http);
            ReflectionTestUtils.setField(this, "streamingHttpClient", http);
            ReflectionTestUtils.setField(this, "modelStreamExecutor", executor);
            ReflectionTestUtils.setField(this, "streamTraceSupport", (RagStreamTraceSupport) (name, type) -> RagStreamTraceSupport.NOOP_SPAN);
            ReflectionTestUtils.setField(this, "chatEgressPort", port);
        }
    }

    /** 供路由层端到端用例：在 streamChat 返回句柄时把它暴露给端口（取消由 beforeSend 触发）。 */
    static final class HandleCapturingClient extends FixtureClient {
        final CountDownLatch submitted = new CountDownLatch(1);

        HandleCapturingClient(Executor executor, ScriptedPort port) {
            super(executor, port);
        }

        @Override
        public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
            StreamCancellationHandle handle = super.streamChat(request, callback, target);
            port.handleRef.set(handle);
            submitted.countDown();
            return handle;
        }
    }
}
