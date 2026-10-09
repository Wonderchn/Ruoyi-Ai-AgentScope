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

package com.nageoffer.ai.ragent.rag.rewrite;

import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.core.rewrite.MultiQuestionRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryTermMappingService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Exercises actual parsing and rule splitting, never the provider's language capabilities. */
class MultiQuestionRewriteBehaviorTest {
    private static final String QUESTION = "你好呀，淘宝和天猫数据安全怎么做的？";
    private static final String NORMALIZED = "淘宝和天猫数据安全怎么做的？";
    private final LLMService llm = mock(LLMService.class);
    private final QueryTermMappingService terms = mock(QueryTermMappingService.class);
    private final PromptTemplateLoader prompts = mock(PromptTemplateLoader.class);

    @Test
    void parsesRewriteAndPreservesBothParallelSubjects() {
        RewriteResult result = rewrite("""
                {"rewrite":" 淘宝和天猫的数据安全措施？ ",
                 "sub_questions":[" 淘宝的数据安全措施？ "," 天猫的数据安全措施？ "]}
                """);

        // Preserve every effective assertion from the deprecated provider test.
        assertThat(result).isNotNull();
        assertThat(result.rewrittenQuestion()).isNotNull().isNotBlank()
                .isEqualTo("淘宝和天猫的数据安全措施？");
        assertThat(result.subQuestions()).isNotNull().isNotEmpty()
                .allSatisfy(sub -> assertThat(sub).isNotNull().isNotBlank())
                .containsExactly("淘宝的数据安全措施？", "天猫的数据安全措施？");
        assertThat(result.subQuestions()).anySatisfy(sub -> assertThat(sub).contains("淘宝"))
                .anySatisfy(sub -> assertThat(sub).contains("天猫"));
    }

    @Test
    void stripsCodeFenceAndFiltersNonStringOrBlankSubQuestions() {
        RewriteResult result = rewrite("""
                ```json
                {"rewrite":" 数据安全措施？ ",
                 "sub_questions":[null,12,true,{},[]," "," 淘宝安全？ ","天猫安全？"]}
                ```
                """);

        assertThat(result.rewrittenQuestion()).isEqualTo("数据安全措施？");
        assertThat(result.subQuestions()).containsExactly("淘宝安全？", "天猫安全？");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"rewrite\":\" 安全措施？ \"}",
            "{\"rewrite\":\" 安全措施？ \",\"sub_questions\":[]}",
            "{\"rewrite\":\" 安全措施？ \",\"sub_questions\":[null,42,\" \"]}",
            "{\"rewrite\":\" 安全措施？ \",\"sub_questions\":\"wrong shape\"}"
    })
    void usesRewriteAsSingleSubQuestionWhenNoUsableSubQuestions(String raw) {
        RewriteResult result = rewrite(raw);

        assertThat(result.rewrittenQuestion()).isEqualTo("安全措施？");
        assertThat(result.subQuestions()).containsExactly("安全措施？");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "not json", "[]", "null", "{}",
            "{\"rewrite\":\" \"}", "{\"rewrite\":null}", "{\"rewrite\":{}}"})
    void fallsBackToNormalizedQuestionForUnusableResponse(String raw) {
        RewriteResult result = rewrite(raw);

        assertThat(result.rewrittenQuestion()).isEqualTo(NORMALIZED);
        assertThat(result.subQuestions()).containsExactly(NORMALIZED);
    }

    @Test
    void providerExceptionFallsBackWithoutTryingAnotherTier() {
        when(terms.normalize(QUESTION)).thenReturn(NORMALIZED);
        when(llm.chat(any(ChatRequest.class), eq(Tier.FAST)))
                .thenThrow(new IllegalStateException("provider unavailable"));

        RewriteResult result = service(true).rewriteWithSplit(QUESTION);

        assertThat(result.rewrittenQuestion()).isEqualTo(NORMALIZED);
        assertThat(result.subQuestions()).containsExactly(NORMALIZED);
        verify(llm).chat(any(ChatRequest.class), eq(Tier.FAST));
        org.mockito.Mockito.verifyNoMoreInteractions(llm);
    }

    @Test
    void disabledRewriteSplitsNormalizedQuestionAtCommonDelimitersWithoutProvider() {
        when(terms.normalize(QUESTION)).thenReturn(" 淘宝安全? 天猫安全？。；;\n Redis地址 ");

        RewriteResult result = service(false).rewriteWithSplit(QUESTION);

        assertThat(result.rewrittenQuestion()).isEqualTo(" 淘宝安全? 天猫安全？。；;\n Redis地址 ");
        assertThat(result.subQuestions()).containsExactly("淘宝安全？", "天猫安全？", "Redis地址？");
        verify(terms).normalize(QUESTION);
        verifyNoInteractions(llm, prompts);
    }

    @Test
    void disabledRewriteKeepsQuestionWhenNoNonBlankSplitRemains() {
        when(terms.normalize(QUESTION)).thenReturn("？；");

        RewriteResult result = service(false).rewriteWithSplit(QUESTION);

        assertThat(result.rewrittenQuestion()).isEqualTo("？；");
        assertThat(result.subQuestions()).containsExactly("？；");
        verifyNoInteractions(llm, prompts);
    }

    private RewriteResult rewrite(String raw) {
        when(terms.normalize(QUESTION)).thenReturn(NORMALIZED);
        when(prompts.load(anyString())).thenReturn("rewrite prompt");
        when(llm.chat(any(ChatRequest.class), eq(Tier.FAST))).thenReturn(raw);
        RewriteResult result = service(true).rewriteWithSplit(QUESTION, List.of());
        verify(llm).chat(any(ChatRequest.class), eq(Tier.FAST));
        return result;
    }

    private MultiQuestionRewriteService service(boolean enabled) {
        RAGConfigProperties config = new RAGConfigProperties();
        config.setQueryRewriteEnabled(enabled);
        return new MultiQuestionRewriteService(llm, config, terms, prompts);
    }
}
