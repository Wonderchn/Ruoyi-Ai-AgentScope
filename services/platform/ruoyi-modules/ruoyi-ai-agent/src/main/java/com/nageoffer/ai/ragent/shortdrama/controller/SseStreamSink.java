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

package com.nageoffer.ai.ragent.shortdrama.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.shortdrama.service.StreamSink;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link StreamSink} 的 SSE 实现。
 *
 * <p>帧名与旧短剧面保持一致：{@code phase} / {@code stream} / {@code complete} /
 * {@code error}。客户端断开后不再写帧；下一段模型增量到达时流式服务看到
 * {@link #isClosed()} 会取消上游调用，避免继续消耗预算。
 */
final class SseStreamSink implements StreamSink {

    private final SseEmitter emitter;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    SseStreamSink(SseEmitter emitter, ObjectMapper objectMapper) {
        this.emitter = emitter;
        this.objectMapper = objectMapper;
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> {
            closed.set(true);
            completeQuietly();
        });
        emitter.onError(error -> closed.set(true));
    }

    @Override
    public void phase(String phase, String status, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phase", phase);
        data.put("status", status);
        data.put("message", message);
        send("phase", data);
    }

    @Override
    public void stream(String phase, String text) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phase", phase);
        data.put("text", text);
        send("stream", data);
    }

    @Override
    public void complete(String json) {
        sendRaw("complete", json);
        closed.set(true);
        completeQuietly();
    }

    @Override
    public void error(String json) {
        sendRaw("error", json);
        closed.set(true);
        completeQuietly();
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    private void send(String name, Map<String, Object> data) {
        try {
            sendRaw(name, objectMapper.writeValueAsString(data));
        } catch (JsonProcessingException e) {
            // 帧内容全部来自服务端固定字段，正常不会失败；失败时关闭流而不是写入半个 JSON。
            closed.set(true);
            completeQuietly();
        }
    }

    private void sendRaw(String name, String json) {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(name).data(json));
        } catch (IOException | IllegalStateException e) {
            closed.set(true);
            completeQuietly();
        }
    }

    private void completeQuietly() {
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
            // 客户端已经断开
        }
    }
}
