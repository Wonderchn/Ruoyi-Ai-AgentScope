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
 * WITHOUT WARRANTIES OR CONDITIONS OF OR ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.runtime;

import com.nageoffer.ai.ragent.authorization.FrozenTableDdl;
import com.nageoffer.ai.ragent.runtime.dao.OutboxDao;
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

/**
 * W3-6 A1 真库行为验证：outbox 认领的**存活锁过滤**在真实 PostgreSQL 上的行为。
 *
 * <p>负例有非空目标（§6.1-5）：先插入夹具行并确认它在库内（锚点），再对
 * "存活锁内的行"执行认领，断言认领集**不含**该行；同一行锁过期后再次认领
 * 必须命中（at-least-once 的崩溃补偿路径不被误伤）。修复前该测试的形态 1 会
 * 命中（锁失效）；修复后形态 1 空、形态 2 命中。
 *
 * <p>需要 {@code -Dragent.outbox.test.jdbc-url=jdbc:postgresql://host:port/db}；
 * 未提供时显式跳过（NOT_RUN，不算通过）。表形状从冻结迁移抽出（{@link FrozenTableDdl}）。
 */
@Tag("dev")
@EnabledIfSystemProperty(named = OutboxClaimLeasePostgresTest.URL_PROPERTY, matches = ".+",
        disabledReason = "需要隔离 PostgreSQL：-D" + OutboxClaimLeasePostgresTest.URL_PROPERTY
                + "=jdbc:postgresql://host:port/db")
class OutboxClaimLeasePostgresTest {

    static final String URL_PROPERTY = "ragent.outbox.test.jdbc-url";

    private static final String TENANT = "T-OUTBOX";
    private static final String LIVE_LOCKED = "evt-live-locked";
    private static final String EXPIRED_LOCKED = "evt-expired-locked";
    private static final String NEVER_LOCKED = "evt-never-locked";

    private static JdbcTemplate jdbc;
    private static OutboxDao dao;

    @BeforeAll
    static void setUp() throws IOException {
        String url = System.getProperty(URL_PROPERTY);
        assertThat(url).as("缺少 -D" + URL_PROPERTY).isNotBlank();
        DriverManagerDataSource ds = new DriverManagerDataSource(url, "postgres", "");
        jdbc = new JdbcTemplate(ds);
        dao = new OutboxDao(jdbc);

        jdbc.execute("CREATE SCHEMA IF NOT EXISTS platform");
        jdbc.execute("DROP TABLE IF EXISTS platform.outbox_event");
        for (String ddl : FrozenTableDdl.forTable("outbox_event")) {
            jdbc.execute(ddl);
        }
        // 形状锚点：认领语义依赖的列必须全部来自冻结迁移（缺一即停止，不猜）
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema='platform'"
                        + " AND table_name='outbox_event'", String.class);
        assertThat(columns).as("冻结形状的 outbox_event 列")
                .contains("state", "next_attempt_at", "locked_by", "locked_until", "attempt_count",
                        "payload", "seq", "event_type", "run_id");

        jdbc.update("DELETE FROM platform.outbox_event");
        insertOutbox(LIVE_LOCKED, /*lockAlive*/ true);
        insertOutbox(EXPIRED_LOCKED, /*lockAlive*/ false);
        insertOutbox(NEVER_LOCKED, /*lockAlive*/ false);
        jdbc.update("UPDATE platform.outbox_event SET locked_until = now() + interval '10 minutes',"
                        + " locked_by = 'relay-other' WHERE event_id = ?", LIVE_LOCKED);
        jdbc.update("UPDATE platform.outbox_event SET locked_until = now() - interval '1 minute',"
                        + " locked_by = 'relay-other' WHERE event_id = ?", EXPIRED_LOCKED);

