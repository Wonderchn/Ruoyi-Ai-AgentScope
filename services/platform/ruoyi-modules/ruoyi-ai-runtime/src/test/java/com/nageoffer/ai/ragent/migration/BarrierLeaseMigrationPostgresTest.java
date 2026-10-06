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
package com.nageoffer.ai.ragent.migration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W3-1 (T1): V22 timestamp-anomaly repair + V23 barrier lease columns — real-database
 * criteria for the DATA layer of the barrier self-healing contract.
 *
 * <p>Why this layer matters: the Java-side lease CAS (T2r) is only sound if the DB shape
 * and the migration semantics are exactly as aligned. These criteria pin:
 * <ol>
 *   <li>the V23 columns exist on the frozen barrier shape (frozen DDL + chain ALTERs,
 *       extracted verbatim — never a hand-written copy, same rule as FrozenTableDdl);</li>
 *   <li>the aligned lease-CAS semantics on the DB side: expired lease + empty active set
 *       reclaims; unexpired lease never reclaims; an unexpired ACTIVE permit blocks;</li>
 *   <li>the V22 narrow repair predicate with before/after deltas and a healthy control.</li>
 * </ol>
 *
 * <p>Every assertion is anchored: fixtures are seeded with pinned literal timestamps and
 * the fixture presence is itself asserted, so an empty database cannot pass vacuously.
 * Negative evidence: each criterion was proven to fail against mutated copies of the
 * migration (repair disabled / columns stripped / ABANDONED wrongly synced) — see
 * the WP-027 run-20 evidence directory.
 *
 * <p>Needs {@code -Dragent.w3migrations.test.jdbc-url=...}; when absent the class is
 * SKIPPED with an explicit reason, never silently green.
 */
@Tag("dev")
@EnabledIfSystemProperty(named = BarrierLeaseMigrationPostgresTest.URL_PROPERTY, matches = ".+",
        disabledReason = "需要隔离 PostgreSQL：-D" + BarrierLeaseMigrationPostgresTest.URL_PROPERTY
                + "=jdbc:postgresql://host:port/db")
class BarrierLeaseMigrationPostgresTest {

    static final String URL_PROPERTY = "ragent.w3migrations.test.jdbc-url";

    private static final String V22_FILE = "V22__wp027_timestamp_anomaly_repair.sql";
    private static final String V23_FILE = "V23__tenant_barrier_lease_expiry.sql";

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setUp() throws IOException {
        String url = System.getProperty(URL_PROPERTY);
        assertThat(url).as("缺少 -D" + URL_PROPERTY).isNotBlank();
        DriverManagerDataSource ds = new DriverManagerDataSource(url, "postgres", "");
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("CREATE SCHEMA IF NOT EXISTS platform");
        // rebuild the frozen shapes this migration chain touches; child tables first is
        // unnecessary here (no FKs between the three), but keep the order deterministic
        jdbc.execute("DROP TABLE IF EXISTS platform.ai_wp027_timestamp_anomaly CASCADE");
        jdbc.execute("DROP TABLE IF EXISTS platform.ai_tenant_barrier CASCADE");
        jdbc.execute("DROP TABLE IF EXISTS platform.ai_execution_permit CASCADE");
        jdbc.execute("DROP TABLE IF EXISTS platform.ai_acl_epoch CASCADE");
        for (String ddl : frozenDdl("ai_tenant_barrier", "ai_execution_permit", "ai_acl_epoch")) {
            jdbc.execute(ddl);
        }
        // shape anchor: the frozen columns must be exactly what the extractor found
        List<String> barrierColumns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema='platform'"
                        + " AND table_name='ai_tenant_barrier' ORDER BY column_name", String.class);
        assertThat(barrierColumns).as("冻结 V7 里 ai_tenant_barrier 的形状")
                .contains("tenant_id", "status", "barrier_id", "target_acl_version", "reason", "updated_at")
                .doesNotContain("lease_expires_at", "attempt_count", "reconciled_at", "reconciled_by");

