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
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.authorization.AiDomainWriteIdentity;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.database.MyMetaObjectHandler;
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
import java.util.List;
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
 *   <li><b>取消</b>：{@code deleted=1}（无既有行时插入 {@code vote=0} 占位；既有行只置
 *       deleted/update_time，vote 保留最后一次有效值——所有读面按 deleted=0 过滤）；
 *       重复取消幂等（仍恰一行）；</li>
 *   <li><b>四步增量</b>：like→re-like→unlike→re-like 终点恰一行、vote=1/deleted=0；</li>
 *   <li><b>服务层双保险</b>（{@code MessageFeedbackServiceImpl.doUpsertFeedback} 的
 *       {@code lt(updateTime, submitTime)}）：乱序事件经服务层也不得覆写；</li>
 *   <li><b>用户维度独立</b>：同一消息上另一 user_id 的反馈是独立行（冲突键含 user），
 *       不影响当前用户的行。</li>
 * </ul>
 *
 * <p><b>夹具独立性（复核 M1 修复点：不得隐式依赖方法执行顺序）。</b>本类全部判据的键都是
 * {@code (tenant, message, user)}；若多个用例共享同一条消息行，则"先跑哪个用例"会改变
 * ON CONFLICT 命中的既有行及其 update_time，把断言结果绑到 JUnit 的方法执行顺序上
 * （默认顺序≠声明顺序 ⇒ 首写会在旧行上命中 WHERE 守卫返回 0 行 ⇒ 必红）。
 * 因此<b>每个用例拥有自己的消息行</b>（{@link #MSG_LIKE} 等五个常量），任何执行顺序下
 * 首写都是 INSERT（恰 1 行）——不需要 {@code @Order}（那只是掩盖顺序依赖），也不依赖
 * 用例中途清场。{@code @BeforeAll} 的 purge 只负责跨"类重复运行"的干净起点。
 *
 * <p>需要 {@code -Dragent.merged.test.jdbc-url=...}（数据库已应用冻结迁移 V7..V10）；
 * 未提供时显式跳过并给出原因，不伪装通过。本类只碰 {@code f17idem-%} 前缀的行。
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

    // 每用例一条独立消息行（键 (tenant, message, user) 的 message 维度互不相同）；
    // 上面的类注释已说明：共享消息行会把断言结果绑到执行顺序上（复核 M1）。
    private static final String MSG_LIKE = "f17idem-msg-like";
    private static final String MSG_STALE = "f17idem-msg-stale";
    private static final String MSG_CANCEL = "f17idem-msg-cancel";
    private static final String MSG_SERVICE = "f17idem-msg-svc";
    private static final String MSG_TWO_USERS = "f17idem-msg-two";

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
        // 与服务层 update 路径同语义：@TableField(fill = INSERT_UPDATE) 的字段（update_time）
        // 被 MP 无条件放进 SET 子句，值由 MetaObjectHandler 在执行前填充。台架不注册处理器时
        // 该参数绑 null → 真库首跑报 `null value in column "update_time"`（复核 M1 后续发现①）。
        // 生产（Spring）与既有真库 E2E 都用 MyMetaObjectHandler；这里必须按同一语义装配。
        // 用 GlobalConfigUtils.defaults() 而不是裸 new GlobalConfig()：后者 dbConfig 为 null，
        // MybatisSqlSessionFactoryBuilder.build 会解引用 tablePrefix 直接 NPE（真库首跑实测）。
        GlobalConfig globalConfig = GlobalConfigUtils.defaults();
        globalConfig.setMetaObjectHandler(new MyMetaObjectHandler());
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
        configuration.setEnvironment(new Environment("f17-idem", new JdbcTransactionFactory(), ds));
        configuration.addMapper(ConversationMapper.class);
        configuration.addMapper(ConversationMessageMapper.class);
        configuration.addMapper(MessageFeedbackMapper.class);
        factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        purgeFixtureRows();
        seedConversationAndMessages();
    }

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    @Test
    @DisplayName("like→re-like→unlike→re-like：冲突键 (tenant,message,user)，终点恰一行且可复活")
    void likeReLikeUnlikeReLikeEndsInExactlyOneActiveRow() throws Exception {
        long t = 1_800_000_000_000L;
        assertThat(upsertActive(MSG_LIKE, 1, t)).as("首次点赞 = 插入").isEqualTo(1);
        assertThat(upsertActive(MSG_LIKE, 1, t + 1_000)).as("重复点赞 = 就地更新（不产生第二行）").isEqualTo(1);
        assertThat(rawCount(MSG_LIKE, USER_ID)).isEqualTo(1);

        assertThat(upsertCancelled(MSG_LIKE, t + 2_000)).as("取消 = 同一行置 deleted=1").isEqualTo(1);
        assertThat(rawCount(MSG_LIKE, USER_ID)).isEqualTo(1);
        assertThat(rawDeleted(MSG_LIKE, USER_ID)).as("取消的语义载体是 deleted=1").isEqualTo(1);
        // 真库首跑（ENV-BATCH-3）实测：取消的冲突分支（DO UPDATE SET）只写 update_time/deleted，
        // **不重写 vote** —— 既有行保留最后一次有效值；"vote=0 的占位"只出现在无既有行的取消
        // （INSERT 分支，见 repeatedCancelIsIdempotent）。所有读路径按 deleted=0 过滤 ⇒
        // 保留值对读面不可见（治理读/网关读面都不返回该行）。
        assertThat(rawVote(MSG_LIKE, USER_ID)).as("取消不重写 vote：保留最后一次有效值").isEqualTo(1);

        assertThat(upsertActive(MSG_LIKE, 1, t + 3_000)).as("取消后再点赞 = 同一行复活").isEqualTo(1);
        assertThat(rawCount(MSG_LIKE, USER_ID)).isEqualTo(1);
        assertThat(rawVote(MSG_LIKE, USER_ID)).isEqualTo(1);
        assertThat(rawDeleted(MSG_LIKE, USER_ID)).isZero();
    }

    @Test
    @DisplayName("乱序保护（mapper）：旧 update_time 的 upsert 影响 0 行，不覆写新状态")
    void staleUpsertDoesNotOverwriteNewerState() throws Exception {
        long t = 1_800_000_100_000L;
        assertThat(upsertActive(MSG_STALE, 1, t)).isEqualTo(1);

        assertThat(upsertActive(MSG_STALE, -1, t - 5_000))
                .as("旧事件：WHERE update_time < EXCLUDED.update_time 不成立").isZero();
        assertThat(rawVote(MSG_STALE, USER_ID)).as("新状态保持 1，不被旧事件覆写").isEqualTo(1);
        assertThat(rawCount(MSG_STALE, USER_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("重复取消幂等（mapper）：晚到的第二次取消仍是同一占位行")
    void repeatedCancelIsIdempotent() throws Exception {
        long t = 1_800_000_200_000L;
        // 首取消 = INSERT 分支（本消息行此前没有任何反馈）→ vote=0 的占位；
        // 重复取消 = 冲突分支（只置 update_time/deleted，vote 保持占位值 0）→ 幂等。
        assertThat(upsertCancelled(MSG_CANCEL, t)).isEqualTo(1);
        assertThat(upsertCancelled(MSG_CANCEL, t + 1_000)).as("晚到的重复取消：更新同一行").isEqualTo(1);
        assertThat(rawCount(MSG_CANCEL, USER_ID)).isEqualTo(1);
        assertThat(rawVote(MSG_CANCEL, USER_ID)).isZero();
        assertThat(rawDeleted(MSG_CANCEL, USER_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("服务层双保险：乱序事件被 lt(updateTime, submitTime) 拦下，新事件才生效")
    void serviceLayerStaleEventIsIgnoredAndNewerEventWins() throws Exception {
        long t = 1_800_000_300_000L;
        assertThat(upsertActive(MSG_SERVICE, 1, t)).isEqualTo(1);
        PrincipalContext.clear();

        try (SqlSession session = factory.openSession(true)) {
            MessageFeedbackServiceImpl service = new MessageFeedbackServiceImpl(
                    session.getMapper(MessageFeedbackMapper.class),
                    session.getMapper(ConversationMessageMapper.class),
                    null);

            service.submitFeedbackByEvent(MessageFeedbackEvent.builder()
                    .messageId(MSG_SERVICE).userId(USER_ID).vote(-1).submitTime(t - 5_000).build());
            assertThat(rawVote(MSG_SERVICE, USER_ID)).as("乱序（旧 submitTime）经服务层不得覆写").isEqualTo(1);
            assertThat(rawCount(MSG_SERVICE, USER_ID)).isEqualTo(1);

            service.submitFeedbackByEvent(MessageFeedbackEvent.builder()
                    .messageId(MSG_SERVICE).userId(USER_ID).vote(-1).submitTime(t + 5_000).build());
            assertThat(rawVote(MSG_SERVICE, USER_ID)).as("新事件（晚 submitTime）正常覆写").isEqualTo(-1);
            assertThat(rawCount(MSG_SERVICE, USER_ID)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("用户维度独立：同一消息上另一 user_id 是独立行，互不覆写")
    void anotherUserVoteIsAnIndependentRow() throws Exception {
        long t = 1_800_000_400_000L;
        assertThat(upsertActive(MSG_TWO_USERS, 1, t)).isEqualTo(1);
        assertThat(upsertFeedback(MSG_TWO_USERS, OTHER_USER_ID, -1, t + 1_000)).isEqualTo(1);

        assertThat(rawCount(MSG_TWO_USERS, USER_ID)).as("当前用户仍恰一行").isEqualTo(1);
        assertThat(rawCount(MSG_TWO_USERS, OTHER_USER_ID)).as("另一用户是独立行（冲突键含 user_id）").isEqualTo(1);
        assertThat(rawVote(MSG_TWO_USERS, USER_ID)).isEqualTo(1);
        assertThat(rawVote(MSG_TWO_USERS, OTHER_USER_ID)).isEqualTo(-1);
    }

    // ---------------------------------------------------------------- 夹具与直接写路径

    private static void purgeFixtureRows() throws Exception {
        try (Connection connection = ds.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM platform.ai_message_feedback WHERE id LIKE 'f17idem-%'");
            statement.executeUpdate("DELETE FROM platform.ai_message WHERE id LIKE 'f17idem-%'");
            statement.executeUpdate("DELETE FROM platform.ai_conversation WHERE id LIKE 'f17idem-%'");
        }
    }

    private static void seedConversationAndMessages() {
        withPrincipal(() -> {
            ConversationDO conversation = ConversationDO.builder()
                    .id(CONV_ROW).conversationId(CONV).userId(USER_ID).title("F17 幂等夹具")
                    .lastTime(new Date(1_800_000_000_000L)).build();
            AiDomainWriteIdentity.apply(conversation);
            try (SqlSession session = factory.openSession(true)) {
                session.getMapper(ConversationMapper.class).insert(conversation);
                for (String messageId : List.of(MSG_LIKE, MSG_STALE, MSG_CANCEL, MSG_SERVICE, MSG_TWO_USERS)) {
                    ConversationMessageDO message = ConversationMessageDO.builder()
                            .id(messageId).conversationId(CONV).userId(USER_ID).role("assistant")
                            .content("F17 幂等夹具消息 " + messageId).messageStatus("NORMAL").build();
                    AiDomainWriteIdentity.apply(message);
                    session.getMapper(ConversationMessageMapper.class).insert(message);
                }
            }
        });
    }

    private static int upsertActive(String messageId, int vote, long submitTime) {
        try (SqlSession session = factory.openSession(true)) {
            return session.getMapper(MessageFeedbackMapper.class).upsertActiveFeedback(
                    feedback(messageId, USER_ID, vote, submitTime));
        }
    }

    private static int upsertFeedback(String messageId, String userId, int vote, long submitTime) {
        try (SqlSession session = factory.openSession(true)) {
            return session.getMapper(MessageFeedbackMapper.class).upsertActiveFeedback(
                    feedback(messageId, userId, vote, submitTime));
        }
    }

    private static int upsertCancelled(String messageId, long submitTime) {
        try (SqlSession session = factory.openSession(true)) {
            return session.getMapper(MessageFeedbackMapper.class).upsertCancelledFeedback(
                    feedback(messageId, USER_ID, null, submitTime));
        }
    }

    /** 反馈行主键由 (message, user) 派生：VARCHAR(20) 内，且与 purge 前缀一致。 */
    private static MessageFeedbackDO feedback(String messageId, String userId, Integer vote, long submitTime) {
        Date stamp = new Date(submitTime);
        MessageFeedbackDO row = MessageFeedbackDO.builder()
                .id(messageId.replace("-msg-", "-fb-") + "-" + userId)
                .messageId(messageId)
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

    private static long rawCount(String messageId, String userId) throws Exception {
        return rawLong("SELECT count(*) FROM platform.ai_message_feedback"
                + " WHERE tenant_id = ? AND message_id = ? AND user_id = ?", messageId, userId);
    }

    private static long rawVote(String messageId, String userId) throws Exception {
        return rawLong("SELECT vote FROM platform.ai_message_feedback"
                + " WHERE tenant_id = ? AND message_id = ? AND user_id = ? ORDER BY update_time DESC LIMIT 1",
                messageId, userId);
    }

    private static long rawDeleted(String messageId, String userId) throws Exception {
        return rawLong("SELECT deleted FROM platform.ai_message_feedback"
                + " WHERE tenant_id = ? AND message_id = ? AND user_id = ? ORDER BY update_time DESC LIMIT 1",
                messageId, userId);
    }

    private static long rawLong(String sql, String messageId, String userId) throws Exception {
        try (Connection connection = ds.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, TENANT);
            statement.setString(2, messageId);
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
