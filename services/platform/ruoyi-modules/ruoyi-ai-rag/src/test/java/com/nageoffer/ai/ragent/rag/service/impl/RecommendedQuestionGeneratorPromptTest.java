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

package com.nageoffer.ai.ragent.rag.service.impl;

import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.convention.GroundingChunk;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptSlot;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateUtils;
import com.nageoffer.ai.ragent.rag.dto.RecommendedQuestionsPayload;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F09:2-D1 红相：{@code render()} 会剥离"未传值的必需占位符"，
 * 因此生成器必须把 {@code {chunks}} 的值放进 render 的槽位 map，
 * 不能依赖 render 之后的字符串替换（那时占位符已被剥离，替换是空操作）。
 */
@Tag("dev")
class RecommendedQuestionGeneratorPromptTest {

    private static final String TEMPLATE = "Q={question} A={answer} C={count} CH={chunks}";

    private final AgentPromptResolver resolver = mock(AgentPromptResolver.class);
    private final LLMService llmService = mock(LLMService.class);
    private final RecommendedQuestionGenerator generator =
            new RecommendedQuestionGenerator(resolver, llmService);

    @Test
    void groundingChunksReachTheModelPrompt() {
        stubRenderLikeTheRealResolver();
        when(llmService.chat(any(ChatRequest.class), eq(Tier.FAST)))
                .thenReturn("[\"one\", \"two\", \"three\"]");

        RecommendedQuestionsPayload payload = generator.generate(
                "question-text", "answer-text",
                List.of(new GroundingChunk("doc-a", "chunk-evidence-text")));

        assertThat(payload.questions()).containsExactly("one", "two", "three");
        String prompt = capturedPrompt();
        assertThat(prompt)
                .contains("Q=question-text", "A=answer-text", "C=3",
                        "CH=1. 【doc-a】chunk-evidence-text")
                .doesNotContain("{chunks}", "{question}", "{answer}", "{count}");
    }

    @Test
    void emptyChunksUseFallbackTextNotALiteralPlaceholder() {
        stubRenderLikeTheRealResolver();
        when(llmService.chat(any(ChatRequest.class), eq(Tier.FAST)))
                .thenReturn("[\"one\", \"two\", \"three\"]");

        generator.generate("q", "a", List.of());

        assertThat(capturedPrompt())
                .contains("CH=（无检索片段，仅依据问答生成）")
                .doesNotContain("{chunks}");
    }

    private void stubRenderLikeTheRealResolver() {
        when(resolver.render(eq(AgentPromptSlot.RECOMMENDED_QUESTIONS), anyMap()))
                .thenAnswer(call -> {
                    Map<String, String> slots = call.getArgument(1);
                    String filled = PromptTemplateUtils.fillSlots(TEMPLATE, slots);
                    // AgentPromptResolver#render 的真实语义：必需占位符未传值即剥离
                    for (String placeholder : AgentPromptSlot.RECOMMENDED_QUESTIONS.getRequiredPlaceholders()) {
                        String key = placeholder.substring(1, placeholder.length() - 1);
                        if (!slots.containsKey(key)) {
                            filled = filled.replace(placeholder, "");
                        }
                    }
                    return PromptTemplateUtils.cleanupPrompt(filled);
                });
    }

    private String capturedPrompt() {
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmService).chat(captor.capture(), eq(Tier.FAST));
        return captor.getValue().getMessages().get(0).getContent();
    }
}
