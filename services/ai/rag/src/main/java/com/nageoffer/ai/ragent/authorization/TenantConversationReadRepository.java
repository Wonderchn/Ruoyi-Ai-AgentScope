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
        String sql = "SELECT conversation_id, title, last_time FROM t_conversation"
                + " WHERE tenant_id = ? AND member_id = ? AND conversation_id = ? AND deleted = 0";
        return jdbc.query(sql, (rs, rowNum) -> new ConversationRow(
                        rs.getString("conversation_id"),
                        rs.getString("title"),
                        rs.getTimestamp("last_time")),
                tenantId, memberId, conversationId).stream().findFirst();
    }

    /** 消息分页：(tenant, member, conversation) + 时间序；limit 由调用方给出（1..200 收敛在调用方）。 */
    public List<MessageRow> listMessages(String tenantId, String memberId, String conversationId,
                                         long offset, int limit) {
        requireScope(tenantId, memberId);
        if (offset < 0 || limit < 1 || limit > 200) {
            throw new ClientException("分页参数非法");
        }
        String sql = "SELECT id, role, content, message_status, create_time FROM t_message"
                + " WHERE tenant_id = ? AND member_id = ? AND conversation_id = ? AND deleted = 0"
                + " ORDER BY create_time ASC, id ASC LIMIT ? OFFSET ?";
        return jdbc.query(sql, (rs, rowNum) -> new MessageRow(
                        rs.getString("id"),
                        rs.getString("role"),
                        rs.getString("content"),
                        rs.getString("message_status"),
                        rs.getTimestamp("create_time")),
                tenantId, memberId, conversationId, limit, offset);
    }

    /** 会话计数：统计必须与列表同范围，不允许全库分母。 */
    public long countConversations(String tenantId, String memberId) {
        requireScope(tenantId, memberId);
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM t_conversation WHERE tenant_id = ? AND member_id = ? AND deleted = 0",
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

    public record MessageRow(String id, String role, String content, String messageStatus, Timestamp createTime) {
    }
}
