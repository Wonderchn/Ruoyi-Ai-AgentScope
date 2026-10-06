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

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 会话/消息的租户成员作用域只读仓储（P1.3d）。
 *
 * <p>查询恒带 {@code tenant_id + member_id}：会话是本成员的私有数据，
 * 拿到 conversationId 不等于拿到读取权。跨租户 ID 与不存在同样返回
 * {@code empty}/{@code List.of()}——不区分"无权"与"不存在"，调用方一律按 404 外显。
 *
 * <p>默认不装配（{@code ai.integration.enabled=true}）。
 */
@Slf4j
@Repository
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class TenantConversationReadRepository {

    private final JdbcTemplate jdbc;

    public TenantConversationReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ConversationRow> findConversation(String tenantId, String memberId, String conversationId) {
        requireScope(tenantId, memberId);
        if (conversationId == null || conversationId.isBlank()) {
            // 用 null 去查不是"找不到"，是调用方 bug：大声拒绝而不是发一条必空的 SQL
            throw new ClientException("conversationId 不能为空");
        }
        String sql = "SELECT conversation_id, title, last_time FROM platform.ai_conversation"
                + " WHERE tenant_id = ? AND member_id = ? AND conversation_id = ? AND deleted = 0";
        return jdbc.query(sql, (rs, rowNum) -> new ConversationRow(
                        rs.getString("conversation_id"),
                        rs.getString("title"),
                        rs.getTimestamp("last_time")),
                tenantId, memberId, conversationId).stream().findFirst();
    }

    /**
     * 历史读取选择的列（**唯一来源**：SQL 与构建期护栏都读这一份）。
     *
     * <p>抽成常量是为了让"完整历史"这件事可以被判据检查：护栏把它与冻结形状的列集合对照，
     * 缺列或写了不存在的列都会失败。如果这里退回成 SQL 里的内联字符串，
     * 就只能靠真库测试发现——而真库测试在没有 URL 时是跳过的。
     */
    static final List<String> MESSAGE_COLUMNS = List.of(
            "id", "role", "content", "message_status", "create_time",
            "thinking_content", "thinking_duration",
            "sources::text AS sources",
            "recommended_questions::text AS recommended_questions",
            "retrieved_chunks::text AS retrieved_chunks",
            "reply_to_message_id",
            // WP-035B：覆盖护栏逐列要求"选它或显式排除"时，这两列被判定属于 F03 的历史要求——
            // model_name 是"模型绑定"（F03 验收点），total_tokens 是逐消息用量。
            "model_name", "total_tokens");

    /**
     * 消息分页：(tenant, member, conversation) + 时间序；limit 由调用方给出（1..200 收敛在调用方）。
     *
     * <p><b>WP-035A：完整历史。</b>此前只取 {@code id/role/content/message_status/create_time}，
     * 把 {@code thinking_content}、{@code thinking_duration}、{@code sources}、
     * {@code recommended_questions}、{@code retrieved_chunks}、{@code reply_to_message_id} 全丢了。
     * F03 的验收明确要求"历史包含附件、引用、工具、模型/工作流绑定，不只保留 message.content"，
     * 所以这些列必须回到读取路径上——否则前端再完整也拿不到数据。
     *
     * <p>三个 jsonb 列显式 {@code ::text} 取回：PG JDBC 虽然也能用 {@code getString} 读 jsonb，
     * 但显式转换让返回类型在 SQL 层就确定，不依赖驱动行为；文本形式是无损的，
     * 解析交给调用方（前端已经按 JSON 处理）。
     *
     * <p>范围与过滤条件保持逐字不变（tenant + member + conversation + deleted = 0），
     * 分页上限仍由调用方收敛在 1..200——补字段不允许放宽任何一条。
     */
    public List<MessageRow> listMessages(String tenantId, String memberId, String conversationId,
                                         long offset, int limit) {
        requireScope(tenantId, memberId);
        if (offset < 0 || limit < 1 || limit > 200) {
            throw new ClientException("分页参数非法");
        }
        String sql = "SELECT " + String.join(", ", MESSAGE_COLUMNS)
                + " FROM platform.ai_message"
                + " WHERE tenant_id = ? AND member_id = ? AND conversation_id = ? AND deleted = 0"
                + " ORDER BY create_time ASC, id ASC LIMIT ? OFFSET ?";
        return jdbc.query(sql, (rs, rowNum) -> new MessageRow(
                        rs.getString("id"),
                        rs.getString("role"),
                        rs.getString("content"),
                        rs.getString("message_status"),
                        rs.getTimestamp("create_time"),
                        rs.getString("thinking_content"),
                        rs.getObject("thinking_duration", Integer.class),
                        rs.getString("sources"),
                        rs.getString("recommended_questions"),
                        rs.getString("retrieved_chunks"),
                        rs.getString("reply_to_message_id"),
                        rs.getString("model_name"),
                        rs.getObject("total_tokens", Integer.class)),
                tenantId, memberId, conversationId, limit, offset);
    }

    /** 会话计数：统计必须与列表同范围，不允许全库分母。 */
    public List<ConversationRow> listConversations(String tenantId,String memberId,long offset,int limit) {
        requireScope(tenantId,memberId);
        if(offset<0 || limit<1 || limit>200){throw new ClientException("分页参数非法");}
        return jdbc.query("SELECT conversation_id,title,last_time FROM platform.ai_conversation WHERE tenant_id=? AND member_id=?"
                +" AND deleted=0 ORDER BY last_time DESC NULLS LAST,conversation_id LIMIT ? OFFSET ?",
                (rs,n)->new ConversationRow(rs.getString(1),rs.getString(2),rs.getTimestamp(3)),tenantId,memberId,limit,offset);
    }

    public long countConversations(String tenantId, String memberId) {
        requireScope(tenantId, memberId);
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM platform.ai_conversation WHERE tenant_id = ? AND member_id = ? AND deleted = 0",
                Long.class, tenantId, memberId);
        return count == null ? 0L : count;
    }

    private static void requireScope(String tenantId, String memberId) {
        if (tenantId == null || tenantId.isBlank() || memberId == null || memberId.isBlank()) {
            throw new ClientException("tenantId/memberId 均不能为空");
        }
    }

    public record ConversationRow(String conversationId, String title, Timestamp lastTime) {
    }

    /**
     * 一条消息的完整历史视图。
     *
     * <p>jsonb 列以 JSON 文本返回（无损）；{@code thinkingDuration} 可能为 NULL，
     * 所以用包装类型而不是 {@code int}——用基本类型会把 NULL 静默变成 0，
     * 而"没记录耗时"和"耗时 0 毫秒"是两件事。
     */
    public record MessageRow(String id, String role, String content, String messageStatus,
                             Timestamp createTime, String thinkingContent, Integer thinkingDuration,
                             String sources, String recommendedQuestions, String retrievedChunks,
                             String replyToMessageId, String modelName, Integer totalTokens) {
    }
}
