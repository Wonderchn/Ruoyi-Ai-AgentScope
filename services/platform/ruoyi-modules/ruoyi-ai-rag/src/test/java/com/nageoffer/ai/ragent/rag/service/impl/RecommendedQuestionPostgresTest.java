package com.nageoffer.ai.ragent.rag.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.dto.RecommendedQuestionsPayload;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Mapper and PostgreSQL, synthetic table/rows; no TenantLine or platform login. */
@Tag("dev")
@EnabledIfSystemProperty(named = "ragent.recommendation.test.jdbc-url", matches = ".+",
        disabledReason = "Requires private PostgreSQL via ragent.recommendation.test.jdbc-url")
class RecommendedQuestionPostgresTest {
    private PGSimpleDataSource source;
    private String schema;
    private SqlSession session;
    private ConversationMessageMapper mapper;
    private RecommendedQuestionGenerator generator;
    private RecommendedQuestionServiceImpl service;

    @BeforeEach void setup() throws Exception {
        source = new PGSimpleDataSource();
        source.setUrl(System.getProperty("ragent.recommendation.test.jdbc-url"));
        schema = "r6_recommend_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            // Synthetic fixture, not a migration-chain claim; names/types match the actual entity.
            statement.execute("CREATE TABLE " + schema + ".ai_message (id varchar(20) PRIMARY KEY, "
                    + "conversation_id varchar(64), tenant_id varchar(64) NOT NULL, member_id varchar(160) NOT NULL, "
                    + "user_id varchar(20), role varchar(20), content text, thinking_content text, thinking_duration integer, "
                    + "sources jsonb, retrieved_chunks jsonb, recommended_questions jsonb, reply_to_message_id varchar(20), "
                    + "message_status varchar(20), create_time timestamp, update_time timestamp, deleted integer DEFAULT 0)");
        }
        source.setCurrentSchema(schema);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("r6-recommend", new JdbcTransactionFactory(), source));
        config.addMapper(ConversationMessageMapper.class);
        session = new MybatisSqlSessionFactoryBuilder().build(config).openSession(true);
        mapper = session.getMapper(ConversationMessageMapper.class);
        generator = mock(RecommendedQuestionGenerator.class);
        service = new RecommendedQuestionServiceImpl(mapper, generator);
        PrincipalContext.set(RecommendedQuestionTenantScopeTest.principal("T-A"));
    }
    @AfterEach void cleanup() throws Exception {
        PrincipalContext.clear();
        if (session != null) session.close();
        if (schema != null) try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
    private ConversationMessageDO seed(String id, String tenant, String role, String reply, List<String> cached) {
        var row = ConversationMessageDO.builder().id(id).tenantId(tenant).memberId("platform:" + tenant + ":7")
                .userId("7").conversationId("conversation").role(role).content(id + "-content")
                .replyToMessageId(reply).recommendedQuestions(cached).messageStatus("NORMAL").deleted(0).build();
        assertThat(mapper.insert(row)).isEqualTo(1);
        return row;
    }
    @Test void foreignCachedAndUncachedAnswersAreBothInvisible() {
        seed("foreign-cached", "T-B", "assistant", null, List.of("foreign-secret"));
        seed("foreign-new", "T-B", "assistant", null, null);
        for (String id : List.of("foreign-cached", "foreign-new", "missing")) {
            assertThatThrownBy(() -> service.generate(id, "7")).isInstanceOf(ClientException.class).hasMessage("消息不存在");
        }
        verifyNoInteractions(generator);
    }
    @Test void ownGenerationPersistsAndSubsequentReadUsesCache() {
        seed("own-question", "T-A", "user", null, null);
        seed("own-answer", "T-A", "assistant", "own-question", null);
        when(generator.generate("own-question-content", "own-answer-content", null))
                .thenReturn(RecommendedQuestionsPayload.success(List.of("own-generated")));
        assertThat(service.generate("own-answer", "7").questions()).containsExactly("own-generated");
        assertThat(service.generate("own-answer", "7").questions()).containsExactly("own-generated");
        verify(generator, times(1)).generate("own-question-content", "own-answer-content", null);
        assertThat(mapper.selectById("own-answer").getRecommendedQuestions()).containsExactly("own-generated");
    }
    @Test void foreignReplyDoesNotReachTheGenerator() {
        seed("foreign-question", "T-B", "user", null, null);
        seed("own-answer", "T-A", "assistant", "foreign-question", null);
        when(generator.generate(null, "own-answer-content", null)).thenReturn(RecommendedQuestionsPayload.empty());
        assertThat(service.generate("own-answer", "7").status()).isEqualTo(RecommendedQuestionsPayload.Status.EMPTY);
        verify(generator).generate(null, "own-answer-content", null);
    }
    @Test void changedOwnerDuringGenerationPreventsPersistenceAndDelivery() {
        seed("own-answer", "T-A", "assistant", null, null);
        when(generator.generate(null, "own-answer-content", null)).thenAnswer(call -> {
            try (var connection = source.getConnection(); var statement = connection.createStatement()) {
                assertThat(statement.executeUpdate("UPDATE ai_message SET tenant_id='T-B', member_id='platform:T-B:7' "
                        + "WHERE id='own-answer'")).isEqualTo(1);
            }
            return RecommendedQuestionsPayload.success(List.of("must-not-persist"));
        });
        assertThatThrownBy(() -> service.generate("own-answer", "7")).isInstanceOf(ClientException.class).hasMessage("消息不存在");
        assertThat(mapper.selectById("own-answer").getRecommendedQuestions()).isNull();
    }
}
