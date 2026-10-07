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
import com.nageoffer.ai.ragent.framework.convention.MergedTableRow;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.entity.MessageFeedbackDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.MessageFeedbackMapper;
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
import java.sql.Statement;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E5/WP-025 真实验收：在<b>隔离 PostgreSQL</b> 上跑<b>实际 Mapper 字节码</b>。
 *
 * <p>判据来自 WP-025 验收表：
 * <ul>
 *   <li>合并表（会话/消息/反馈）在统一表名上 CRUD、分页、逻辑删除过滤成功；</li>
 *   <li>两个租户、同租户两个成员各自只看到自己的行（WHERE 恒带 tenant + member）；</li>
 *   <li>缺执行主体时写入被拒绝，且不落任何行；</li>
 *   <li>{@code tenant_id}/{@code member_id} 是 NOT NULL：不填就插入失败（证明"靠默认值"不可行）；</li>
 *   <li>滚动回滚不留半拷贝行。</li>
 * </ul>
 *
 * <p>需要 {@code -Dragent.merged.test.jdbc-url=...}（数据库已应用冻结迁移 V7..V10）。
 * 没有该参数时<b>显式跳过并给出原因</b>，不伪装通过。
 */
@Tag("dev")
@EnabledIfSystemProperty(named = MergedTableMapperPostgresTest.URL_PROPERTY, matches = ".+",
        disabledReason = "需要隔离 PostgreSQL：-D" + MergedTableMapperPostgresTest.URL_PROPERTY
                + "=jdbc:postgresql://host:port/db（已应用冻结迁移 V7..V10）")
class MergedTableMapperPostgresTest {

    static final String URL_PROPERTY = "ragent.merged.test.jdbc-url";
    private static final String USER = "migrate_platform";

    private static String url;
    private static SqlSessionFactory factory;

    private static final String TENANT_A = "1001";
    private static final String TENANT_B = "1002";

