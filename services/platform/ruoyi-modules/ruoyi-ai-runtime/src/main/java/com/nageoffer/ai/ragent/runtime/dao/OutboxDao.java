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

package com.nageoffer.ai.ragent.runtime.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** outbox 持久投递任务 DAO（PG 首期方案；无 MQ）。 */
@Repository
public class OutboxDao {

    private final JdbcTemplate jdbc;

    public OutboxDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 一条被认领的待投递行。
     *
     * <p><b>为什么带上 {@code payload} 与 {@code operationKey}。</b>C12.2 要求交给传输实现的
     * {@code OutboxMessage} 携带 {@code payload}（JSON 文本）与可空的 {@code operationKey}。
     * 这两个字段在 {@code outbox_event} 里的形态不同：{@code payload} 是既有的
     * {@code JSONB} 列（V7），而 {@code operationKey} <b>没有独立列</b> —— 它按约定放在
     * 载荷里。所以这里不新加列、不加迁移，直接
     * {@code payload ->> 'operationKey'} 读出来：既满足契约，也不虚构存储。
     * {@code payload::text} 取文本形态，避免传输层再碰 JSONB 的驱动细节。
     */
    public record OutboxRow(String tenantId, String eventId, String runId, String eventType, Long seq,
                            int attemptCount, String payload, String operationKey) {
    }

    /** 认领待投递行；locked_until 过期后可被再次认领（at-least-once）。 */
    public List<OutboxRow> claim(String workerId, int lockSeconds, int batch) {
        return jdbc.query(
                "UPDATE outbox_event o SET locked_by=?, locked_until=now() + (? * interval '1 second'), "
                        + "attempt_count=o.attempt_count+1 "
                        + "WHERE (o.tenant_id, o.event_id) IN ("
                        + "  SELECT tenant_id, event_id FROM outbox_event "
                        + "  WHERE state='PENDING' AND next_attempt_at <= now() "
                        + "  ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT ?) "
                        + "RETURNING o.tenant_id, o.event_id, o.run_id, o.event_type, o.seq, o.attempt_count, "
                        + "o.payload::text AS payload_text, o.payload ->> 'operationKey' AS operation_key",
                (rs, rowNum) -> new OutboxRow(rs.getString("tenant_id"), rs.getString("event_id"),
                        rs.getString("run_id"), rs.getString("event_type"),
                        rs.getObject("seq") == null ? null : rs.getLong("seq"), rs.getInt("attempt_count"),
                        rs.getString("payload_text"), rs.getString("operation_key")),
                workerId, lockSeconds, batch);
    }

    public void markPublished(String tenantId, String eventId) {
        jdbc.update("UPDATE outbox_event SET state='PUBLISHED', published_at=now(), locked_by=NULL, locked_until=NULL, "
                        + "last_error=NULL WHERE tenant_id=? AND event_id=? AND state='PENDING'",
                tenantId, eventId);
    }

    public void markRetry(String tenantId, String eventId, String error, int backoffSeconds, boolean deadLetter) {
        if (deadLetter) {
            jdbc.update("UPDATE outbox_event SET state='DEAD_LETTER', last_error=?, locked_by=NULL, locked_until=NULL "
                            + "WHERE tenant_id=? AND event_id=? AND state='PENDING'",
                    truncate(error), tenantId, eventId);
            return;
        }
        jdbc.update("UPDATE outbox_event SET next_attempt_at=now() + (? * interval '1 second'), last_error=?, "
                        + "locked_by=NULL, locked_until=NULL "
                        + "WHERE tenant_id=? AND event_id=? AND state='PENDING'",
                backoffSeconds, truncate(error), tenantId, eventId);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 400 ? value : value.substring(0, 400);
    }

    public long countPending() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE state='PENDING'", Long.class);
        return count == null ? 0 : count;
    }

    public long countByState(String state) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE state=?", Long.class, state);
        return count == null ? 0 : count;
    }
}
