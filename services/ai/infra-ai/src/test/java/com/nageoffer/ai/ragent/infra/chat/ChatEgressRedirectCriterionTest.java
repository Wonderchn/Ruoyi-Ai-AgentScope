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
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F-C2-10 判据：被 canonical 批准的 Host A 返回 302 Location→Host B 时，客户端必须
 * 不跟随重定向（零连接、明确失败），而不是把请求带到未过 canonical 校验的第二 host。
 * <p>
 * 两个服务端全部绑定回环地址（127.0.0.1 临时端口），零外网、零 provider 凭据；
 * 同步与流式两条路径各一条判据。去掉 {@code .followRedirects(false).followSslRedirects(false)}
 * 时（OkHttp 默认跟随），本判据必须转红——复刻 MUT-C5 击杀。
 */
class ChatEgressRedirectCriterionTest {

    private static final String SYNC_BODY = "{\"id\":\"provider-b\",\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";

    @Test
    void syncCall302ToSecondHostIsNotFollowed() throws Exception {
        try (LoopbackServer unapproved = new LoopbackServer(false, null, SYNC_BODY);
             LoopbackServer approved = new LoopbackServer(true,
                     "http://127.0.0.1:" + unapproved.port() + "/chat/completions", null)) {
            RedirectHarness harness = new RedirectHarness();

            assertThatThrownBy(() -> harness.client.chat(request(512), targetAt(approved.port())))
                    .isInstanceOf(ModelClientException.class)
                    .hasMessageContaining("HTTP 302");
            assertThat(approved.connections.get()).isGreaterThanOrEqualTo(1);
            assertThat(unapproved.connections.get()).as("unapproved host must see zero connections").isZero();
        }
    }

    @Test
    void streamCall302ToSecondHostIsNotFollowed() throws Exception {
        try (LoopbackServer unapproved = new LoopbackServer(false, null, SYNC_BODY);
             LoopbackServer approved = new LoopbackServer(true,
                     "http://127.0.0.1:" + unapproved.port() + "/chat/completions", null)) {
            RedirectHarness harness = new RedirectHarness();

            harness.client.streamChat(request(512), harness.callback, targetAt(approved.port()));

            assertThat(harness.callback.errors).hasSize(1);
            assertThat(harness.callback.errors.get(0))
                    .isInstanceOf(ModelClientException.class)
                    .hasMessageContaining("HTTP 302");
            assertThat(harness.callback.completed).hasValue(0);
            assertThat(approved.connections.get()).isGreaterThanOrEqualTo(1);
            assertThat(unapproved.connections.get()).as("unapproved host must see zero connections").isZero();
        }
    }

    // ==================== 夹具 ====================

    static ChatRequest request(Integer max) {
        return ChatRequest.builder().maxTokens(max).build();
    }

    static ModelTarget targetAt(int port) {
        AIModelProperties.ModelCandidate model = new AIModelProperties.ModelCandidate();
        model.setProvider("deepseek");
        model.setModel("approved-model");
        AIModelProperties.ProviderConfig cfg = new AIModelProperties.ProviderConfig();
        cfg.setApiKey("test-only");
        cfg.setUrl("http://127.0.0.1:" + port);
        cfg.setEndpoints(Map.of("chat", "/chat/completions"));
        return new ModelTarget("test", model, cfg, null);
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

    static final class RedirectHarness {
        final DeepSeekChatClient client = new DeepSeekChatClient();
        final RecordingCallback callback = new RecordingCallback();

        RedirectHarness() {
            OkHttpClient http = new OkHttpClient.Builder()
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .callTimeout(10, TimeUnit.SECONDS)
                    .build();
            ReflectionTestUtils.setField(client, "syncHttpClient", http);
            ReflectionTestUtils.setField(client, "streamingHttpClient", http);
            ReflectionTestUtils.setField(client, "modelStreamExecutor", (Executor) Runnable::run);
            ReflectionTestUtils.setField(client, "streamTraceSupport", (RagStreamTraceSupport) (name, type) -> RagStreamTraceSupport.NOOP_SPAN);
            ReflectionTestUtils.setField(client, "chatEgressPort", (ChatEgressPort) call -> new ChatEgressPort.Lease() {
                @Override
                public void beforeSend() {
                }

                @Override
                public void received(String providerId, Map<String, Object> usage) {
                }
            });
        }
    }

    /** 极简回环 HTTP 服务端：可回 302（带 Location），也可回 200（带 JSON 体）。 */
    static final class LoopbackServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final boolean redirect;
        private final String location;
        private final String body;
        final AtomicInteger connections = new AtomicInteger();
        private volatile boolean running = true;
        private final Thread thread;

        LoopbackServer(boolean redirect, String location, String body) throws IOException {
            this.redirect = redirect;
            this.location = location;
            this.body = body;
            this.serverSocket = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
            this.thread = new Thread(this::serve, "loopback-http-" + serverSocket.getLocalPort());
            this.thread.setDaemon(true);
            this.thread.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        private void serve() {
            while (running) {
                try (Socket socket = serverSocket.accept()) {
                    connections.incrementAndGet();
                    socket.setSoTimeout(5000);
                    consumeRequest(socket.getInputStream());
                    OutputStream out = socket.getOutputStream();
                    if (redirect) {
                        out.write(("HTTP/1.1 302 Found\r\nLocation: " + location
                                + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                    } else {
                        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                                + payload.length + "\r\nConnection: close\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                        out.write(payload);
                    }
                    out.flush();
                } catch (IOException ignored) {
                    // 关闭或读超时：单连接失败不应终结 accept 循环
                }
            }
        }

        @Override
        public void close() {
            running = false;
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private static void consumeRequest(InputStream in) throws IOException {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int state = 0;
            while (state < 4) {
                int b = in.read();
                if (b == -1) {
                    return;
                }
                head.write(b);
                state = switch (state) {
                    case 0 -> b == '\r' ? 1 : 0;
                    case 1 -> b == '\n' ? 2 : (b == '\r' ? 1 : 0);
                    case 2 -> b == '\r' ? 3 : 0;
                    default -> b == '\n' ? 4 : 0;
                };
            }
            String headText = head.toString(StandardCharsets.ISO_8859_1);
            int contentLength = 0;
            for (String line : headText.split("\r\n")) {
                if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
            }
            byte[] buffer = new byte[8192];
            int remaining = contentLength;
            while (remaining > 0) {
                int read = in.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read == -1) {
                    return;
                }
                remaining -= read;
            }
        }
    }
}
