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

package com.nageoffer.ai.ragent.authorization;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WP-035A：F03 会话历史的**完整字段**读取，在真实 PostgreSQL 上验证。
 *
 * <p>此前 {@code listMessages} 只取 {@code id/role/content/message_status/create_time}，
 * 把思考内容/耗时、引用来源、推荐问题、检索片段、回复关系全丢了——前端再完整也拿不到数据。
 * 本判据盯的就是"这些列真的回来了"，以及**没有**因为补字段而放宽范围与过滤。
 *
 * <p>表形状从冻结迁移抽出（{@link FrozenTableDdl}），需要
 * {@code -Dragent.conversation.test.jdbc-url=...}；未提供时显式跳过。
 */
@Tag("dev")
@EnabledIfSystemProperty(named = ConversationHistoryPostgresTest.URL_PROPERTY, matches = ".+",
        disabledReason = "需要隔离 PostgreSQL：-D" + ConversationHistoryPostgresTest.URL_PROPERTY
                + "=jdbc:postgresql://host:port/db")
class ConversationHistoryPostgresTest {

    static final String URL_PROPERTY = "ragent.conversation.test.jdbc-url";

    private static final String TENANT = "T-HIST";
    private static final String MEMBER = "platform:T-HIST:21";
    private static final String OTHER_MEMBER = "platform:T-HIST:22";
    private static final String OTHER_TENANT = "T-HIST-OTHER";
    private static final String CONV = "conv-hist-1";
    /**
     * 同一租户内 conversation_id 同时受两条唯一键约束：
     * {@code uk_conversation_tenant (tenant_id, conversation_id, user_id)}（V7:2029）与
     * {@code uk_conversation_tenant_business (tenant_id, conversation_id, member_id)}（V7:2088）。
     * 所以"同租户两个成员共用同一个 conversation_id"在 schema 上是不允许的——
     * 跨成员隔离必须用各自不同的会话 id 来验证。
     */
    private static final String CONV_OTHER_MEMBER = "conv-hist-2";

    private static JdbcTemplate jdbc;
    private static TenantConversationReadRepository conversations;

