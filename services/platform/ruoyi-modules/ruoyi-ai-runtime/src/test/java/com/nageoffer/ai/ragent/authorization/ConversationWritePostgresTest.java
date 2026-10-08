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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WP-034B：F03 会话写入（重命名 / 软删）在**真实 PostgreSQL** 上的语义判据。
 *
 * <p>为什么必须有这一层：写服务里那两条 SQL 的正确性靠读代码看不出来——它依赖
 * {@code tenant_id + member_id} 双限定、{@code deleted = 0} 过滤、以及"0 行 = 不存在"的语义，
 * 而这三样都只在真库上才会真的生效。合成/单测替身会把这些条件悄悄放过。
 *
 * <p>表形状**不是手写的**：本测试从冻结迁移
 * （{@code docs/script/sql/postgres/V7__unified_ai_domain.sql} 的 {@code CREATE TABLE}
 * 加 V7..V12 里针对该表的 {@code ALTER TABLE}）里抽出来执行，所以被测的表就是冻结形状本身；
 * 手写一份 CREATE 会立刻产生"测试通过、生产列不同"的分叉。
 *
 * <p>需要 {@code -Dragent.conversation.test.jdbc-url=...}；未提供时**显式跳过**并带原因，
 * 不是静默通过。
 */
@Tag("dev")
@EnabledIfSystemProperty(named = ConversationWritePostgresTest.URL_PROPERTY, matches = ".+",
        disabledReason = "需要隔离 PostgreSQL：-D" + ConversationWritePostgresTest.URL_PROPERTY
                + "=jdbc:postgresql://host:port/db")
class ConversationWritePostgresTest {

    static final String URL_PROPERTY = "ragent.conversation.test.jdbc-url";

    private static final String TENANT_A = "T-CONV-A";
    private static final String TENANT_B = "T-CONV-B";
    private static final String MEMBER_A1 = "platform:T-CONV-A:11";
    private static final String MEMBER_A2 = "platform:T-CONV-A:12";

    private static JdbcTemplate jdbc;
    private static NamedParameterJdbcTemplate named;
    private static TenantConversationReadRepository conversations;

    @BeforeAll
    static void setUp() throws IOException {
        String url = System.getProperty(URL_PROPERTY);
        assertThat(url).as("缺少 -D" + URL_PROPERTY).isNotBlank();
        DriverManagerDataSource ds = new DriverManagerDataSource(url, "postgres", "");
        jdbc = new JdbcTemplate(ds);
        named = new NamedParameterJdbcTemplate(ds);
        conversations = new TenantConversationReadRepository(jdbc);

        jdbc.execute("CREATE SCHEMA IF NOT EXISTS platform");
        // 先删子表：ai_message 有指向本表的外键，另一个测试类可能刚建过它
        jdbc.execute("DROP TABLE IF EXISTS platform.ai_message CASCADE");
        jdbc.execute("DROP TABLE IF EXISTS platform.ai_conversation CASCADE");
        for (String ddl : FrozenTableDdl.forTable("ai_conversation")) {
            jdbc.execute(ddl);
        }
        // 形状断言：列必须来自冻结迁移，缺一列就说明抽取逻辑错了
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema='platform'"
                        + " AND table_name='ai_conversation'", String.class);
        assertThat(columns)
                .as("从冻结迁移抽出的 ai_conversation 形状")
                .contains("id", "conversation_id", "user_id", "title", "last_time", "deleted",
                        "tenant_id", "member_id");

