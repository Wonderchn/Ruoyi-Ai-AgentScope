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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link OutboxMqReauthorizer} 的默认实现：**拿权威账本复核，而不是相信消息**。
 *
 * <p><b>重新授权在这里的落地形态是"重新读事实"。</b>MQ 消息只是**投递提示**，不是事实
 * （{@code OutboxTransport} 的契约原文：事件事实以 {@code ai_run_event} 为准）。
 * 因此消费端的第一件事是回权威表确认"这条事件确实属于这个租户"：
 *
 * <pre>
 * SELECT 1 FROM ai_run_event WHERE tenant_id=? AND run_id=? AND seq=? AND event_id=?
 * </pre>
 *
 * <p>这一条查询同时挡住三类真实故障：
 * <ol>
 *   <li><b>跨租户伪造/串写</b>：四个条件里 {@code tenant_id} 是硬条件，B 租户的消息
 *       即使在 A 租户的连接上被消费也命不中行（C6：原生 SQL 同样遵守边界）；</li>
 *   <li><b>生产端快照过期</b>：事件若已被清理/未落库，消费端拿不到事实就**拒绝**，
 *       而不是照生产端的说法执行；</li>
 *   <li><b>重复的 key 但不同的内容</b>：{@code event_id} 与 {@code seq} 同时比对，
 *       去重键相同但内容对不上的消息不会被执行。</li>
 * </ol>
 *
 * <p><b>为什么查询失败是 UNAVAILABLE 而不是 DENY。</b>数据库抖动与"事件不存在"是完全不同的事：
 * 前者等一会就恢复，后者永远不会。把前者记成 {@code REJECTED} 会把一次 DB 抖动
 * 变成一批事件的**永久丢弃** —— 这不是保守，是丢数据。
 *
 * <p><b>没有 run 归属的外部动作（{@code operationKey} 类）在本类里一律拒绝。</b>
 * 这类消息的授权不能用 run 事件存在性证明，而平台侧的"二次授权端口"尚未取得
 * （见 WP-043 报告 §6 未完成项与 G 项）。C6 的口径是**事实不可得即拒绝**，
 * 所以这里选择 DENY（记录后可查询）而不是 ALLOW 放行。等 T2/T4 提供该端口后，
 * 由 {@link #setExternalReauthorizer} 注入即可放开这一支。
 */
public class LedgerBackedOutboxMqReauthorizer implements OutboxMqReauthorizer {

    private static final Logger log = LoggerFactory.getLogger(LedgerBackedOutboxMqReauthorizer.class);

    static final String RUN_EVENT_EXISTS_SQL =
            "SELECT 1 FROM ai_run_event WHERE tenant_id=? AND run_id=? AND seq=? AND event_id=?";

    private final JdbcTemplate jdbc;

    /** 可选的外部动作二次授权端口（T2/T4 提供）；未设置时外部动作被拒绝。 */
    private volatile OutboxMqReauthorizer externalReauthorizer;

    public LedgerBackedOutboxMqReauthorizer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void setExternalReauthorizer(OutboxMqReauthorizer externalReauthorizer) {
        this.externalReauthorizer = externalReauthorizer;
    }

    @Override
    public Decision reauthorize(OutboxMqEnvelope envelope) {
        if (envelope.getRunId() == null || envelope.getRunId().isBlank() || envelope.getSeq() == null) {
            OutboxMqReauthorizer external = this.externalReauthorizer;
            if (external == null) {
                log.warn("外部业务动作缺少二次授权端口，按 C6 拒绝：tenant={}, eventId={}, operationKey={}",
                        envelope.getTenantId(), envelope.getEventId(), envelope.getOperationKey());
                return Decision.DENY;
            }
            return external.reauthorize(envelope);
        }
        try {
            Integer hit = jdbc.query(RUN_EVENT_EXISTS_SQL,
                    rs -> rs.next() ? 1 : null,
                    envelope.getTenantId(), envelope.getRunId(), envelope.sequenceOrZero(),
                    envelope.getEventId());
            return hit == null ? Decision.DENY : Decision.ALLOW;
        } catch (DataAccessException e) {
            // 事实暂时不可得：不记拒绝（那会永久丢弃），抛出让 broker 重投。
            throw new OutboxMqReauthorizationUnavailableException(
                    "重新授权失败：权威账本不可达。tenant=" + envelope.getTenantId()
                            + " eventId=" + envelope.getEventId(), e);
        }
    }
}
