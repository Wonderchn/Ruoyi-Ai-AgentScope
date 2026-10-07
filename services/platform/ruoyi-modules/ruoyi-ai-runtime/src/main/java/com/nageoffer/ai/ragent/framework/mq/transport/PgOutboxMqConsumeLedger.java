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

package com.nageoffer.ai.ragent.framework.mq.transport;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * {@link OutboxMqConsumeLedger} 的 PG 实现，落在 V18 的 {@code platform.ai_mq_consume_dedup}。
 *
 * <p><b>认领必须是"一条语句"。</b>{@link #claim} 用
 * {@code INSERT ... ON CONFLICT (tenant_id, dedup_key) DO UPDATE ... WHERE <可再认领条件> RETURNING state}
 * 完成判定：返回一行 = 认领成功，零行 = 重复。用"先 SELECT 再判断"的写法会在两个实例
 * 同时投递同一 key 时双双判为首次（典型 TOCTOU 竞态），副作用就做两次 —— 而
 * "重复消费不产生第二次副作用"正是 C12.4 的第一条。
 *
 * <p><b>可再认领的条件有两条，缺一不可。</b>
 * <ul>
 *   <li>{@code RETRYABLE}：客观失败后允许重来；</li>
 *   <li>{@code PROCESSING} 且超过租约：**消费者崩溃**的出路。少这一条，"崩溃在副作用之前"
 *       的消息被重投时会被当成重复而跳过，事件永久丢失。</li>
 * </ul>
 * {@code CONSUMED} / {@code REJECTED} 是终局，**不被租约绕过**（租约只对 PROCESSING 生效）。
 *
 * <p><b>表不存在时故意不降级。</b>V18 未部署时这里会抛
 * {@code BadSqlGrammarException}。绝不放宽成"没有账本就都算首次" —— 那会把去重静默关掉，
 * 在至少一次投递下直接变成"重复副作用"。
 */
public class PgOutboxMqConsumeLedger implements OutboxMqConsumeLedger {

    /**
     * 原子认领语句。
     *
     * <p><b>该文本与真库验证证据逐字一致</b>：`.scratch/platform-embedded-impl/WP-043/
     * 20261006-t3-v18-consumer-dedup/tools/V18Probe.java` 用的 {@code CLAIM_SQL} 就是这一串，
     * 在真 PG 17.11（t3dev）上跑过 20 项检查（首次认领 / 租约内重复跳过 / 租约过期再认领 /
     * CONSUMED 终局不受租约绕过 / RETRYABLE 可重来 / REJECTED 永不重试 / 跨租户互不影响 /
     * 四条约束负例 / 无残留行）。
     * {@code PgOutboxMqConsumeLedgerSqlContractTest} 钉住关键片段，防止代码与已验证文本漂移。
     */
    public static final String CLAIM_SQL =
            "INSERT INTO platform.ai_mq_consume_dedup "
                    + "(tenant_id, dedup_key, dedup_kind, event_id, event_type, run_id, state, attempt_count, "
                    + " first_seen_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, 'PROCESSING', 1, now(), now()) "
                    + "ON CONFLICT (tenant_id, dedup_key) DO UPDATE "
                    + "  SET state='PROCESSING', attempt_count = ai_mq_consume_dedup.attempt_count + 1, "
                    + "      updated_at = now() "
                    + "  WHERE ai_mq_consume_dedup.state = 'RETRYABLE' "
                    + "     OR (ai_mq_consume_dedup.state = 'PROCESSING' "
                    + "         AND ai_mq_consume_dedup.updated_at < now() - (?::double precision * interval '1 second')) "
                    + "RETURNING state";

    static final String MARK_CONSUMED_SQL =
            "UPDATE platform.ai_mq_consume_dedup SET state='CONSUMED', consumed_at=now(), updated_at=now(), "
                    + "last_error=NULL WHERE tenant_id=? AND dedup_key=? AND state='PROCESSING'";

    static final String MARK_REJECTED_SQL =
            "UPDATE platform.ai_mq_consume_dedup SET state='REJECTED', updated_at=now(), last_error=? "
                    + "WHERE tenant_id=? AND dedup_key=? AND state IN ('PROCESSING', 'RETRYABLE')";

    static final String RELEASE_SQL =
            "UPDATE platform.ai_mq_consume_dedup SET state='RETRYABLE', updated_at=now(), last_error=? "
                    + "WHERE tenant_id=? AND dedup_key=? AND state='PROCESSING'";

    /** last_error 列宽 512；超长会插入失败，因此先截断（不截断＝把可诊断性换成一次写入异常）。 */
    private static final int ERROR_MAX = 512;

    private final JdbcTemplate jdbc;

    public PgOutboxMqConsumeLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ClaimOutcome claim(OutboxMqEnvelope envelope, int leaseSeconds) {
        List<String> rows = jdbc.query(CLAIM_SQL,
                (rs, rowNum) -> rs.getString("state"),
                envelope.getTenantId(), envelope.dedupKey(), kindOf(envelope), envelope.getEventId(),
                envelope.getEventType(), envelope.getRunId(), leaseSeconds);
        return rows.isEmpty() ? ClaimOutcome.DUPLICATE : ClaimOutcome.CLAIMED;
    }

    @Override
    public void markConsumed(String tenantId, String dedupKey) {
        jdbc.update(MARK_CONSUMED_SQL, tenantId, dedupKey);
    }

    @Override
    public void markRejected(String tenantId, String dedupKey, String reason) {
        jdbc.update(MARK_REJECTED_SQL, truncate(reason), tenantId, dedupKey);
    }

    @Override
    public void release(String tenantId, String dedupKey, String reason) {
        jdbc.update(RELEASE_SQL, truncate(reason), tenantId, dedupKey);
    }

    /** 去重键前缀与 kind 同源，避免"键说 op、kind 说 EVT"这种自相矛盾的行。 */
    private static String kindOf(OutboxMqEnvelope envelope) {
        return envelope.dedupKey().startsWith("op:") ? "OP" : "EVT";
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= ERROR_MAX ? value : value.substring(0, ERROR_MAX);
    }
}
