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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 显式合成问答 test double（{@code p2.chat.mode=synthetic}，仅专属测试）。
 *
 * <p>确定性输出（引用上下文中出现的 marker），用于验证检索→引用→流式事件→结算
 * 链路；<b>不是真实模型签收</b>（真实签收需输入 I2–I4）。usage 标记为 synthetic，
 * 与真实调用分开记账。
 */
@Component
@ConditionalOnProperty(name = "p2.chat.mode", havingValue = "synthetic")
public class SyntheticChatGateway implements ChatGateway {

    private final long deltaDelayMs;

    public SyntheticChatGateway(@Value("${p2.chat.synthetic-delta-delay-ms:0}") long deltaDelayMs) {
        this.deltaDelayMs = deltaDelayMs;
    }

    @Override
    public String provider() {
        return "synthetic";
    }

    @Override
    public String model() {
        return "synthetic-echo";
    }

    @Override
    public ChatResult stream(List<ChatMessage> messages, int maxTokens, Consumer<String> onDelta) {
        String userText = "";
        StringBuilder context = new StringBuilder();
        for (ChatMessage message : messages) {
            if (message.getRole() == ChatMessage.Role.USER) {
                userText = message.getContent() == null ? "" : message.getContent();
            }
            if (message.getRole() == ChatMessage.Role.SYSTEM && message.getContent() != null) {
                context.append(message.getContent());
            }
        }
        String answer = compose(userText, context.toString());
        int chunkSize = 16;
        for (int start = 0; start < answer.length(); start += chunkSize) {
            String chunk = answer.substring(start, Math.min(answer.length(), start + chunkSize));
            onDelta.accept(chunk);
            if (deltaDelayMs > 0) {
                try {
                    Thread.sleep(deltaDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("synthetic", true);
        usage.put("promptChars", userText.length());
        usage.put("completionChars", answer.length());
        return new ChatResult(answer, "synthetic-req-" + java.util.UUID.randomUUID(), usage, "stop");
    }

    private String compose(String question, String context) {
        StringBuilder answer = new StringBuilder();
        answer.append("[synthetic-answer] ");
        if (context.contains("P2-MARKER-XYZZY")) {
            answer.append("the marker code is P2-MARKER-XYZZY. ");
        }
        if (context.contains("Marker-Beta-2026")) {
            answer.append("page two anchor Marker-Beta-2026 was retrieved. ");
        }
        answer.append("question=\"").append(question).append("\" ");
        if (context.isBlank()) {
            answer.append("no retrieved evidence; evidence insufficient.");
        }
        return answer.toString();
    }
}