        // apply the chain under test, in order: V22 then V23 (V23 asserts its own columns,
        // so a broken extractor cannot get past this line)
        jdbc.execute(migrationSql(V22_FILE));
        jdbc.execute(migrationSql(V23_FILE));
    }

    @Test
    @DisplayName("V23 形状：四列 + 租约索引落到冻结形状上；本表 status 集合不含 ABANDONED")
    void v23ColumnsAndIndexExistAndAbandonedStaysOutOfTheAiTable() {
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema='platform'"
                        + " AND table_name='ai_tenant_barrier'", String.class);
        assertThat(columns).as("V23 增列")
                .contains("lease_expires_at", "attempt_count", "reconciled_at", "reconciled_by");

        String leaseType = jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_schema='platform'"
                        + " AND table_name='ai_tenant_barrier' AND column_name='lease_expires_at'", String.class);
        assertThat(leaseType).as("lease_expires_at 类型").isEqualTo("timestamp with time zone");

        Integer indexCount = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE schemaname='platform' AND tablename='ai_tenant_barrier'"
                        + " AND indexname='idx_ai_tenant_barrier_lease'", Integer.class);
        assertThat(indexCount).as("租约索引恰好一个").isEqualTo(1);

        String statusCheck = jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='platform.ai_tenant_barrier'::regclass"
                        + " AND conname='ck_ai_tenant_barrier_status'", String.class);
        assertThat(statusCheck).as("AI 侧 status 约束定义").isNotNull().doesNotContain("ABANDONED");
    }

    @Test
    @DisplayName("判据①：PENDING + 租约已过 + 无未过期 ACTIVE permit ⇒ CAS 收回 OPEN 并留 reconciled 取证")
    void expiredLeaseWithEmptyActiveSetIsReclaimed() {
        seedTenant("T-C1");
        seedBarrier("T-C1", "c1-barrier", "2020-01-01 00:00:00+08"); // long-expired lease
        assertThat(barrierStatus("T-C1")).isEqualTo("PENDING");

        int reclaimed = leaseCas("T-C1", "c1-barrier");
        assertThat(reclaimed).as("过期租约 + 空活跃集必须恰好收回 1 行").isEqualTo(1);
        assertThat(barrierStatus("T-C1")).isEqualTo("OPEN");
        assertThat(reconciledBy("T-C1")).isEqualTo("lease-reconciler");
        assertThat(reconciledAt("T-C1")).as("reconciled_at 必须被写入").isNotNull();
    }

    @Test
    @DisplayName("判据②：租约未过 ⇒ CAS 绝不回收（status 保持 PENDING、reconciled 列不动）")
    void unexpiredLeaseIsNeverReclaimed() {
        seedTenant("T-C2");
        seedBarrier("T-C2", "c2-barrier", "2030-01-01 00:00:00+08"); // far-future lease
        int reclaimed = leaseCas("T-C2", "c2-barrier");
        assertThat(reclaimed).as("租约未过必须 0 行被改").isZero();
        assertThat(barrierStatus("T-C2")).as("保护不沿时间轴自己消失").isEqualTo("PENDING");
        assertThat(reconciledAt("T-C2")).as("未收回时 reconciled_at 必须仍为 NULL").isNull();
    }

    @Test
    @DisplayName("判据③：租约已过但存在未过期 ACTIVE permit ⇒ 不回收（并对照释放后可回收）")
    void activePermitBlocksReclaimUntilItIsGone() {
        seedTenant("T-C3");
        seedBarrier("T-C3", "c3-barrier", "2020-01-01 00:00:00+08");
        seedPermit("c3-blocker", "T-C3", "2030-01-01 00:00:00+08"); // unexpired blocker

        int blocked = leaseCas("T-C3", "c3-barrier");
        assertThat(blocked).as("未过期 permit 在场时必须 0 行被改").isZero();
        assertThat(barrierStatus("T-C3")).isEqualTo("PENDING");

        jdbc.update("UPDATE platform.ai_execution_permit SET status='RELEASED', released_at=now()"
                + " WHERE permit_id='c3-blocker'");
        int reclaimedAfterRelease = leaseCas("T-C3", "c3-barrier");
        assertThat(reclaimedAfterRelease).as("blocker 释放后同一 CAS 必须收回").isEqualTo(1);
        assertThat(barrierStatus("T-C3")).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("V22：错乱行被重置为 acquired_at+300s、台账恰两条、健康对照行不动")
    void v22RepairsOnlyTheAnomalousRowsAndLedgersThem() throws IOException {
        seedTenant("T-V22");
        // healthy control: a correct single-clock lease
        seedPermitWithTimestamps("v22-healthy", "T-V22",
                "2026-10-06 07:00:00+08", "2026-10-06 07:05:00+08");
        // anomaly A: expires before acquired (the observed two-clock damage)
        seedPermitWithTimestamps("v22-anomaly-a", "T-V22",
                "2026-10-06 15:00:00+08", "2026-10-06 07:05:00+08");
        // anomaly B: far-future lease no writer in the repo produces
        seedPermitWithTimestamps("v22-anomaly-b", "T-V22",
                "2026-10-06 07:00:00+08", "2026-10-07 07:00:00+08");

        Integer anomaliesBefore = jdbc.queryForObject(anomalyCountSql(), Integer.class, "T-V22");
        assertThat(anomaliesBefore).as("修复前：该租户异常行恰 3（A 类×1 + B 类×1 + 健康×0，其中 A/B 必须在场）")
                .isEqualTo(2);

        jdbc.execute(migrationSql(V22_FILE)); // idempotent re-run on a live database

        Integer anomaliesAfter = jdbc.queryForObject(anomalyCountSql(), Integer.class, "T-V22");
        assertThat(anomaliesAfter).as("修复后：异常必须归零（前后增量口径）").isZero();

        Integer healthyUntouched = jdbc.queryForObject(
                "SELECT count(*) FROM platform.ai_execution_permit"
                        + " WHERE permit_id='v22-healthy'"
                        + " AND expires_at = '2026-10-06 07:05:00+08'::timestamptz"
                        + " AND acquired_at = '2026-10-06 07:00:00+08'::timestamptz",
                Integer.class);
        assertThat(healthyUntouched).as("健康对照行的两个时间戳必须逐字未动（SQL 侧字面量比较，不受 JVM 时区影响）")
                .isEqualTo(1);

        Integer repairedCorrectly = jdbc.queryForObject(
                "SELECT count(*) FROM platform.ai_execution_permit"
                        + " WHERE permit_id='v22-anomaly-a'"
                        + " AND expires_at = acquired_at + interval '300 seconds'"
                        + " AND acquired_at = '2026-10-06 15:00:00+08'::timestamptz",
                Integer.class);
        assertThat(repairedCorrectly).as("错乱行：acquired_at 保持原值，expires_at 重置为 acquired_at+300s")
                .isEqualTo(1);

        Integer ledgerRows = jdbc.queryForObject(
                "SELECT count(*) FROM platform.ai_wp027_timestamp_anomaly WHERE row_key IN"
                        + " ('v22-anomaly-a','v22-anomaly-b')", Integer.class);
        assertThat(ledgerRows).as("台账必须为两条异常行各留一条原值记录").isEqualTo(2);
    }

    // ------------------------------------------------------------ helpers

    private static final String CAS =
            "UPDATE platform.ai_tenant_barrier SET status='OPEN', updated_at=now(),"
                    + " reconciled_at=now(), reconciled_by='lease-reconciler'"
                    + " WHERE tenant_id=? AND barrier_id=? AND status='PENDING'"
                    + " AND (lease_expires_at IS NULL OR lease_expires_at <= now())"
                    + " AND NOT EXISTS(SELECT 1 FROM platform.ai_execution_permit p"
                    + " WHERE p.tenant_id=? AND p.status='ACTIVE'"
                    + " AND p.expires_at>CURRENT_TIMESTAMP)";

    private int leaseCas(String tenant, String barrier) {
        return jdbc.update(CAS, tenant, barrier, tenant);
    }

    private String barrierStatus(String tenant) {
        return jdbc.queryForObject(
                "SELECT status FROM platform.ai_tenant_barrier WHERE tenant_id=?", String.class, tenant);
    }

    private String reconciledBy(String tenant) {
        return jdbc.queryForObject(
                "SELECT reconciled_by FROM platform.ai_tenant_barrier WHERE tenant_id=?", String.class, tenant);
    }

    private java.sql.Timestamp reconciledAt(String tenant) {
        return jdbc.queryForObject(
                "SELECT reconciled_at FROM platform.ai_tenant_barrier WHERE tenant_id=?",
                java.sql.Timestamp.class, tenant);
    }

    private void seedTenant(String tenant) {
        jdbc.update("INSERT INTO platform.ai_acl_epoch (tenant_id, version) VALUES (?, 1)", tenant);
    }

    private void seedBarrier(String tenant, String barrierId, String leaseExpires) {
        jdbc.update("INSERT INTO platform.ai_tenant_barrier"
                        + " (tenant_id, status, barrier_id, reason, updated_at, lease_expires_at, attempt_count)"
                        + " VALUES (?, 'PENDING', ?, 'criteria fixture', now(), ?::timestamptz, 1)",
                tenant, barrierId, leaseExpires);
    }

    private void seedPermit(String permitId, String tenant, String expiresAt) {
        jdbc.update("INSERT INTO platform.ai_execution_permit"
                        + " (permit_id, tenant_id, member_id, action, policy_version, acl_version,"
                        + " resource_refs_hash, operation_id, status, acquired_at, expires_at)"
                        + " VALUES (?, ?, ?, 'kb.write', 1, 1, 'hash', ?, 'ACTIVE', now(), ?::timestamptz)",
                permitId, tenant, "platform:" + tenant + ":1", permitId + "-op", expiresAt);
    }

    private void seedPermitWithTimestamps(String permitId, String tenant, String acquiredAt, String expiresAt) {
        jdbc.update("INSERT INTO platform.ai_execution_permit"
                        + " (permit_id, tenant_id, member_id, action, policy_version, acl_version,"
                        + " resource_refs_hash, operation_id, status, acquired_at, expires_at)"
                        + " VALUES (?, ?, ?, 'kb.write', 1, 1, 'hash', ?, 'ACTIVE', ?::timestamptz, ?::timestamptz)",
                permitId, tenant, "platform:" + tenant + ":1", permitId + "-op", acquiredAt, expiresAt);
    }

    private String anomalyCountSql() {
        return "SELECT count(*) FROM platform.ai_execution_permit p"
                + " WHERE p.tenant_id=? AND p.status='ACTIVE'"
                + " AND (p.expires_at < p.acquired_at - interval '60 seconds'"
                + "   OR p.expires_at > p.acquired_at + interval '3600 seconds')";
    }

    /** Reads the whole migration file verbatim; no statement is ever rewritten here. */
    private static String migrationSql(String fileName) throws IOException {
        Path sqlDir = Path.of("services", "platform", "docs", "script", "sql", "postgres");
        Path file = sqlDir.resolve(fileName);
        assertThat(Files.exists(file)).as("迁移文件存在: " + fileName).isTrue();
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /**
     * Verbatim frozen CREATE TABLE statements for the given tables from V7 (the shape
     * authority), mirroring the project's FrozenTableDdl rule: executed DDL must be the
     * frozen shape itself, never a hand-written copy.
     */
    static List<String> frozenDdl(String... tables) throws IOException {
        Path sqlDir = Path.of("services", "platform", "docs", "script", "sql", "postgres");
        String v7 = Files.readString(sqlDir.resolve("V7__unified_ai_domain.sql"), StandardCharsets.UTF_8);
        List<String> out = new java.util.ArrayList<>();
        for (String table : tables) {
            Pattern p = Pattern.compile(
                    "CREATE\\s+TABLE(?:\\s+IF\\s+NOT\\s+EXISTS)?\\s+platform\\." + table + "\\s*\\([^;]*?\\)\\s*;",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher m = p.matcher(v7);
            assertThat(m.find()).as("V7 里必须能找到 platform." + table + " 的建表语句").isTrue();
            out.add(m.group());
        }
        return out;
    }
}
