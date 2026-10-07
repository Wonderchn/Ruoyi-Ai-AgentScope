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

/**
 * 正式 run 事件的租户作用域只读仓储（P1.3d）。
 *
 * <p><b>事件不能按裸 eventId 取。</b>ai_run_event 的主键是
 * {@code (tenant_id, run_id, seq)}（V5）：定位一个事件必须先过 run 的
 * {@code (tenant_id, run_id)} 归属，再按 seq 读——否则 eventId/seq 会变成
 * 跨租户的读取凭据。读取按原 seq 升序分页，不做 SSE/seq 生成/Worker（P2）。
 * 默认不装配（{@code ai.integration.enabled=true}）。
 */
@Slf4j
@Repository
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class TenantEventReadRepository {

    private final JdbcTemplate jdbc;

    public TenantEventReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按 (tenant, run) 分页读事件，seq 升序；limit 1..200。
     * run 不属本租户时不会出现该 run 的任何事件行（复合条件自然隔离）。
     */
    public List<EventRow> listEvents(String tenantId, String runId, long afterSeq, int limit) {
        if (tenantId == null || tenantId.isBlank() || runId == null || runId.isBlank()) {
            throw new ClientException("tenantId/runId 均不能为空");
        }
        if (afterSeq < 0 || limit < 1 || limit > 200) {
            throw new ClientException("分页参数非法");
        }
        String sql = "SELECT seq, event_type, payload, created_at FROM ai_run_event"
                + " WHERE tenant_id = ? AND run_id = ? AND seq > ?"
                + " ORDER BY seq ASC LIMIT ?";
        return jdbc.query(sql, (rs, rowNum) -> new EventRow(
                        rs.getLong("seq"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getTimestamp("created_at")),
                tenantId, runId, afterSeq, limit);
    }

    public record EventRow(long seq, String eventType, String payload, Timestamp createdAt) {
    }
}
