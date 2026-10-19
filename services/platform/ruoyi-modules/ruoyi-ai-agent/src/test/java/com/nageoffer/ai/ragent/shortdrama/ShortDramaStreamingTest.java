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

package com.nageoffer.ai.ragent.shortdrama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.shortdrama.controller.ShortDramaDtos;
import com.nageoffer.ai.ragent.shortdrama.llm.DramaLlmClient;
import com.nageoffer.ai.ragent.shortdrama.service.DramaActor;
import com.nageoffer.ai.ragent.shortdrama.service.DramaIdempotencyStore;
import com.nageoffer.ai.ragent.shortdrama.service.ShortDramaProjectService;
import com.nageoffer.ai.ragent.shortdrama.service.ShortDramaStreamService;
import com.nageoffer.ai.ragent.shortdrama.service.ShortDramaWriteService;
import com.nageoffer.ai.ragent.shortdrama.service.StreamSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F21:0 流式契约红/绿判据：阶段/增量/完成帧、失败不落库、断流释放、幂等重放。
 */
@Tag("dev")
class ShortDramaStreamingTest {

    private static final String PAYLOAD = "{\"projectName\":\"逆风\",\"description\":\"简介\","
            + "\"scriptName\":\"第一稿\",\"tone\":\"热血\",\"outlineText\":\"大纲\"}\n"
            + "===SCRIPT===\n内景 训练馆 白天\n角色：「开始」";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ShortDramaWriteService writeService = mock(ShortDramaWriteService.class);
    private final ShortDramaProjectService projectService = mock(ShortDramaProjectService.class);
    private final DramaIdempotencyStore idempotency = new DramaIdempotencyStore();
    private final DramaActor actor = DramaTestSupport.actor();

    private ShortDramaStreamService serviceWith(LLMService llm) {
        return new ShortDramaStreamService(new DramaLlmClient(llm, mapper), writeService,
                projectService, idempotency, mapper);
    }

    @Test
    @DisplayName("成功：阶段帧 + 增量帧 + complete，项目只落库一次")
    void streamsPhasesAndPersistsOnce() {
        FakeLlm llm = new FakeLlm(PAYLOAD, null);
        RecordingSink sink = new RecordingSink();
        when(writeService.createProjectWithScript(eq(actor), any(), any()))
                .thenReturn(new ShortDramaWriteService.CreatedIds(10, 20));

        serviceWith(llm).streamCreateFromIdea(actor,
                new ShortDramaDtos.CreateFromIdeaRequest("一个创意", "逆风", null, null, null), sink);

        assertThat(llm.streamCalls.get()).isEqualTo(1);
        assertThat(sink.events).anyMatch(e -> e.startsWith("phase:polish:running"));
        assertThat(sink.events).anyMatch(e -> e.startsWith("phase:outline:done"));
        String streamed = sink.events.stream()
                .filter(e -> e.startsWith("stream:script:"))
                .map(e -> e.substring("stream:script:".length()))
                .collect(java.util.stream.Collectors.joining());
        assertThat(streamed)
                .as("增量帧必须无丢失/无重复地拼回正文（分隔符不得作为正文推送）")
                .isEqualTo("内景 训练馆 白天\n角色：「开始」");
        assertThat(sink.events).anyMatch(e -> e.startsWith("complete:"));
        assertThat(sink.events.get(sink.events.size() - 1)).contains("10");
        verify(writeService).createProjectWithScript(eq(actor), any(), any());
    }

    @Test
    @DisplayName("失败：error 帧，不落库，幂等登记释放")
    void errorDoesNotPersist() {
        FakeLlm llm = new FakeLlm(null, new RuntimeException("provider down"));
        RecordingSink sink = new RecordingSink();

        serviceWith(llm).streamCreateFromIdea(actor,
                new ShortDramaDtos.CreateFromIdeaRequest("一个创意", null, null, null, "key-1"), sink);

        assertThat(sink.events).anyMatch(e -> e.startsWith("error:"));
        verify(writeService, never()).createProjectWithScript(any(), any(), any());
        assertThat(idempotency.completed(actor, "key-1")).isEmpty();
        assertThat(idempotency.size()).isZero();
    }

    @Test
    @DisplayName("幂等重放：已完成 key 不再调用模型，直接 complete 既有项目")
    void idempotentReplaySkipsModel() {
        idempotency.complete(actor, "key-1", 10);
        when(projectService.getProject(actor, 10)).thenReturn(new ShortDramaDtos.ProjectDetailView(
                new ShortDramaDtos.ProjectView(10L, "逆风", null, "draft",
                        "realistic", "9:16", null, null),
                new ShortDramaDtos.ScriptView(20L, 10L, "第一稿", "正文",
                        null, null, "ai", null, null),
                List.of(), List.of(), List.of(), 0, 0, 0));
        FakeLlm llm = new FakeLlm(PAYLOAD, null);
        RecordingSink sink = new RecordingSink();

        serviceWith(llm).streamCreateFromIdea(actor,
                new ShortDramaDtos.CreateFromIdeaRequest("一个创意", null, null, null, "key-1"), sink);

        assertThat(llm.streamCalls.get()).isZero();
        assertThat(sink.events).singleElement().asString().startsWith("complete:");
    }

    @Test
    @DisplayName("输入校验：空创意 → 400，不调用模型")
    void blankIdeaIsRejected() {
        FakeLlm llm = new FakeLlm(PAYLOAD, null);
        RecordingSink sink = new RecordingSink();
        assertThatThrownBy(() -> serviceWith(llm).streamCreateFromIdea(actor,
                new ShortDramaDtos.CreateFromIdeaRequest("  ", null, null, null, null), sink))
                .isInstanceOf(P04AiException.class)
                .extracting(e -> ((P04AiException) e).errorCode())
                .isEqualTo(P04AiErrorCode.BAD_REQUEST);
        assertThat(llm.streamCalls.get()).isZero();
    }

    /** 同步触发回调的假 LLM；用于把 SSE 语义变成可断言的记录。 */
    private static final class FakeLlm implements LLMService {
        private final String payload;
        private final RuntimeException error;
        private final AtomicInteger streamCalls = new AtomicInteger();

        private FakeLlm(String payload, RuntimeException error) {
            this.payload = payload;
            this.error = error;
        }

        @Override
        public String chat(ChatRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String chat(ChatRequest request, Tier tier) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String chat(ChatRequest request, Tier tier, String preferredModelId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback) {
            streamCalls.incrementAndGet();
            if (error != null) {
                callback.onError(error);
                return () -> {
                };
            }
            int split = payload.indexOf("===SCRIPT===") + "===SCRIPT===".length() + 6;
            callback.onContent(payload.substring(0, split));
            callback.onContent(payload.substring(split));
            callback.onComplete();
            return () -> {
            };
        }
    }

    private static final class RecordingSink implements StreamSink {
        private final List<String> events = new ArrayList<>();

        @Override
        public void phase(String phase, String status, String message) {
            events.add("phase:" + phase + ":" + status);
        }

        @Override
        public void stream(String phase, String text) {
            events.add("stream:" + phase + ":" + text);
        }

        @Override
        public void complete(String json) {
            events.add("complete:" + json);
        }

        @Override
        public void error(String json) {
            events.add("error:" + json);
        }

        @Override
        public boolean isClosed() {
            return false;
        }
    }
}
