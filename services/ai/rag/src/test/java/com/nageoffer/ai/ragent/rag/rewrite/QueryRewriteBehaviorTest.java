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

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.core.rewrite.MultiQuestionRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryTermMappingCacheManager;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryTermMappingService;
import com.nageoffer.ai.ragent.rag.dao.entity.QueryTermMappingDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.QueryTermMappingMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.QUERY_REWRITE_AND_SPLIT_PROMPT_PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Deterministic successors to QueryRewriteTests; the real-provider demo stays deprecated. */
class QueryRewriteBehaviorTest {
    private final LLMService llm = mock(LLMService.class);
    private final QueryTermMappingService terms = mock(QueryTermMappingService.class);
    private final PromptTemplateLoader prompts = mock(PromptTemplateLoader.class);

    @ParameterizedTest
    @ValueSource(strings = {
            "请帮我查询下直快赔数据安全文档",
            "你底层用的什么模型",
            "OA 系统主要提供哪些功能？测试环境 Redis 地址是多少？数据安全怎么做的？",
            "OA 系统和保险系统主要提供哪些功能？数据安全怎么做的？"
    })
    void rewriteReturnsParsedNonBlankResultAndSendsNormalizedQuestion(String question) {
        String normalized = "归一化：" + question;
        String rewritten = "检索：" + question;
        when(terms.normalize(question)).thenReturn(normalized);
        when(prompts.load(QUERY_REWRITE_AND_SPLIT_PROMPT_PATH)).thenReturn("rewrite system prompt");
        when(llm.chat(any(ChatRequest.class), eq(Tier.FAST)))
                .thenReturn("{\"rewrite\":\" " + rewritten + " \",\"sub_questions\":[]}");

        String result = service(true, terms).rewrite(question);

        // Preserve the old non-null/non-blank checks, with an exact parsing assertion.
        assertThat(result).isNotNull().isNotBlank().isEqualTo(rewritten);
        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llm).chat(request.capture(), eq(Tier.FAST));
        assertThat(request.getValue().getMessages()).hasSize(2);
        assertThat(request.getValue().getMessages().get(0).getRole()).isEqualTo(ChatMessage.Role.SYSTEM);
        assertThat(request.getValue().getMessages().get(0).getContent()).isEqualTo("rewrite system prompt");
        assertThat(request.getValue().getMessages().get(1).getRole()).isEqualTo(ChatMessage.Role.USER);
        assertThat(request.getValue().getMessages().get(1).getContent()).isEqualTo(normalized);
        assertThat(request.getValue().getTemperature()).isEqualTo(0.1D);
        assertThat(request.getValue().getTopP()).isEqualTo(0.3D);
        assertThat(request.getValue().getThinking()).isFalse();
        verify(prompts).load(QUERY_REWRITE_AND_SPLIT_PROMPT_PATH);
        verify(terms).normalize(question);
    }

    @Test
    void rewriteUsesNormalizedQuestionWhenProviderFails() {
        when(terms.normalize("原问题")).thenReturn("归一化问题");
        when(llm.chat(any(ChatRequest.class), eq(Tier.FAST))).thenThrow(new IllegalStateException("offline"));

        assertThat(service(true, terms).rewrite("原问题")).isEqualTo("归一化问题");
        verify(llm).chat(any(ChatRequest.class), eq(Tier.FAST));
    }

    @Test
    void disabledRewriteStillAppliesRealTermMappingWithoutCallingProvider() {
        QueryTermMappingMapper mapper = mock(QueryTermMappingMapper.class);
        QueryTermMappingCacheManager cache = mock(QueryTermMappingCacheManager.class);
        QueryTermMappingDO mapping = new QueryTermMappingDO();
        mapping.setSourceTerm("阿里");
        mapping.setTargetTerm("阿里巴巴");
        mapping.setEnabled(1);
        mapping.setMatchType(1);
        when(cache.getMappingsFromCache()).thenReturn(List.of(mapping));
        QueryTermMappingService realTerms = new QueryTermMappingService(mapper, cache);

        assertThat(service(false, realTerms).rewrite("阿里使用的是钉钉么？"))
                .isEqualTo("阿里巴巴使用的是钉钉么？");
        verify(cache).getMappingsFromCache();
        verifyNoInteractions(llm, prompts, mapper);
    }

    private MultiQuestionRewriteService service(boolean enabled, QueryTermMappingService mappingService) {
        RAGConfigProperties config = new RAGConfigProperties();
        config.setQueryRewriteEnabled(enabled);
        return new MultiQuestionRewriteService(llm, config, mappingService, prompts);
    }
}
