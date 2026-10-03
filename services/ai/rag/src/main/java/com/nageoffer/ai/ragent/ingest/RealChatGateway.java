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

package com.nageoffer.ai.ragent.ingest;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 真实问答模型网关（{@code p2.chat.mode=real}，默认）。
 *
 * <p><b>真实模型 ID / 受控 key / 外发范围与预算尚未确认（输入 I2–I4）</b>：本类按既有
 * {@link LLMService} 路由调用；提供方 requestId/usage 当前不可得时按合同记
 * {@code PENDING_RECONCILIATION}（不按 0 结算）。I2–I4 确认前不得用于真实签收。
 */
@Component
@ConditionalOnProperty(name = "p2.chat.mode", havingValue = "real", matchIfMissing = true)
public class RealChatGateway implements ChatGateway {

    private final LLMService llmService;
    private final String modelId;

    public RealChatGateway(LLMService llmService, @Value("${agent.chat.model:deepseek-flash}") String modelId) {
        this.llmService = llmService;
        this.modelId = modelId;
    }

    @Override
    public String provider() {
        return "configured-provider";
    }

    @Override
    public String model() {
        return modelId;
    }

    @Override
    public ChatResult stream(List<ChatMessage> messages, int maxTokens, Consumer<String> onDelta) {
        CountDownLatch done = new CountDownLatch(1);
        StringBuilder content = new StringBuilder();
        AtomicReference<Throwable> error = new AtomicReference<>();
        ChatRequest request = ChatRequest.builder()
                .messages(messages)
                .maxTokens(maxTokens > 0 ? maxTokens : null)
                .build();
        llmService.streamChat(request, new StreamCallback() {
            @Override
            public void onContent(String chunk) {
                if (chunk == null || chunk.isEmpty()) {
                    return;
                }
                content.append(chunk);
                onDelta.accept(chunk);
            }

            @Override
            public void onComplete() {
                done.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                error.set(throwable);
                done.countDown();
            }
        });
        try {
            if (!done.await(600, TimeUnit.SECONDS)) {
                throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE, "model stream timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE, "model stream interrupted");
        }
        if (error.get() != null) {
            throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE, "model stream failed");
        }
        // 提供方 requestId/usage 未在现有接口暴露：按合同保持待核对，不伪造结算
        return new ChatResult(content.toString(), null, null, "stop");
    }
}
