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

package com.nageoffer.ai.ragent.rag.core.unified;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.authorization.AiDomainWriteIdentity;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.entity.MessageFeedbackDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.MessageFeedbackMapper;
import com.nageoffer.ai.ragent.rag.mq.event.MessageFeedbackEvent;
import com.nageoffer.ai.ragent.rag.service.impl.MessageFeedbackServiceImpl;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Date;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F17-A1 / op2：反馈写入的<b>幂等语义</b>在真实 PostgreSQL 上的钉死判据
 * （mapper 两个 upsert + 服务层双保险，全部跑实际字节码）。
 *
 * <p>既有 {@code MergedTableMapperPostgresTest} 证明了"合并表能写、租户/成员隔离"；
 * 本类补它没覆盖的反馈幂等面（optimization A 行"幂等语义"，R10 把 HTTP 端到端幂等
 * 顺延到 submitted 分支后，先在 mapper/服务层钉死）：
 * <ul>
 *   <li><b>冲突键</b> {@code (tenant_id, message_id, user_id)}（V7:2033）：like→re-like
 *       不产生第二行，就地更新；</li>
 *   <li><b>条件 upsert / 乱序保护</b>：{@code WHERE update_time < EXCLUDED.update_time}
 *       ——旧事件（较早 submitTime）返回 0 行、不覆写新状态；</li>
 *   <li><b>取消占位</b>：{@code vote=0, deleted=1}；重复取消幂等（仍恰一行）；</li>
 *   <li><b>四步增量</b>：like→re-like→unlike→re-like 终点恰一行、vote=1/deleted=0；</li>
 *   <li><b>服务层双保险</b>（{@code MessageFeedbackServiceImpl.doUpsertFeedback} 的
 *       {@code lt(updateTime, submitTime)}）：乱序事件经服务层也不得覆写；</li>
 *   <li><b>用户维度独立</b>：同一消息上另一 user_id 的反馈是独立行（冲突键含 user），
 *       不影响当前用户的行。</li>
 * </ul>
 *
 * <p>需要 {@code -Dragent.merged.test.jdbc-url=...}（数据库已应用冻结迁移 V7..V10）；
 * 未提供时显式跳过并给出原因，不伪装通过。本类只碰 {@code f17idem-%} 前缀的行，
 * 重复运行不依赖"库是干净的"。
 */
@Tag("dev")
@EnabledIfSystemProperty(named = MessageFeedbackIdempotencyPostgresTest.URL_PROPERTY, matches = ".+",
        disabledReason = "需要隔离 PostgreSQL：-D" + MessageFeedbackIdempotencyPostgresTest.URL_PROPERTY
                + "=jdbc:postgresql://host:port/db（已应用冻结迁移 V7..V10）")
class MessageFeedbackIdempotencyPostgresTest {

    static final String URL_PROPERTY = "ragent.merged.test.jdbc-url";
    private static final String DB_USER = "migrate_platform";

    private static final String TENANT = "F17-IDEM-T";
    private static final String USER_ID = "11";
    private static final String OTHER_USER_ID = "99";
    private static final String MEMBER = "platform:F17-IDEM-T:11";
    private static final String CONV = "f17idem-conv-1";
    private static final String CONV_ROW = "f17idem-conv-r";
    private static final String MESSAGE = "f17idem-msg-1";

    private static PGSimpleDataSource ds;
    private static SqlSessionFactory factory;

