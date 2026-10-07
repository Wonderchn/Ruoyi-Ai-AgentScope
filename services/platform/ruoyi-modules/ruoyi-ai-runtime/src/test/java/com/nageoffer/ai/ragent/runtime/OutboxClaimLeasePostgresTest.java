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

package com.nageoffer.ai.ragent.runtime;

import com.nageoffer.ai.ragent.authorization.FrozenTableDdl;
import com.nageoffer.ai.ragent.runtime.dao.OutboxDao;
import org.junit.jupiter.api.AfterAll;
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
 * <p><b>隔离形态（W3-T0 排障三轮后的定案，v4）：</b>测试使用**自足 schema**
 * （{@code w3t2r_fix}）——在共享真库上跑判据必须与"活进程 + 其他成员夹具"双向隔离：
 * <ul>
 *   <li><b>教训（形状合规 ≠ 语义合规）</b>：FrozenTableDdl 只抽 DDL，抽不到**活进程语义**——
 *       本测试 v2 曾把夹具行插进共享 {@code platform.outbox_event}，被 relay-enabled=true
 *       的双实例 OutboxRelay 在 1s 轮询内认领投递成 PUBLISHED（残留行 published_at 紧贴
 *       插入时刻可冻证）；静态触发器假设经证据排除（全迁移目录 outbox_event 上无
 *       TRIGGER/RULE）。"插进去的行会自己变状态"= 表上有活消费者，判据库表必须自足；</li>
 *   <li>schema 内表由 FrozenTableDdl 形状构建（{@code platform.} 前缀替换为本 schema）；</li>
 *   <li>连接 search_path 钉到本 schema ⇒ OutboxDao 的无前缀 SQL 全部落在这里；
 *       共享库 relay 走默认 search_path（platform），**双向互不见**；</li>
 *   <li>收尾 {@code @AfterAll} DROP SCHEMA CASCADE 自清；夹具 event_id 仍带运行唯一段
 *       （防同 schema 内残留交互）。</li>
 * </ul>
 *
 * <p>负例有非空目标（§6.1-5）：夹具三行先断言在库（锚点），再对"存活锁内的行"执行认领，
 * 断言认领集不含该行；同一行锁过期后再次认领必须命中（at-least-once 补偿路径）。
 * 修复前形态 1 会命中（锁失效）；修复后形态 1 空、形态 2 命中。
 *
 * <p>需要 {@code -Dragent.outbox.test.jdbc-url=jdbc:postgresql://host:port/db}（运行角色
 * 需要目标库的 CREATE 权限以建自足 schema；无则 NOT_RUN）；未提供时显式跳过。
 */
@Tag("dev")
@EnabledIfSystemProperty(named = OutboxClaimLeasePostgresTest.URL_PROPERTY, matches = ".+",
        disabledReason = "需要隔离 PostgreSQL：-D" + OutboxClaimLeasePostgresTest.URL_PROPERTY
                + "=jdbc:postgresql://host:port/db")
class OutboxClaimLeasePostgresTest {

    static final String URL_PROPERTY = "ragent.outbox.test.jdbc-url";

    /** 自足 schema：本测试专属，建/清都由本类负责（CASCADE 自清）。 */
    private static final String FIXTURE_SCHEMA = "w3t2r_fix";
    /** 夹具 event_id 的运行唯一段：防同 schema 内残留交互。 */
    private static final String RUN_SUFFIX = "-" + java.util.UUID.randomUUID().toString()
            .replace("-", "").substring(0, 8);
    private static final String TENANT = "T-OUTBOX";
    private static final String LIVE_LOCKED = "w3t2r-live-locked" + RUN_SUFFIX;
    private static final String EXPIRED_LOCKED = "w3t2r-expired-locked" + RUN_SUFFIX;
    private static final String NEVER_LOCKED = "w3t2r-never-locked" + RUN_SUFFIX;
    private static final String COMPENSATION = "w3t2r-expire-compensation" + RUN_SUFFIX;

    private static JdbcTemplate jdbc;
    private static OutboxDao dao;
    private static org.springframework.jdbc.datasource.SingleConnectionDataSource sharedDs;

    @BeforeAll
    static void setUp() throws IOException, java.sql.SQLException {
        String url = System.getProperty(URL_PROPERTY);
        assertThat(url).as("缺少 -D" + URL_PROPERTY).isNotBlank();

        // 管理连接：建/清 schema 用（不受 search_path 影响）
        try (var admin = new org.springframework.jdbc.datasource.DriverManagerDataSource(url).getConnection();
             var statement = admin.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + FIXTURE_SCHEMA + " CASCADE");
            statement.execute("CREATE SCHEMA " + FIXTURE_SCHEMA);
        }

        // 测试连接：**单连接** + 连接级 search_path 钉死到自足 schema。
        // W3-T0 排障修正（v4→v5）：DriverManagerDataSource 无池 ⇒ 每次调用新连接 ⇒
        // 连接级 SET search_path 只活一瞬，OutboxDao 的无前缀 SQL 会落到默认 search_path
        // （v4 的 relation not exist 即此真因）。SingleConnectionDataSource 全程同连接 ⇒
        // SET 一次全局生效，OutboxDao 生产 SQL（无前缀，不可改）自然落进自足 schema。
        sharedDs = new org.springframework.jdbc.datasource.SingleConnectionDataSource(url, "", "", true);
        try (var connection = sharedDs.getConnection(); var statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + FIXTURE_SCHEMA);
        }
        jdbc = new JdbcTemplate(sharedDs);

        for (String ddl : FrozenTableDdl.forTable("outbox_event")) {
            jdbc.execute(ddl.replace("platform.outbox_event", FIXTURE_SCHEMA + ".outbox_event"));
        }
        dao = new OutboxDao(jdbc);

        // 形状校验（只读）：认领语义依赖的列必须全部在位（自建表也校验——防 DDL 抽取自身漂移）
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema=?"
                        + " AND table_name='outbox_event'", String.class, FIXTURE_SCHEMA);
        assertThat(columns).as("outbox_event 认领语义依赖的列（冻结形状，schema=" + FIXTURE_SCHEMA + "）")
                .contains("state", "next_attempt_at", "locked_by", "locked_until", "attempt_count",
                        "payload", "seq", "event_type", "run_id");

        // 夹具：三行基线（自足 schema 内无并发消费者，PENDING 会一直待到本测试来认领）
        insertOutbox(LIVE_LOCKED);
        insertOutbox(EXPIRED_LOCKED);
        insertOutbox(NEVER_LOCKED);
        int liveStamped = jdbc.update("UPDATE outbox_event SET locked_until = now() + interval '10 minutes',"
                + " locked_by = 'relay-other' WHERE event_id = ? AND state='PENDING'", LIVE_LOCKED);
        int expiredStamped = jdbc.update("UPDATE outbox_event SET locked_until = now() - interval '1 minute',"
                + " locked_by = 'relay-other' WHERE event_id = ? AND state='PENDING'", EXPIRED_LOCKED);
        assertThat(liveStamped).as("存活锁 stamp 必须真的改到本运行的行").isEqualTo(1);
        assertThat(expiredStamped).as("过期锁 stamp 必须真的改到本运行的行").isEqualTo(1);

        // 夹具锚点（防空集合恒真）：三行夹具必须真实在库且状态符合设计
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_id=? AND state='PENDING'"
                        + " AND locked_until > now()", Integer.class, LIVE_LOCKED)).as("存活锁夹具行在库").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_id=? AND state='PENDING'"
                        + " AND locked_until < now()", Integer.class, EXPIRED_LOCKED)).as("过期锁夹具行在库").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_id=? AND state='PENDING'"
                        + " AND locked_until IS NULL", Integer.class, NEVER_LOCKED)).as("无锁夹具行在库").isEqualTo(1);
    }

    @AfterAll
    static void tearDown() throws java.sql.SQLException {
        if (sharedDs == null) {
            return;
        }
        // 自清：整个 schema CASCADE（本测试建的一切都在里面；不碰共享对象）。
        // 先关单连接（DROP 需要无活动会话持有的锁；schema 内表锁由本连接持有），
        // 再用独立管理连接 DROP。
        sharedDs.destroy();
        try (var connection = new DriverManagerDataSource(System.getProperty(URL_PROPERTY)).getConnection();
             var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + FIXTURE_SCHEMA + " CASCADE");
        }
    }

    private static void insertOutbox(String eventId) {
        jdbc.update("INSERT INTO outbox_event (tenant_id, event_id, run_id, event_type, payload, state)"
                        + " VALUES (?, ?, ?, 'run.status', ?::jsonb, 'PENDING')",
                TENANT, eventId, "w3t2r-run", "{\"n\":1}");
    }

    @Test
    @DisplayName("认领：存活锁内的行不被认领；过期锁行与无锁行被认领（一次认领可同时命中后两者）")
    void claimSkipsLiveLockedRowAndClaimsTheOthers() {
        List<String> ids = dao.claim("relay-w3t2r", 30, 64).stream()
                .map(OutboxDao.OutboxRow::eventId).toList();

        assertThat(ids)
                .as("认领集必须包含本测试的过期锁行与无锁行、且**不含**存活锁行（修复前会包含全部 = 锁失效）")
                .contains(EXPIRED_LOCKED, NEVER_LOCKED)
                .doesNotContain(LIVE_LOCKED);

        // 幂等证据：认领集里的行已上锁——锁存活期内不再出现在下一次认领集
        List<String> second = dao.claim("relay-w3t2r", 30, 64).stream()
                .map(OutboxDao.OutboxRow::eventId).toList();
        assertThat(second)
                .as("刚被认领的行在锁存活期内不得被再次认领（认领即上锁的闭环）")
                .doesNotContainAnyElementsOf(ids);
    }

    @Test
    @DisplayName("锁过期补偿：锁存活期内拒绝认领，锁拨到过期后同一行可被再次认领")
    void expiredLockRowBecomesClaimableAgain() {
        jdbc.update("INSERT INTO outbox_event (tenant_id, event_id, run_id, event_type, payload, state)"
                        + " VALUES (?, ?, ?, 'run.status', '{\"n\":2}'::jsonb, 'PENDING')",
                TENANT, COMPENSATION, "w3t2r-run2");
        jdbc.update("UPDATE outbox_event SET locked_until = now() + interval '10 minutes',"
                + " locked_by = 'relay-a' WHERE event_id = ?", COMPENSATION);

        assertThat(dao.claim("relay-w3t2r", 30, 64).stream()
                .map(OutboxDao.OutboxRow::eventId).toList())
                .as("锁存活期内不得认领（负例目标存在性：该行确实被锁着）")
                .doesNotContain(COMPENSATION);

        jdbc.update("UPDATE outbox_event SET locked_until = now() - interval '1 minute'"
                + " WHERE event_id = ?", COMPENSATION);

        assertThat(dao.claim("relay-w3t2r", 30, 64).stream()
                .map(OutboxDao.OutboxRow::eventId).toList())
                .as("锁过期后同一行必须可被再次认领（at-least-once 补偿路径）")
                .contains(COMPENSATION);
    }
}
