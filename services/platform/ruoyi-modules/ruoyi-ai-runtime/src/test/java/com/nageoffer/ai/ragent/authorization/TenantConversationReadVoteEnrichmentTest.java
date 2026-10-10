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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F17-A1 / op3：既有消息读面（{@code GET /api/ai/v1/conversations/{id}/messages}）的
 * <b>vote 富化契约</b>（构建期，不需要数据库）。
 *
 * <p><b>要证明的一件事。</b>消息列表读面返回的每条消息必须带上<b>当前用户自己</b>
 * 的有效反馈值（1=赞 / -1=踩；无有效反馈为 null），且这次富化：
 * <ul>
 *   <li><b>零新路由</b>：仍然是 {@code TenantConversationReadRepository.listMessages}
 *       这一条既有读路径（{@code ROUTES:121}），不是新增的查询面；</li>
 *   <li><b>限域</b>：反馈查询恒带 {@code tenant_id + user_id + deleted = 0}
 *       （取消占位 vote=0/deleted=1 不出现在读面——与旧链 {@code getUserVotes} 同口径），
 *       并且只按<b>本页消息 id</b> 取（IN 列表大小 = 页大小，不用全库分母）；</li>
 *   <li><b>不泄露他人反馈</b>：{@code user_id} 谓词是"我的赞踩"的唯一来源
 *       （写入侧冲突键同为 {@code (tenant, message, user)}）。</li>
 * </ul>
 *
 * <p>本类用<b>伪 JdbcTemplate</b>（记录 SQL 与绑定参数、按 SQL 返回预置行）把编排层钉死；
 * SQL 的真实语义（user 隔离、deleted=0 过滤）由真库判据兜底：
 * {@code ConversationHistoryPostgresTest}（仓储层）与 {@code LocalConversationHistoryPostgresE2ETest}
 * （公开路由层），两者都在提供 {@code -D...jdbc-url} 时才运行（未提供显式跳过）。
 */
@Tag("dev")
class TenantConversationReadVoteEnrichmentTest {

    @Test
    @DisplayName("消息读面带 vote：当前用户的有效反馈就地富化，未反馈为 null（JSON 字段可见）")
    void messagesCarryCurrentUsersActiveVote() throws Exception {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        jdbc.messageRows = List.of(message("m-1", "user"), message("m-2", "assistant"));
        jdbc.feedbackVotes = List.of(vote("m-2", 1));

        TenantConversationReadRepository repository = new TenantConversationReadRepository(jdbc);
        List<TenantConversationReadRepository.MessageRow> rows =
                repository.listMessages("T1", "platform:T1:11", "11", "conv-1", 0, 100);

        assertThat(rows.get(1).vote()).as("助手消息带回当前用户的赞").isEqualTo(1);
        assertThat(rows.get(0).vote()).as("没有反馈的消息为 null（不是 0）").isNull();

        String json = new ObjectMapper().writeValueAsString(rows);
        assertThat(json)
                .as("既有消息读面的 JSON 必须携带 vote 字段（富化，不新增路由）")
                .contains("\"vote\":1")
                .contains("\"vote\":null");
    }

    @Test
    @DisplayName("反馈查询按 (tenant, user, deleted=0, 本页 message_id IN) 限域，空页不发查询")
    void feedbackQueryIsScopedAndBoundedToThePage() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        jdbc.messageRows = List.of(message("m-1", "user"), message("m-2", "assistant"));
        jdbc.feedbackVotes = List.of(vote("m-2", -1));

        TenantConversationReadRepository repository = new TenantConversationReadRepository(jdbc);
        repository.listMessages("T1", "platform:T1:11", "11", "conv-1", 0, 100);

        List<String> voteSql = jdbc.sqlContaining("ai_message_feedback");
        assertThat(voteSql).as("富化必须复用既有读面（零新路由）：恰一次限域反馈查询").hasSize(1);
        assertThat(voteSql.get(0))
                .contains("tenant_id = ?")
                .contains("user_id = ?")
                .contains("deleted = 0")
                .as("IN 列表大小 = 页大小（两行 ⇒ 两个占位符）").contains("message_id IN (?, ?)");
        assertThat(jdbc.argsOf("ai_message_feedback"))
                .as("绑定顺序：tenant, user, 本页消息 id")
                .containsExactly("T1", "11", "m-1", "m-2");

        // 空页：没有 id 可查，不发必空的反馈 SQL
        RecordingJdbcTemplate emptyJdbc = new RecordingJdbcTemplate();
        new TenantConversationReadRepository(emptyJdbc).listMessages("T1", "platform:T1:11", "11", "conv-1", 0, 100);
        assertThat(emptyJdbc.sqlContaining("ai_message_feedback")).isEmpty();
    }

    @Test
    @DisplayName("缺 userId 在触达 SQL 之前就拒绝：vote 不允许匿名回退")
    void blankUserIsRejectedBeforeAnySql() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        TenantConversationReadRepository repository = new TenantConversationReadRepository(jdbc);

        assertThatThrownBy(() -> repository.listMessages("T1", "platform:T1:11", " ", "conv-1", 0, 10))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("userId");
        assertThatThrownBy(() -> repository.listMessages("T1", "platform:T1:11", null, "conv-1", 0, 10))
                .isInstanceOf(ClientException.class);
        assertThat(jdbc.sqlContaining("")).as("拒绝发生在任何 SQL 之前").isEmpty();
    }

    // ---------------------------------------------------------------- 台架

    /** 记录 SQL 与绑定参数、按 SQL 返回预置行的伪 JdbcTemplate（不连数据库）。 */
    static final class RecordingJdbcTemplate extends JdbcTemplate {

        List<TenantConversationReadRepository.MessageRow> messageRows = List.of();
        List<Map.Entry<String, Integer>> feedbackVotes = List.of();

        private final List<String> sqls = new ArrayList<>();
        private final List<Object[]> args = new ArrayList<>();

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... bindArgs) {
            sqls.add(sql);
            args.add(bindArgs);
            if (sql.contains("ai_message_feedback")) {
                return cast(feedbackVotes);
            }
            return cast(messageRows);
        }

        List<String> sqlContaining(String fragment) {
            List<String> out = new ArrayList<>();
            for (String sql : sqls) {
                if (sql.contains(fragment)) {
                    out.add(sql);
                }
            }
            return out;
        }

        /** 与 {@link #sqlContaining} 同口径取第一条命中 SQL 的绑定参数。 */
        List<Object> argsOf(String fragment) {
            for (int i = 0; i < sqls.size(); i++) {
                if (sqls.get(i).contains(fragment)) {
                    return List.of(args.get(i));
                }
            }
            return List.of();
        }

        @SuppressWarnings("unchecked")
        private static <T> List<T> cast(List<?> rows) {
            return (List<T>) rows;
        }
    }

    private static Map.Entry<String, Integer> vote(String messageId, Integer vote) {
        return new AbstractMap.SimpleImmutableEntry<>(messageId, vote);
    }

    private static TenantConversationReadRepository.MessageRow message(String id, String role) {
        return new TenantConversationReadRepository.MessageRow(id, role, "内容-" + id, "NORMAL",
                new Timestamp(1_700_000_000_000L), null, null, null, null, null, null, null, null, null);
    }
}