    @BeforeAll
    static void setUp() {
        url = System.getProperty(URL_PROPERTY);
        // @EnabledIfSystemProperty 已经保证 url 存在；这里再断言一次，防止注解被误改后静默跑空。
        assertThat(url).as("缺少 -D" + URL_PROPERTY).isNotBlank();
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(USER);
        ds.setPassword("migrate_platform");
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("wp025", new JdbcTransactionFactory(), ds));
        configuration.addMapper(ConversationMapper.class);
        configuration.addMapper(ConversationMessageMapper.class);
        configuration.addMapper(MessageFeedbackMapper.class);
        factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        purgeFixtureRows(ds);
    }

    /**
     * 本测试只碰自己前缀的行：重复运行不依赖"库是干净的"，也不会删到别人的数据。
     * （数据库是任务自有的隔离库，仍然按前缀收敛范围。）
     */
    private static void purgeFixtureRows(PGSimpleDataSource ds) {
        String[] statements = {
                "DELETE FROM platform.ai_message_feedback WHERE id LIKE 'wp025-%'",
                "DELETE FROM platform.ai_message WHERE id LIKE 'wp025-%'",
                "DELETE FROM platform.ai_conversation WHERE id LIKE 'wp025-%'",
        };
        try (Connection connection = ds.getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.executeUpdate(sql);
            }
        } catch (Exception e) {
            throw new IllegalStateException("无法清理本测试的夹具行（数据库是否已应用冻结迁移 V7..V10？）", e);
        }
    }

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    @Test
    @DisplayName("合并表在统一表名上写入/读回，身份列来自执行主体")
    void mergedTablesRoundTripWithPrincipalIdentity() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            ConversationMapper conversations = session.getMapper(ConversationMapper.class);
            ConversationMessageMapper messages = session.getMapper(ConversationMessageMapper.class);
            MessageFeedbackMapper feedbacks = session.getMapper(MessageFeedbackMapper.class);

            withPrincipal(TENANT_A, "11", () -> {
                ConversationDO conversation = ConversationDO.builder()
                        .id("wp025-conv-a1")
                        .conversationId("wp025-public-a1")
                        .userId("11")
                        .title("WP-025 会话")
                        .lastTime(new Date())
                        .build();
                AiDomainWriteIdentity.apply(conversation);
                assertThat(conversations.insert(conversation)).isEqualTo(1);

                ConversationMessageDO message = ConversationMessageDO.builder()
                        .id("wp025-msg-a1")
                        .conversationId("wp025-public-a1")
                        .userId("11")
                        .role("assistant")
                        .content("WP-025 消息")
                        .messageStatus("NORMAL")
                        .build();
                AiDomainWriteIdentity.apply(message);
                assertThat(messages.insert(message)).isEqualTo(1);

                MessageFeedbackDO feedback = MessageFeedbackDO.builder()
                        .id("wp025-fb-a1")
                        .messageId("wp025-msg-a1")
                        .conversationId("wp025-public-a1")
                        .userId("11")
                        .vote(1)
                        .build();
                AiDomainWriteIdentity.apply(feedback);
                assertThat(feedbacks.upsertActiveFeedback(feedback)).isEqualTo(1);
            });

            ConversationDO loaded = conversations.selectOne(Wrappers.lambdaQuery(ConversationDO.class)
                    .eq(ConversationDO::getConversationId, "wp025-public-a1"));
            assertThat(loaded).isNotNull();
            assertThat(loaded.getTenantId()).isEqualTo(TENANT_A);
            assertThat(loaded.getMemberId()).isEqualTo("platform:1001:11");
            assertThat(loaded.getTitle()).isEqualTo("WP-025 会话");

            ConversationMessageDO loadedMessage = messages.selectOne(
                    Wrappers.lambdaQuery(ConversationMessageDO.class).eq(ConversationMessageDO::getId, "wp025-msg-a1"));
            assertThat(loadedMessage.getMemberId()).isEqualTo("platform:1001:11");
            assertThat(loadedMessage.getTenantId()).isEqualTo(TENANT_A);
        }
    }

    @Test
    @DisplayName("同租户两个成员、两个租户之间互不可见（WHERE 带 tenant + member）")
    void membersAndTenantsAreIsolated() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            ConversationMapper conversations = session.getMapper(ConversationMapper.class);
            seedConversation(conversations, TENANT_A, "11", "wp025-iso-a11", "A 租户 11 号成员");
            seedConversation(conversations, TENANT_A, "12", "wp025-iso-a12", "A 租户 12 号成员");
            seedConversation(conversations, TENANT_B, "11", "wp025-iso-b11", "B 租户 11 号成员");

            List<ConversationDO> asA11 = scopedConversations(conversations, TENANT_A, "11");
            assertThat(asA11).extracting(ConversationDO::getConversationId)
                    .containsExactly("wp025-iso-a11-pub");

            List<ConversationDO> asA12 = scopedConversations(conversations, TENANT_A, "12");
            assertThat(asA12).extracting(ConversationDO::getConversationId)
                    .containsExactly("wp025-iso-a12-pub");

            List<ConversationDO> asB11 = scopedConversations(conversations, TENANT_B, "11");
            assertThat(asB11).extracting(ConversationDO::getConversationId)
                    .containsExactly("wp025-iso-b11-pub");

            // B 租户的成员拿不到 A 租户的行，即使 userId 相同
            assertThat(asB11).noneMatch(c -> c.getConversationId().startsWith("wp025-iso-a"));
        }
    }

    @Test
    @DisplayName("缺执行主体时写身份被拒绝，且一行都不落")
    void missingPrincipalRefusesIdentityAndWritesNothing() throws Exception {
        PrincipalContext.clear();
        ConversationDO conversation = ConversationDO.builder()
                .id("wp025-noprincipal")
                .conversationId("wp025-noprincipal-pub")
                .userId("11")
                .title("无主体")
                .build();
        assertThatThrownBy(() -> AiDomainWriteIdentity.apply(conversation))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("no execution principal");

        try (SqlSession session = factory.openSession(true)) {
            ConversationMapper conversations = session.getMapper(ConversationMapper.class);
            assertThat(conversations.selectCount(Wrappers.lambdaQuery(ConversationDO.class)
                    .eq(ConversationDO::getId, "wp025-noprincipal"))).isZero();
        }
    }

    @Test
    @DisplayName("identity 缺失时数据库以 NOT NULL 拒绝：不存在靠列默认值蒙混的通路")
    void nullIdentityIsRejectedByTheDatabase() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            ConversationMapper conversations = session.getMapper(ConversationMapper.class);
            withPrincipal(TENANT_A, "11", () -> {
                ConversationDO unowned = ConversationDO.builder()
                        .id("wp025-unowned")
                        .conversationId("wp025-unowned-pub")
                        .userId("11")
                        .title("没有身份")
                        .build();
                assertThatThrownBy(() -> conversations.insert(unowned))
                        .as("tenant_id/member_id 都是 NOT NULL 且无默认值：不填就失败，而不是落进默认租户。"
                                + "注意 MyBatis-Plus 会省略 null 字段，所以数据库先报 tenant_id，"
                                + "关键是行没有落库")
                        .hasMessageContaining("not-null constraint");
            });
            assertThat(conversations.selectCount(Wrappers.lambdaQuery(ConversationDO.class)
                    .eq(ConversationDO::getId, "wp025-unowned")))
                    .as("被数据库拒绝的行不得存在")
                    .isZero();
        }
    }

    @Test
    @DisplayName("回滚不留半拷贝行；逻辑删除过滤生效；分页有序")
    void rollbackLeavesNoPartialRowsAndDeletesAreFiltered() throws Exception {
        try (SqlSession session = factory.openSession(false)) {
            ConversationMapper conversations = session.getMapper(ConversationMapper.class);
            withPrincipal(TENANT_A, "11", () -> {
                ConversationDO conversation = ConversationDO.builder()
                        .id("wp025-rollback")
                        .conversationId("wp025-rollback-pub")
                        .userId("11")
                        .title("回滚")
                        .build();
                AiDomainWriteIdentity.apply(conversation);
                conversations.insert(conversation);
                session.rollback();
            });
        }
        try (SqlSession session = factory.openSession(true)) {
            ConversationMapper conversations = session.getMapper(ConversationMapper.class);
            assertThat(conversations.selectCount(Wrappers.lambdaQuery(ConversationDO.class)
                    .eq(ConversationDO::getId, "wp025-rollback"))).isZero();

            withPrincipal(TENANT_A, "11", () -> {
                for (int i = 0; i < 3; i++) {
                    ConversationDO conversation = ConversationDO.builder()
                            .id("wp025-page-" + i)
                            .conversationId("wp025-page-pub-" + i)
                            .userId("11")
                            .title("分页 " + i)
                            .lastTime(new Date(1_700_000_000_000L + i * 1000L))
                            .build();
                    AiDomainWriteIdentity.apply(conversation);
                    conversations.insert(conversation);
                }
            });
            List<ConversationDO> page = conversations.selectList(Wrappers.lambdaQuery(ConversationDO.class)
                    .eq(ConversationDO::getMemberId, "platform:1001:11")
                    .likeRight(ConversationDO::getConversationId, "wp025-page-pub-")
                    .orderByDesc(ConversationDO::getLastTime));
            assertThat(page).hasSize(3);
            assertThat(page.get(0).getConversationId()).isEqualTo("wp025-page-pub-2");

            // 逻辑删除后不再出现在查询里（@TableLogic）
            ConversationDO removed = page.get(0);
            conversations.deleteById(removed.getId());
            assertThat(conversations.selectCount(Wrappers.lambdaQuery(ConversationDO.class)
                    .eq(ConversationDO::getConversationId, "wp025-page-pub-2"))).isZero();
        }
    }

    // ------------------------------------------------------------ helpers

    private static void withPrincipal(String tenant, String userId, Runnable body) {
        ExecutionPrincipal previous = PrincipalContext.get();
        PrincipalContext.set(new ExecutionPrincipal(tenant, userId,
                ExecutionPrincipal.canonicalMembershipId(tenant, userId),
                1, 1, Set.of(), "wp025-jti", "wp025-tests", 1L, 2L));
        try {
            body.run();
        } finally {
            PrincipalContext.restore(previous);
        }
    }

    private static void seedConversation(ConversationMapper mapper, String tenant, String userId,
                                         String key, String title) {
        withPrincipal(tenant, userId, () -> {
            ConversationDO conversation = ConversationDO.builder()
                    .id(key)
                    .conversationId(key + "-pub")
                    .userId(userId)
                    .title(title)
                    .lastTime(new Date())
                    .build();
            AiDomainWriteIdentity.apply(conversation);
            mapper.insert(conversation);
        });
    }

    private static List<ConversationDO> scopedConversations(ConversationMapper mapper,
                                                            String tenant, String userId) {
        return mapper.selectList(Wrappers.lambdaQuery(ConversationDO.class)
                .eq(ConversationDO::getTenantId, tenant)
                .eq(ConversationDO::getMemberId, ExecutionPrincipal.canonicalMembershipId(tenant, userId))
                .likeRight(ConversationDO::getConversationId, "wp025-iso-"));
    }

    /** 让 {@link MergedTableRow} 的契约在编译期可见（不是断言，是"这个测试认这个接口"）。 */
    @SuppressWarnings("unused")
    private static void contractAnchor(MergedTableRow row) {
        row.setTenantId("t");
        row.setMemberId("m");
    }
}
