package com.nageoffer.ai.ragent.rag.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.dto.RecommendedQuestionsPayload;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Canonical predicates are required even when no platform TenantLine plugin runs. */
@Tag("dev")
class RecommendedQuestionTenantScopeTest {
    private final ConversationMessageMapper mapper = mock(ConversationMessageMapper.class);
    private final RecommendedQuestionGenerator generator = mock(RecommendedQuestionGenerator.class);
    private final RecommendedQuestionServiceImpl service = new RecommendedQuestionServiceImpl(mapper, generator);

    @BeforeAll static void table() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ConversationMessageDO.class);
    }
    @BeforeEach void principal() {
        PrincipalContext.set(principal("T-A"));
    }
    @AfterEach void clear() { PrincipalContext.clear(); }
    static ExecutionPrincipal principal(String tenant) {
        return new ExecutionPrincipal(tenant, "7", "platform:" + tenant + ":7", 1, 1,
                Set.of("ai:conversation:read"), "recommend-test", "platform", 0, Long.MAX_VALUE);
    }
    private ConversationMessageDO answer() {
        return ConversationMessageDO.builder().id("answer").tenantId("T-A").memberId("platform:T-A:7")
                .userId("7").conversationId("conversation").role("assistant").content("answer-text")
                .replyToMessageId("question").messageStatus("NORMAL").deleted(0).build();
    }
    private void scoped(Object argument) {
        var wrapper = (AbstractWrapper<?, ?, ?>) argument;
        assertThat(wrapper.getSqlSegment()).contains("tenant_id", "member_id", "user_id", "deleted");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("T-A", "platform:T-A:7", "7");
    }
    @Test void missingPrincipalAndMismatchedCallerStopBeforeReading() {
        assertThatThrownBy(() -> service.generate("answer", "8")).isInstanceOf(ClientException.class);
        PrincipalContext.clear();
        assertThatThrownBy(() -> service.generate("answer", "7")).isInstanceOf(ClientException.class);
        verifyNoInteractions(mapper, generator);
    }
    @Test void cachedReadStillRequiresCanonicalScope() {
        var answer = answer(); answer.setRecommendedQuestions(List.of("cached"));
        when(mapper.selectOne(any())).thenAnswer(call -> { scoped(call.getArgument(0)); return answer; });
        assertThat(service.generate("answer", "7").questions()).containsExactly("cached");
        verifyNoInteractions(generator);
        verify(mapper, never()).update(any(ConversationMessageDO.class), any());
    }
    @Test void questionReadAndPersistenceCarryTheSameCanonicalScope() {
        var answer = answer();
        var question = ConversationMessageDO.builder().content("question-text").build();
        when(mapper.selectOne(any())).thenAnswer(call -> {
            scoped(call.getArgument(0));
            return ((AbstractWrapper<?, ?, ?>) call.getArgument(0)).getParamNameValuePairs().values()
                    .contains("assistant") ? answer : question;
        });
        when(generator.generate("question-text", "answer-text", null))
                .thenReturn(RecommendedQuestionsPayload.success(List.of("generated")));
        when(mapper.update(any(ConversationMessageDO.class), any())).thenAnswer(call -> {
            scoped(call.getArgument(1));
            assertThat(((AbstractWrapper<?, ?, ?>) call.getArgument(1)).getParamNameValuePairs().values())
                    .contains("answer", "assistant");
            return 1;
        });
        assertThat(service.generate("answer", "7").questions()).containsExactly("generated");
        verify(mapper, times(2)).selectOne(any());
    }
    @Test void lostOwnershipAtPersistenceDoesNotReturnGeneratedContent() {
        var answer = answer(); answer.setReplyToMessageId(null);
        when(mapper.selectOne(any())).thenReturn(answer);
        when(generator.generate(null, "answer-text", null)).thenReturn(RecommendedQuestionsPayload.success(List.of("secret")));
        when(mapper.update(any(ConversationMessageDO.class), any())).thenReturn(0);
        assertThatThrownBy(() -> service.generate("answer", "7")).isInstanceOf(ClientException.class);
    }
    @Test void failedGenerationDoesNotWrite() {
        var answer = answer(); answer.setReplyToMessageId(null);
        when(mapper.selectOne(any())).thenReturn(answer);
        when(generator.generate(null, "answer-text", null)).thenReturn(RecommendedQuestionsPayload.failed());
        assertThat(service.generate("answer", "7").status()).isEqualTo(RecommendedQuestionsPayload.Status.FAILED);
        verify(mapper, never()).update(any(ConversationMessageDO.class), any());
    }
    @Test void interruptedMessagesDoNotGenerateOrPersist() {
        var answer = answer(); answer.setMessageStatus("INTERRUPTED");
        when(mapper.selectOne(any())).thenReturn(answer);
        assertThat(service.generate("answer", "7").status()).isEqualTo(RecommendedQuestionsPayload.Status.EMPTY);
        verifyNoInteractions(generator);
        verify(mapper, never()).update(any(ConversationMessageDO.class), any());
    }
}