    @BeforeAll
    static void setUp() throws IOException {
        String url = System.getProperty(URL_PROPERTY);
        assertThat(url).as("缺少 -D" + URL_PROPERTY).isNotBlank();
        DriverManagerDataSource ds = new DriverManagerDataSource(url, "postgres", "");
        jdbc = new JdbcTemplate(ds);
        conversations = new TenantConversationReadRepository(jdbc);

        jdbc.execute("CREATE SCHEMA IF NOT EXISTS platform");
        // ai_message 有指向 ai_conversation 的外键（fk_message_conversation），
        // ai_message_feedback 有指向 ai_message 的外键（fk_message_feedback_message）——
        // 删除按"子表在前"、创建按"父表在前"；两个测试类各自建自己需要的表，谁先跑都不受影响。
        jdbc.execute("DROP TABLE IF EXISTS platform.ai_message_feedback");
        jdbc.execute("DROP TABLE IF EXISTS platform.ai_message");
        jdbc.execute("DROP TABLE IF EXISTS platform.ai_conversation");
        for (String ddl : FrozenTableDdl.forTable("ai_conversation")) {
            jdbc.execute(ddl);
        }
        for (String ddl : FrozenTableDdl.forTable("ai_message")) {
            jdbc.execute(ddl);
        }
        // F17-A1 / op3：读面 vote 富化的真库判据要用到反馈表
        for (String ddl : FrozenTableDdl.forTable("ai_message_feedback")) {
            jdbc.execute(ddl);
        }
        // 形状锚点：这些列必须来自冻结迁移，否则下面的断言无从谈起
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema='platform'"
                        + " AND table_name='ai_message'", String.class);
        assertThat(columns)
                .as("从冻结迁移抽出的 ai_message 形状")
                .contains("id", "conversation_id", "role", "content", "message_status", "deleted",
                        "tenant_id", "member_id", "thinking_content", "thinking_duration",
                        "sources", "recommended_questions", "retrieved_chunks", "reply_to_message_id");
        // F17-A1 / op3：vote 富化读的是这张表；形状锚点保证下面的真库夹具插入的是冻结形状
        List<String> feedbackColumns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema='platform'"
                        + " AND table_name='ai_message_feedback'", String.class);
        assertThat(feedbackColumns)
                .as("从冻结迁移抽出的 ai_message_feedback 形状")
                .contains("id", "message_id", "conversation_id", "user_id", "vote", "deleted",
                        "tenant_id", "member_id", "create_time", "update_time");

        jdbc.update("DELETE FROM platform.ai_message");
        jdbc.update("DELETE FROM platform.ai_conversation");
        // 父行必须先存在：fk_message_conversation 要求 (tenant_id, conversation_id, member_id)
        // 命中会话行——这正是"消息不能挂到别人的会话上"的数据库级保证。
        seedConversation(CONV, TENANT, MEMBER, "21");
        seedConversation(CONV_OTHER_MEMBER, TENANT, OTHER_MEMBER, "22");
        seedConversation(CONV, OTHER_TENANT, MEMBER, "21");
        insert("m-1", TENANT, MEMBER, CONV, "user", "第一问", "NORMAL",
                null, null, null, null, null, null);
        insert("m-2", TENANT, MEMBER, CONV, "assistant", "第一答", "NORMAL",
                "先想了 3 秒", 3000,
                "[{\"docId\":\"doc-a\",\"page\":7}]",
                "[\"还可以问什么\"]",
                "[{\"chunkId\":\"ch-1\",\"score\":0.9}]",
                "m-1");
        jdbc.update("UPDATE platform.ai_message SET model_name = ?, total_tokens = ? WHERE id = ?",
                "deepseek-chat", 1234, "m-2");
        insert("m-3", TENANT, MEMBER, CONV, "assistant", "已删除的回答", "NORMAL",
                null, null, null, null, null, null);
        jdbc.update("UPDATE platform.ai_message SET deleted = 1 WHERE id = 'm-3'");
        insert("m-other-member", TENANT, OTHER_MEMBER, CONV_OTHER_MEMBER, "assistant", "别人的消息",
                "NORMAL", null, null, null, null, null, null);
        insert("m-other-tenant", OTHER_TENANT, MEMBER, CONV, "assistant", "别的租户的消息",
                "NORMAL", null, null, null, null, null, null);
    }

    @Test
    @DisplayName("完整历史：思考/引用/推荐问题/检索片段/回复关系都回到读取路径上")
    void historyCarriesEveryDeclaredField() {
        List<TenantConversationReadRepository.MessageRow> rows =
                conversations.listMessages(TENANT, MEMBER, "21", CONV, 0, 200);

        assertThat(rows).extracting(TenantConversationReadRepository.MessageRow::id)
                .as("只返回本租户本成员且未删除的两条").containsExactly("m-1", "m-2");

        TenantConversationReadRepository.MessageRow answer = rows.get(1);
        assertThat(answer.content()).isEqualTo("第一答");
        assertThat(answer.thinkingContent()).as("思考内容").isEqualTo("先想了 3 秒");
        assertThat(answer.thinkingDuration()).as("思考耗时").isEqualTo(3000);
        // jsonb 会规范化：**键顺序不保留**、空白被重排（实测 "[{\"page\": 7, \"docId\": \"doc-a\"}]"）。
        // 所以断言必须按 JSON 语义比较，不能按文本比较——文本比较会变成对 PG 内部表示的断言。
        assertThat(json(answer.sources())).as("引用来源")
                .isEqualTo(json("[{\"docId\": \"doc-a\", \"page\": 7}]"));
        assertThat(json(answer.recommendedQuestions()))
                .isEqualTo(json("[\"还可以问什么\"]"));
        assertThat(json(answer.retrievedChunks()))
                .isEqualTo(json("[{\"chunkId\": \"ch-1\", \"score\": 0.9}]"));
        assertThat(answer.replyToMessageId()).as("回复关系").isEqualTo("m-1");
        // WP-035B：覆盖护栏逼出来的两列（F03 的"模型绑定"与逐消息用量）
        assertThat(answer.modelName()).as("模型绑定").isEqualTo("deepseek-chat");
        assertThat(answer.totalTokens()).as("逐消息用量").isEqualTo(1234);
    }

    @Test
    @DisplayName("NULL 与 0 必须可区分：没记录耗时不能变成耗时 0 毫秒")
    void absentValuesStayNullInsteadOfBecomingZero() {
        TenantConversationReadRepository.MessageRow question =
                conversations.listMessages(TENANT, MEMBER, "21", CONV, 0, 200).get(0);
        assertThat(question.thinkingContent()).isNull();
        assertThat(question.thinkingDuration())
                .as("包装类型：NULL 保持 NULL，不能静默变成 0")
                .isNull();
        assertThat(question.sources()).isNull();
        assertThat(question.recommendedQuestions()).isNull();
        assertThat(question.retrievedChunks()).isNull();
        assertThat(question.replyToMessageId()).isNull();
        assertThat(question.modelName()).as("未记录模型时保持 NULL").isNull();
    }

    @Test
    @DisplayName("补字段没有放宽范围：跨成员/跨租户/已删除都读不到")
    void scopeAndSoftDeleteStillApply() {
        List<String> ids = conversations.listMessages(TENANT, MEMBER, "21", CONV, 0, 200).stream()
                .map(TenantConversationReadRepository.MessageRow::id).toList();
        assertThat(ids).doesNotContain("m-3", "m-other-member", "m-other-tenant");
        assertThat(conversations.listMessages(OTHER_TENANT, MEMBER, "21", CONV, 0, 200))
                .extracting(TenantConversationReadRepository.MessageRow::id)
                .containsExactly("m-other-tenant");
        assertThat(conversations.listMessages(TENANT, OTHER_MEMBER, "22", CONV_OTHER_MEMBER, 0, 200))
                .extracting(TenantConversationReadRepository.MessageRow::id)
                .containsExactly("m-other-member");
    }

    @Test
    @DisplayName("分页上限仍然收敛在 1..200，补字段不允许放宽参数校验")
    void paginationBoundsAreUnchanged() {
        assertThatThrownBy(() -> conversations.listMessages(TENANT, MEMBER, "21", CONV, 0, 201))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> conversations.listMessages(TENANT, MEMBER, "21", CONV, -1, 10))
                .isInstanceOf(RuntimeException.class);
        assertThat(conversations.listMessages(TENANT, MEMBER, "21", CONV, 1, 1))
                .extracting(TenantConversationReadRepository.MessageRow::id)
                .containsExactly("m-2");
    }

    @Test
    @DisplayName("F17-A1：vote 只回传当前用户的有效反馈——他人行不计入、取消占位不出现")
    void voteEnrichmentIsScopedToCurrentUserAndHidesCancelledPlaceholders() {
        String mine = "f17fb-mine";
        String other = "f17fb-other";
        try {
            jdbc.update("INSERT INTO platform.ai_message_feedback (id, message_id, conversation_id, user_id,"
                            + " vote, create_time, update_time, deleted, tenant_id, member_id)"
                            + " VALUES (?,?,?,?,?, now(), now(), 0, ?, ?)",
                    mine, "m-2", CONV, "21", 1, TENANT, MEMBER);
            // 对抗性负例：同租户、同成员维度、**另一个 user_id** 的反馈行（冻结 schema 允许：
            // user 是投票人维度，FK 只约束 tenant+message）。user_id 谓词必须让它不出现在 21 的读面。
            jdbc.update("INSERT INTO platform.ai_message_feedback (id, message_id, conversation_id, user_id,"
                            + " vote, create_time, update_time, deleted, tenant_id, member_id)"
                            + " VALUES (?,?,?,?,?, now(), now(), 0, ?, ?)",
                    other, "m-2", CONV, "99", -1, TENANT, MEMBER);
            Long persistedRows = jdbc.queryForObject(
                    "SELECT count(*) FROM platform.ai_message_feedback WHERE tenant_id = ? AND message_id = ?",
                    Long.class, TENANT, "m-2");
            assertThat(persistedRows).as("库里确实有两行（负例不是空跑）").isEqualTo(2L);

            List<TenantConversationReadRepository.MessageRow> rows =
                    conversations.listMessages(TENANT, MEMBER, "21", CONV, 0, 200);
            assertThat(rowOf(rows, "m-2").vote())
                    .as("读面必须带回当前用户自己的有效反馈（1=赞）").isEqualTo(1);
            assertThat(rowOf(rows, "m-1").vote())
                    .as("没有反馈的消息 vote 为 null，不是 0").isNull();

            // 取消占位（vote=0, deleted=1）不出现在读面——与旧链 getUserVotes 的 deleted=0 口径一致
            jdbc.update("UPDATE platform.ai_message_feedback SET vote = 0, deleted = 1, update_time = now()"
                    + " WHERE id = ?", mine);
            assertThat(rowOf(conversations.listMessages(TENANT, MEMBER, "21", CONV, 0, 200), "m-2").vote())
                    .as("取消后的占位行不得作为反馈回传（null≠0）").isNull();
        } finally {
            jdbc.update("DELETE FROM platform.ai_message_feedback WHERE id IN (?, ?)", mine, other);
        }
    }

    private static TenantConversationReadRepository.MessageRow rowOf(
            List<TenantConversationReadRepository.MessageRow> rows, String messageId) {
        return rows.stream().filter(row -> messageId.equals(row.id())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("旧行 NULL last_time 不抢到列表顶部，同时间会话稳定排序")
    void conversationsWithUnknownActivitySortAfterKnownActivity() {
        String tenant = "T-SORT";
        String member = "platform:T-SORT:21";
        try {
            seedConversation("sort-b", tenant, member, "21");
            seedConversation("sort-a", tenant, member, "21");
            seedConversation("sort-unknown", tenant, member, "21");
            jdbc.update("UPDATE platform.ai_conversation SET last_time = TIMESTAMP '2026-01-01 12:00:00'"
                    + " WHERE tenant_id = ? AND member_id = ?", tenant, member);
            jdbc.update("UPDATE platform.ai_conversation SET last_time = NULL"
                    + " WHERE tenant_id = ? AND member_id = ? AND conversation_id = ?",
                    tenant, member, "sort-unknown");
            assertThat(conversations.listConversations(tenant, member, 0, 200))
                    .extracting(TenantConversationReadRepository.ConversationRow::conversationId)
                    .containsExactly("sort-a", "sort-b", "sort-unknown");
        } finally {
            jdbc.update("DELETE FROM platform.ai_conversation WHERE tenant_id = ? AND member_id = ?",
                    tenant, member);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** 按 JSON 语义解析，用于与 jsonb 返回值比较（键顺序与空白都不保证）。 */
    private static com.fasterxml.jackson.databind.JsonNode json(String text) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(text);
        } catch (Exception e) {
            throw new AssertionError("不是合法 JSON: " + text, e);
        }
    }

    private static void seedConversation(String conversationId, String tenantId, String memberId,
                                         String userId) {
        // 主键列是 VARCHAR(20)：用短哈希而不是把 tenant/member 拼进去，否则直接超宽被拒
        String id = "cid" + Integer.toHexString((tenantId + "|" + memberId + "|" + conversationId).hashCode());
        jdbc.update("INSERT INTO platform.ai_conversation"
                        + " (id, conversation_id, user_id, title, last_time, create_time, update_time,"
                        + " deleted, tenant_id, member_id) VALUES (?,?,?,?, now(), now(), now(), 0, ?, ?)",
                id, conversationId, userId, "历史测试", tenantId, memberId);
    }

    private static void insert(String id, String tenantId, String memberId, String conversationId,
                               String role, String content, String status, String thinkingContent,
                               Integer thinkingDuration, String sources, String recommended,
                               String chunks, String replyTo) {
        jdbc.update("INSERT INTO platform.ai_message (id, conversation_id, user_id, role, content,"
                        + " message_status, thinking_content, thinking_duration, sources,"
                        + " recommended_questions, retrieved_chunks, reply_to_message_id,"
                        + " create_time, update_time, deleted, tenant_id, member_id)"
                        + " VALUES (?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?, now(), now(), 0, ?, ?)",
                id, conversationId, "21", role, content, status, thinkingContent, thinkingDuration,
                sources, recommended, chunks, replyTo, tenantId, memberId);
    }
}