        // 夹具锚点（防空集合恒真）：三行夹具必须真实在库且状态符合设计
        Integer live = jdbc.queryForObject(
                "SELECT count(*) FROM platform.outbox_event WHERE event_id=? AND state='PENDING'"
                        + " AND locked_until > now()", Integer.class, LIVE_LOCKED);
        Integer expired = jdbc.queryForObject(
                "SELECT count(*) FROM platform.outbox_event WHERE event_id=? AND state='PENDING'"
                        + " AND locked_until < now()", Integer.class, EXPIRED_LOCKED);
        Integer never = jdbc.queryForObject(
                "SELECT count(*) FROM platform.outbox_event WHERE event_id=? AND state='PENDING'"
                        + " AND locked_until IS NULL", Integer.class, NEVER_LOCKED);
        assertThat(live).as("存活锁夹具行在库").isEqualTo(1);
        assertThat(expired).as("过期锁夹具行在库").isEqualTo(1);
        assertThat(never).as("无锁夹具行在库").isEqualTo(1);
    }

    private static void insertOutbox(String eventId, boolean lockAlive) {
        jdbc.update("INSERT INTO platform.outbox_event (tenant_id, event_id, run_id, event_type, payload, state)"
                        + " VALUES (?, ?, 'run-outbox-1', 'run.status', ?::jsonb, 'PENDING')",
                TENANT, eventId, "{\"n\":1}");
    }

    @Test
    @DisplayName("认领：存活锁内的行不被认领；过期锁行与无锁行被认领（一次认领可同时命中后两者）")
    void claimSkipsLiveLockedRowAndClaimsTheOthers() {
        List<OutboxDao.OutboxRow> claimed = dao.claim("relay-test", 30, 8);

        List<String> ids = claimed.stream().map(OutboxDao.OutboxRow::eventId).toList();
        assertThat(ids)
                .as("认领集必须包含过期锁行与无锁行、且**不含**存活锁行（修复前会包含全部三行 = 锁失效）")
                .contains(EXPIRED_LOCKED, NEVER_LOCKED)
                .doesNotContain(LIVE_LOCKED);

        // 幂等证据：被认领行带上了新锁——下次认领（锁存活期内）不再出现
        if (!ids.isEmpty()) {
            List<OutboxDao.OutboxRow> second = dao.claim("relay-test", 30, 8);
            List<String> secondIds = second.stream().map(OutboxDao.OutboxRow::eventId).toList();
            assertThat(secondIds)
                    .as("刚被认领的行在锁存活期内不得被再次认领（认领即上锁的闭环）")
                    .doesNotContainAnyElementsOf(ids);
        }
    }

    @Test
    @DisplayName("锁过期补偿：对已被本测试锁定的行，把锁拨到过期后必须可被再次认领")
    void expiredLockRowBecomesClaimableAgain() {
        // 用一个独立事件做锁过期补偿路径（不依赖上一测试的残留状态）
        String eventId = "evt-expire-compensation";
        jdbc.update("INSERT INTO platform.outbox_event (tenant_id, event_id, run_id, event_type, payload, state)"
                        + " VALUES (?, ?, 'run-outbox-2', 'run.status', '{\"n\":2}'::jsonb, 'PENDING') ON CONFLICT DO NOTHING",
                TENANT, eventId);
        jdbc.update("UPDATE platform.outbox_event SET locked_until = now() + interval '10 minutes',"
                + " locked_by = 'relay-a' WHERE event_id = ?", eventId);

        assertThat(dao.claim("relay-b", 30, 8).stream()
                .map(OutboxDao.OutboxRow::eventId).toList())
                .as("锁存活期内不得认领（负例目标存在性：该行确实被锁着）")
                .doesNotContain(eventId);

        jdbc.update("UPDATE platform.outbox_event SET locked_until = now() - interval '1 minute'"
                + " WHERE event_id = ?", eventId);

        assertThat(dao.claim("relay-b", 30, 8).stream()
                .map(OutboxDao.OutboxRow::eventId).toList())
                .as("锁过期后同一行必须可被再次认领（at-least-once 补偿路径）")
                .contains(eventId);
    }
}