    @BeforeAll
    static void setUp() throws Exception {
        String url = System.getProperty(URL_PROPERTY);
        assertThat(url).as("缺少 -D" + URL_PROPERTY).isNotBlank();
        ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(DB_USER);
        ds.setPassword(DB_USER);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("f17-idem", new JdbcTransactionFactory(), ds));
        configuration.addMapper(ConversationMapper.class);
        configuration.addMapper(ConversationMessageMapper.class);
        configuration.addMapper(MessageFeedbackMapper.class);
        factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        purgeFixtureRows();
        seedConversationAndMessage();
    }

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    @Test
    @DisplayName("like→re-like→unlike→re-like：冲突键 (tenant,message,user)，终点恰一行且可复活")
    void likeReLikeUnlikeReLikeEndsInExactlyOneActiveRow() throws Exception {
        long t = 1_800_000_000_000L;
        assertThat(upsertActive(1, t)).as("首次点赞 = 插入").isEqualTo(1);
        assertThat(upsertActive(1, t + 1_000)).as("重复点赞 = 就地更新（不产生第二行）").isEqualTo(1);
        assertThat(rawCount(USER_ID)).isEqualTo(1);

        assertThat(upsertCancelled(t + 2_000)).as("取消 = 占位行（vote=0, deleted=1）").isEqualTo(1);
        assertThat(rawCount(USER_ID)).isEqualTo(1);
        assertThat(rawVote(USER_ID)).as("取消占位 vote=0").isZero();
        assertThat(rawDeleted(USER_ID)).as("取消占位 deleted=1").isEqualTo(1);

        assertThat(upsertActive(1, t + 3_000)).as("取消后再点赞 = 同一行复活").isEqualTo(1);
        assertThat(rawCount(USER_ID)).isEqualTo(1);
        assertThat(rawVote(USER_ID)).isEqualTo(1);
        assertThat(rawDeleted(USER_ID)).isZero();
    }

    @Test
    @DisplayName("乱序保护（mapper）：旧 update_time 的 upsert 影响 0 行，不覆写新状态")
    void staleUpsertDoesNotOverwriteNewerState() throws Exception {
        long t = 1_800_000_100_000L;
        assertThat(upsertActive(1, t)).isEqualTo(1);

        assertThat(upsertActive(-1, t - 5_000)).as("旧事件：WHERE update_time < EXCLUDED.update_time 不成立")
                .isZero();
        assertThat(rawVote(USER_ID)).as("新状态保持 1，不被旧事件覆写").isEqualTo(1);
        assertThat(rawCount(USER_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("重复取消幂等（mapper）：晚到的第二次取消仍是同一占位行")
    void repeatedCancelIsIdempotent() throws Exception {
        long t = 1_800_000_200_000L;
        assertThat(upsertCancelled(t)).isEqualTo(1);
        assertThat(upsertCancelled(t + 1_000)).as("晚到的重复取消：更新同一行").isEqualTo(1);
        assertThat(rawCount(USER_ID)).isEqualTo(1);
        assertThat(rawVote(USER_ID)).isZero();
        assertThat(rawDeleted(USER_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("服务层双保险：乱序事件被 lt(updateTime, submitTime) 拦下，新事件才生效")
    void serviceLayerStaleEventIsIgnoredAndNewerEventWins() throws Exception {
        long t = 1_800_000_300_000L;
        assertThat(upsertActive(1, t)).isEqualTo(1);
        PrincipalContext.clear();

        try (SqlSession session = factory.openSession(true)) {
            MessageFeedbackServiceImpl service = new MessageFeedbackServiceImpl(
                    session.getMapper(MessageFeedbackMapper.class),
                    session.getMapper(ConversationMessageMapper.class),
                    null);

            service.submitFeedbackByEvent(MessageFeedbackEvent.builder()
                    .messageId(MESSAGE).userId(USER_ID).vote(-1).submitTime(t - 5_000).build());
            assertThat(rawVote(USER_ID)).as("乱序（旧 submitTime）经服务层不得覆写").isEqualTo(1);
            assertThat(rawCount(USER_ID)).isEqualTo(1);

            service.submitFeedbackByEvent(MessageFeedbackEvent.builder()
                    .messageId(MESSAGE).userId(USER_ID).vote(-1).submitTime(t + 5_000).build());
            assertThat(rawVote(USER_ID)).as("新事件（晚 submitTime）正常覆写").isEqualTo(-1);
            assertThat(rawCount(USER_ID)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("用户维度独立：同一消息上另一 user_id 是独立行，互不覆写")
    void anotherUserVoteIsAnIndependentRow() throws Exception {
        long t = 1_800_000_400_000L;
        assertThat(upsertActive(1, t)).isEqualTo(1);
        assertThat(upsertFeedback(OTHER_USER_ID, -1, t + 1_000)).isEqualTo(1);

        assertThat(rawCount(USER_ID)).as("当前用户仍恰一行").isEqualTo(1);
        assertThat(rawCount(OTHER_USER_ID)).as("另一用户是独立行（冲突键含 user_id）").isEqualTo(1);
        assertThat(rawVote(USER_ID)).isEqualTo(1);
        assertThat(rawVote(OTHER_USER_ID)).isEqualTo(-1);
    }

    // ---------------------------------------------------------------- 夹具与直接写路径

    private static void purgeFixtureRows() throws Exception {
        try (Connection connection = ds.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM platform.ai_message_feedback WHERE id LIKE 'f17idem-%'");
            statement.executeUpdate("DELETE FROM platform.ai_message WHERE id LIKE 'f17idem-%'");
            statement.executeUpdate("DELETE FROM platform.ai_conversation WHERE id LIKE 'f17idem-%'");
        }
    }

    private static void seedConversationAndMessage() {
        withPrincipal(() -> {
            ConversationDO conversation = ConversationDO.builder()
                    .id(CONV_ROW).conversationId(CONV).userId(USER_ID).title("F17 幂等夹具")
                    .lastTime(new Date(1_800_000_000_000L)).build();
            AiDomainWriteIdentity.apply(conversation);
            ConversationMessageDO message = ConversationMessageDO.builder()
                    .id(MESSAGE).conversationId(CONV).userId(USER_ID).role("assistant")
                    .content("F17 幂等夹具消息").messageStatus("NORMAL").build();
            AiDomainWriteIdentity.apply(message);
            try (SqlSession session = factory.openSession(true)) {
                session.getMapper(ConversationMapper.class).insert(conversation);
                session.getMapper(ConversationMessageMapper.class).insert(message);
            }
        });
    }

    private static int upsertActive(int vote, long submitTime) {
        try (SqlSession session = factory.openSession(true)) {
            return session.getMapper(MessageFeedbackMapper.class).upsertActiveFeedback(
                    feedback(USER_ID, vote, submitTime));
        }
    }

    private static int upsertFeedback(String userId, int vote, long submitTime) {
        try (SqlSession session = factory.openSession(true)) {
            return session.getMapper(MessageFeedbackMapper.class).upsertActiveFeedback(
                    feedback(userId, vote, submitTime));
        }
    }

    private static int upsertCancelled(long submitTime) {
        try (SqlSession session = factory.openSession(true)) {
            return session.getMapper(MessageFeedbackMapper.class).upsertCancelledFeedback(
                    feedback(USER_ID, null, submitTime));
        }
    }

    private static MessageFeedbackDO feedback(String userId, Integer vote, long submitTime) {
        Date stamp = new Date(submitTime);
        MessageFeedbackDO row = MessageFeedbackDO.builder()
                .id("f17idem-fb-" + userId)
                .messageId(MESSAGE)
                .conversationId(CONV)
                .userId(userId)
                .vote(vote)
                .createTime(stamp)
                .updateTime(stamp)
                .build();
        row.setTenantId(TENANT);
        row.setMemberId(MEMBER);
        return row;
    }

    // ---------------------------------------------------------------- 读回（含 deleted=1 占位；@TableLogic 查询看不到）

    private static long rawCount(String userId) throws Exception {
        return rawLong("SELECT count(*) FROM platform.ai_message_feedback"
                + " WHERE tenant_id = ? AND message_id = ? AND user_id = ?", userId);
    }

    private static long rawVote(String userId) throws Exception {
        return rawLong("SELECT vote FROM platform.ai_message_feedback"
                + " WHERE tenant_id = ? AND message_id = ? AND user_id = ? ORDER BY update_time DESC LIMIT 1", userId);
    }

    private static long rawDeleted(String userId) throws Exception {
        return rawLong("SELECT deleted FROM platform.ai_message_feedback"
                + " WHERE tenant_id = ? AND message_id = ? AND user_id = ? ORDER BY update_time DESC LIMIT 1", userId);
    }

    private static long rawLong(String sql, String userId) throws Exception {
        try (Connection connection = ds.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, TENANT);
            statement.setString(2, MESSAGE);
            statement.setString(3, userId);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).as("夹具行必须存在：%s", sql).isTrue();
                return rs.getLong(1);
            }
        }
    }

    private static void withPrincipal(Runnable body) {
        ExecutionPrincipal previous = PrincipalContext.get();
        PrincipalContext.set(new ExecutionPrincipal(TENANT, USER_ID,
                ExecutionPrincipal.canonicalMembershipId(TENANT, USER_ID),
                1, 1, Set.of(), "f17-jti", "f17-tests", 1L, 2L));
        try {
            body.run();
        } finally {
            PrincipalContext.restore(previous);
        }
    }

    /** 让 Wrappers 的静态入口在编译期可见（不是断言，是"这个测试认这个接口"）。 */
    @SuppressWarnings("unused")
    private static void contractAnchor() {
        Wrappers.lambdaQuery(MessageFeedbackDO.class).eq(MessageFeedbackDO::getUserId, USER_ID);
    }
}