        // 每个判据自己播种自己用的行：JUnit 的默认执行顺序不保证，
        // 共享行会让"前一个判据把行删了"变成后一个判据的假失败。
        jdbc.update("DELETE FROM platform.ai_conversation");
    }

    @Test
    @DisplayName("正例：重命名命中 1 行、标题落库、读路径立刻看到新标题")
    void renameUpdatesExactlyOneRowAndTheReadPathSeesIt() {
        seed("c-rename", TENANT_A, MEMBER_A1, "原标题", 0);
        assertThat(conversations.findConversation(TENANT_A, MEMBER_A1, "c-rename"))
                .get().extracting(TenantConversationReadRepository.ConversationRow::title)
                .isEqualTo("原标题");

        int rows = named.update(AiResourceWriteService.SQL_RENAME_CONVERSATION,
                renameParams("改后的标题", TENANT_A, MEMBER_A1, "c-rename", null));
        assertThat(rows).as("命中 1 行").isEqualTo(1);
        assertThat(conversations.findConversation(TENANT_A, MEMBER_A1, "c-rename"))
                .get().extracting(TenantConversationReadRepository.ConversationRow::title)
                .isEqualTo("改后的标题");
    }

    @Test
    @DisplayName("正例：软删命中 1 行，读路径立刻不再返回它")
    void softDeleteHidesTheConversationFromTheReadPath() {
        seed("c-delete", TENANT_A, MEMBER_A1, "待删", 0);
        int rows = named.update(AiResourceWriteService.SQL_SOFT_DELETE_CONVERSATION, Map.of(
                "tenant", TENANT_A, "member", MEMBER_A1, "conversation", "c-delete"));
        assertThat(rows).isEqualTo(1);
        assertThat(conversations.findConversation(TENANT_A, MEMBER_A1, "c-delete"))
                .as("deleted = 0 过滤必须让它立刻不可见")
                .isEmpty();
        assertThat(conversations.listConversations(TENANT_A, MEMBER_A1, 0, 100))
                .extracting(TenantConversationReadRepository.ConversationRow::conversationId)
                .doesNotContain("c-delete");
        // 行还在（软删，不物理删）：消息与审计链保留
        Integer still = jdbc.queryForObject(
                "SELECT count(*) FROM platform.ai_conversation WHERE conversation_id='c-delete'",
                Integer.class);
        assertThat(still).as("软删必须保留行本身").isEqualTo(1);
    }

    @Test
    @DisplayName("负例：跨租户 / 跨成员 / 已删除 都是 0 行（按不存在处理，不泄露存在性）")
    void crossScopeAndAlreadyDeletedAffectNoRows() {
        seed("c-scope", TENANT_A, MEMBER_A1, "受保护", 0);
        seed("c-gone", TENANT_A, MEMBER_A1, "已删除", 1);
        assertThat(named.update(AiResourceWriteService.SQL_RENAME_CONVERSATION,
                renameParams("越权改名", TENANT_B, MEMBER_A1, "c-scope", null)))
                .as("另一租户不能改名").isZero();
        assertThat(named.update(AiResourceWriteService.SQL_RENAME_CONVERSATION,
                renameParams("越权改名", TENANT_A, MEMBER_A2, "c-scope", null)))
                .as("同租户另一成员不能改名").isZero();
        assertThat(named.update(AiResourceWriteService.SQL_SOFT_DELETE_CONVERSATION, Map.of(
                "tenant", TENANT_A, "member", MEMBER_A1, "conversation", "c-gone")))
                .as("已删除的行再次删除是 0 行，不制造版本噪声").isZero();
        assertThat(named.update(AiResourceWriteService.SQL_SOFT_DELETE_CONVERSATION, Map.of(
                "tenant", TENANT_A, "member", MEMBER_A1, "conversation", "no-such-id")))
                .as("不存在的会话是 0 行").isZero();
    }

    @Test
    @DisplayName("负例：超长标题被数据库拒绝，而不是被静默截断")
    void overlongTitleIsRejectedNotTruncated() {
        seed("c-long", TENANT_A, MEMBER_A1, "原标题", 0);
        String tooLong = "x".repeat(AiResourceWriteService.CONVERSATION_TITLE_MAX + 1);
        assertThatThrownBy(() -> named.update(AiResourceWriteService.SQL_RENAME_CONVERSATION,
                renameParams(tooLong, TENANT_A, MEMBER_A1, "c-long", null)))
                .as("title 是 VARCHAR(128)：超长必须显式失败，静默截断会让用户看到的内容与存的不一致")
                .hasMessageContaining("too long");
        assertThat(conversations.findConversation(TENANT_A, MEMBER_A1, "c-long"))
                .get().extracting(TenantConversationReadRepository.ConversationRow::title)
                .isEqualTo("原标题");
    }

    @Test
    @DisplayName("版本正负例：匹配版本更新并递增；旧版本不写、不改标题")
    void matchingVersionWritesAndStaleVersionLeavesTheRowUnchanged() {
        seed("c-version", TENANT_A, MEMBER_A1, "原版本标题", 0);
        Long initial = versionOf("c-version");
        assertThat(named.update(AiResourceWriteService.SQL_RENAME_CONVERSATION,
                renameParams("新版本标题", TENANT_A, MEMBER_A1, "c-version", initial)))
                .as("当前版本命中一行").isEqualTo(1);
        assertThat(versionOf("c-version")).isEqualTo(initial + 1);
        assertThat(named.update(AiResourceWriteService.SQL_RENAME_CONVERSATION,
                renameParams("旧版本覆盖", TENANT_A, MEMBER_A1, "c-version", initial)))
                .as("旧版本不得覆盖新标题").isZero();
        assertThat(versionOf("c-version")).isEqualTo(initial + 1);
        assertThat(conversations.findConversation(TENANT_A, MEMBER_A1, "c-version"))
                .get().extracting(TenantConversationReadRepository.ConversationRow::title)
                .isEqualTo("新版本标题");
    }

    // ------------------------------------------------------------------ helpers

    private static MapSqlParameterSource renameParams(String title, String tenant, String member,
                                                      String conversation, Long expectedVersion) {
        // Map.of cannot carry null. Missing version is still a supplied nullable SQL parameter,
        // exercising production's CAST(:expectedVersion AS bigint) rather than failing pre-SQL.
        return new MapSqlParameterSource().addValue("title", title).addValue("tenant", tenant)
                .addValue("member", member).addValue("conversation", conversation)
                .addValue("expectedVersion", expectedVersion);
    }

    private static Long versionOf(String conversation) {
        return named.queryForObject(AiResourceWriteService.SQL_CONVERSATION_VERSION,
                Map.of("tenant", TENANT_A, "member", MEMBER_A1, "conversation", conversation), Long.class);
    }

    private static void seed(String conversationId, String tenantId, String memberId, String title,
                             int deleted) {
        jdbc.update("INSERT INTO platform.ai_conversation"
                        + " (id, conversation_id, user_id, title, last_time, create_time, update_time,"
                        + " deleted, tenant_id, member_id)"
                        + " VALUES (?,?,?,?, now(), now(), now(), ?,?,?)",
                "id-" + conversationId, conversationId, "11", title, deleted, tenantId, memberId);
    }

}
